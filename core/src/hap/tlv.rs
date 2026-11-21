//! TLV8 as used by HomeKit pairing: values > 255 bytes are split into consecutive items.

pub const METHOD: u8 = 0x00;
pub const IDENTIFIER: u8 = 0x01;
pub const SALT: u8 = 0x02;
pub const PUBLIC_KEY: u8 = 0x03;
pub const PROOF: u8 = 0x04;
pub const ENCRYPTED_DATA: u8 = 0x05;
pub const STATE: u8 = 0x06;
pub const ERROR: u8 = 0x07;
pub const BACK_OFF: u8 = 0x08;
pub const SIGNATURE: u8 = 0x0A;
pub const FLAGS: u8 = 0x13;

pub fn encode(items: &[(u8, &[u8])]) -> Vec<u8> {
    let mut out = Vec::new();
    for &(ty, value) in items {
        if value.is_empty() {
            out.extend_from_slice(&[ty, 0]);
            continue;
        }
        for chunk in value.chunks(255) {
            out.push(ty);
            out.push(chunk.len() as u8);
            out.extend_from_slice(chunk);
        }
    }
    out
}

pub fn decode(data: &[u8]) -> Vec<(u8, Vec<u8>)> {
    let mut out: Vec<(u8, Vec<u8>)> = Vec::new();
    let mut i = 0;
    let mut prev_full = false; // previous fragment was 255 bytes → may continue
    while i + 2 <= data.len() {
        let ty = data[i];
        let len = data[i + 1] as usize;
        let end = (i + 2 + len).min(data.len());
        let value = &data[i + 2..end];
        match out.last_mut() {
            Some((last_ty, v)) if *last_ty == ty && prev_full => v.extend_from_slice(value),
            _ => out.push((ty, value.to_vec())),
        }
        prev_full = len == 255;
        i = end;
    }
    out
}

pub fn get(items: &[(u8, Vec<u8>)], ty: u8) -> Option<&[u8]> {
    items.iter().find(|(t, _)| *t == ty).map(|(_, v)| v.as_slice())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_values_round_trip() {
        let key = vec![7u8; 384];
        let enc = encode(&[(STATE, &[3]), (PUBLIC_KEY, &key), (PROOF, &[1, 2, 3])]);
        assert_eq!(enc.len(), 3 + (2 + 255) + (2 + 129) + 5);
        let dec = decode(&enc);
        assert_eq!(get(&dec, STATE), Some(&[3u8][..]));
        assert_eq!(get(&dec, PUBLIC_KEY).unwrap(), &key[..]);
        assert_eq!(get(&dec, PROOF), Some(&[1u8, 2, 3][..]));
    }
}
