//! Miracast (Wi-Fi Display R1) source.
//!
//! The platform layer forms the Wi-Fi Direct group; the sink then opens a TCP connection to our
//! RTSP port. We run the capability exchange (M1–M7), stream H.264 + AAC in MPEG-TS over RTP
//! and answer keep-alives / IDR requests until TEARDOWN.

use crate::events::{Event, Events};
use crate::rtsp::{Conn, Message};
use crate::session::Session;
use crate::ts::{TsMuxer, TsSink, PACKET};
use crate::netutil::{udp_with, TOS_VIDEO};
use crate::util::{mono_us, rand_u32};
use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, TcpListener, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, Weak};
use std::thread;
use std::time::{Duration, Instant};

pub const RTSP_PORT: u16 = 7236;
const SERVER_RTP_PORT: u16 = 19000;
const URI: &str = "rtsp://localhost/wfd1.0";

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Mode {
    pub cea_bit: u32,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// WFD level bitmap: 01=3.1 02=3.2 04=4 08=4.1 10=4.2
    pub wfd_level: u32,
    /// H.264 level ×10
    pub avc_level: u32,
    pub bitrate: u32,
}

/// Best first.
pub const MODES: [Mode; 4] = [
    Mode { cea_bit: 7, width: 1920, height: 1080, fps: 30, wfd_level: 0x04, avc_level: 40, bitrate: 8_000_000 },
    Mode { cea_bit: 6, width: 1280, height: 720, fps: 60, wfd_level: 0x02, avc_level: 32, bitrate: 6_000_000 },
    Mode { cea_bit: 5, width: 1280, height: 720, fps: 30, wfd_level: 0x01, avc_level: 31, bitrate: 4_000_000 },
    Mode { cea_bit: 0, width: 640, height: 480, fps: 60, wfd_level: 0x01, avc_level: 31, bitrate: 2_500_000 },
];

/// Picks the best mode both sides support plus whether AAC audio is possible.
/// Picks the best mode both sides support. `allow_1080`: 1080p needs a strong Wi-Fi Direct link;
/// 720p (what most Miracast senders use) is far more robust, so it is the default.
pub fn choose_formats(caps: &HashMap<String, String>, allow_1080: bool) -> (Mode, bool) {
    let mut mode = MODES[2];
    if let Some(v) = caps.get("wfd_video_formats") {
        let t: Vec<&str> = v.split([' ', ',']).filter(|s| !s.is_empty()).collect();
        if t.len() >= 5 && t[0] != "none" {
            let level = u32::from_str_radix(t[3], 16).unwrap_or(1);
            let cea = u64::from_str_radix(t[4], 16).unwrap_or(1);
            mode = MODES
                .iter()
                .copied()
                .filter(|m| allow_1080 || m.height < 1080)
                .find(|m| cea & (1 << m.cea_bit) != 0 && level >= m.wfd_level)
                .unwrap_or(MODES[3]);
        }
    }
    let audio = caps.get("wfd_audio_codecs").is_some_and(|a| a.contains("AAC"));
    (mode, audio)
}

/// TS packets → RTP (PT 33), 7 per datagram, handed to a pacing thread.
///
/// Key frames are hundreds of datagrams; blasting them out at once overflows Wi-Fi Direct queues.
/// The pacer spreads them out and keeps the kernel send buffer small (a big one hides congestion as
/// seconds of lag). Audio has its own lane that always goes first, so on a weak link the picture
/// may suffer but the sound keeps arriving on time. Congestion is measured as *queueing delay* of
/// video, which works the same at any bitrate.
pub struct RtpTsSender {
    buf: [u8; 12 + 7 * PACKET],
    count: usize,
    seq: u16,
    ssrc: u32,
    pacer: Arc<Pacer>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum PacerEvent {
    /// Video is waiting too long – lower the bitrate.
    Congested,
    /// Video waited so long it was dropped – lower the bitrate and send a key frame.
    Overflow,
}

#[derive(Default)]
struct Lanes {
    audio: std::collections::VecDeque<Vec<u8>>,
    video: std::collections::VecDeque<(Vec<u8>, Instant)>,
}

struct Pacer {
    lanes: Mutex<Lanes>,
    cv: std::sync::Condvar,
    stopped: AtomicBool,
    /// bytes per second the pacer sends at most
    rate: usize,
    congest_after: Duration,
    drop_after: Duration,
    last_signal: Mutex<Option<Instant>>,
    on_event: Box<dyn Fn(PacerEvent) + Send + Sync>,
}

impl RtpTsSender {
    pub fn new(dest: SocketAddr, max_bitrate: u32, on_event: Box<dyn Fn(PacerEvent) + Send + Sync>) -> io::Result<Self> {
        let sock = udp_with(TOS_VIDEO, 256 * 1024, None)?;
        let pacer = Arc::new(Pacer::new((max_bitrate as usize / 8 * 3).max(1_500_000), on_event));
        let p = pacer.clone();
        thread::Builder::new().name("rtp-pacer".into()).spawn(move || p.run(sock, dest))?;
        Ok(Self { buf: [0; 12 + 7 * PACKET], count: 0, seq: rand_u32() as u16, ssrc: rand_u32(), pacer })
    }

    fn flush(&mut self) {
        if self.count == 0 {
            return;
        }
        let ts = (mono_us() * 9 / 100) as u32;
        self.buf[0] = 0x80;
        self.buf[1] = 33;
        self.buf[2..4].copy_from_slice(&self.seq.to_be_bytes());
        self.buf[4..8].copy_from_slice(&ts.to_be_bytes());
        self.buf[8..12].copy_from_slice(&self.ssrc.to_be_bytes());
        self.seq = self.seq.wrapping_add(1);
        // Access units are flushed separately, so a datagram is all audio or all video (or PSI).
        let pid = ((self.buf[13] as u16 & 0x1F) << 8) | self.buf[14] as u16;
        let is_video = pid == crate::ts::VIDEO_PID;
        self.pacer.push(self.buf[..12 + self.count * PACKET].to_vec(), is_video);
        self.count = 0;
    }
}

impl Drop for RtpTsSender {
    fn drop(&mut self) {
        self.pacer.stopped.store(true, Ordering::SeqCst);
        self.pacer.cv.notify_all();
    }
}

impl Pacer {
    fn new(rate: usize, on_event: Box<dyn Fn(PacerEvent) + Send + Sync>) -> Self {
        Self {
            lanes: Mutex::default(),
            cv: Default::default(),
            stopped: AtomicBool::new(false),
            rate,
            congest_after: Duration::from_millis(150),
            drop_after: Duration::from_millis(600),
            last_signal: Mutex::new(None),
            on_event,
        }
    }

    fn signal(&self, e: PacerEvent) {
        let mut last = self.last_signal.lock().unwrap();
        let now = Instant::now();
        // Congestion at most once a second; overflow always.
        if e == PacerEvent::Congested && last.is_some_and(|t| now.duration_since(t) < Duration::from_secs(1)) {
            return;
        }
        *last = Some(now);
        drop(last);
        (self.on_event)(e);
    }

    fn push(&self, d: Vec<u8>, is_video: bool) {
        let now = Instant::now();
        let mut event = None;
        {
            let mut l = self.lanes.lock().unwrap();
            if !is_video {
                l.audio.push_back(d);
            } else {
                let waited = l.video.front().map(|(_, t)| now.duration_since(*t)).unwrap_or_default();
                if waited > self.drop_after {
                    log::warn!("video {} ms behind: dropped {} video datagrams, audio unaffected", waited.as_millis(), l.video.len());
                    l.video.clear();
                    event = Some(PacerEvent::Overflow);
                } else if waited > self.congest_after {
                    event = Some(PacerEvent::Congested);
                }
                l.video.push_back((d, now));
            }
        }
        self.cv.notify_one();
        if let Some(e) = event {
            self.signal(e);
        }
    }

    fn next(&self) -> Option<Vec<u8>> {
        let mut l = self.lanes.lock().unwrap();
        while l.audio.is_empty() && l.video.is_empty() && !self.stopped.load(Ordering::SeqCst) {
            l = self.cv.wait_timeout(l, Duration::from_millis(200)).unwrap().0;
        }
        l.audio.pop_front().or_else(|| l.video.pop_front().map(|(d, _)| d))
    }

    fn run(&self, sock: UdpSocket, dest: SocketAddr) {
        let burst = 24.0 * 1024.0;
        let mut tokens = burst;
        let mut last = Instant::now();
        while !self.stopped.load(Ordering::SeqCst) {
            let Some(d) = self.next() else { continue };
            let now = Instant::now();
            tokens = (tokens + now.duration_since(last).as_secs_f64() * self.rate as f64).min(burst);
            last = now;
            if tokens < d.len() as f64 {
                let wait = (d.len() as f64 - tokens) / self.rate as f64;
                thread::sleep(Duration::from_secs_f64(wait));
                tokens = d.len() as f64;
                last = Instant::now();
            }
            tokens -= d.len() as f64;
            if let Err(e) = sock.send_to(&d, dest) {
                log::debug!("rtp send: {e}");
            }
        }
    }
}

impl TsSink for RtpTsSender {
    fn packet(&mut self, p: &[u8; PACKET]) {
        let o = 12 + self.count * PACKET;
        self.buf[o..o + PACKET].copy_from_slice(p);
        self.count += 1;
        if self.count == 7 {
            self.flush();
        }
    }
    fn access_unit_end(&mut self) {
        self.flush();
    }
}

pub struct MiracastSession {
    allow_1080: bool,
    events: Arc<Events>,
    stopped: AtomicBool,
    session_id: String,
    conn: Mutex<Option<Arc<Conn>>>,
    mux: Mutex<Option<TsMuxer<RtpTsSender>>>,
    negotiated: Mutex<Option<(Mode, bool, String)>>, // mode, audio, rtp ports line
    sink_rtp_port: AtomicU16,
    played: Mutex<Option<Sender<()>>>,
    options_seen: Mutex<Option<Sender<()>>>,
}

impl MiracastSession {
    /// Starts listening on `port` for the sink's RTSP connection.
    pub fn start(port: u16, allow_1080: bool) -> io::Result<Arc<Self>> {
        let listener = TcpListener::bind(("0.0.0.0", port))?;
        listener.set_nonblocking(true)?;
        let s = Arc::new(Self {
            allow_1080,
            events: Arc::new(Events::default()),
            stopped: AtomicBool::new(false),
            session_id: format!("{}", 10_000_000 + rand_u32() % 89_999_999),
            conn: Mutex::new(None),
            mux: Mutex::new(None),
            negotiated: Mutex::new(None),
            sink_rtp_port: AtomicU16::new(1028),
            played: Mutex::new(None),
            options_seen: Mutex::new(None),
        });
        let me = s.clone();
        thread::Builder::new().name("wfd".into()).spawn(move || {
            if let Err(e) = me.run(listener) {
                if !me.is_stopped() {
                    log::warn!("miracast failed: {e}");
                    me.finish(Some(e.to_string()));
                }
            }
        })?;
        Ok(s)
    }

    fn is_stopped(&self) -> bool {
        self.stopped.load(Ordering::SeqCst)
    }

    fn run(self: &Arc<Self>, listener: TcpListener) -> io::Result<()> {
        let deadline = Instant::now() + Duration::from_secs(180);
        let stream = loop {
            if self.is_stopped() {
                return Ok(());
            }
            match listener.accept() {
                Ok((s, addr)) => {
                    log::info!("sink connected from {addr}");
                    break s;
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                    if Instant::now() > deadline {
                        return Err(io::Error::other("The display never opened a Miracast session"));
                    }
                    thread::sleep(Duration::from_millis(100));
                }
                Err(e) => return Err(e),
            }
        };
        drop(listener);
        stream.set_nonblocking(false)?;
        self.events.status("Display connected – negotiating…");

        let weak: Weak<Self> = Arc::downgrade(self);
        let w2 = weak.clone();
        let conn = Conn::start(
            stream,
            None,
            Some(Box::new(move |req, c| {
                if let Some(s) = weak.upgrade() {
                    s.on_request(req, c)
                }
            })),
            Some(Box::new(move || {
                if let Some(s) = w2.upgrade() {
                    if !s.is_stopped() {
                        s.finish(None);
                    }
                }
            })),
        )?;
        *self.conn.lock().unwrap() = Some(conn.clone());
        let t = Duration::from_secs(10);
        let (m2_tx, m2_rx) = channel();
        *self.options_seen.lock().unwrap() = Some(m2_tx);

        // M1
        let m1 = conn.request("OPTIONS", "*", &[("Require", "org.wfa.wfd1.0".into())], None, t)?;
        expect_ok(&m1, "Display rejected the Miracast handshake")?;
        // M2: the sink asks for our options; like Android's source we send M3 only after that.
        let _ = m2_rx.recv_timeout(Duration::from_secs(5));

        // M3
        let body = b"wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\n";
        let m3 = conn.request("GET_PARAMETER", URI, &[], Some(("text/parameters", body)), t)?;
        expect_ok(&m3, "Display didn't report its capabilities")?;
        let caps = m3.parameters();
        log::info!("sink caps: {caps:?}");
        let (mode, audio) = choose_formats(&caps, self.allow_1080);
        let rtp_ports = caps
            .get("wfd_client_rtp_ports")
            .cloned()
            .unwrap_or_else(|| "RTP/AVP/UDP;unicast 1028 0 mode=play".into());
        if let Some(p) = rtp_ports.split(' ').nth(1).and_then(|p| p.parse().ok()) {
            self.sink_rtp_port.store(p, Ordering::SeqCst);
        }
        *self.negotiated.lock().unwrap() = Some((mode, audio, rtp_ports.clone()));

        // M4 – propose the best mode; if the sink refuses, step down until one sticks.
        let start = MODES.iter().position(|m| *m == mode).unwrap_or(2);
        let mut accepted = None;
        for candidate in MODES[start..].iter().copied() {
            let mut params = format!(
                "wfd_video_formats: 00 00 01 {:02x} {:08x} 00000000 00000000 00 0000 0000 00 none none\r\n",
                candidate.wfd_level,
                1u32 << candidate.cea_bit
            );
            if audio {
                params += "wfd_audio_codecs: AAC 00000001 00\r\n";
            }
            params += &format!("wfd_presentation_URL: rtsp://{}/wfd1.0/streamid=0 none\r\n", conn.local_ip());
            params += &format!("wfd_client_rtp_ports: {rtp_ports}\r\n");
            let m4 = conn.request("SET_PARAMETER", URI, &[], Some(("text/parameters", params.as_bytes())), t)?;
            if m4.status() == 200 {
                accepted = Some(candidate);
                break;
            }
            log::warn!("sink refused {}x{}@{} ({}), trying lower", candidate.width, candidate.height, candidate.fps, m4.status());
        }
        let mode = accepted.ok_or_else(|| io::Error::other("Display refused every stream format"))?;
        *self.negotiated.lock().unwrap() = Some((mode, audio, rtp_ports.clone()));

        // M5 → sink sends SETUP (M6) and PLAY (M7)
        self.events.status(format!("Negotiating {}×{} @ {} fps…", mode.width, mode.height, mode.fps));
        let (tx, rx) = channel();
        *self.played.lock().unwrap() = Some(tx);
        conn.request("SET_PARAMETER", URI, &[], Some(("text/parameters", b"wfd_trigger_method: SETUP\r\n")), t)?;
        rx.recv_timeout(Duration::from_secs(20))
            .map_err(|_| io::Error::other("Display never started playback"))?;

        // M16 keep-alive
        let mut ticks = 0u32;
        while !self.is_stopped() && !conn.is_closed() {
            thread::sleep(Duration::from_secs(1));
            ticks += 1;
            if ticks.is_multiple_of(25) {
                let _ = conn.request("GET_PARAMETER", URI, &[("Session", self.session_id.clone())], None, t);
            }
        }
        Ok(())
    }

    fn on_request(&self, req: &Message, c: &Conn) {
        let sid = self.session_id.clone();
        match req.method() {
            "OPTIONS" => {
                c.respond(req, 200, &[(
                    "Public",
                    "org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER".into(),
                )], None);
                if let Some(tx) = self.options_seen.lock().unwrap().take() {
                    let _ = tx.send(());
                }
            }
            "SETUP" => {
                if let Some(t) = req.header("Transport") {
                    if let Some(p) = t.split(';').find_map(|kv| kv.strip_prefix("client_port=")) {
                        if let Some(p) = p.split('-').next().and_then(|p| p.parse().ok()) {
                            self.sink_rtp_port.store(p, Ordering::SeqCst);
                        }
                    }
                }
                let port = self.sink_rtp_port.load(Ordering::SeqCst);
                c.respond(req, 200, &[
                    ("Session", format!("{sid};timeout=30")),
                    ("Transport", format!(
                        "RTP/AVP/UDP;unicast;client_port={port};server_port={SERVER_RTP_PORT}-{}",
                        SERVER_RTP_PORT + 1
                    )),
                ], None);
            }
            "PLAY" => {
                c.respond(req, 200, &[("Session", sid), ("Range", "npt=now-".into())], None);
                self.start_streaming(c);
            }
            "PAUSE" => c.respond(req, 200, &[("Session", sid)], None),
            "TEARDOWN" => {
                c.respond(req, 200, &[("Session", sid)], None);
                self.finish(None);
            }
            "SET_PARAMETER" => {
                if req.body_text().contains("wfd_idr_request") {
                    self.events.emit(Event::KeyFrame);
                }
                c.respond(req, 200, &[], None);
            }
            "GET_PARAMETER" => c.respond(req, 200, &[], None),
            _ => c.respond(req, 501, &[], None),
        }
    }

    fn start_streaming(&self, c: &Conn) {
        let mut mux = self.mux.lock().unwrap();
        if mux.is_some() {
            self.events.emit(Event::KeyFrame);
            return;
        }
        let Some((mode, audio, _)) = *self.negotiated.lock().unwrap() else { return };
        let dest = SocketAddr::new(c.peer_ip(), self.sink_rtp_port.load(Ordering::SeqCst));
        let ev = self.events.clone();
        // Key frames are the biggest bursts; requesting one per overflow fed the congestion. At most
        // one every 2 s.
        let last_idr = Mutex::new(None::<Instant>);
        let on_event = Box::new(move |e: PacerEvent| {
            ev.emit(Event::Congestion);
            if e == PacerEvent::Overflow {
                let mut last = last_idr.lock().unwrap();
                if last.is_none_or(|t| t.elapsed() > Duration::from_secs(2)) {
                    *last = Some(Instant::now());
                    ev.emit(Event::KeyFrame);
                }
            }
        });
        match RtpTsSender::new(dest, mode.bitrate, on_event) {
            Ok(sender) => {
                *mux = Some(TsMuxer::new(true, audio, sender));
                self.events.emit(Event::Mode {
                    width: mode.width,
                    height: mode.height,
                    fps: mode.fps,
                    bitrate: mode.bitrate,
                    level: mode.avc_level,
                    audio,
                });
                self.events.live(format!(
                    "Mirroring {}×{} @ {} fps{}",
                    mode.width,
                    mode.height,
                    mode.fps,
                    if audio { " with sound" } else { "" }
                ));
                if let Some(tx) = self.played.lock().unwrap().take() {
                    let _ = tx.send(());
                }
            }
            Err(e) => {
                drop(mux);
                self.finish(Some(format!("Couldn't open RTP socket: {e}")))
            }
        }
    }

    fn finish(&self, err: Option<String>) {
        self.shutdown();
        self.events.ended(err);
    }

    fn shutdown(&self) {
        if self.stopped.swap(true, Ordering::SeqCst) {
            return;
        }
        if let Some(conn) = self.conn.lock().unwrap().take() {
            let sid = self.session_id.clone();
            thread::spawn(move || {
                if !conn.is_closed() {
                    let _ = conn.request("TEARDOWN", URI, &[("Session", sid)], None, Duration::from_millis(1500));
                }
                conn.close();
            });
        }
        self.mux.lock().unwrap().take();
    }
}

fn expect_ok(m: &Message, what: &str) -> io::Result<()> {
    if m.status() == 200 {
        Ok(())
    } else {
        Err(io::Error::other(format!("{what} ({})", m.status())))
    }
}

impl Session for MiracastSession {
    fn push_video(&self, data: &[u8], pts_us: i64, key: bool) {
        if let Some(m) = self.mux.lock().unwrap().as_mut() {
            m.write_video(data, pts_us, key);
        }
    }
    fn push_audio(&self, data: &[u8], pts_us: i64) {
        if let Some(m) = self.mux.lock().unwrap().as_mut() {
            m.write_audio(data, pts_us);
        }
    }
    fn stop(&self) {
        self.shutdown();
        self.events.ended(None);
    }
    fn events(&self) -> &Events {
        &self.events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audio_goes_first_and_stale_video_is_dropped() {
        let events = Arc::new(Mutex::new(Vec::new()));
        let ev = events.clone();
        // No sending thread: the queue only grows, like a stalled Wi-Fi link.
        let mut p = Pacer::new(3_000_000, Box::new(move |e| ev.lock().unwrap().push(e)));
        p.congest_after = Duration::from_millis(5);
        p.drop_after = Duration::from_millis(20);
        for _ in 0..50 {
            p.push(vec![1u8; 100], true);
        }
        p.push(vec![2u8; 100], false);
        // audio jumps the queue
        assert_eq!(p.next().unwrap()[0], 2);
        thread::sleep(Duration::from_millis(10));
        p.push(vec![1u8; 100], true); // video waited > 5 ms → congested
        p.push(vec![2u8; 100], false);
        thread::sleep(Duration::from_millis(25));
        p.push(vec![1u8; 100], true); // video waited > 20 ms → dropped
        let l = p.lanes.lock().unwrap();
        assert_eq!(l.audio.len(), 1, "audio must never be dropped");
        assert_eq!(l.video.len(), 1, "stale video must be dropped");
        drop(l);
        let ev = events.lock().unwrap();
        assert!(ev.contains(&PacerEvent::Congested));
        assert!(ev.contains(&PacerEvent::Overflow));
    }

    #[test]
    fn picks_best_supported_mode() {
        let mut caps = HashMap::new();
        caps.insert("wfd_video_formats".into(), "40 00 02 04 0001FFFF 3FFFFFFF 00000FFF 00 0000 0000 11 none none".into());
        caps.insert("wfd_audio_codecs".into(), "LPCM 00000003 00, AAC 0000000F 00".into());
        let (m, a) = choose_formats(&caps, true);
        assert_eq!((m.width, m.height, m.fps), (1920, 1080, 30));
        assert!(a);
        // default: 720p even when the TV could do 1080p
        assert_eq!(choose_formats(&caps, false).0.height, 720);

        caps.insert("wfd_video_formats".into(), "00 00 01 02 000000E1 00000000 00000000 00 0000 0000 00 none none".into());
        caps.insert("wfd_audio_codecs".into(), "LPCM 00000003 00".into());
        let (m, a) = choose_formats(&caps, true);
        assert_eq!((m.width, m.fps), (1280, 60)); // level 3.2 can't do 1080p
        assert!(!a);
    }
}
