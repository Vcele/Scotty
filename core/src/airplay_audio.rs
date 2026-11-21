//! AirPlay audio entry point: picks AirPlay 1 (RAOP) or AirPlay 2 and falls back automatically.

use crate::ap2::Ap2Session;
use crate::events::Events;
use crate::hap::pairing::Credentials;
use crate::raop::{RaopError, RaopSession, NEEDS_AP2};
use crate::session::Session;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::thread;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Protocol {
    /// Try AirPlay 1 first, upgrade to AirPlay 2 if the receiver insists.
    Auto,
    AirPlay1,
    /// Apple devices (HomePod, Apple TV) – go straight to AirPlay 2.
    AirPlay2,
}

impl Protocol {
    pub fn from_i32(v: i32) -> Self {
        match v {
            1 => Protocol::AirPlay1,
            2 => Protocol::AirPlay2,
            _ => Protocol::Auto,
        }
    }
}

pub struct AirPlayAudio {
    events: Arc<Events>,
    inner: Mutex<Option<Arc<dyn Session>>>,
    pin_tx: Mutex<Sender<String>>,
    stopped: AtomicBool,
    volume: Mutex<f32>,
}

fn rejection(status: u16) -> String {
    match status {
        401 => "Receiver is password protected – turn off “Require password” in its AirPlay settings".into(),
        453 => "Receiver is busy with another AirPlay sender".into(),
        s => format!("Receiver rejected the stream ({s})"),
    }
}

impl AirPlayAudio {
    pub fn start(host: &str, port: u16, volume: f32, protocol: Protocol, creds: Option<String>) -> io::Result<Arc<Self>> {
        let (pin_tx, pin_rx) = channel();
        let s = Arc::new(Self {
            events: Arc::new(Events::default()),
            inner: Mutex::new(None),
            pin_tx: Mutex::new(pin_tx),
            stopped: AtomicBool::new(false),
            volume: Mutex::new(volume),
        });
        let me = s.clone();
        let host = host.to_string();
        let creds = creds.as_deref().and_then(Credentials::decode);
        thread::Builder::new().name("airplay-audio".into()).spawn(move || {
            me.events.status("Connecting…");
            let ap2 = |me: &Arc<Self>| -> io::Result<Arc<dyn Session>> {
                let v = *me.volume.lock().unwrap();
                Ok(Ap2Session::connect(&host, port, v, creds.clone(), me.events.clone(), pin_rx)? as Arc<dyn Session>)
            };
            let result: io::Result<(Arc<dyn Session>, &str)> = if protocol == Protocol::AirPlay2 || creds.is_some() {
                ap2(&me).map(|s| (s, "AirPlay 2"))
            } else {
                match RaopSession::connect(&host, port, *me.volume.lock().unwrap(), me.events.clone()) {
                    Ok(s) => Ok((s as Arc<dyn Session>, "AirPlay")),
                    Err(RaopError::Rejected(st)) if protocol == Protocol::Auto && NEEDS_AP2.contains(&st) => {
                        log::info!("RAOP refused ({st}), switching to AirPlay 2");
                        ap2(&me).map(|s| (s, "AirPlay 2"))
                    }
                    Err(RaopError::Rejected(st)) => Err(io::Error::other(rejection(st))),
                    Err(RaopError::Io(e)) if protocol == Protocol::Auto => {
                        log::info!("RAOP failed ({e}), trying AirPlay 2");
                        ap2(&me).map(|s| (s, "AirPlay 2")).map_err(|e2| {
                            log::warn!("AirPlay 2 also failed: {e2}");
                            e
                        })
                    }
                    Err(RaopError::Io(e)) => Err(e),
                }
            };
            match result {
                Ok((session, proto)) => {
                    if me.stopped.load(Ordering::SeqCst) {
                        session.stop();
                        return;
                    }
                    *me.inner.lock().unwrap() = Some(session);
                    me.events.live(format!("Streaming lossless audio via {proto}"));
                }
                Err(e) => {
                    if !me.stopped.load(Ordering::SeqCst) {
                        me.events.ended(Some(e.to_string()));
                    }
                }
            }
        })?;
        Ok(s)
    }
}

impl Session for AirPlayAudio {
    fn push_audio(&self, data: &[u8], pts_us: i64) {
        let inner = self.inner.lock().unwrap().clone();
        if let Some(s) = inner {
            s.push_audio(data, pts_us);
        }
    }
    fn set_volume(&self, volume: f32) {
        *self.volume.lock().unwrap() = volume;
        let inner = self.inner.lock().unwrap().clone();
        if let Some(s) = inner {
            s.set_volume(volume);
        }
    }
    fn submit_pin(&self, pin: &str) {
        let _ = self.pin_tx.lock().unwrap().send(pin.to_string());
    }
    fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        let inner = self.inner.lock().unwrap().take();
        match inner {
            Some(s) => s.stop(),
            None => self.events.ended(None),
        }
    }
    fn events(&self) -> &Events {
        &self.events
    }
}
