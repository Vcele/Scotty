//! Screen + sound to AirPlay video receivers.
//!
//! Real-time AirPlay *mirroring* is gated behind FairPlay, so instead the phone serves a live HLS
//! stream (MPEG-TS segments cut at key frames) and asks the receiver to play it via the public
//! AirPlay video API (`POST /play`). Latency is a few seconds.

use crate::events::{Event, Events};
use crate::rtsp::{read_response, Message};
use crate::session::Session;
use crate::ts::{TsMuxer, TsSink, PACKET};
use crate::ap2;
use crate::hap::pairing::{self, Credentials};
use crate::netutil::tune_tcp;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::collections::VecDeque;
use std::io::{self, BufReader, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const WIDTH: u32 = 1280;
pub const HEIGHT: u32 = 720;
pub const FPS: u32 = 30;
pub const BITRATE: u32 = 4_000_000;

struct Segment {
    index: u32,
    data: Arc<Vec<u8>>,
    duration: f64,
}

#[derive(Default)]
struct Segments {
    list: VecDeque<Segment>,
    next: u32,
}

/// Live HLS segmenter + HTTP server.
pub struct Hls {
    segs: Mutex<Segments>,
    cv: Condvar,
    stopped: AtomicBool,
    port: u16,
}

pub struct HlsSink {
    hls: Arc<Hls>,
    current: Vec<u8>,
    seg_start: Option<i64>,
}

impl TsSink for HlsSink {
    fn packet(&mut self, p: &[u8; PACKET]) {
        self.current.extend_from_slice(p);
    }
    fn key_frame_start(&mut self, pts90: i64) {
        let Some(start) = self.seg_start else {
            self.seg_start = Some(pts90);
            self.current.clear();
            return;
        };
        let duration = (pts90 - start) as f64 / 90_000.0;
        if duration < 0.9 {
            return;
        }
        let data = Arc::new(std::mem::replace(&mut self.current, Vec::with_capacity(1 << 20)));
        let mut s = self.hls.segs.lock().unwrap();
        let index = s.next;
        s.next += 1;
        s.list.push_back(Segment { index, data, duration });
        while s.list.len() > 8 {
            s.list.pop_front();
        }
        self.hls.cv.notify_all();
        self.seg_start = Some(pts90);
    }
}

impl Hls {
    pub fn start() -> io::Result<(Arc<Hls>, HlsSink)> {
        let listener = TcpListener::bind(("0.0.0.0", 0))?;
        listener.set_nonblocking(true)?;
        let hls = Arc::new(Hls {
            segs: Mutex::default(),
            cv: Condvar::new(),
            stopped: AtomicBool::new(false),
            port: listener.local_addr()?.port(),
        });
        let h = hls.clone();
        thread::Builder::new().name("hls".into()).spawn(move || {
            while !h.stopped.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((s, _)) => {
                        let h = h.clone();
                        thread::spawn(move || {
                            if let Err(e) = h.serve(s) {
                                log::debug!("hls serve: {e}");
                            }
                        });
                    }
                    Err(_) => thread::sleep(Duration::from_millis(50)),
                }
            }
        })?;
        let sink = HlsSink { hls: hls.clone(), current: Vec::with_capacity(1 << 20), seg_start: None };
        Ok((hls, sink))
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn wait_for_segments(&self, n: usize, timeout: Duration) -> bool {
        let end = Instant::now() + timeout;
        let mut s = self.segs.lock().unwrap();
        while s.list.len() < n {
            let now = Instant::now();
            if now >= end || self.stopped.load(Ordering::SeqCst) {
                return false;
            }
            s = self.cv.wait_timeout(s, end - now).unwrap().0;
        }
        true
    }

    pub fn playlist(&self) -> String {
        let s = self.segs.lock().unwrap();
        let skip = s.list.len().saturating_sub(5);
        let list: Vec<&Segment> = s.list.iter().skip(skip).collect();
        let mut out = String::from("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n");
        out += &format!("#EXT-X-MEDIA-SEQUENCE:{}\n", list.first().map_or(0, |s| s.index));
        for seg in list {
            out += &format!("#EXTINF:{:.3},\nseg{}.ts\n", seg.duration, seg.index);
        }
        out
    }

    fn serve(&self, s: TcpStream) -> io::Result<()> {
        s.set_nonblocking(false)?;
        s.set_read_timeout(Some(Duration::from_secs(10)))?;
        let mut w = s.try_clone()?;
        let mut r = BufReader::new(s);
        while let Some(req) = Message::read(&mut r)? {
            let path = req.path().split('?').next().unwrap_or("/").to_string();
            let found: Option<(&str, Arc<Vec<u8>>)> = if path.ends_with(".m3u8") {
                Some(("application/vnd.apple.mpegurl", Arc::new(self.playlist().into_bytes())))
            } else if let Some(idx) = path.strip_prefix("/seg").and_then(|p| p.strip_suffix(".ts")) {
                let idx: u32 = idx.parse().unwrap_or(u32::MAX);
                let s = self.segs.lock().unwrap();
                s.list.iter().find(|s| s.index == idx).map(|s| ("video/mp2t", s.data.clone()))
            } else {
                None
            };
            match found {
                Some((ty, body)) => {
                    write!(w, "HTTP/1.1 200 OK\r\nContent-Type: {ty}\r\nContent-Length: {}\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n\r\n", body.len())?;
                    if req.method() != "HEAD" {
                        w.write_all(&body)?;
                    }
                }
                None => w.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")?,
            }
            w.flush()?;
        }
        Ok(())
    }

    pub fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        self.cv.notify_all();
    }
}

pub struct AirPlayScreenSession {
    events: Events,
    stopped: AtomicBool,
    mux: Mutex<Option<TsMuxer<HlsSink>>>,
    hls: Mutex<Option<Arc<Hls>>>,
    control: Mutex<Option<TcpStream>>,
    session_id: String,
    creds: Mutex<Option<Credentials>>,
    pin_tx: Mutex<Sender<String>>,
    pin_rx: Mutex<Option<Receiver<String>>>,
}

impl AirPlayScreenSession {
    pub fn start(host: &str, port: u16, creds: Option<String>) -> io::Result<Arc<Self>> {
        let (pin_tx, pin_rx) = channel();
        let s = Arc::new(Self {
            events: Events::default(),
            stopped: AtomicBool::new(false),
            mux: Mutex::new(None),
            hls: Mutex::new(None),
            control: Mutex::new(None),
            session_id: pairing::uuid(),
            creds: Mutex::new(creds.as_deref().and_then(Credentials::decode)),
            pin_tx: Mutex::new(pin_tx),
            pin_rx: Mutex::new(Some(pin_rx)),
        });
        let me = s.clone();
        let host = host.to_string();
        thread::Builder::new().name("airplay-video".into()).spawn(move || {
            if let Err(e) = me.run(&host, port) {
                if !me.is_stopped() {
                    log::warn!("airplay video failed: {e}");
                    me.finish(Some(e.to_string()));
                }
            }
        })?;
        Ok(s)
    }

    fn is_stopped(&self) -> bool {
        self.stopped.load(Ordering::SeqCst)
    }

    fn run(&self, host: &str, port: u16) -> io::Result<()> {
        self.events.status("Connecting…");
        let addr: SocketAddr = (host, port)
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| io::Error::other("Unknown receiver address"))?;
        let mut sock = crate::netutil::connect_retry(&addr)?;
        sock.set_read_timeout(Some(Duration::from_secs(10)))?;
        let local_ip = sock.local_addr()?.ip();
        *self.control.lock().unwrap() = Some(sock.try_clone()?);

        let (hls, sink) = Hls::start()?;
        *self.hls.lock().unwrap() = Some(hls.clone());
        *self.mux.lock().unwrap() = Some(TsMuxer::new(true, true, sink));
        self.events.emit(Event::Mode { width: WIDTH, height: HEIGHT, fps: FPS, bitrate: BITRATE, level: 31, audio: true });
        self.events.status("Buffering…");
        if !hls.wait_for_segments(3, Duration::from_secs(20)) {
            if self.is_stopped() {
                return Ok(());
            }
            return Err(io::Error::other("No video from the screen encoder"));
        }

        let url = format!("http://{local_ip}:{}/live.m3u8", hls.port());
        log::info!("asking receiver to play {url}");
        let body = format!("Content-Location: {url}\nStart-Position: 0.000000\n");
        let resp = self.http(&mut sock, host, port, "POST", "/play", Some(("text/parameters", body.as_bytes())))?;
        match resp.status() {
            200 => {
                self.events.live("Streaming screen + sound (a few seconds behind)");
                while !self.is_stopped() {
                    thread::sleep(Duration::from_secs(3));
                    if self.is_stopped() {
                        break;
                    }
                    if self.http(&mut sock, host, port, "GET", "/playback-info", None).is_err() {
                        if !self.is_stopped() {
                            self.finish(None);
                        }
                        break;
                    }
                }
                Ok(())
            }
            401 | 403 | 470 | 471 => {
                // Apple TV (tvOS 10.2+) wants a HomeKit-paired, encrypted session for /play.
                log::info!("plain /play refused ({}), pairing", resp.status());
                let _ = sock.shutdown(std::net::Shutdown::Both);
                self.run_paired(addr, &url)
            }
            s => Err(io::Error::other(format!("Receiver can't play the stream ({s})"))),
        }
    }

    fn run_paired(&self, addr: SocketAddr, url: &str) -> io::Result<()> {
        let tcp = crate::netutil::connect_retry(&addr)?;
        tune_tcp(&tcp);
        tcp.set_read_timeout(Some(Duration::from_secs(190)))?;
        *self.control.lock().unwrap() = Some(tcp.try_clone()?);
        self.events.status("Pairing…");
        let creds = self.creds.lock().unwrap().clone();
        let pin_rx = self.pin_rx.lock().unwrap().take().ok_or_else(|| io::Error::other("pairing already attempted"))?;
        let secret = ap2::pair(&tcp, "HTTP/1.1", creds.as_ref(), &self.events, &pin_rx, false)?;
        tcp.set_read_timeout(Some(Duration::from_secs(10)))?;
        let mut client = ap2::SecureClient::new(&tcp, &secret, "HTTP/1.1")?;
        client.headers = vec![("X-Apple-Session-ID".into(), self.session_id.clone())];
        let mac: [u8; 6] = crate::hap::random();
        let mac = mac.iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":");
        let body = ap2::bplist(ap2::dict(vec![
            ("Content-Location", plist::Value::String(url.into())),
            ("Start-Position-Seconds", plist::Value::Real(0.0)),
            ("uuid", plist::Value::String(self.session_id.clone())),
            ("streamType", plist::Value::Integer(1.into())),
            ("mediaType", plist::Value::String("file".into())),
            ("volume", plist::Value::Real(1.0)),
            ("rate", plist::Value::Real(1.0)),
            ("clientBundleID", plist::Value::String("app.scotty".into())),
            ("clientProcName", plist::Value::String("Scotty".into())),
            ("SenderMACAddress", plist::Value::String(mac)),
            ("model", plist::Value::String("iPhone14,3".into())),
            ("osBuildVersion", plist::Value::String("21F79".into())),
        ]));
        let r = client.request("POST", "/play", &[], Some(("application/x-apple-binary-plist", &body)))?;
        if r.status() != 200 {
            return Err(io::Error::other(format!("Receiver can't play the stream ({})", r.status())));
        }
        self.events.live("Streaming screen + sound (a few seconds behind)");
        while !self.is_stopped() {
            thread::sleep(Duration::from_secs(3));
            if self.is_stopped() {
                break;
            }
            if client.request("GET", "/playback-info", &[], None).is_err() {
                if !self.is_stopped() {
                    self.finish(None);
                }
                break;
            }
        }
        Ok(())
    }

    fn http(
        &self,
        sock: &mut TcpStream,
        host: &str,
        port: u16,
        method: &str,
        path: &str,
        body: Option<(&str, &[u8])>,
    ) -> io::Result<Message> {
        let mut head = format!(
            "{method} {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUser-Agent: MediaControl/1.0\r\nX-Apple-Session-ID: {}\r\n",
            self.session_id
        );
        if let Some((ty, _)) = body {
            head += &format!("Content-Type: {ty}\r\n");
        }
        head += &format!("Content-Length: {}\r\n\r\n", body.map_or(0, |b| b.1.len()));
        sock.write_all(head.as_bytes())?;
        if let Some((_, b)) = body {
            sock.write_all(b)?;
        }
        read_response(sock)?.ok_or_else(|| io::Error::other("Receiver closed the connection"))
    }

    fn finish(&self, err: Option<String>) {
        self.shutdown();
        self.events.ended(err);
    }

    fn shutdown(&self) {
        if self.stopped.swap(true, Ordering::SeqCst) {
            return;
        }
        self.mux.lock().unwrap().take();
        if let Some(h) = self.hls.lock().unwrap().take() {
            h.stop();
        }
        if let Some(mut s) = self.control.lock().unwrap().take() {
            let sid = self.session_id.clone();
            thread::spawn(move || {
                let _ = write!(s, "POST /stop HTTP/1.1\r\nUser-Agent: MediaControl/1.0\r\nX-Apple-Session-ID: {sid}\r\nContent-Length: 0\r\n\r\n");
                thread::sleep(Duration::from_millis(300));
                let _ = s.shutdown(Shutdown::Both);
            });
        }
    }
}

impl Session for AirPlayScreenSession {
    fn submit_pin(&self, pin: &str) {
        let _ = self.pin_tx.lock().unwrap().send(pin.to_string());
    }
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
