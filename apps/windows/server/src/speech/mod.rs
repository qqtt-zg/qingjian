//! 候选译词的按需朗读：只向本机接收工具提交用户点击的英文，不监听输入或自动播报。

mod connection;
mod queue;
mod sender;
mod sink;

pub use sender::SpeechSender;
pub use sink::SpeechSink;
