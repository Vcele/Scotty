//! JNI surface for `app.scotty.core.ScottyCore`.
//!
//! Sessions live in a registry keyed by an opaque `long` handle so Kotlin never holds raw
//! pointers. Every entry point catches panics so nothing unwinds across the FFI boundary.

use crate::airplay_video::AirPlayScreenSession;
use crate::airplay_audio::{AirPlayAudio, Protocol};
use crate::session::Session;
use crate::wfd::MiracastSession;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

fn registry() -> &'static Mutex<HashMap<i64, Arc<dyn Session>>> {
    static R: OnceLock<Mutex<HashMap<i64, Arc<dyn Session>>>> = OnceLock::new();
    R.get_or_init(Default::default)
}

fn register(s: Arc<dyn Session>) -> jlong {
    static NEXT: AtomicI64 = AtomicI64::new(1);
    let id = NEXT.fetch_add(1, Ordering::SeqCst);
    registry().lock().unwrap().insert(id, s);
    id
}

fn get(h: jlong) -> Option<Arc<dyn Session>> {
    registry().lock().unwrap().get(&h).cloned()
}

fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| {
        log::error!("panic in native call");
        default
    })
}

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

/// Returns a handle, or a negative value on failure (error is logged).
fn start(r: std::io::Result<Arc<dyn Session>>) -> jlong {
    match r {
        Ok(s) => register(s),
        Err(e) => {
            log::error!("session start failed: {e}");
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_init(_env: JNIEnv, _c: JClass) {
    #[cfg(target_os = "android")]
    {
        let inner = android_logger::AndroidLogger::new(
            android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("ScottyCore"),
        );
        if log::set_boxed_logger(Box::new(crate::ringlog::RingLogger(inner))).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_miracastStart(_env: JNIEnv, _c: JClass, port: jint) -> jlong {
    guard(-1, || start(MiracastSession::start(port as u16).map(|s| s as Arc<dyn Session>)))
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_airplayAudioStart(
    mut env: JNIEnv, _c: JClass, host: JString, raop_port: jint, ap2_port: jint, volume: jfloat, protocol: jint, creds: JString,
) -> jlong {
    let host = string(&mut env, &host);
    let creds = (!creds.is_null()).then(|| string(&mut env, &creds)).filter(|c| !c.is_empty());
    guard(-1, || {
        start(AirPlayAudio::start(&host, raop_port as u16, ap2_port as u16, volume, Protocol::from_i32(protocol), creds).map(|s| s as Arc<dyn Session>))
    })
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_airplayScreenStart(
    mut env: JNIEnv, _c: JClass, host: JString, port: jint, creds: JString,
) -> jlong {
    let host = string(&mut env, &host);
    let creds = (!creds.is_null()).then(|| string(&mut env, &creds)).filter(|c| !c.is_empty());
    guard(-1, || start(AirPlayScreenSession::start(&host, port as u16, creds).map(|s| s as Arc<dyn Session>)))
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_pushVideo(
    env: JNIEnv, _c: JClass, h: jlong, data: JByteArray, pts_us: jlong, key: jboolean,
) {
    let Some(s) = get(h) else { return };
    let Ok(bytes) = env.convert_byte_array(&data) else { return };
    guard((), || s.push_video(&bytes, pts_us, key == JNI_TRUE));
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_pushAudio(
    env: JNIEnv, _c: JClass, h: jlong, data: JByteArray, pts_us: jlong,
) {
    let Some(s) = get(h) else { return };
    let Ok(bytes) = env.convert_byte_array(&data) else { return };
    guard((), || s.push_audio(&bytes, pts_us));
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_setVolume(_env: JNIEnv, _c: JClass, h: jlong, v: jfloat) {
    if let Some(s) = get(h) {
        guard((), || s.set_volume(v));
    }
}

/// Blocks up to `timeout_ms` for the next event; null if none.
#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_pollEvent(
    env: JNIEnv, _c: JClass, h: jlong, timeout_ms: jint,
) -> jstring {
    let Some(s) = get(h) else { return std::ptr::null_mut() };
    let ev = guard(None, || s.events().poll(Duration::from_millis(timeout_ms.max(0) as u64)));
    match ev {
        Some(e) => env.new_string(e.encode()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Stops the session and frees the handle.
#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_stop(_env: JNIEnv, _c: JClass, h: jlong) {
    let s = registry().lock().unwrap().remove(&h);
    if let Some(s) = s {
        guard((), || s.stop());
    }
}

#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_submitPin(mut env: JNIEnv, _c: JClass, h: jlong, pin: JString) {
    let pin = string(&mut env, &pin);
    if let Some(s) = get(h) {
        guard((), || s.submit_pin(&pin));
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_scotty_core_ScottyCore_setMetadata(
    mut env: JNIEnv, _c: JClass, h: jlong, title: JString, artist: JString, album: JString,
    artwork: JByteArray, artwork_mime: JString, duration_ms: jlong, position_ms: jlong,
) {
    let Some(s) = get(h) else { return };
    let opt = |env: &mut JNIEnv, js: &JString| if js.is_null() { String::new() } else { string(env, js) };
    let m = crate::metadata::Metadata {
        title: opt(&mut env, &title),
        artist: opt(&mut env, &artist),
        album: opt(&mut env, &album),
        artwork: if artwork.is_null() { None } else { env.convert_byte_array(&artwork).ok() },
        artwork_mime: opt(&mut env, &artwork_mime),
        duration_ms: duration_ms.max(0) as u64,
        position_ms: position_ms.max(0) as u64,
    };
    guard((), || s.set_metadata(&m));
}

/// Scotty's own recent log lines (survive logcat rotation), for diagnostics.
#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_recentLog(env: JNIEnv, _c: JClass) -> jstring {
    env.new_string(crate::ringlog::dump()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Lets the Kotlin side add lines to the in-memory diagnostics log.
#[no_mangle]
pub extern "system" fn Java_app_scotty_core_ScottyCore_note(mut env: JNIEnv, _c: JClass, tag: JString, msg: JString) {
    let tag = string(&mut env, &tag);
    let msg = string(&mut env, &msg);
    crate::ringlog::remember(log::Level::Info, &tag, &msg);
}
