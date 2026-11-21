//! Logger that forwards to logcat and also keeps the most recent lines in memory, so diagnostics
//! still contain Scotty's protocol log after busy apps have rotated the shared logcat buffer.

use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const KEEP: usize = 400;

fn ring() -> &'static Mutex<VecDeque<String>> {
    static R: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(VecDeque::with_capacity(KEEP)))
}

pub fn remember(level: log::Level, target: &str, msg: &str) {
    let d = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    let secs = d.as_secs() % 86_400;
    let line = format!(
        "{:02}:{:02}:{:02}.{:03} UTC {} {}: {}",
        secs / 3600,
        secs / 60 % 60,
        secs % 60,
        d.subsec_millis(),
        level,
        target.trim_start_matches("scotty_core::"),
        msg
    );
    let mut r = ring().lock().unwrap();
    if r.len() == KEEP {
        r.pop_front();
    }
    r.push_back(line);
}

pub fn dump() -> String {
    ring().lock().unwrap().iter().cloned().collect::<Vec<_>>().join("\n")
}

#[cfg(target_os = "android")]
pub struct RingLogger(pub android_logger::AndroidLogger);

#[cfg(target_os = "android")]
impl log::Log for RingLogger {
    fn enabled(&self, m: &log::Metadata) -> bool {
        m.level() <= log::Level::Info
    }
    fn log(&self, r: &log::Record) {
        if !self.enabled(r.metadata()) {
            return;
        }
        self.0.log(r);
        remember(r.level(), r.target(), &r.args().to_string());
    }
    fn flush(&self) {}
}

#[cfg(test)]
mod tests {
    #[test]
    fn keeps_only_recent_lines() {
        for i in 0..(super::KEEP + 10) {
            super::remember(log::Level::Info, "scotty_core::test", &format!("line {i}"));
        }
        let d = super::dump();
        assert_eq!(d.lines().count(), super::KEEP);
        assert!(d.ends_with(&format!("line {}", super::KEEP + 9)));
        assert!(!d.contains("line 9\n"));
    }
}
