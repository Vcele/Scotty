//! AirPlay 2 sender: HomeKit pairing (transient, PIN, verify), encrypted RTSP control channel,
//! event channel, realtime ALAC stream with per-packet encryption, NTP timing, /feedback.
//!
//! Android can't bind the privileged PTP ports, so we use NTP timing, which HomePods and
//! Apple TVs accept for realtime (type 96) streams.

use crate::events::{Event, Events};
use crate::hap::channel::{secure, OpenReader, SealWriter};
use crate::hap::pairing::{self, control_keys, event_keys, Credentials, PairError};
use crate::hap::random;
use crate::netutil::tune_tcp;
use crate::raop::volume_db;
use crate::rtp_audio::{Destination, RtpAudio, FRAMES, RATE};
use crate::rtsp::{read_response, Message};
use crate::session::Session;
use crate::util::rand_u64;
use plist::{Dictionary, Value};
use std::io::{self, BufReader, Write};
use std::net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Receiver;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

pub const USER_AGENT: &str = "AirPlay/690.7.1";

/// Plain (pre-pairing) request/response on the control socket.
pub struct PlainClient<'a> {
    pub stream: &'a TcpStream,
    pub proto: &'static str,
    pub cseq: u32,
}

impl PlainClient<'_> {
    pub fn post(&mut self, path: &str, body: &[u8], hkp: u8) -> io::Result<(u16, Vec<u8>)> {
        self.cseq += 1;
        let head = format!(
            "POST {path} {}\r\nCSeq: {}\r\nUser-Agent: {USER_AGENT}\r\nX-Apple-HKP: {hkp}\r\n\
             Content-Type: application/octet-stream\r\nContent-Length: {}\r\n\r\n",
            self.proto,
            self.cseq,
            body.len()
        );
        let mut w = self.stream;
        w.write_all(head.as_bytes())?;
        w.write_all(body)?;
        let mut r = self.stream;
        let m = read_response(&mut r)?.ok_or_else(|| io::Error::other("Receiver closed the connection"))?;
        Ok((m.status(), m.body))
    }
}

/// Encrypted request/response client used after pairing.
pub struct SecureClient {
    w: SealWriter<TcpStream>,
    r: BufReader<OpenReader<TcpStream>>,
    cseq: u32,
    proto: &'static str,
    pub headers: Vec<(String, String)>,
}

impl SecureClient {
    pub fn new(stream: &TcpStream, secret: &[u8], proto: &'static str) -> io::Result<Self> {
        let (w, r) = secure(stream, control_keys(secret))?;
        Ok(Self { w, r: BufReader::new(r), cseq: 0, proto, headers: Vec::new() })
    }

    pub fn request(&mut self, method: &str, uri: &str, extra: &[(&str, String)], body: Option<(&str, &[u8])>) -> io::Result<Message> {
        self.cseq += 1;
        let mut head = format!("{method} {uri} {}\r\nCSeq: {}\r\nUser-Agent: {USER_AGENT}\r\n", self.proto, self.cseq);
        for (k, v) in &self.headers {
            head += &format!("{k}: {v}\r\n");
        }
        for (k, v) in extra {
            head += &format!("{k}: {v}\r\n");
        }
        let body = body.unwrap_or(("", &[]));
        if !body.0.is_empty() {
            head += &format!("Content-Type: {}\r\n", body.0);
        }
        head += &format!("Content-Length: {}\r\n\r\n", body.1.len());
        let mut msg = head.into_bytes();
        msg.extend_from_slice(body.1);
        self.w.send(&msg)?;
        let m = Message::read(&mut self.r)?.ok_or_else(|| io::Error::other("Receiver closed the connection"))?;
        if uri != "/feedback" {
            log::info!("{method} {uri} → {}", m.status());
        }
        Ok(m)
    }
}

pub fn bplist(v: Value) -> Vec<u8> {
    let mut out = Vec::new();
    plist::to_writer_binary(&mut out, &v).expect("plist serialisation");
    out
}

pub fn dict(pairs: Vec<(&str, Value)>) -> Value {
    let mut d = Dictionary::new();
    for (k, v) in pairs {
        d.insert(k.to_string(), v);
    }
    Value::Dictionary(d)
}

fn parse_plist(m: &Message) -> Option<Dictionary> {
    plist::Value::from_reader(io::Cursor::new(&m.body)).ok()?.into_dictionary()
}

/// Pairs on an open connection: stored credentials → verify; otherwise transient, falling back to
/// PIN pairing (the receiver shows a code; `pin_rx` delivers what the user typed).
pub fn pair(
    stream: &TcpStream,
    proto: &'static str,
    creds: Option<&Credentials>,
    events: &Events,
    pin_rx: &Receiver<String>,
    allow_transient: bool,
) -> io::Result<Vec<u8>> {
    let mut plain = PlainClient { stream, proto, cseq: 0 };
    let mut post = |p: &str, b: &[u8], h: u8| plain.post(p, b, h);
    if let Some(c) = creds {
        match pairing::verify(&mut post, c) {
            Ok(s) => return Ok(s),
            Err(PairError::Auth(e)) => {
                log::warn!("pair-verify failed: {e}");
                events.status("Saved pairing was rejected – pairing again…");
            }
            Err(e) => return Err(e.into()),
        }
    }
    if allow_transient && creds.is_none() {
        match pairing::transient(&mut post) {
            Ok(s) => return Ok(s),
            Err(PairError::Auth(e)) => log::info!("transient pairing refused ({e}), trying PIN pairing"),
            Err(e) => return Err(e.into()),
        }
    }
    pairing::pin_start(&mut post)?;
    events.emit(Event::PinRequired);
    events.status("Enter the code shown on the TV");
    let pin = pin_rx
        .recv_timeout(Duration::from_secs(180))
        .map_err(|_| io::Error::other("No PIN entered"))?;
    events.status("Pairing…");
    let new = pairing::pin_setup(&mut post, pin.trim())?;
    events.emit(Event::Credentials(new.encode()));
    Ok(pairing::verify(&mut post, &new)?)
}

pub struct Ap2Session {
    events: Arc<Events>,
    stopped: AtomicBool,
    audio: Arc<RtpAudio>,
    client: Mutex<Option<SecureClient>>,
    url: String,
    stream: Mutex<Option<TcpStream>>,
    volume: Mutex<f32>,
    meta_sent: Mutex<crate::metadata::Sent>,
}

impl Ap2Session {
    pub fn connect(
        host: &str,
        port: u16,
        volume: f32,
        creds: Option<Credentials>,
        events: Arc<Events>,
        pin_rx: Receiver<String>,
    ) -> io::Result<Arc<Self>> {
        let addr = (host, port).to_socket_addrs()?.next().ok_or_else(|| io::Error::other("Unknown receiver address"))?;
        let tcp = crate::netutil::connect_retry(&addr)?;
        tune_tcp(&tcp);
        let local = tcp.local_addr()?.ip();
        events.status("Pairing…");
        let secret = pair(&tcp, "RTSP/1.0", creds.as_ref(), &events, &pin_rx, true)?;

        let s = Arc::new(Self {
            events,
            stopped: AtomicBool::new(false),
            audio: RtpAudio::new(Some(audio_key(&secret)))?,
            client: Mutex::new(None),
            url: format!("rtsp://{local}/{}", rand_u64() % 9_000_000_000 + 1_000_000_000),
            stream: Mutex::new(Some(tcp.try_clone()?)),
            volume: Mutex::new(volume),
            meta_sent: Mutex::default(),
        });
        s.setup(&tcp, addr.ip(), &secret)?;
        let me = s.clone();
        thread::Builder::new().name("ap2-feedback".into()).spawn(move || me.feedback_loop())?;
        Ok(s)
    }

    /// Mirrors OwnTones' proven sequence: SETUP (session) → RECORD → SETUP (stream) → volume.
    fn setup(&self, tcp: &TcpStream, remote: IpAddr, secret: &[u8]) -> io::Result<()> {
        let mut c = SecureClient::new(tcp, secret, "RTSP/1.0")?;
        let mac: [u8; 6] = {
            let mut m: [u8; 6] = random();
            m[0] = (m[0] | 0x02) & 0xFE; // locally administered, unicast
            m
        };
        let mac_str = mac.iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":");
        let dacp = format!("{:016X}", rand_u64());
        c.headers = vec![
            ("X-Apple-Client-Name".into(), "Scotty".into()),
            ("Client-Instance".into(), dacp.clone()),
            ("DACP-ID".into(), dacp),
            ("Active-Remote".into(), (rand_u64() as u32).to_string()),
        ];

        // SETUP (session). SCOTTY_AP2_TIMING=PTP is a test hook for PTP-only receivers
        // (shairport-sync); Android itself can't do PTP, so real sessions use NTP.
        let ptp = std::env::var("SCOTTY_AP2_TIMING").is_ok_and(|v| v == "PTP");
        let local_ip = tcp.local_addr()?.ip().to_string();
        let mut fields = vec![
            ("deviceID", Value::String(mac_str)),
            ("sessionUUID", Value::String(pairing::uuid())),
            ("timingPort", Value::Integer(self.audio.timing_port().into())),
            ("timingProtocol", Value::String(if ptp { "PTP" } else { "NTP" }.into())),
        ];
        if ptp {
            fields.push(("timingPeerInfo", dict(vec![
                ("Addresses", Value::Array(vec![Value::String(local_ip.clone())])),
                ("ID", Value::String(local_ip.clone())),
            ])));
        }
        let body = bplist(dict(fields));
        let r = c.request("SETUP", &self.url, &[], Some(("application/x-apple-binary-plist", &body)))?;
        log::info!("SETUP session → {}", r.status());
        if r.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused session setup ({})", r.status())));
        }
        let d = parse_plist(&r).unwrap_or_default();
        if let Some(port) = d.get("eventPort").and_then(Value::as_unsigned_integer) {
            self.open_event_channel(SocketAddr::new(remote, port as u16), secret);
        }

        let r = c.request("RECORD", &self.url, &[], None)?;
        log::info!("RECORD → {}", r.status());
        if r.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused playback ({})", r.status())));
        }
        if ptp {
            let peers = bplist(Value::Array(vec![Value::String(local_ip.clone()), Value::String(remote.to_string())]));
            let _ = c.request("SETPEERS", &self.url, &[], Some(("/peer-list-changed", &peers)));
        }

        // SETUP (stream): realtime ALAC, encrypted with the pairing secret.
        let stream = dict(vec![
            ("audioFormat", Value::Integer(0x40000.into())), // ALAC 44100/16/2
            ("audioMode", Value::String("default".into())),
            ("controlPort", Value::Integer(self.audio.control_port().into())),
            ("ct", Value::Integer(2.into())), // ALAC
            ("isMedia", Value::Boolean(true)),
            ("latencyMax", Value::Integer(88200.into())),
            ("latencyMin", Value::Integer(11025.into())),
            ("shk", Value::Data(audio_key(secret).to_vec())),
            ("spf", Value::Integer((FRAMES as u64).into())),
            ("sr", Value::Integer(RATE.into())),
            ("type", Value::Integer(96.into())),
            ("supportsDynamicStreamID", Value::Boolean(false)),
            ("streamConnectionID", Value::Integer((rand_u64() as u32).into())),
        ]);
        let body = bplist(dict(vec![("streams", Value::Array(vec![stream]))]));
        let r = c.request("SETUP", &self.url, &[], Some(("application/x-apple-binary-plist", &body)))?;
        log::info!("SETUP stream → {}", r.status());
        if r.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused audio stream ({})", r.status())));
        }
        let d = parse_plist(&r).unwrap_or_default();
        let s0 = d
            .get("streams")
            .and_then(Value::as_array)
            .and_then(|a| a.first())
            .and_then(Value::as_dictionary)
            .cloned()
            .unwrap_or_default();
        let data_port = s0.get("dataPort").and_then(Value::as_unsigned_integer).ok_or_else(|| io::Error::other("Receiver sent no audio port"))?;
        let control = s0.get("controlPort").and_then(Value::as_unsigned_integer).map(|p| SocketAddr::new(remote, p as u16));
        log::info!("AirPlay 2 stream: data port {data_port}, control {control:?}");

        let vol = format!("volume: {:.6}\r\n", volume_db(*self.volume.lock().unwrap()));
        let _ = c.request("SET_PARAMETER", &self.url, &[], Some(("text/parameters", vol.as_bytes())));
        self.audio.start(Destination { data: SocketAddr::new(remote, data_port as u16), control });
        *self.client.lock().unwrap() = Some(c);
        Ok(())
    }

    fn open_event_channel(&self, addr: SocketAddr, secret: &[u8]) {
        let Ok(s) = TcpStream::connect_timeout(&addr, Duration::from_secs(3)) else {
            log::warn!("event channel {addr} unreachable");
            return;
        };
        tune_tcp(&s);
        let Ok((mut w, r)) = secure(&s, event_keys(secret)) else { return };
        thread::Builder::new()
            .name("ap2-events".into())
            .spawn(move || {
                let mut r = BufReader::new(r);
                while let Ok(Some(m)) = Message::read(&mut r) {
                    log::debug!("event: {} ({} bytes)", m.start, m.body.len());
                    let resp = format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", m.cseq());
                    if w.send(resp.as_bytes()).is_err() {
                        break;
                    }
                }
                drop(s);
            })
            .ok();
    }

    fn feedback_loop(&self) {
        let mut failures = 0;
        while !self.stopped.load(Ordering::SeqCst) {
            thread::sleep(Duration::from_secs(2));
            if self.stopped.load(Ordering::SeqCst) {
                break;
            }
            let ok = self
                .client
                .lock()
                .unwrap()
                .as_mut()
                .map(|c| c.request("POST", "/feedback", &[], None).map(|m| m.status() == 200).unwrap_or(false))
                .unwrap_or(false);
            failures = if ok { 0 } else { failures + 1 };
            if failures >= 3 {
                self.finish(Some("Lost connection to the receiver".into()));
                break;
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
        self.audio.stop();
        if let Some(mut c) = self.client.lock().unwrap().take() {
            let body = bplist(dict(vec![("streams", Value::Array(vec![dict(vec![("type", Value::Integer(96.into()))])]))]));
            let _ = c.request("TEARDOWN", &self.url, &[], Some(("application/x-apple-binary-plist", &body)));
            let _ = c.request("TEARDOWN", &self.url, &[], None);
        }
        if let Some(s) = self.stream.lock().unwrap().take() {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
    }
}

impl Session for Ap2Session {
    fn set_metadata(&self, m: &crate::metadata::Metadata) {
        let mut guard = self.client.lock().unwrap();
        let Some(c) = guard.as_mut() else { return };
        for (ty, body, rtptime) in crate::metadata::requests(self.audio.current_rtp(), m, &mut self.meta_sent.lock().unwrap()) {
            match c.request("SET_PARAMETER", &self.url, &[("RTP-Info", format!("rtptime={rtptime}"))], Some((&ty, &body))) {
                Ok(r) => log::info!("metadata {ty} → {}", r.status()),
                Err(e) => log::warn!("metadata {ty}: {e}"),
            }
        }
    }
    fn push_audio(&self, data: &[u8], _pts_us: i64) {
        self.audio.push_pcm(data);
    }
    fn set_volume(&self, volume: f32) {
        *self.volume.lock().unwrap() = volume;
        let body = format!("volume: {:.6}\r\n", volume_db(volume));
        if let Some(c) = self.client.lock().unwrap().as_mut() {
            let _ = c.request("SET_PARAMETER", &self.url.clone(), &[], Some(("text/parameters", body.as_bytes())));
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

/// Audio payload key: the first 32 bytes of the pairing secret (64 bytes after transient pairing).
fn audio_key(secret: &[u8]) -> [u8; 32] {
    let mut k = [0u8; 32];
    let n = secret.len().min(32);
    k[..n].copy_from_slice(&secret[..n]);
    k
}
