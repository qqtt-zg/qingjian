//! 单槽朗读队列：新点击覆盖尚未发送的旧点击，关闭时唤醒发送线程退出。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};

#[derive(Default)]
pub(super) struct SpeechQueue {
    pending: Mutex<Option<(u64, String)>>,

    ready: Condvar,

    closed: AtomicBool,
}

impl SpeechQueue {
    pub(super) fn replace(&self, request: (u64, String)) -> bool {
        let Ok(mut pending) = self.pending.lock() else {
            return false;
        };
        if self.closed.load(Ordering::Acquire) {
            return false;
        }
        *pending = Some(request);
        self.ready.notify_one();
        true
    }

    pub(super) fn take(&self) -> Option<(u64, String)> {
        let pending = self.pending.lock().ok()?;
        let mut pending = self
            .ready
            .wait_while(pending, |pending| {
                pending.is_none() && !self.closed.load(Ordering::Acquire)
            })
            .ok()?;
        if self.closed.load(Ordering::Acquire) {
            None
        } else {
            pending.take()
        }
    }

    pub(super) fn close(&self) {
        if let Ok(mut pending) = self.pending.lock() {
            self.closed.store(true, Ordering::Release);
            *pending = None;
            self.ready.notify_one();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::SpeechQueue;

    #[test]
    fn latest_click_replaces_pending_click_and_close_discards_it() {
        let queue = SpeechQueue::default();
        assert!(queue.replace((1, "old".to_owned())));
        assert!(queue.replace((2, "latest".to_owned())));
        assert_eq!(queue.take(), Some((2, "latest".to_owned())));
        assert!(queue.replace((3, "pending".to_owned())));
        queue.close();
        assert_eq!(queue.take(), None);
        assert!(!queue.replace((4, "after close".to_owned())));
    }
}
