//! 读取本机接收工具写出的连接信息；目的地址固定回环，只接受端口和随机配对令牌。

use std::io::{self, Read};
use std::path::Path;

pub(super) struct Connection {
    pub(super) port: u16,

    pub(super) token: String,
}

impl Connection {
    pub(super) fn load(path: &Path) -> io::Result<Self> {
        let mut data = String::new();
        std::fs::File::open(path)?
            .take(4097)
            .read_to_string(&mut data)?;
        if data.len() > 4096 {
            return Err(io::Error::other("speech connection file is too large"));
        }
        let document = data
            .parse::<toml_edit::DocumentMut>()
            .map_err(io::Error::other)?;
        let port = document
            .get("port")
            .and_then(|value| value.as_integer())
            .and_then(|port| u16::try_from(port).ok())
            .ok_or_else(|| io::Error::other("invalid speech receiver port"))?;
        let token = document
            .get("token")
            .and_then(|value| value.as_str())
            .ok_or_else(|| io::Error::other("missing speech pairing token"))?
            .to_owned();
        let connection = Self { port, token };
        if connection.port == 0
            || connection.token.len() != 32
            || !connection
                .token
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit())
        {
            return Err(io::Error::other("invalid speech connection settings"));
        }
        Ok(connection)
    }
}
