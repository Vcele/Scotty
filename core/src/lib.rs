//! Scotty protocol core.
//!
//! Android (Kotlin) owns everything that needs platform APIs – screen/audio capture, hardware
//! encoders, Wi-Fi Direct and mDNS – and pushes encoded media into a [`Session`] here. This
//! crate speaks the wire protocols:
//!
//! * [`wfd`]      – Miracast / Wi-Fi Display source (RTSP M1–M16, MPEG-TS over RTP)
//! * [`raop`]     – AirPlay audio (RAOP: RTSP + ALAC over RTP, NTP timing, retransmits)
//! * [`airplay_video`] – AirPlay video: live HLS server + `POST /play`
//!
//! Sessions report back through a small event queue that Kotlin polls ([`events`]).

pub mod airplay_audio;
pub mod airplay_video;
pub mod ap2;
pub mod hap;
pub mod metadata;
pub mod netutil;
pub mod rtp_audio;
pub mod events;
pub mod raop;
pub mod rtsp;
pub mod session;
pub mod ts;
pub mod util;
pub mod wfd;

mod jni_api;

pub use session::Session;
