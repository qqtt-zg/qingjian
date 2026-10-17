//! 候选的英文朗读正文：英文候选读本身，其余候选读英文译词。

use super::{Candidate, CandidateKind};

impl Candidate {
    /// 不包含词性、读音或显示标记；英文候选不依赖是否已有中文释义。
    pub fn speech_text(&self) -> Option<String> {
        if self.kind == CandidateKind::English {
            let text = self.text.trim();
            return (!text.is_empty()).then(|| text.to_owned());
        }
        self.translation.as_ref()?.speech_text()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::candidate::{Language, PartOfSpeech, Sense, Translation};

    fn candidate(kind: CandidateKind, text: &str, language: Language, gloss: &str) -> Candidate {
        Candidate {
            text: text.to_owned(),
            kind,
            syllables: Vec::new(),
            reading: None,
            translation: Some(Translation::new(
                language,
                vec![Sense {
                    part_of_speech: Some(PartOfSpeech::Verb),
                    text: gloss.to_owned(),
                    reading: None,
                    fresh: false,
                }],
            )),
            aux_code: None,
        }
    }

    #[test]
    fn english_speaks_itself_instead_of_its_chinese_definition() {
        for word in ["control", "controlled", "controls", "controlling"] {
            let mut candidate = candidate(CandidateKind::English, word, Language::Chinese, "控制");
            assert_eq!(candidate.speech_text().as_deref(), Some(word));
            candidate.translation = None;
            assert_eq!(candidate.speech_text().as_deref(), Some(word));
            candidate.text = "  ".to_owned();
            assert_eq!(candidate.speech_text(), None);
        }
    }

    #[test]
    fn other_candidates_still_require_an_english_translation() {
        let chinese = candidate(CandidateKind::Chinese, "开发", Language::English, "develop");
        assert_eq!(chinese.speech_text().as_deref(), Some("develop"));
        for (kind, text) in [
            (CandidateKind::Chinese, "控制"),
            (CandidateKind::Emoji, "🎛️"),
        ] {
            let mut candidate = candidate(kind, text, Language::Chinese, "控制");
            assert_eq!(candidate.speech_text(), None);
            candidate.translation = None;
            assert_eq!(candidate.speech_text(), None);
        }
    }
}
