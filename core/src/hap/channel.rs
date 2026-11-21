//! HAP-style encrypted framing: each frame is `len (u16 LE) | ChaCha20-Poly1305(payload) | tag`,
//! with the length as AAD and a per-direction 64-bit little-endian counter as nonce.

use super::{nonce_from, open, seal};
use std::io::{self, Read, Write};
use std::net::TcpStream;

const MAX_FRAME: usize = 1024;

pub struct Keys {
    pub write: [u8; 32],
    pub read: [u8; 32],
}

/// Encrypts outgoing data.
pub struct SealWriter<W: Write> {
    inner: W,
    key: [u8; 32],
    counter: u64,
}

impl<W: Write> SealWriter<W> {
    pub fn new(inner: W, key: [u8; 32]) -> Self {
        Self { inner, key, counter: 0 }
    }
    pub fn send(&mut self, data: &[u8]) -> io::Result<()> {
        let mut out = Vec::with_capacity(data.len() + (data.len() / MAX_FRAME + 1) * 18);
        for chunk in data.chunks(MAX_FRAME) {
            let len = (chunk.len() as u16).to_le_bytes();
            let sealed = seal(&self.key, &nonce_from(&self.counter.to_le_bytes()), &len, chunk);
            self.counter += 1;
            out.extend_from_slice(&len);
            out.extend_from_slice(&sealed);
        }
        self.inner.write_all(&out)?;
        self.inner.flush()
    }
}

/// Decrypts incoming data; implements [`Read`] so RTSP parsing works unchanged.
pub struct OpenReader<R: Read> {
    inner: R,
    key: [u8; 32],
    counter: u64,
    buf: Vec<u8>,
    pos: usize,
}

impl<R: Read> OpenReader<R> {
    pub fn new(inner: R, key: [u8; 32]) -> Self {
        Self { inner, key, counter: 0, buf: Vec::new(), pos: 0 }
    }
    fn fill(&mut self) -> io::Result<bool> {
        let mut len = [0u8; 2];
        match self.inner.read_exact(&mut len) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(false),
            Err(e) => return Err(e),
        }
        let n = u16::from_le_bytes(len) as usize;
        let mut frame = vec![0u8; n + 16];
        self.inner.read_exact(&mut frame)?;
        let plain = open(&self.key, &nonce_from(&self.counter.to_le_bytes()), &len, &frame)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "decryption failed"))?;
        self.counter += 1;
        self.buf = plain;
        self.pos = 0;
        Ok(true)
    }
}

impl<R: Read> Read for OpenReader<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        while self.pos >= self.buf.len() {
            if !self.fill()? {
                return Ok(0);
            }
        }
        let n = out.len().min(self.buf.len() - self.pos);
        out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

/// Splits a connected socket into an encrypting writer and decrypting reader.
pub fn secure(stream: &TcpStream, keys: Keys) -> io::Result<(SealWriter<TcpStream>, OpenReader<TcpStream>)> {
    Ok((SealWriter::new(stream.try_clone()?, keys.write), OpenReader::new(stream.try_clone()?, keys.read)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_round_trip_across_boundaries() {
        let key = [9u8; 32];
        let mut wire = Vec::new();
        let msg: Vec<u8> = (0..3000u32).map(|i| i as u8).collect();
        {
            let mut w = SealWriter::new(&mut wire, key);
            w.send(&msg).unwrap();
            w.send(b"second").unwrap();
        }
        assert_eq!(wire.len(), 3000 + 6 + 4 * 18);
        let mut r = OpenReader::new(&wire[..], key);
        let mut got = Vec::new();
        r.read_to_end(&mut got).unwrap();
        assert_eq!(&got[..3000], &msg[..]);
        assert_eq!(&got[3000..], b"second");
        // wrong key fails
        let mut bad = OpenReader::new(&wire[..], [1u8; 32]);
        assert!(bad.read(&mut [0u8; 8]).is_err());
    }
}
