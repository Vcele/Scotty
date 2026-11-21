//! Now-playing info for AirPlay receivers: DMAP text (title/artist/album), artwork and progress,
//! all sent as RTSP SET_PARAMETER with an `RTP-Info: rtptime=` anchor.

use crate::rtp_audio::RATE;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Metadata {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub artwork: Option<Vec<u8>>,
    /// "image/jpeg" or "image/png"
    pub artwork_mime: String,
    pub duration_ms: u64,
    pub position_ms: u64,
}

fn tag(out: &mut Vec<u8>, code: &[u8; 4], data: &[u8]) {
    out.extend_from_slice(code);
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    out.extend_from_slice(data);
}

/// `mlit { minm, asar, asal }` – what iTunes/Music send.
pub fn dmap(m: &Metadata) -> Vec<u8> {
    let mut items = Vec::new();
    tag(&mut items, b"minm", m.title.as_bytes());
    tag(&mut items, b"asar", m.artist.as_bytes());
    tag(&mut items, b"asal", m.album.as_bytes());
    let mut out = Vec::new();
    tag(&mut out, b"mlit", &items);
    out
}

/// `(start, current, end)` RTP times for the `progress:` parameter, given the RTP time of the
/// sample being captured now.
pub fn progress(current_rtp: u32, m: &Metadata) -> (u32, u32, u32) {
    let elapsed = (m.position_ms * RATE / 1000) as u32;
    let start = current_rtp.wrapping_sub(elapsed);
    let end = start.wrapping_add((m.duration_ms * RATE / 1000) as u32);
    (start, current_rtp, if m.duration_ms > 0 { end } else { current_rtp })
}

/// What was last sent, so unchanged text/artwork/progress isn't re-sent. Receivers treat every
/// text update as a new track and reset their display, so flooding them hides the cover art.
#[derive(Default)]
pub struct Sent {
    text: Option<(String, String, String)>,
    artwork: Option<u64>,
    progress_start: Option<u32>,
}

fn fingerprint(b: &[u8]) -> u64 {
    // FNV-1a – only to notice changes
    b.iter().fold(0xcbf2_9ce4_8422_2325u64, |h, &x| (h ^ x as u64).wrapping_mul(0x100_0000_01b3))
}

/// The requests to send for `m`, given what was already sent: (content type, body, rtptime).
/// Order follows OwnTones (progress, text, artwork); unchanged parts are skipped and a progress
/// update is only sent when the track start moved by more than 2 s (seek / pause / new track).
pub fn requests(current_rtp: u32, m: &Metadata, sent: &mut Sent) -> Vec<(String, Vec<u8>, u32)> {
    let (start, cur, end) = progress(current_rtp, m);
    let text = (m.title.clone(), m.artist.clone(), m.album.clone());
    let new_track = sent.text.as_ref() != Some(&text);
    let art = m.artwork.as_deref().map(fingerprint);
    let moved = sent.progress_start.is_none_or(|s| (start.wrapping_sub(s) as i32).unsigned_abs() > 2 * RATE as u32);

    let mut v = Vec::new();
    if new_track || moved {
        v.push(("text/parameters".to_string(), format!("progress: {start}/{cur}/{end}\r\n").into_bytes(), start));
        sent.progress_start = Some(start);
    }
    if new_track {
        v.push(("application/x-dmap-tagged".to_string(), dmap(m), start));
        sent.text = Some(text);
    }
    if let (Some(a), Some(fp)) = (&m.artwork, art) {
        if new_track || sent.artwork != Some(fp) {
            let mime = if m.artwork_mime.is_empty() { "image/jpeg".into() } else { m.artwork_mime.clone() };
            v.push((mime, a.clone(), start));
            sent.artwork = Some(fp);
        }
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dmap_layout() {
        let m = Metadata { title: "Song".into(), artist: "Band".into(), album: "LP".into(), ..Default::default() };
        let d = dmap(&m);
        assert_eq!(&d[..4], b"mlit");
        assert_eq!(u32::from_be_bytes(d[4..8].try_into().unwrap()) as usize, d.len() - 8);
        assert_eq!(&d[8..12], b"minm");
        assert_eq!(u32::from_be_bytes(d[12..16].try_into().unwrap()), 4);
        assert_eq!(&d[16..20], b"Song");
    }

    #[test]
    fn progress_anchors_to_capture_clock() {
        let m = Metadata { duration_ms: 200_000, position_ms: 10_000, ..Default::default() };
        let (s, c, e) = progress(1_000_000, &m);
        assert_eq!(c, 1_000_000);
        assert_eq!(s, 1_000_000 - 441_000);
        assert_eq!(e, s + 8_820_000);
    }
}

#[cfg(test)]
mod dedupe_tests {
    use super::*;

    #[test]
    fn repeated_updates_are_not_resent() {
        let mut sent = Sent::default();
        let mut m = Metadata { title: "A".into(), artist: "B".into(), album: "C".into(), artwork: Some(vec![1, 2, 3]), artwork_mime: "image/jpeg".into(), duration_ms: 200_000, position_ms: 1_000 };
        let first: Vec<String> = requests(1_000_000, &m, &mut sent).into_iter().map(|r| r.0).collect();
        assert_eq!(first, ["text/parameters", "application/x-dmap-tagged", "image/jpeg"]);
        // same track, position advanced in step with the clock → nothing
        m.position_ms = 2_000;
        assert!(requests(1_000_000 + 44_100, &m, &mut sent).is_empty());
        // seek → progress only
        m.position_ms = 60_000;
        let seek: Vec<String> = requests(1_000_000 + 88_200, &m, &mut sent).into_iter().map(|r| r.0).collect();
        assert_eq!(seek, ["text/parameters"]);
        // new track → everything
        m.title = "Next".into();
        assert_eq!(requests(1_100_000, &m, &mut sent).len(), 3);
    }
}
