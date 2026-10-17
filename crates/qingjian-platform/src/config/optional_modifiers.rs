//! 可关闭的修饰键组合：空串关闭，其余沿用 Modifiers 的解析与写法。

use serde::{Deserialize, Deserializer, Serializer};

use super::modifiers::Modifiers;

pub(super) fn deserialize<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Modifiers, D::Error> {
    let text = String::deserialize(deserializer)?;
    if text.trim().is_empty() {
        Ok(Modifiers::default())
    } else {
        text.parse().map_err(serde::de::Error::custom)
    }
}

pub(super) fn serialize<S: Serializer>(keys: &Modifiers, serializer: S) -> Result<S::Ok, S::Error> {
    serializer.serialize_str(&keys.key())
}
