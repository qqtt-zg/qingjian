//! 将本机配对二维码 PNG 按像素块画到新版设置中，不发送给外部图片服务。

use bamboo_slip::{Canvas, Color, Icon, Pixmap};

#[derive(Debug)]
pub struct Qr(Pixmap);

impl Qr {
    pub fn load(path: &str) -> Option<Self> {
        Pixmap::load_png(path).ok().map(Self)
    }
}

impl Icon for Qr {
    fn draw(&self, canvas: &mut Canvas, x: f32, y: f32, size: f32, _color: Color) {
        let width = self.0.width();
        let ratio = size / width as f32;
        canvas.fill_rect(x, y, size, size, Color::rgb(255, 255, 255));
        // desktop.py 的二维码每个模块是 5×5，逐模块画避免缩放插值模糊。
        for row in (0..self.0.height()).step_by(5) {
            for col in (0..width).step_by(5) {
                if self.0.pixels()[(row * width + col) as usize].red() < 128 {
                    canvas.fill_rect(
                        x + col as f32 * ratio,
                        y + row as f32 * ratio,
                        5.0 * ratio,
                        5.0 * ratio,
                        Color::rgb(0, 0, 0),
                    );
                }
            }
        }
    }
}
