# Beamory

Cast from your Pixel to the screens and speakers Google left behind. Material You (dynamic color) app, Kotlin + Jetpack Compose.

| Mode | Protocol | What you get |
|---|---|---|
| **Miracast** | Wi-Fi Display source (Wi-Fi Direct + RTSP + H.264/AAC in MPEG-TS over RTP) | Live screen mirroring with sound, up to 1080p30 / 720p60. No router needed. |
| **AirPlay – Audio only** | RAOP (AirPlay audio), lossless ALAC, NTP timing, retransmits | Everything the phone plays (Spotify, YouTube Music, podcasts…), with volume control. |
| **AirPlay – Screen + audio** | Live HLS served from the phone + AirPlay video `POST /play` | Screen and sound on Apple TV / AirPlay TVs, ~3–5 s behind. |

## Build & install

```sh
nix develop            # JDK 17, Gradle, Android SDK 36, adb
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the debug key so you can sideload it directly.

## How it works / limits

* **Miracast is implemented entirely in the app.** Pixels dropped the system Miracast stack, so Beamory
  does the Wi-Fi Direct connection, WFD RTSP negotiation (M1–M7, keep-alive, IDR requests) and RTP
  streaming itself. Normal apps can't set the Wi-Fi Display info element in Wi-Fi Direct (that's a
  privileged API), so Beamory doesn't advertise itself as a "Miracast source" there. Most TVs and dongles
  connect anyway, but some strict sinks may refuse. Audio is sent as AAC, so a sink that only takes LPCM
  mirrors without sound.
* **AirPlay audio** uses the RAOP protocol, which AirPlay 1 devices and most AirPlay 2 receivers accept
  (TVs, Sonos, Denon/Marantz, Bose, shairport-sync, AirPort Express…). Receivers that require AirPlay 2
  HomeKit pairing (HomePods, and Apple TVs with strict access settings) aren't supported yet. Set the
  receiver's AirPlay access to "Everyone on the same network" without a password.
* **AirPlay screen mirroring** in Apple's real-time format requires FairPlay, so Beamory streams the screen
  as live HLS instead. It works on receivers that support AirPlay video and adds a few seconds of latency.
* Audio is captured with Android's playback capture. Apps that opt out of capture (some DRM/voice apps)
  stay silent. Spotify works.

## Code map

```
app/src/main/java/app/beamory/
  MainActivity.kt, ui/          Compose UI (Material 3, dynamic color)
  cast/                         CastService (foreground + MediaProjection), shared state
  media/TsMuxer.kt              MPEG-TS muxer (shared by Miracast + HLS)
  media/Encoders.kt             screen → H.264, playback capture, AAC
  miracast/                     Wi-Fi Direct discovery, WFD source (RTSP/RTP)
  airplay/                      mDNS discovery, RAOP audio, HLS + /play video
  net/Rtsp.kt                   bidirectional RTSP client/server
```

The TS muxer has a JVM test that muxes ffmpeg-generated H.264/AAC (`-Dbeamory.tsdir=<dir with in.h264, in.aac>`).
