package io.github.dsheirer.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.ConfiguredBroadcast;
import io.github.dsheirer.audio.broadcast.remote.RemoteApiConfiguration;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.IAudioSegmentListener;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3AudioConverter;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.gui.SDRTrunk;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.prefs.Preferences;
import java.lang.management.ManagementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedded, headless-safe HTTP API and initial browser dashboard. */
public class SdrTrunkWebServer implements IAudioSegmentListener
{
    private static final Logger mLog = LoggerFactory.getLogger(SdrTrunkWebServer.class);
    private static final Gson GSON = new Gson();
    private static final long STARTED = System.currentTimeMillis();
    private static final String TOKEN_KEY = "sdrtrunk.web.access.token";
    private static volatile SdrTrunkWebServer ACTIVE;
    private final PlaylistManager mPlaylistManager;
    private final TunerManager mTunerManager;
    private final ResourceMonitor mResourceMonitor;
    private final UserPreferences mUserPreferences;
    private volatile String mToken;
    private volatile byte[] mLatestAudio;
    private volatile Map<String,Object> mLatestAudioMetadata = Map.of();
    private final AtomicLong mLatestAudioSequence = new AtomicLong();
    private final ConcurrentLinkedDeque<Map<String,Object>> mActivity = new ConcurrentLinkedDeque<>();
    private HttpServer mServer;

    public SdrTrunkWebServer(PlaylistManager playlistManager, TunerManager tunerManager,
                             ResourceMonitor resourceMonitor, UserPreferences userPreferences, String token)
    {
        mPlaylistManager = playlistManager;
        mTunerManager = tunerManager;
        mResourceMonitor = resourceMonitor;
        mUserPreferences = userPreferences;
        mToken = token;
        ACTIVE = this;
    }

    /** Updates the bearer token without restarting the receiver or web server. */
    public void setToken(String token)
    {
        mToken = token;
    }

    public static String getSavedToken()
    {
        return Preferences.userNodeForPackage(SDRTrunk.class).get(TOKEN_KEY, "");
    }

    public static void saveToken(String token)
    {
        Preferences.userNodeForPackage(SDRTrunk.class).put(TOKEN_KEY, token);
        if(ACTIVE != null) { ACTIVE.setToken(token); }
    }

    public static boolean isRunning()
    {
        return ACTIVE != null && ACTIVE.mServer != null;
    }

    public static synchronized void restartActive() throws IOException
    {
        if(ACTIVE == null) { throw new IOException("Web interface has not been initialized"); }
        ACTIVE.stop();
        ACTIVE.start();
    }

    public void start() throws IOException
    {
        String bind = System.getProperty("sdrtrunk.web.bind", "127.0.0.1");
        int port = Integer.getInteger("sdrtrunk.web.port", 8080);
        mServer = HttpServer.create(new InetSocketAddress(bind, port), 0);
        mServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        mServer.createContext("/api/v1/health", exchange -> json(exchange, 200, Map.of("status", "ok")));
        mServer.createContext("/api/v1/status", authenticated(this::status));
        mServer.createContext("/api/v1/channels", authenticated(this::channels));
        mServer.createContext("/api/v1/talkgroups", authenticated(this::talkgroups));
        mServer.createContext("/api/v1/tuners", authenticated(this::tuners));
        mServer.createContext("/api/v1/broadcasters", authenticated(this::broadcasters));
        mServer.createContext("/api/v1/remote-destinations", authenticated(this::remoteDestinations));
        mServer.createContext("/api/v1/channel-control", authenticated(this::channelControl));
        mServer.createContext("/api/v1/recordings", authenticated(this::recordings));
        mServer.createContext("/api/v1/recording-audio", authenticated(this::recordingAudio));
        mServer.createContext("/api/v1/live-audio", authenticated(this::liveAudio));
        mServer.createContext("/api/v1/live-status", authenticated(this::liveStatus));
        mServer.createContext("/api/v1/activity", authenticated(this::activity));
        mServer.createContext("/", this::dashboard);
        mServer.start();
        mLog.info("sdrtrunk web interface listening at http://{}:{}", bind, port);
        if(!InetAddress.getByName(bind).isLoopbackAddress() && (mToken == null || mToken.isBlank()))
        {
            mLog.warn("Web interface is reachable off-host without SDRTRUNK_WEB_TOKEN; API requests will be denied");
        }
    }

    public void stop()
    {
        if(mServer != null)
        {
            mServer.stop(2);
            mServer = null;
        }
    }

    private HttpHandler authenticated(HttpHandler handler)
    {
        return exchange ->
        {
            boolean loopback = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            boolean tokenMatch = mToken != null && !mToken.isBlank() &&
                ("Bearer " + mToken).equals(authorization);
            if(!loopback && !tokenMatch)
            {
                json(exchange, 401, Map.of("error", "authentication required"));
                return;
            }
            handler.handle(exchange);
        };
    }

    private void status(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("started", Instant.ofEpochMilli(STARTED).toString());
        result.put("uptimeMs", System.currentTimeMillis() - STARTED);
        Runtime runtime = Runtime.getRuntime();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        result.put("cpu", load >= 0 ? load / Math.max(1, runtime.availableProcessors()) : -1);
        result.put("cpuAvailable", load >= 0);
        result.put("memoryUsed", runtime.totalMemory() - runtime.freeMemory());
        result.put("memoryAllocated", runtime.totalMemory());
        result.put("memoryMaximum", runtime.maxMemory());
        result.put("recordingsSize", mResourceMonitor.fileSizeRecordingsProperty().get());
        result.put("eventLogsSize", mResourceMonitor.fileSizeEventLogsProperty().get());
        json(exchange, 200, result);
    }

    private void channels(HttpExchange exchange) throws IOException
    {
        if("POST".equals(exchange.getRequestMethod())) { editChannel(exchange); return; }
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        List<Map<String, Object>> result = new ArrayList<>();
        for(Channel channel: mPlaylistManager.getChannelModel().getChannels())
        {
            result.add(channelMap(channel));
        }
        for(Channel channel: mPlaylistManager.getChannelModel().trafficChannelList())
        {
            result.add(channelMap(channel));
        }
        json(exchange, 200, result);
    }

    private Map<String, Object> channelMap(Channel channel)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", channel.getName());
        value.put("id", channel.getChannelID());
        value.put("system", channel.getSystem());
        value.put("site", channel.getSite());
        value.put("type", channel.getChannelType().name());
        value.put("processing", channel.isProcessing());
        value.put("autoStart", channel.isAutoStart());
        value.put("aliasList", channel.getAliasListName());
        value.put("decoder", channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType().name() : null);
        value.put("source", channel.getSourceConfiguration() != null ?
            channel.getSourceConfiguration().toString() : null);
        if(channel.getSourceConfiguration() instanceof SourceConfigTuner tunerSource)
        {
            value.put("frequency", tunerSource.getFrequency());
        }
        return value;
    }

    private void editChannel(HttpExchange exchange) throws IOException
    {
        JsonObject request = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
            JsonObject.class);
        String action = stringValue(request, "action", "update");
        int id = request.has("id") ? request.get("id").getAsInt() : -1;
        Channel channel = mPlaylistManager.getChannelModel().getChannels().stream()
            .filter(item -> item.getChannelID() == id).findFirst().orElse(null);

        if("create".equalsIgnoreCase(action))
        {
            DecoderType decoder = decoderValue(request);
            channel = new Channel(stringValue(request, "name", "New Channel"));
            channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoder));
            updateChannelFields(channel, request, false);
            mPlaylistManager.getChannelModel().addChannel(channel);
            json(exchange, 201, channelMap(channel));
            return;
        }

        if(channel == null) { json(exchange, 404, Map.of("error", "channel not found")); return; }
        if(channel.isProcessing()) { json(exchange, 409, Map.of("error", "stop channel before editing")); return; }
        if("delete".equalsIgnoreCase(action))
        {
            mPlaylistManager.getChannelModel().removeChannel(channel);
            json(exchange, 200, Map.of("deleted", id));
            return;
        }
        updateChannelFields(channel, request, true);
        mPlaylistManager.getChannelModel().receive(new ChannelEvent(channel,
            ChannelEvent.Event.NOTIFICATION_CONFIGURATION_CHANGE));
        json(exchange, 200, channelMap(channel));
    }

    private void updateChannelFields(Channel channel, JsonObject request, boolean allowDecoderChange)
    {
        if(request.has("name")) { channel.setName(request.get("name").getAsString()); }
        if(request.has("system")) { channel.setSystem(request.get("system").getAsString()); }
        if(request.has("site")) { channel.setSite(request.get("site").getAsString()); }
        if(request.has("aliasList")) { channel.setAliasListName(request.get("aliasList").getAsString()); }
        if(request.has("autoStart")) { channel.setAutoStart(request.get("autoStart").getAsBoolean()); }
        if(request.has("frequency"))
        {
            SourceConfigTuner source = channel.getSourceConfiguration() instanceof SourceConfigTuner existing ?
                existing : new SourceConfigTuner();
            source.setFrequency(request.get("frequency").getAsLong());
            channel.setSourceConfiguration(source);
        }
        if(allowDecoderChange && request.has("decoder"))
        {
            DecoderType decoder = decoderValue(request);
            if(channel.getDecodeConfiguration() == null || channel.getDecodeConfiguration().getDecoderType() != decoder)
            {
                channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoder));
            }
        }
    }

    private DecoderType decoderValue(JsonObject request)
    {
        try { return DecoderType.valueOf(stringValue(request, "decoder", DecoderType.NBFM.name())); }
        catch(Exception e) { return DecoderType.NBFM; }
    }

    private void tuners(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        List<Map<String, Object>> result = new ArrayList<>();
        for(DiscoveredTuner discovered: mTunerManager.getAvailableTuners())
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", discovered.getId());
            value.put("status", discovered.getTunerStatus().name());
            value.put("class", discovered.getTunerClass().name());
            value.put("error", discovered.getErrorMessage());
            if(discovered.hasTuner())
            {
                Tuner tuner = discovered.getTuner();
                value.put("name", tuner.getPreferredName());
                value.put("type", tuner.getTunerType().name());
                value.put("frequency", tuner.getTunerController().getFrequency());
                value.put("sampleRate", tuner.getTunerController().getSampleRate());
                value.put("frequencyCorrection", tuner.getTunerController().getFrequencyCorrection());
            }
            result.add(value);
        }
        json(exchange, 200, result);
    }

    private void broadcasters(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        BroadcastModel model = mPlaylistManager.getBroadcastModel();
        List<Map<String, Object>> result = new ArrayList<>();
        for(ConfiguredBroadcast configured: model.getConfiguredBroadcasts())
        {
            BroadcastConfiguration configuration = configured.getBroadcastConfiguration();
            AbstractAudioBroadcaster broadcaster = configured.getAudioBroadcaster();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", configuration.getName());
            value.put("type", configuration.getBroadcastServerType().name());
            value.put("enabled", configuration.isEnabled());
            value.put("valid", configuration.isValid());
            value.put("state", configured.broadcastStateProperty().get() != null ?
                configured.broadcastStateProperty().get().name() : null);
            value.put("host", configuration.getHost());
            value.put("queue", broadcaster != null ? broadcaster.getAudioQueueSize() : 0);
            value.put("sent", broadcaster != null ? broadcaster.getStreamedAudioCount() : 0);
            value.put("errors", broadcaster != null ? broadcaster.getAudioErrorCount() : 0);
            value.put("agedOff", broadcaster != null ? broadcaster.getAgedOffAudioCount() : 0);
            result.add(value);
        }
        json(exchange, 200, result);
    }

    private void channelControl(HttpExchange exchange) throws IOException
    {
        if(!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        JsonObject request = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
            JsonObject.class);
        String name = request.has("name") ? request.get("name").getAsString() : null;
        String action = request.has("action") ? request.get("action").getAsString() : null;
        Channel channel = mPlaylistManager.getChannelModel().getChannels().stream()
            .filter(item -> item.getName() != null && item.getName().equals(name)).findFirst().orElse(null);
        if(channel == null) { json(exchange, 404, Map.of("error", "channel not found")); return; }
        ChannelProcessingManager manager = mPlaylistManager.getChannelProcessingManager();
        try
        {
            if("start".equalsIgnoreCase(action)) { manager.start(channel); }
            else if("stop".equalsIgnoreCase(action)) { manager.stop(channel); }
            else { json(exchange, 400, Map.of("error", "action must be start or stop")); return; }
            json(exchange, 200, channelMap(channel));
        }
        catch(Exception e)
        {
            json(exchange, 409, Map.of("error", e.getMessage() != null ? e.getMessage() : "channel operation failed"));
        }
    }

    private void remoteDestinations(HttpExchange exchange) throws IOException
    {
        if("GET".equals(exchange.getRequestMethod()))
        {
            List<Map<String,Object>> result = new ArrayList<>();
            for(ConfiguredBroadcast configured: mPlaylistManager.getBroadcastModel().getConfiguredBroadcasts())
            {
                if(configured.getBroadcastConfiguration() instanceof RemoteApiConfiguration configuration)
                {
                    result.add(remoteDestinationMap(configuration));
                }
            }
            json(exchange, 200, result);
            return;
        }
        if(!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try
        {
            JsonObject request = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8), JsonObject.class);
            String action = stringValue(request, "action", "create");
            String originalName = stringValue(request, "originalName", null);
            RemoteApiConfiguration configuration = findRemoteDestination(originalName);
            if("delete".equalsIgnoreCase(action))
            {
                if(configuration == null) { json(exchange, 404, Map.of("error", "destination not found")); return; }
                mPlaylistManager.getBroadcastModel().removeBroadcastConfiguration(configuration);
                json(exchange, 200, Map.of("deleted", originalName));
                return;
            }
            boolean creating = configuration == null;
            if(!creating && !"update".equalsIgnoreCase(action))
            { json(exchange, 409, Map.of("error", "destination already exists")); return; }
            if(creating) { configuration = new RemoteApiConfiguration(); }
            String name = requiredString(request, "name");
            String url = requiredString(request, "url");
            configuration.setName(name);
            configuration.setHost(url);
            configuration.setEnabled(booleanValue(request, "enabled", true));
            configuration.setApiKeyEnvironmentVariable(stringValue(request, "apiKeyEnvironmentVariable",
                RemoteApiConfiguration.DEFAULT_API_KEY_ENVIRONMENT_VARIABLE));
            configuration.setAuthenticationHeader(stringValue(request, "authenticationHeader", "Authorization"));
            configuration.setAuthenticationPrefix(stringValue(request, "authenticationPrefix", "Bearer "));
            configuration.setMaximumRetries(integerValue(request, "maximumRetries", 5));
            configuration.setMaximumConcurrentUploads(integerValue(request, "maximumConcurrentUploads", 2));
            configuration.setRequestTimeoutSeconds(integerValue(request, "requestTimeoutSeconds", 60));
            configuration.setOpenAiEnabled(booleanValue(request, "openAiEnabled", false));
            configuration.setTranslateToEnglish(booleanValue(request, "translateToEnglish", false));
            configuration.setOpenAiKeyEnvironmentVariable(stringValue(request, "openAiKeyEnvironmentVariable",
                RemoteApiConfiguration.DEFAULT_OPENAI_KEY_ENVIRONMENT_VARIABLE));
            configuration.setLocalWhisperExecutable(stringValue(request, "localWhisperExecutable", null));
            configuration.setLocalWhisperModel(stringValue(request, "localWhisperModel", null));
            configuration.setMaximumRecordingAge(integerValue(request, "maximumRecordingAgeSeconds", 600) * 1000L);
            if(creating)
            {
                mPlaylistManager.getBroadcastModel().addBroadcastConfiguration(configuration);
            }
            else
            {
                mPlaylistManager.getBroadcastModel().process(new BroadcastEvent(configuration,
                    BroadcastEvent.Event.CONFIGURATION_CHANGE));
            }
            mPlaylistManager.schedulePlaylistSave();
            json(exchange, creating ? 201 : 200, remoteDestinationMap(configuration));
        }
        catch(IllegalArgumentException e)
        {
            json(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private RemoteApiConfiguration findRemoteDestination(String name)
    {
        if(name == null) { return null; }
        return mPlaylistManager.getBroadcastModel().getBroadcastConfigurations().stream()
            .filter(RemoteApiConfiguration.class::isInstance).map(RemoteApiConfiguration.class::cast)
            .filter(item -> name.equals(item.getName())).findFirst().orElse(null);
    }

    private static Map<String,Object> remoteDestinationMap(RemoteApiConfiguration configuration)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("name", configuration.getName());
        value.put("url", configuration.getHost());
        value.put("enabled", configuration.isEnabled());
        value.put("apiKeyEnvironmentVariable", configuration.getApiKeyEnvironmentVariable());
        value.put("authenticationHeader", configuration.getAuthenticationHeader());
        value.put("authenticationPrefix", configuration.getAuthenticationPrefix());
        value.put("maximumRetries", configuration.getMaximumRetries());
        value.put("maximumConcurrentUploads", configuration.getMaximumConcurrentUploads());
        value.put("requestTimeoutSeconds", configuration.getRequestTimeoutSeconds());
        value.put("maximumRecordingAgeSeconds", configuration.getMaximumRecordingAge() / 1000L);
        value.put("openAiEnabled", configuration.isOpenAiEnabled());
        value.put("translateToEnglish", configuration.isTranslateToEnglish());
        value.put("openAiKeyEnvironmentVariable", configuration.getOpenAiKeyEnvironmentVariable());
        value.put("localWhisperExecutable", configuration.getLocalWhisperExecutable());
        value.put("localWhisperModel", configuration.getLocalWhisperModel());
        return value;
    }

    /** Talkgroup aliases shared by web channel editing, RadioReference supplements and Remote Calls. */
    private void talkgroups(HttpExchange exchange) throws IOException
    {
        if("GET".equals(exchange.getRequestMethod()))
        {
            List<Map<String,Object>> result = new ArrayList<>();
            List<Alias> aliases = mPlaylistManager.getAliasModel().getAliases();
            for(int index = 0; index < aliases.size(); index++)
            {
                Alias alias = aliases.get(index);
                for(AliasID identifier: alias.getAliasIdentifiers())
                {
                    if(identifier instanceof Talkgroup talkgroup)
                    {
                        Map<String,Object> value = new LinkedHashMap<>();
                        value.put("id", index);
                        value.put("name", alias.getName());
                        value.put("group", alias.getGroup());
                        value.put("aliasList", alias.getAliasListName());
                        value.put("protocol", talkgroup.getProtocol().name());
                        value.put("talkgroup", talkgroup.getValue());
                        value.put("record", alias.isRecordable());
                        value.put("priority", alias.getPlaybackPriority());
                        value.put("remoteCalls", alias.getBroadcastChannels().stream()
                            .map(item -> item.getChannelName()).toList());
                        result.add(value);
                    }
                }
            }
            json(exchange, 200, result);
            return;
        }
        if(!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try
        {
            JsonObject request = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8), JsonObject.class);
            String action = stringValue(request, "action", "create");
            int id = integerValue(request, "id", -1);
            List<Alias> aliases = mPlaylistManager.getAliasModel().aliasList();
            Alias alias = id >= 0 && id < aliases.size() ? aliases.get(id) : null;
            if("delete".equalsIgnoreCase(action))
            {
                if(alias == null) { json(exchange, 404, Map.of("error", "talkgroup alias not found")); return; }
                mPlaylistManager.getAliasModel().removeAlias(alias);
                json(exchange, 200, Map.of("deleted", id));
                return;
            }
            boolean creating = alias == null;
            if(creating) { alias = new Alias(requiredString(request, "name")); }
            alias.setName(requiredString(request, "name"));
            alias.setAliasListName(requiredString(request, "aliasList"));
            alias.setGroup(stringValue(request, "group", null));
            alias.setRecordable(booleanValue(request, "record", false));
            alias.setCallPriority(integerValue(request, "priority", 100));
            Protocol protocol = Protocol.valueOf(requiredString(request, "protocol"));
            int value = integerValue(request, "talkgroup", -1);
            Talkgroup talkgroup = alias.getAliasIdentifiers().stream().filter(Talkgroup.class::isInstance)
                .map(Talkgroup.class::cast).findFirst().orElse(null);
            if(talkgroup == null) { talkgroup = new Talkgroup(protocol, value); alias.addAliasID(talkgroup); }
            else { talkgroup.setProtocol(protocol); talkgroup.setValue(value); }
            alias.removeAllBroadcastChannels();
            if(request.has("remoteCalls") && request.get("remoteCalls").isJsonArray())
            {
                for(var item: request.getAsJsonArray("remoteCalls"))
                {
                    alias.addAliasID(new BroadcastChannel(item.getAsString()));
                }
            }
            if(!talkgroup.isValid()) { throw new IllegalArgumentException("talkgroup is outside the valid range for " + protocol); }
            if(creating) { mPlaylistManager.getAliasModel().addAlias(alias); }
            else
            {
                mPlaylistManager.getAliasModel().removeAlias(alias);
                mPlaylistManager.getAliasModel().addAlias(alias);
            }
            mPlaylistManager.schedulePlaylistSave();
            json(exchange, creating ? 201 : 200, Map.of("saved", alias.getName()));
        }
        catch(Exception e)
        {
            json(exchange, 400, Map.of("error", e.getMessage() != null ? e.getMessage() : "invalid talkgroup"));
        }
    }

    private static String requiredString(JsonObject object, String name)
    {
        String value = stringValue(object, name, null);
        if(value == null || value.isBlank())
        {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String stringValue(JsonObject object, String name, String defaultValue)
    {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : defaultValue;
    }

    private static boolean booleanValue(JsonObject object, String name, boolean defaultValue)
    {
        return object.has(name) ? object.get(name).getAsBoolean() : defaultValue;
    }

    private static int integerValue(JsonObject object, String name, int defaultValue)
    {
        return object.has(name) ? object.get(name).getAsInt() : defaultValue;
    }

    private void dashboard(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        byte[] bytes = DASHBOARD.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public Listener<AudioSegment> getAudioSegmentListener()
    {
        return segment ->
        {
            Runnable encode = () -> encodeLiveAudio(segment);
            if(segment.isComplete()) { Thread.startVirtualThread(encode); }
            else
            {
                segment.completeProperty().addListener((observable, oldValue, complete) ->
                {
                    if(complete) { Thread.startVirtualThread(encode); }
                });
            }
        };
    }

    private void encodeLiveAudio(AudioSegment segment)
    {
        try
        {
            if(segment.hasAudio() && !segment.isEncrypted())
            {
                MP3AudioConverter converter = new MP3AudioConverter(InputAudioFormat.SR_8000,
                    MP3Setting.CBR_16, false);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                for(byte[] block: converter.convert(segment.getAudioBuffers())) { output.write(block); }
                byte[] audio = output.toByteArray();
                if(audio.length > 0)
                {
                    Identifier to = segment.getIdentifierCollection().getToIdentifier();
                    Identifier from = segment.getIdentifierCollection().getFromIdentifier();
                    List<String> aliases = to != null && segment.getAliasList() != null ?
                        segment.getAliasList().getAliases(to).stream().map(Alias::getName).toList() : List.of();
                    Identifier frequency = segment.getIdentifierCollection().getIdentifier(
                        IdentifierClass.CONFIGURATION, Form.CHANNEL_FREQUENCY, Role.ANY);
                    Map<String,Object> metadata = new LinkedHashMap<>();
                    metadata.put("talkgroup", to != null ? to.toString() : "Unknown");
                    metadata.put("alias", aliases.isEmpty() ? "Unidentified" : String.join(", ", aliases));
                    metadata.put("source", from != null ? from.toString() : "Unknown");
                    metadata.put("frequency", frequency instanceof ConfigurationLongIdentifier value ?
                        value.getValue() : 0);
                    metadata.put("started", segment.getStartTimestamp());
                    metadata.put("duration", segment.getDuration());
                    metadata.put("audioLevelDbfs", audioLevelDbfs(segment));
                    metadata.put("rfSignalAvailable", false);
                    mLatestAudio = audio;
                    metadata.put("sequence", mLatestAudioSequence.incrementAndGet());
                    mLatestAudioMetadata = metadata;
                }
            }
        }
        catch(Exception e)
        {
            mLog.warn("Unable to prepare live web audio", e);
        }
        finally
        {
            segment.decrementConsumerCount();
        }
    }

    private void liveAudio(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        byte[] audio = mLatestAudio;
        long requested = longQueryValue(exchange, "after", -1);
        long sequence = mLatestAudioSequence.get();
        if(audio == null || requested >= sequence)
        {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("X-Audio-Sequence", Long.toString(sequence));
        bytes(exchange, 200, "audio/mpeg", audio);
    }

    private void liveStatus(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        json(exchange, 200, mLatestAudioMetadata);
    }

    private static double audioLevelDbfs(AudioSegment segment)
    {
        double sum = 0;
        long count = 0;
        for(float[] buffer: segment.getAudioBuffers())
        {
            for(float sample: buffer) { sum += sample * sample; count++; }
        }
        if(count == 0 || sum == 0) { return -100.0; }
        return Math.max(-100.0, 20.0 * Math.log10(Math.sqrt(sum / count)));
    }

    public Listener<IDecodeEvent> getDecodeEventListener()
    {
        return event ->
        {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("time", event.getTimeStart());
            item.put("end", event.getTimeEnd());
            item.put("duration", event.getDuration());
            item.put("protocol", event.getProtocol() != null ? event.getProtocol().toString() : "Unknown");
            item.put("type", event.getEventType() != null ? event.getEventType().toString() : "Activity");
            Identifier talkgroup = event.getIdentifierCollection().getToIdentifier();
            item.put("talkgroup", talkgroup != null ? talkgroup.toString() : "Unknown");
            AliasList aliasList = mPlaylistManager.getAliasModel().getAliasList(event.getIdentifierCollection());
            List<String> aliases = talkgroup != null && aliasList != null ? aliasList.getAliases(talkgroup).stream()
                .map(Alias::getName).toList() : List.of();
            item.put("alias", aliases.isEmpty() ? "Unidentified" : String.join(", ", aliases));
            item.put("source", event.getIdentifierCollection().getIdentifiers(Role.FROM).stream()
                .map(Object::toString).findFirst().orElse(""));
            item.put("details", event.getDetails());
            item.put("frequency", event.getChannelDescriptor() != null ?
                event.getChannelDescriptor().getDownlinkFrequency() : 0);
            item.put("signalAvailable", false);
            mActivity.addFirst(item);
            while(mActivity.size() > 100) { mActivity.pollLast(); }
        };
    }

    private void activity(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        json(exchange, 200, new ArrayList<>(mActivity));
    }

    private void recordings(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        Path root = mUserPreferences.getDirectoryPreference().getDirectoryRecording();
        if(!Files.isDirectory(root)) { json(exchange, 200, List.of()); return; }
        try(var paths = Files.list(root))
        {
            List<Map<String,Object>> result = paths.filter(Files::isRegularFile)
                .filter(path -> isAudioFile(path.getFileName().toString()))
                .sorted((a,b) -> Long.compare(lastModified(b), lastModified(a))).limit(200)
                .map(path -> Map.<String,Object>of("name", path.getFileName().toString(),
                    "size", fileSize(path), "modified", lastModified(path))).toList();
            json(exchange, 200, result);
        }
    }

    private void recordingAudio(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        String name = queryValue(exchange, "name");
        Path root = mUserPreferences.getDirectoryPreference().getDirectoryRecording().toAbsolutePath().normalize();
        Path file = name == null ? root : root.resolve(name).normalize();
        if(name == null || !file.startsWith(root) || !file.getParent().equals(root) || !Files.isRegularFile(file) ||
            !isAudioFile(name))
        {
            json(exchange, 404, Map.of("error", "recording not found"));
            return;
        }
        bytes(exchange, 200, name.toLowerCase().endsWith(".wav") ? "audio/wav" : "audio/mpeg",
            Files.readAllBytes(file));
    }

    private static boolean isAudioFile(String name)
    {
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav");
    }

    private static long lastModified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch(Exception e) { return 0; } }
    private static long fileSize(Path path) { try { return Files.size(path); } catch(Exception e) { return 0; } }
    private static String queryValue(HttpExchange exchange, String key)
    {
        String query = exchange.getRequestURI().getRawQuery();
        if(query == null) { return null; }
        for(String part: query.split("&"))
        {
            String[] pair = part.split("=", 2);
            if(URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key))
            { return pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""; }
        }
        return null;
    }
    private static long longQueryValue(HttpExchange exchange, String key, long fallback)
    { try { return Long.parseLong(queryValue(exchange, key)); } catch(Exception e) { return fallback; } }

    private static void bytes(HttpExchange exchange, int status, String contentType, byte[] value) throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, value.length);
        exchange.getResponseBody().write(value);
        exchange.close();
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException
    {
        json(exchange, 405, Map.of("error", "method not allowed"));
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException
    {
        byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final String DASHBOARD = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>sdrtrunk web</title><style>
        :root{color-scheme:dark;--bg:#091017;--panel:#111d27;--line:#263847;--text:#e9f2f7;--muted:#8da5b4;--accent:#38d39f}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px system-ui,sans-serif}header{padding:20px 28px;border-bottom:1px solid var(--line);display:flex;align-items:center;gap:15px;justify-content:space-between;flex-wrap:wrap}h1{margin:0;font-size:21px}main{padding:22px;display:grid;gap:18px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.card,section{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}.value{font-size:24px;color:var(--accent);margin-top:7px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid var(--line)}th,.muted{color:var(--muted)}button{background:var(--accent);border:0;border-radius:5px;padding:6px 10px;color:#05251b;font-weight:700}input,select,textarea{background:#0a141c;color:var(--text);border:1px solid var(--line);border-radius:5px;padding:7px}textarea{width:100%}dialog{color:var(--text);background:var(--panel);border:1px solid var(--line);border-radius:10px;max-width:760px}h2{font-size:16px;margin:0 0 12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.scanner{background:#06100d;border:1px solid #267c60}.scanner-line{display:flex;gap:24px;align-items:center;flex-wrap:wrap}.scan-state{font-size:24px;color:#67ffc5;letter-spacing:2px}.receiving{color:#ffca58}.meter{height:12px;width:160px;background:#18252c;border-radius:6px;overflow:hidden}.meter span{display:block;height:100%;background:linear-gradient(90deg,#37d79d,#ffd05a,#ff5e5e)}@media(max-width:850px){.grid{grid-template-columns:1fr}}
        </style></head><body><header><h1>sdrtrunk web console</h1><div><input id="token" type="password" placeholder="Web access token" autocomplete="current-password"> <button id="saveToken">Connect</button></div><span id="updated" class="muted">connecting</span></header><main>
        <section class="scanner"><h2>Live Traffic Scanner</h2><div class="scanner-line"><div><div class="muted">SCANNER</div><div id="scanState" class="scan-state">SCANNING</div></div><div><div class="muted">TALKGROUP ID</div><div class="value" id="activeTalkgroup">—</div></div><div><div class="muted">TALKGROUP ALIAS</div><div class="value" id="activeAlias">—</div></div><div><div class="muted">FREQUENCY</div><div class="value" id="activeFrequency">—</div></div><div><div class="muted">SOURCE RADIO</div><div class="value" id="activeSource">—</div></div><div><div class="muted">RF SIGNAL</div><div id="signalText">Unavailable</div></div><div><div class="muted">AUDIO LEVEL</div><div id="audioLevelText">—</div><div class="meter"><span id="signalMeter" style="width:0"></span></div></div></div><p><button id="liveToggle">Start Live Listening</button> <span id="liveStatus" class="muted">Off</span></p><audio id="audioPlayer" controls></audio></section>
        <div class="cards"><div class="card">CPU<div class="value" id="cpu">—</div></div><div class="card">Memory<div class="value" id="memory">—</div></div><div class="card">Tuners<div class="value" id="tunerCount">—</div></div><div class="card">Active channels<div class="value" id="activeCount">—</div></div></div>
        <div class="grid"><section><h2>Tuners</h2><table><thead><tr><th>Name</th><th>Status</th><th>Frequency</th></tr></thead><tbody id="tuners"></tbody></table></section>
        <section><h2>Streaming destinations</h2><table><thead><tr><th>Name</th><th>Type</th><th>State</th><th>Queue</th></tr></thead><tbody id="streams"></tbody></table></section></div>
        <section><h2>Playlist Channels <button onclick="openChannelEditor()">New Channel</button></h2><table><thead><tr><th>System</th><th>Site</th><th>Name</th><th>Decoder</th><th>Status</th><th>Control</th></tr></thead><tbody id="channels"></tbody></table></section>
        <section><h2>Talkgroups &amp; Aliases <button onclick="openTalkgroupEditor()">Add Talkgroup</button></h2><p class="muted">Add or edit talkgroups imported from RadioReference. Assigning a Remote Call destination controls which calls are sent there.</p><table><thead><tr><th>Alias List</th><th>Talkgroup</th><th>Name</th><th>Group</th><th>Protocol</th><th>Record</th><th>Remote Calls</th><th></th></tr></thead><tbody id="talkgroups"></tbody></table></section>
        <section><h2>Recorded audio</h2><table><thead><tr><th>File</th><th>Date</th><th>Size</th><th></th></tr></thead><tbody id="recordings"></tbody></table></section>
        <section><h2>Recent scanner activity</h2><table><thead><tr><th>Time</th><th>Talkgroup</th><th>Alias</th><th>Source</th><th>Protocol</th><th>Frequency</th><th>Event</th></tr></thead><tbody id="activity"></tbody></table></section>
        <section><h2>Remote Calls <button onclick="openRemoteEditor()">Add Destination</button></h2><table><thead><tr><th>Name</th><th>POST URL</th><th>Status</th><th>Transcription</th><th></th></tr></thead><tbody id="remoteDestinations"></tbody></table></section>
        <dialog id="channelDialog"><form id="channelForm"><input name="id" type="hidden"><h2>Edit Playlist Channel</h2><p><label>Name<br><input name="name" required></label></p><p><label>System<br><input name="system"></label> <label>Site<br><input name="site"></label></p><p><label>Frequency (Hz)<br><input name="frequency" type="number" min="0"></label> <label>Protocol<br><select name="decoder"><option>AM</option><option>DMR</option><option>LTR</option><option>LTR_NET</option><option>MPT1327</option><option>NBFM</option><option>NXDN</option><option>PASSPORT</option><option>P25_PHASE1</option><option>P25_PHASE2</option></select></label></p><p><label>Alias list<br><input name="aliasList"></label> <label><input name="autoStart" type="checkbox"> Auto-start</label></p><button type="submit">Save</button> <button type="button" onclick="channelDialog.close()">Cancel</button> <button id="deleteChannel" type="button">Delete</button><span id="channelResult" class="muted"></span></form></dialog>
        <dialog id="talkgroupDialog"><form id="talkgroupForm"><input name="id" type="hidden"><h2>Talkgroup / Alias</h2><div class="cards"><label>Alias list<br><input name="aliasList" required></label><label>Talkgroup ID<br><input name="talkgroup" type="number" min="0" required></label><label>Talkgroup name / alias<br><input name="name" required></label><label>Category / group<br><input name="group"></label><label>Protocol<br><select name="protocol"><option value="APCO25">P25</option><option>DMR</option><option>NXDN</option><option>LTR</option><option>LTR_NET</option><option>MPT1327</option><option>PASSPORT</option><option>NBFM</option><option>AM</option></select></label><label>Playback priority<br><input name="priority" type="number" min="1" max="100" value="100"></label></div><p><label><input name="record" type="checkbox"> Record calls</label></p><fieldset><legend>Send calls to Remote Calls destinations</legend><div id="talkgroupRemoteCalls" class="cards"></div></fieldset><p><button type="submit">Save</button> <button type="button" onclick="talkgroupDialog.close()">Cancel</button> <button id="deleteTalkgroup" type="button">Delete</button> <span id="talkgroupResult" class="muted"></span></p></form></dialog>
        <dialog id="remoteDialog"><form id="remoteForm"><input name="originalName" type="hidden"><h2>Remote Call API Destination</h2><div class="cards"><label>Name<br><input name="name" required></label><label>POST URL<br><input name="url" type="url" required></label><label>API key environment variable<br><input name="apiKeyEnvironmentVariable" value="SDRTRUNK_REMOTE_API_KEY"></label><label>Authentication header<br><input name="authenticationHeader" value="Authorization"></label><label>Authentication prefix<br><input name="authenticationPrefix" value="Bearer "></label><label>Retries<br><input name="maximumRetries" type="number" min="0" value="5"></label><label>Concurrent uploads<br><input name="maximumConcurrentUploads" type="number" min="1" value="2"></label><label>Timeout seconds<br><input name="requestTimeoutSeconds" type="number" min="1" value="60"></label><label>Maximum call age seconds<br><input name="maximumRecordingAgeSeconds" type="number" min="1" value="600"></label><label>OpenAI key environment variable<br><input name="openAiKeyEnvironmentVariable" value="OPENAI_API_KEY"></label><label>Local Whisper executable<br><input name="localWhisperExecutable"></label><label>Local Whisper model<br><input name="localWhisperModel"></label></div><p><label><input name="enabled" type="checkbox" checked> Enabled</label> <label><input name="openAiEnabled" type="checkbox"> OpenAI Whisper</label> <label><input name="translateToEnglish" type="checkbox"> Translate to English</label></p><button type="submit">Save</button> <button type="button" onclick="remoteDialog.close()">Cancel</button> <button id="deleteRemote" type="button">Delete</button> <span id="remoteResult" class="muted"></span></form></dialog>
        </main><script>
        const esc=s=>String(s??'').replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));
        const mhz=n=>n?`${(n/1e6).toFixed(5)} MHz`:'—'; const mb=n=>n?`${(n/1048576).toFixed(1)} MB`:'—';
        let apiToken=localStorage.getItem('sdrtrunkWebToken')||'';token.value=apiToken;
        const apiHeaders=json=>Object.assign(json?{'Content-Type':'application/json'}:{},apiToken?{'Authorization':'Bearer '+apiToken}:{});
        saveToken.addEventListener('click',()=>{apiToken=token.value.trim();localStorage.setItem('sdrtrunkWebToken',apiToken);refresh()});
        let liveOn=false,liveSequence=-1,audioUrl=null;
        liveToggle.addEventListener('click',()=>{liveOn=!liveOn;liveToggle.textContent=liveOn?'Stop Live Listening':'Start Live Listening';liveStatus.textContent=liveOn?'Waiting for traffic':'Off';if(liveOn)pollLive()});
        async function useAudio(r,label){if(!r.ok)return;if(audioUrl)URL.revokeObjectURL(audioUrl);audioUrl=URL.createObjectURL(await r.blob());audioPlayer.src=audioUrl;liveStatus.textContent=label;try{await audioPlayer.play()}catch(e){liveStatus.textContent=label+' — press Play'}}
        function showLive(m){if(!m||!m.sequence)return;activeTalkgroup.textContent=m.talkgroup||'—';activeAlias.textContent=m.alias||'Unidentified';activeFrequency.textContent=mhz(m.frequency);activeSource.textContent=m.source||'—';signalText.textContent=m.rfSignalAvailable?m.rfSignalDbm+' dBm':'Unavailable';const db=Number(m.audioLevelDbfs);audioLevelText.textContent=Number.isFinite(db)?db.toFixed(1)+' dBFS':'—';signalMeter.style.width=Number.isFinite(db)?Math.max(0,Math.min(100,(db+60)/60*100))+'%':'0'}
        async function pollLive(){if(!liveOn)return;try{const r=await fetch('/api/v1/live-audio?after='+liveSequence,{headers:apiHeaders(false)});if(r.status===200){liveSequence=Number(r.headers.get('X-Audio-Sequence'));const status=await fetch('/api/v1/live-status',{headers:apiHeaders(false)}).then(x=>x.json());showLive(status);await useAudio(r,'Playing '+(status.alias||status.talkgroup||'live traffic'))}}catch(e){liveStatus.textContent=e.message}finally{if(liveOn)setTimeout(pollLive,1000)}}
        async function playRecording(name){const r=await fetch('/api/v1/recording-audio?name='+encodeURIComponent(name),{headers:apiHeaders(false)});await useAudio(r,'Playing '+name)}
        async function control(name,action){await fetch('/api/v1/channel-control',{method:'POST',headers:apiHeaders(true),body:JSON.stringify({name,action})});refresh()}
        let channelCache=[];
        function openChannelEditor(id){const x=channelCache.find(c=>c.id===id)||{};channelForm.reset();for(const k of ['id','name','system','site','frequency','decoder','aliasList'])if(x[k]!=null)channelForm.elements[k].value=x[k];channelForm.elements.autoStart.checked=!!x.autoStart;deleteChannel.style.display=id==null?'none':'inline-block';channelResult.textContent='';channelDialog.showModal()}
        channelForm.addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(channelForm),body=Object.fromEntries(f);body.action=body.id?'update':'create';if(body.id)body.id=Number(body.id);body.frequency=Number(body.frequency||0);body.autoStart=f.has('autoStart');const r=await fetch('/api/v1/channels',{method:'POST',headers:apiHeaders(true),body:JSON.stringify(body)});channelResult.textContent=r.ok?'Saved':(await r.json()).error;if(r.ok){channelDialog.close();refresh()}});
        deleteChannel.addEventListener('click',async()=>{if(!confirm('Delete this channel?'))return;const r=await fetch('/api/v1/channels',{method:'POST',headers:apiHeaders(true),body:JSON.stringify({action:'delete',id:Number(channelForm.elements.id.value)})});if(r.ok){channelDialog.close();refresh()}else channelResult.textContent=(await r.json()).error});
        let talkgroupCache=[],remoteCache=[];
        function openTalkgroupEditor(id,aliasList){const x=talkgroupCache.find(t=>t.id===id)||{aliasList:aliasList||'',priority:100,protocol:'APCO25',remoteCalls:[]};talkgroupForm.reset();for(const k of ['id','aliasList','talkgroup','name','group','protocol','priority'])if(x[k]!=null)talkgroupForm.elements[k].value=x[k];talkgroupForm.elements.record.checked=!!x.record;talkgroupRemoteCalls.innerHTML=remoteCache.map(d=>`<label><input type="checkbox" name="remoteCalls" value="${esc(d.name)}" ${(x.remoteCalls||[]).includes(d.name)?'checked':''}> ${esc(d.name)}</label>`).join('')||'<span class="muted">Add a Remote Calls destination below first.</span>';deleteTalkgroup.style.display=id==null?'none':'inline-block';talkgroupResult.textContent='';talkgroupDialog.showModal()}
        talkgroupForm.addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(talkgroupForm),body=Object.fromEntries(f);body.action=body.id?'update':'create';if(body.id)body.id=Number(body.id);body.talkgroup=Number(body.talkgroup);body.priority=Number(body.priority||100);body.record=f.has('record');body.remoteCalls=f.getAll('remoteCalls');const r=await fetch('/api/v1/talkgroups',{method:'POST',headers:apiHeaders(true),body:JSON.stringify(body)});const j=await r.json();talkgroupResult.textContent=r.ok?'Saved':j.error;if(r.ok){talkgroupDialog.close();refresh()}});
        deleteTalkgroup.addEventListener('click',async()=>{if(!confirm('Delete this talkgroup alias?'))return;const r=await fetch('/api/v1/talkgroups',{method:'POST',headers:apiHeaders(true),body:JSON.stringify({action:'delete',id:Number(talkgroupForm.elements.id.value)})});if(r.ok){talkgroupDialog.close();refresh()}else talkgroupResult.textContent=(await r.json()).error});
        function openRemoteEditor(name){const x=remoteCache.find(d=>d.name===name)||{enabled:true,maximumRetries:5,maximumConcurrentUploads:2,requestTimeoutSeconds:60,maximumRecordingAgeSeconds:600,apiKeyEnvironmentVariable:'SDRTRUNK_REMOTE_API_KEY',authenticationHeader:'Authorization',authenticationPrefix:'Bearer ',openAiKeyEnvironmentVariable:'OPENAI_API_KEY'};remoteForm.reset();for(const k of ['originalName','name','url','apiKeyEnvironmentVariable','authenticationHeader','authenticationPrefix','maximumRetries','maximumConcurrentUploads','requestTimeoutSeconds','maximumRecordingAgeSeconds','openAiKeyEnvironmentVariable','localWhisperExecutable','localWhisperModel'])remoteForm.elements[k].value=k==='originalName'?(name||''):(x[k]??'');for(const k of ['enabled','openAiEnabled','translateToEnglish'])remoteForm.elements[k].checked=!!x[k];deleteRemote.style.display=name?'inline-block':'none';remoteResult.textContent='';remoteDialog.showModal()}
        remoteForm.addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(remoteForm),body=Object.fromEntries(f);body.action=body.originalName?'update':'create';for(const k of ['enabled','openAiEnabled','translateToEnglish'])body[k]=f.has(k);for(const k of ['maximumRetries','maximumConcurrentUploads','requestTimeoutSeconds','maximumRecordingAgeSeconds'])body[k]=Number(body[k]);const r=await fetch('/api/v1/remote-destinations',{method:'POST',headers:apiHeaders(true),body:JSON.stringify(body)});const j=await r.json();remoteResult.textContent=r.ok?'Saved':j.error;if(r.ok){remoteDialog.close();refresh()}});
        deleteRemote.addEventListener('click',async()=>{if(!confirm('Delete this Remote Calls destination?'))return;const name=remoteForm.elements.originalName.value,r=await fetch('/api/v1/remote-destinations',{method:'POST',headers:apiHeaders(true),body:JSON.stringify({action:'delete',originalName:name})});if(r.ok){remoteDialog.close();refresh()}else remoteResult.textContent=(await r.json()).error});
        async function refresh(){try{const [s,t,c,b,r,a,l,tg,rd]=await Promise.all(['status','tuners','channels','broadcasters','recordings','activity','live-status','talkgroups','remote-destinations'].map(x=>fetch('/api/v1/'+x,{headers:apiHeaders(false)}).then(r=>{if(!r.ok)throw Error(r.status===401?'Access token required':'HTTP '+r.status);return r.json()})));cpu.textContent=s.cpuAvailable?(s.cpu<.005?'<1%':(s.cpu*100).toFixed(1)+'%'):'Unavailable';memory.textContent=mb(s.memoryUsed)+' / '+mb(s.memoryMaximum);tunerCount.textContent=t.length;activeCount.textContent=c.filter(x=>x.processing).length;
        const current=a.length&&Date.now()-a[0].time<5000?a[0]:null;scanState.textContent=current?'RECEIVING':'SCANNING';scanState.className='scan-state'+(current?' receiving':'');if(current){activeTalkgroup.textContent=current.talkgroup;activeAlias.textContent=current.alias||'Unidentified';activeFrequency.textContent=mhz(current.frequency);activeSource.textContent=current.source||'—'}else if(l&&l.sequence){showLive(l)}
        channelCache=c.filter(x=>x.type==='STANDARD');talkgroupCache=tg;remoteCache=rd;tuners.innerHTML=t.map(x=>`<tr><td>${esc(x.name||x.id)}</td><td>${esc(x.status)}</td><td>${mhz(x.frequency)}</td></tr>`).join('');streams.innerHTML=b.map(x=>`<tr><td>${esc(x.name)}</td><td>${esc(x.type)}</td><td>${esc(x.state)}</td><td>${x.queue}</td></tr>`).join('');channels.innerHTML=c.map(x=>`<tr><td>${esc(x.system)}</td><td>${esc(x.site)}</td><td>${esc(x.name)}</td><td>${esc(x.decoder)}</td><td>${x.processing?'Active':'Stopped'}</td><td><button onclick="control(decodeURIComponent('${encodeURIComponent(x.name)}'),'${x.processing?'stop':'start'}')">${x.processing?'Stop':'Start'}</button> ${x.type==='STANDARD'?`<button onclick="openChannelEditor(${x.id})">Edit</button> <button onclick="openTalkgroupEditor(null,decodeURIComponent('${encodeURIComponent(x.aliasList||'')}'))">Add TG</button>`:''}</td></tr>`).join('');talkgroups.innerHTML=tg.map(x=>`<tr><td>${esc(x.aliasList)}</td><td>${x.talkgroup}</td><td>${esc(x.name)}</td><td>${esc(x.group)}</td><td>${esc(x.protocol)}</td><td>${x.record?'Yes':'No'}</td><td>${esc((x.remoteCalls||[]).join(', '))}</td><td><button onclick="openTalkgroupEditor(${x.id})">Edit</button></td></tr>`).join('');remoteDestinations.innerHTML=rd.map(x=>`<tr><td>${esc(x.name)}</td><td>${esc(x.url)}</td><td>${x.enabled?'Enabled':'Disabled'}</td><td>${x.openAiEnabled?(x.translateToEnglish?'Translate':'Transcribe'):(x.localWhisperExecutable?'Local Whisper':'Off')}</td><td><button onclick="openRemoteEditor(decodeURIComponent('${encodeURIComponent(x.name)}'))">Edit</button></td></tr>`).join('');recordings.innerHTML=r.map(x=>`<tr><td>${esc(x.name)}</td><td>${new Date(x.modified).toLocaleString()}</td><td>${mb(x.size)}</td><td><button onclick="playRecording(decodeURIComponent('${encodeURIComponent(x.name)}'))">Play</button></td></tr>`).join('');activity.innerHTML=a.slice(0,30).map(x=>`<tr><td>${new Date(x.time).toLocaleTimeString()}</td><td>${esc(x.talkgroup)}</td><td>${esc(x.alias||'Unidentified')}</td><td>${esc(x.source)}</td><td>${esc(x.protocol)}</td><td>${mhz(x.frequency)}</td><td>${esc(x.type)}</td></tr>`).join('');updated.textContent='Updated '+new Date().toLocaleTimeString()}catch(e){updated.textContent=e.message}}refresh();setInterval(refresh,2000);
        </script></body></html>""";
}
