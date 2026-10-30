# 手机朗读连接的持久化与多电脑

**状态：** 计划，尚未实现。2026-10-30 按用户需求（手机与电脑不在同一局域网、扫一次码长期使用、两台电脑轮换）整理；同日定稿**彻底放弃电脑向手机推送音色包、`pack` 概念整层删除**，音色选择 100% 纯手机本地决定（见 2.5）；同日改定**用隧道令牌而不是 credentials 文件**，配置与操作全部收进设置页（见 2.1、3.3）。当前实现基线为 App 0.2.26-dev、`tools/speech-relay` 三种模式（局域网 / 临时公网 / Tailscale）。本文中的命名隧道模式、凭证持久化、开机恢复、App 多配对与故障转移、音色控制解耦均为后续开发任务，不是现有能力声明。

**目标：** 手机扫一次码之后，电脑重启、电脑关机再开机、换网络、手机开 VPN 都不必重新配对；两台电脑轮换使用时手机自动找到开着的那台；音色纯手机端设置，不再受电脑任何干扰。

**边界：** 只改 `tools/speech-relay` 与 `apps/android-speech`（另需 `apps/settings` 加一个开机自启开关、删掉音色区），不动 Core、不动 Windows 发送器（`apps/windows/server/src/speech/sender.rs` 保持回环明文 HTTP）、不动 `Pairing.parse` 的现有地址格式。保留现有三种模式，命名隧道作为新增的第四种。不引入 pip 依赖（`tools/speech-relay/README.md:104`），不要求管理员权限，不安装系统服务。**音色包推送链路整条删除**（`voice_packs.py`、`/api/packs/*`、`PackTransfer.kt` 都不保留），这是唯一的破坏性改动。

## 一、现状与痛点

### 1.1 现在能用的三种方式

| 模式 | 地址 | 凭证 | 重启后 |
| --- | --- | --- | --- |
| 局域网（默认） | `http://192.168.x.x:21835` | 每次运行随机 | 失效，且 App 用不了（见下） |
| `--internet` 临时公网 | `https://<随机>.trycloudflare.com` | 每次运行随机 | 地址与凭证都变，必须重扫 |
| `--tailscale` | `https://<机器>.<网络>.ts.net:8443` | `pairing.json` 持久 | 地址与凭证都不变，可继续用 |

### 1.2 三条根因

**① 凭证不持久。** `tools/speech-relay/server.py:472` 是 `SpeechState(secrets.token_hex(16), ...)`，接收令牌每次运行重新随机。只有 Tailscale 分支（`:494-497`）用 `PairingStore` 覆盖它。

**② App 只接受 HTTPS。** `apps/android-speech/app/src/main/java/org/qingjian/speech/Pairing.kt:10` 要求 `uri.scheme == "https"`，`AndroidManifest.xml:13` 又是 `usesCleartextTraffic="false"`。局域网模式给的 `http://` 地址 App 根本连不上（网页接收页可以）。所以现状下「同一局域网」这条路对 App 是死的。

**③ 地址会变且 App 感知不到。** 临时公网域名随进程变；`ReceiverService.kt:75` 只在 `onStartCommand` 时读一次 `prefs["url"]`，运行中不重读、不监听网络变化（`app/src/main` 里没有任何 `ConnectivityManager` / `NetworkCallback` / `WifiManager` 命中）。`ReceiverService.kt:28-38` 的 60 秒窗口到点就 `stopSelf()`，报「连接超时，请手动重试」。

### 1.3 为什么不用 Tailscale

Android 同一时刻只允许一个 `VpnService` 活动。用户手机偶尔要开别的 VPN，开了就会把 Tailscale 挤掉（系统 `onRevoke`），Tailscale 模式下连接直接断。这不是配置问题，是平台硬限制。

命名隧道不受影响：**拨号方是电脑**（`cloudflared` 主动出站建长连接），手机只是像访问普通网站一样发 HTTPS 请求，完全不碰 VPN 接口。

### 1.4 为什么必须保留中继

`apps/windows/server` 里没有任何 `TcpListener`（`sender.rs:85` 只有 `TcpStream::connect_timeout`，目的地址硬编码回环），输入法只发不收，无法给手机提供可访问的东西。中继承担的职责是 HTTPS 服务、15 秒长轮询、双令牌鉴权、配对页与二维码、`/api/learning/*`、`/api/update/*`、`/api/notes/*`、`/api/device`。这些搬进 TSF DLL 不现实（DLL 跑在宿主应用进程里，`docs/design/speech.md:13` 要求「输入/UI 线程不等待网络」）。

## 二、方案

四层基础能力，加一层音色控制解耦（2.5）。

### 2.1 第一层：Cloudflare 命名隧道（固定地址）

`tunnel.py` 现在只有 `QuickTunnel`（`:21-36`），跑 `cloudflared tunnel --url http://127.0.0.1:{port}`，不登录、域名随机、进程停即失效。命名隧道换成用**隧道令牌**跑 `cloudflared tunnel run`，域名由 Cloudflare 后台的 Public Hostname 决定，固定不变。

**用令牌，不用 credentials 文件（关键设计）。** `cloudflared tunnel run --help` 原文：「`--token value` — The Tunnel token. When provided along with credentials, this will take precedence. Also takes precedence over token-file」。本机实测：给一个结构合法但内容伪造的令牌，`cloudflared` 会一路走到 `INF Starting tunnel tunnelID=11111111-…` 再报 `ERR Register tunnel error from server side error="Failed to get tunnel"`；给一个格式不对的字符串则立刻 `Provided Tunnel token is not valid.` 并退出。**说明运行一条隧道只需要一个字符串，不需要 `~/.cloudflared/<UUID>.json`，也不需要 `cert.pem`。**

三个直接好处：① 用户全程不碰命令行；② 第二台电脑不用拷文件，复制粘贴令牌即可；③ 令牌泄露可随时在后台轮换，不动账号级凭证。

**令牌长什么样。** base64 编码的 JSON，三个字段 `a`（账号 ID）、`t`（隧道 UUID）、`s`（隧道密钥），形如 `eyJhIjoi...`，约 160–200 字符。设置页可以就地解码校验（见 3.3）。

**一次性准备（用户做，全程网页操作）：**

1. Cloudflare 后台 → Zero Trust → Networks → Tunnels → Create a tunnel → 选 Cloudflared → 起名（如 `qingjian-home`）。
2. 创建后页面给出的安装命令里，`--token eyJhIjoi...` 后面那一整串就是令牌，复制它。
3. 同一页 Public Hostname → Add：Subdomain 填 `speech`，Domain 选 `qingjian.app`，Service 填 `http://localhost:21835`。
4. 把令牌粘进设置页。

**第 3 步漏了的症状是「隧道连上了但访问返回 502」**，不是「连不上」，所以设置页的引导文案必须把这一步写清楚，否则用户会去查网络。

**运行命令：**

```powershell
cloudflared tunnel --no-autoupdate --protocol http2 run
```

令牌**不放在命令行里**，走环境变量 `TUNNEL_TOKEN`（`--token` 的 help 标注了 `[%TUNNEL_TOKEN%]`，即该环境变量）。理由：命令行参数在进程列表里对同机其他用户可见，而令牌是能直接连上隧道的凭证。

注意上面这条命令**不带 `--url`**——ingress 由后台的 Public Hostname 决定（第 3 步）。这正是用令牌跑隧道的默认形态：远程托管配置。

`--protocol http2` 保留：针对国内宽带对 UDP/QUIC (443) 普遍存在的 QoS 丢包甚至阻断风险，显式走 TCP。实测加它时日志为 `INF Initial protocol http2`、建连行 `protocol=http2`，不加则是 `quic`。`--url` 在父命令层与 `run` 子命令层都支持，若实测发现远程配置不生效、必须本地控制 ingress，就退回「本地 `config.yml` + 令牌」的写法（仍用令牌，只多一个文件）。两种写法都写进 `NamedTunnel` 注释，验收以实测为准。

子域免费（免费版 DNS 记录不限量），橙云必须开（命名隧道不能 DNS only）。

**实现：** `tools/speech-relay/tunnel.py` 新增 `NamedTunnel`，与 `QuickTunnel` 同形制（`start()` / `close()`），差异：

- 不从 stdout 抓域名，域名来自配置。
- 令牌走 `env` 传给子进程（`TUNNEL_TOKEN`），不进命令行参数。
- 判据从「抓到 trycloudflare 域名」改成「cloudflared 报告已建立连接」。实测 `cloudflared 2026.10.0` 建连时固定打印这一行（本机验证过）：

  ```
  INF Registered tunnel connection connIndex=0 connection=<uuid> event=0 ip=<ip> location=<loc> protocol=http2
  ```

  正则匹配 `Registered tunnel connection` 即可 `ready.set()`。这是唯一判据（`protocol=` 后面跟的是实际协商结果，加了 `--protocol http2` 就是 `http2`，不加是 `quic`；判据只认前半句，不要匹配协议名）。
- **失败要能区分开。** 实测两种失败各有固定输出：令牌格式不对 → `Provided Tunnel token is not valid.`；令牌格式对但隧道不存在或已删 → 先 `INF Starting tunnel tunnelID=…` 再 `ERR Register tunnel error from server side error="Failed to get tunnel"`。前者是「粘贴错了」，后者是「后台把隧道删了」，提示文案必须分开，否则用户会去重新粘贴一个没问题的令牌。
- **就绪保护：** `NamedTunnel` 在未成功 `ready.set()` 且确认连通前，`state.public_url` 必须保持为空，避免 `desktop.py` 轮询时误判 `https://` 前缀已就绪而提前返回。
- `close()` 沿用现有的 `terminate()` → `wait(5)` → `kill()` → `wait(5)`，并 join 读线程。
- Windows 上 `cloudflared` 不自动更新，`--no-autoupdate` 仍保留以保持多平台一致。

**前置检查只能查本地，不能查云端：**
- 检查 `named-tunnel.toml` 的 `host` 是非空域名、`token` 能被 base64 解开且含 `a`/`t`/`s` 三个字段。
- 不匹配就抛明确错误（如「隧道令牌不完整，请从 Cloudflare 后台重新复制」），**不要静默退回临时隧道**。
- 不做「隧道是否还存在」的云端校验：`cloudflared tunnel list` 需要 `cert.pem`（账号级凭证），运行期不该有。这条只能靠启动日志里的 `Failed to get tunnel` 兜住。
- `NamedTunnel.start()` 把 stderr 尾部收进异常，进程在前 60 秒内退出时拼进 `RuntimeError`，明确暴露原因。

**配置落点：** 新增 `%APPDATA%\Qingjian\speech\named-tunnel.toml`：

```toml
host = "speech.qingjian.app"                            # 后台 Public Hostname 里配的域名
token = "eyJhIjoi..."                                   # 后台给的隧道令牌，约 160–200 字符
```

只有这两个键，权限 `0o600`（复用 `pairing.py:save` 的写法）。**传输协议固定 `http2`，不作为配置项**——`--protocol` 在 `cloudflared` 的 help 里查不到、非法取值也不报错，属于未文档化的隐藏 flag，不该让用户配。`desktop.py` 的 `start()` 读这个文件决定用哪种模式，文件不存在就退回现有的临时隧道行为（见 5.3）。

**不做账号授权（OAuth）。** `cloudflared tunnel login` 只有「浏览器跳转 + 回调」一条路（`--loginURL`/`--callbackURL` 只能改地址，无无头模式）。要把它搬进设置页等于自己实现一遍 OAuth：注册 OAuth client、接回调、换 token、再调 Cloudflare API 建隧道与 DNS。而且 `cert.pem` 是**账号级**凭证，一旦落到用户机器上，泄露范围是整个 Cloudflare 账号而不只是一条隧道。用户一辈子只做一次这个动作，不值得。粘贴令牌已经达到同样的效果（一次配置、长期有效、可随时吊销），代码量为零。

### 2.2 第二层：凭证持久化

命名隧道模式下把 `server.py:472` 的随机令牌换成 `PairingStore`，与 Tailscale 分支同构。`pairing.py` 本身模式无关（39 行，校验 + 原子写 + `0o600`），**不需要改**。

`server.py` 的改动是把 `:491-497` 那段 Tailscale 专属逻辑抽成「持久化分支」，让 `--tailscale` 与命名隧道共用：

```python
if args.tailscale or args.named:
    state.pairing = PairingStore(state_file.with_name("pairing.json"), args.reset_pairing)
    state.token = state.pairing.data["receive_token"]
    state.publish_token = state.pairing.data["publish_token"]
    state.sequence = state.pairing.data["sequence"]
    connection_settings = f'port = {args.port}\ntoken = "{state.publish_token}"\n'
```

同时 `server.py:469` 的 `if args.reset_pairing and not args.tailscale: parser.error("--reset-pairing 仅用于 --tailscale 模式")` 要允许命名隧道。两台电脑的 `pairing.json` 在各自 `%APPDATA%` 下，天然隔离。

### 2.3 第三层：开机后自动恢复

选方案 A：**Windows 用户启动项（注册表 `Run`）+ 设置页开关**。

**职责清晰拆分：**
1. **系统开机静默启动动作（`desktop.py autostart`）**：
   - 由 Windows 开机启动项自动执行。
   - 读取 `named-tunnel.toml`：如果文件不存在或凭证损坏，静默退出（返回码 0），不弹窗不干扰用户日常登录；若配置有效，则等同调用 `start()` 启动中继。
   - **绝对路径与无窗保障**：注册表命令写绝对路径，优先调用与 `sys.executable` 同目录的 `pythonw.exe`：
     `"C:\path\to\pythonw.exe" -B "C:\path\to\tools\speech-relay\desktop.py" autostart`
     若无 `pythonw.exe` 则由内部设置 `CREATE_NO_WINDOW` 标志运行，避免桌面闪现控制台黑框。
2. **设置页管理开机自启命令（`desktop.py autostart-enable` / `autostart-disable`）**：
   - 由设置程序在用户拨动「开机自动连接」开关时调用。
   - 内部使用 Python 标准库 `winreg` 模块修改 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 下的 `QingjianSpeechRelay` 键值。
   - 执行完成后**统一调用 `output(snapshot())` 输出 TOML 状态文本**，杜绝返回空内容导致 Rust 侧 `call()` 报错。
3. **设置页状态回显**：
   - `desktop.py snapshot()` 检查注册表键是否存在，返回 `autostart = true/false`；
   - `apps/settings/src/jobs/speech/snapshot.rs` 的 `Snapshot` 增加 `pub(crate) autostart: bool`；
   - `apps/settings/src/view/pages/speech.rs` 在「启动手机连接」下方放置 `Switch` 控件（只有在检测到 `named-tunnel.toml` 存在时才允许开启，否则提示需先配置命名隧道）。

### 2.4 第四层：两台电脑轮换（App 侧）

两台电脑各自一份 `pairing.json`、各自一个子域（`speech-a.qingjian.app` / `speech-b.qingjian.app`，建两条隧道各绑一个）。手机存多条配对。

**为什么不用「同时监听两台」：**
三个真实冲突——`SpeechPlayer.submit()` 是单槽（后到打断先到）、`control.snapshot()` 的 `enabled` 会互相覆盖导致朗读被莫名暂停、`acknowledge()`（`POST /api/device`）与 `Transfer.run` 的归属不明。收益只有「两台同时开也不漏」，不值得。

**存储改造与旧数据平滑迁移：**
若 `prefs` 中包含旧的字符串 `url` 且尚未存在 `pairings` 列表，首次启动自动迁移生成包含该项的单元素列表，并设置 `active = url`。

| 位置 | 现状 | 改为 |
| --- | --- | --- |
| `learning/ConnectionControls.kt:22` | `putString("url", url)` 单值 | `putString("pairings", JSONArray)` + `putString("active", url)`，按 host 去重 |
| `ConnectionControls.kt:39` | `reconnect()` 拿旧 `url` | 拿 `active` |
| `ReceiverService.kt:75` | `Pairing.parse(prefs["url"])` | 读 `active`（带旧数据自动迁移） |
| `ReceiverService.kt:93-150` | 单条接收循环 | 加故障转移（见下） |
| `ReceiverService.kt:28-38` | 60 秒到点 `stopSelf()` | **严格保持 60 秒硬超时**（探测在 60s 窗口内完成，见下） |
| `ReceiverService.kt:77` | `onStartCommand` 里 `controlRevision = -1L` | 切换电脑时同样重置为 `-1`（两台 `control.json` 的 `revision` 各自从 0 递增，不重置会让 `applyControl` 去重短路，见 3.2） |
| `update/UpdateClient.kt:47` | `pair()` 读 `speech.url` | 读 `active` |
| `update/UpdateClient.kt:120` 与 `:134` | 写 `source = base + "/#" + token`，下载时 `require(source == pair(app))` | **检查更新时把当时那台的标识一起写进 metadata，下载时用那台而不是当前 active** |
| `learning/archive/Transfer.kt:52` | `Pairing.parse(speech["url"])` | 读 `active` |

**故障转移与 Sequence 自愈防死锁逻辑：**

```
长轮询 active 失败
  → 在当轮 60 秒超时窗口内，并发探测其余配对 GET /api/status（2 秒超时，立即返回）
  → 某备选电脑有应答：
       1. 读出对端当前 sequence，记为 remoteSeq
       2. 检查手机本地记录的该电脑 after：
          - 若无本地记录：after = remoteSeq（初次对接）
          - 若 after > remoteSeq（对端重置过序号）：after = remoteSeq（重置自愈，防止 sequence 倒挂死锁）
          - 若 0 <= after <= remoteSeq：保留本地 after（安全补播最新一次点击）
       3. 更新 active 并切过去，恢复接收循环，清零重试计时器
  → 60 秒窗口耗尽仍全无应答：
       严格执行 stopSelf()，更新状态为「重新连接」，坚决不后台无限轮询耗电
```

**计时器规则（避免探测吃掉窗口）：** 60 秒 `retryDeadline` 在探测期间**不重置**，只有探测成功并恢复长轮询时才清零。探测预算固定：每台 2 秒超时、并发发出、单轮不超过 8 秒、最多两轮，叠加退避后严格落在 60 秒内。超过预算直接 `stopSelf()`。

**补播与学习档案的多机一致性：**
1. **补播：** `SpeechState` 只保存最新一条（`publish()` 是覆盖不是追加）。切过去只要序号自愈正常，能且仅能听到刚才最后一次点击，不会积压。
2. **学习档案同步（`Transfer.kt`）：** 手机作为生词与练习记录的 SSOT（Single Source of Truth）。无论切到哪台电脑，手机都只会把自身的最新备份写入当前电脑的 `learning-backups/`，两台电脑各自获得最新镜像备份，天然无冲突。

**两台同时开会漏一次与交互设计（敲定）：**
- **漏一次的处理**：手机连 B、在 A 上点喇叭，手机无反应。A 侧虽然由于本机回环 HTTP 202 显示发送成功，但重点击即可重试。
- **界面交互方案（符合 `docs/design/mobile-connection.md` 规范）**：
  - **顶部标题栏（`ConnectionBar`）保持极简**：仍然只展示当前 `active` 电脑的连接状态（绿点/转圈/倒计时/重新连接）与一键启停，不占额外行，不引入多电脑菜单；
  - **多电脑列表收纳在详细连接面板（`ConnectionPanel`）**：用户点击顶部状态或通过菜单展开现有的底部弹窗 `ConnectionPanel` 时，面板中展示「已配对电脑列表」：列出每台电脑的子域名/别名、在线状态指示绿点/灰点、切换按钮以及长按删除；扫新码直接加入该列表并自动置为 active。

### 2.5 第五层：音色控制彻底解耦（纯手机本地决定）

**放弃「电脑向手机推送音色包」的三条事实依据**（用户在测试阶段实测反馈「发送很慢，发送后找不到发送的音色」，对应下列 1 与 2；第 3 条是代码层面的硬缺陷）：

1. **传输量注定很慢。** 音色包就是模型文件本身：内置 Kokoro 的 `model.onnx` 有 325 MB（`apps/android-speech/app/src/main/assets/kokoro-multi-lang-v1_0/model.onnx`），Kitten 的 `model.fp16.onnx` 有 24 MB。`PackTransfer.kt:22` 还要求手机至少有「包大小 + 解压上限（768 MB）+ 32 MB」的空闲空间，`:29` 的 `readTimeout=30000` 意味着 30 秒没有数据就判失败、整包重来。经公网隧道传这个量级，用户体感就是「发送很慢」。
2. **传完也找不到。** `VoiceLibrary.kt:50` 的 `names()` 对非内置包一律返回 `Array(count) { "音色 ${it+1}" }`，包里 `voicepack.json` 的 `name` 字段（`voice_packs.py:27` 会校验长度）根本没被用上，用户在下拉里只看到「音色 1」「音色 2」「音色 3」……认不出刚传的是哪个。
3. **更关键：手机上根本没有切换音色包的入口。** `MainActivity.kt:184` 的音色下拉只写 `putInt("voice", position)`（包内第几个音色），唯一写 `prefs["pack"]` 的地方是 `ReceiverService.kt:177`，也就是电脑下发的控制帧。包传完了，用户没有任何地方能切过去用它——除非再让电脑点一次那个音色。**这条决定了整套机制不可用，与传输快慢无关。**

**结论：`pack` 这一层整层删掉。** 外挂包砍掉后 `pack` 恒为 `"builtin"`，这一层不再有存在意义。音色退化为单纯的 `voice` 索引（0–7），新音色直接随 APK 发版进 `BundledVoice.labels`。

**同时修复当前单机就存在的缺陷——重连时电脑冲掉手机音色：**

缺陷根因：`ReceiverService.kt:77` 在 `onStartCommand` 里无条件 `controlRevision = -1L`，重连后第一帧 `applyControl` 必满足 `revision > controlRevision`，把电脑 `control.json` 里的旧音色写回手机 `prefs` 并 `player.configure()`。用户在手机「朗读设置」里选的音色（如女声 1 · af_heart）重连后直接被冲回默认值。

**改造后：**

1. **电脑不再控制手机音色**：`SpeechControl` 移除 `pack`/`speaker`，**但必须保留 `revision`**——它是手机判断「这条控制帧处理过没有」的唯一依据，删了 `applyControl` 的去重就没了；只留 `enabled` 与 `allow_notes`。`applyControl` 只读 `enabled`，不再写任何音色 prefs。
2. **手机 100% 独立管理音色**：`SpeechPlayer.kt:77-82` 直接读手机 `prefs["voice"]`；重连、切电脑、重开 App 都不改变当前音色。
3. **设置页音色区简化成一行文字**：「音色请在手机端「朗读设置」里调整」。不再有下拉、不再有导入按钮（详见 3.3）。

**顺带修正音色命名。** `BundledVoice.kt:13` 现在是：

```kotlin
val labels = arrayOf("男声 1", "女声 1 · af_heart", "男声 2", "女声 2", "男声 3", "女声 3", "男声 4", "女声 4")
```

只区分性别与序号。而 `BundledVoice.kt:15` 的 `engineKey(voice) = if(voice==1) "kokoro-heart" else "kitten"` 说明**只有 index 1（af_heart）是多语模型，其余 7 个是 Kitten 英文模型**——这个信息在 UI 上完全没体现，用户选「男声 3」去读中文会得不到预期效果。参照 `ChineseVoices.kt:7` 的 `"晓贝 · 女声"` 格式，改成「名字 + 性别 + 语言适用范围」，例如 `"女声 · 柔和（中英）"`、`"男声 · 清晰（英文）"`。**具体每个音色的风格词需要用户试听后定**，本文只定格式。

## 三、改动点清单

### 3.1 `tools/speech-relay`

| 文件 | 改动 |
| --- | --- |
| `tunnel.py` | 新增 `NamedTunnel`（`start`/`close`）；令牌走环境变量 `TUNNEL_TOKEN` 不进命令行；命令行使用 `--protocol http2`；就绪判据用日志 `Registered tunnel connection`；`Provided Tunnel token is not valid.` 与 `Failed to get tunnel` 两种失败分开提示；未就绪前不提前暴露 `public_url` |
| `server.py:452-476` | 加 `--named`（与 `--internet`/`--tailscale` 互斥）、`--named-host`、`--named-token`；由 `desktop.py` 从 `named-tunnel.toml` 读出后传入；`:469` 的 `--reset-pairing` 放行 |
| `server.py:472-474` | `--named` 时 `addresses=[]`，`bind="127.0.0.1"` |
| `server.py:484` | `--named` 时起 `NamedTunnel` 而不是 `QuickTunnel` |
| `server.py:491-497` | 抽成共用的持久化分支，`--named` 也走 |
| `server.py:501-505` | `state.public_url = f"https://{host}"`（隧道确认已连通后赋值） |
| `server.py:508-512` | 启动打印增加命名隧道分支文案 |
| `server.py:519-520` | 主循环里 `if tunnel and tunnel.process.poll() is not None: raise RuntimeError("公网连接工具已退出，请重新启动以获得新的配对地址。")` —— 命名隧道重启后地址不变，这句文案要按模式分开：临时隧道保留原文，命名隧道改成「公网连接已断开，请重新启动手机连接」 |
| `speech_control.py` | 删 `pack`/`speaker`，**保留 `revision`** 与 `enabled`/`allow_notes`；连带删 `:9` `BUILTIN`、`:44-54` `change()` 的 pack/speaker 分支、`:65-93` `acknowledge()` 的 catalog/active_pack/active_speaker 校验、`:95-108` `desktop()` 的 choices 生成；`:13` 的 `__init__(self, root, packs)` 去掉 `packs` 参数。`desktop()` 仍要返回 `phone_connected` 与 `phone_status`（设置页要显示手机状态）。**旧 `control.json` 里残留的 `pack`/`speaker` 必须静默忽略**——`__init__` 会 `change(data, initial=True)` 读它，抛 `ValueError` 就等于老用户升级后中继起不来 |
| `voice_packs.py` | **整文件删除**（127 行，打包逻辑随推送机制一起下线） |
| `server.py:212-239` | `/api/packs/*` 路由整块删除 |
| `server.py:481` | `SpeechControl(state_file.parent, VoicePacks(...))` 去掉 `VoicePacks` 参数 |
| `desktop.py` | `start()` 读 `named-tunnel.toml` 决定模式；新增开机静默启动动作 `autostart`；新增注册表管理动作 `autostart-enable` / `autostart-disable`；`snapshot()` 增加 `autostart` 字段；**`:82` 的 `"selected": f"{control['pack']}:{control['speaker']}"` 必须同改**——字段一删这里 KeyError，整个 `snapshot()` 崩，设置页会显示「未启动手机朗读连接」；`output()`（`:126-132`）里 `if key != "choices"` 的特例与 `[[choices]]` 块一并删；`:139` action choices 删 `select`/`import`（`preview` 保留，它走 `/api/speech` 与音色无关）；`:100-108` 的 80×1s 就绪轮询要分两条路径——交互式 `start` 保持 80 秒，`autostart` 单独放宽（开机时网络往往还没就绪），失败静默退出但写 `startup-error.log` |
| `start.ps1` | 加 `-Named` 开关 |
| `README.md` | 加命名隧道一节；删「电脑推送音色包」说明；**接口表删 `/api/packs/<sha256>` 与 `/api/packs/<sha256>/meta` 两行** |
| `test_tunnel.py` | 加 `NamedTunnel` 单元测试 |
| `test_voice_packs.py` | **整文件删除**（3 个用例，`voice_packs.py` 没了它没有被测对象） |
| `test_control.py` | 5 个方法里：`test_model_package_download_requires_phone_auth_and_is_exact`（`:74-88`）**删掉**；`test_control_changes_wake_poll_and_pause_rejects_speech`（`:56`/`:59`）去掉 `speaker` 只留 `enabled`；`test_device_acknowledgement_exposes_status_but_not_publish_token`（`:64-72`）去掉 `catalog`/`active_pack`/`active_speaker` 只留 `status` 与 `control_revision`；另两个（`:46`、`:90`）不动 |
| `test_notes.py:25` | `SpeechControl(self.root, VoicePacks(self.root / "packs"))` 构造签名同步改 |

### 3.2 `apps/android-speech`

净增约 80–120 行 Kotlin（多配对与故障转移），另净删约 150 行（音色包链路）。

- `Pairing.kt` 不改；
- `ConnectionControls.kt` 增加旧版单 `url` 向多配对列表的平滑数据迁移（`pairings` + `active`）；
- `ReceiverService.kt`：
  - 故障转移探测（严格限制在 60s 窗口内，单轮最多 8s，超时准时 `stopSelf()`）；
  - Sequence 倒挂自愈防死锁；
  - **切换电脑时把 `controlRevision` 重置为 `-1`**（两台电脑的 `control.json` 各自从 0 递增，不重置会让 `applyControl` 的 `if(revision==controlRevision) return` 短路——A 上暂停过、切到 B 后手机以为还被暂停，朗读不工作。`enabled` 幂等，重放无副作用）；
  - `applyControl` 彻底剥离 `pack`/`speaker` 写入，只读 `enabled`，不再修改任何音色 prefs；
  - **`:106-107` 的 `pack`/`speaker` 校验必须同批删掉**：
    ```kotlin
    val pack=control.getString("pack");require(pack=="builtin"||pack.matches(Regex("[a-f0-9]{64}")))
    require(control.getInt("speaker") in 0..1023)
    ```
    服务端不再下发 `pack` 后 `getString("pack")` 返回 null，`pack.matches(...)` 直接 NPE，被外层 `catch (_: Exception)` 吞掉后报「网络中断，正在重新连接…」→ 退避 → 再收同一帧 → **无限重连，用户只看到一直转圈**；
  - 移除 `PackTransfer` 音色包下载调用；
- `PackTransfer.kt`：**整文件删除**（82 行，只服务音色包下载）；
- `VoiceLibrary.kt`：删外挂包扫描（`catalog()`、`directory()` 的 `filesDir/voices` 部分、`recordCount` 的多包分支），但 **`names`/`create`/`count`/`recordCount` 不能删**——`SpeechPlayer.kt:78`/`:99` 与 `MainActivity.kt:97` 都在用它们取内置音色名与建引擎。要么保留并固定走 `BundledVoice`，要么整体搬进 `BundledVoice`；
- `BundledVoice.kt:13`：`labels` 按 2.5 的新格式改名；
- `ConnectionPanel.kt` 增加已配对电脑卡片列表与在线绿点，支持手动切换与解绑；
- `UpdateClient.kt` 检查更新锁定 metadata 中的来源 host，下载时按对应 host 校验。

### 3.3 `apps/settings`

- `jobs/speech/snapshot.rs`：`Snapshot` 增加 `pub(crate) autostart: bool`（`parse()` 的 `boolean()` 闭包缺字段默认 `false`，不影响旧版 `desktop.py` 输出）；**删掉 `selected` 与 `choices` 两个字段及 `parse()` 里对应的解析**——音色下拉没了，这两个字段没有消费方；
- `view/pages/speech.rs`：手机连接区域改成四行——

  | 行 | 控件 |
  | --- | --- |
  | 连接方式 | 两个 `Segmented` 选项：`固定地址` / `临时地址`；选固定时展开下面两行，选临时时隐藏 |
  | 服务地址 | `TextField`（`host`），默认占位 `speech.qingjian.app` |
  | 隧道令牌 | `TextField` + `.secret(true)`（密文圆点），右侧三个按钮：`粘贴` / `怎么拿这个？` / `保存` |
  | 开机自动连接 | `Switch`（`named-tunnel.toml` 不存在时禁用并提示需先配置固定地址） |

- `view/pages/speech.rs`：「怎么拿这个？」按钮弹 `dialog`（`phrases.rs:159` 有现成范式），四步图文 + 一个「打开 Cloudflare 后台」按钮；
- `view/pages/speech.rs:177-245`：**「朗读与音色」这个小标题保留**，但删掉里面的音色下拉与「音色管理」整段（含「选择文件夹…」按钮），改为一行说明文字：「音色请在手机端「朗读设置」里调整」。「启用候选朗读」`Switch` 与「音色试听」（走 `/api/speech`，与音色包无关）保留。
- `jobs/speech/mod.rs:run()`：删 `action == "import"` 的 `rfd::FileDialog` 分支与对应 notice 文案（`"正在打包音色；完成后手机开始下载…"`）；action 白名单去掉 `select` / `import`；新增 `named-save`（保存 host + token）与 `named-clear`（清除配置、退回临时地址）；
- `jobs/speech/mod.rs:call()`：令牌这类敏感值**不放进命令行参数**（`desktop.py` 是 Python 子进程，参数在进程列表可见），改成写临时文件后传路径，或让 `desktop.py` 直接从 `named-tunnel.toml` 读；
- `message/field_id.rs`：`FieldId` 增加 `TunnelHost` / `TunnelToken`（`TunnelToken` 加进 `ALL` 让 `with_fields` 初始化；`commit()` 里清空等于不改，与 `ApiKey` 同策略）；
- `system/action.rs`：新增 `Action::OpenTunnelDashboard`，指向 `https://dash.cloudflare.com/?to=/:account/zero-trust/networks/tunnels`，复用已有的 `pub fn open()`（`:88`）；
- **令牌就地校验**（粘贴时与保存前各一次，在 `jobs/speech/` 里做，不在 UI 层）：base64 解开 → `json.loads` → 检查 `a`/`t`/`s` 三个键。`a` 是 32 位十六进制、`t` 是 UUID 格式。不通过就在输入框下方红字给出具体原因（「这不像隧道令牌：应以 `eyJ` 开头，约两百个字符」/「令牌解出来缺隧道 ID，可能复制不全」），通过则绿字显示「隧道 ID：`1a2b3c4d…`，保存后自动连接」。**用户当场能确认复制对没对，不用等中继启动失败再回头猜。**
- `docs.rs`：`LINKS` 表登记「隧道令牌」这一行 → `docs/user/learning/translation.md` 的「固定地址连接」一节（有测试核对那一节标题真实存在）；

### 3.4 文档同步

| 文件 | 改什么 |
| --- | --- |
| `docs/design/speech.md:15` | 「`--internet` 额外启动 Quick Tunnel」之外补命名隧道说明 |
| `docs/design/speech.md:21` | 「临时隧道没有可用性保证，重启后重新配对」——命名隧道不适用，需明确区分 |
| `docs/design/speech.md:29` | Tailscale 那段的持久化描述改成「持久化模式（Tailscale / 命名隧道）」 |
| `docs/design/speech.md`（新增一节） | 「音色归属」：音色由手机本地决定，电脑不下发也不推送音色包；新音色随 APK 发版内置 |
| `docs/design/mobile-connection.md:9-10` | 补充多配对故障转移在 60 秒窗口内完成且超时后依然严格停止的规约说明 |
| `docs/user/learning/translation.md:32-54` | 加「固定地址连接」一节（后台建隧道 → 复制令牌 → 配 Public Hostname → 粘进设置页），并删除电脑向手机传音色包的描述 |
| `docs/README.md` | 本文件加一行 |
| `docs/plan/todo.md` | 视进度加条目 |

## 四、分期与验收

### 阶段 1：一台电脑「扫一次码永久用」

含 2.1 + 2.2 + 2.3，并顺带合入 2.5（单机解耦音色控制）。做完后：用户在后台建一次隧道、把令牌粘进设置页，手机扫一次码，之后电脑重启/关机再开机都不用重扫，手机选的音色永远不被重连冲掉。

验收：

- 建好隧道后 `desktop.py start`，`/api/admin` 的 `pair_url` 是 `https://speech.qingjian.app/#<32hex>`，且**重启三次地址与令牌都不变**。
- 手机扫一次码，断开、重开 App、电脑重启，连接都能自己恢复。
- **音色保全验证**：手机在「朗读设置」选任意音色（如女声 1 · af_heart），电脑多次重启、App 多次重开，手机音色始终保持所选，绝不被冲回默认男声。
- **App 不无限转圈**：中继不再下发 `pack`/`speaker` 后，手机长轮询正常、通知栏显示「已连接」，不会陷入「网络中断，正在重新连接…」的死循环（验证 `ReceiverService.kt:106-107` 的校验已同批删除）。
- **旧 `control.json` 兼容**：电脑上残留带 `pack`/`speaker` 的旧 `control.json` 时，中继能正常启动（`SpeechControl.__init__` 的 `change(data, initial=True)` 要静默忽略这两个字段，不能抛 `ValueError`，否则中继起不来）。
- **令牌校验三种输入都对**：粘 `eyJhIjoi...` 正常令牌 → 绿字显示隧道 ID 并自动连接；粘半截 → 红字「令牌解出来缺隧道 ID，可能复制不全」，不写文件；粘一段随便的文字 → 红字「这不像隧道令牌：应以 `eyJ` 开头」。三种情况都不需要等中继启动失败才反馈。
- **两种运行期失败提示能分开**：令牌被后台轮换/隧道被删 → 提示「后台把这条隧道删了或换了令牌，请重新复制」；网络不通 → 提示「连不上 Cloudflare」。实测这两种在 `cloudflared` 输出里分别对应 `Failed to get tunnel` 与连接超时类日志，不能都显示成「建立手机连接超时」。
- **Public Hostname 没配的表现要能被识别**：隧道连上但访问返回 502 时，设置页提示里要带上「检查后台的 Public Hostname 是否指向 `http://localhost:21835`」。
- 开机自启开关能正确读写注册表 `Run`，开机启动后服务静默跑在后台，无控制台黑框弹出。
- `python -B -m unittest discover -s tools/speech-relay -p 'test_*.py' -v` 全绿（删掉 `test_voice_packs.py` 整文件与 `test_control.py` 里那个音色包下载用例后）。
- `cargo clippy --all-targets -- -D warnings` 全绿（设置页改动）。

### 阶段 2：两台电脑轮换

含 2.4。做完后：手机存两条配对，上班连 A、下班连 B，自动切换。

验收：

- 两台都配置好，手机只扫两次码（各一次）。老版本平滑升级，旧配对不丢失。
- 关掉当前连的那台、打开另一台，手机在当轮 60 秒退避周期内自动切过去，通知栏文案正确；若两台都关机，60 秒到期准时停止，不常驻后台耗电。
- Sequence 倒挂自愈验证：人为将电脑端 sequence 设小（如对端重新生成配对为 0，手机存量为 50），切换后手机自动校准为 0 并能正常接收后续新词播音，不发生死锁。
- 两台同时开时，在 `ConnectionPanel` 手动切换能切到另一台，且切过去会补播最后一次点击。
- **`controlRevision` 跨机不串**：在 A 上点「暂停朗读」（`revision` 变 1、`enabled=false`），切到 B（`revision` 也是 1、`enabled=true`）后手机能正常朗读，不会因为 `if(revision==controlRevision) return` 短路而以为还被暂停。
- 检查更新在 A 上发起、切到 B 后下载仍能完成（`UpdateClient` 锁定 source 校验）。
- 切换后 `after` 用本地校准序号，不重播历史、不漏新事件。

## 五、风险与不做的事

### 5.1 风险

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| Cloudflare 线路质量与 QUIC 阻断 | 国内走国际线路，晚高峰可能抖；部分网络丢弃 UDP 443 | 命名隧道默认使用 `--protocol http2`，避免 QUIC 握手失败；重连退避已有（1500ms 起翻倍封顶 15s） |
| 对端电脑 sequence 重置失步 | 手机本地记录序号大于服务端，导致长轮询永久不返回新词 | 握手时对比 `remoteSeq`，若本地 `after > remoteSeq` 则强制重置校准 |
| 两台电脑都关机导致后台无限探测耗电 | 破坏系统后台规范 | 坚决执行 60 秒硬超时规则，超时后停止前台服务并提示手动重连 |
| `cloudflared` 进程挂了没人拉 | 手机看到 502 / 超时 | `cloudflared` 自身会重连网络；进程退出需要中继侧监测并重启，列入阶段 1 的实现细节 |
| `cert.pem` 的作用面 | 运行期用不到它，但 `tunnel list` / `route dns` 需要 | 运行期只用 `--token`（或 `TUNNEL_TOKEN` 环境变量，见 2.1）；前置检查只查本地配置，不查云端；云端已删的隧道靠启动日志 `Failed to get tunnel` 兜住 |
| 隧道令牌泄露 | 拿到令牌即可把流量引到自己的机器 | 令牌不进命令行（走 `TUNNEL_TOKEN` 环境变量），`named-tunnel.toml` 权限 `0o600`；设置页输入框 `.secret(true)` 密文显示；泄露后在后台轮换令牌即可，不动账号级凭证 |
| 设置页要用户去后台复制令牌 | 步骤多，容易漏掉 Public Hostname | 「怎么拿这个？」对话框给四步图文 + 直达后台按钮；粘贴时就地解码校验，当场告诉用户对不对；Public Hostname 漏配的表现（连上但 502）单独给提示 |
| 一个 Cloudflare 账号下的子域公开可见 | 域名可被扫描到 | 已有双令牌鉴权 + 401 语义；`/api/status` 不返回发布凭证；保持 `docs/design/speech.md:14` 的「不记录词条历史」 |
| TLS 在 Cloudflare 边缘终止 | 不是端到端加密 | 与现有临时隧道一致，`README.md:92` 已声明，文档沿用 |
| 开机自启常驻后台 | 启动项可能弹黑框或路径找不到 | 注册表使用绝对路径与 `pythonw.exe`，设置页开关默认关，用户主动打开 |
| 电脑设置页没有音色可点了 | 用户以为功能丢了 | 5.2 已定「音色归手机」；设置页那行文字直接写明「音色请在手机端「朗读设置」里调整」，把入口指向真正能改的地方 |

**`ACCESS_LOCAL_NETWORK` 不受影响。** 该权限（API 37 新增）只约束局域网访问；命名隧道走公网域名，不触发。这是它相对「局域网直连」的额外好处。

### 5.2 明确不做

| 方案 | 为什么不做 |
| --- | --- |
| 电脑向手机推送音色包与远程音色控制 | 三条硬理由：① 包就是模型本身（Kokoro `model.onnx` 325 MB），`PackTransfer.kt:29` 的 `readTimeout=30000` 下经公网传必然慢且易中断重来；② `VoiceLibrary.kt:50` 的 `names()` 对非内置包一律返回「音色 N」，包里 `voicepack.json` 的 `name` 从来没被读过；③ **手机上根本没有切换包的入口**——唯一写 `prefs["pack"]` 的地方是 `ReceiverService.kt:177` 电脑下发的控制帧，包传完用户也切不过去。所以 `pack` 整层删掉，新音色随 APK 发版内置 |
| 局域网直连 + 自签证书 + 指纹固定 | 纯标准库不能生成 X.509（`ssl` 模块没有 `Certificate`）；`Pairing.kt:10` 只接受 https 且 `HttpURLConnection` 用系统默认 `SSLContext` + 默认主机名校验，自签证书必被拒，还要一并放宽主机名校验（局域网 IP 证书通常无 IP SAN）；且用户两台电脑经常不在同一局域网 |
| mDNS / NSD 自动发现 | 不解决信任也不解决凭证持久化；`minSdk 26` 要手动持 `MulticastLock` 才能收包；Windows 侧 5353 端口共享未验证；官方也建议不要把 IP 当长期地址 |
| 一个子域 + 路径分流 | 要放宽 `Pairing.kt:10` 的 path 校验，影响所有调用方 |
| 同时监听两台 | 见 2.4 的三个冲突 |
| 看门狗（5 秒探测非活跃那台） | 收益是把「手动点一下」换成「等 5 秒」，反而更慢；真要做到两台同时开也自动切，正确做法是两条长轮询常驻，那 2.4 的三个冲突就全回来了。所以只做手动切换 |
| 突破 60 秒限制的后台无限保活 | 违背 `docs/design/mobile-connection.md` 规约，严重消耗手机电量与流量 |
| gzip 压缩响应 | 实测只省 191 B（540 → 349），不值得改协议 |
| 改 `Connection: close` 省流量 | 实测 Cloudflare 边缘对客户端已呈现 `keep-alive`，服务端这个头不传染到客户端；改它收益依赖 `HttpURLConnection` 连接池行为，风险大于收益 |
| 中继搬到 NAS / 旧电脑 / 旧安卓机 / OpenWrt | 用户已确认「电脑关机 = 功能不需要」，不需要常开机器；且中继要读打字机本机的 `user-vocab.tsv`、`learning-backups/`、`update`，搬走会退化（音色包目录随 2.5 删除，已不在这个清单里） |

### 5.3 保留不动

`--tailscale` 模式与 `--internet` 临时隧道都保留。命名隧道是新增的第四种，不是替换。设置页在没有 `named-tunnel.toml` 时继续用现有临时隧道行为；有配置但用户把「连接方式」切回临时地址时，配置保留不删，切回去立即生效。

音色包相关代码（`voice_packs.py`、`/api/packs/*`、`PackTransfer.kt`）**不保留、不降级为「隐藏功能」**——留着就是一条随时会被误用的死路，且它依赖的 `pack` 概念本身就是本次要删掉的那一层。

## 六、需要用户配合的一次性动作

阶段 1 开工前需要做一次，我做不了（要登录 Cloudflare 账号）：

1. 打开 https://dash.cloudflare.com/?to=/:account/zero-trust/networks/tunnels（设置页那个「打开 Cloudflare 后台」按钮就是它）。
2. Create a tunnel → 选 **Cloudflared** → 起名（建议 `qingjian-home`，第二台用 `qingjian-work`）。
3. 创建完成后页面给出安装命令，复制其中 `--token` 后面那一整串（`eyJhIjoi...`，约两百字符）——这就是隧道令牌。
4. 同一页 **Public Hostname → Add**：Subdomain 填 `speech`，Domain 选 `qingjian.app`，Service 填 `http://localhost:21835`。**这一步漏了的表现是「隧道连上了但访问 502」**。
5. 把令牌粘进设置页「隧道令牌」输入框，点保存。

全程不碰命令行、不需要 `cloudflared tunnel login`、不需要拷任何文件。

第 2 台电脑时重复 1–5，隧道名与子域换成 `qingjian-work` / `speech-b.qingjian.app`。

**不建议**的替代做法：粘贴 Cloudflare API Token（带 Tunnel:Edit + DNS:Edit）让设置页自动建隧道、自动绑 DNS，连后台都不用开。技术上可行，但那是**账号级**凭证，泄露范围是整个域名的 DNS 解析；省下的只是四步操作，不划算。

## 七、仍未知

- 用户「打字/听发音那台电脑」是笔记本还是台式机、平时关机频率——影响阶段 1 的开机自启是否必要（若经常关机，自启更重要）。
- 两台电脑的子域名是否用 `speech-a` / `speech-b`，还是各自语义化的名字。
- 8 个内置音色各自的风格词与语言标注（2.5 末段）——需要用户逐个试听后定，本文只定格式。
- **用令牌启动的隧道是否强制读远程托管配置**（2.1 末段）——若 `--url` 被后台 ingress 覆盖，本地不传 `--url` 即可；若必须本地控制 ingress，要退回「本地 `config.yml` + 令牌」。开工第一步先验证这一条，因为它决定 `NamedTunnel` 的启动参数。
- 设置页两个 `TextField` 的宽度与「粘贴/怎么拿这个？/保存」三个按钮在窄窗口下会不会挤（`phrases.rs:159` 的 `TextField` 有 `.width()`，需要按 `page.px()` 定）。
