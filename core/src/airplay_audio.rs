//! AirPlay audio entry point: picks AirPlay 1 (RAOP) or AirPlay 2 and falls back automatically.

use crate::ap2::Ap2Session;
use crate::events::Events;
use crate::hap::pairing::Credentials;
use crate::metadata::Metadata;
use crate::raop::{RaopError, RaopSession, NEEDS_AP2};
use crate::session::Session;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::thread;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Protocol {
    /// Receiver doesn't advertise AirPlay 2: AirPlay 1 first, upgrade if it insists.
    Auto,
    AirPlay1,
    /// Receiver advertises AirPlay 2 pairing (feature bit 48: HomePod, Apple TV, Sonos, most
    /// current speakers/TVs). Many of these accept an AirPlay 1 connection but then play
    /// nothing, so AirPlay 2 goes first and AirPlay 1 is only the fallback.
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
    metadata: Mutex<Option<Metadata>>,
}

fn rejection(status: u16) -> String {
    match status {
        401 => "Receiver is password protected – turn off “Require password” in its AirPlay settings".into(),
        453 => "Receiver is busy with another AirPlay sender".into(),
        s => format!("Receiver rejected the stream ({s})"),
    }
}

/// Errors that come from the user's side of pairing; falling back to AirPlay 1 would only hide them.
fn is_user_pairing_error(e: &io::Error) -> bool {
    let s = e.to_string();
    s.contains("Wrong PIN") || s.contains("No PIN entered") || s.contains("Too many attempts")
}

impl AirPlayAudio {
    pub fn start(
        host: &str,
        raop_port: u16,
        ap2_port: u16,
        volume: f32,
        protocol: Protocol,
        creds: Option<String>,
    ) -> io::Result<Arc<Self>> {
        let (pin_tx, pin_rx) = channel();
        let s = Arc::new(Self {
            events: Arc::new(Events::default()),
            inner: Mutex::new(None),
            pin_tx: Mutex::new(pin_tx),
            stopped: AtomicBool::new(false),
            volume: Mutex::new(volume),
            metadata: Mutex::new(None),
        });
        let me = s.clone();
        let host = host.to_string();
        let creds = creds.as_deref().and_then(Credentials::decode);
        let raop_port = if raop_port == 0 { ap2_port } else { raop_port };
        let ap2_port = if ap2_port == 0 { raop_port } else { ap2_port };
        thread::Builder::new().name("airplay-audio".into()).spawn(move || {
            me.events.status("Connecting…");
            let mut pin_rx = Some(pin_rx);
            let mut ap2 = |me: &Arc<Self>| -> io::Result<(Arc<dyn Session>, &'static str)> {
                let rx = pin_rx.take().ok_or_else(|| io::Error::other("AirPlay 2 already attempted"))?;
                let v = *me.volume.lock().unwrap();
                log::info!("AirPlay 2 → {host}:{ap2_port}");
                let s = Ap2Session::connect(&host, ap2_port, v, creds.clone(), me.events.clone(), rx)?;
                Ok((s as Arc<dyn Session>, "AirPlay 2"))
            };
            let raop = |me: &Arc<Self>| -> Result<(Arc<dyn Session>, &'static str), RaopError> {
                log::info!("AirPlay 1 → {host}:{raop_port}");
                let s = RaopSession::connect(&host, raop_port, *me.volume.lock().unwrap(), me.events.clone())?;
                Ok((s as Arc<dyn Session>, "AirPlay"))
            };
            let raop_err = |e: RaopError| match e {
                RaopError::Rejected(st) => io::Error::other(rejection(st)),
                RaopError::Io(e) => e,
            };

            let result: io::Result<(Arc<dyn Session>, &str)> = match protocol {
                Protocol::AirPlay1 => raop(&me).map_err(raop_err),
                Protocol::AirPlay2 => match ap2(&me) {
                    Ok(s) => Ok(s),
                    Err(e) if is_user_pairing_error(&e) || me.stopped.load(Ordering::SeqCst) => Err(e),
                    Err(e) => {
                        log::warn!("AirPlay 2 failed ({e}), falling back to AirPlay 1");
                        me.events.status("Trying AirPlay 1…");
                        raop(&me).map_err(|e1| {
                            log::warn!("AirPlay 1 also failed: {e1:?}");
                            e
                        })
                    }
                },
                Protocol::Auto => match raop(&me) {
                    Ok(s) => Ok(s),
                    Err(RaopError::Rejected(st)) if NEEDS_AP2.contains(&st) => {
                        log::info!("RAOP refused ({st}), switching to AirPlay 2");
                        ap2(&me)
                    }
                    Err(RaopError::Rejected(st)) => Err(io::Error::other(rejection(st))),
                    Err(RaopError::Io(e)) => {
                        log::info!("RAOP failed ({e}), trying AirPlay 2");
                        ap2(&me).map_err(|e2| {
                            log::warn!("AirPlay 2 also failed: {e2}");
                            e
                        })
                    }
                },
            };
            match result {
                Ok((session, proto)) => {
                    if me.stopped.load(Ordering::SeqCst) {
                        session.stop();
                        return;
                    }
                    if let Some(m) = me.metadata.lock().unwrap().clone() {
                        session.set_metadata(&m);
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
    fn set_metadata(&self, m: &Metadata) {
        *self.metadata.lock().unwrap() = Some(m.clone());
        let inner = self.inner.lock().unwrap().clone();
        if let Some(s) = inner {
            s.set_metadata(m);
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
