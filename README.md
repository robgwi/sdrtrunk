# sdrtrunk Web and Raspberry Pi Fork

This repository is Rob Gwi's experimental fork of [DSheirer's sdrtrunk](https://github.com/DSheirer/sdrtrunk). It adds Raspberry Pi ARM64 packaging, an embedded web console, remote call delivery, browser audio, and additional Playlist Editor workflows while retaining the Java desktop application.

> This is a beta fork and is not an official upstream sdrtrunk release. Back up your playlist before testing it.

## Current release: 0.6.2-beta-6

- [Download Raspberry Pi ARM64 beta 6](https://github.com/robgwi/sdrtrunk/releases/tag/raspberry-pi-v0.6.2-beta-6)
- [Raspberry Pi installation guide](RASPBERRY_PI.md)
- [Upstream sdrtrunk wiki](https://github.com/DSheirer/sdrtrunk/wiki)

The Raspberry Pi archive contains its own ARM64 Java and JavaFX runtime. A separate Java installation is not needed. It supports the Java desktop plus web console when launched from Raspberry Pi Desktop or VNC, and headless receiver plus web console when launched without a display or with `-Djava.awt.headless=true`.

## Fork features

### Scanner-style web console

- Runs alongside both the desktop and headless application.
- Shows scanning/receiving state, talkgroup ID and configured alias/name, source radio, protocol, frequency, and recent decoded activity.
- Starts and stops configured channels remotely.
- Plays completed live calls in the top scanner panel with call metadata and a measured audio-level meter.
- Lists and plays MP3/WAV recordings from the configured recording directory.
- Shows tuner, channel, CPU, memory, and streaming-destination status.
- Uses bearer-token authentication for non-local API requests.

Signal strength is displayed as unavailable when a tuner does not expose calibrated RSSI through sdrtrunk's shared tuner interface. The dashboard does not fabricate a signal value.

### Web playlist management

The beta-6 web channel and talkgroup editor can:

- Create and delete channels.
- Edit channel name, system, site, alias-list assignment, frequency, protocol, and auto-start.
- Start and stop channels.
- Add, edit, and delete talkgroup aliases in each channel's assigned alias list.
- Supplement talkgroups previously imported through RadioReference without repeating the system import.
- Edit talkgroup name, category, protocol, numeric ID, recording flag, and playback priority.
- Select the Remote Calls destinations that receive each talkgroup, using the same alias routing model as the desktop app.
- Add, edit, enable, disable, and delete Remote Call API destinations, including authentication, retry, timeout, hosted Whisper, local Whisper, and translation settings.
- Include the configured talkgroup alias in saved audio filenames for easier browsing and identification.

Advanced protocol-specific decoder fields, non-talkgroup alias identifier types, and new online RadioReference system searches/imports still use the Java desktop Playlist Editor. Talkgroups from those imports can now be maintained or expanded in the web console.

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

## Raspberry Pi quick start

This build requires a Raspberry Pi 4 or 5 running a 64-bit operating system. Confirm that `uname -m` prints `aarch64`.

```bash
sudo apt update
sudo apt install libusb-1.0-0 unzip

unzip sdr-trunk-raspberry-pi-aarch64-linux-aarch64-v0.6.2-beta-6.zip
cd sdr-trunk-linux-aarch64-v0.6.2-beta-6
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
