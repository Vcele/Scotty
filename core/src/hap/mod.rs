//! HomeKit-style pairing and encryption used by AirPlay 2.
//!
//! * [`tlv`]     – TLV8 encoding
//! * [`srp`]     – SRP-6a client (RFC 5054 math, Apple's M1/M2 proofs)
//! * [`pairing`] – transient pair-setup, PIN pair-setup (M1–M6) and pair-verify
//! * [`channel`] – the ChaCha20-Poly1305 framed transport used after pairing

pub mod channel;
pub mod pairing;
pub mod srp;
pub mod tlv;

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use sha2::Sha512;

/// HKDF-SHA512 → 32 bytes.
pub fn hkdf32(ikm: &[u8], salt: &str, info: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    Hkdf::<Sha512>::new(Some(salt.as_bytes()), ikm)
        .expand(info.as_bytes(), &mut out)
        .expect("32 bytes is a valid HKDF length");
    out
}

/// 12-byte nonce: 4 zero bytes followed by an 8-byte label or counter.
pub fn nonce_from(label: &[u8]) -> [u8; 12] {
    let mut n = [0u8; 12];
    let l = label.len().min(8);
    n[12 - l..].copy_from_slice(&label[..l]);
    n
}

pub fn seal(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], plain: &[u8]) -> Vec<u8> {
    ChaCha20Poly1305::new(Key::from_slice(key))
        .encrypt(Nonce::from_slice(nonce), Payload { msg: plain, aad })
        .expect("chacha20poly1305 encryption cannot fail")
}

pub fn open(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], sealed: &[u8]) -> Option<Vec<u8>> {
    ChaCha20Poly1305::new(Key::from_slice(key))
        .decrypt(Nonce::from_slice(nonce), Payload { msg: sealed, aad })
        .ok()
}

pub fn random<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    getrandom::getrandom(&mut b).expect("system RNG");
    b
}
