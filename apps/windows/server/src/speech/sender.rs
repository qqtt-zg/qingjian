//! 有界后台队列把英文送给本机 HTTP 接收工具；连续点击只发送仍在等待的最新一条。

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::connection::Connection;
use super::queue::SpeechQueue;
use super::sink::SpeechSink;

pub struct SpeechSender {
    queue: Arc<SpeechQueue>,

    connection_path: PathBuf,

    sequence: Arc<AtomicU64>,

    result: Arc<Mutex<Option<bool>>>,
}

impl SpeechSender {
    pub fn new(connection_path: PathBuf) -> io::Result<Self> {
        let queue = Arc::new(SpeechQueue::default());
        let worker = queue.clone();
        let path = connection_path.clone();
        let sequence = Arc::new(AtomicU64::new(0));
        let current = sequence.clone();
        let result = Arc::new(Mutex::new(None));
        let outcome = result.clone();
        std::thread::Builder::new()
            .name("qingjian-speech".to_owned())
            .spawn(move || {
                while let Some((id, text)) = worker.take() {
                    let sent = send(&path, &text);
                    if let Err(error) = &sent {
                        tracing::warn!(%error, "手机朗读发送失败，请检查接收工具与手机页面");
                    }
                    if let Ok(mut outcome) = outcome.lock()
                        && id == current.load(Ordering::Acquire)
                    {
                        *outcome = Some(sent.is_ok());
                    }
                }
            })?;
        Ok(Self {
            queue,
            connection_path,
            sequence,
            result,
        })
    }
}

impl SpeechSink for SpeechSender {
    fn submit(&self, text: String) -> bool {
        if text.trim().is_empty() || text.chars().count() > 500 || !self.connection_path.is_file() {
            return false;
        }
        let id = self.sequence.fetch_add(1, Ordering::AcqRel) + 1;
        let Ok(mut result) = self.result.lock() else {
            return false;
        };
        *result = None;
        self.queue.replace((id, text))
    }

    fn take_result(&self) -> Option<bool> {
        self.result.lock().ok()?.take()
    }
}

impl Drop for SpeechSender {
    fn drop(&mut self) {
        self.queue.close();
    }
}

fn send(path: &std::path::Path, text: &str) -> io::Result<()> {
    let connection = Connection::load(path)?;
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, connection.port));
    let timeout = Duration::from_secs(1);
    let mut stream = TcpStream::connect_timeout(&address, timeout)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    let body = text.as_bytes();
    let header = format!(
        "POST /api/speech HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nAuthorization: Bearer {}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        connection.port,
        connection.token,
        body.len()
    );
    stream.write_all(header.as_bytes())?;
    stream.write_all(body)?;
    let mut status = String::new();
    BufReader::new(stream).take(1024).read_line(&mut status)?;
    if !status.starts_with("HTTP/1.0 202 ") && !status.starts_with("HTTP/1.1 202 ") {
        return Err(io::Error::other("speech receiver rejected the request"));
    }
    Ok(())
}
