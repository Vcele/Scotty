use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Mutex;
use std::time::Duration;

/// Things a session tells the app. Encoded as tab-separated strings over JNI.
#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// Progress text for the UI.
    Status(String),
    /// Media is flowing; text describes it.
    Live(String),
    /// Start the screen encoder with these parameters. `level` is the H.264 level ×10 (31, 32, 40).
    Mode { width: u32, height: u32, fps: u32, bitrate: u32, level: u32, audio: bool },
    /// Receiver asked for an IDR frame.
    KeyFrame,
    /// Receiver shows a PIN; the app should ask the user and call `submit_pin`.
    PinRequired,
    /// New long-term pairing to store for this receiver.
    Credentials(String),
    /// Session is over; `Some(reason)` if it failed.
    Ended(Option<String>),
}

impl Event {
    pub fn encode(&self) -> String {
        match self {
            Event::Status(s) => format!("status\t{s}"),
            Event::Live(s) => format!("live\t{s}"),
            Event::Mode { width, height, fps, bitrate, level, audio } => {
                format!("mode\t{width}\t{height}\t{fps}\t{bitrate}\t{level}\t{}", *audio as u8)
            }
            Event::KeyFrame => "idr".into(),
            Event::PinRequired => "pin".into(),
            Event::Credentials(c) => format!("creds\t{c}"),
            Event::Ended(e) => format!("ended\t{}", e.as_deref().unwrap_or("")),
        }
    }
}

pub struct Events {
    tx: Sender<Event>,
    rx: Mutex<Receiver<Event>>,
    ended: AtomicBool,
}

impl Default for Events {
    fn default() -> Self {
        let (tx, rx) = channel();
        Self { tx, rx: Mutex::new(rx), ended: AtomicBool::new(false) }
    }
}

impl Events {
    pub fn emit(&self, e: Event) {
        if let Event::Ended(_) = e {
            if self.ended.swap(true, Ordering::SeqCst) {
                return; // only one Ended per session
            }
        }
        match &e {
            Event::Credentials(_) => log::info!("event: Credentials(<redacted>)"),
            e => log::info!("event: {e:?}"),
        }
        let _ = self.tx.send(e);
    }
    pub fn status(&self, s: impl Into<String>) {
        self.emit(Event::Status(s.into()))
    }
    pub fn live(&self, s: impl Into<String>) {
        self.emit(Event::Live(s.into()))
    }
    pub fn ended(&self, err: Option<String>) {
        self.emit(Event::Ended(err))
    }
    pub fn has_ended(&self) -> bool {
        self.ended.load(Ordering::SeqCst)
    }
    pub fn poll(&self, timeout: Duration) -> Option<Event> {
        self.rx.lock().unwrap().recv_timeout(timeout).ok()
    }
}
