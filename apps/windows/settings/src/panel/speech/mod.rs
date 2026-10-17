//! 手机朗读原生设置子组件；网络和音色打包均交给后台辅助程序。

mod component;
mod message;
mod snapshot;
mod view;

use self::snapshot::Snapshot;

pub(crate) struct SpeechPane {
    snapshot: Snapshot,

    epoch: u64,

    busy: bool,

    notice: String,
}
