//! AirPlay audio sender (RAOP).
//!
//! RTSP (OPTIONS/ANNOUNCE/SETUP/RECORD/SET_PARAMETER/TEARDOWN), ALAC frames carrying raw PCM
//! (the "uncompressed" escape), RTP over UDP, NTP timing replies, sync packets and
//! retransmissions. Accepted by AirPlay 1 receivers and most AirPlay 2 receivers without
//! HomeKit pairing (TVs, Sonos, AVRs, shairport-sync, AirPort Express…).

use crate::events::Events;
use crate::rtsp::Conn;
use crate::session::Session;
use crate::util::{ntp_now, rand_u32, rand_u64};
use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const RATE: u64 = 44_100;
pub const FRAMES: usize = 352;
const FRAME_BYTES: usize = FRAMES * 4;
const LATENCY: u32 = (RATE * 2) as u32;
const USER_AGENT: &str = "iTunes/12.12.4 (Macintosh; OS X 12.6)";

/// Wraps 352 frames of 16-bit LE stereo PCM in an ALAC frame with the escape (uncompressed) flag.
pub fn alac_uncompressed(pcm: &[u8]) -> Vec<u8> {
    debug_assert_eq!(pcm.len(), FRAME_BYTES);
    let mut w = BitWriter::with_capacity(pcm.len() + 8);
    w.put(1, 3); // ID_CPE – channel pair
    w.put(0, 4); // element instance tag
    w.put(0, 12); // unused
    w.put(0, 1); // no explicit sample count – frame has the default length
    w.put(0, 2); // bytes shifted
    w.put(1, 1); // escape flag: uncompressed samples follow
    for s in pcm.as_chunks::<2>().0 {
        w.put(s[1] as u32, 8); // big-endian sample
        w.put(s[0] as u32, 8);
    }
    w.put(7, 3); // ID_END
    w.finish()
}

struct BitWriter {
    buf: Vec<u8>,
    bits: usize,
}

impl BitWriter {
    fn with_capacity(n: usize) -> Self {
        Self { buf: Vec::with_capacity(n), bits: 0 }
    }
    fn put(&mut self, v: u32, n: usize) {
        for i in (0..n).rev() {
            if self.bits.is_multiple_of(8) {
                self.buf.push(0);
            }
            if (v >> i) & 1 == 1 {
                *self.buf.last_mut().unwrap() |= 0x80 >> (self.bits % 8);
            }
            self.bits += 1;
        }
    }
    fn finish(self) -> Vec<u8> {
        self.buf
    }
}

struct Stream {
    dest: SocketAddr,
    pending: Vec<u8>,
    seq: u16,
    rtp_start: u32,
    first: bool,
    start: Option<Instant>,
    history: Vec<Option<Vec<u8>>>,
}

pub struct RaopSession {
    events: Events,
    stopped: AtomicBool,
    audio_sock: UdpSocket,
    control_sock: UdpSocket,
    timing_sock: UdpSocket,
    stream: Mutex<Option<Stream>>,
    samples_sent: AtomicU64,
    rtp_start: u32,
    remote_control: Mutex<Option<SocketAddr>>,
    rtsp: Mutex<Option<(Arc<Conn>, String, Option<String>)>>, // conn, url, session
    volume: Mutex<f32>,
}

impl RaopSession {
    pub fn start(host: &str, port: u16, volume: f32) -> io::Result<Arc<Self>> {
        let bind = || -> io::Result<UdpSocket> {
            let s = UdpSocket::bind(("0.0.0.0", 0))?;
            s.set_read_timeout(Some(Duration::from_millis(500)))?;
            Ok(s)
        };
        let s = Arc::new(Self {
            events: Events::default(),
            stopped: AtomicBool::new(false),
            audio_sock: bind()?,
            control_sock: bind()?,
            timing_sock: bind()?,
            stream: Mutex::new(None),
            samples_sent: AtomicU64::new(0),
            rtp_start: rand_u32() & 0x7FFF_FFFF,
            remote_control: Mutex::new(None),
            rtsp: Mutex::new(None),
            volume: Mutex::new(volume),
        });
        let me = s.clone();
        let host = host.to_string();
        thread::Builder::new().name("raop".into()).spawn(move || {
            if let Err(e) = me.run(&host, port) {
                if !me.is_stopped() {
                    log::warn!("raop failed: {e}");
                    me.finish(Some(e.to_string()));
                }
            }
        })?;
        Ok(s)
    }

    fn is_stopped(&self) -> bool {
        self.stopped.load(Ordering::SeqCst)
    }

    fn run(self: &Arc<Self>, host: &str, port: u16) -> io::Result<()> {
        self.events.status("Connecting…");
        let addr = (host, port)
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| io::Error::other("Unknown receiver address"))?;
        let tcp = TcpStream::connect_timeout(&addr, Duration::from_secs(5))?;
        let remote = addr.ip();
        let weak = Arc::downgrade(self);
        let conn = Conn::start(
            tcp,
            Some(USER_AGENT),
            None,
            Some(Box::new(move || {
                if let Some(s) = weak.upgrade() {
                    if !s.is_stopped() {
                        s.finish(Some("The receiver closed the connection".into()));
                    }
                }
            })),
        )?;
        let sid = 1_000_000_000u64 + rand_u64() % 8_999_999_999;
        let url = format!("rtsp://{}/{sid}", conn.local_ip());
        let client_id = format!("{:016X}", rand_u64());
        let base = vec![
            ("Client-Instance", client_id.clone()),
            ("DACP-ID", client_id),
            ("Active-Remote", (1 + rand_u32() % 0x7FFF_FFFE).to_string()),
        ];
        let t = Duration::from_secs(10);
        *self.rtsp.lock().unwrap() = Some((conn.clone(), url.clone(), None));

        conn.request("OPTIONS", "*", &base, None, t)?;

        let sdp = format!(
            "v=0\r\no=iTunes {sid} 0 IN IP4 {local}\r\ns=iTunes\r\nc=IN IP4 {remote}\r\nt=0 0\r\n\
             m=audio 0 RTP/AVP 96\r\na=rtpmap:96 AppleLossless\r\n\
             a=fmtp:96 {FRAMES} 0 16 40 10 14 2 255 0 0 {RATE}\r\n",
            local = conn.local_ip(),
        );
        let announce = conn.request("ANNOUNCE", &url, &base, Some(("application/sdp", sdp.as_bytes())), t)?;
        match announce.status() {
            200 => {}
            401 => return Err(io::Error::other("Receiver is password protected – turn off “Require password” in its AirPlay settings")),
            403 => return Err(io::Error::other("Receiver refused – allow “Everyone on the same network” in its AirPlay settings")),
            453 => return Err(io::Error::other("Receiver is busy with another AirPlay sender")),
            470 | 471 => return Err(io::Error::other("Receiver requires AirPlay 2 HomeKit pairing, which Scotty can't do yet")),
            s => return Err(io::Error::other(format!("Receiver rejected the stream ({s})"))),
        }

        self.spawn_timing();
        self.spawn_control();
        let mut h = base.clone();
        h.push((
            "Transport",
            format!(
                "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port={};timing_port={}",
                self.control_sock.local_addr()?.port(),
                self.timing_sock.local_addr()?.port()
            ),
        ));
        let setup = conn.request("SETUP", &url, &h, None, t)?;
        if setup.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused SETUP ({})", setup.status())));
        }
        let session = setup.header("Session").map(|s| s.split(';').next().unwrap_or(s).to_string());
        let transport = setup.header("Transport").unwrap_or_default().to_string();
        let port_of = |key: &str| {
            transport.split(';').find_map(|kv| kv.strip_prefix(key)?.strip_prefix('=')?.parse::<u16>().ok())
        };
        let server_port = port_of("server_port").ok_or_else(|| io::Error::other("Receiver sent no audio port"))?;
        if let Some(cp) = port_of("control_port") {
            *self.remote_control.lock().unwrap() = Some(SocketAddr::new(remote, cp));
        }
        *self.rtsp.lock().unwrap() = Some((conn.clone(), url.clone(), session.clone()));
        let with_session = |mut v: Vec<(&'static str, String)>| {
            if let Some(s) = &session {
                v.push(("Session", s.clone()));
            }
            v
        };

        let seq = rand_u32() as u16;
        let mut h = with_session(base.clone());
        h.push(("Range", "npt=0-".into()));
        h.push(("RTP-Info", format!("seq={seq};rtptime={}", self.rtp_start)));
        let record = conn.request("RECORD", &url, &h, None, t)?;
        if record.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused RECORD ({})", record.status())));
        }
        let v = *self.volume.lock().unwrap();
        let _ = self.send_volume(v);

        *self.stream.lock().unwrap() = Some(Stream {
            dest: SocketAddr::new(remote, server_port),
            pending: Vec::with_capacity(FRAME_BYTES * 4),
            seq,
            rtp_start: self.rtp_start,
            first: true,
            start: None,
            history: vec![None; 1024],
        });
        self.spawn_sync(remote);
        self.events.live("Streaming lossless audio · play anything on your phone");

        let mut ticks = 0;
        while !self.is_stopped() && !conn.is_closed() {
            thread::sleep(Duration::from_secs(1));
            ticks += 1;
            if ticks % 15 == 0 {
                let _ = conn.request("OPTIONS", "*", &with_session(base.clone()), None, Duration::from_secs(5));
            }
        }
        Ok(())
    }

    fn send_frame(&self, st: &mut Stream, pcm: &[u8]) {
        let alac = alac_uncompressed(pcm);
        let mut p = Vec::with_capacity(12 + alac.len());
        let ts = st.rtp_start.wrapping_add(self.samples_sent.load(Ordering::SeqCst) as u32);
        p.push(0x80);
        p.push(if st.first { 0xE0 } else { 0x60 });
        st.first = false;
        p.extend_from_slice(&st.seq.to_be_bytes());
        p.extend_from_slice(&ts.to_be_bytes());
        p.extend_from_slice(&0x5C07_7A11u32.to_be_bytes()); // ssrc
        p.extend_from_slice(&alac);
        if let Err(e) = self.audio_sock.send_to(&p, st.dest) {
            log::warn!("audio send: {e}");
        }
        st.history[(st.seq & 1023) as usize] = Some(p);
        st.seq = st.seq.wrapping_add(1);
        self.samples_sent.fetch_add(FRAMES as u64, Ordering::SeqCst);
    }

    fn spawn_timing(self: &Arc<Self>) {
        let me = self.clone();
        thread::spawn(move || {
            let mut buf = [0u8; 128];
            while !me.is_stopped() {
                let Ok((n, from)) = me.timing_sock.recv_from(&mut buf) else { continue };
                if n < 32 || buf[1] & 0x7F != 0x52 {
                    continue;
                }
                let recv = ntp_now();
                let mut r = [0u8; 32];
                r[0] = 0x80;
                r[1] = 0xD3;
                r[3] = 7;
                r[8..16].copy_from_slice(&buf[24..32]); // origin = their transmit time
                r[16..24].copy_from_slice(&recv.to_be_bytes());
                r[24..32].copy_from_slice(&ntp_now().to_be_bytes());
                let _ = me.timing_sock.send_to(&r, from);
            }
        });
    }

    fn spawn_control(self: &Arc<Self>) {
        let me = self.clone();
        thread::spawn(move || {
            let mut buf = [0u8; 64];
            while !me.is_stopped() {
                let Ok((n, from)) = me.control_sock.recv_from(&mut buf) else { continue };
                if n < 8 || buf[1] & 0x7F != 0x55 {
                    continue;
                }
                let first = u16::from_be_bytes([buf[4], buf[5]]);
                let count = u16::from_be_bytes([buf[6], buf[7]]).min(64);
                let resend: Vec<Vec<u8>> = {
                    let st = me.stream.lock().unwrap();
                    let Some(st) = st.as_ref() else { continue };
                    (0..count)
                        .filter_map(|k| {
                            let s = first.wrapping_add(k);
                            let p = st.history[(s & 1023) as usize].as_ref()?;
                            (u16::from_be_bytes([p[2], p[3]]) == s).then(|| {
                                let mut r = vec![0x80, 0xD6, 0x00, 0x01];
                                r.extend_from_slice(p);
                                r
                            })
                        })
                        .collect()
                };
                for r in resend {
                    let _ = me.control_sock.send_to(&r, from);
                }
            }
        });
    }

    fn spawn_sync(self: &Arc<Self>, _remote: IpAddr) {
        let me = self.clone();
        thread::spawn(move || {
            let mut first = true;
            while !me.is_stopped() {
                if let Some(dest) = *me.remote_control.lock().unwrap() {
                    let now = me.rtp_start.wrapping_add(me.samples_sent.load(Ordering::SeqCst) as u32);
                    let mut p = [0u8; 20];
                    p[0] = if first { 0x90 } else { 0x80 };
                    p[1] = 0xD4;
                    p[3] = 7;
                    p[4..8].copy_from_slice(&now.wrapping_sub(LATENCY).to_be_bytes());
                    p[8..16].copy_from_slice(&ntp_now().to_be_bytes());
                    p[16..20].copy_from_slice(&now.to_be_bytes());
                    let _ = me.control_sock.send_to(&p, dest);
                    first = false;
                }
                thread::sleep(Duration::from_secs(1));
            }
        });
    }

    fn send_volume(&self, v: f32) -> io::Result<()> {
        let Some((conn, url, session)) = self.rtsp.lock().unwrap().clone() else { return Ok(()) };
        let db = if v <= 0.001 { -144.0 } else { -30.0 + 30.0 * v.min(1.0) as f64 };
        let body = format!("volume: {db:.6}\r\n");
        let h: Vec<(&str, String)> = session.map(|s| ("Session", s)).into_iter().collect();
        conn.request("SET_PARAMETER", &url, &h, Some(("text/parameters", body.as_bytes())), Duration::from_secs(3))?;
        Ok(())
    }

    fn finish(&self, err: Option<String>) {
        self.shutdown();
        self.events.ended(err);
    }

    fn shutdown(&self) {
        if self.stopped.swap(true, Ordering::SeqCst) {
            return;
        }
        if let Some((conn, url, session)) = self.rtsp.lock().unwrap().take() {
            thread::spawn(move || {
                if let Some(s) = session {
                    let _ = conn.request("TEARDOWN", &url, &[("Session", s)], None, Duration::from_millis(1500));
                }
                conn.close();
            });
        }
    }
}

impl Session for RaopSession {
    /// 16-bit LE stereo PCM at 44.1 kHz, any chunk size.
    fn push_audio(&self, data: &[u8], _pts_us: i64) {
        let mut guard = self.stream.lock().unwrap();
        let Some(st) = guard.as_mut() else { return };
        st.pending.extend_from_slice(data);
        while st.pending.len() >= FRAME_BYTES {
            let chunk: Vec<u8> = st.pending.drain(..FRAME_BYTES).collect();
            let start = *st.start.get_or_insert_with(Instant::now);
            // Capture stalls (nothing playing, CPU hiccup): pad with silence so the RTP clock
            // keeps pace with real time.
            let expected = start.elapsed().as_micros() as u64 * RATE / 1_000_000;
            while expected.saturating_sub(self.samples_sent.load(Ordering::SeqCst)) > RATE / 4 {
                self.send_frame(st, &[0u8; FRAME_BYTES]);
            }
            self.send_frame(st, &chunk);
        }
    }

    fn set_volume(&self, volume: f32) {
        *self.volume.lock().unwrap() = volume;
        let me_rtsp = self.rtsp.lock().unwrap().clone();
        if let Some((conn, url, session)) = me_rtsp {
            thread::spawn(move || {
                let db = if volume <= 0.001 { -144.0 } else { -30.0 + 30.0 * volume.min(1.0) as f64 };
                let body = format!("volume: {db:.6}\r\n");
                let h: Vec<(&str, String)> = session.map(|s| ("Session", s)).into_iter().collect();
                let _ = conn.request("SET_PARAMETER", &url, &h, Some(("text/parameters", body.as_bytes())), Duration::from_secs(3));
            });
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
    fn alac_frame_layout() {
        let mut pcm = vec![0u8; FRAME_BYTES];
        pcm[0] = 0x34; // first left sample = 0x1234 (LE)
        pcm[1] = 0x12;
        let f = alac_uncompressed(&pcm);
        // 23 header bits + 352*2*16 sample bits + 3 end bits
        assert_eq!(f.len(), (23 + FRAMES * 32 + 3).div_ceil(8));
        // header: 001 0000 000000000000 0 00 1 → first 23 bits
        assert_eq!(f[0], 0b0010_0000);
        assert_eq!(f[1], 0);
        // bit 22 is the escape flag; sample 0x1234 starts at bit 23
        let bits: Vec<u8> = f.iter().flat_map(|b| (0..8).rev().map(move |i| (b >> i) & 1)).collect();
        assert_eq!(bits[22], 1);
        let sample = bits[23..39].iter().fold(0u16, |a, b| (a << 1) | *b as u16);
        assert_eq!(sample, 0x1234);
        let end = 23 + FRAMES * 32;
        assert_eq!(&bits[end..end + 3], &[1, 1, 1]);
    }
}
