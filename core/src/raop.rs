//! AirPlay 1 audio (RAOP): RTSP ANNOUNCE/SETUP/RECORD + unencrypted ALAC over RTP.
//! Accepted by AirPlay 1 receivers and most third-party AirPlay 2 receivers.

use crate::events::Events;
use crate::netutil::tune_tcp;
use crate::rtp_audio::{Destination, RtpAudio, FRAMES, RATE};
use crate::rtsp::Conn;
use crate::session::Session;
use crate::util::{rand_u32, rand_u64};
use std::io;
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const USER_AGENT: &str = "iTunes/12.12.4 (Macintosh; OS X 12.6)";

/// Statuses that mean "this receiver wants AirPlay 2 pairing".
pub const NEEDS_AP2: &[u16] = &[403, 470, 471];

#[derive(Debug)]
pub enum RaopError {
    /// ANNOUNCE rejected with this status.
    Rejected(u16),
    Io(io::Error),
}

impl From<io::Error> for RaopError {
    fn from(e: io::Error) -> Self {
        RaopError::Io(e)
    }
}

pub fn volume_db(v: f32) -> f64 {
    if v <= 0.001 {
        -144.0
    } else {
        -30.0 + 30.0 * v.min(1.0) as f64
    }
}

struct Control {
    conn: Arc<Conn>,
    url: String,
    session: Option<String>,
}

pub struct RaopSession {
    events: Arc<Events>,
    stopped: AtomicBool,
    audio: Arc<RtpAudio>,
    control: Mutex<Option<Control>>,
    volume: Mutex<f32>,
}

impl RaopSession {
    /// Connects synchronously up to RECORD so callers can fall back to AirPlay 2 on rejection.
    pub fn connect(host: &str, port: u16, volume: f32, events: Arc<Events>) -> Result<Arc<Self>, RaopError> {
        let s = Arc::new(Self {
            events,
            stopped: AtomicBool::new(false),
            audio: RtpAudio::new(None)?,
            control: Mutex::new(None),
            volume: Mutex::new(volume),
        });
        s.handshake(host, port)?;
        let me = s.clone();
        thread::Builder::new().name("raop-keepalive".into()).spawn(move || me.keepalive())?;
        Ok(s)
    }

    fn handshake(self: &Arc<Self>, host: &str, port: u16) -> Result<(), RaopError> {
        let addr = (host, port).to_socket_addrs()?.next().ok_or_else(|| io::Error::other("Unknown receiver address"))?;
        let tcp = crate::netutil::connect_retry(&addr)?;
        tune_tcp(&tcp);
        let remote = addr.ip();
        let weak = Arc::downgrade(self);
        let conn = Conn::start(
            tcp,
            Some(USER_AGENT),
            None,
            Some(Box::new(move || {
                if let Some(s) = weak.upgrade() {
                    if !s.stopped.load(Ordering::SeqCst) {
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
            s => {
                conn.close();
                return Err(RaopError::Rejected(s));
            }
        }

        let mut h = base.clone();
        h.push((
            "Transport",
            format!(
                "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port={};timing_port={}",
                self.audio.control_port(),
                self.audio.timing_port()
            ),
        ));
        let setup = conn.request("SETUP", &url, &h, None, t)?;
        if setup.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused SETUP ({})", setup.status())).into());
        }
        let session = setup.header("Session").map(|s| s.split(';').next().unwrap_or(s).to_string());
        let transport = setup.header("Transport").unwrap_or_default().to_string();
        let port_of =
            |key: &str| transport.split(';').find_map(|kv| kv.strip_prefix(key)?.strip_prefix('=')?.parse::<u16>().ok());
        let server_port = port_of("server_port").ok_or_else(|| io::Error::other("Receiver sent no audio port"))?;
        let control = port_of("control_port").map(|p| SocketAddr::new(remote, p));

        let mut h = base.clone();
        if let Some(s) = &session {
            h.push(("Session", s.clone()));
        }
        h.push(("Range", "npt=0-".into()));
        h.push(("RTP-Info", format!("seq={};rtptime={}", self.audio.first_seq, self.audio.rtp_start)));
        let record = conn.request("RECORD", &url, &h, None, t)?;
        if record.status() != 200 {
            return Err(io::Error::other(format!("Receiver refused RECORD ({})", record.status())).into());
        }
        *self.control.lock().unwrap() = Some(Control { conn, url, session });
        self.send_volume(*self.volume.lock().unwrap());
        self.audio.start(Destination { data: (remote, server_port).into(), control });
        Ok(())
    }

    fn keepalive(&self) {
        let mut ticks = 0;
        while !self.stopped.load(Ordering::SeqCst) {
            thread::sleep(Duration::from_secs(1));
            ticks += 1;
            if ticks % 15 == 0 {
                let c = self.control.lock().unwrap().as_ref().map(|c| (c.conn.clone(), c.session.clone()));
                if let Some((conn, session)) = c {
                    let h: Vec<(&str, String)> = session.map(|s| ("Session", s)).into_iter().collect();
                    let _ = conn.request("OPTIONS", "*", &h, None, Duration::from_secs(5));
                }
            }
        }
    }

    fn send_volume(&self, v: f32) {
        let c = self.control.lock().unwrap().as_ref().map(|c| (c.conn.clone(), c.url.clone(), c.session.clone()));
        if let Some((conn, url, session)) = c {
            thread::spawn(move || {
                let body = format!("volume: {:.6}\r\n", volume_db(v));
                let h: Vec<(&str, String)> = session.map(|s| ("Session", s)).into_iter().collect();
                let _ = conn.request("SET_PARAMETER", &url, &h, Some(("text/parameters", body.as_bytes())), Duration::from_secs(3));
            });
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
        if let Some(c) = self.control.lock().unwrap().take() {
            thread::spawn(move || {
                if let Some(s) = c.session {
                    let _ = c.conn.request("TEARDOWN", &c.url, &[("Session", s)], None, Duration::from_millis(1500));
                }
                c.conn.close();
            });
        }
    }
}

impl Session for RaopSession {
    fn set_metadata(&self, m: &crate::metadata::Metadata) {
        let c = self.control.lock().unwrap().as_ref().map(|c| (c.conn.clone(), c.url.clone(), c.session.clone()));
        let Some((conn, url, session)) = c else { return };
        for (ty, body, rtptime) in crate::metadata::requests(self.audio.current_rtp(), m, true) {
            let mut h: Vec<(&str, String)> = vec![("RTP-Info", format!("rtptime={rtptime}"))];
            if let Some(s) = &session {
                h.push(("Session", s.clone()));
            }
            match conn.request("SET_PARAMETER", &url, &h, Some((&ty, &body)), Duration::from_secs(5)) {
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
        self.send_volume(volume);
    }
    fn stop(&self) {
        self.shutdown();
        self.events.ended(None);
    }
    fn events(&self) -> &Events {
        &self.events
    }
}
