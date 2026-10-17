# 手机朗读连接工具

供 `personal-tts` 修改版使用。英文译词旁显示小喇叭，点击才发送英文；不选词、不替换输入、不消耗拼音，不自动朗读上屏。两个义项按顺序读出，不读 `pron.` / `n.` 等词性。

## 使用

## 青简设置中的「手机朗读」（推荐）

本地修改版设置已加入原生「手机朗读」页，手机先覆盖安装 App 0.1.4 一次。后续兼容音色只传数据，不需要重装 APK：

1. 打开青简设置 → 手机朗读 → 启动手机连接。连接建立后，二维码直接显示在设置页，无需另开命令行。
2. 手机 App 扫码连接。设置页显示手机在线状态、播放状态和音色接收进度。
3. 在设置页选择音色、试听，或开关「启用手机朗读」。暂停保留连接，停止连接会关闭本次转发；下次启动需重新扫码。
4. 下载并解压兼容的 Sherpa-ONNX 模型，在设置页点「发送音色文件夹到手机」，选择其模型文件夹。打包后手机自动下载、校验并加载，完成后可选择模型里的音色。

目前支持 Kitten、Piper/VITS、Kokoro 的单个 ONNX 模型目录，包含 `tokens.txt`、配套 `voices.bin` / `lexicon.txt`（若需要）及 `espeak-ng-data`（若需要）。Kitten / Kokoro 文件夹名应包含对应类型；特殊结构可使用 `voicepack.json` 描述。需要新版原生推理库的模型仍需更新 App。已实测 Kitten 模型完整传输与加载，其他具体模型仍应试听确认。

文件夹中不能包含链接、可执行文件或路径越界。包最大 512 MiB，解压数据最大 768 MiB、8000 个条目；手机空间不足或校验 / 加载失败会显示原因，保留或恢复内置音色。模型包缓存于电脑 `%APPDATA%\Qingjian\speech\packs` 与手机应用私有目录，不会将模型写入 APK。

原生页通过随包 `desktop.py` 后台管理本机服务，二维码 PNG 在本机生成。此个人开发版依赖本机 Python 和 Node.js；源码部署须将工具目录与 cloudflared 一起放到修改版目录，设置操作不会显示终端窗口。

## 原有命令行与网页方式

1. 使用同一份修改源码构建的 Server 与 TSF DLL，候选窗口选择“青简渲染器”。不要只覆盖安装目录中的一个组件。
2. 电脑需 Python 3.10 或以上，在仓库根目录执行：

   ```powershell
   pwsh -File .\tools\speech-relay\start.ps1
   ```

3. 电脑浏览器会打开配对页。复制当前局域网的“手机连接地址”，发到安卓手机，用 Chrome 或 Edge 打开。电脑接网线、手机连同一路由器 Wi-Fi 也可以。
4. 蓝牙耳机连接手机；选择英文音色，点“启用朗读”，先听到 Ready。
5. 保持手机页在前台。电脑输入拼音后点击需要听的那一行小喇叭，没有英文译词的行不显示喇叭。

同词可重复点击；等待中的旧点击由新点击替换，避免积压。手机页面支持重听与语速调整。

**App 扫码连接（推荐个人试用）**：用 `start.ps1 -Internet` 启动，电脑配对页会显示二维码；在青简朗读 App 0.1.1 或更新版点击“扫码连接”，允许相机后扫描即可自动连接。二维码包含完整 HTTPS 地址及接收凭证，由本机随附的 qrcode-generator 1.4.4 生成，不调用外部二维码服务。电脑工具重启后需要扫描新码；同次连接期间重开 App 可复用。扫码无效或取消不会覆盖原地址。局域网 HTTP 地址不生成 App 配对码。

喇叭悬停时显示浅色背景和手形光标，按住时加深；在同一个喇叭上松开才发送，拖出后松开取消。候选窗随后显示“正在发送朗读请求…”，成功后提示“已发送，请在手机确认播放”，失败则显示“朗读发送失败，请检查连接工具”。“已发送”表示电脑连接工具已接受请求，不表示手机已经发声。仍加载旧版 DLL 的应用也可点击朗读，无需为朗读重启所有应用；旧版原有的鼠标上屏限制保留。

**首版需要前台运行。** 后台、锁屏时主动暂停，返回后重新启用；尚不保证锁屏长期接收。没有英文音色时，请在手机系统文字转语音设置中安装；语音是否联网取决于所选语音引擎。

系统英文试听正常，但浏览器没有列出音色时：刷新本页，点击“重新检测音色”，或选择“系统自动选择英文”后点“试播英文”。空音色列表不会禁用“启用朗读”；自动选择时以 `en-US` 请求系统语音，是否能够发声仍取决于浏览器与系统引擎。页面打开后会短暂重试检测，返回前台时也重新读取音色。播放失败会显示浏览器错误并暂停接收。若按钮仍不可用，确认链接包含完整配对部分，且使用支持语音朗读的独立浏览器；系统试播正常而网页失败时可改用独立 Chrome 或 Edge 重试。

首次防火墙提示时仅允许受信任的专用网络；默认 TCP 21835。打不开时检查局域网、防火墙和 Wi-Fi 客户端隔离。工具不自动修改防火墙。关闭终端停止转发，配对地址随之失效。不要公开配对地址或将此 HTTP 服务转发到公网。

## 不同网络：Tailscale 固定连接

个人长期使用可选此模式，无需自备域名或云服务器。电脑与手机安装官方 Tailscale、登录同一私人网络并开启连接；首次在 Tailscale 管理页面启用 HTTPS / Serve（若命令给出启用链接，先完成再重试）。不要启用 Funnel。

```powershell
pwsh -File .\tools\speech-relay\start.ps1 -Tailscale
```

工具使用专用 HTTPS 端口 8443，通过 Tailscale Serve 将请求转到本机 `127.0.0.1:21835`，只对获准访问的 tailnet 设备开放。Serve 使用后台持久配置，停止工具后该地址暂时返回连接失败，重新启动工具即可恢复；不会自动关闭 Tailscale 或清理其他服务。若 8443 已有其他配置，工具拒绝覆盖，可以用 `-TailscalePort 9443` 选择专用端口。也拒绝复用已公开启用 Funnel 的端口。

首次将配对页里的完整 `https://电脑名.网络名.ts.net:8443/#…` 地址粘贴到「青简朗读」App，之后同一电脑同一端口重启可继续使用。现有 App 已接受此 HTTPS 地址，无需更新安装包。手机与电脑需要保持 Tailscale 连接；手机已有 VPN 时需检查能否同时使用。重装 Tailscale、更换设备、域名变化或主动重置配对后，需要更新地址。

此模式把接收与发布凭证、事件序号保存在 `%APPDATA%\Qingjian\speech\pairing.json`，不保存英文正文。事件序号也持久保存，避免服务重启后手机仍按旧序号等待。退出仅删除本次 `connection.toml`；不要删除 `pairing.json`，否则下次会重新配对。需要撤销旧链接时，停止当前连接工具，再运行：

```powershell
pwsh -File .\tools\speech-relay\start.ps1 -Tailscale -ResetPairing
```

凭证文件损坏不会自动重置；请检查文件或主动重置。电脑登录用户的私有数据目录用于保存凭证，不应共享该文件。若要避免 Tailscale 设备定期重新认证，可在管理页面对个人可信设备设置 Disable Key Expiry；这是 Tailscale 的设备设置，与本工具的配对凭证不同。

官方参考：[Serve 与后台持久运行](https://tailscale.com/docs/reference/tailscale-cli/serve)、[HTTPS 启用](https://tailscale.com/docs/how-to/set-up-https-certificates)、[设备密钥到期](https://tailscale.com/docs/features/access-control/key-expiry)。

## 临时公网连接（原有方式）

电脑和手机不在同一局域网时，可通过 Cloudflare 官方 `cloudflared` 建立临时 HTTPS 连接。无需账号、域名或自备服务器；手机可使用其他 Wi-Fi 或移动网络。

1. 从 [Cloudflare 官方下载页](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/) 下载适合电脑的 `cloudflared`。Windows x64 可将可执行文件保存为仓库的 `target\tools\cloudflared.exe`，或将其放入 PATH。工具不会自动下载安装、修改 PATH 或安装系统服务。
2. 关闭旧的朗读连接工具，再执行：

   ```powershell
   pwsh -File .\tools\speech-relay\start.ps1 -Internet
   ```

   也可以指定已有工具位置：

   ```powershell
   pwsh -File .\tools\speech-relay\start.ps1 -Internet -Cloudflared 'D:\tools\cloudflared.exe'
   ```

3. 手机打开输出的完整 `https://….trycloudflare.com/#…` 地址；电脑自动打开的配对页也提供此地址。连接耳机后点击“启用朗读”，保持页面前台。

公网模式只在本机回环地址监听，不需要开放路由器端口或添加入站防火墙规则。只有主动点击的英文经过 Cloudflare 中转；HTTP 流量在 Cloudflare 终止 TLS，**不是端到端加密**。完整手机链接包含接收凭证，请勿公开。

手机接收凭证与电脑发布凭证分别随机生成，手机链接只能接收，不能提交朗读。发布凭证只写入本机连接文件；公网配对页和状态接口均不返回它。现有输入法发送器无需重编译，下一次点击会读取新连接文件。

保持启动命令运行；按 Ctrl+C 会关闭本次子隧道并删除本次连接文件。重启后公网地址与配对凭证都会改变，旧链接失效。连接中断会自动重试；隧道进程退出时工具停止，并提示重新启动。免费临时隧道仅供试用，没有可用性保证，长期固定使用需要另行配置正式隧道，见 [官方说明](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)。

## 参数与协议

```powershell
python .\tools\speech-relay\server.py --port 21835 --open
```

Python 部分仅用标准库，无需 pip；公网模式额外需要官方 `cloudflared`。启动时分别生成手机接收令牌和电脑发布令牌，将发布端口与发布令牌原子写入 `%APPDATA%\Qingjian\speech\connection.toml`；正常退出删除属于本次运行的文件。电脑发送器只接受端口与发布令牌，目的地址固定回环。不落盘词条，只在内存保留最新一次点击。

| 接口 | 调用方与行为 |
|---|---|
| `GET /` | 静态接收页，无内嵌令牌；令牌从链接 fragment 获取 |
| `GET /api/status` | 接收凭证读取当前序号、局域网地址或公网地址，不返回词条与发布凭证 |
| `GET /api/events?after=N` | 配对手机最多等待 15 秒，返回最新 `{sequence, text}` |
| `POST /api/speech` | 仅回环来源且持有独立发布凭证，UTF-8 `text/plain`、1–500 字、最多 8192 字节；成功 202 |
| `GET /api/admin` | 本机发布凭证读取设置快照、手机状态与配对地址，不返回发布凭证 |
| `POST /api/admin/control` | 本机发布凭证修改启停、模型编号、speaker ID；控制版本参与长轮询唤醒 |
| `POST /api/admin/stop` | 本机发布凭证停止本次连接工具与子隧道 |
| `POST /api/device` | 手机接收凭证提交受大小限制的播放状态与已安装音色数量 |
| `GET /api/packs/<sha256>/meta` | 手机接收凭证获取模型大小和完整性信息 |
| `GET /api/packs/<sha256>` | 手机接收凭证流式下载指定数据包；手机禁止跟随重定向并校验 SHA-256 |

所有 API 要求 `Authorization: Bearer <对应操作的令牌>`。未配对或使用另一角色的令牌返回 401、远程发布 403、参数错误 400。无目录列表或任意文件读取。手机启用时从当前序号开始，避免重播旧事件；同词重听也会产生新序号。局域网模式多网卡/VPN 时选择手机能访问的局域网地址。

## 验证

```powershell
python -B -m unittest discover -s tools/speech-relay -p 'test_*.py' -v
cargo test --locked -p qingjian-core --lib
cargo test --locked -p qingjian-render --test hits
cargo test --locked -p qingjian-windows-server --test engine_loop --test speech
cargo run --locked -p qingjian-render --example preview -- --speech --out target/speech-preview
```

Windows 构建/测试设置 `$env:QINGJIAN_UIACCESS = '0'`。实际手机发声、耳机路由与候选窗不失焦仍需真机验证，单元测试不替代这部分。

## Android 应用更新

`app_updates.py` 管理本机 updates 目录。`/api/update` 返回版本清单，`/api/update/<sha256>` 下载按哈希固定的 APK；两者均只接受手机接收凭证，不接受电脑发布凭证。无安装包返回 update=null，不存在的哈希或越界路径返回 404。下载中切换发布不修改已打开的 APK 文件，旧哈希文件保留时仍可继续进行中的下载。

`update_http.py` 为更新包提供已鉴权的单区间 Range/If-Range、206/416、ETag 与 HEAD。Android 0.2.10-dev 按 4 MiB 分段请求、有限重试，网络中断或暂停时保留哈希绑定的部分文件，完成后重新检查完整哈希、版本和签名。首次更新电脑连接工具代码需重启，普通发布 APK 不需重启。协议依据：[HTTP Range](https://www.rfc-editor.org/rfc/rfc9110.html#name-range-requests)。

源码根目录执行 `pwsh -File apps/android-speech/build.ps1 -PublishUpdate` 即可构建并发布 ARM64 包。默认缓存位置为 `%APPDATA%\Qingjian\speech\updates`；服务每次检查时读清单，发布后无需重启。首次升级连接工具代码需重启服务，已有配对按原模式保留或重新扫码。自定义 state-file 的更新目录位于该文件旁的 updates 中。

先手动覆盖安装支持更新的手机 App，之后在「设置 → 应用更新」下载并进入系统安装界面。电脑本地发布不开放远程上传，安装包签名由手机再次核验；调试包应保留原 debug.keystore，不能随意换机器生成新签名。旧 APK 保留于电脑缓存供进行中的下载读取，版本清单始终只公布当前版本，手机拒绝安装不高于已安装版本的包。
