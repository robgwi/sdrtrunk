package io.github.dsheirer.audio.broadcast.remote;

import com.google.gson.Gson;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.util.ThreadPool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
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
    private ScheduledFuture<?> mProcessor;
    private final SpeechProcessor mSpeechProcessor;

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
            setBroadcastState(BroadcastState.CONNECTED);
            mProcessor = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::processQueue, 0, 250,
                TimeUnit.MILLISECONDS);
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
                String key = getBroadcastConfiguration().resolveApiKey();
                if(key != null && !key.isBlank() && getBroadcastConfiguration().getAuthenticationHeader() != null &&
                    !getBroadcastConfiguration().getAuthenticationHeader().isBlank())
                {
                    request.header(getBroadcastConfiguration().getAuthenticationHeader(),
                        getBroadcastConfiguration().getAuthenticationPrefix() + key);
                }
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
