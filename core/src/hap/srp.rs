//! SRP-6a client as used by HomeKit / AirPlay 2 pair-setup (RFC 5054 group 3072, SHA-512,
//! username "Pair-Setup"). Generic over the hash so it can be checked against RFC 5054 vectors.

use num_bigint::BigUint;
use sha2::digest::Digest;

/// RFC 5054 3072-bit group, generator 5.
pub const N_3072_HEX: &str = concat!(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74",
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437",
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED",
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05",
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB",
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B",
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718",
    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33",
    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7",
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864",
    "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2",
    "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
);

pub struct Group {
    pub n: BigUint,
    pub g: BigUint,
}

impl Group {
    pub fn rfc5054_3072() -> Self {
        Self { n: BigUint::parse_bytes(N_3072_HEX.as_bytes(), 16).unwrap(), g: BigUint::from(5u8) }
    }
    fn len(&self) -> usize {
        self.n.bits().div_ceil(8) as usize
    }
    fn pad(&self, v: &BigUint) -> Vec<u8> {
        let b = v.to_bytes_be();
        let mut out = vec![0u8; self.len().saturating_sub(b.len())];
        out.extend_from_slice(&b);
        out
    }
}

fn h<D: Digest>(parts: &[&[u8]]) -> Vec<u8> {
    let mut d = D::new();
    for p in parts {
        d.update(p);
    }
    d.finalize().to_vec()
}

/// All values of one client run (exposed for tests).
pub struct ClientRun {
    pub k: BigUint,
    pub x: BigUint,
    pub a_pub: BigUint,
    pub u: BigUint,
    pub s: BigUint,
    /// Session key K = H(S)
    pub key: Vec<u8>,
    /// Client proof M1
    pub m1: Vec<u8>,
    /// Expected server proof M2
    pub m2: Vec<u8>,
}

/// Runs the client side given the server's salt and public key `B`.
pub fn client<D: Digest>(
    group: &Group,
    username: &[u8],
    password: &[u8],
    salt: &[u8],
    b_pub: &[u8],
    a_priv: &[u8],
) -> Result<ClientRun, &'static str> {
    let n = &group.n;
    let g = &group.g;
    let b = BigUint::from_bytes_be(b_pub);
    if (&b % n) == BigUint::ZERO {
        return Err("invalid server public key");
    }
    let a = BigUint::from_bytes_be(a_priv);
    let k = BigUint::from_bytes_be(&h::<D>(&[&n.to_bytes_be(), &group.pad(g)]));
    let inner = h::<D>(&[username, b":", password]);
    let x = BigUint::from_bytes_be(&h::<D>(&[salt, &inner]));
    let a_pub = g.modpow(&a, n);
    let u = BigUint::from_bytes_be(&h::<D>(&[&group.pad(&a_pub), &group.pad(&b)]));
    if u == BigUint::ZERO {
        return Err("invalid scrambling parameter");
    }
    // S = (B - k·g^x)^(a + u·x) mod N
    let kgx = (&k * g.modpow(&x, n)) % n;
    let base = ((&b + n) - kgx) % n;
    let s = base.modpow(&(&a + &u * &x), n);
    let key = h::<D>(&[&s.to_bytes_be()]);

    // M1 = H(H(N) xor H(g) | H(I) | s | A | B | K)
    let hn = h::<D>(&[&n.to_bytes_be()]);
    let hg = h::<D>(&[&g.to_bytes_be()]);
    let hx: Vec<u8> = hn.iter().zip(&hg).map(|(a, b)| a ^ b).collect();
    let hi = h::<D>(&[username]);
    let a_bytes = a_pub.to_bytes_be();
    let m1 = h::<D>(&[&hx, &hi, salt, &a_bytes, &b.to_bytes_be(), &key]);
    let m2 = h::<D>(&[&a_bytes, &m1, &key]);
    Ok(ClientRun { k, x, a_pub, u, s, key, m1, m2 })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn n(hex: &str) -> BigUint {
        BigUint::parse_bytes(hex.as_bytes(), 16).unwrap()
    }

    /// RFC 5054 Appendix B test vector (1024-bit group, SHA-1).
    #[test]
    fn rfc5054_vector() {
        let group = Group {
            n: n(concat!(
                "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C9C256576",
                "D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE48E495C1D6089DAD1",
                "5DC7D7B46154D6B6CE8EF4AD69B15D4982559B297BCF1885C529F566660E57EC",
                "68EDBC3C05726CC02FD4CBF4976EAA9AFD5138FE8376435B9FC61D2FC0EB06E3"
            )),
            g: BigUint::from(2u8),
        };
        let salt = n("BEB25379D1A8581EB5A727673A2441EE").to_bytes_be();
        let a = n("60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393").to_bytes_be();
        let b_pub = n(concat!(
            "BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011BAF38964",
            "DC46A0670DD125B95A981652236F99D9B681CBF87837EC996C6DA04453728610",
            "D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA37089E6F9C6059F388838E7A",
            "00030B331EB76840910440B1B27AAEAEEB4012B7D7665238A8E3FB004B117B58"
        ))
        .to_bytes_be();
        let r = client::<sha1::Sha1>(&group, b"alice", b"password123", &salt, &b_pub, &a).unwrap();
        assert_eq!(r.k, n("7556AA045AEF2CDD07ABAF0F665C3E818913186F"));
        assert_eq!(r.x, n("94B7555AABE9127CC58CCF4993DB6CF84D16C124"));
        assert_eq!(r.u, n("CE38B9593487DA98554ED47D70A7AE5F462EF019"));
        assert_eq!(
            r.a_pub,
            n(concat!(
                "61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC44352E890",
                "3211C04692272D8B2D1A5358A2CF1B6E0BFCF99F921530EC8E39356179EAE45E",
                "42BA92AEACED825171E1E8B9AF6D9C03E1327F44BE087EF06530E69F66615261",
                "EEF54073CA11CF5858F0EDFDFE15EFEAB349EF5D76988A3672FAC47B0769447B"
            ))
        );
        assert_eq!(
            r.s,
            n(concat!(
                "B0DC82BABCF30674AE450C0287745E7990A3381F63B387AAF271A10D233861E3",
                "59B48220F7C4693C9AE12B0A6F67809F0876E2D013800D6C41BB59B6D5979B5C",
                "00A172B4A2A5903A0BDCAF8A709585EB2AFAFA8F3499B200210DCC1F10EB3394",
                "3CD67FC88A2F39A4BE5BEC4EC0A3212DC346D7E474B29EDE8A469FFECA686E5A"
            ))
        );
    }
}
