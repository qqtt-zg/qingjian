# Android 标题栏与启动图标：0.2.17-dev

对应 [已确认的 HTML](mobile-header-preview.html)。沿用 Kotlin Views，不增加 App 依赖。

## 主页面与子页面

学习、词库、AI 老师共用 `MainHeader`：标题占剩余宽度，右侧固定为 76dp 连接摘要和 44dp 扫码按钮。学习页在摘要左侧放 44dp 设置；AI 页在标题左侧放 44dp 会话菜单。统一高度与内边距，控制位置不随标题、按钮数量或列表滚动改变。

子页面只保留原生返回与标题，设置目录移除连接条及重复的电脑连接行。声音控制继续由朗读设置页负责。扫码宿主 `MainActivity` 保留原详细控制，接收配对结果后返回来源页；不改变配对协议。

## 状态与连接面板

`ConnectionStatus` 从接收服务独立的 `connection_status` 分类为已连接、连接中、配对失效、未连接。电脑暂停但保留连接属于已连接；不把播放完成或生成音频当作连接断开。

`ConnectionPanel` 使用原生底部 Dialog，显示完整说明、最近接收与播放。复用 `ConnectionControls` 重连、停止；无有效地址或正在连接时禁用重连，失效时给出重新扫码。停止不清除地址或学习记录。偏好监听随显示注册，关闭或标题栏销毁时注销；关闭面板不停止接收。

## 应用图标

采用 HTML 品牌标识的深绿 `#225746` 背景和浅色「简」字。`app-icon.svg` 保存可编辑源图；Android 前景为矢量字形，背景独立，由系统按圆形或圆角矩形裁切。API 33 起提供 monochrome 层；不在应用内额外绘制启动器遮罩。

「简」字轮廓来自 Noto Serif SC Regular（Google Fonts 上游），只分发单字轮廓，不打包字体；SIL OFL 1.1 许可保留在 `assets/licenses/noto-serif-sc-OFL.txt`，并加入第三方说明。字体来源：https://github.com/google/fonts/tree/main/ofl/notoserifsc 。

自适应图标依据 [Android 官方文档](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。图标、包名、签名互相独立；此版包名与签名保持原值，不改变数据目录。
