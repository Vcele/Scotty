use crate::events::Events;

/// A running cast session. Media is pushed in by the platform layer.
pub trait Session: Send + Sync {
    /// H.264 Annex-B access unit (SPS/PPS in front of key frames).
    fn push_video(&self, _data: &[u8], _pts_us: i64, _key: bool) {}
    /// ADTS AAC frame (Miracast / AirPlay video) or 16-bit LE stereo PCM (AirPlay audio).
    fn push_audio(&self, _data: &[u8], _pts_us: i64) {}
    /// 0.0 – 1.0
    fn set_volume(&self, _volume: f32) {}
    fn stop(&self);
    fn events(&self) -> &Events;
}
