use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::sync::OnceLock;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

/// Random u64 from the std hasher's per-process random keys (no extra dependency needed).
pub fn rand_u64() -> u64 {
    let mut h = RandomState::new().build_hasher();
    h.write_u128(SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
    h.finish()
}

pub fn rand_u32() -> u32 {
    rand_u64() as u32
}

/// Monotonic microseconds since first call.
pub fn mono_us() -> i64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_micros() as i64
}

/// Current wall-clock time as 64-bit NTP timestamp.
pub fn ntp_now() -> u64 {
    let d = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    let secs = d.as_secs() + 2_208_988_800;
    let frac = ((d.subsec_nanos() as u64) << 32) / 1_000_000_000;
    (secs << 32) | frac
}
