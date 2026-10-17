//! 译词旁的小喇叭：自己画矢量轮廓，避免系统 emoji 字体把它变成彩色图案。

use bamboo_slip::tiny_skia::PathBuilder;

use crate::{HitTarget, Rendered};
use bamboo_slip::{Canvas, Color};

#[derive(Debug)]
pub(crate) struct Speaker;

impl bamboo_slip::Icon for Speaker {
    fn draw(&self, canvas: &mut Canvas, x: f32, y: f32, size: f32, color: Color) {
        draw_speaker(canvas, x, y, size, color);
    }
}

impl Rendered {
    /// 在已画的位图上追加喇叭悬停 / 按下反馈，不改变布局与命中区域。
    pub fn speaker_feedback(&mut self, candidate: usize, pressed: bool, dark: bool) {
        let Some(hit) = self
            .hits
            .iter()
            .find(|hit| hit.target == HitTarget::Speak(candidate))
        else {
            return;
        };
        let (x, y) = (hit.x + self.content_x as f32, hit.y + self.content_y as f32);
        let (background, foreground) = match (dark, pressed) {
            (false, false) => (Color::rgb(222, 237, 215), Color::rgb(55, 103, 67)),
            (false, true) => (Color::rgb(55, 103, 67), Color::rgb(255, 255, 255)),
            (true, false) => (Color::rgb(53, 75, 57), Color::rgb(192, 226, 183)),
            (true, true) => (Color::rgb(145, 197, 144), Color::rgb(21, 33, 25)),
        };
        let mut canvas = Canvas::from_pixmap(self.pixmap.clone());
        canvas.fill_round_rect(x, y, hit.width, hit.height, 4.0 * self.scale, background);
        let size = 14.0 * self.scale;
        let down = if pressed { self.scale } else { 0.0 };
        draw_speaker(
            &mut canvas,
            x + down,
            y + (hit.height - size) / 2.0 + down,
            size,
            foreground,
        );
        self.pixmap = canvas.into_pixmap();
    }
}

pub(crate) fn draw_speaker(canvas: &mut Canvas, x: f32, y: f32, size: f32, color: Color) {
    let mut body = PathBuilder::new();
    body.move_to(x + size * 0.10, y + size * 0.36);
    body.line_to(x + size * 0.30, y + size * 0.36);
    body.line_to(x + size * 0.54, y + size * 0.15);
    body.line_to(x + size * 0.54, y + size * 0.85);
    body.line_to(x + size * 0.30, y + size * 0.64);
    body.line_to(x + size * 0.10, y + size * 0.64);
    body.close();
    if let Some(path) = body.finish() {
        canvas.stroke_outline(&path, (size * 0.08).max(1.0), color);
    }
    for (left, right, top, bottom) in [(0.68, 0.84, 0.32, 0.68), (0.79, 1.06, 0.14, 0.86)] {
        let mut wave = PathBuilder::new();
        wave.move_to(x + size * left, y + size * top);
        wave.cubic_to(
            x + size * right,
            y + size * 0.40,
            x + size * right,
            y + size * 0.60,
            x + size * left,
            y + size * bottom,
        );
        if let Some(path) = wave.finish() {
            canvas.stroke_outline(&path, (size * 0.075).max(1.0), color);
        }
    }
}
