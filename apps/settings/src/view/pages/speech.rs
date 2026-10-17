//! 手机朗读页：本机二维码、连接与朗读控制、音色选择和发送；提示紧邻操作。

use bamboo_slip::taffy::{Dimension, NodeId, Size, Style};
use bamboo_slip::{
    Button, RenderError, SettingRow, Switch, Ui, Visual, group, paragraph, section_title,
};

use crate::message::{Message, Popup};
use crate::view::Page;
use crate::view::parts::controls::select;

pub fn speech(ui: &mut Ui<Message>, page: &Page) -> Result<Vec<NodeId>, RenderError> {
    let job = &page.settings.speech;
    let state = &job.state;
    let theme = &page.theme;
    let available = job.live && !job.busy;
    let mut out = vec![section_title(ui, theme, "连接手机")?];
    let mut rows = Vec::new();
    for (action, label, disabled) in [
        ("start", "启动手机连接", state.running),
        ("stop", "停止连接", !state.running),
        ("status", "刷新状态", false),
    ] {
        let button = Button::new(label).disabled(!available || disabled).build(
            ui,
            theme,
            action,
            Message::Speech(action, None),
        )?;
        rows.push(SettingRow::new(label).control(button).build(ui, theme)?);
    }
    out.push(group(ui, theme, &rows)?);
    let status = if !job.live {
        "隔离配置：手机连接操作已禁用"
    } else if state.connected {
        "手机已连接"
    } else {
        "手机尚未连接或需要更新 App"
    };
    out.push(paragraph(ui, theme, status, None)?);
    out.push(paragraph(ui, theme, &state.status, None)?);
    if let Some(qr) = &job.qr {
        let size = page.px(250.0);
        out.push(ui.scene().node(
            Style {
                size: Size {
                    width: Dimension::length(size),
                    height: Dimension::length(size),
                },
                flex_shrink: 0.0,
                ..Style::default()
            },
            Visual::Icon {
                icon: qr.clone(),
                size,
                color: theme.colors.text,
            },
            &[],
        )?);
        out.push(paragraph(
            ui,
            theme,
            "用手机青简朗读 App 扫码连接。连接工具重启后请扫描新码。",
            None,
        )?);
    }
    if !state.url.is_empty() {
        out.push(paragraph(ui, theme, &state.url, None)?);
    }
    out.push(section_title(ui, theme, "朗读与音色")?);
    let switch = Switch::new(state.enabled)
        .disabled(!available || !state.running)
        .build(
            ui,
            theme,
            "speech-enabled",
            Message::Speech(if state.enabled { "pause" } else { "enable" }, None),
        )?;
    let selected = state
        .choices
        .iter()
        .find(|(key, _)| *key == state.selected)
        .map(|(_, label)| label.as_str())
        .unwrap_or("请先连接手机");
    let options: Vec<&str> = state
        .choices
        .iter()
        .map(|(_, label)| label.as_str())
        .collect();
    let voices = select(
        ui,
        page,
        Popup::SpeechVoice,
        (selected, &options),
        245.0,
        !available || !state.running,
    )?;
    let preview = Button::new("在手机试听")
        .disabled(!available || !state.running || !state.enabled)
        .build(
            ui,
            theme,
            "speech-preview",
            Message::Speech("preview", None),
        )?;
    let rows = [
        SettingRow::new("启用手机朗读")
            .control(switch)
            .build(ui, theme)?,
        SettingRow::new("手机音色")
            .control(voices)
            .build(ui, theme)?,
        SettingRow::new("试听当前音色")
            .control(preview)
            .build(ui, theme)?,
    ];
    out.push(group(ui, theme, &rows)?);
    out.push(section_title(ui, theme, "添加音色")?);
    out.push(paragraph(
        ui,
        theme,
        "App 0.1.5 已内置 af_heart，选女声 1 即可。其他兼容模型可发送解压后的模型文件夹。",
        None,
    )?);
    let import = Button::new("发送音色文件夹到手机")
        .disabled(!available || !state.running)
        .build(ui, theme, "speech-import", Message::Speech("import", None))?;
    out.push(import);
    out.push(paragraph(ui, theme, &job.notice, None)?);
    out.push(paragraph(ui, theme, &state.status, None)?);
    Ok(out)
}
