//! Sends a short tone plus now-playing metadata to an AirPlay receiver (manual check).
//! `cargo run --example meta_check -- HOST PORT [1|2]`
use scotty_core::airplay_audio::{AirPlayAudio, Protocol};
use scotty_core::metadata::Metadata;
use scotty_core::session::Session;
use std::time::{Duration, Instant};

fn main() {
    env_logger::builder().filter_level(log::LevelFilter::Info).init();
    let a: Vec<String> = std::env::args().collect();
    let host = a.get(1).map(String::as_str).unwrap_or("127.0.0.1");
    let port: u16 = a.get(2).and_then(|p| p.parse().ok()).unwrap_or(5000);
    let proto = if a.get(3).map(String::as_str) == Some("2") { Protocol::AirPlay2 } else { Protocol::AirPlay1 };
    let s = AirPlayAudio::start(host, port, port, 0.8, proto, None).unwrap();
    // 1x1 PNG
    let png = vec![0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,0x0D,0x49,0x48,0x44,0x52,0,0,0,1,0,0,0,1,8,6,0,0,0,0x1F,0x15,0xC4,0x89,0,0,0,0x0A,0x49,0x44,0x41,0x54,0x78,0x9C,0x63,0,1,0,0,5,0,1,0x0D,0x0A,0x2D,0xB4,0,0,0,0,0x49,0x45,0x4E,0x44,0xAE,0x42,0x60,0x82];
    s.set_metadata(&Metadata { title: "Beam Me Up".into(), artist: "Scotty".into(), album: "Enterprise".into(), artwork: Some(png), artwork_mime: "image/png".into(), duration_ms: 180_000, position_ms: 5_000 });
    let start = Instant::now();
    let mut n = 0u64;
    while start.elapsed() < Duration::from_secs(4) {
        let mut pcm = Vec::with_capacity(352 * 4);
        for _ in 0..352 {
            let v = ((n as f64 * 2.0 * std::f64::consts::PI * 440.0 / 44_100.0).sin() * 8_000.0) as i16;
            pcm.extend_from_slice(&v.to_le_bytes());
            pcm.extend_from_slice(&v.to_le_bytes());
            n += 1;
        }
        s.push_audio(&pcm, 0);
        if let Some(w) = Duration::from_secs_f64(n as f64 / 44_100.0).checked_sub(start.elapsed()) { std::thread::sleep(w); }
    }
    s.stop();
    std::thread::sleep(Duration::from_millis(500));
}
