# Remote Call API and heartbeat

The Remote Call API destination sends completed calls and optional online heartbeats to a user-supplied HTTP POST
URL. Both request types use the same authentication header, prefix, API key, timeout, and redirect policy.

## Configure a destination

Open the web console and select **Remote Calls > Add Destination**. Configure:

- **Name:** a unique destination name used by talkgroup alias routing.
- **POST URL:** the receiving API endpoint for calls and heartbeats.
- **API key environment variable:** defaults to `SDRTRUNK_REMOTE_API_KEY`.
- **Authentication header/prefix:** defaults to `Authorization` and `Bearer `.
- **Send heartbeat:** enables or disables online heartbeats for this destination.
- **Heartbeat interval seconds:** how often to send, with a minimum of 5 seconds and a default of 60 seconds.

Save the destination, then assign it to talkgroups under **Playlist > Talkgroups & Aliases**.

## Heartbeat request

When enabled, sdrtrunk immediately sends a heartbeat when the destination starts and repeats it at the selected
interval. The request uses `Content-Type: application/json` and includes `X-SDRTrunk-Event: heartbeat` so the receiving
server can distinguish it from an audio upload.

Example:

```http
POST /calls HTTP/1.1
Content-Type: application/json; charset=UTF-8
Authorization: Bearer your-api-key
User-Agent: sdrtrunk
X-SDRTrunk-Event: heartbeat
```

```json
{
  "event": "heartbeat",
  "status": "online",
  "connected": true,
  "timestamp": 1788667200000,
  "timestampIso": "2026-09-06T12:00:00Z",
  "application": "sdrtrunk",
  "version": "0.7.0-beta-9",
  "destination": "Dispatch API",
  "hostname": "scanner-host",
  "uptimeMs": 86400000,
  "queuedCalls": 0
}
```

The receiving server must return any HTTP 2xx status. A successful response updates the destination to **Connected**
and records the latest success time in the web console. A network failure or non-2xx response changes the state to
**Temporary Broadcast Error**, displays the error, and tries again at the next heartbeat interval.

Turning the heartbeat off stops heartbeat requests but does not disable call uploads. Disabling the entire destination
stops both calls and heartbeats.

## Completed-call request

Completed calls use `multipart/form-data` with:

- `metadata`: JSON describing the call ID, timestamp, duration, frequency, identifiers, and optional transcription.
- `audio`: the completed MP3 recording.

The call request also includes `User-Agent: sdrtrunk`, the configured authentication header, and an `Idempotency-Key`
containing the stable call ID. The receiving API should use that key to avoid creating duplicate calls during retries.

## Example receiver behavior

The same endpoint can branch using the content type or event header:

```text
if X-SDRTrunk-Event == "heartbeat":
    parse JSON body
    update scanner last-seen/online status
    return HTTP 204
else:
    parse multipart metadata and audio
    store the completed call
    return HTTP 201
```

For online monitoring, consider a scanner offline after it has missed two or three expected heartbeat intervals.

## Security

Store the API key in the configured environment variable rather than the playlist. Use HTTPS for destinations outside
the trusted local network. Heartbeats contain the scanner host name, software version, uptime, and queue size, but do
not contain talkgroup or audio data.
