//! 后台发送器的回环 HTTP 协议验证，不接触真实手机、词条或配对设置。

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::mpsc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use qingjian_windows_server::speech::{SpeechSender, SpeechSink};

#[test]
fn sender_posts_utf8_to_loopback_with_pairing_header() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let path = std::env::temp_dir().join(format!(
        "qingjian-speech-test-{}-{}.toml",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::write(
        &path,
        format!("port = {port}\ntoken = \"{}\"\n", "a".repeat(32)),
    )
    .unwrap();
    let (tx, rx) = mpsc::channel();
    let peer = std::thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(3);
        let (stream, _) = loop {
            match listener.accept() {
                Ok(peer) => break peer,
                Err(error)
                    if error.kind() == std::io::ErrorKind::WouldBlock
                        && Instant::now() < deadline =>
                {
                    std::thread::sleep(Duration::from_millis(10));
                }
                other => panic!("接收朗读连接失败：{other:?}"),
            }
        };
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut reader = BufReader::new(stream);
        let mut headers = String::new();
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).unwrap();
            if line == "\r\n" || line.is_empty() {
                break;
            }
            headers.push_str(&line);
            assert!(headers.len() < 4096);
        }
        let length = headers
            .lines()
            .find_map(|line| line.strip_prefix("Content-Length: "))
            .unwrap()
            .parse::<usize>()
            .unwrap();
        let mut body = vec![0; length];
        reader.read_exact(&mut body).unwrap();
        reader
            .get_mut()
            .write_all(b"HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n")
            .unwrap();
        tx.send((headers, String::from_utf8(body).unwrap()))
            .unwrap();
    });
    let sender = SpeechSender::new(path.clone()).unwrap();
    assert!(!sender.submit(" ".to_owned()));
    assert!(!sender.submit("x".repeat(501)));
    assert!(sender.submit("I, me".to_owned()));
    let received = rx.recv_timeout(Duration::from_secs(4));
    std::fs::remove_file(path).unwrap();
    let (headers, body) = received.unwrap();
    assert!(headers.starts_with("POST /api/speech HTTP/1.1\r\n"));
    assert!(headers.contains(&format!("Authorization: Bearer {}\r\n", "a".repeat(32))));
    assert!(headers.contains("Content-Type: text/plain; charset=utf-8\r\n"));
    assert_eq!(body, "I, me");
    peer.join().unwrap();
    let deadline = Instant::now() + Duration::from_secs(2);
    let result = loop {
        if let Some(result) = sender.take_result() {
            break result;
        }
        assert!(Instant::now() < deadline, "没有发送结果");
        std::thread::sleep(Duration::from_millis(10));
    };
    assert!(result);
}

#[test]
fn sender_reports_failed_connection_to_the_ui() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let path = std::env::temp_dir().join(format!(
        "qingjian-speech-failure-{}.toml",
        std::process::id()
    ));
    std::fs::write(
        &path,
        format!("port = {port}\ntoken = \"{}\"\n", "b".repeat(32)),
    )
    .unwrap();
    let sender = SpeechSender::new(path.clone()).unwrap();
    assert!(sender.submit("hello".to_owned()));
    let deadline = Instant::now() + Duration::from_secs(3);
    let result = loop {
        if let Some(result) = sender.take_result() {
            break result;
        }
        assert!(Instant::now() < deadline, "没有发送失败结果");
        std::thread::sleep(Duration::from_millis(10));
    };
    std::fs::remove_file(path).unwrap();
    assert!(!result);
}
