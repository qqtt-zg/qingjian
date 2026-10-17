//! 主题里能写的矢量小图标（`"icon": "cloud"`），各自实现引擎的 [`bamboo_slip::Icon`]。

mod cloud;
mod gear;

use std::sync::{Arc, LazyLock};

use serde::Deserialize;

use crate::speaker::Speaker;
use cloud::Cloud;
use gear::Gear;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Icon {
    /// 云联想的云朵。
    Cloud,

    /// 状态条打开设置的齿轮。
    Gear,

    /// 译词的按需朗读按钮。
    Speaker,
}

static CLOUD: LazyLock<Arc<dyn bamboo_slip::Icon>> = LazyLock::new(|| Arc::new(Cloud));

static GEAR: LazyLock<Arc<dyn bamboo_slip::Icon>> = LazyLock::new(|| Arc::new(Gear));
static SPEAKER: LazyLock<Arc<dyn bamboo_slip::Icon>> = LazyLock::new(|| Arc::new(Speaker));

impl Icon {
    /// 交给场景树画的那一个。
    pub(crate) fn painter(self) -> Arc<dyn bamboo_slip::Icon> {
        match self {
            Self::Cloud => Arc::clone(&CLOUD),
            Self::Gear => Arc::clone(&GEAR),
            Self::Speaker => Arc::clone(&SPEAKER),
        }
    }
}
