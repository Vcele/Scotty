//! Plays the role of a Miracast TV: runs the full WFD RTSP exchange against the source,
//! receives the RTP/MPEG-TS stream and checks it.

mod common;

use scotty_core::events::Event;
use scotty_core::rtsp::Message;
use scotty_core::session::Session;
use scotty_core::wfd::MiracastSession;
use std::io::{BufReader, Write};
use std::net::{TcpStream, UdpSocket};
use std::time::Duration;

fn reply(w: &mut TcpStream, req: &Message, extra: &str, body: &str) {
    let mut s = format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\n{extra}", req.cseq());
    if !body.is_empty() {
        s += &format!("Content-Type: text/parameters\r\nContent-Length: {}\r\n", body.len());
    }
    s += "\r\n";
    s += body;
    w.write_all(s.as_bytes()).unwrap();
}

fn request(w: &mut TcpStream, r: &mut BufReader<TcpStream>, cseq: u32, line: &str, headers: &str) -> Message {
    write!(w, "{line} RTSP/1.0\r\nCSeq: {cseq}\r\n{headers}\r\n").unwrap();
    let m = Message::read(r).unwrap().unwrap();
    assert!(m.is_response(), "expected response, got {}", m.start);
    assert_eq!(m.status(), 200, "{line}");
    m
}

#[test]
fn full_wfd_session_streams_ts_over_rtp() {
    let _ = env_logger::builder().is_test(true).try_init();
    let port = 17_236;
    let src = MiracastSession::start(port).unwrap();
    let rtp = UdpSocket::bind("127.0.0.1:0").unwrap();
    rtp.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let rtp_port = rtp.local_addr().unwrap().port();

    let mut w = TcpStream::connect(("127.0.0.1", port)).unwrap();
    let mut r = BufReader::new(w.try_clone().unwrap());

    // M1
    let m1 = Message::read(&mut r).unwrap().unwrap();
    assert_eq!(m1.method(), "OPTIONS");
    assert_eq!(m1.header("Require"), Some("org.wfa.wfd1.0"));
    reply(&mut w, &m1, "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n", "");
    // M2
    let m2 = request(&mut w, &mut r, 1, "OPTIONS *", "Require: org.wfa.wfd1.0\r\n");
    assert!(m2.header("Public").unwrap().contains("SETUP"));
    // M3
    let m3 = Message::read(&mut r).unwrap().unwrap();
    assert_eq!(m3.method(), "GET_PARAMETER");
    reply(&mut w, &m3, "", &format!(
        "wfd_video_formats: 00 00 02 04 000000FF 00000000 00000000 00 0000 0000 00 none none\r\n\
         wfd_audio_codecs: LPCM 00000003 00, AAC 00000001 00\r\n\
         wfd_client_rtp_ports: RTP/AVP/UDP;unicast {rtp_port} 0 mode=play\r\n"));
    // M4
    let m4 = Message::read(&mut r).unwrap().unwrap();
    assert_eq!(m4.method(), "SET_PARAMETER");
    let p = m4.parameters();
    assert!(p["wfd_video_formats"].starts_with("00 00 01 04 00000080"), "{p:?}"); // 1080p30
    assert_eq!(p["wfd_audio_codecs"], "AAC 00000001 00");
    assert!(p["wfd_presentation_URL"].starts_with("rtsp://127.0.0.1/"));
    reply(&mut w, &m4, "", "");
    // M5
    let m5 = Message::read(&mut r).unwrap().unwrap();
    assert_eq!(m5.parameters()["wfd_trigger_method"], "SETUP");
    reply(&mut w, &m5, "", "");
    // M6
    let m6 = request(&mut w, &mut r, 2, "SETUP rtsp://127.0.0.1/wfd1.0/streamid=0",
        &format!("Transport: RTP/AVP/UDP;unicast;client_port={rtp_port}\r\n"));
    let session = m6.header("Session").unwrap().split(';').next().unwrap().to_string();
    // M7
    request(&mut w, &mut r, 3, "PLAY rtsp://127.0.0.1/wfd1.0/streamid=0", &format!("Session: {session}\r\n"));

    // Source must ask the app to start encoding at the negotiated mode.
    let mut mode = None;
    while let Some(e) = src.events().poll(Duration::from_secs(2)) {
        if let Event::Mode { .. } = e {
            mode = Some(e);
            break;
        }
    }
    assert_eq!(mode, Some(Event::Mode { width: 1920, height: 1080, fps: 30, bitrate: 10_000_000, level: 40, audio: true }));

    // IDR request (M13) → KeyFrame event
    write!(w, "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\nSession: {session}\r\nContent-Type: text/parameters\r\nContent-Length: 17\r\n\r\nwfd_idr_request\r\n").unwrap();
    assert_eq!(Message::read(&mut r).unwrap().unwrap().status(), 200);
    assert!(std::iter::from_fn(|| src.events().poll(Duration::from_millis(500))).any(|e| e == Event::KeyFrame));

    // Push media, collect RTP.
    let media = common::load();
    let collector = std::thread::spawn(move || {
        let mut ts = Vec::new();
        let mut buf = [0u8; 2048];
        let mut last_seq: Option<u16> = None;
        while let Ok(n) = rtp.recv(&mut buf) {
            assert_eq!(buf[0], 0x80);
            assert_eq!(buf[1] & 0x7F, 33);
            let seq = u16::from_be_bytes([buf[2], buf[3]]);
            if let Some(l) = last_seq {
                assert_eq!(seq, l.wrapping_add(1));
            }
            last_seq = Some(seq);
            assert_eq!((n - 12) % 188, 0);
            ts.extend_from_slice(&buf[12..n]);
        }
        ts
    });
    common::feed(&media, |v, t, k| { src.push_video(v, t, k); std::thread::sleep(Duration::from_micros(300)) }, |a, t| src.push_audio(a, t));
    let ts = collector.join().unwrap();
    assert!(!ts.is_empty());
    assert!(ts.chunks(188).all(|p| p[0] == 0x47));
    if let Some(dir) = common::out_dir() {
        std::fs::write(dir.join("miracast.ts"), &ts).unwrap();
    }

    // Sink-initiated TEARDOWN ends the session cleanly.
    request(&mut w, &mut r, 5, "TEARDOWN rtsp://127.0.0.1/wfd1.0/streamid=0", &format!("Session: {session}\r\n"));
    assert!(std::iter::from_fn(|| src.events().poll(Duration::from_secs(2))).any(|e| e == Event::Ended(None)));
}
