//! Plays the role of an AirPlay video receiver: accepts POST /play, then pulls the HLS
//! playlist and segments from the phone like an Apple TV would.

mod common;

use scotty_core::airplay_video::AirPlayScreenSession;
use scotty_core::events::Event;
use scotty_core::rtsp::Message;
use scotty_core::session::Session;
use std::io::{BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

fn http_get(url: &str) -> (u16, Vec<u8>) {
    let rest = url.strip_prefix("http://").unwrap();
    let (hostport, path) = rest.split_at(rest.find('/').unwrap());
    let mut s = TcpStream::connect(hostport).unwrap();
    write!(s, "GET {path} HTTP/1.1\r\nHost: {hostport}\r\n\r\n").unwrap();
    let m = Message::read(&mut BufReader::new(s)).unwrap().unwrap();
    (m.status(), m.body)
}

#[test]
fn play_request_and_hls_stream() {
    let _ = env_logger::builder().is_test(true).try_init();
    let receiver = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = receiver.local_addr().unwrap().port();
    let src = AirPlayScreenSession::start("127.0.0.1", port, None).unwrap();

    assert!(matches!(src.events().poll(Duration::from_secs(1)), Some(Event::Status(_))));
    let (mut conn, _) = receiver.accept().unwrap();
    match src.events().poll(Duration::from_secs(2)) {
        Some(Event::Mode { width: 1280, height: 720, audio: true, .. }) => {}
        e => panic!("expected mode, got {e:?}"),
    }

    // Feed ~4 s of media (enough for 3 segments) in a background thread.
    let media = common::load();
    let s2 = src.clone();
    let feeder = std::thread::spawn(move || common::feed(&media, |v, t, k| s2.push_video(v, t, k), |a, t| s2.push_audio(a, t)));

    let mut r = BufReader::new(conn.try_clone().unwrap());
    let play = Message::read(&mut r).unwrap().unwrap();
    assert_eq!(play.start, "POST /play HTTP/1.1");
    assert!(play.header("X-Apple-Session-ID").is_some());
    let params = play.parameters();
    let url = params["Content-Location"].clone();
    assert!(url.ends_with("/live.m3u8"), "{url}");
    conn.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").unwrap();
    feeder.join().unwrap();

    let (status, pl) = http_get(&url);
    assert_eq!(status, 200);
    let pl = String::from_utf8(pl).unwrap();
    assert!(pl.starts_with("#EXTM3U"), "{pl}");
    let segs: Vec<&str> = pl.lines().filter(|l| l.ends_with(".ts")).collect();
    assert!(!segs.is_empty(), "{pl}");
    let base = url.rsplit_once('/').unwrap().0;
    let mut all = Vec::new();
    for s in &segs {
        let (st, body) = http_get(&format!("{base}/{s}"));
        assert_eq!(st, 200);
        assert_eq!(body.len() % 188, 0);
        assert_eq!(body[0], 0x47);
        all.extend(body);
    }
    if let Some(dir) = common::out_dir() {
        std::fs::write(dir.join("hls.ts"), &all).unwrap();
        std::fs::write(dir.join("live.m3u8"), &pl).unwrap();
    }

    // playback-info poll arrives on the same connection
    let info = Message::read(&mut r).unwrap().unwrap();
    assert_eq!(info.start, "GET /playback-info HTTP/1.1");
    conn.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").unwrap();

    src.stop();
    let mut rest = Vec::new();
    conn.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let _ = r.read_to_end(&mut rest);
    assert!(String::from_utf8_lossy(&rest).contains("POST /stop"));
}
