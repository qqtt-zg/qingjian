//! 手机朗读的后台命令与定时状态同步；隔离配置下不连接真实服务。

mod qr;
mod snapshot;
#[cfg(test)]
mod tests;

pub use qr::Qr;
pub use snapshot::Snapshot;

use std::process::Command;
use std::sync::{Arc, Mutex, mpsc};
use std::time::Duration;

use bamboo_slip::Waker;

type Outcome = (bool, Result<Snapshot, String>);
type Request = (&'static str, Option<String>, bool);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpeechMode {
    Fixed,
    Temporary,
}

pub struct SpeechJob {
    pub state: Snapshot,

    pub notice: String,

    pub busy: bool,

    pub live: bool,

    pub qr: Option<Arc<Qr>>,

    pub mode: Option<SpeechMode>,

    pub guide_open: bool,

    requests: Option<mpsc::Sender<Request>>,

    result: Arc<Mutex<Option<Outcome>>>,
}

impl SpeechJob {
    pub fn new(live: bool) -> Self {
        Self {
            state: Snapshot::default(),
            notice: String::new(),
            busy: false,
            live,
            qr: None,
            mode: None,
            guide_open: false,
            requests: None,
            result: Arc::default(),
        }
    }

    pub fn mode(&self) -> SpeechMode {
        if let Some(mode) = self.mode {
            mode
        } else if self.state.has_named_config {
            SpeechMode::Fixed
        } else {
            SpeechMode::Temporary
        }
    }

    pub fn validate_token(token: &str) -> Result<String, String> {
        let trimmed = token.trim();
        if trimmed.is_empty() {
            return Err("令牌不能为空".to_owned());
        }
        if !trimmed.starts_with("eyJ") {
            return Err("这不像隧道令牌：应以 eyJ 开头，约两百个字符".to_owned());
        }
        use base64::Engine;
        let mut padded = trimmed.to_owned();
        while !padded.len().is_multiple_of(4) {
            padded.push('=');
        }
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(&padded)
            .map_err(|e| format!("Base64 解码失败：{e}"))?;
        let json: serde_json::Value = serde_json::from_slice(&bytes)
            .map_err(|e| format!("令牌不是合法 JSON 结构：{e}"))?;
        let t = json.get("t").and_then(|v| v.as_str()).unwrap_or("");
        let a = json.get("a").and_then(|v| v.as_str()).unwrap_or("");
        let s = json.get("s").and_then(|v| v.as_str()).unwrap_or("");
        if t.is_empty() || a.is_empty() || s.is_empty() {
            return Err("令牌解出来缺少必要字段（需含账号与隧道信息）".to_owned());
        }
        Ok(t.to_owned())
    }

    pub fn poll(&mut self, waker: Option<Waker>) {
        if self.requests.is_some() || !self.live {
            return;
        }
        let (sender, receiver) = mpsc::channel::<Request>();
        self.requests = Some(sender);
        let result = Arc::clone(&self.result);
        std::thread::spawn(move || {
            let mut request = ("status", None, false);
            loop {
                let action = request.2;
                let outcome = call(request.0, request.1);
                if let Ok(mut slot) = result.lock() {
                    // 窗口暂时没处理唤醒时，轮询不能盖掉尚未交付的操作结果。
                    if action
                        || slot
                            .as_ref()
                            .is_none_or(|(pending_action, _)| !pending_action)
                    {
                        *slot = Some((action, outcome));
                    }
                }
                if let Some(waker) = &waker {
                    waker.wake();
                }
                request = match receiver.recv_timeout(Duration::from_secs(2)) {
                    Ok(request) => request,
                    Err(mpsc::RecvTimeoutError::Timeout) => ("status", None, false),
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                };
            }
        });
    }

    pub fn run(&mut self, action: &'static str, value: Option<String>, waker: Option<Waker>) {
        match action {
            "mode-fixed" => {
                self.mode = Some(SpeechMode::Fixed);
                if let Some(waker) = waker {
                    waker.wake();
                }
                return;
            }
            "mode-temporary" => {
                self.mode = Some(SpeechMode::Temporary);
                if let Some(waker) = waker {
                    waker.wake();
                }
                return;
            }
            "guide-open" => {
                self.guide_open = true;
                if let Some(waker) = waker {
                    waker.wake();
                }
                return;
            }
            "guide-close" => {
                self.guide_open = false;
                if let Some(waker) = waker {
                    waker.wake();
                }
                return;
            }
            _ => {}
        }
        if !self.live || self.busy {
            return;
        }
        self.poll(waker);
        if self
            .requests
            .as_ref()
            .is_some_and(|sender| sender.send((action, value, true)).is_ok())
        {
            self.busy = true;
            self.notice = match action {
                "named-save" => "正在保存隧道并建立连接…",
                "named-clear" => "正在清除固定隧道并恢复临时连接…",
                "start" => "正在建立手机连接…",
                _ => "正在处理…",
            }
            .to_owned();
        }
    }

    pub fn finish(&mut self) -> bool {
        let outcome = self.result.lock().ok().and_then(|mut slot| slot.take());
        let Some((action, outcome)) = outcome else {
            return false;
        };
        if self.busy && !action {
            return false;
        }
        if action {
            self.busy = false;
        }
        match outcome {
            Ok(state) => {
                if state.qr != self.state.qr {
                    self.qr = Qr::load(&state.qr).map(Arc::new);
                }
                self.state = state;
                if action {
                    self.notice.clear();
                }
            }
            Err(error) => self.notice = error,
        }
        true
    }
}

fn call(action: &str, value: Option<String>) -> Result<Snapshot, String> {
    let helper = qingjian_platform::resources::bundled_resource("tools/speech-relay/desktop.py")
        .ok_or("未找到手机朗读工具，请检查修改版目录")?;
    let mut command = Command::new("python");
    command
        .arg("-B")
        .arg(helper)
        .arg(action)
        .env("PYTHONIOENCODING", "utf-8");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    let mut temp_file: Option<std::path::PathBuf> = None;
    if let Some(data) = value {
        if action == "named-save" {
            // 敏感的 Token 不直接作为命令行参数传递，通过临时文件转交
            let dir = std::env::temp_dir();
            let file_path = dir.join(format!("qj-named-save-{}.tmp", std::process::id()));
            std::fs::write(&file_path, &data).map_err(|e| format!("写入临时配置失败：{e}"))?;
            command.arg(&file_path);
            temp_file = Some(file_path);
        } else {
            command.arg(data);
        }
    }
    let output = command
        .output()
        .map_err(|e| format!("启动手机朗读工具失败：{e}"));
    if let Some(path) = temp_file {
        let _ = std::fs::remove_file(path);
    }
    let output = output?;
    let text = String::from_utf8(output.stdout).map_err(|e| e.to_string())?;
    if text.is_empty() {
        return Err("手机朗读工具未返回结果，请检查 Python 环境".to_owned());
    }
    let state = Snapshot::parse(&text)?;
    if !output.status.success() {
        return Err(state.status);
    }
    Ok(state)
}
