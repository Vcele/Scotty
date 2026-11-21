//! Streams a 440 Hz tone to an AirPlay (RAOP) receiver: `cargo run --example raop_tone -- HOST PORT SECONDS`
use scotty_core::raop::RaopSession;
use scotty_core::session::Session;
use std::time::{Duration, Instant};

fn main() {
    env_logger::builder().filter_level(log::LevelFilter::Info).init();
    let a: Vec<String> = std::env::args().collect();
    let host = a.get(1).map(String::as_str).unwrap_or("127.0.0.1");
    let port: u16 = a.get(2).and_then(|p| p.parse().ok()).unwrap_or(5000);
    let secs: f64 = a.get(3).and_then(|p| p.parse().ok()).unwrap_or(5.0);
    let s = RaopSession::start(host, port, 0.8).unwrap();
    let start = Instant::now();
    let mut n: u64 = 0;
    let chunk = 352;
    while start.elapsed().as_secs_f64() < secs {
        if s.events().has_ended() { break; }
        while let Some(e) = s.events().poll(Duration::ZERO) { println!("event: {}", e.encode()); }
        let mut pcm = Vec::with_capacity(chunk * 4);
        for _ in 0..chunk {
            let v = ((n as f64 * 2.0 * std::f64::consts::PI * 440.0 / 44_100.0).sin() * 12_000.0) as i16;
            pcm.extend_from_slice(&v.to_le_bytes());
            pcm.extend_from_slice(&v.to_le_bytes());
            n += 1;
        }
        s.push_audio(&pcm, 0);
        // pace in real time
        let due = Duration::from_secs_f64(n as f64 / 44_100.0);
        if let Some(wait) = due.checked_sub(start.elapsed()) { std::thread::sleep(wait); }
    }
    s.stop();
    while let Some(e) = s.events().poll(Duration::from_millis(500)) { println!("event: {}", e.encode()); }
}
