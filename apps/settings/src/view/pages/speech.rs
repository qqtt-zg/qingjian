//! 手机朗读页：连接方式切换（固定隧道/临时公网）、二维码、令牌校验与指引、开机自启与朗读控制。

use bamboo_slip::taffy::{
    AlignContent, AlignItems, Dimension, FlexDirection, JustifyContent, LengthPercentage,
    LengthPercentageAuto, NodeId, Rect, Size, Style,
};
use bamboo_slip::{
    BoxPaint, Button, Color, Fill, RenderError, Segmented, SettingRow, Switch, TextField, Ui,
    Visual, dialog, group, paragraph, section_title,
};

use crate::jobs::speech::{SpeechJob, SpeechMode};
use crate::message::{FieldId, Message};
use crate::system::action::Action;
use crate::view::Page;

/// 输入框宽度（点）。
const FIELD_WIDTH: f32 = 280.0;

/// 引导弹窗宽度（点）。
const GUIDE_DIALOG_WIDTH: f32 = 460.0;

pub fn speech(ui: &mut Ui<Message>, page: &Page) -> Result<Vec<NodeId>, RenderError> {
    let job = &page.settings.speech;
    let state = &job.state;
    let theme = &page.theme;
    let available = job.live && !job.busy;
    let mode = job.mode();
    let mut out = Vec::new();

    // 1. 手机连接
    out.push(section_title(ui, theme, "手机连接")?);

    let connect_detail = if !job.live {
        "隔离配置：手机连接操作已禁用"
    } else if state.connected {
        "已连接至手机青简朗读 App"
    } else if state.running {
        if state.has_named_config {
            "固定隧道服务运行中，等待手机 App 扫码连接"
        } else {
            "临时公网服务运行中，等待手机 App 扫码连接"
        }
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
        .info("手机与电脑通过中继建立连接，在候选出现英文或查看释义时可实时发音。")
        .control(control_node);

    if !state.status.is_empty() && state.status != "手机已连接" {
        service_row = service_row.below(paragraph(ui, theme, &state.status, None)?);
    }

    let mut conn_rows = vec![service_row.build(ui, theme)?];

    // 连接方式切换选择器
    let mode_segmented = Segmented::new(
        &["固定地址", "临时地址"],
        if mode == SpeechMode::Fixed { 0 } else { 1 },
    )
    .disabled(!available)
    .build(ui, theme, "speech-mode", |index| {
        if index == 0 {
            Message::Speech("mode-fixed", None)
        } else {
            Message::Speech("mode-temporary", None)
        }
    })?;

    let mode_row = SettingRow::new("连接方式")
        .detail(if mode == SpeechMode::Fixed {
            "命名隧道固定域名，推荐长期使用"
        } else {
            "临时隧道，重启中继后地址失效"
        })
        .info("固定地址通过 Cloudflare 命名隧道绑定个人域名，扫一次码长期有效，重启或换网络无需重扫，且支持两台电脑轮换；临时地址即开即用。")
        .control(mode_segmented)
        .build(ui, theme)?;

    conn_rows.push(mode_row);

    // 固定模式配置区
    if mode == SpeechMode::Fixed {
        let host_edit = page
            .settings
            .fields
            .get(&FieldId::TunnelHost)
            .map(|e| e.text().trim().to_owned())
            .unwrap_or_else(|| {
                if !state.named_host.is_empty() {
                    state.named_host.clone()
                } else {
                    "speech.qingjian.app".to_owned()
                }
            });

        let token_edit = page
            .settings
            .fields
            .get(&FieldId::TunnelToken)
            .map(|e| e.text().trim().to_owned())
            .unwrap_or_default();

        let host_input = if let Some(edit) = page.settings.fields.get(&FieldId::TunnelHost) {
            TextField::new(edit)
                .placeholder("例如 speech.qingjian.app")
                .width(FIELD_WIDTH)
                .build(
                    ui,
                    theme,
                    ("field", FieldId::TunnelHost),
                    |edit| Message::Edit(FieldId::TunnelHost, edit),
                    None,
                    Some(Message::Cancel(FieldId::TunnelHost)),
                )?
        } else {
            ui.scene().node(Style::default(), Visual::Group, &[])?
        };

        let token_input = if let Some(edit) = page.settings.fields.get(&FieldId::TunnelToken) {
            TextField::new(edit)
                .placeholder("从 Cloudflare 复制的 eyJhIjoi... 令牌")
                .secret(true)
                .width(FIELD_WIDTH)
                .build(
                    ui,
                    theme,
                    ("field", FieldId::TunnelToken),
                    |edit| Message::Edit(FieldId::TunnelToken, edit),
                    None,
                    Some(Message::Cancel(FieldId::TunnelToken)),
                )?
        } else {
            ui.scene().node(Style::default(), Visual::Group, &[])?
        };

        let host_row = SettingRow::new("服务地址")
            .detail("Cloudflare 隧道 Public Hostname 中绑定的域名")
            .control(host_input);

        conn_rows.push(host_row.build(ui, theme)?);

        let mut token_row = SettingRow::new("隧道令牌")
            .detail("创建隧道后页面给出的完整运行 Token，以 eyJ 开头")
            .control(token_input);

        // 校验提示展示
        if !token_edit.is_empty() {
            match SpeechJob::validate_token(&token_edit) {
                Ok(tunnel_id) => {
                    let valid_text = format!("✓ 隧道 ID 校验有效：{tunnel_id}，保存后将自动应用");
                    let valid_node = ui.scene().node(
                        Style::default(),
                        Visual::Text {
                            text: valid_text,
                            style: theme.text(theme.small, 400, Color::rgb(35, 134, 54)),
                        },
                        &[],
                    )?;
                    token_row = token_row.below(valid_node);
                }
                Err(err) => {
                    let err_text = format!("✗ {err}");
                    let err_node = ui.scene().node(
                        Style::default(),
                        Visual::Text {
                            text: err_text,
                            style: theme.text(theme.small, 400, Color::rgb(218, 54, 51)),
                        },
                        &[],
                    )?;
                    token_row = token_row.below(err_node);
                }
            }
        }

        conn_rows.push(token_row.build(ui, theme)?);

        // 操作按钮行：指引、保存并应用、恢复临时
        let guide_btn = Button::new("怎么拿这个？").build(
            ui,
            theme,
            "tunnel-guide",
            Message::Speech("guide-open", None),
        )?;

        let can_save = available && !host_edit.is_empty() && !token_edit.is_empty();
        let payload = serde_json::json!({
            "host": host_edit,
            "token": token_edit,
        })
        .to_string();

        let save_btn = Button::new("保存并应用")
            .primary()
            .disabled(!can_save)
            .build(
                ui,
                theme,
                "named-save",
                Message::Speech("named-save", Some(payload)),
            )?;

        let mut action_buttons = vec![guide_btn, save_btn];

        if state.has_named_config {
            let clear_btn = Button::new("清除固定配置").disabled(!available).build(
                ui,
                theme,
                "named-clear",
                Message::Speech("named-clear", None),
            )?;
            action_buttons.push(clear_btn);
        }

        let action_node = ui.scene().node(
            Style {
                flex_direction: FlexDirection::Row,
                gap: page.gap(8.0),
                ..Style::default()
            },
            Visual::Group,
            &action_buttons,
        )?;

        let action_row = SettingRow::new("配置操作")
            .detail(if state.has_named_config {
                "固定隧道配置已落盘生效；点击保存可覆盖更新"
            } else {
                "填入域名与令牌后点击保存并应用"
            })
            .control(action_node)
            .build(ui, theme)?;

        conn_rows.push(action_row);
    }

    // 二维码展示
    if let Some(qr) = &job.qr {
        let qr_size = page.px(180.0);
        let pad = page.px(10.0);

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
                if state.has_named_config {
                    "固定隧道模式：扫码一次永久保留，关机重启或换网络无需重扫。"
                } else {
                    "临时公网模式：连接工具重启后二维码将自动更新，手机需重新扫码。"
                },
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

    // 开机自动连接
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
        "未配置固定地址：配置固定隧道后可开启开机自启"
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

    // 3. 引导弹窗
    if job.guide_open {
        guide_dialog(ui, theme, page)?;
    }

    Ok(out)
}

/// 弹出获取 Cloudflare 隧道的步骤指引卡片
fn guide_dialog(ui: &mut Ui<Message>, theme: &bamboo_slip::WidgetTheme, page: &Page) -> Result<(), RenderError> {
    let title = ui.scene().node(
        Style::default(),
        Visual::Text {
            text: "获取 Cloudflare 免费固定隧道".to_owned(),
            style: theme.text(theme.font.size + 2.0, 600, theme.colors.text),
        },
        &[],
    )?;

    let steps = [
        "1. 打开 Cloudflare 后台，进入 Zero Trust → Networks → Tunnels。",
        "2. 点击 Create a tunnel，选择 Cloudflared，起一个名字（如 qingjian-home）。",
        "3. 创建后在安装命令中找到 --token 后的字符串（以 eyJ 开头），将其复制。",
        "4. 在同页 Public Hostname 下添加域名（如 speech.yourdomain.com），Service 填 http://127.0.0.1:21835（切勿填 localhost）。",
        "5. 将域名和令牌粘贴到设置页，点击「保存并应用」即可。",
    ];

    let mut children = vec![title];
    for step in steps {
        children.push(paragraph(ui, theme, step, None)?);
    }

    let dashboard_btn = Button::new("打开 Cloudflare 隧道后台").build(
        ui,
        theme,
        "open-cf-dashboard",
        Message::Run(Action::OpenTunnelDashboard),
    )?;

    let close_btn = Button::new("我知道了").primary().build(
        ui,
        theme,
        "close-guide",
        Message::Speech("guide-close", None),
    )?;

    let buttons_node = ui.scene().node(
        Style {
            flex_direction: FlexDirection::Row,
            justify_content: JustifyContent::FLEX_END,
            align_items: AlignItems::CENTER,
            gap: page.gap(10.0),
            margin: Rect {
                top: LengthPercentageAuto::length(page.px(8.0)),
                ..Rect::zero()
            },
            ..Style::default()
        },
        Visual::Group,
        &[dashboard_btn, close_btn],
    )?;

    children.push(buttons_node);

    dialog(ui, theme, "tunnel-guide-dialog", GUIDE_DIALOG_WIDTH, &children)
}
