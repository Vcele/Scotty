//! Socket helpers: large buffers and Wi-Fi QoS marking.

use socket2::{Domain, Protocol, Socket, Type};
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Duration;

/// IP TOS values; Wi-Fi (WMM) maps the top 3 bits to an access category.
pub const TOS_VOICE: u32 = 0xC0; // CS6 → AC_VO, lowest latency queue
pub const TOS_VIDEO: u32 = 0xA0; // CS5 → AC_VI

/// UDP socket bound to an ephemeral port with big buffers and a QoS mark.
pub fn udp(tos: u32, read_timeout: Option<Duration>) -> io::Result<UdpSocket> {
    let s = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    let _ = s.set_send_buffer_size(2 * 1024 * 1024);
    let _ = s.set_recv_buffer_size(512 * 1024);
    let _ = s.set_tos(tos);
    s.bind(&SocketAddr::from(([0, 0, 0, 0], 0)).into())?;
    let u: UdpSocket = s.into();
    u.set_read_timeout(read_timeout)?;
    Ok(u)
}

/// TCP keep-alive + no-delay for control connections so dead peers are noticed.
pub fn tune_tcp(s: &std::net::TcpStream) {
    let _ = s.set_nodelay(true);
    let sock = socket2::SockRef::from(s);
    let ka = socket2::TcpKeepalive::new().with_time(Duration::from_secs(10)).with_interval(Duration::from_secs(5));
    let _ = sock.set_tcp_keepalive(&ka);
    let _ = sock.set_tos(TOS_VOICE);
}

/// Connects with a generous timeout and one retry: receivers in network standby (AV receivers,
/// speakers) often need a few seconds to wake up, and a just-aborted session may still be closing.
pub fn connect_retry(addr: &SocketAddr) -> std::io::Result<std::net::TcpStream> {
    let mut last = None;
    for attempt in 0..2 {
        match std::net::TcpStream::connect_timeout(addr, Duration::from_secs(10)) {
            Ok(s) => return Ok(s),
            Err(e) => {
                log::warn!("connect {addr} attempt {} failed: {e}", attempt + 1);
                last = Some(e);
                std::thread::sleep(Duration::from_millis(1500));
            }
        }
    }
    Err(last.unwrap())
}
