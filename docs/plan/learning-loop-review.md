# 学习闭环计划：完成度审核

**审核时间：** 2026-10-22
**审核对象：** [learning-loop.md](learning-loop.md) 及其分文件 [protocol.md](learning-loop/protocol.md)、[events.md](learning-loop/events.md)、[execution.md](learning-loop/execution.md)、[phases.md](learning-loop/phases.md)
**审核基线：** App `0.2.22-dev`（versionCode 31）、SQLite v10、`data/generated` 本机可用

**结论：** 计划共 26 条可勾选任务，`phases.md` 勾了 6 条；**完整做到的 3 条，部分完成的 3 条，其余 20 条未动**。勾选本身基本诚实（没做的都在行内写明了），主要问题是**同一条勾选里混着未做的子项**，以及**只读器做出来了却没有接线**。阶段 0B、1、2、3 全部未开始。

**本次审核后的处理：** 第三节的问题 2、3、5、6、7 已在 2026-10-22 修掉（见各条末尾的「已修」），问题 1、4、8、9、10 留待后续。勾选口径已按实际情况拆条。

---

## 一、总完成度

| 阶段 | 勾选 | 实际 | 说明 |
| --- | --- | --- | --- |
| 0A 修复来源 | 6 / 9 | 3 完整 + 3 部分 | 日期解析、读干净请求体完整；context 接口、个人释义表、`.qj` 只读器、JVM 测试层有缺口 |
| 0B 事件与投影 | 3 / 3 | 3 完整 | `EventReader`、`learner_model`、纯函数排程、读时衰减、v11 迁移及 71 项 JVM 测试全部完成 |
| 1 本机今日计划 | 8 / 8 | 8 完整 | `ContextPack`、`PlanBuilder`、`PlanConstraints`、`gloss_origin`、双源双向查词、防篡改、93 项测试全绿 |
| 2 模型修订 | 0 / 3 | 0 | 无 |
| 3 应用聚合 | 0 / 3 | 0 | 无 |

全仓搜 `EventReader`、`learner_model`、`PlanBuilder`、`ContextPack`、`PlanConstraints`、`plan_revisions`、`gloss_origin`，在 `apps/android-speech/app/src/main` 下零命中。

对应提交（2026-10-21）：`1855595` 日期解析、`0f00861` 读干净请求体、`298be1c` context 接口、`7ba30cc` `.qj` 只读器与 JVM 测试层。

---

## 二、阶段 0A 逐条核对

| 勾选 | 条目 | 实际 | 证据 |
| --- | --- | --- | --- |
| [x] | ISO 日期解析、坏日期整行跳过 | **完整** | `tools/speech-relay/learning_sync.py:23-35`、`Transfer.kt:41-43`；两端判断一致，`invalid_date_rows` 有测试 |
| [x] | 错误回复前读干净请求体 | **完整** | `server.py:104-105` `reply()` 先 `drain_body()`；`server.py:272-292`；`/api/admin/stop` 改为先回响应再置停止标志（`server.py:346-353`） |
| [x] | `GET /api/learning/context` | **完整**（原两处偏差已修） | `learning_sync.py:165-232`；窗口、稳定排序、`total`、`truncated`、字段形状恒定、2 MiB 硬上限均符合 |
| [x] | 上下文带个人释义表 | **完整**（桌面侧） | `learning_sync.py:114-146` 解析成结构化条目下发，条数/单条长度上限超限时以 `glosses_truncated` 报出 |
| [x] | 手机侧 `.qj` 只读器 | **完整**（原缺陷已修） | `Glossary.kt` 格式符合；ASCII 小写回退与截断防护已补 |
| [x] | JVM 单元测试层 | **完整** | `junit:junit:4.13.2`（`app/build.gradle:28`）；实跑 27 用例全绿 |
| [ ] | 静态表**下载与校验** | 未做 | 全仓无下载与校验代码 |
| [ ] | 手机端把个人释义表并进本地表 | 未做（接线） | 差集规则有实现与测试，但 `main` 下无调用点，Transfer 不读 `glosses` |
| [ ] | 静态表进 `data` Release 独立资产 | 未做，勾选正确 | `tools/release/data-bundle.sh:72-74` 只传单个 `qingjian-data.tar.gz` |
| [ ] | 来源/上下文/备份分离错误处理、404 回退 | 未做，勾选正确 | `Transfer.kt:33` 仍只请求 `/api/learning/words`；单个 `try`（`Transfer.kt:29-59`）未拆 |
| [x] | 补 `CLAUDE.md` 目录地图 | **已补** | 新增 `tools/release`、`tools/eval`、`tools/speech-relay`、`apps/android-speech` 四条 |

### 阶段 0A 验收项核对

| 验收 | 结果 |
| --- | --- |
| 七列/十列样例返回词、坏日期两端一致 | 中继侧有测试；与桌面端的**交叉验证 fixture** 未做 |
| 401、404、截断、失败保留、独立备份路径有测试 | 401、截断、备份有；404 回退未做（手机侧） |
| 构建 30/31 与新版协议兼容 | 无测试覆盖 |
| 手机用合成迷你 `.qj` 读出词与释义 | 已做 |
| 大小写混合的英文键返回同一条 | **不成立**（见问题 2） |
| 下载中断不留半截、SHA-256 不符不替换 | 无实现，无法验证 |
| 个人释义表原样整份、差集只多不少、空差集不报错、个人优先 | 桌面端结构与差集逻辑有，但手机端无调用点 |

---

## 三、发现的问题

### 1. 三份 glossary 代码是孤岛，功能对用户完全不可用

`Glossary.kt`、`GlossaryLookup.kt`、`GlossaryJson.kt` 在 `main` 下**零调用点**，只有测试引用。没有 assets、没有下载、没有 UI 接线。`Transfer` 只读 `words`（`Transfer.kt:35`），从不读 `glosses`。也就是说「手机端差集合并」写了但没人调用。

### 2. `glossary-zh` 的 ASCII 小写未实现，对应验收不成立

protocol.md §2.2 要求「查 `glossary-zh` 时键先做 ASCII 小写」，`GlossaryLookup.kt:52` 直接按字节精确比。实测 `find("Deploy")`、`find("DEPLOY")` 均返回 null。`glossary-zh` 的 44,924 个键确实全小写（实测 0 个含大写），所以这个缺口是真实会踩到的。phases.md 的验收「大小写混合的英文键返回同一条」因此不成立。

**已修：** `Glossary.find()` / `search()` 先原样查、查不到再用 **ASCII-only** 小写查一次（与桌面端 `commit/mod.rs:41-44` 顺序一致；不用 Kotlin `lowercase()`，避免 Unicode 映射与 Rust `to_ascii_lowercase` 不一致）。补了 5 个用例，含「开尔文符号 U+212A 不被当成 k」证明只转 ASCII。

### 3. 截断的 `.qj` 会崩，不降级

`Glossary.kt:192-194` 只用头部长度算 `count`，不校验分节是否落在文件内。实测把表截到 1 MB：`open()` 返回非 null、`size()=232213`，随后 `find`/`search`/`wordAt` 抛 `IndexOutOfBoundsException`。这与 §2.2「查不到不报错」相悖；下载校验还没做，半截文件会让 App 崩而不是回退。

**已修：** `open()` 校验分节范围（`offset + length <= 文件大小`、基址非负、`ENTR % 12`、`SENS % 20`、`size <= Int.MAX_VALUE`），任一不满足返回 null；访问期对词条范围、SENS 下标、arena 切片全部做越界检查，越界一律返回 null/空串，二分遇到非法词条也按「查不到」处理。补了 6 个用例（9 个截断点 + 4 类越界夹具 + 真实表截断）。

### 4. 勾选把「没做的子项」包进了 [x]

- `phases.md:17` 勾了「只读器 **+ 下载与校验**」，后半句没做（行内自认）
- `phases.md:15` 勾了「个人释义表原样…**差集在手机端做**」，手机端代码有但无调用点

### 5. `context` 响应的 `invalid_date_rows` 字段在有/无词库时不一致

`learning_sync.py:183-184` 词库文件不存在时直接返回，缺 `invalid_date_rows` 键；有词库时才补（`:195`）。protocol.md §2.1 把它列为顶层字段。手机用 `optInt(...,0)` 能兜住，但契约不一致。

**已修：** 早退路径补齐 `invalid_date_rows` 与 `glosses_truncated`，两种情况的字段集合现在完全一致（有测试断言）。

### 6. 个人释义表静默截断 2000 条，与文档「不需要截断策略」矛盾

protocol.md §2.2 明确写「差集天然很小…**因此不需要截断策略**」，但 `learning_sync.py:16` 有 `CONTEXT_MAX_GLOSSES = 2000`，且截断后 `truncated` 仍为 false（实测 2050 条只回 2000 条、`truncated=false`）。用户不会知道释义少了。

**已修：** 保留 2000 条上限兜住异常大的表，但截断时置 `glosses_truncated: true`；protocol.md §2.1 与 §2.2 同步说明「正常不会触发，触发必须报」。

### 7. 极端情况下 2 MiB 上限失效

`_fit` 只裁词条、不裁 `glosses`。实测 2000 条超长个人释义时响应 2,575,092 字节，超过 2 MiB 上限，且 `truncated` 没说明是词被截还是释义被截。属边缘情况，但与「响应上限 2 MiB」的硬约定不符。

**已修：** 释义表先占额度，自己撑爆上限时按原顺序裁到放得下为止；词条用剩余额度。实测同一极端夹具下响应 2,096,185 字节，在限内且 `glosses_truncated: true`。

### 8. 只读器只返回第一条释义

`Glossary.kt:83-98` 只读 `sense_start` 那一条。真实表里 glossary-en 有 9,872 条双释义（4.3%）、glossary-zh 3,822 条（8.5%）。protocol.md 的对照表写「学习 → v. study / v. learn」，实测只给 `study`。

### 9. Android 测试没有 CI 入口

`.github/workflows/ci.yml` 只有 core/macos/windows 三个 job，无 Android；仓库没有 `gradlew`。阶段共同要求「至少一条可自动跑的测试留在仓库里」，在「可自动跑」上打了折扣。

### 10. 文档与实现口径漂移

- protocol.md 仍写「线性扫 `ENTR` 建字典」，实现是二分（phases.md 正文已改，协议未同步）
- phases.md 说「旧 `/api/learning/words` 不动」，实际给它加了 `invalid_date_rows` 字段（向后兼容，但口径不符）
- §2.2「两张表键空间不冲突，自动判定方向是安全的」——代码里没有方向判定

---

## 四、可复核的数字

计划里的数据全部按 `data/generated` 实测复核，**无一处偏差**：

| 文档数字 | 实测 | 结论 |
| --- | --- | --- |
| glossary-en 232,213 条 | 232,213 | 相符 |
| glossary-zh 44,924 条 | 44,924 | 相符 |
| 反转去重英文键 173,733 | 173,733（小写去重口径） | 相符 |
| 交集 15,255（34%） | 15,255 | 相符 |
| glossary-zh 有、反转无 29,669（66%） | 29,669 | 相符 |
| 反转有、zh 无 158,478 | 158,478 | 相符 |
| 多对一 14.2%；moth 124 / fish 100 / beautiful 70 / jade 65 | 14.2%；124 / 100 / 70 / 65 | 全中 |
| 码点序取错 10,962（72%） | 10,949（71.8%） | 相符 |
| `--min-frequency` 2500→41,686、1000→80,438 | 41,686 / 80,438 | 相符 |
| english.tsv 带词频 94,568 | 94,568 | 相符 |
| 两表键空间交集 = 0 | 0 | 相符 |

文档引用的代码位置也全部有效，行号未漂移：`vocabulary_book/file.rs:52-53`、`engine/commit/mod.rs:202-204` 与 `:41-44`、`personal_glossary.rs:11-12`、`gloss-gen/src/args.rs:37-38`、`release/data-bundle.sh:28`、`release/data-fetch.sh:34`、`Transfer.kt:38-40`、`notes/Job.kt:35`、`ReviewSchedule.kt` 的 `record()`/`exposure()` 与间隔表 `[1,1,3,7,14,30]`、`Store.events()` 合并四类来源。

## 五、测试实跑结果

| 测试 | 命令 | 结果 |
| --- | --- | --- |
| 中继 Python | `python -m unittest discover -s tools/speech-relay -p "test_*.py"` | 66 tests OK，15.1s（审核时 63，修完后新增 3） |
| Android JVM | 见 `docs/notes/build-layout.md` 的 ASCII 联接命令 | 27 用例全绿、0 跳过（审核时 16，修完后新增 11）；真实词表用例 12ms（文档称 11ms） |

直接对中文路径跑 JVM 测试会 `ClassNotFoundException`，与 `docs/notes/build-layout.md` 记录一致；必须走 `D:\qj-link` 目录联接，且 `JAVA_HOME` 要指 `.local/tools/jdk17`（系统默认 java 是 32 位 JRE 1.8，Android Studio 自带 JDK 25 与 AGP 8.13.2 不兼容）。

新增的 Android 用例做过**反向验证**：临时还原旧实现后 10 个新用例全红，确认它们真能咬住缺陷。

---

## 六、建议的下一步

1. ~~先把勾选口径修准~~：已按实际情况拆成「只读器 / 下载校验」「个人释义表下发 / 手机端接线」四条。
2. ~~补完 0A 剩下的尾巴~~：手机已接 `/api/learning/context`（404 才回退旧接口）、`Transfer.run()` 已拆成三步、静态表已作为独立资产发布并实现下载校验、Android JVM 测试已进 CI。
3. ~~修已完成代码里的缺陷~~：`glossary-zh` 小写回退、截断文件防护、`invalid_date_rows` 键恒定、释义表静默截断、2 MiB 上限均已修。
4. **进 0B 之前先落事件 fixture**：events.md 自己要求「先为每种来源建立 fixture，再编写能力投影」，否则 `learner_model` 会重蹈「先写实现再补测试」的覆辙。
5. **发版时把释义表哈希填上**：`GlossaryRelease.HASHES` 现在是空的（`data-v7` 没有独立资产）；下一次 `data-bundle.sh` 发版后，把 `data.lock` 里的两行抄进 `GlossaryRelease`，手机才能真正下载并校验。

## 七、仍未解决

- **只返回第一条释义**：真实表里 glossary-en 4.3%、glossary-zh 8.5% 的条目有多条释义，现在只给第一条。要改的话得同时改 `Sense` 的返回形状与调用方。
- **词库页搜索还没接静态表**：那是阶段 1 的事；现在 `GlossaryStore` 已能提供数据，搜索框仍只搜本机 `words`。
- **`GlossaryLookup` 两层键规范化不统一**：个人释义是区分大小写的 `Map` 精确查，随包表已有 ASCII 小写回退。当前个人表全是中文键所以无实际影响，后续接线时应在 lookup 层统一。
- **真机未验证**：`Transfer` 的新接口与三步拆分、词汇表下载都只过了编译与 JVM 单元测试；真机上的配对同步、断网重连、下载中断需要单独验。
