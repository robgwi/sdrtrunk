# Raspberry Pi desktop and web build

This distribution targets a Raspberry Pi 4 or 5 running a 64-bit Raspberry Pi OS (`aarch64`).  Java 26 and JavaFX
are included in the archive; Java does not need to be installed separately on the Pi.

## Install required system library

```bash
sudo apt update
sudo apt install libusb-1.0-0
```

## Install and run

Copy `sdr-trunk-raspberry-pi-aarch64-linux-aarch64-v0.6.2-beta-3.zip` to the Pi, then run:

```bash
unzip sdr-trunk-raspberry-pi-aarch64-linux-aarch64-v0.6.2-beta-3.zip
cd sdr-trunk-linux-aarch64-v0.6.2-beta-3
export SDRTRUNK_WEB_TOKEN='replace-with-a-long-random-token'
bin/sdr-trunk
```

When launched from a terminal in the Raspberry Pi desktop or VNC session, the Java desktop interface opens and the
web interface runs at the same time.  When no graphical display is available, start it explicitly in headless mode:

```bash
JAVA_OPTS='-Djava.awt.headless=true' bin/sdr-trunk
```

Open `http://<raspberry-pi-address>:8080/` from another computer.  API clients must send the token as:

```text
Authorization: Bearer replace-with-a-long-random-token
```

The custom call destination key and hosted Whisper key can be supplied without putting them in the playlist:

```bash
export SDRTRUNK_REMOTE_API_KEY='destination-key'
export OPENAI_API_KEY='OpenAI-key'
```

For reliable operation, configure these environment variables in the service manager rather than a shell history.

## USB permissions

If the SDR is detected only when running as root, add an appropriate `udev` rule for the tuner instead of running
sdrtrunk as root.  The vendor and product IDs can be obtained with `lsusb`.
