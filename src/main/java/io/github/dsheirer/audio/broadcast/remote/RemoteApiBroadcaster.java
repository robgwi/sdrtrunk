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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reliable completed-call uploader for user-configured HTTP API destinations. */
public class RemoteApiBroadcaster extends AbstractAudioBroadcaster<RemoteApiConfiguration>
{
    private static final Logger mLog = LoggerFactory.getLogger(RemoteApiBroadcaster.class);
    private static final Gson GSON = new Gson();
    private final Queue<QueuedCall> mQueue = new PriorityBlockingQueue<>(32,
        Comparator.comparingLong(QueuedCall::nextAttempt));
    private final Duration mRequestTimeout;
    private final Object mConnectionLock = new Object();
    private volatile HttpClient mHttpClient;
    private final Semaphore mUploadSlots;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mHeartbeatInFlight = new AtomicBoolean();
    private final AtomicInteger mConsecutiveConnectionFailures = new AtomicInteger();
    private final AtomicInteger mReconnectCount = new AtomicInteger();
    private final AtomicLong mHttpClientGeneration = new AtomicLong();
    private ScheduledFuture<?> mProcessor;
    private ScheduledFuture<?> mHeartbeatProcessor;
    private volatile CompletableFuture<HttpResponse<String>> mHeartbeatRequest;
    private final SpeechProcessor mSpeechProcessor;
    private volatile long mStarted;
    private volatile long mNextHeartbeatAttempt;
    private volatile long mLastHeartbeatAttempt;
    private volatile long mLastHeartbeatSuccess;
    private volatile String mLastHeartbeatError = "";
    private volatile long mLastCallAttempt;
    private volatile long mLastCallSuccess;
    private volatile String mLastCallError = "";
    private volatile long mLastContactAttempt;
    private volatile long mLastContactSuccess;
    private volatile long mLastReconnect;

    public RemoteApiBroadcaster(RemoteApiConfiguration configuration)
    {
        super(configuration);
        mRequestTimeout = Duration.ofSeconds(configuration.getRequestTimeoutSeconds());
        mHttpClient = createHttpClient();
        mUploadSlots = new Semaphore(configuration.getMaximumConcurrentUploads());
        if(configuration.isOpenAiEnabled())
        {
            mSpeechProcessor = new OpenAiWhisperProcessor(configuration.getOpenAiKeyEnvironmentVariable(),
                configuration.isTranslateToEnglish(), mRequestTimeout);
        }
        else if(configuration.getLocalWhisperExecutable() != null &&
            !configuration.getLocalWhisperExecutable().isBlank() && configuration.getLocalWhisperModel() != null &&
            !configuration.getLocalWhisperModel().isBlank())
        {
            mSpeechProcessor = new LocalWhisperProcessor(configuration.getLocalWhisperExecutable(),
                configuration.getLocalWhisperModel(), configuration.isTranslateToEnglish(), mRequestTimeout);
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
            mStarted = System.currentTimeMillis();
            mNextHeartbeatAttempt = mStarted;
            setBroadcastState(getBroadcastConfiguration().isHeartbeatEnabled() ? BroadcastState.CONNECTING :
                BroadcastState.CONNECTED);
            mProcessor = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::processQueue, 0, 250,
                TimeUnit.MILLISECONDS);
            if(getBroadcastConfiguration().isHeartbeatEnabled())
            {
                //Run a short watchdog interval so that failed or stale requests retry sooner than the normal heartbeat
                //interval.  The due timestamp still controls the configured cadence when the connection is healthy.
                mHeartbeatProcessor = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::monitorHeartbeat, 0, 1,
                    TimeUnit.SECONDS);
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
        CompletableFuture<HttpResponse<String>> heartbeatRequest = mHeartbeatRequest;
        if(heartbeatRequest != null)
        {
            heartbeatRequest.cancel(true);
            mHeartbeatRequest = null;
        }
        mHeartbeatInFlight.set(false);
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
                CompletableFuture<Void> upload;
                try
                {
                    upload = upload(call);
                }
                catch(Exception e)
                {
                    //A synchronous preparation failure must not permanently consume a semaphore permit.
                    try
                    {
                        retryOrFail(call, e);
                    }
                    finally
                    {
                        mUploadSlots.release();
                        queueChanged();
                    }
                    continue;
                }
                upload.whenComplete((ignored, error) ->
                {
                    try
                    {
                        if(error == null)
                        {
                            connectionSucceeded();
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
                    .timeout(mRequestTimeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                    .header("User-Agent", "sdrtrunk")
                    .header("Idempotency-Key", metadata.getCallId())
                    .POST(body.publisher());
                addAuthentication(request);
                mLastCallAttempt = System.currentTimeMillis();
                mLastContactAttempt = mLastCallAttempt;
                HttpClient client = mHttpClient;
                return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(response ->
                {
                    if(response.statusCode() < 200 || response.statusCode() >= 300)
                    {
                        throw new IllegalStateException(responseError(response));
                    }
                    mLastCallSuccess = System.currentTimeMillis();
                    mLastCallError = "";
                });
            }
            catch(Exception e)
            {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private void monitorHeartbeat()
    {
        try
        {
            if(!mRunning.get())
            {
                return;
            }

            long now = System.currentTimeMillis();
            if(isConnectionStale() && !mHeartbeatInFlight.get() && now - mLastReconnect > heartbeatIntervalMillis())
            {
                mLastHeartbeatError = "No successful contact within the heartbeat stale window";
                recoverConnection(mLastHeartbeatError);
                mNextHeartbeatAttempt = now;
            }

            if(now >= mNextHeartbeatAttempt)
            {
                sendHeartbeat();
            }
        }
        catch(Exception e)
        {
            mLastHeartbeatError = rootMessage(e);
            recoverConnection(mLastHeartbeatError);
            mNextHeartbeatAttempt = System.currentTimeMillis() + retryDelayMillis();
            mHeartbeatInFlight.set(false);
            mLog.error("Error monitoring Remote API heartbeat for [{}]",
                getBroadcastConfiguration().getName(), e);
        }
    }

    private void sendHeartbeat()
    {
        long clientGeneration;
        CompletableFuture<HttpResponse<String>> heartbeatRequest;
        Exception preparationError = null;

        synchronized(mConnectionLock)
        {
            if(!mRunning.get() || !mHeartbeatInFlight.compareAndSet(false, true))
            {
                return;
            }

            mLastHeartbeatAttempt = System.currentTimeMillis();
            mLastContactAttempt = mLastHeartbeatAttempt;
            clientGeneration = mHttpClientGeneration.get();
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

                HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create(getBroadcastConfiguration().getHost()))
                        .timeout(mRequestTimeout)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("User-Agent", "sdrtrunk")
                        .header("X-SDRTrunk-Event", "heartbeat")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(heartbeat)));
                addAuthentication(request);
                heartbeatRequest = mHttpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                    .orTimeout(mRequestTimeout.toMillis() + 1_000L, TimeUnit.MILLISECONDS);
                mHeartbeatRequest = heartbeatRequest;
            }
            catch(Exception e)
            {
                heartbeatRequest = null;
                preparationError = e;
            }
        }

        if(preparationError != null)
        {
            completeHeartbeat(null, preparationError, clientGeneration);
        }
        else
        {
            heartbeatRequest.whenComplete((response, error) ->
                completeHeartbeat(response, error, clientGeneration));
        }
    }

    private void completeHeartbeat(HttpResponse<String> response, Throwable error, long clientGeneration)
    {
        if(!mRunning.get() || clientGeneration != mHttpClientGeneration.get())
        {
            return;
        }

        try
        {
            if(error == null && response != null && response.statusCode() >= 200 && response.statusCode() < 300)
            {
                mLastHeartbeatSuccess = System.currentTimeMillis();
                mLastHeartbeatError = "";
                connectionSucceeded();
                mNextHeartbeatAttempt = System.currentTimeMillis() + heartbeatIntervalMillis();
            }
            else
            {
                String message = error != null ? rootMessage(error) :
                    (response != null ? responseError(response) : "Remote API returned an unknown response");
                mLastHeartbeatError = message;
                recoverConnection(message);
                mNextHeartbeatAttempt = System.currentTimeMillis() + retryDelayMillis();
                mLog.warn("Remote API heartbeat failed for [{}]: {}", getBroadcastConfiguration().getName(), message);
            }
        }
        finally
        {
            if(clientGeneration == mHttpClientGeneration.get())
            {
                mHeartbeatRequest = null;
                mHeartbeatInFlight.set(false);
            }
        }
    }

    /** Discards pooled HTTP connections and schedules an immediate health check without dropping queued calls. */
    public void reconnect()
    {
        if(!mRunning.get())
        {
            return;
        }

        rebuildHttpClient();
        mConsecutiveConnectionFailures.set(0);
        mLastHeartbeatError = "";
        mLastCallError = "";
        if(getBroadcastConfiguration().isHeartbeatEnabled())
        {
            setBroadcastState(BroadcastState.CONNECTING);
            mNextHeartbeatAttempt = 0;
        }
        else
        {
            setBroadcastState(BroadcastState.CONNECTED);
        }
    }

    private HttpClient createHttpClient()
    {
        return HttpClient.newBuilder().connectTimeout(mRequestTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private void rebuildHttpClient()
    {
        synchronized(mConnectionLock)
        {
            mHttpClientGeneration.incrementAndGet();
            CompletableFuture<HttpResponse<String>> heartbeatRequest = mHeartbeatRequest;
            mHeartbeatRequest = null;
            mHeartbeatInFlight.set(false);
            if(heartbeatRequest != null && !heartbeatRequest.isDone())
            {
                heartbeatRequest.cancel(true);
            }
            mHttpClient = createHttpClient();
            mLastReconnect = System.currentTimeMillis();
            mReconnectCount.incrementAndGet();
        }
    }

    private void recoverConnection(String message)
    {
        mConsecutiveConnectionFailures.incrementAndGet();
        rebuildHttpClient();
        mNextHeartbeatAttempt = 0;
        setBroadcastState(BroadcastState.CONNECTING);
        mLog.info("Remote API connection [{}] is reconnecting after: {}",
            getBroadcastConfiguration().getName(), message);
    }

    private void connectionSucceeded()
    {
        mLastContactSuccess = System.currentTimeMillis();
        mConsecutiveConnectionFailures.set(0);
        setBroadcastState(BroadcastState.CONNECTED);
    }

    private long heartbeatIntervalMillis()
    {
        return TimeUnit.SECONDS.toMillis(getBroadcastConfiguration().getHeartbeatIntervalSeconds());
    }

    private long staleWindowMillis()
    {
        return Math.max(heartbeatIntervalMillis() * 3L, mRequestTimeout.toMillis() * 2L + 2_000L);
    }

    private long retryDelayMillis()
    {
        int failures = Math.min(mConsecutiveConnectionFailures.get(), 6);
        long backoff = 1_000L << Math.max(0, failures - 1);
        return Math.min(heartbeatIntervalMillis(), Math.min(60_000L, backoff));
    }

    public boolean isConnectionStale()
    {
        if(!mRunning.get() || !getBroadcastConfiguration().isHeartbeatEnabled())
        {
            return false;
        }
        long lastSuccess = Math.max(mLastHeartbeatSuccess, mLastContactSuccess);
        long reference = lastSuccess > 0 ? lastSuccess : mStarted;
        return reference > 0 && System.currentTimeMillis() - reference > staleWindowMillis();
    }

    private static String responseError(HttpResponse<String> response)
    {
        String body = response.body();
        if(body == null || body.isBlank())
        {
            return "Remote API returned HTTP " + response.statusCode();
        }
        String compact = body.replaceAll("[\\r\\n]+", " ").trim();
        if(compact.length() > 300)
        {
            compact = compact.substring(0, 300) + "...";
        }
        return "Remote API returned HTTP " + response.statusCode() + ": " + compact;
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
    public long getLastCallAttempt() { return mLastCallAttempt; }
    public long getLastCallSuccess() { return mLastCallSuccess; }
    public String getLastCallError() { return mLastCallError; }
    public long getLastContactAttempt() { return mLastContactAttempt; }
    public long getLastContactSuccess() { return mLastContactSuccess; }
    public long getLastReconnect() { return mLastReconnect; }
    public int getReconnectCount() { return mReconnectCount.get(); }
    public int getConsecutiveConnectionFailures() { return mConsecutiveConnectionFailures.get(); }

    private void retryOrFail(QueuedCall call, Throwable error)
    {
        mLastCallError = rootMessage(error);
        recoverConnection(mLastCallError);
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
