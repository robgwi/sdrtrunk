package io.github.dsheirer.audio.broadcast.remote;

import com.google.gson.Gson;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.util.ThreadPool;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reliable completed-call uploader for user-configured HTTP API destinations. */
public class RemoteApiBroadcaster extends AbstractAudioBroadcaster<RemoteApiConfiguration>
{
    private static final Logger mLog = LoggerFactory.getLogger(RemoteApiBroadcaster.class);
    private static final Gson GSON = new Gson();
    private final Queue<QueuedCall> mQueue = new PriorityBlockingQueue<>(32,
        Comparator.comparingLong(QueuedCall::nextAttempt));
    private final HttpClient mHttpClient;
    private final Semaphore mUploadSlots;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mHeartbeatInFlight = new AtomicBoolean();
    private ScheduledFuture<?> mProcessor;
    private ScheduledFuture<?> mHeartbeatProcessor;
    private final SpeechProcessor mSpeechProcessor;
    private volatile long mLastHeartbeatAttempt;
    private volatile long mLastHeartbeatSuccess;
    private volatile String mLastHeartbeatError = "";

    public RemoteApiBroadcaster(RemoteApiConfiguration configuration)
    {
        super(configuration);
        Duration timeout = Duration.ofSeconds(configuration.getRequestTimeoutSeconds());
        mHttpClient = HttpClient.newBuilder().connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        mUploadSlots = new Semaphore(configuration.getMaximumConcurrentUploads());
        if(configuration.isOpenAiEnabled())
        {
            mSpeechProcessor = new OpenAiWhisperProcessor(configuration.getOpenAiKeyEnvironmentVariable(),
                configuration.isTranslateToEnglish(), timeout);
        }
        else if(configuration.getLocalWhisperExecutable() != null &&
            !configuration.getLocalWhisperExecutable().isBlank() && configuration.getLocalWhisperModel() != null &&
            !configuration.getLocalWhisperModel().isBlank())
        {
            mSpeechProcessor = new LocalWhisperProcessor(configuration.getLocalWhisperExecutable(),
                configuration.getLocalWhisperModel(), configuration.isTranslateToEnglish(), timeout);
        }
        else
        {
            mSpeechProcessor = path -> CompletableFuture.completedFuture(CallTranscription.none());
        }
    }

    @Override
    public void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            setBroadcastState(getBroadcastConfiguration().isHeartbeatEnabled() ? BroadcastState.CONNECTING :
                BroadcastState.CONNECTED);
            mProcessor = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::processQueue, 0, 250,
                TimeUnit.MILLISECONDS);
            if(getBroadcastConfiguration().isHeartbeatEnabled())
            {
                mHeartbeatProcessor = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::sendHeartbeat, 0,
                    getBroadcastConfiguration().getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void stop()
    {
        mRunning.set(false);
        if(mProcessor != null)
        {
            mProcessor.cancel(true);
            mProcessor = null;
        }
        if(mHeartbeatProcessor != null)
        {
            mHeartbeatProcessor.cancel(true);
            mHeartbeatProcessor = null;
        }
        dispose();
        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    @Override
    public void dispose()
    {
        QueuedCall call;
        while((call = mQueue.poll()) != null)
        {
            call.recording().removePendingReplay();
        }
    }

    @Override
    public int getAudioQueueSize()
    {
        return mQueue.size() + (getBroadcastConfiguration().getMaximumConcurrentUploads() -
            mUploadSlots.availablePermits());
    }

    @Override
    public void receive(AudioRecording recording)
    {
        mQueue.offer(new QueuedCall(recording, 0, System.currentTimeMillis()));
        queueChanged();
    }

    private void processQueue()
    {
        try
        {
            while(mRunning.get() && mUploadSlots.tryAcquire())
            {
                QueuedCall call = mQueue.peek();
                if(call == null || call.nextAttempt() > System.currentTimeMillis())
                {
                    mUploadSlots.release();
                    return;
                }
                mQueue.poll();
                queueChanged();
                if(System.currentTimeMillis() - call.recording().getStartTime() >
                    getBroadcastConfiguration().getMaximumRecordingAge())
                {
                    call.recording().removePendingReplay();
                    incrementAgedOffAudioCount();
                    broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                    mUploadSlots.release();
                    continue;
                }
                upload(call).whenComplete((ignored, error) ->
                {
                    try
                    {
                        if(error == null)
                        {
                            setBroadcastState(BroadcastState.CONNECTED);
                            incrementStreamedAudioCount();
                            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
                            call.recording().removePendingReplay();
                        }
                        else
                        {
                            retryOrFail(call, error);
                        }
                    }
                    finally
                    {
                        mUploadSlots.release();
                        queueChanged();
                    }
                });
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing remote call upload queue", e);
        }
    }

    private CompletableFuture<Void> upload(QueuedCall call)
    {
        AudioRecording recording = call.recording();
        CompletedCallMetadata metadata = CompletedCallMetadata.from(recording);
        return mSpeechProcessor.process(recording.getPath()).exceptionally(error ->
        {
            mLog.warn("Speech processing failed; uploading call without transcript: {}", error.getMessage());
            return CallTranscription.none();
        }).thenCompose(transcription ->
        {
            try
            {
                metadata.setTranscription(transcription);
                byte[] audio = Files.readAllBytes(recording.getPath());
                MultipartBody body = new MultipartBody()
                    .text("metadata", GSON.toJson(metadata))
                    .file("audio", recording.getPath().getFileName().toString(), "audio/mpeg", audio);
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(getBroadcastConfiguration().getHost()))
                    .timeout(Duration.ofSeconds(getBroadcastConfiguration().getRequestTimeoutSeconds()))
                    .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                    .header("User-Agent", "sdrtrunk")
                    .header("Idempotency-Key", metadata.getCallId())
                    .POST(body.publisher());
                addAuthentication(request);
                return mHttpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(response ->
                {
                    if(response.statusCode() < 200 || response.statusCode() >= 300)
                    {
                        throw new IllegalStateException("Remote API returned HTTP " + response.statusCode());
                    }
                });
            }
            catch(Exception e)
            {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private void sendHeartbeat()
    {
        if(!mRunning.get() || !mHeartbeatInFlight.compareAndSet(false, true))
        {
            return;
        }

        mLastHeartbeatAttempt = System.currentTimeMillis();
        try
        {
            Map<String,Object> heartbeat = new LinkedHashMap<>();
            heartbeat.put("event", "heartbeat");
            heartbeat.put("status", "online");
            heartbeat.put("connected", true);
            heartbeat.put("timestamp", mLastHeartbeatAttempt);
            heartbeat.put("timestampIso", Instant.ofEpochMilli(mLastHeartbeatAttempt).toString());
            heartbeat.put("application", "sdrtrunk");
            heartbeat.put("version", applicationVersion());
            heartbeat.put("destination", getBroadcastConfiguration().getName());
            heartbeat.put("hostname", hostname());
            heartbeat.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
            heartbeat.put("queuedCalls", getAudioQueueSize());

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(getBroadcastConfiguration().getHost()))
                .timeout(Duration.ofSeconds(getBroadcastConfiguration().getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", "sdrtrunk")
                .header("X-SDRTrunk-Event", "heartbeat")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(heartbeat)));
            addAuthentication(request);
            mHttpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> completeHeartbeat(response, error));
        }
        catch(Exception e)
        {
            completeHeartbeat(null, e);
        }
    }

    private void completeHeartbeat(HttpResponse<String> response, Throwable error)
    {
        try
        {
            if(!mRunning.get())
            {
                return;
            }

            if(error == null && response != null && response.statusCode() >= 200 && response.statusCode() < 300)
            {
                mLastHeartbeatSuccess = System.currentTimeMillis();
                mLastHeartbeatError = "";
                setBroadcastState(BroadcastState.CONNECTED);
            }
            else
            {
                String message = error != null ? rootMessage(error) :
                    "Remote API returned HTTP " + (response != null ? response.statusCode() : "unknown");
                mLastHeartbeatError = message;
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                mLog.warn("Remote API heartbeat failed for [{}]: {}", getBroadcastConfiguration().getName(), message);
            }
        }
        finally
        {
            mHeartbeatInFlight.set(false);
        }
    }

    private void addAuthentication(HttpRequest.Builder request)
    {
        String key = getBroadcastConfiguration().resolveApiKey();
        String header = getBroadcastConfiguration().getAuthenticationHeader();
        if(key != null && !key.isBlank() && header != null && !header.isBlank())
        {
            String prefix = getBroadcastConfiguration().getAuthenticationPrefix();
            request.header(header, (prefix != null ? prefix : "") + key);
        }
    }

    private static String hostname()
    {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch(Exception e) { return "unknown"; }
    }

    private static String applicationVersion()
    {
        String version = RemoteApiBroadcaster.class.getPackage().getImplementationVersion();
        return version != null && !version.isBlank() ? version : "development";
    }

    private static String rootMessage(Throwable error)
    {
        Throwable cause = error;
        while(cause.getCause() != null) { cause = cause.getCause(); }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    public long getLastHeartbeatAttempt() { return mLastHeartbeatAttempt; }
    public long getLastHeartbeatSuccess() { return mLastHeartbeatSuccess; }
    public String getLastHeartbeatError() { return mLastHeartbeatError; }

    private void retryOrFail(QueuedCall call, Throwable error)
    {
        setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
        int nextAttempt = call.attempt() + 1;
        if(nextAttempt <= getBroadcastConfiguration().getMaximumRetries() && mRunning.get())
        {
            long delay = Math.min(300_000L, 1_000L << Math.min(nextAttempt - 1, 18));
            long jitter = (long)(Math.random() * Math.max(1, delay / 4));
            mQueue.offer(new QueuedCall(call.recording(), nextAttempt, System.currentTimeMillis() + delay + jitter));
            mLog.warn("Remote call upload failed; retry {}/{} scheduled: {}", nextAttempt,
                getBroadcastConfiguration().getMaximumRetries(), error.getMessage());
        }
        else
        {
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            call.recording().removePendingReplay();
            mLog.error("Remote call upload permanently failed after {} attempts", nextAttempt, error);
        }
    }

    private void queueChanged()
    {
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
    }

    private record QueuedCall(AudioRecording recording, int attempt, long nextAttempt) {}
}
