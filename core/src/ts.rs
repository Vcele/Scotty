//! Minimal MPEG-2 transport stream muxer: one program, H.264 video and/or ADTS AAC audio.

pub const PMT_PID: u16 = 0x100;
pub const VIDEO_PID: u16 = 0x1011;
pub const AUDIO_PID: u16 = 0x1100;
pub const PACKET: usize = 188;

const PTS_OFFSET: i64 = 27_000; // 300 ms head-room so PCR < PTS
const PCR_LAG: i64 = 13_500; // PCR runs 150 ms behind video PTS
const AUD: [u8; 6] = [0, 0, 0, 1, 0x09, 0xF0];

/// Receives finished TS packets.
pub trait TsSink: Send {
    fn packet(&mut self, p: &[u8; PACKET]);
    /// Called right before the PAT/PMT + data of a video key frame are written.
    fn key_frame_start(&mut self, _pts90: i64) {}
    /// Called after every complete access unit.
    fn access_unit_end(&mut self) {}
}

pub struct TsMuxer<S: TsSink> {
    has_video: bool,
    has_audio: bool,
    sink: S,
    cc: Box<[u8; 0x2000]>,
    t0: Option<i64>,
    since_psi: u32,
}

impl<S: TsSink> TsMuxer<S> {
    pub fn new(has_video: bool, has_audio: bool, sink: S) -> Self {
        Self { has_video, has_audio, sink, cc: Box::new([0; 0x2000]), t0: None, since_psi: 0 }
    }

    pub fn sink(&mut self) -> &mut S {
        &mut self.sink
    }

    fn to_90k(&mut self, us: i64) -> i64 {
        let t0 = *self.t0.get_or_insert(us);
        (us - t0).max(0) * 9 / 100 + PTS_OFFSET
    }

    pub fn write_video(&mut self, annexb: &[u8], pts_us: i64, key: bool) {
        if !self.has_video {
            return;
        }
        let pts = self.to_90k(pts_us);
        if key {
            self.sink.key_frame_start(pts);
        }
        self.since_psi += 1;
        if key || self.since_psi >= 15 {
            self.write_psi();
            self.since_psi = 0;
        }
        let mut payload = Vec::with_capacity(AUD.len() + annexb.len());
        payload.extend_from_slice(&AUD);
        payload.extend_from_slice(annexb);
        self.write_pes(VIDEO_PID, 0xE0, &payload, pts, Some((pts - PCR_LAG) * 300), key, false);
        self.sink.access_unit_end();
    }

    pub fn write_audio(&mut self, adts: &[u8], pts_us: i64) {
        if !self.has_audio {
            return;
        }
        if !self.has_video {
            if self.since_psi.is_multiple_of(20) {
                self.write_psi();
            }
            self.since_psi += 1;
        }
        let pts = self.to_90k(pts_us);
        let pcr = (!self.has_video).then(|| (pts - PCR_LAG) * 300);
        self.write_pes(AUDIO_PID, 0xC0, adts, pts, pcr, !self.has_video, true);
        self.sink.access_unit_end();
    }

    fn write_psi(&mut self) {
        let pat = with_crc(vec![
            0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01,
            0xE0 | (PMT_PID >> 8) as u8, PMT_PID as u8,
        ]);
        self.write_section(0, &pat);

        let mut streams = Vec::new();
        let mut es = |ty: u8, pid: u16| streams.extend_from_slice(&[ty, 0xE0 | (pid >> 8) as u8, pid as u8, 0xF0, 0x00]);
        if self.has_video {
            es(0x1B, VIDEO_PID);
        }
        if self.has_audio {
            es(0x0F, AUDIO_PID);
        }
        let pcr_pid = if self.has_video { VIDEO_PID } else { AUDIO_PID };
        let len = 9 + streams.len() + 4;
        let mut pmt = vec![
            0x02, 0xB0 | (len >> 8) as u8, len as u8, 0x00, 0x01, 0xC1, 0x00, 0x00,
            0xE0 | (pcr_pid >> 8) as u8, pcr_pid as u8, 0xF0, 0x00,
        ];
        pmt.extend_from_slice(&streams);
        let pmt = with_crc(pmt);
        self.write_section(PMT_PID, &pmt);
    }

    fn write_section(&mut self, pid: u16, section: &[u8]) {
        let mut p = [0xFFu8; PACKET];
        p[0] = 0x47;
        p[1] = 0x40 | (pid >> 8) as u8;
        p[2] = pid as u8;
        p[3] = 0x10 | self.next_cc(pid);
        p[4] = 0; // pointer field
        p[5..5 + section.len()].copy_from_slice(section);
        self.sink.packet(&p);
    }

    #[allow(clippy::too_many_arguments)]
    fn write_pes(&mut self, pid: u16, stream_id: u8, data: &[u8], pts: i64, pcr: Option<i64>, random_access: bool, bounded: bool) {
        let pes_len = if bounded && data.len() + 8 <= 0xFFFF { data.len() + 8 } else { 0 };
        let mut full = Vec::with_capacity(14 + data.len());
        full.extend_from_slice(&[0, 0, 1, stream_id, (pes_len >> 8) as u8, pes_len as u8, 0x80, 0x80, 5]);
        full.extend_from_slice(&encode_pts(pts));
        full.extend_from_slice(data);

        let mut off = 0;
        let mut first = true;
        while off < full.len() {
            let mut p = [0u8; PACKET];
            p[0] = 0x47;
            p[1] = if first { 0x40 } else { 0 } | (pid >> 8) as u8;
            p[2] = pid as u8;
            let remaining = full.len() - off;
            let fields = first && (pcr.is_some() || random_access);
            let mut af = if fields { 2 + if pcr.is_some() { 6 } else { 0 } } else { 0 };
            let payload = remaining.min(184 - af);
            if payload < 184 - af {
                af = 184 - payload;
            }
            p[3] = if af > 0 { 0x30 } else { 0x10 } | self.next_cc(pid);
            let mut i = 4;
            if af > 0 {
                p[i] = (af - 1) as u8;
                i += 1;
                if af > 1 {
                    let mut flags = 0u8;
                    if fields && random_access {
                        flags |= 0x40;
                    }
                    let pcr = if fields { pcr } else { None };
                    if pcr.is_some() {
                        flags |= 0x10;
                    }
                    p[i] = flags;
                    i += 1;
                    if let Some(pcr) = pcr {
                        let base = pcr / 300;
                        let ext = pcr % 300;
                        p[i] = (base >> 25) as u8;
                        p[i + 1] = (base >> 17) as u8;
                        p[i + 2] = (base >> 9) as u8;
                        p[i + 3] = (base >> 1) as u8;
                        p[i + 4] = (((base & 1) << 7) as u8) | 0x7E | ((ext >> 8) as u8);
                        p[i + 5] = ext as u8;
                        i += 6;
                    }
                    while i < 4 + af {
                        p[i] = 0xFF;
                        i += 1;
                    }
                }
            }
            p[4 + af..4 + af + payload].copy_from_slice(&full[off..off + payload]);
            off += payload;
            first = false;
            self.sink.packet(&p);
        }
    }

    fn next_cc(&mut self, pid: u16) -> u8 {
        let c = &mut self.cc[pid as usize];
        let v = *c;
        *c = (v + 1) & 0x0F;
        v
    }
}

fn encode_pts(pts: i64) -> [u8; 5] {
    [
        0x21 | ((pts >> 29) as u8 & 0x0E),
        (pts >> 22) as u8,
        ((pts >> 14) as u8 & 0xFE) | 1,
        (pts >> 7) as u8,
        ((pts << 1) as u8 & 0xFE) | 1,
    ]
}

fn with_crc(mut s: Vec<u8>) -> Vec<u8> {
    let crc = crc32_mpeg(&s);
    s.extend_from_slice(&crc.to_be_bytes());
    s
}

pub fn crc32_mpeg(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc ^= (b as u32) << 24;
        for _ in 0..8 {
            crc = if crc & 0x8000_0000 != 0 { (crc << 1) ^ 0x04C1_1DB7 } else { crc << 1 };
        }
    }
    crc
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Collect(Vec<[u8; PACKET]>, u32);
    impl TsSink for Collect {
        fn packet(&mut self, p: &[u8; PACKET]) {
            self.0.push(*p)
        }
        fn key_frame_start(&mut self, _: i64) {
            self.1 += 1
        }
    }

    #[test]
    fn crc_matches_reference() {
        // CRC-32/MPEG-2 check value
        assert_eq!(crc32_mpeg(b"123456789"), 0x0376_E6E7);
    }

    #[test]
    fn packets_are_well_formed() {
        let mut m = TsMuxer::new(true, true, Collect::default());
        let frame = [0, 0, 0, 1, 0x65, 1, 2, 3].repeat(100);
        m.write_video(&frame, 0, true);
        m.write_audio(&[0xFF, 0xF1, 0x50, 0x80, 0x02, 0x1F, 0xFC], 10_000);
        m.write_video(&frame[..50], 33_000, false);
        let c = m.sink();
        assert_eq!(c.1, 1);
        assert!(c.0.iter().all(|p| p[0] == 0x47));
        // continuity counters per PID increase by one
        let mut last = std::collections::HashMap::new();
        for p in &c.0 {
            let pid = ((p[1] as u16 & 0x1F) << 8) | p[2] as u16;
            let cc = p[3] & 0x0F;
            if let Some(prev) = last.insert(pid, cc) {
                assert_eq!(cc, (prev + 1) & 0x0F, "pid {pid:#x}");
            }
        }
        // first packets are PAT then PMT
        assert_eq!(((c.0[0][1] as u16 & 0x1F) << 8) | c.0[0][2] as u16, 0);
        assert_eq!(((c.0[1][1] as u16 & 0x1F) << 8) | c.0[1][2] as u16, PMT_PID);
    }
}
