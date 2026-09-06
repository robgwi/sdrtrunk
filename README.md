# sdrtrunk Web and Raspberry Pi Fork

This repository is Rob Gwi's experimental fork of [DSheirer's sdrtrunk](https://github.com/DSheirer/sdrtrunk). It adds Raspberry Pi ARM64 packaging, an embedded web console, remote call delivery, browser audio, and additional Playlist Editor workflows while retaining the Java desktop application.

> This is a beta fork and is not an official upstream sdrtrunk release. Back up your playlist before testing it.

## Current release: 0.7.0-beta-4

- [Download Raspberry Pi ARM64 0.7 beta 4](https://github.com/robgwi/sdrtrunk/releases/tag/raspberry-pi-v0.7.0-beta-4)
- [Raspberry Pi installation guide](RASPBERRY_PI.md)
- [Upstream sdrtrunk wiki](https://github.com/DSheirer/sdrtrunk/wiki)

The Raspberry Pi archive contains its own ARM64 Java and JavaFX runtime. A separate Java installation is not needed. It supports the Java desktop plus web console when launched from Raspberry Pi Desktop or VNC, and headless receiver plus web console when launched without a display or with `-Djava.awt.headless=true`.

## Fork features

### Scanner-style web console

- Runs alongside both the desktop and headless application.
- Keeps the main page focused on the live police-scanner display; management tools open from the top menu in modal windows.
- Shows scanning/receiving state, talkgroup ID and configured alias/name, source radio, protocol, frequency, and recent decoded activity.
- Clears the previous talkgroup, alias, frequency, source, signal, and level as soon as the receiver returns to scanning.
- Starts and stops configured channels remotely.
- Queues completed live calls in order and plays every call to completion before applying the configurable between-call hold time.
- Shows the current call metadata, measured audio level, and number of waiting calls in the top scanner panel.
- Lists and plays MP3/WAV recordings from the configured recording directory.
- Shows tuner, channel, CPU, memory, and streaming-destination status.
- Uses bearer-token authentication for non-local API requests.

Signal strength is displayed as unavailable when a tuner does not expose calibrated RSSI through sdrtrunk's shared tuner interface. The dashboard does not fabricate a signal value.

### Web playlist management

The web channel and talkgroup editor can:

- Create and delete channels.
- Edit channel name, system, site, alias-list assignment, frequency, protocol, and auto-start.
- Start and stop channels.
- Add, edit, and delete talkgroup aliases in each channel's assigned alias list.
- Configure and test a RadioReference Premium account using the same shared service as the desktop editor.
- Browse country, state, county, statewide systems, and county systems; then preview and selectively import trunked-system talkgroups without duplicates.
- Filter RadioReference talkgroups by category or search text, create an alias list, identify previously imported entries, and optionally mark fully encrypted imports as Do Not Monitor.
- Edit talkgroup name, category, protocol, numeric ID, recording flag, and playback priority.
- Select the Remote Calls destinations that receive each talkgroup, using the same alias routing model as the desktop app.
- Add, edit, enable, disable, and delete Remote Call API destinations, including authentication, retry, timeout, hosted Whisper, local Whisper, and translation settings.
- Include the configured talkgroup alias in saved audio filenames for easier browsing and identification.

Advanced protocol-specific decoder fields, site/channel creation, conventional agency-frequency imports, and non-talkgroup alias identifier types still use the Java desktop Playlist Editor. The web RadioReference workflow now mirrors the desktop trunked-talkgroup import path.

### Desktop Playlist Editor improvements

- Change an existing channel protocol without deleting and recreating the channel.
- Choose a protocol while cloning a channel.
- Preserve general channel settings while resetting incompatible protocol-specific decoder options.
- Manage the web access token and restart the embedded web server from **Playlist Editor > Web Interface**.

### Remote call API and transcription

- POST completed calls and metadata to a configurable API URL with an MP3 attachment.
- Authenticate using an API key stored in an environment variable.
- Transcribe locally or use OpenAI `whisper-1`.
- Optionally translate supported audio to English through the hosted OpenAI workflow.
- Existing Rdio Scanner uploads continue to use `<Rdio Scanner URL>/api/call-upload`.

### Background scanner transcription

- Runs the [robgwi Whisper fork](https://github.com/robgwi/whisper) as a separate background process, so SDR decoding remains in Java and does not wait for transcription.
- Configures the executable, model, language, transcribe/translate task, timeout, scanner vocabulary prompt, numeric cleanup, and optional PII redaction from the web console.
- Recognizes scanner phonetic-alphabet runs, compacts spoken digits and license plates, and shows completed transcripts with their talkgroup and alias.
- Pins locations found from transcript text on an OpenStreetMap preview using Nominatim geocoding.

Whisper, PyTorch, ffmpeg, and model weights are intentionally not bundled in the Java archive. On a Raspberry Pi, start with `tiny.en` or `base.en`; larger models may be too slow or memory-intensive.

## Raspberry Pi quick start

This build requires a Raspberry Pi 4 or 5 running a 64-bit operating system. Confirm that `uname -m` prints `aarch64`.

```bash
sudo apt update
sudo apt install libusb-1.0-0 unzip

unzip sdr-trunk-raspberry-pi-aarch64-linux-aarch64-v0.7.0-beta-4.zip
cd sdr-trunk-linux-aarch64-v0.7.0-beta-4
bin/sdr-trunk
```

Open `http://<raspberry-pi-address>:8080/` from another computer. A desktop/VNC first launch asks you to create a web access token. A first headless launch generates a token, saves it, and writes it once to the startup log.

To supply secrets through the launch environment instead:

```bash
export SDRTRUNK_WEB_TOKEN='replace-with-a-long-random-token'
export SDRTRUNK_REMOTE_API_KEY='remote-destination-key'
export OPENAI_API_KEY='OpenAI-key'
bin/sdr-trunk
```

Environment variables override saved GUI token settings. Do not commit real keys or tokens to this repository.

## Release history

### 0.7.0-beta-4

- Rebuilt the web RadioReference modal around the desktop editor's trunked-talkgroup workflow.
- Fixed sign-in failures being reported as HTTP 400; invalid, expired, unavailable, and Premium results now appear as readable account status.
- Applied successful credentials to the application's shared RadioReference service and restored saved credentials when the web service initializes.
- Added Country, State, County, and Trunked System browsing with preferred-location restoration.
- Added category/search filtering, individual and visible-row selection, alias-list creation, existing-alias detection, and selective imports.
- Added optional Do Not Monitor priority for fully encrypted imported talkgroups.
- Improved upstream/network error status while reserving HTTP 400 for malformed user input.

### 0.7.0-beta-3

- Simplified the main web page to a dedicated police-scanner dashboard.
- Moved System, Playlist, RadioReference, Recordings, Transcripts, Remote Calls, and Settings into responsive modal windows opened from the top menu.
- Added a dedicated System modal for CPU, memory, tuners, active channels, streaming destinations, and recent activity.
- Removed the completed-audio fallback from the real-time display so SCANNING never shows the last call's talkgroup, alias, frequency, source, signal, or audio level.
- Kept queued MP3 playback status separate from the real-time receiver state.

### 0.7.0-beta-2

- Fixed Live Listening replacing an in-progress call when the next completed audio file arrived.
- Added an ordered server-side buffer of up to 200 completed calls so bursts are not lost between browser polls.
- Made the browser wait for each call to finish before fetching and playing the next queued call.
- Added a browser-persisted hold-time setting, defaulting to 0.7 seconds between calls, plus a queued-call count.
- Kept displayed talkgroup, alias, source, frequency, and signal metadata synchronized with the call actually playing.
- Made manual Recorded Audio playback stop Live Listening explicitly instead of silently replacing its audio.

### 0.7.0-beta-1

- Redesigned the web console around a persistent top menu for dashboard, playlist, RadioReference, transcripts, Remote Calls, and settings.
- Added web-based RadioReference Premium credential testing plus trunked-system talkgroup preview/import into an alias list, with protocol-aware values and duplicate protection.
- Added queued background transcription through the `robgwi/whisper` command-line application.
- Added web settings for Whisper model, language, task, executable, timeout, scanner vocabulary, number cleanup, PII redaction, and map region.
- Added scanner phonetic-alphabet and numeric post-processing, transcript metadata, Nominatim address lookup, and OpenStreetMap pinning.
- Added focused tests for scanner transcription normalization and PII redaction.

### 0.6.2-beta-9

- Fixed `/api/v1/live-status` returning HTTP 500 after receiving non-finite audio samples.
- Sanitized live audio-level calculations so the endpoint always emits valid JSON numbers.
- Ignored blank alias names while preparing live scanner metadata.
- Added a final compatibility repair for non-finite levels already present in a live-status snapshot.

### 0.6.2-beta-8

- Fixed web API exceptions closing connections and appearing only as **Failed to fetch**.
- Isolated dashboard data requests so one failed panel no longer prevents the rest of the console from updating.
- Added endpoint-specific error details to the dashboard and application log.
- Snapshot live channel, tuner, broadcaster, destination, and alias collections during web requests to prevent concurrent-update failures.

### 0.6.2-beta-7

- Renamed the visible web interface header and browser title to **SDR-Trunk Web Console**.

### 0.6.2-beta-6

- Added the resolved talkgroup alias to recorded MP3/WAV filenames.
- Sanitized alias text using the existing recording filename safety and length handling.
- Preserved the talkgroup ID, source radio, timestamp, duplicate suffix, and recording format in filenames.

### 0.6.2-beta-5

- Expanded web Playlist Channels management with talkgroup and alias maintenance.
- Added manual talkgroup additions for supplementing RadioReference-imported systems.
- Added talkgroup recording, playback-priority, and per-Remote-Calls destination routing controls.
- Upgraded Remote Calls management from add-only to complete add, edit, enable/disable, and delete workflows.
- Exposed Remote Calls authentication, retry, concurrency, timeout, maximum-age, local Whisper, hosted Whisper, and translation settings.

### 0.6.2-beta-4

- Promoted the accumulated Raspberry Pi and web work to a distinct beta-4 release.
- Added the scanner-style receiving display and decoded activity history.
- Added core browser channel creation, editing, protocol/frequency changes, auto-start, start/stop, and deletion.
- Consolidated installation, security, feature, limitation, and release-history documentation.
- Expanded Live Traffic with talkgroup aliases, per-call metadata, audio dBFS level, and corrected headless CPU/memory reporting.

### 0.6.2-beta-3

- Added a self-contained Raspberry Pi Linux ARM64 distribution.
- Enabled desktop/VNC and headless operation from one package while keeping the web console available in both modes.
- Added first-run web-token creation, GUI token editing, and web-server restart controls.
- Added authenticated live call listening and recorded-audio playback.
- Added direct protocol changes and protocol selection while cloning Playlist Editor channels.

### Early beta work

- Added the embedded web server and initial status dashboard.
- Added the Remote Call API broadcast destination and completed MP3 upload.
- Added local and hosted Whisper transcription/translation processing.
- Added remote channel and streaming-destination status APIs.

## Building from source

```bash
./gradlew test
./gradlew runtimeZipRaspberryPi
```

The Raspberry Pi ZIP is written under `build/image/`.

## Upstream project

sdrtrunk is a cross-platform Java application for decoding, monitoring, recording, and streaming trunked and related radio systems using software-defined radios.

- [Upstream getting started](https://github.com/DSheirer/sdrtrunk/wiki/Getting-Started)
- [Upstream user manual](https://github.com/DSheirer/sdrtrunk/wiki/User-Manual)
- [Upstream support](https://github.com/DSheirer/sdrtrunk/wiki/Support)
- [Official upstream releases](https://github.com/DSheirer/sdrtrunk/releases)

This fork remains licensed under the GNU General Public License version 3 or later, consistent with the upstream project. See [LICENSE](LICENSE) for the license text.
