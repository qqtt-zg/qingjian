//! 标点 / 数字按当前键盘布局解析，测键时不改变 dead key 状态。

use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, GetKeyboardLayout, GetKeyboardState, HKL, MAPVK_VK_TO_VSC, MapVirtualKeyExW,
    ToUnicodeEx, VK_NUMLOCK,
};

pub(super) fn character(vk: u32) -> Option<char> {
    // 主键盘数字、小键盘数字与运算符（不解出字符的键 would_eat 会直接放行给应用）、OEM 标点和空格。
    if !matches!(vk, 0x30..=0x39 | 0x60..=0x6F | 0xBA..=0xC0 | 0xDB..=0xDE | 0x20) {
        return None;
    }
    let mut state = [0; 256];
    if unsafe { GetKeyboardState(&mut state) }.is_ok()
        && let Some(ch) = resolve(vk, &state, unsafe { GetKeyboardLayout(0) })
    {
        return Some(ch);
    }
    // 特殊窗口或异步状态下 ToUnicodeEx 偶尔取不到字符时的物理键位兜底
    match vk {
        0x60..=0x69 if unsafe { GetKeyState(VK_NUMLOCK.0 as i32) } & 1 != 0 => {
            Some((b'0' + (vk - 0x60) as u8) as char)
        }
        0x6A => Some('*'),
        0x6B => Some('+'),
        0x6D => Some('-'),
        0x6E if unsafe { GetKeyState(VK_NUMLOCK.0 as i32) } & 1 != 0 => Some('.'),
        0x6F => Some('/'),
        _ => None,
    }
}

fn resolve(vk: u32, state: &[u8; 256], layout: HKL) -> Option<char> {
    let scan = unsafe { MapVirtualKeyExW(vk, MAPVK_VK_TO_VSC, Some(layout)) };
    let mut buffer = [0; 8];
    // Windows 10 1607 起，bit 2 可避免测键修改 dead key 状态。
    let count = unsafe { ToUnicodeEx(vk, scan, state, &mut buffer, 1 << 2, Some(layout)) };
    // 仅接受单个 UTF-16 单元；dead key、无映射和较长结果不生成字符。
    if count != 1 {
        return None;
    }
    char::from_u32(u32::from(buffer[0]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        GetKeyboardLayoutList, VK_NUMLOCK, VK_SHIFT,
    };

    fn loaded_layouts() -> Vec<HKL> {
        let count = unsafe { GetKeyboardLayoutList(None) };
        assert!(count > 0);
        let mut layouts = vec![HKL::default(); count as usize];
        let copied = unsafe { GetKeyboardLayoutList(Some(&mut layouts)) };
        assert!(copied > 0);
        layouts.truncate(copied as usize);
        layouts
    }

    fn existing_layout(id: u32) -> HKL {
        assert_eq!(
            std::env::var("QINGJIAN_ISOLATED_KEYBOARD_TESTS").as_deref(),
            Ok("1"),
            "真实键盘状态测试只能在专用 Windows 测试环境中显式开启"
        );
        loaded_layouts()
            .into_iter()
            .find(|layout| layout.0 as usize as u32 == id)
            .unwrap_or_else(|| panic!("测试环境须预先配置布局 {id:08x}；测试不会自动加载"))
    }

    #[test]
    #[ignore = "需要隔离 Windows 环境和预先配置的美式、德语布局"]
    fn punctuation_and_digits_follow_layout() {
        let us = existing_layout(0x04090409);
        let de = existing_layout(0x04070407);
        let mut state = [0; 256];
        assert_eq!(resolve(0xBA, &state, us), Some(';'));
        assert_eq!(resolve(0x32, &state, de), Some('2'));
        assert_eq!(resolve(0x20, &state, us), Some(' '));
        state[VK_SHIFT.0 as usize] = 0x80;
        assert_eq!(resolve(0xBA, &state, us), Some(':'));
        assert_eq!(resolve(0xBB, &state, us), Some('+'));
        assert_eq!(resolve(0xBB, &state, de), Some('*'));
        assert_eq!(resolve(0x32, &state, us), Some('@'));
        assert_eq!(resolve(0x32, &state, de), Some('"'));
    }

    /// 小键盘数字与运算符也要能解出字符：V 模式（表达式模式）里敲小键盘才会进缓冲区。
    /// 关掉 NumLock 时系统上报的 vk 是导航键（VK_INSERT / VK_END…），到不了这里。
    #[test]
    #[ignore = "需要隔离 Windows 环境和预先配置的美式布局"]
    fn keypad_digits_and_operators_resolve() {
        let us = existing_layout(0x04090409);
        let mut state = [0; 256];
        state[VK_NUMLOCK.0 as usize] = 0x01;
        for (vk, expected) in [
            (0x60, '0'),
            (0x61, '1'),
            (0x62, '2'),
            (0x63, '3'),
            (0x64, '4'),
            (0x65, '5'),
            (0x66, '6'),
            (0x67, '7'),
            (0x68, '8'),
            (0x69, '9'),
            (0x6A, '*'),
            (0x6B, '+'),
            (0x6D, '-'),
            (0x6E, '.'),
            (0x6F, '/'),
        ] {
            assert_eq!(resolve(vk, &state, us), Some(expected), "vk={vk:#x}");
        }
    }

    #[test]
    #[ignore = "修改 dead key 状态，仅在预先配置国际键盘的隔离 Windows 环境运行"]
    fn dead_key_lookup_does_not_change_next_character() {
        let intl = existing_layout(0xf0010409);
        let state = [0; 256];
        for _ in 0..2 {
            assert_eq!(resolve(0xDE, &state, intl), None);
            // 每次预读后立即检查，避免两次 dead key 抵消副作用。
            assert_eq!(resolve(0x45, &state, intl), Some('e'));
        }
        assert_eq!(resolve(0, &state, intl), None);
        let mut buffer = [0; 8];
        // 与上面的重音检查串行：内核键盘缓冲会让并行测试互相影响。
        let dead = unsafe { ToUnicodeEx(0xDE, 0, &state, &mut buffer, 0, Some(intl)) };
        let result = resolve(0x31, &state, intl);
        // 清掉待组合的重音后再断言。
        let count = unsafe { ToUnicodeEx(0x31, 0, &state, &mut buffer, 0, Some(intl)) };
        assert!(dead < 0);
        assert_eq!(count, 2);
        assert_eq!(result, None);
    }

    #[test]
    fn other_keys_are_not_resolved() {
        for vk in [0x41, 0x70, 0x0D, 0x14] {
            assert_eq!(character(vk), None);
        }
    }

    #[test]
    fn current_layout_lookup_keeps_loaded_layouts_unchanged() {
        let before = loaded_layouts();
        let current = unsafe { GetKeyboardLayout(0) };
        let state = [0; 256];
        for key in [0x31, 0x32, 0xBA, 0xDE] {
            let _ = resolve(key, &state, current);
        }
        assert_eq!(loaded_layouts(), before);
    }

    #[test]
    #[ignore = "切换真实键盘状态，仅在隔离 Windows 环境显式运行"]
    fn event_reads_thread_layout_and_keyboard_state() {
        use crate::com::key::event::to_key_event;
        use crate::com::service::TextService;
        use windows::Win32::Foundation::{LPARAM, WPARAM};
        use windows::Win32::UI::Input::KeyboardAndMouse::{
            ActivateKeyboardLayout, SetKeyboardState, VK_CAPITAL, VK_CONTROL, VK_LSHIFT,
        };
        use windows::Win32::UI::TextServices::ITfKeyEventSink;
        use windows::core::ComObject;

        let layouts = [
            (existing_layout(0x04090409), '+'),
            (existing_layout(0x04070407), '*'),
        ];
        let original_layout = unsafe { GetKeyboardLayout(0) };
        let mut original_state = [0; 256];
        unsafe { GetKeyboardState(&mut original_state) }.unwrap();
        // 隔离环境中才允许切换；即使断言失败，也先恢复布局和按键状态。
        let result = std::panic::catch_unwind(|| {
            let service = ComObject::new(TextService::new());
            let sink: ITfKeyEventSink = service.to_interface();
            for (layout, expected) in layouts {
                unsafe { ActivateKeyboardLayout(layout, Default::default()) }.unwrap();
                let mut state = [0; 256];
                state[VK_SHIFT.0 as usize] = 0x80;
                state[VK_LSHIFT.0 as usize] = 0x80;
                unsafe { SetKeyboardState(&state) }.unwrap();
                for _ in 0..2 {
                    let event = to_key_event(0xBB, true);
                    assert_eq!(event.character, Some(expected));
                    assert!(event.modifiers.shift);
                    assert!(event.modifiers.english_mode);
                    assert!(
                        unsafe { sink.OnTestKeyDown(None, WPARAM(0xBB), LPARAM(0)) }
                            .unwrap()
                            .as_bool()
                    );
                }
                state[VK_SHIFT.0 as usize] = 0;
                state[VK_LSHIFT.0 as usize] = 0;
                state[VK_CAPITAL.0 as usize] = 1;
                state[VK_CONTROL.0 as usize] = 0x80;
                unsafe { SetKeyboardState(&state) }.unwrap();
                let event = to_key_event(0x31, false);
                assert!(!event.modifiers.shift);
                assert!(event.modifiers.caps);
                assert!(event.modifiers.ctrl);
                assert_eq!(event.virtual_key, 0x31);
                assert_eq!(to_key_event(0x41, false).character, Some('A'));
                assert!(
                    !unsafe { sink.OnTestKeyDown(None, WPARAM(0x31), LPARAM(0)) }
                        .unwrap()
                        .as_bool()
                );
            }
        });
        unsafe { ActivateKeyboardLayout(original_layout, Default::default()) }.unwrap();
        unsafe { SetKeyboardState(&original_state) }.unwrap();
        result.unwrap();
    }
}
