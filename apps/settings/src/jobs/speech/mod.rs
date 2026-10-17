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

pub struct SpeechJob {
    pub state: Snapshot,

    pub notice: String,

    pub busy: bool,

    pub live: bool,

    pub qr: Option<Arc<Qr>>,

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
            requests: None,
            result: Arc::default(),
        }
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

    pub fn run(&mut self, action: &'static str, mut value: Option<String>, waker: Option<Waker>) {
        if !self.live || self.busy {
            return;
        }
        if action == "import" {
            let Some(folder) = rfd::FileDialog::new()
                .set_title("选择解压后的音色模型文件夹")
                .pick_folder()
            else {
                return;
            };
            value = Some(folder.to_string_lossy().into_owned());
        }
        self.poll(waker);
        if self
            .requests
            .as_ref()
            .is_some_and(|sender| sender.send((action, value, true)).is_ok())
        {
            self.busy = true;
            self.notice = match action {
                "import" => "正在打包音色；完成后手机开始下载…",
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
    if let Some(value) = value {
        command.arg(value);
    }
    let output = command
        .output()
        .map_err(|e| format!("启动手机朗读工具失败：{e}"))?;
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
