use serde::{Deserialize, Serialize};

use super::language::Language;
use super::sense::Sense;

/// 候选词在单一学习语言下的翻译。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Translation {
    /// 译文所属的学习语言。
    pub language: Language,

    /// 释义，按常用程度排序，最多 [`Self::MAX_SENSES`] 条。
    senses: Vec<Sense>,
}

impl Translation {
    /// 释义上限。超出的部分在构造时丢弃，保证候选框不会退化成迷你词典。
    pub const MAX_SENSES: usize = 2;

    pub fn new(language: Language, mut senses: Vec<Sense>) -> Self {
        senses.truncate(Self::MAX_SENSES);
        Self { language, senses }
    }

    pub fn senses(&self) -> &[Sense] {
        &self.senses
    }

    pub fn senses_mut(&mut self) -> &mut [Sense] {
        &mut self.senses
    }

    /// 按需朗读英文译词：只取义项正文，不带词性、注音或生词标记。
    pub fn speech_text(&self) -> Option<String> {
        if self.language != Language::English {
            return None;
        }
        let mut texts: Vec<&str> = Vec::new();
        for sense in &self.senses {
            let text = sense.text.trim();
            if !text.is_empty() && !texts.contains(&text) {
                texts.push(text);
            }
        }
        (!texts.is_empty()).then(|| texts.join(", "))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::candidate::PartOfSpeech;

    #[test]
    fn keeps_at_most_two_senses() {
        let senses = ["develop", "development", "exploit"]
            .into_iter()
            .map(|text| Sense {
                part_of_speech: Some(PartOfSpeech::Verb),
                text: text.to_owned(),
                reading: None,
                fresh: false,
            })
            .collect();
        let translation = Translation::new(Language::English, senses);
        assert_eq!(translation.senses().len(), Translation::MAX_SENSES);
    }

    #[test]
    fn speech_uses_english_text_without_labels_or_duplicate_senses() {
        let sense = |text: &str| Sense {
            part_of_speech: Some(PartOfSpeech::Pronoun),
            text: text.to_owned(),
            reading: Some("reading must not be spoken".to_owned()),
            fresh: true,
        };
        let translation = Translation::new(Language::English, vec![sense(" I "), sense("me")]);
        assert_eq!(translation.speech_text().as_deref(), Some("I, me"));
        let duplicate = Translation::new(Language::English, vec![sense("hello"), sense("hello")]);
        assert_eq!(duplicate.speech_text().as_deref(), Some("hello"));
        assert_eq!(
            Translation::new(Language::Japanese, vec![sense("こんにちは")]).speech_text(),
            None
        );
        assert_eq!(
            Translation::new(Language::English, vec![sense(" ")]).speech_text(),
            None
        );
    }
}
