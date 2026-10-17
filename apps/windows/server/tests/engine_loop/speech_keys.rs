//! 可配置的候选朗读快捷键：当前页序号，只发英文，不上屏或消耗输入。

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use qingjian_platform::Config;
use qingjian_platform::protocol::{KeyEvent, KeyModifiers, KeyOutcome};
use qingjian_windows_server::speech::SpeechSink;
use qingjian_windows_server::{Router, RouterConfig};

use crate::support::{
    CTRL, SHIFT, digit_with, preedit, press, router_with, slot_of, type_english, type_letters,
};

struct RecordingSpeech(Arc<Mutex<Vec<String>>>);

impl SpeechSink for RecordingSpeech {
    fn submit(&self, text: String) -> bool {
        self.0.lock().unwrap().push(text);
        true
    }
}

fn configured(speech: &str) -> (Router, Arc<Mutex<Vec<String>>>) {
    configured_on_page(speech, 9)
}

fn configured_on_page(speech: &str, page_size: usize) -> (Router, Arc<Mutex<Vec<String>>>) {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let path = std::env::temp_dir().join(format!(
        "qingjian-speech-keys-{}-{}.toml",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::write(&path,format!("[general]\npage_size={page_size}\n[shortcut]\nspeak_candidate='{speech}'\ntranslation='shift'\ntranslation_second='ctrl+shift'\n")).unwrap();
    let config = Config::load(&path).unwrap();
    std::fs::remove_file(&path).unwrap();
    let mut router = router_with(RouterConfig::from(&config));
    let spoken = Arc::new(Mutex::new(Vec::new()));
    router.set_speech_sink(Box::new(RecordingSpeech(spoken.clone())));
    (router, spoken)
}

#[test]
fn ctrl_digit_speaks_twice_without_committing_and_shift_still_commits_translation() {
    let (mut router, spoken) = configured("ctrl");
    let (_, _, before) = type_letters(&mut router, "nihao");
    let slot = slot_of(&before, "你好");
    for _ in 0..2 {
        let (outcome, commit, after) = press(&mut router, digit_with(slot, CTRL));
        assert_eq!((outcome, commit), (KeyOutcome::Consumed, None));
        assert_eq!(preedit(&after), preedit(&before));
        assert_eq!(after.candidates, before.candidates);
    }
    assert_eq!(spoken.lock().unwrap().as_slice(), ["hello", "hello"]);
    let (_, commit, after) = press(&mut router, digit_with(slot, SHIFT));
    assert_eq!(commit.as_deref(), Some("hello"));
    assert!(after.is_empty());
}

#[test]
fn english_candidate_speaks_itself_instead_of_committing() {
    let (mut router, spoken) = configured("ctrl");
    let (_, _, before) = type_letters(&mut router, "hello");
    let slot = slot_of(&before, "hello");
    let (outcome, commit, after) = press(&mut router, digit_with(slot, CTRL));
    assert_eq!((outcome, commit), (KeyOutcome::Consumed, None));
    assert_eq!(spoken.lock().unwrap().as_slice(), ["hello"]);
    assert_eq!(after.candidates, before.candidates);
    assert_eq!(preedit(&after), preedit(&before));
}

#[test]
fn speech_uses_the_current_page_and_preserves_highlight() {
    let (mut router, spoken) = configured_on_page("ctrl", 1);
    let (_, _, before) = type_english(&mut router, "hel");
    let (_, _, selected) = press(
        &mut router,
        KeyEvent::new(
            0x22,
            None,
            KeyModifiers {
                english_mode: true,
                ..Default::default()
            },
        ),
    );
    assert_ne!(
        selected.candidates.items[0].text,
        before.candidates.items[0].text
    );
    let (outcome, commit, after) = press(
        &mut router,
        digit_with(
            1,
            KeyModifiers {
                english_mode: true,
                ..CTRL
            },
        ),
    );
    assert_eq!((outcome, commit), (KeyOutcome::Consumed, None));
    assert_eq!(
        spoken.lock().unwrap().as_slice(),
        [selected.candidates.items[0].text.as_str()]
    );
    assert_eq!(after.highlight, selected.highlight);
    assert_eq!(after.candidates, selected.candidates);
}

#[test]
fn disabled_and_custom_speech_keys_do_not_capture_unrelated_keys() {
    for (keys, held) in [("", CTRL), ("ctrl+alt", CTRL)] {
        let (mut router, spoken) = configured(keys);
        type_letters(&mut router, "nihao");
        assert_eq!(
            press(&mut router, digit_with(1, held)).0,
            KeyOutcome::Passthrough
        );
        assert!(spoken.lock().unwrap().is_empty());
    }
    let (mut router, spoken) = configured("ctrl+alt");
    assert_eq!(
        press(
            &mut router,
            digit_with(1, KeyModifiers { alt: true, ..CTRL })
        )
        .0,
        KeyOutcome::Passthrough
    );
    let (_, _, before) = type_letters(&mut router, "nihao");
    let slot = slot_of(&before, "你好");
    assert_eq!(
        press(
            &mut router,
            digit_with(slot, KeyModifiers { alt: true, ..CTRL })
        )
        .0,
        KeyOutcome::Consumed
    );
    assert_eq!(spoken.lock().unwrap().as_slice(), ["hello"]);
}

#[test]
fn missing_number_does_not_read_a_candidate_on_the_next_page() {
    let (mut router, spoken) = configured_on_page("ctrl", 1);
    let (_, _, before) = type_english(&mut router, "hel");
    for number in [2, 9] {
        let (outcome, commit, after) = press(
            &mut router,
            digit_with(
                number,
                KeyModifiers {
                    english_mode: true,
                    ..CTRL
                },
            ),
        );
        assert_eq!((outcome, commit), (KeyOutcome::Consumed, None));
        assert_eq!(after.candidates, before.candidates);
    }
    assert!(spoken.lock().unwrap().is_empty());
    assert_eq!(
        press(
            &mut router,
            digit_with(
                0,
                KeyModifiers {
                    english_mode: true,
                    ..CTRL
                }
            )
        )
        .0,
        KeyOutcome::Passthrough
    );
}

#[test]
fn speech_key_reports_missing_receiver_without_consuming_input() {
    let mut router = router_with(RouterConfig {
        speech_keys: CTRL,
        ..RouterConfig::default()
    });
    let (_, _, before) = type_letters(&mut router, "nihao");
    let slot = slot_of(&before, "你好");
    let (outcome, commit, after) = press(&mut router, digit_with(slot, CTRL));
    assert_eq!((outcome, commit), (KeyOutcome::Consumed, None));
    assert_eq!(after.notice.as_deref(), Some("请先启动手机朗读连接工具"));
    assert_eq!(preedit(&after), preedit(&before));
}
