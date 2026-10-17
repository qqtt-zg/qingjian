//! 鼠标点候选窗口：上屏的字挂到焦点会话上，下一拍轮询（或抢先来的键）带给 DLL；老协议的 DLL 不收点击。

use qingjian_render::HitTarget;
use qingjian_windows_server::speech::SpeechSink;

use crate::composing::SentenceTranslator;
use crate::support::{
    Arc, ClientMessage, Frame, Mutex, Router, RouterConfig, SESSION, ServerMessage, SessionId,
    letter, preedit, press, press_in, router, router_with, slot_of, type_letters,
};

fn poll(router: &mut Router, session: SessionId) -> (Frame, Option<String>) {
    match router.handle(ClientMessage::Poll { session }) {
        Some(ServerMessage::Update { frame, commit, .. }) => (frame, commit),
        other => panic!("expected Update, got {other:?}"),
    }
}

#[test]
fn clicking_a_candidate_commits_it_on_the_next_poll() {
    let mut router = router();
    let (_, _, frame) = type_letters(&mut router, "nihao");
    let slot = slot_of(&frame, "你好");
    router.handle_click(HitTarget::Candidate(slot as usize - 1));
    let (after, commit) = poll(&mut router, SESSION);
    assert_eq!(commit.as_deref(), Some("你好"));
    assert!(after.is_empty());
    // 交出去一次就没了
    assert_eq!(poll(&mut router, SESSION).1, None);
}

#[test]
fn clicking_a_translation_commits_that_sense() {
    let mut router = router();
    let (_, _, frame) = type_letters(&mut router, "nihao");
    let slot = slot_of(&frame, "你好");
    router.handle_click(HitTarget::Translation {
        candidate: slot as usize - 1,
        sense: 0,
    });
    assert_eq!(poll(&mut router, SESSION).1.as_deref(), Some("hello"));
}

#[test]
fn clicking_a_sentence_translation_commits_it() {
    let submitted = Arc::new(Mutex::new(Vec::new()));
    let mut router = router_with(RouterConfig {
        sentence_translation: true,
        ..RouterConfig::default()
    });
    router
        .engine_mut()
        .set_predictor(Box::new(SentenceTranslator::new(submitted)));
    let (_, _, frame) = type_letters(&mut router, "xiangkaifa");
    let slot = slot_of(&frame, "想开发");
    router.handle_click(HitTarget::Translation {
        candidate: slot as usize - 1,
        sense: 0,
    });
    assert_eq!(
        poll(&mut router, SESSION).1.as_deref(),
        Some("It seems not to work.")
    );
}

#[test]
fn a_key_before_the_poll_carries_the_clicked_text() {
    let mut router = router();
    let (_, _, frame) = type_letters(&mut router, "nihao");
    let slot = slot_of(&frame, "你好");
    router.handle_click(HitTarget::Candidate(slot as usize - 1));
    let (_, commit, frame) = press(&mut router, letter('w'));
    assert_eq!(commit.as_deref(), Some("你好"));
    assert_eq!(preedit(&frame), "w");
    assert_eq!(poll(&mut router, SESSION).1, None);
}

#[test]
fn old_dlls_do_not_take_clicks() {
    let mut router = router();
    let old = SessionId(2);
    router.handle(ClientMessage::OpenSession {
        session: old,
        app: None,
        // 点击上屏从协议 8 起（`CLICK_SINCE`），7 是最后一版不认 `Update.commit` 的 DLL
        protocol: 7,
    });
    for c in "nihao".chars() {
        press_in(&mut router, old, letter(c));
    }
    router.handle_click(HitTarget::Candidate(0));
    let (frame, commit) = poll(&mut router, old);
    assert_eq!(commit, None);
    assert_eq!(preedit(&frame), "ni'hao");
}

struct RecordingSpeech(Arc<Mutex<Vec<String>>>);

impl SpeechSink for RecordingSpeech {
    fn submit(&self, text: String) -> bool {
        self.0.lock().unwrap().push(text);
        true
    }
}

#[test]
fn speaker_replays_english_without_committing_or_consuming_composition() {
    let mut router = router();
    let spoken = Arc::new(Mutex::new(Vec::new()));
    router.set_speech_sink(Box::new(RecordingSpeech(spoken.clone())));
    let (_, _, before) = type_letters(&mut router, "nihao");
    assert!(spoken.lock().unwrap().is_empty(), "打字不能自动朗读");
    let offset = slot_of(&before, "你好") as usize - 1;
    router.handle_click(HitTarget::Speak(offset));
    router.handle_click(HitTarget::Speak(offset));
    let (after, commit) = poll(&mut router, SESSION);
    assert_eq!(commit, None);
    assert_eq!(preedit(&after), preedit(&before));
    assert_eq!(after.candidates, before.candidates);
    let spoken = spoken.lock().unwrap();
    assert_eq!(spoken.len(), 2, "重复点击应允许重听");
    assert_eq!(spoken[0], "hello");
    assert_eq!(spoken[1], "hello");
    drop(spoken);
    router.handle_click(HitTarget::Candidate(offset));
    assert_eq!(poll(&mut router, SESSION).1.as_deref(), Some("你好"));
}

#[test]
fn speaker_without_receiver_keeps_original_input_and_reports_setup() {
    let mut router = router();
    let (_, _, before) = type_letters(&mut router, "nihao");
    let offset = slot_of(&before, "你好") as usize - 1;
    router.handle_click(HitTarget::Speak(offset));
    let (after, commit) = poll(&mut router, SESSION);
    assert_eq!(commit, None);
    assert_eq!(preedit(&after), preedit(&before));
    assert_eq!(after.notice.as_deref(), Some("请先启动手机朗读连接工具"));
}

#[test]
fn old_dll_can_speak_without_gaining_mouse_commit_support() {
    let mut router = router();
    let spoken = Arc::new(Mutex::new(Vec::new()));
    router.set_speech_sink(Box::new(RecordingSpeech(spoken.clone())));
    let old = SessionId(2);
    router.handle(ClientMessage::OpenSession {
        session: old,
        app: None,
        protocol: 7,
    });
    for c in "nihao".chars() {
        press_in(&mut router, old, letter(c));
    }
    let (before, _) = poll(&mut router, old);
    let offset = slot_of(&before, "你好") as usize - 1;
    router.handle_click(HitTarget::Speak(offset));
    assert_eq!(spoken.lock().unwrap().as_slice(), &["hello"]);
    let (after, commit) = poll(&mut router, old);
    assert_eq!(commit, None);
    assert_eq!(preedit(&after), preedit(&before));
    router.handle_click(HitTarget::Candidate(offset));
    assert_eq!(poll(&mut router, old).1, None);
}

#[test]
fn english_candidate_in_pinyin_mode_speaks_the_word_without_committing() {
    let mut router = router();
    let spoken = Arc::new(Mutex::new(Vec::new()));
    router.set_speech_sink(Box::new(RecordingSpeech(spoken.clone())));
    let (_, _, before) = type_letters(&mut router, "hello");
    let offset = slot_of(&before, "hello") as usize - 1;
    assert_eq!(
        before.candidates.items[offset].kind,
        qingjian_core::CandidateKind::English
    );
    router.handle_click(HitTarget::Speak(offset));
    let (after, commit) = poll(&mut router, SESSION);
    assert_eq!(spoken.lock().unwrap().as_slice(), &["hello"]);
    assert_eq!(commit, None);
    assert_eq!(preedit(&after), preedit(&before));
    assert_eq!(after.candidates, before.candidates);
    router.handle_click(HitTarget::Candidate(offset));
    assert_eq!(poll(&mut router, SESSION).1.as_deref(), Some("hello"));
}
