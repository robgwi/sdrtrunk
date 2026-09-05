package io.github.dsheirer.web;

import io.github.dsheirer.gui.SDRTrunk;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Background OpenAI Whisper CLI processing for completed web-console calls. */
public class WebTranscriptionService
{
    private static final Logger mLog = LoggerFactory.getLogger(WebTranscriptionService.class);
    public static final String DEFAULT_PROMPT = "Police radio transcript. Unit numbers: Adam-12, Lincoln-42, " +
        "5-21, 3-14. CAD numbers: 2024-001234. License plates: 7ABC123. Addresses: 1234 Main Street. " +
        "10-codes: 10-4, 10-97, 10-8, 10-7. Phonetic: Adam, Boy, Charles, David, Edward, Frank, George, " +
        "Henry, Ida, John, King, Lincoln, Mary, Nora, Ocean, Paul, Queen, Robert, Sam, Tom, Union, Victor, " +
        "William, X-ray, Yellow, Zebra. Signal codes, badge numbers, and radio callsigns.";
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("\\b(?:\\+?1[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b");
    private static final Pattern DOB = Pattern.compile("(?i)\\b(?:date of birth|DOB|D\\.O\\.B\\.?)\\s*[: ]?\\s*\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}");
    private static final Pattern ADDRESS = Pattern.compile("(?i)\\b\\d{1,6}\\s+(?:[NSEW]\\s+)?[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?\\s+(?:Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Boulevard|Blvd|Highway|Hwy|Lane|Ln|Way|Court|Ct|Circle|Cir|Place|Pl)\\b");
    private static final Map<String,String> PHONETIC = Map.ofEntries(
        Map.entry("adam", "A"), Map.entry("boy", "B"), Map.entry("charles", "C"), Map.entry("david", "D"),
        Map.entry("edward", "E"), Map.entry("frank", "F"), Map.entry("george", "G"), Map.entry("henry", "H"),
        Map.entry("ida", "I"), Map.entry("john", "J"), Map.entry("king", "K"), Map.entry("lincoln", "L"),
        Map.entry("mary", "M"), Map.entry("nora", "N"), Map.entry("ocean", "O"), Map.entry("paul", "P"),
        Map.entry("queen", "Q"), Map.entry("robert", "R"), Map.entry("sam", "S"), Map.entry("tom", "T"),
        Map.entry("union", "U"), Map.entry("victor", "V"), Map.entry("william", "W"), Map.entry("x-ray", "X"),
        Map.entry("yellow", "Y"), Map.entry("zebra", "Z"));
    private static final Pattern PHONETIC_RUN = Pattern.compile("(?i)\\b(?:" + String.join("|", PHONETIC.keySet()) +
        ")(?:[ ,.-]+(?:" + String.join("|", PHONETIC.keySet()) + "))+\\b");
    private static final Pattern PHONETIC_TOKEN = Pattern.compile("(?i)" + String.join("|", PHONETIC.keySet()));
    private final Preferences mPreferences = Preferences.userNodeForPackage(SDRTrunk.class);
    private final ConcurrentLinkedDeque<Map<String,Object>> mTranscripts = new ConcurrentLinkedDeque<>();
    private final Semaphore mWorker = new Semaphore(1);
    private final java.util.concurrent.ExecutorService mExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    public Map<String,Object> settings()
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("enabled", mPreferences.getBoolean("sdrtrunk.whisper.enabled", false));
        value.put("executable", mPreferences.get("sdrtrunk.whisper.executable", "whisper"));
        value.put("model", mPreferences.get("sdrtrunk.whisper.model", "base.en"));
        value.put("language", mPreferences.get("sdrtrunk.whisper.language", "English"));
        value.put("task", mPreferences.get("sdrtrunk.whisper.task", "transcribe"));
        value.put("prompt", mPreferences.get("sdrtrunk.whisper.prompt", DEFAULT_PROMPT));
        value.put("normalize", mPreferences.getBoolean("sdrtrunk.whisper.normalize", true));
        value.put("redact", mPreferences.getBoolean("sdrtrunk.whisper.redact", false));
        value.put("timeoutSeconds", mPreferences.getInt("sdrtrunk.whisper.timeout", 180));
        value.put("city", mPreferences.get("sdrtrunk.whisper.city", ""));
        value.put("repository", "https://github.com/robgwi/whisper");
        value.put("busy", mWorker.availablePermits() == 0);
        return value;
    }

    public void update(Map<String,Object> values)
    {
        putBoolean(values, "enabled", "sdrtrunk.whisper.enabled");
        putBoolean(values, "normalize", "sdrtrunk.whisper.normalize");
        putBoolean(values, "redact", "sdrtrunk.whisper.redact");
        putString(values, "executable", "sdrtrunk.whisper.executable");
        putString(values, "model", "sdrtrunk.whisper.model");
        putString(values, "language", "sdrtrunk.whisper.language");
        putString(values, "task", "sdrtrunk.whisper.task");
        putString(values, "prompt", "sdrtrunk.whisper.prompt");
        putString(values, "city", "sdrtrunk.whisper.city");
        Object timeout = values.get("timeoutSeconds");
        if(timeout instanceof Number number) { mPreferences.putInt("sdrtrunk.whisper.timeout", Math.max(10, number.intValue())); }
    }

    private void putBoolean(Map<String,Object> values, String source, String key)
    { if(values.get(source) instanceof Boolean value) { mPreferences.putBoolean(key, value); } }
    private void putString(Map<String,Object> values, String source, String key)
    { if(values.get(source) instanceof String value) { mPreferences.put(key, value); } }

    public List<Map<String,Object>> transcripts() { return new ArrayList<>(mTranscripts); }

    public void submit(byte[] mp3, Map<String,Object> call)
    {
        if(!mPreferences.getBoolean("sdrtrunk.whisper.enabled", false)) { return; }
        mExecutor.execute(() -> process(mp3, call));
    }

    private void process(byte[] mp3, Map<String,Object> call)
    {
        Path directory = null;
        mWorker.acquireUninterruptibly();
        try
        {
            directory = Files.createTempDirectory("sdrtrunk-whisper-");
            Path audio = directory.resolve("call.mp3");
            Files.write(audio, mp3);
            String executable = mPreferences.get("sdrtrunk.whisper.executable", "whisper");
            String model = mPreferences.get("sdrtrunk.whisper.model", "base.en");
            List<String> command = new ArrayList<>(List.of(executable, audio.toString(), "--model", model,
                "--output_format", "txt", "--output_dir", directory.toString(), "--language",
                mPreferences.get("sdrtrunk.whisper.language", "English"), "--task",
                mPreferences.get("sdrtrunk.whisper.task", "transcribe"), "--initial_prompt",
                mPreferences.get("sdrtrunk.whisper.prompt", DEFAULT_PROMPT)));
            Path processLog = directory.resolve("whisper.log");
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(processLog.toFile()).start();
            int timeout = mPreferences.getInt("sdrtrunk.whisper.timeout", 180);
            if(!process.waitFor(timeout, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Whisper timed out"); }
            String output = Files.readString(processLog, StandardCharsets.UTF_8);
            if(process.exitValue() != 0) { throw new IllegalStateException(output); }
            String raw = Files.readString(directory.resolve("call.txt"), StandardCharsets.UTF_8).trim();
            String normalized = mPreferences.getBoolean("sdrtrunk.whisper.normalize", true) ? normalize(raw) : raw;
            Map<String,Object> item = new LinkedHashMap<>(call);
            item.put("time", Instant.now().toString());
            boolean redacted = mPreferences.getBoolean("sdrtrunk.whisper.redact", false);
            item.put("text", redacted ? redact(normalized) : normalized);
            if(!redacted) { item.put("raw", raw); }
            item.put("redacted", redacted);
            item.put("model", model);
            mTranscripts.addFirst(item);
            while(mTranscripts.size() > 200) { mTranscripts.pollLast(); }
        }
        catch(Exception e) { mLog.error("Background Whisper transcription failed", e); }
        finally
        {
            if(directory != null)
            {
                try(var files = Files.list(directory)) { files.forEach(path -> { try { Files.deleteIfExists(path); } catch(Exception ignored) {} }); }
                catch(Exception ignored) {}
                try { Files.deleteIfExists(directory); } catch(Exception ignored) {}
            }
            mWorker.release();
        }
    }

    static String normalize(String text)
    {
        Matcher phoneticMatcher = PHONETIC_RUN.matcher(text);
        StringBuilder phoneticResult = new StringBuilder();
        while(phoneticMatcher.find())
        {
            StringBuilder letters = new StringBuilder();
            Matcher tokenMatcher = PHONETIC_TOKEN.matcher(phoneticMatcher.group());
            while(tokenMatcher.find())
            { letters.append(PHONETIC.get(tokenMatcher.group().toLowerCase())); }
            phoneticMatcher.appendReplacement(phoneticResult, Matcher.quoteReplacement(letters.toString()));
        }
        phoneticMatcher.appendTail(phoneticResult);
        String value = phoneticResult.toString();
        for(int count = 6; count >= 3; count--)
        {
            StringBuilder pattern = new StringBuilder("\\b");
            StringBuilder replacement = new StringBuilder();
            for(int index = 1; index <= count; index++)
            { if(index > 1) { pattern.append("\\s+"); } pattern.append("(\\d)"); replacement.append("$").append(index); }
            pattern.append("\\b"); value = value.replaceAll(pattern.toString(), replacement.toString());
        }
        value = value.replaceAll("(?i)\\b([0-9])\\s*([A-Z]{2,3})\\s*([0-9]{3,4})\\b", "$1$2$3");
        return value.replaceAll("\\s{2,}", " ").trim();
    }

    static String redact(String text)
    {
        String value = SSN.matcher(text).replaceAll("[REDACTED:SSN]");
        value = PHONE.matcher(value).replaceAll("[REDACTED:PHONE]");
        value = DOB.matcher(value).replaceAll("[REDACTED:DOB]");
        return ADDRESS.matcher(value).replaceAll("[REDACTED:ADDRESS]");
    }
}
