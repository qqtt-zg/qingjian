//! 手机朗读页的后台操作及状态回包。

#[derive(Clone)]
pub(crate) enum Message {
    Run(&'static str),
    Select(Option<usize>),
    Enabled(bool),
    Done(u64, Result<String, String>),
}
