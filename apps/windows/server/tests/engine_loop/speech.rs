//! 后台发送结果只更新当前朗读提示，不消耗输入，也不覆盖后续按键。

use std::sync::{Arc, Mutex};

use qingjian_platform::protocol::{ClientMessage, ServerMessage};
use qingjian_render::HitTarget;
use qingjian_windows_server::speech::SpeechSink;

use crate::support::{SESSION, letter, press, router, slot_of, type_letters};

struct FeedbackSpeech(Arc<Mutex<Option<bool>>>);

impl SpeechSink for FeedbackSpeech {
    fn submit(&self, _text: String) -> bool {
        true
    }

    fn take_result(&self) -> Option<bool> {
        self.0.lock().unwrap().take()
    }
}

#[test]
fn speech_delivery_and_failure_are_visible_without_committing() {
    for delivered in [true, false] {
        let mut router = router();
        let result = Arc::new(Mutex::new(None));
        router.set_speech_sink(Box::new(FeedbackSpeech(result.clone())));
        let (_, _, before) = type_letters(&mut router, "nihao");
        router.handle_click(HitTarget::Speak(slot_of(&before, "你好") as usize - 1));
        *result.lock().unwrap() = Some(delivered);
        let Some(ServerMessage::Update { frame, commit, .. }) =
            router.handle(ClientMessage::Poll { session: SESSION })
        else {
            panic!("missing update")
        };
        assert_eq!(commit, None);
        assert_eq!(frame.candidates, before.candidates);
        assert_eq!(
            frame.notice.as_deref(),
            Some(if delivered {
                "已发送，请在手机确认播放"
            } else {
                "朗读发送失败，请检查连接工具"
            })
        );
    }
}

#[test]
fn late_speech_result_does_not_overwrite_new_input() {
    let mut router = router();
    let result = Arc::new(Mutex::new(None));
    router.set_speech_sink(Box::new(FeedbackSpeech(result.clone())));
    let (_, _, before) = type_letters(&mut router, "nihao");
    router.handle_click(HitTarget::Speak(slot_of(&before, "你好") as usize - 1));
    press(&mut router, letter('m'));
    *result.lock().unwrap() = Some(false);
    let Some(ServerMessage::Update { frame, .. }) =
        router.handle(ClientMessage::Poll { session: SESSION })
    else {
        panic!("missing update")
    };
    assert!(frame.notice.is_none());
}
