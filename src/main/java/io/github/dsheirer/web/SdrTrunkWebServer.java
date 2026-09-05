package io.github.dsheirer.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.audio.broadcast.ConfiguredBroadcast;
import io.github.dsheirer.audio.broadcast.remote.RemoteApiConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedded, headless-safe HTTP API and initial browser dashboard. */
public class SdrTrunkWebServer
{
    private static final Logger mLog = LoggerFactory.getLogger(SdrTrunkWebServer.class);
    private static final Gson GSON = new Gson();
    private static final long STARTED = System.currentTimeMillis();
    private final PlaylistManager mPlaylistManager;
    private final TunerManager mTunerManager;
    private final ResourceMonitor mResourceMonitor;
    private final String mToken;
    private HttpServer mServer;

    public SdrTrunkWebServer(PlaylistManager playlistManager, TunerManager tunerManager,
                             ResourceMonitor resourceMonitor)
    {
        mPlaylistManager = playlistManager;
        mTunerManager = tunerManager;
        mResourceMonitor = resourceMonitor;
        mToken = System.getenv("SDRTRUNK_WEB_TOKEN");
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
        mServer.createContext("/api/v1/tuners", authenticated(this::tuners));
        mServer.createContext("/api/v1/broadcasters", authenticated(this::broadcasters));
        mServer.createContext("/api/v1/remote-destinations", authenticated(this::remoteDestinations));
        mServer.createContext("/api/v1/channel-control", authenticated(this::channelControl));
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
        result.put("cpu", mResourceMonitor.cpuPercentageProperty().get());
        result.put("memoryUsed", mResourceMonitor.memoryUsedProperty().get());
        result.put("memoryAllocated", mResourceMonitor.memoryAllocatedProperty().get());
        result.put("memoryMaximum", mResourceMonitor.memoryTotalProperty().get());
        result.put("recordingsSize", mResourceMonitor.fileSizeRecordingsProperty().get());
        result.put("eventLogsSize", mResourceMonitor.fileSizeEventLogsProperty().get());
        json(exchange, 200, result);
    }

    private void channels(HttpExchange exchange) throws IOException
    {
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
        value.put("system", channel.getSystem());
        value.put("site", channel.getSite());
        value.put("type", channel.getChannelType().name());
        value.put("processing", channel.isProcessing());
        value.put("aliasList", channel.getAliasListName());
        value.put("decoder", channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType().name() : null);
        value.put("source", channel.getSourceConfiguration() != null ?
            channel.getSourceConfiguration().toString() : null);
        return value;
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
        if(!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try
        {
            JsonObject request = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8), JsonObject.class);
            String name = requiredString(request, "name");
            String url = requiredString(request, "url");
            RemoteApiConfiguration configuration = new RemoteApiConfiguration();
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
            ConfiguredBroadcast created = mPlaylistManager.getBroadcastModel().addBroadcastConfiguration(configuration);
            if(created == null)
            {
                json(exchange, 409, Map.of("error", "destination already exists"));
                return;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", configuration.getName());
            response.put("url", configuration.getHost());
            response.put("enabled", configuration.isEnabled());
            response.put("apiKeyEnvironmentVariable", configuration.getApiKeyEnvironmentVariable());
            response.put("openAiEnabled", configuration.isOpenAiEnabled());
            response.put("translateToEnglish", configuration.isTranslateToEnglish());
            json(exchange, 201, response);
        }
        catch(IllegalArgumentException e)
        {
            json(exchange, 400, Map.of("error", e.getMessage()));
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
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px system-ui,sans-serif}header{padding:20px 28px;border-bottom:1px solid var(--line);display:flex;align-items:center;gap:15px;justify-content:space-between;flex-wrap:wrap}h1{margin:0;font-size:21px}main{padding:22px;display:grid;gap:18px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.card,section{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}.value{font-size:24px;color:var(--accent);margin-top:7px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid var(--line)}th,.muted{color:var(--muted)}button{background:var(--accent);border:0;border-radius:5px;padding:6px 10px;color:#05251b;font-weight:700}input{background:#0a141c;color:var(--text);border:1px solid var(--line);border-radius:5px;padding:7px}h2{font-size:16px;margin:0 0 12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}@media(max-width:850px){.grid{grid-template-columns:1fr}}
        </style></head><body><header><h1>sdrtrunk web console</h1><div><input id="token" type="password" placeholder="Web access token" autocomplete="current-password"> <button id="saveToken">Connect</button></div><span id="updated" class="muted">connecting</span></header><main>
        <div class="cards"><div class="card">CPU<div class="value" id="cpu">—</div></div><div class="card">Memory<div class="value" id="memory">—</div></div><div class="card">Tuners<div class="value" id="tunerCount">—</div></div><div class="card">Active channels<div class="value" id="activeCount">—</div></div></div>
        <div class="grid"><section><h2>Tuners</h2><table><thead><tr><th>Name</th><th>Status</th><th>Frequency</th></tr></thead><tbody id="tuners"></tbody></table></section>
        <section><h2>Streaming destinations</h2><table><thead><tr><th>Name</th><th>Type</th><th>State</th><th>Queue</th></tr></thead><tbody id="streams"></tbody></table></section></div>
        <section><h2>Channels</h2><table><thead><tr><th>System</th><th>Site</th><th>Name</th><th>Decoder</th><th>Status</th><th>Control</th></tr></thead><tbody id="channels"></tbody></table></section>
        <section><h2>Add Remote Call API destination</h2><form id="remoteForm" class="cards"><label>Name<br><input name="name" required></label><label>POST URL<br><input name="url" type="url" required></label><label>API key environment variable<br><input name="apiKeyEnvironmentVariable" value="SDRTRUNK_REMOTE_API_KEY"></label><label><input name="openAiEnabled" type="checkbox"> OpenAI Whisper</label><label><input name="translateToEnglish" type="checkbox"> Translate to English</label><div><button type="submit">Add destination</button> <span id="remoteResult" class="muted"></span></div></form></section>
        </main><script>
        const esc=s=>String(s??'').replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));
        const mhz=n=>n?`${(n/1e6).toFixed(5)} MHz`:'—'; const mb=n=>n?`${(n/1048576).toFixed(1)} MB`:'—';
        let apiToken=localStorage.getItem('sdrtrunkWebToken')||'';token.value=apiToken;
        const apiHeaders=json=>Object.assign(json?{'Content-Type':'application/json'}:{},apiToken?{'Authorization':'Bearer '+apiToken}:{});
        saveToken.addEventListener('click',()=>{apiToken=token.value.trim();localStorage.setItem('sdrtrunkWebToken',apiToken);refresh()});
        async function control(name,action){await fetch('/api/v1/channel-control',{method:'POST',headers:apiHeaders(true),body:JSON.stringify({name,action})});refresh()}
        remoteForm.addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(remoteForm),body=Object.fromEntries(f);body.openAiEnabled=f.has('openAiEnabled');body.translateToEnglish=f.has('translateToEnglish');const r=await fetch('/api/v1/remote-destinations',{method:'POST',headers:apiHeaders(true),body:JSON.stringify(body)});const j=await r.json();remoteResult.textContent=r.ok?'Destination added':j.error;refresh()});
        async function refresh(){try{const [s,t,c,b]=await Promise.all(['status','tuners','channels','broadcasters'].map(x=>fetch('/api/v1/'+x,{headers:apiHeaders(false)}).then(r=>{if(!r.ok)throw Error(r.status===401?'Access token required':'HTTP '+r.status);return r.json()})));cpu.textContent=(s.cpu*100).toFixed(1)+'%';memory.textContent=mb(s.memoryUsed);tunerCount.textContent=t.length;activeCount.textContent=c.filter(x=>x.processing).length;
        tuners.innerHTML=t.map(x=>`<tr><td>${esc(x.name||x.id)}</td><td>${esc(x.status)}</td><td>${mhz(x.frequency)}</td></tr>`).join('');streams.innerHTML=b.map(x=>`<tr><td>${esc(x.name)}</td><td>${esc(x.type)}</td><td>${esc(x.state)}</td><td>${x.queue}</td></tr>`).join('');channels.innerHTML=c.map(x=>`<tr><td>${esc(x.system)}</td><td>${esc(x.site)}</td><td>${esc(x.name)}</td><td>${esc(x.decoder)}</td><td>${x.processing?'Active':'Stopped'}</td><td><button onclick="control(decodeURIComponent('${encodeURIComponent(x.name)}'),'${x.processing?'stop':'start'}')">${x.processing?'Stop':'Start'}</button></td></tr>`).join('');updated.textContent='Updated '+new Date().toLocaleTimeString()}catch(e){updated.textContent=e.message}}refresh();setInterval(refresh,2000);
        </script></body></html>""";
}
