//! RTP audio engine shared by AirPlay 1 (RAOP) and AirPlay 2 realtime streams:
//! ALAC framing, real-time pacing with silence fill, NTP timing replies, sync packets,
//! retransmissions and (for AirPlay 2) per-packet ChaCha20-Poly1305 encryption.

use crate::hap::{nonce_from, seal};
use crate::netutil::{udp, TOS_VOICE};
use crate::util::{ntp_now, rand_u32};
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const RATE: u64 = 44_100;
pub const FRAMES: usize = 352;
pub const FRAME_BYTES: usize = FRAMES * 4;
/// Receiver-side buffer we ask for. Two seconds rides out Wi-Fi hiccups without stutter.
pub const LATENCY: u32 = (RATE * 2) as u32;
const HISTORY: usize = 2048;

/// Wraps 352 frames of 16-bit LE stereo PCM in an ALAC frame with the escape (uncompressed) flag.
pub fn alac_uncompressed(pcm: &[u8]) -> Vec<u8> {
    debug_assert_eq!(pcm.len(), FRAME_BYTES);
    let mut out = Vec::with_capacity(pcm.len() + 4);
    // 23 header bits: 001 (CPE) 0000 (tag) 000000000000 (unused) 0 (no size) 00 (shift) 1 (escape)
    // followed by big-endian samples and the 3-bit END tag. Everything after the header is
    // shifted by 23 bits = 2 bytes + 7 bits, so we pack via a 32-bit accumulator.
    let mut acc: u64 = 0b0010_0000_0000_0000_0000_001; // 23 bits
    let mut nbits: u32 = 23;
    let mut push = |v: u64, n: u32, out: &mut Vec<u8>| {
        acc = (acc << n) | v;
        nbits += n;
        while nbits >= 8 {
            nbits -= 8;
            out.push((acc >> nbits) as u8);
        }
        acc &= (1 << nbits) - 1;
    };
    for s in pcm.chunks_exact(2) {
        push(((s[1] as u64) << 8) | s[0] as u64, 16, &mut out);
    }
    push(0b111, 3, &mut out);
    if nbits > 0 {
        out.push((acc << (8 - nbits)) as u8);
    }
    out
}

struct Stream {
    pending: Vec<u8>,
    seq: u16,
    first: bool,
    /// When the last capture chunk arrived (stall detection).
    start: Option<Instant>,
    history: Vec<Option<Vec<u8>>>,
}

/// Where packets go once the receiver told us its ports.
#[derive(Clone, Copy)]
pub struct Destination {
    pub data: SocketAddr,
    pub control: Option<SocketAddr>,
}

pub struct RtpAudio {
    stopped: AtomicBool,
    data_sock: UdpSocket,
    control_sock: UdpSocket,
    timing_sock: UdpSocket,
    stream: Mutex<Stream>,
    dest: Mutex<Option<Destination>>,
    samples_sent: AtomicU64,
    silent_frames: AtomicU64,
    timing_requests: AtomicU64,
    resend_requests: AtomicU64,
    pub rtp_start: u32,
    pub first_seq: u16,
    ssrc: u32,
    /// AirPlay 2 `shk`; None for RAOP.
    key: Option<[u8; 32]>,
}

impl RtpAudio {
    pub fn new(key: Option<[u8; 32]>) -> io::Result<Arc<Self>> {
        let t = Some(Duration::from_millis(300));
        let first_seq = rand_u32() as u16;
        let a = Arc::new(Self {
            stopped: AtomicBool::new(false),
            data_sock: udp(TOS_VOICE, t)?,
            control_sock: udp(TOS_VOICE, t)?,
            timing_sock: udp(TOS_VOICE, t)?,
            stream: Mutex::new(Stream {
                pending: Vec::with_capacity(FRAME_BYTES * 8),
                seq: first_seq,
                first: true,
                start: None,
                history: vec![None; HISTORY],
            }),
            dest: Mutex::new(None),
            samples_sent: AtomicU64::new(0),
            silent_frames: AtomicU64::new(0),
            timing_requests: AtomicU64::new(0),
            resend_requests: AtomicU64::new(0),
            rtp_start: rand_u32() & 0x7FFF_FFFF,
            first_seq,
            ssrc: rand_u32(),
            key,
        });
        a.spawn_timing();
        a.spawn_control();
        Ok(a)
    }

    pub fn control_port(&self) -> u16 {
        self.control_sock.local_addr().map(|a| a.port()).unwrap_or(0)
    }
    pub fn timing_port(&self) -> u16 {
        self.timing_sock.local_addr().map(|a| a.port()).unwrap_or(0)
    }

    /// Starts sending to the receiver (call after RECORD succeeded).
    pub fn start(self: &Arc<Self>, dest: Destination) {
        *self.dest.lock().unwrap() = Some(dest);
        self.spawn_sync();
    }

    /// RTP timestamp of the next sample to be sent (= what is being captured right now).
    pub fn current_rtp(&self) -> u32 {
        self.rtp_start.wrapping_add(self.samples_sent.load(Ordering::SeqCst) as u32)
    }

    pub fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
    }

    fn is_stopped(&self) -> bool {
        self.stopped.load(Ordering::SeqCst)
    }

    /// 16-bit LE stereo PCM at 44.1 kHz, any chunk size.
    pub fn push_pcm(&self, data: &[u8]) {
        let Some(dest) = *self.dest.lock().unwrap() else { return };
        let mut st = self.stream.lock().unwrap();
        // A real capture stall (nothing arrived for > 300 ms): fill the gap with silence so the
        // receiver doesn't underrun. Small, steady clock drift between capture and wall clock is
        // left alone – the sync packets let the receiver follow it, and padding for drift would
        // insert audible gaps every few minutes.
        let now = Instant::now();
        if let Some(prev) = st.start.replace(now) {
            let gap = now.duration_since(prev);
            if gap > Duration::from_millis(300) {
                let fill = ((gap - Duration::from_millis(50)).min(Duration::from_secs(2)).as_micros() as u64 * RATE
                    / 1_000_000) as usize
                    / FRAMES;
                log::info!("capture stalled for {} ms, filling {} silent packets", gap.as_millis(), fill);
                for _ in 0..fill {
                    self.send_frame(&mut st, dest, &[0u8; FRAME_BYTES]);
                }
            }
        }
        st.pending.extend_from_slice(data);
        // Bound the backlog: if capture delivered a burst (e.g. after a stall), drop the oldest
        // audio instead of building up latency.
        let max = FRAME_BYTES * 64;
        if st.pending.len() > max {
            let excess = (st.pending.len() - max) / FRAME_BYTES * FRAME_BYTES;
            st.pending.drain(..excess);
        }
        while st.pending.len() >= FRAME_BYTES {
            let chunk: Vec<u8> = st.pending.drain(..FRAME_BYTES).collect();
            if chunk.iter().all(|&b| b == 0) {
                self.silent_frames.fetch_add(1, Ordering::Relaxed);
            }
            self.send_frame(&mut st, dest, &chunk);
        }
    }

    fn send_frame(&self, st: &mut Stream, dest: Destination, pcm: &[u8]) {
        let alac = alac_uncompressed(pcm);
        let ts = self.rtp_start.wrapping_add(self.samples_sent.load(Ordering::SeqCst) as u32);
        let mut p = Vec::with_capacity(12 + alac.len() + 24);
        p.push(0x80);
        p.push(if st.first { 0xE0 } else { 0x60 });
        st.first = false;
        p.extend_from_slice(&st.seq.to_be_bytes());
        p.extend_from_slice(&ts.to_be_bytes());
        p.extend_from_slice(&self.ssrc.to_be_bytes());
        match &self.key {
            None => p.extend_from_slice(&alac),
            Some(key) => {
                // AirPlay 2 realtime: AAD = timestamp+ssrc, nonce = sequence number (so a
                // retransmitted packet is byte-identical), trailing 8 nonce bytes.
                let nb = (st.seq as u64).to_le_bytes();
                let nonce = nonce_from(&nb);
                let aad: [u8; 8] = p[4..12].try_into().unwrap();
                p.extend_from_slice(&seal(key, &nonce, &aad, &alac));
                p.extend_from_slice(&nb);
            }
        }
        if let Err(e) = self.data_sock.send_to(&p, dest.data) {
            log::debug!("audio send: {e}");
        }
        let idx = st.seq as usize % HISTORY;
        st.history[idx] = Some(p);
        st.seq = st.seq.wrapping_add(1);
        self.samples_sent.fetch_add(FRAMES as u64, Ordering::SeqCst);
    }

    fn spawn_timing(self: &Arc<Self>) {
        let me = Arc::downgrade(self);
        thread::Builder::new().name("ntp".into()).spawn(move || {
            let mut buf = [0u8; 128];
            loop {
                let Some(me) = me.upgrade() else { break };
                if me.is_stopped() {
                    break;
                }
                let Ok((n, from)) = me.timing_sock.recv_from(&mut buf) else { continue };
                if n < 32 || buf[1] & 0x7F != 0x52 {
                    continue;
                }
                me.timing_requests.fetch_add(1, Ordering::Relaxed);
                let recv = ntp_now();
                let mut r = [0u8; 32];
                r[0] = 0x80;
                r[1] = 0xD3;
                r[3] = 7;
                r[8..16].copy_from_slice(&buf[24..32]);
                r[16..24].copy_from_slice(&recv.to_be_bytes());
                r[24..32].copy_from_slice(&ntp_now().to_be_bytes());
                let _ = me.timing_sock.send_to(&r, from);
            }
        }).ok();
    }

    fn spawn_control(self: &Arc<Self>) {
        let me = Arc::downgrade(self);
        thread::Builder::new().name("rtp-control".into()).spawn(move || {
            let mut buf = [0u8; 64];
            loop {
                let Some(me) = me.upgrade() else { break };
                if me.is_stopped() {
                    break;
                }
                let Ok((n, from)) = me.control_sock.recv_from(&mut buf) else { continue };
                if n < 8 || buf[1] & 0x7F != 0x55 {
                    continue;
                }
                let first = u16::from_be_bytes([buf[4], buf[5]]);
                let count = u16::from_be_bytes([buf[6], buf[7]]).min(128);
                let resend: Vec<Vec<u8>> = {
                    let st = me.stream.lock().unwrap();
                    (0..count)
                        .filter_map(|k| {
                            let s = first.wrapping_add(k);
                            let p = st.history[s as usize % HISTORY].as_ref()?;
                            (u16::from_be_bytes([p[2], p[3]]) == s).then(|| {
                                let mut r = vec![0x80, 0xD6, 0x00, 0x01];
                                r.extend_from_slice(p);
                                r
                            })
                        })
                        .collect()
                };
                me.resend_requests.fetch_add(1, Ordering::Relaxed);
                log::debug!("retransmit {count} from {first}: {} available", resend.len());
                for r in resend {
                    let _ = me.control_sock.send_to(&r, from);
                }
            }
        }).ok();
    }

    fn spawn_sync(self: &Arc<Self>) {
        let me = Arc::downgrade(self);
        thread::Builder::new().name("rtp-sync".into()).spawn(move || {
            let mut first = true;
            let mut ticks = 0u32;
            loop {
                let Some(me) = me.upgrade() else { break };
                if me.is_stopped() {
                    break;
                }
                ticks += 1;
                if ticks % 10 == 0 {
                    let frames = me.samples_sent.load(Ordering::SeqCst) / FRAMES as u64;
                    log::info!(
                        "audio stats: {frames} packets, {} silent captured, {} timing requests, {} resend requests",
                        me.silent_frames.load(Ordering::Relaxed),
                        me.timing_requests.load(Ordering::Relaxed),
                        me.resend_requests.load(Ordering::Relaxed)
                    );
                }
                if let Some(Destination { control: Some(dest), .. }) = *me.dest.lock().unwrap() {
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
                drop(me);
                thread::sleep(Duration::from_secs(1));
            }
        }).ok();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hap::open;

    /// Reference bit-by-bit packer the fast version must match.
    fn reference(pcm: &[u8]) -> Vec<u8> {
        let mut bits: Vec<u8> = Vec::new();
        let mut put = |v: u32, n: usize| (0..n).rev().for_each(|i| bits.push(((v >> i) & 1) as u8));
        put(1, 3);
        put(0, 4);
        put(0, 12);
        put(0, 1);
        put(0, 2);
        put(1, 1);
        for s in pcm.chunks_exact(2) {
            put(s[1] as u32, 8);
            put(s[0] as u32, 8);
        }
        put(7, 3);
        bits.chunks(8).map(|c| c.iter().enumerate().fold(0u8, |a, (i, b)| a | (b << (7 - i)))).collect()
    }

    #[test]
    fn alac_matches_reference() {
        let pcm: Vec<u8> = (0..FRAME_BYTES).map(|i| (i * 7 + 3) as u8).collect();
        assert_eq!(alac_uncompressed(&pcm), reference(&pcm));
        assert_eq!(alac_uncompressed(&pcm).len(), (23 + FRAMES * 32 + 3usize).div_ceil(8));
    }

    #[test]
    fn ap2_packets_decrypt_like_a_receiver() {
        let key = [5u8; 32];
        let audio = RtpAudio::new(Some(key)).unwrap();
        let rx = UdpSocket::bind("127.0.0.1:0").unwrap();
        rx.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
        audio.start(Destination { data: rx.local_addr().unwrap(), control: None });
        audio.push_pcm(&[1u8; FRAME_BYTES]);
        let mut buf = [0u8; 2048];
        let n = rx.recv(&mut buf).unwrap();
        // shairport-sync's AirPlay 2 receiver: nonce = last 8 bytes, tag the 16 before, AAD = bytes 4..12
        let nonce = nonce_from(&buf[n - 8..n]);
        let plain = open(&key, &nonce, &buf[4..12], &buf[12..n - 8]).expect("decrypts");
        assert_eq!(plain, alac_uncompressed(&[1u8; FRAME_BYTES]));
        audio.stop();
    }
}
