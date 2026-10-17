//! 手机朗读原生表单、配对二维码及音色发送入口。

use windows_reactor::{
    Button, ChildrenControl, ComboBox, ContentControl, EncodedImage, Image, KeyedView,
    LayoutControl, Orientation, StackPanel, ToggleSwitch, View, ViewContext,
};

use super::SpeechPane;
use super::message::Message;
use crate::panel::controls::{field, note, page, section};

pub(super) fn view(pane: &SpeechPane, context: &mut ViewContext<SpeechPane>) -> View {
    let state = &pane.snapshot;
    let available = state.running && !pane.busy;
    let mut fields: Vec<View> = vec![
        note("手机安装支持音色传输的青简朗读 App，先在这里启动连接，再用手机 App 扫码。"),
        StackPanel::new()
            .orientation(Orientation::Horizontal)
            .spacing(12.0)
            .children([
                Button::new()
                    .is_enabled(!pane.busy && !state.running)
                    .on_click(context.message(Message::Run("start")))
                    .content("启动手机连接"),
                Button::new()
                    .is_enabled(available)
                    .on_click(context.message(Message::Run("stop")))
                    .content("停止连接"),
                Button::new()
                    .is_enabled(!pane.busy)
                    .on_click(context.message(Message::Run("status")))
                    .content("刷新状态"),
            ]),
        note(if state.connected {
            "手机已连接"
        } else {
            "手机尚未连接或需要更新手机 App"
        }),
        note(&state.status),
        note(&pane.notice),
    ];
    if !state.qr.is_empty() {
        if let Ok(bytes) = std::fs::read(&state.qr) {
            fields.push(
                Image::new()
                    .width(250.0)
                    .height(250.0)
                    .source_data(EncodedImage::new(bytes))
                    .into(),
            );
        }
        fields.push(note(
            "用手机青简朗读 App 的“扫码连接”扫描。电脑连接工具重启后请扫描新二维码。",
        ));
    }
    if !state.url.is_empty() {
        fields.push(note(&state.url));
    }
    fields.push(section(
        "朗读控制",
        [
            field(
                "启用手机朗读",
                "暂停后保留连接，重新打开即可继续。",
                ToggleSwitch::new()
                    .is_enabled(available)
                    .is_on(state.enabled)
                    .on_toggled(context.callback(Message::Enabled)),
            ),
            field(
                "手机音色",
                "新音色首次选择后会传输并加载，请查看手机状态。",
                ComboBox::new()
                    .is_enabled(available)
                    .items_source(state.choices.iter().map(|(_, label)| label.as_str()))
                    .selected_index(
                        state
                            .choices
                            .iter()
                            .position(|(key, _)| *key == state.selected)
                            .unwrap_or(0),
                    )
                    .on_selection_changed(context.callback(Message::Select)),
            ),
            Button::new()
                .is_enabled(available && state.enabled)
                .on_click(context.message(Message::Run("preview")))
                .content("在手机试听当前音色"),
        ],
    ));
    fields.push(section("添加音色",[
        note("选择已解压的 Sherpa-ONNX 模型文件夹（Kitten、Piper/VITS、Kokoro），包含模型和配套数据。手机连接后即可下载，不需要重新安装 APK；需要新版原生引擎的模型除外。"),
        Button::new().is_enabled(available).on_click(context.message(Message::Run("import"))).content("发送音色文件夹到手机"),
    ]));
    page(
        "手机朗读",
        StackPanel::new().spacing(12.0).keyed_children(
            fields
                .into_iter()
                .enumerate()
                .map(|(i, view)| KeyedView::new(i.to_string(), view)),
        ),
    )
}
