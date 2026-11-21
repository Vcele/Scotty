//! RTSP/HTTP message parsing and a bidirectional RTSP connection.

use std::collections::HashMap;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{IpAddr, Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::mpsc::{sync_channel, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Message {
    pub start: String,
    headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Message {
    /// Reads one RTSP/HTTP message. `Ok(None)` on clean EOF.
    pub fn read(r: &mut impl BufRead) -> io::Result<Option<Message>> {
        let mut start = String::new();
        loop {
            start.clear();
            if r.read_line(&mut start)? == 0 {
                return Ok(None);
            }
            if !start.trim().is_empty() {
                break;
            }
        }
        let mut headers = Vec::new();
        loop {
            let mut line = String::new();
            if r.read_line(&mut line)? == 0 {
                return Ok(None);
            }
            let line = line.trim_end();
            if line.is_empty() {
                break;
            }
            if let Some((k, v)) = line.split_once(':') {
                headers.push((k.trim().to_string(), v.trim().to_string()));
            }
        }
        let mut m = Message { start: start.trim().to_string(), headers, body: Vec::new() };
        let len: usize = m.header("Content-Length").and_then(|v| v.parse().ok()).unwrap_or(0);
        m.body = vec![0; len];
        r.read_exact(&mut m.body)?;
        Ok(Some(m))
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
    pub fn is_response(&self) -> bool {
        self.start.starts_with("RTSP/") || self.start.starts_with("HTTP/")
    }
    pub fn status(&self) -> u16 {
        self.start.split(' ').nth(1).and_then(|s| s.parse().ok()).unwrap_or(0)
    }
    pub fn method(&self) -> &str {
        self.start.split(' ').next().unwrap_or("")
    }
    pub fn path(&self) -> &str {
        self.start.split(' ').nth(1).unwrap_or("/")
    }
    pub fn cseq(&self) -> i32 {
        self.header("CSeq").and_then(|v| v.parse().ok()).unwrap_or(-1)
    }
    pub fn body_text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
    /// `text/parameters` body → map.
    pub fn parameters(&self) -> HashMap<String, String> {
        self.body_text()
            .lines()
            .filter_map(|l| {
                let l = l.trim();
                if l.is_empty() {
                    return None;
                }
                Some(match l.split_once(':') {
                    Some((k, v)) => (k.trim().to_string(), v.trim().to_string()),
                    None => (l.to_string(), String::new()),
                })
            })
            .collect()
    }
}

pub fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        400 => "Bad Request",
        404 => "Not Found",
        454 => "Session Not Found",
        501 => "Not Implemented",
        _ => "Status",
    }
}

pub type RequestHandler = Box<dyn Fn(&Message, &Conn) + Send + Sync>;

/// RTSP connection: our requests are matched to responses by CSeq; peer requests go to a handler.
pub struct Conn {
    writer: Mutex<TcpStream>,
    pending: Mutex<HashMap<i32, SyncSender<Message>>>,
    cseq: AtomicI32,
    closed: AtomicBool,
    user_agent: Option<String>,
    local: IpAddr,
    peer: IpAddr,
}

impl Conn {
    pub fn start(
        stream: TcpStream,
        user_agent: Option<&str>,
        handler: Option<RequestHandler>,
        on_close: Option<Box<dyn FnOnce() + Send>>,
    ) -> io::Result<Arc<Conn>> {
        stream.set_nodelay(true)?;
        let local = stream.local_addr()?.ip();
        let peer = stream.peer_addr()?.ip();
        let reader = stream.try_clone()?;
        let conn = Arc::new(Conn {
            writer: Mutex::new(stream),
            pending: Mutex::new(HashMap::new()),
            cseq: AtomicI32::new(0),
            closed: AtomicBool::new(false),
            user_agent: user_agent.map(str::to_string),
            local,
            peer,
        });
        let c = conn.clone();
        thread::Builder::new().name("rtsp-reader".into()).spawn(move || {
            let mut r = BufReader::new(reader);
            loop {
                match Message::read(&mut r) {
                    Ok(Some(m)) => {
                        log::debug!("<< {}", m.start);
                        if m.is_response() {
                            if let Some(tx) = c.pending.lock().unwrap().remove(&m.cseq()) {
                                let _ = tx.send(m);
                            }
                        } else if let Some(h) = &handler {
                            h(&m, &c);
                        } else {
                            c.respond(&m, 501, &[], None);
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        if !c.is_closed() {
                            log::warn!("rtsp read: {e}");
                        }
                        break;
                    }
                }
            }
            let was_closed = c.closed.swap(true, Ordering::SeqCst);
            c.pending.lock().unwrap().clear(); // drops senders → waiting requests fail
            if !was_closed {
                if let Some(f) = on_close {
                    f();
                }
            }
        })?;
        Ok(conn)
    }

    pub fn local_ip(&self) -> IpAddr {
        self.local
    }
    pub fn peer_ip(&self) -> IpAddr {
        self.peer
    }
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    pub fn request(
        &self,
        method: &str,
        uri: &str,
        headers: &[(&str, String)],
        body: Option<(&str, &[u8])>,
        timeout: Duration,
    ) -> io::Result<Message> {
        if self.is_closed() {
            return Err(io::Error::new(io::ErrorKind::NotConnected, "connection closed"));
        }
        let id = self.cseq.fetch_add(1, Ordering::SeqCst) + 1;
        let (tx, rx) = sync_channel(1);
        self.pending.lock().unwrap().insert(id, tx);
        let mut head = format!("{method} {uri} RTSP/1.0\r\nCSeq: {id}\r\n");
        if let Some(ua) = &self.user_agent {
            head += &format!("User-Agent: {ua}\r\n");
        }
        for (k, v) in headers {
            head += &format!("{k}: {v}\r\n");
        }
        if let Some((ty, b)) = body {
            head += &format!("Content-Type: {ty}\r\nContent-Length: {}\r\n", b.len());
        }
        head += "\r\n";
        log::debug!(">> {method} {uri}");
        self.write(head.as_bytes(), body.map(|b| b.1))?;
        let r = rx.recv_timeout(timeout).map_err(|_| {
            self.pending.lock().unwrap().remove(&id);
            io::Error::new(io::ErrorKind::TimedOut, format!("no reply to {method}"))
        });
        if let Ok(m) = &r {
            log::info!("{method} {uri} → {}", m.status());
        } else {
            log::warn!("{method} {uri} → no reply");
        }
        r
    }

    pub fn respond(&self, req: &Message, status: u16, headers: &[(&str, String)], body: Option<&str>) {
        let mut head = format!("RTSP/1.0 {status} {}\r\nCSeq: {}\r\n", reason(status), req.cseq());
        for (k, v) in headers {
            head += &format!("{k}: {v}\r\n");
        }
        if let Some(b) = body {
            head += &format!("Content-Type: text/parameters\r\nContent-Length: {}\r\n", b.len());
        }
        head += "\r\n";
        if let Err(e) = self.write(head.as_bytes(), body.map(str::as_bytes)) {
            log::warn!("respond: {e}");
        }
    }

    fn write(&self, head: &[u8], body: Option<&[u8]>) -> io::Result<()> {
        let mut w = self.writer.lock().unwrap();
        w.write_all(head)?;
        if let Some(b) = body {
            w.write_all(b)?;
        }
        w.flush()
    }

    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        let _ = self.writer.lock().unwrap().shutdown(Shutdown::Both);
    }
}

/// Reads exactly one HTTP response from a persistent connection.
pub fn read_response(r: &mut impl Read) -> io::Result<Option<Message>> {
    let mut br = BufReader::with_capacity(1, r); // 1-byte buffer: never over-reads the socket
    Message::read(&mut br)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_message_with_body() {
        let raw = b"RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 32\r\n\r\nwfd_video_formats: 00 00 01 01\r\n";
        let mut r = BufReader::new(&raw[..]);
        let m = Message::read(&mut r).unwrap().unwrap();
        assert!(m.is_response());
        assert_eq!(m.status(), 200);
        assert_eq!(m.cseq(), 3);
        assert_eq!(m.parameters()["wfd_video_formats"], "00 00 01 01");
    }
}
