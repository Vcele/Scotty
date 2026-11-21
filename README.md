# Scotty

*Beam me up.* Cast from Android to the screens and speakers Google left behind: Miracast
displays and AirPlay speakers/TVs, including HomePods and Apple TVs.
A Material You app with its protocol core written in Rust.

| Mode | Protocol | What you get |
|---|---|---|
| **Miracast** | Wi-Fi Display source: Wi-Fi Direct + RTSP (M1–M16) + H.264/AAC in MPEG-TS over RTP | Live screen mirroring with sound, up to 1080p30 / 720p60. No router needed. |
| **AirPlay · Audio** | AirPlay 1 (RAOP) or AirPlay 2 (HomeKit pairing, encrypted realtime stream), chosen automatically | Everything the phone plays (Spotify, YouTube Music, podcasts…), lossless. |
| **AirPlay · Screen + audio** | Live HLS from the phone + AirPlay video `POST /play` (paired + encrypted for Apple TV) | Screen and sound on Apple TV / AirPlay TVs, ~3–5 s behind. |

## Feels like part of the system

* **Quick Settings tile "Scotty"**: tap to send audio to your last AirPlay speaker, tap again to
  bring it back to the phone. Long-press opens the speaker picker.
* **Speaker picker**: an iOS-style "Play audio on…" sheet that floats over any app. Also available
  as a launcher shortcut (long-press the Scotty icon).
* **Volume keys and volume panel control the speaker** while beaming, and the phone itself stays
  silent. Optional, on by default.
* **System media controls** show the active cast with Android's own "Other device" output chip.
* **Pairing is remembered.** Apple TVs ask for an on-screen PIN once; HomePods pair silently.

Android doesn't let third-party apps add entries to the system output switcher for other apps'
media, so the tile and picker are the closest equivalent.

## Architecture

```
┌──────────────── Kotlin (app/) ────────────────┐      ┌─────────── Rust (core/) ───────────┐
│ Compose UI · tile · picker · CastService      │ JNI  │ wfd.rs         Miracast source      │
│ MediaProjection → MediaCodec H.264 / AAC      │ ───▶ │ airplay_audio  AirPlay 1 ↔ 2 choice │
│ AudioPlaybackCapture → PCM                    │ push │ raop.rs / ap2.rs  sessions          │
│ Wi-Fi Direct (P2P) · mDNS (NsdManager)        │ ◀─── │ rtp_audio.rs   ALAC/RTP/NTP/resend  │
│ MediaSession remote volume · mute / key remap │ poll │ hap/           SRP, TLV8, pairing,  │
│ cast/NativeSession.kt: events, reconnect      │      │                ChaCha20 channel     │
│                                               │      │ airplay_video  HLS + /play          │
│                                               │      │ ts.rs · rtsp.rs · netutil.rs        │
└───────────────────────────────────────────────┘      └────────────────────────────────────┘
```

Kotlin keeps only what needs Android APIs. The core owns every wire protocol and reports back
through a small event queue (`status`, `live`, `mode`, `idr`, `pin`, `creds`, `ended`).

### Stability measures

* **Miracast**: a paced RTP sender smooths key-frame bursts to 3× the bitrate. If the link stalls,
  it drops the backlog and requests an IDR frame so the picture recovers instead of lagging.
  Packets are marked for the Wi-Fi video queue (WMM AC_VI). If the TV rejects a format, Scotty
  steps down to a lower one. Keep-alives and IDR requests are answered.
* **AirPlay audio**: a 2 s receiver buffer, retransmission from a 2048-packet history, silence
  fill when capture stalls, and a bounded backlog. Packets go to the Wi-Fi voice queue (AC_VO),
  TCP keep-alive is on, and a low-latency Wi-Fi lock is held.
* **Auto-reconnect**: a live AirPlay session that drops is re-established up to 3 times, and
  capture keeps running.

## Build & install

```sh
nix develop                     # JDK 17, Gradle, SDK 36, NDK, Rust + Android targets, cargo-ndk
./gradlew assembleRelease       # also builds core/ for arm64-v8a + x86_64 via cargo-ndk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release signing reads `SCOTTY_KEYSTORE`, `SCOTTY_KEYSTORE_PASSWORD`, `SCOTTY_KEY_ALIAS` and
`SCOTTY_KEY_PASSWORD`. Without them, the build is signed with the debug key. Pushing a `v*` tag
builds the APK on GitHub Actions and publishes it as a release. Add the same values as repository
secrets (keystore as `SCOTTY_KEYSTORE_BASE64`) to get stable signatures across releases.

## Tests

```sh
cd core
cargo test                                    # unit + protocol tests
SCOTTY_MEDIA=<dir with in.h264 + in.aac> SCOTTY_OUT=<dir> cargo test   # real media, writes .ts for ffprobe
cargo run --example raop_tone -- HOST PORT 5 [auto|1|2]   # tone to any AirPlay receiver (PIN via stdin)
```

* SRP is checked against the RFC 5054 test vector. TLV8, the encrypted channel, ALAC packing and
  AirPlay 2 packet encryption have unit tests.
* `tests/miracast_sink.rs` plays a TV: M1–M7, IDR request, TEARDOWN, and real-time RTP/TS checks.
* `tests/airplay_video.rs` plays an Apple TV: `/play`, HLS playlist and segments, `/stop`.
* AirPlay 1 audio was verified against **shairport-sync** (exact tone out). AirPlay 2 pairing,
  the encrypted control channel and the event channel were verified against shairport-sync's
  AirPlay 2 build.

### Waydroid

Waydroid runs the x86_64 build. It has no Wi-Fi Direct and its mDNS doesn't see the host, so use
**Add by IP** (`192.168.240.1`) with a shairport-sync on the host to exercise the full chain:
capture, phone mute, volume keys, tile and picker.

## Limits

* **Miracast is implemented entirely in the app.** Regular apps can't put the Wi-Fi Display
  information element into Wi-Fi Direct, so a few strict sinks may refuse. Audio is AAC, so a
  sink that only takes LPCM mirrors without sound.
* **AirPlay 2 timing**: Android apps can't bind the privileged PTP ports, so Scotty uses NTP timing
  for AirPlay 2 streams (the approach OwnTones uses with HomePods). shairport-sync's AirPlay 2
  mode only supports PTP and can't be used as an end-to-end AirPlay 2 audio test.
* **AirPlay screen** uses HLS because real-time mirroring requires Apple's FairPlay.
* Apps that opt out of audio capture stay silent. Spotify works.
