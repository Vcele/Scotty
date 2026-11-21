# Scotty

*Beam me up.* Cast from your Pixel to the screens and speakers Google left behind.
A Material You (dynamic color) Android app with its protocol core written in Rust.

| Mode | Protocol | What you get |
|---|---|---|
| **Miracast** | Wi-Fi Display source: Wi-Fi Direct + RTSP (M1–M16) + H.264/AAC in MPEG-TS over RTP | Live screen mirroring with sound, up to 1080p30 / 720p60. No router needed. |
| **AirPlay · Audio only** | RAOP: lossless ALAC, NTP timing, sync packets, retransmits, volume | Everything the phone plays (Spotify, YouTube Music, podcasts…). |
| **AirPlay · Screen + audio** | Live HLS served from the phone + AirPlay video `POST /play` | Screen and sound on Apple TV / AirPlay TVs, ~3–5 s behind. |

## Architecture

```
┌──────────────── Kotlin (app/) ────────────────┐      ┌────────── Rust (core/) ──────────┐
│ Compose UI · CastService (foreground)         │ JNI  │ wfd.rs        Miracast source     │
│ MediaProjection → MediaCodec H.264 / AAC      │ ───▶ │ raop.rs       AirPlay audio       │
│ AudioPlaybackCapture → PCM                    │ push │ airplay_video HLS + /play         │
│ Wi-Fi Direct (P2P) · mDNS (NsdManager)        │ ◀─── │ ts.rs         MPEG-TS muxer       │
│ cast/NativeSession.kt ← polls core events     │ poll │ rtsp.rs       RTSP client/server  │
└───────────────────────────────────────────────┘      └───────────────────────────────────┘
```

Kotlin keeps only what needs Android APIs. The core owns every wire protocol and reports back
through a small event queue (`status`, `live`, `mode`, `idr`, `ended`) that Kotlin polls.
`mode` tells the app which resolution, bitrate and H.264 level to start the encoder with,
after the core has negotiated them with the receiver.

## Build & install

```sh
nix develop                     # JDK 17, Gradle, SDK 36, NDK, Rust + Android targets, cargo-ndk
./gradlew assembleRelease       # also builds core/ for arm64-v8a + x86_64 via cargo-ndk
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the debug key so it can be sideloaded directly.

## Tests

```sh
cd core
cargo test                                    # unit + protocol tests (synthetic media)
SCOTTY_MEDIA=<dir with in.h264 + in.aac> SCOTTY_OUT=<dir> cargo test   # real media, writes .ts files for ffprobe
cargo run --example raop_tone -- HOST PORT 5  # stream a 440 Hz tone to any AirPlay receiver
```

* `tests/miracast_sink.rs`: plays the TV side of a Miracast session (M1–M7, IDR request,
  TEARDOWN) and checks the RTP/TS stream it receives.
* `tests/airplay_video.rs`: plays an Apple TV: accepts `/play`, pulls the HLS playlist and
  segments, and checks `/playback-info` and `/stop`.
* AirPlay audio was verified against **shairport-sync** (in the dev shell). With
  `shairport-sync -p 5100 -m tinysvcmdns -o stdout > out.raw`, the receiver plays back the exact
  test tone.

### Waydroid

Waydroid runs the x86_64 build. Waydroid has no Wi-Fi Direct and its mDNS doesn't see the host,
so use **Add by IP** on the AirPlay tab (host = `192.168.240.1`) and a shairport-sync on the host
to test the full flow: app → playback capture → JNI → Rust → receiver.

## Limits

* **Miracast is implemented entirely in the app.** Regular apps can't put the Wi-Fi Display
  information element into Wi-Fi Direct (that's a privileged API), so Scotty isn't advertised as a
  "Miracast source". Most TVs and dongles connect anyway, but strict sinks may refuse. Audio is AAC,
  so a sink that only takes LPCM mirrors without sound.
* **AirPlay audio** works with AirPlay 1 receivers and most AirPlay 2 receivers (TVs, Sonos,
  Denon/Marantz, Bose, shairport-sync, AirPort Express). Receivers that require HomeKit pairing
  (HomePods, and Apple TVs with strict access settings) aren't supported yet. Set AirPlay access to
  "Everyone on the same network" without a password.
* **AirPlay screen** uses HLS because real-time mirroring requires Apple's FairPlay.
* Apps that opt out of audio capture stay silent. Spotify works.
