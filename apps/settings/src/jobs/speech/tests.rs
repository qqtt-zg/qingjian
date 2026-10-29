//! 隔离配置不操作真实连接，后台旧状态不覆盖用户动作，失败保留可用界面。

use super::{Snapshot, SpeechJob};

#[test]
fn isolated_configuration_cannot_start_or_stop_real_receiver() {
    let mut job = SpeechJob::new(false);
    job.run("start", None, None);
    job.run("stop", None, None);
    assert!(!job.busy && job.requests.is_none());
}

#[test]
fn late_poll_does_not_complete_action_and_error_keeps_connection() {
    let mut job = SpeechJob::new(false);
    job.state.running = true;
    job.busy = true;
    *job.result.lock().unwrap() = Some((false, Ok(Snapshot::default())));
    assert!(!job.finish());
    assert!(job.busy && job.state.running);
    *job.result.lock().unwrap() = Some((true, Err("模型文件夹包含辅助脚本".to_owned())));
    assert!(job.finish());
    assert!(!job.busy && job.state.running);
    assert!(job.notice.contains("辅助脚本"));
}

#[test]
fn manual_refresh_completes_and_speech_page_builds_without_live_service() {
    use crate::page_id::PageId;
    use crate::settings::Settings;
    use bamboo_slip::{FontLibrary, Runner};
    use std::time::Instant;

    let path =
        std::env::temp_dir().join(format!("qingjian-speech-merge-{}.toml", std::process::id()));
    let mut settings = Settings::load(path);
    settings.page = PageId::Speech;
    settings.speech.busy = true;
    let state = Snapshot::parse("running = true\nenabled = true\nphone_connected = true\nstatus = \"手机已连接\"\nselected = \"builtin:1\"\n[[choices]]\nkey = \"builtin:1\"\nlabel = \"女声 1 · af_heart\"\n").unwrap();
    *settings.speech.result.lock().unwrap() = Some((true, Ok(state)));
    assert!(settings.speech.finish());
    assert!(!settings.speech.busy);
    let mut runner = Runner::new(settings, FontLibrary::system("zh-CN").unwrap());
    let painted = runner.rebuild(1.0, (900, 680), Instant::now()).unwrap();
    assert!(painted.content_width > 0 && painted.content_height > 0);
}
