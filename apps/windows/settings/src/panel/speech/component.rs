//! 子组件后台任务与轮询；过期回包不会覆盖用户刚提交的操作。

use std::os::windows::process::CommandExt;
use std::process::Command;
use std::time::Duration;

use windows_reactor::{Component, ComponentContext, View, ViewContext};

use super::SpeechPane;
use super::message::Message;
use super::snapshot::Snapshot;

fn call(action: &str, value: Option<String>) -> Result<String, String> {
    let helper = crate::panel::controls::repo_resource("tools/speech-relay/desktop.py")
        .ok_or_else(|| "未找到手机朗读辅助文件，请检查修改版安装目录".to_owned())?;
    let mut command = Command::new("python");
    command
        .arg("-B")
        .arg(helper)
        .arg(action)
        .env("PYTHONIOENCODING", "utf-8")
        .creation_flags(0x0800_0000);
    if let Some(value) = value {
        command.arg(value);
    }
    let result = command
        .output()
        .map_err(|e| format!("启动手机朗读辅助程序失败：{e}"))?;
    let text = String::from_utf8(result.stdout).map_err(|e| e.to_string())?;
    if text.is_empty() {
        return Err("手机朗读辅助程序没有返回状态，请检查 Python 环境".to_owned());
    }
    if !result.status.success() {
        return Err(Snapshot::parse(&text)?.status);
    }
    Ok(text)
}

impl SpeechPane {
    fn launch(
        &mut self,
        context: &ComponentContext<Self>,
        action: &'static str,
        value: Option<String>,
        delayed: bool,
    ) {
        self.epoch = self.epoch.wrapping_add(1);
        let epoch = self.epoch;
        context.spawn_background(move |cancel| {
            if delayed {
                for _ in 0..20 {
                    if cancel.is_cancelled() {
                        return Message::Done(epoch, Err("已关闭页面".to_owned()));
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
            Message::Done(epoch, call(action, value))
        });
    }
}

impl Component for SpeechPane {
    type Input = ();
    type Message = Message;

    fn create(_input: &(), context: &ComponentContext<Self>) -> Self {
        let mut pane = Self {
            snapshot: Snapshot::default(),
            epoch: 0,
            busy: false,
            notice: String::new(),
        };
        pane.launch(context, "status", None, false);
        pane
    }

    fn update(&mut self, message: Message, context: &ComponentContext<Self>) {
        match message {
            Message::Run(action) if !self.busy => {
                let value = if action == "import" {
                    let Some(folder) = rfd::FileDialog::new()
                        .set_title("选择解压后的 Kitten、Piper 或 Kokoro 模型文件夹")
                        .pick_folder()
                    else {
                        return;
                    };
                    Some(folder.to_string_lossy().into_owned())
                } else {
                    None
                };
                self.busy = true;
                self.notice = match action {
                    "start" => "正在建立手机连接…",
                    "import" => "正在打包并发送音色，请等待…",
                    _ => "正在处理…",
                }
                .to_owned();
                self.launch(context, action, value, false);
            }
            Message::Select(Some(index)) if !self.busy => {
                if let Some((key, _)) = self.snapshot.choices.get(index)
                    && *key != self.snapshot.selected
                {
                    let value = key.clone();
                    self.busy = true;
                    self.notice = "正在切换手机音色…".to_owned();
                    self.launch(context, "select", Some(value), false);
                }
            }
            Message::Enabled(enabled) if !self.busy && enabled != self.snapshot.enabled => {
                self.busy = true;
                self.launch(
                    context,
                    if enabled { "enable" } else { "pause" },
                    None,
                    false,
                );
            }
            Message::Done(epoch, result) if epoch == self.epoch => {
                let was_action = self.busy;
                self.busy = false;
                match result.and_then(|text| Snapshot::parse(&text)) {
                    Ok(snapshot) => {
                        self.snapshot = snapshot;
                        if was_action {
                            self.notice.clear();
                        }
                    }
                    Err(error) => self.notice = error,
                }
                self.launch(context, "status", None, true);
            }
            _ => {}
        }
    }

    fn view(&self, _input: &(), context: &mut ViewContext<Self>) -> View {
        super::view::view(self, context)
    }
}
