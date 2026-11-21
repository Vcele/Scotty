//! AirPlay 2 pairing flows.
//!
//! * **Transient** pair-setup (HomePod & most AirPlay 2 speakers): SRP with the fixed PIN 3939,
//!   session keys come straight from the SRP key. Nothing to store.
//! * **PIN** pair-setup (Apple TV showing a code): SRP M1–M4 with the on-screen PIN, then M5/M6
//!   exchange Ed25519 long-term keys which we keep as [`Credentials`].
//! * **Pair-verify**: X25519 + signatures with stored credentials → session keys.

use super::channel::Keys;
use super::srp::{self, Group};
use super::tlv::{self, ENCRYPTED_DATA, ERROR, FLAGS, IDENTIFIER, METHOD, PROOF, PUBLIC_KEY, SALT, SIGNATURE, STATE};
use super::{hkdf32, nonce_from, open, random, seal};
use ed25519_dalek::{Signer, SigningKey, Verifier, VerifyingKey};
use sha2::Sha512;
use std::fmt;
use x25519_dalek::{PublicKey, StaticSecret};

/// HTTP-ish POST used for pairing: `(path, body, X-Apple-HKP) → (status, body)`.
pub type Post<'a> = dyn FnMut(&str, &[u8], u8) -> std::io::Result<(u16, Vec<u8>)> + 'a;

#[derive(Debug)]
pub enum PairError {
    /// The receiver wants something we don't have (PIN pairing, a correct PIN, …).
    Auth(String),
    Io(std::io::Error),
}

impl fmt::Display for PairError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PairError::Auth(s) => write!(f, "{s}"),
            PairError::Io(e) => write!(f, "{e}"),
        }
    }
}

impl From<std::io::Error> for PairError {
    fn from(e: std::io::Error) -> Self {
        PairError::Io(e)
    }
}

impl From<PairError> for std::io::Error {
    fn from(e: PairError) -> Self {
        match e {
            PairError::Io(e) => e,
            PairError::Auth(s) => std::io::Error::new(std::io::ErrorKind::PermissionDenied, s),
        }
    }
}

/// Long-term pairing with one receiver (from PIN pairing).
#[derive(Clone, Debug, PartialEq)]
pub struct Credentials {
    pub pairing_id: String,
    pub seed: [u8; 32],
    pub device_id: String,
    pub device_ltpk: [u8; 32],
}

impl Credentials {
    pub fn encode(&self) -> String {
        format!("{}:{}:{}:{}", self.pairing_id, hex(&self.seed), hex(&self.device_ltpk), self.device_id)
    }
    pub fn decode(s: &str) -> Option<Self> {
        let mut p = s.splitn(4, ':');
        let pairing_id = p.next()?.to_string();
        let seed = unhex32(p.next()?)?;
        let device_ltpk = unhex32(p.next()?)?;
        let device_id = p.next()?.to_string();
        Some(Self { pairing_id, seed, device_id, device_ltpk })
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex32(s: &str) -> Option<[u8; 32]> {
    if s.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}

pub fn uuid() -> String {
    let r: [u8; 16] = random();
    format!(
        "{}-{}-4{}-{}-{}",
        hex(&r[0..4]).to_uppercase(),
        hex(&r[4..6]).to_uppercase(),
        &hex(&r[6..8]).to_uppercase()[1..],
        hex(&r[8..10]).to_uppercase(),
        hex(&r[10..16]).to_uppercase()
    )
}

pub fn control_keys(secret: &[u8]) -> Keys {
    Keys {
        write: hkdf32(secret, "Control-Salt", "Control-Write-Encryption-Key"),
        read: hkdf32(secret, "Control-Salt", "Control-Read-Encryption-Key"),
    }
}

/// The event channel is opened by us but driven by the receiver, so directions are swapped.
pub fn event_keys(secret: &[u8]) -> Keys {
    Keys {
        write: hkdf32(secret, "Events-Salt", "Events-Read-Encryption-Key"),
        read: hkdf32(secret, "Events-Salt", "Events-Write-Encryption-Key"),
    }
}

fn check(status: u16, body: &[u8], step: &str) -> Result<Vec<(u8, Vec<u8>)>, PairError> {
    if status != 200 {
        return Err(PairError::Auth(format!("{step} refused ({status})")));
    }
    let items = tlv::decode(body);
    if let Some(e) = tlv::get(&items, ERROR) {
        let code = e.first().copied().unwrap_or(0);
        return Err(PairError::Auth(match code {
            2 => "Wrong PIN".into(),
            3 => "Too many attempts – wait a moment and try again".into(),
            6 => "Receiver is busy pairing with someone else".into(),
            c => format!("{step} failed (error {c})"),
        }));
    }
    Ok(items)
}

struct SrpOutcome {
    key: Vec<u8>,
}

fn srp_exchange(post: &mut Post, pin: &str, transient: bool) -> Result<SrpOutcome, PairError> {
    let hkp = if transient { 4 } else { 3 };
    let m1 = if transient {
        tlv::encode(&[(METHOD, &[0]), (STATE, &[1]), (FLAGS, &[0x10])])
    } else {
        tlv::encode(&[(METHOD, &[0]), (STATE, &[1])])
    };
    let (st, body) = post("/pair-setup", &m1, hkp)?;
    let m2 = check(st, &body, "Pair-setup")?;
    let salt = tlv::get(&m2, SALT).ok_or_else(|| PairError::Auth("No salt from receiver".into()))?;
    let b_pub = tlv::get(&m2, PUBLIC_KEY).ok_or_else(|| PairError::Auth("No public key from receiver".into()))?;

    let a_priv: [u8; 32] = random();
    let run = srp::client::<Sha512>(&Group::rfc5054_3072(), b"Pair-Setup", pin.as_bytes(), salt, b_pub, &a_priv)
        .map_err(|e| PairError::Auth(e.into()))?;
    let m3 = tlv::encode(&[(STATE, &[3]), (PUBLIC_KEY, &run.a_pub.to_bytes_be()), (PROOF, &run.m1)]);
    let (st, body) = post("/pair-setup", &m3, hkp)?;
    let m4 = check(st, &body, "Pair-setup proof")?;
    match tlv::get(&m4, PROOF) {
        Some(p) if p == run.m2.as_slice() => {}
        Some(_) => return Err(PairError::Auth("Receiver proof mismatch".into())),
        None => return Err(PairError::Auth("Receiver sent no proof".into())),
    }
    Ok(SrpOutcome { key: run.key })
}

/// Transient pairing → shared secret for [`control_keys`]. Retries once: SRP has a tiny chance of
/// disagreeing on padding of the shared value, and a fresh run fixes that.
pub fn transient(post: &mut Post) -> Result<Vec<u8>, PairError> {
    match srp_exchange(post, "3939", true) {
        Ok(o) => Ok(o.key),
        Err(PairError::Auth(e)) if e.contains("mismatch") => Ok(srp_exchange(post, "3939", true)?.key),
        Err(e) => Err(e),
    }
}

/// Asks the receiver to show a PIN on screen.
pub fn pin_start(post: &mut Post) -> Result<(), PairError> {
    let (st, _) = post("/pair-pin-start", &[], 3)?;
    if st != 200 {
        return Err(PairError::Auth(format!("Receiver can't show a PIN ({st})")));
    }
    Ok(())
}

/// Full PIN pair-setup (M1–M6) → long-term credentials.
pub fn pin_setup(post: &mut Post, pin: &str) -> Result<Credentials, PairError> {
    let srp = srp_exchange(post, pin, false)?;
    let seed: [u8; 32] = random();
    let signing = SigningKey::from_bytes(&seed);
    let ltpk = signing.verifying_key().to_bytes();
    let pairing_id = uuid();

    let device_x = hkdf32(&srp.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info");
    let mut info = device_x.to_vec();
    info.extend_from_slice(pairing_id.as_bytes());
    info.extend_from_slice(&ltpk);
    let sig = signing.sign(&info).to_bytes();
    let sub = tlv::encode(&[(IDENTIFIER, pairing_id.as_bytes()), (PUBLIC_KEY, &ltpk), (SIGNATURE, &sig)]);
    let enc_key = hkdf32(&srp.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info");
    let sealed = seal(&enc_key, &nonce_from(b"PS-Msg05"), &[], &sub);
    let m5 = tlv::encode(&[(STATE, &[5]), (ENCRYPTED_DATA, &sealed)]);
    let (st, body) = post("/pair-setup", &m5, 3)?;
    let m6 = check(st, &body, "Pair-setup key exchange")?;
    let enc = tlv::get(&m6, ENCRYPTED_DATA).ok_or_else(|| PairError::Auth("No device info".into()))?;
    let plain = open(&enc_key, &nonce_from(b"PS-Msg06"), &[], enc)
        .ok_or_else(|| PairError::Auth("Couldn't decrypt device info".into()))?;
    let sub = tlv::decode(&plain);
    let device_id = String::from_utf8_lossy(tlv::get(&sub, IDENTIFIER).unwrap_or_default()).into_owned();
    let device_ltpk: [u8; 32] = tlv::get(&sub, PUBLIC_KEY)
        .and_then(|k| k.try_into().ok())
        .ok_or_else(|| PairError::Auth("No device key".into()))?;
    Ok(Credentials { pairing_id, seed, device_id, device_ltpk })
}

/// Pair-verify with stored credentials → shared secret for [`control_keys`].
pub fn verify(post: &mut Post, creds: &Credentials) -> Result<Vec<u8>, PairError> {
    let secret = StaticSecret::from(random::<32>());
    let public = PublicKey::from(&secret);
    let m1 = tlv::encode(&[(STATE, &[1]), (PUBLIC_KEY, public.as_bytes())]);
    let (st, body) = post("/pair-verify", &m1, 3)?;
    let m2 = check(st, &body, "Pair-verify")?;
    let acc_pub: [u8; 32] = tlv::get(&m2, PUBLIC_KEY)
        .and_then(|k| k.try_into().ok())
        .ok_or_else(|| PairError::Auth("No verify key".into()))?;
    let shared = secret.diffie_hellman(&PublicKey::from(acc_pub));
    let session = hkdf32(shared.as_bytes(), "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info");

    let enc = tlv::get(&m2, ENCRYPTED_DATA).ok_or_else(|| PairError::Auth("No verify data".into()))?;
    let plain = open(&session, &nonce_from(b"PV-Msg02"), &[], enc)
        .ok_or_else(|| PairError::Auth("Pairing is no longer valid – pair again".into()))?;
    let sub = tlv::decode(&plain);
    if let (Some(id), Some(sig)) = (tlv::get(&sub, IDENTIFIER), tlv::get(&sub, SIGNATURE)) {
        let mut info = acc_pub.to_vec();
        info.extend_from_slice(id);
        info.extend_from_slice(public.as_bytes());
        let ok = VerifyingKey::from_bytes(&creds.device_ltpk)
            .ok()
            .zip(ed25519_dalek::Signature::from_slice(sig).ok())
            .is_some_and(|(k, s)| k.verify(&info, &s).is_ok());
        if !ok {
            return Err(PairError::Auth("Receiver identity changed – pair again".into()));
        }
    }

    let signing = SigningKey::from_bytes(&creds.seed);
    let mut info = public.as_bytes().to_vec();
    info.extend_from_slice(creds.pairing_id.as_bytes());
    info.extend_from_slice(&acc_pub);
    let sig = signing.sign(&info).to_bytes();
    let sub = tlv::encode(&[(IDENTIFIER, creds.pairing_id.as_bytes()), (SIGNATURE, &sig)]);
    let sealed = seal(&session, &nonce_from(b"PV-Msg03"), &[], &sub);
    let m3 = tlv::encode(&[(STATE, &[3]), (ENCRYPTED_DATA, &sealed)]);
    let (st, body) = post("/pair-verify", &m3, 3)?;
    check(st, &body, "Pair-verify")?;
    Ok(shared.as_bytes().to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credentials_round_trip() {
        let c = Credentials { pairing_id: uuid(), seed: random(), device_id: "AA:BB:CC".into(), device_ltpk: random() };
        assert_eq!(Credentials::decode(&c.encode()), Some(c));
    }

    #[test]
    fn uuid_shape() {
        let u = uuid();
        assert_eq!(u.len(), 36);
        assert_eq!(u.as_bytes()[14], b'4');
    }
}
