//! 鼠标点候选窗口：点候选与按它的数字键一样上屏，点译词与按译词键一样上屏那一条。
//! Server 不能直接写文档：上屏的文本挂到焦点会话上，DLL 下一拍轮询时随 `Update.commit` 取走写进去，
//! 赶在轮询前又敲了键就接在那次按键结果前面。上屏要求 v8，朗读不依赖 DLL 写文档。

use qingjian_platform::protocol::SessionId;
use qingjian_render::HitTarget;

use crate::dispatch::Router;

/// 从这一版协议起 DLL 会把 `Update.commit` 写进文档。
const CLICK_SINCE: u32 = 8;

impl Router {
    /// 候选窗口上点中了候选或译词（UI 线程经工人通道送来）。
    pub fn handle_click(&mut self, target: HitTarget) {
        if self.focused.is_none() || self.translation.is_some() || !self.composing() {
            return;
        }
        if let HitTarget::Speak(offset) = target {
            self.speak_candidate(offset);
            let frame = self.self_drawn_frame();
            self.reconcile_candidates(&frame);
            return;
        }
        let Some(session) = self.click_session() else {
            return;
        };
        let text = match target {
            HitTarget::Speak(_) => unreachable!("朗读已经单独处理"),
            HitTarget::Candidate(offset) => self
                .slot_index(offset + 1)
                .and_then(|index| self.commit_index(index)),
            HitTarget::Translation { candidate, sense } => self
                .annotated_candidate_on_page(candidate + 1)
                .and_then(|candidate| self.engine.commit_translation(&candidate, sense)),
        };
        let Some(text) = text else {
            tracing::debug!(?target, "点中的格子没有可上屏的");
            return;
        };
        tracing::debug!(?target, "点击上屏");
        self.deliver_clicked(session, text);
    }

    /// 喇叭只发英文正文，不选词、不消耗拼音、不记学习。
    pub(super) fn speak_candidate(&mut self, offset: usize) {
        if offset >= self.config.page_size {
            return;
        }
        let Some(text) = self
            .annotated_candidate_on_page(offset + 1)
            .and_then(|candidate| candidate.speech_text())
        else {
            return;
        };
        let submitted = self
            .speech
            .as_ref()
            .is_some_and(|speech| speech.submit(text));
        tracing::info!(submitted, "收到候选英文朗读点击");
        self.notice = Some(if submitted {
            "正在发送朗读请求…".to_owned()
        } else {
            "请先启动手机朗读连接工具".to_owned()
        });
    }

    /// 发送结果只更新尚在等待的朗读提示，不覆盖后续输入或新会话。
    pub(in crate::dispatch) fn poll_speech(&mut self) {
        let result = self.speech.as_ref().and_then(|speech| speech.take_result());
        let Some(delivered) = result else { return };
        if self.notice.as_deref() != Some("正在发送朗读请求…") || !self.composing() {
            return;
        }
        self.notice = Some(if delivered {
            "已发送，请在手机确认播放".to_owned()
        } else {
            "朗读发送失败，请检查连接工具".to_owned()
        });
        let frame = self.self_drawn_frame();
        self.reconcile_candidates(&frame);
    }

    /// 收得了点击的聚焦会话：DLL 太老、读不到 `Update.commit` 时不收。
    pub(in crate::dispatch) fn click_session(&self) -> Option<SessionId> {
        let session = self.focused?;
        if self.focused_dll_protocol() < CLICK_SINCE {
            tracing::debug!("DLL 协议太老，不收鼠标上屏");
            return None;
        }
        Some(session)
    }

    /// 点击上屏的文本挂到会话上等 DLL 取走，再按新状态重画。
    pub(in crate::dispatch) fn deliver_clicked(&mut self, session: SessionId, text: String) {
        let pending = self.take_clicked(session).unwrap_or_default();
        self.clicked = Some((session, pending + &text));
        self.recompose();
        let shown = self.self_drawn_frame();
        self.reconcile_candidates(&shown);
    }

    /// 取走这个会话点击上屏、还没交出去的文本。
    pub(in crate::dispatch) fn take_clicked(&mut self, session: SessionId) -> Option<String> {
        if self
            .clicked
            .as_ref()
            .is_some_and(|(owner, _)| *owner == session)
        {
            self.clicked.take().map(|(_, text)| text)
        } else {
            None
        }
    }
}
