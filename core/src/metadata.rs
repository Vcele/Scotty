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

/// The requests to send: (content type, body, rtptime anchor).
pub fn requests(current_rtp: u32, m: &Metadata, include_artwork: bool) -> Vec<(String, Vec<u8>, u32)> {
    let (start, cur, end) = progress(current_rtp, m);
    let mut v = vec![("application/x-dmap-tagged".to_string(), dmap(m), start)];
    if include_artwork {
        if let Some(a) = &m.artwork {
            let mime = if m.artwork_mime.is_empty() { "image/jpeg".into() } else { m.artwork_mime.clone() };
            v.push((mime, a.clone(), start));
        }
    }
    v.push(("text/parameters".into(), format!("progress: {start}/{cur}/{end}\r\n").into_bytes(), start));
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
