//! 朗读请求的输出边界；实现应立即入队，不能在输入事件线程上等待网络。

pub trait SpeechSink: Send {
    /// 返回是否接收入队；不表示手机已经播放完成。
    fn submit(&self, text: String) -> bool;

    /// 最近一次提交的发送结果；`true` 只表示本机连接工具接受，不表示手机已播放。
    fn take_result(&self) -> Option<bool> {
        None
    }
}
