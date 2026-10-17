# 本机构建目录

Windows 与 Android 开发将可重新生成的编译文件、本机资源和分发包分别保存。Rust 的默认编译目录不变；不通过目录链接把持久文件留在 `target` 中。

| 目录 | 内容 | 清理约定 |
|---|---|---|
| `target/` | Rust 编译缓存、Windows 打包中转及可重新生成的产品数据压缩包 | 不在构建时清理；阶段结束后按需清理 |
| `apps/android-speech/app/build/` | Gradle 的临时编译输出 | 可用 Gradle clean 清理 |
| `.local/tools/` | cloudflared、便携 Inno Setup、`jdk17/` | 长期保留；不是编译缓存 |
| `.local/downloads/android-speech/` | 固定 SHA-256 的 AAR、模型下载压缩包 | 可重新下载，不影响已准备的模型 |
| `.local/voices/` | 电脑手动导入或传输的音色源文件夹 | 仍需发送模型时保留 |
| `.local/android-avd/` | 本项目模拟器的配置、磁盘与快照 | 保存测试数据，不能作为普通缓存删除 |
| `.local/backups/` | 合并、配置等历史备份 | 确认不再需要后单独清理 |
| `.local/diagnostics/` | 截图、日志及旧测试资料归档 | 按资料用途决定保留时间 |
| `dist/windows/` | 可分发的 Windows 安装器 | 保留需要安装或回退的版本 |
| `dist/android/` | Android 构建脚本导出的 APK | 保留需要安装或回退的版本 |

`.local/`、`dist/` 与 `target/` 都在根 `.gitignore` 中排除。源码、依赖 URL / SHA-256 和构建脚本进 Git；模型、设备资料、凭证和本机安装包不进 Git。

Android 的模型与 AAR 构建输入仍放在模块原有 `app/src/main/assets/` 和 `app/libs/` 中，由 `prepare.py` 准备并按模块规则忽略。它们不是 Gradle 输出，清理 `app/build/` 不会删除这些输入。

## 构建与安装包

在仓库根执行 `pwsh -File apps/android-speech/build.ps1`，输出固定版本与 ABI 命名的 `dist/android/qingjian-learning-<版本>-build<构建号>-<ABI>.apk`。未设置 `JAVA_HOME` 时读取 `.local/tools/jdk17/` 的 JDK；`-Prepare` 的下载缓存写入 `.local/downloads/android-speech/`。

`apps/windows/installer/build.ps1` 的成品为 `dist/windows/qingjian-<版本>-windows-x86_64-setup.exe`。临时运行时文件仍在 `target/installer/settings-runtime/`；发布工作流从 `dist/windows/` 上传安装器。仓库版朗读工具读取 `.local/tools/cloudflared.exe`，安装版布局保持 `{app}/tools/cloudflared.exe`。

本机工具迁移不改变 Windows 输入法的安装目录、注册表、手机配对或更新服务数据。`%APPDATA%/Qingjian/speech/updates` 是独立的手机更新发布目录，只有显式使用 `-PublishUpdate` 才写入。

## 模拟器

`pwsh -File apps/android-speech/emulator.ps1 -List` 列出项目设备。不带参数启动 `QingjianLearning`，`-Headless` 用于无窗口测试，`-ColdBoot` 跳过快照加载。脚本临时指定 `ANDROID_AVD_HOME`，不改全局环境或 Android Studio 的其他设备。

移动 AVD 时先停止模拟器，再更新对应 `.ini` 的 `path` 和生成配置中的旧路径。磁盘文件、相对 backing image 和快照一同保留；迁移前后校验文件，不以新建空模拟器代替数据迁移。Android Studio 默认设备目录中的其他模拟器不参与本项目迁移。

历史诊断与构建输入记录按原样归档，其中记录的旧路径表示当时的构建现场，不作为当前工具路径使用。
