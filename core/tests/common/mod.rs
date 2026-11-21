#![allow(dead_code)]
use std::path::PathBuf;

/// (annexb access unit, is_key) at 30 fps and ADTS frames at 48 kHz.
pub struct Media {
    pub video: Vec<(Vec<u8>, bool)>,
    pub audio: Vec<Vec<u8>>,
}

/// Loads `$SCOTTY_MEDIA/in.h264` + `in.aac` (ffmpeg output) or falls back to synthetic data.
pub fn load() -> Media {
    if let Some(dir) = std::env::var_os("SCOTTY_MEDIA").map(PathBuf::from) {
        let h264 = std::fs::read(dir.join("in.h264")).expect("in.h264");
        let aac = std::fs::read(dir.join("in.aac")).expect("in.aac");
        return Media { video: split_access_units(&h264), audio: split_adts(&aac) };
    }
    let mut video = Vec::new();
    for i in 0..60 {
        let key = i % 30 == 0;
        let mut au = Vec::new();
        if key {
            au.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1F]);
            au.extend_from_slice(&[0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80]);
        }
        au.extend_from_slice(&[0, 0, 0, 1, if key { 0x65 } else { 0x41 }]);
        au.extend(std::iter::repeat_n(i as u8, 3000));
        video.push((au, key));
    }
    let audio = (0..90).map(|_| vec![0xFF, 0xF1, 0x4C, 0x80, 0x01, 0x3F, 0xFC]).collect();
    Media { video, audio }
}

fn split_access_units(b: &[u8]) -> Vec<(Vec<u8>, bool)> {
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 < b.len() {
        if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
            starts.push(i);
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut out = Vec::new();
    let (mut cur, mut key, mut slice) = (Vec::new(), false, false);
    for (k, &s) in starts.iter().enumerate() {
        let end = starts.get(k + 1).copied().unwrap_or(b.len());
        let nal = &b[s..end];
        let ty = nal[3] & 0x1F;
        if (ty == 1 || ty == 5 || ty == 7) && slice {
            out.push((std::mem::take(&mut cur), key));
            key = false;
            slice = false;
        }
        if ty == 5 {
            key = true;
        }
        if ty == 1 || ty == 5 {
            slice = true;
        }
        if ty != 9 {
            cur.push(0);
            cur.extend_from_slice(nal);
        }
    }
    if slice {
        out.push((cur, key));
    }
    out
}

fn split_adts(b: &[u8]) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    let mut i = 0;
    while i + 7 <= b.len() {
        let len = (((b[i + 3] & 3) as usize) << 11) | ((b[i + 4] as usize) << 3) | ((b[i + 5] as usize) >> 5);
        if len < 7 || i + len > b.len() {
            break;
        }
        out.push(b[i..i + len].to_vec());
        i += len;
    }
    out
}

/// Feeds media into a push function interleaved by timestamp.
pub fn feed(m: &Media, mut video: impl FnMut(&[u8], i64, bool), mut audio: impl FnMut(&[u8], i64)) {
    let (mut vi, mut ai) = (0, 0);
    while vi < m.video.len() || ai < m.audio.len() {
        let vt = vi as i64 * 1_000_000 / 30;
        let at = ai as i64 * 1024 * 1_000_000 / 48_000;
        if vi < m.video.len() && (ai >= m.audio.len() || vt <= at) {
            video(&m.video[vi].0, 5_000_000 + vt, m.video[vi].1);
            vi += 1;
        } else {
            audio(&m.audio[ai], 5_000_000 + at);
            ai += 1;
        }
    }
}

pub fn out_dir() -> Option<PathBuf> {
    std::env::var_os("SCOTTY_OUT").map(PathBuf::from)
}
