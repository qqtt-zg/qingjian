//! 候选窗口的点击：只有候选与译词那几块收鼠标（`WM_NCHITTEST` 回 `HTCLIENT`），其余地方回 `HTTRANSPARENT`
//! 穿到下面的应用，主题伸出窗口的装饰、投影都不挡；按下不激活窗口，点中的目标经回调交给 Router。
//! GDI 画法没有点击区域，整窗穿透。右键候选要菜单，菜单里点中的项也经这个回调交回。

use std::cell::RefCell;

use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    ReleaseCapture, SetCapture, TME_LEAVE, TRACKMOUSEEVENT, TrackMouseEvent,
};
use windows::Win32::UI::WindowsAndMessaging::{
    GetCursorPos, HTCLIENT, HTTRANSPARENT, IDC_HAND, LoadCursorW, MA_NOACTIVATE, SetCursor,
};

use qingjian_render::{HitRegion, HitTarget, Rendered};

pub(crate) use crate::dispatch::CandidateMouse;

/// 候选窗口上的鼠标操作回调（UI 线程上调，投给工人线程）。
pub type CandidateClicks = Box<dyn Fn(CandidateMouse) + Send>;

/// 本线程候选窗口的点击状态（只有一个候选窗口）。
struct Clicks {
    on_click: CandidateClicks,

    /// 最近一次贴的位图里的点击区域（内容区像素）。
    hits: Vec<HitRegion>,

    /// 内容区左上角的屏幕坐标（物理像素）。
    origin: (i32, i32),

    rendered: Option<Rendered>,

    position: (i32, i32),

    dark: bool,

    hovered: Option<usize>,

    pressed: Option<usize>,
}

thread_local! {
    static CLICKS: RefCell<Option<Clicks>> = const { RefCell::new(None) };
}

/// 建窗口时登记回调。
pub(super) fn install(on_click: CandidateClicks) {
    CLICKS.with(|clicks| {
        *clicks.borrow_mut() = Some(Clicks {
            on_click,
            hits: Vec::new(),
            origin: (0, 0),
            rendered: None,
            position: (0, 0),
            dark: false,
            hovered: None,
            pressed: None,
        });
    });
}

/// 每次贴位图后换上新的点击区域；GDI 画法与收起时传空。
pub(super) fn set_hits(hits: Vec<HitRegion>, origin: (i32, i32)) {
    cancel_press();
    CLICKS.with(|clicks| {
        if let Some(clicks) = clicks.borrow_mut().as_mut() {
            clicks.hits = hits;
            clicks.origin = origin;
            clicks.rendered = None;
            clicks.hovered = None;
            clicks.pressed = None;
        }
    });
}

pub(super) fn cancel_press() {
    let pressed = CLICKS.with(|clicks| {
        clicks
            .borrow_mut()
            .as_mut()
            .and_then(|clicks| clicks.pressed.take())
    });
    if pressed.is_some() {
        let _ = unsafe { ReleaseCapture() };
    }
}

/// 缓存没有鼠标反馈的原图；每次反馈都从原图重画，不叠加颜色。
pub(super) fn set_rendered(rendered: &Rendered, position: (i32, i32), dark: bool) {
    CLICKS.with(|clicks| {
        if let Some(clicks) = clicks.borrow_mut().as_mut() {
            clicks.hits = rendered.hits.clone();
            clicks.origin = (
                position.0 + rendered.content_x as i32,
                position.1 + rendered.content_y as i32,
            );
            clicks.rendered = Some(rendered.clone());
            clicks.position = position;
            clicks.dark = dark;
        }
    });
}

pub(super) fn present(hwnd: HWND) -> windows::core::Result<()> {
    let image = CLICKS.with(|clicks| {
        let clicks = clicks.borrow();
        let clicks = clicks.as_ref()?;
        let mut rendered = clicks.rendered.clone()?;
        if let Some(candidate) = clicks.hovered {
            rendered.speaker_feedback(candidate, clicks.pressed == Some(candidate), clicks.dark);
        }
        Some((rendered, clicks.position))
    });
    if let Some((rendered, position)) = image {
        super::super::layered::present(hwnd, &rendered.pixmap, position)?;
    }
    Ok(())
}

fn cursor_target() -> Option<HitTarget> {
    let mut point = POINT::default();
    unsafe { GetCursorPos(&mut point) }.ok()?;
    target_at(point.x, point.y)
}

pub(super) fn mouse_move(hwnd: HWND) {
    let hovered = match cursor_target() {
        Some(HitTarget::Speak(index)) => Some(index),
        _ => None,
    };
    let changed = CLICKS.with(|clicks| {
        let mut clicks = clicks.borrow_mut();
        let Some(clicks) = clicks.as_mut() else {
            return false;
        };
        let changed = clicks.hovered != hovered;
        clicks.hovered = hovered;
        changed
    });
    let mut tracking = TRACKMOUSEEVENT {
        cbSize: std::mem::size_of::<TRACKMOUSEEVENT>() as u32,
        dwFlags: TME_LEAVE,
        hwndTrack: hwnd,
        ..Default::default()
    };
    let _ = unsafe { TrackMouseEvent(&mut tracking) };
    if changed {
        let _ = present(hwnd);
    }
}

pub(super) fn mouse_leave(hwnd: HWND) {
    CLICKS.with(|clicks| {
        if let Some(clicks) = clicks.borrow_mut().as_mut() {
            clicks.hovered = None;
        }
    });
    let _ = present(hwnd);
}

pub(super) fn capture_changed(hwnd: HWND) {
    CLICKS.with(|clicks| {
        if let Some(clicks) = clicks.borrow_mut().as_mut() {
            clicks.pressed = None;
        }
    });
    let _ = present(hwnd);
}

pub(super) fn set_cursor() -> bool {
    if matches!(cursor_target(), Some(HitTarget::Speak(_))) {
        if let Ok(cursor) = unsafe { LoadCursorW(None, IDC_HAND) } {
            unsafe { SetCursor(Some(cursor)) };
        }
        true
    } else {
        false
    }
}

/// 屏幕上这一点落在哪个目标上。
fn target_at(x: i32, y: i32) -> Option<HitTarget> {
    CLICKS.with(|clicks| {
        let clicks = clicks.borrow();
        let clicks = clicks.as_ref()?;
        let (x, y) = ((x - clicks.origin.0) as f32, (y - clicks.origin.1) as f32);
        clicks
            .hits
            .iter()
            .find(|region| region.contains(x, y))
            .map(|region| region.target)
    })
}

/// `WM_NCHITTEST`：`lparam` 是屏幕坐标（有符号 16 位）。
pub(super) fn hit_test(lparam: LPARAM) -> LRESULT {
    let x = (lparam.0 & 0xFFFF) as i16 as i32;
    let y = ((lparam.0 >> 16) & 0xFFFF) as i16 as i32;
    if target_at(x, y).is_some() {
        LRESULT(HTCLIENT as isize)
    } else {
        LRESULT(HTTRANSPARENT as isize)
    }
}

pub(super) fn mouse_activate() -> LRESULT {
    LRESULT(MA_NOACTIVATE as isize)
}

/// `WM_LBUTTONDOWN`：按当前光标的屏幕位置认目标，交给回调。
pub(super) fn button_down(hwnd: HWND) {
    let Some(target) = cursor_target() else {
        return;
    };
    if let HitTarget::Speak(index) = target {
        CLICKS.with(|clicks| {
            if let Some(clicks) = clicks.borrow_mut().as_mut() {
                clicks.pressed = Some(index);
                clicks.hovered = Some(index);
            }
        });
        unsafe { SetCapture(hwnd) };
        let _ = present(hwnd);
        return;
    }
    dispatch(target);
}

/// 喇叭按下后在同一个喇叭上松开才发送，拖出后松开取消。
pub(super) fn button_up(hwnd: HWND) {
    let target = cursor_target();
    let pressed = CLICKS.with(|clicks| {
        clicks
            .borrow_mut()
            .as_mut()
            .and_then(|clicks| clicks.pressed.take())
    });
    if let Some(index) = pressed {
        let _ = unsafe { ReleaseCapture() };
        let _ = present(hwnd);
        if target == Some(HitTarget::Speak(index)) {
            dispatch(HitTarget::Speak(index));
        }
    }
}

fn dispatch(target: HitTarget) {
    tracing::debug!(?target, "点击候选窗口");
    send(CandidateMouse::Click(target));
}

/// `WM_RBUTTONUP`：右键在候选或译词上就要菜单。
pub(super) fn right_button_up() {
    let mut point = POINT::default();
    if unsafe { GetCursorPos(&mut point) }.is_err() {
        return;
    }
    let offset = match target_at(point.x, point.y) {
        Some(
            HitTarget::Candidate(offset)
            | HitTarget::Translation {
                candidate: offset, ..
            },
        ) => offset,
        Some(HitTarget::MenuItem(_)) | Some(HitTarget::Speak(_)) | None => return,
    };
    send(CandidateMouse::Menu {
        offset,
        point: (point.x, point.y),
    });
}

/// 交给登记的回调。
pub(super) fn send(event: CandidateMouse) {
    CLICKS.with(|clicks| {
        if let Some(clicks) = clicks.borrow().as_ref() {
            (clicks.on_click)(event);
        }
    });
}
