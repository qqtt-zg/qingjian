//! 手机朗读页：本机二维码、连接与朗读控制、音色选择和发送；提示紧邻操作。

use bamboo_slip::taffy::{
    AlignContent, AlignItems, Dimension, FlexDirection, LengthPercentage, NodeId, Rect, Size, Style,
};
use bamboo_slip::{
    BoxPaint, Button, Color, Fill, RenderError, SettingRow, Switch, Ui, Visual, group, paragraph,
    section_title,
};

use crate::message::Message;
use crate::view::Page;

pub fn speech(ui: &mut Ui<Message>, page: &Page) -> Result<Vec<NodeId>, RenderError> {
    let job = &page.settings.speech;
    let state = &job.state;
    let theme = &page.theme;
    let available = job.live && !job.busy;
    let mut out = Vec::new();

    // 1. 手机连接
    out.push(section_title(ui, theme, "手机连接")?);

    let connect_detail = if !job.live {
        "隔离配置：手机连接操作已禁用"
    } else if state.connected {
        "已连接至手机青简朗读 App"
    } else if state.running {
        "服务运行中，等待手机 App 扫码连接"
    } else {
        "未启动：启动电脑中继服务以与手机配对"
    };

    let control_node = if state.running {
        let stop_btn = Button::new("停止连接").disabled(!available).build(
            ui,
            theme,
            "stop",
            Message::Speech("stop", None),
        )?;
        let status_btn = Button::new("刷新状态").disabled(!available).build(
            ui,
            theme,
            "status",
            Message::Speech("status", None),
        )?;
        ui.scene().node(
            Style {
                flex_direction: FlexDirection::Row,
                gap: page.gap(8.0),
                ..Style::default()
            },
            Visual::Group,
            &[stop_btn, status_btn],
        )?
    } else {
        Button::new("启动手机连接").disabled(!available).build(
            ui,
            theme,
            "start",
            Message::Speech("start", None),
        )?
    };

    let mut service_row = SettingRow::new("连接服务")
        .detail(connect_detail)
        .info("电脑与手机在同一局域网下通过配对中继通信，可在候选出现英文时直接通过手机播放发音。")
        .control(control_node);

    if !state.status.is_empty() && state.status != "手机已连接" {
        service_row = service_row.below(paragraph(ui, theme, &state.status, None)?);
    }

    let mut conn_rows = vec![service_row.build(ui, theme)?];

    // 如果生成了二维码，在连接卡片内增加优雅的配对扫码面板
    if let Some(qr) = &job.qr {
        let qr_size = page.px(180.0);
        let pad = page.px(10.0);

        // 二维码节点
        let qr_node = ui.scene().node(
            Style {
                size: Size {
                    width: Dimension::length(qr_size),
                    height: Dimension::length(qr_size),
                },
                flex_shrink: 0.0,
                ..Style::default()
            },
            Visual::Icon {
                icon: qr.clone(),
                size: qr_size,
                color: Color::rgb(0, 0, 0),
            },
            &[],
        )?;

        // 白色衬底容器（保证在深色主题下也有足够静区能被手机扫描识别）
        let qr_card = ui.scene().node(
            Style {
                padding: Rect {
                    left: LengthPercentage::length(pad),
                    right: LengthPercentage::length(pad),
                    top: LengthPercentage::length(pad),
                    bottom: LengthPercentage::length(pad),
                },
                flex_shrink: 0.0,
                ..Style::default()
            },
            Visual::Box(BoxPaint {
                fill: Some(Fill::Solid(Color::rgb(255, 255, 255))),
                radius: page.px(10.0),
                border: None,
            }),
            &[qr_node],
        )?;

        let mut guide_nodes = vec![
            paragraph(
                ui,
                theme,
                "请打开手机端「青简朗读」App 扫描左侧二维码完成连接。",
                None,
            )?,
            paragraph(
                ui,
                theme,
                "连接工具重启后二维码将自动更新，手机需重新扫码。",
                None,
            )?,
        ];
        if !state.url.is_empty() {
            guide_nodes.push(paragraph(
                ui,
                theme,
                &format!("服务地址：{}", state.url),
                None,
            )?);
        }

        let guide_col = ui.scene().node(
            Style {
                flex_direction: FlexDirection::Column,
                gap: page.gap(6.0),
                flex_shrink: 1.0,
                justify_content: AlignContent::CENTER,
                ..Style::default()
            },
            Visual::Group,
            &guide_nodes,
        )?;

        let qr_row = ui.scene().node(
            Style {
                flex_direction: FlexDirection::Row,
                gap: page.gap(16.0),
                align_items: AlignItems::CENTER,
                padding: Rect {
                    left: LengthPercentage::length(page.px(16.0)),
                    right: LengthPercentage::length(page.px(16.0)),
                    top: LengthPercentage::length(page.px(14.0)),
                    bottom: LengthPercentage::length(page.px(14.0)),
                },
                ..Style::default()
            },
            Visual::Group,
            &[qr_card, guide_col],
        )?;

        conn_rows.push(qr_row);
    }

    let autostart_switch = Switch::new(state.autostart)
        .disabled(!available || !state.has_named_config)
        .build(
            ui,
            theme,
            "speech-autostart",
            Message::Speech(
                if state.autostart {
                    "autostart-disable"
                } else {
                    "autostart-enable"
                },
                None,
            ),
        )?;

    let autostart_detail = if state.has_named_config {
        "开机后在后台静默启动命名隧道中继，无需每次打开设置"
    } else {
        "未配置命名隧道：配置固定隧道后可开启开机自启"
    };

    let autostart_row = SettingRow::new("开机自动连接")
        .detail(autostart_detail)
        .control(autostart_switch)
        .build(ui, theme)?;

    conn_rows.push(autostart_row);

    out.push(group(ui, theme, &conn_rows)?);

    // 2. 朗读控制
    out.push(section_title(ui, theme, "朗读控制")?);

    let switch = Switch::new(state.enabled)
        .disabled(!available || !state.running)
        .build(
            ui,
            theme,
            "speech-enabled",
            Message::Speech(if state.enabled { "pause" } else { "enable" }, None),
        )?;

    let preview = Button::new("在手机试听")
        .disabled(!available || !state.running || !state.enabled)
        .build(
            ui,
            theme,
            "speech-preview",
            Message::Speech("preview", None),
        )?;

    let speech_rows = [
        SettingRow::new("启用候选朗读")
            .detail("候选出现英文词或查看释义时，可在手机端实时发音")
            .control(switch)
            .build(ui, theme)?,
        SettingRow::new("音色试听")
            .detail("向已连接的手机发送示例语音；发声音色与语速请在手机端「朗读设置」中调整")
            .control(preview)
            .build(ui, theme)?,
    ];
    out.push(group(ui, theme, &speech_rows)?);

    Ok(out)
}
