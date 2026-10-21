# 学习闭环：事件契约与能力投影

本文件是 [学习闭环总览](../learning-loop.md) 的**第三章**。章节号在总览里连续编号，跨文件引用按「§几」写。
## 三、先规范事件，再建立学习模型

### 3.1 规范化事件契约

新增只读 `EventReader`，由现有表及 `archive.Store.events()` 补齐来源信息，输出规范化事件；不复制一份成绩再双重计分。

| 字段 | 约定 |
| --- | --- |
| `event_id` | 来源命名空间 + 稳定原始ID，重读和重试不变 |
| `word_id/dimension` | 真实词ID及一个主要训练维度 |
| `occurred_at/day/zone` | UTC毫秒及可验证的作答日/时区；旧记录不足时标注时间精度 |
| `outcome` | graded / assessment / self_review / exposure / skipped / abandoned |
| `correct/assisted` | 正误可为null；辅助与独立性未知分别表示，不能默认独立正确 |
| `score/provider/reference_mode` | 评测的真实分数、提供商与单词/句子模式；缺失为null |
| `plan_id/stage_key` | 如来自计划，关联固定环节；否则为空 |
| `app` | 事件发生时的应用标识。手机自产事件为null；阶段0不采集，阶段3接入桌面侧聚合后再补。缺失表示未知，不以空值当作“不在桌面用过”，也不按应用名推断职业或行业 |

表里的“来源命名空间”指 `event_id` 的前缀（哪个物理表来的），与 §2.1 的 `origin_text`（词的中文来源）和 §3.2 的 `origin`（phone/desktop/both 归属）是三件事，字段名不共用。

映射规则：

| 现有来源 | 主要维度及处理 |
| --- | --- |
| 盲写事件 | spelling；保留提示级别与首次错误 |
| 普通词义选择、英文选词、搭配选择 | meaning；不能把选出英文算成独立拼写 |
| 独立考试 | 根据payload中的看义拼写/听写/语境填词分别映射spelling/listening/spelling |
| 每日题目 | 先关联固定题号；`listening=true` 优先映射listening，文字辅助单独保留 |
| 旧词卡或练习页拼写 | 资料可能可见且没有辅助证据时，独立性为unknown，不补算独立连胜 |
| 发音评测 | 仅成功、有效且目标匹配的结果进入pronunciation |
| 看过、播放、自评、跳过、放弃 | 分别记录，不当作独立正确次数 |

同一次听写只产生一次主要能力作答，不同时制造拼写和听辨两次独立成功。句子总分不直接覆盖单词发音状态；只有可验证对应到该词的有效词级分数可用于词级更新。

- 按 `occurred_at + event_id` 稳定回放，同一个 `event_id + dimension` 只处理一次。
- 重试提交不新增成绩；同一录音多次有效评测都保留历史，但不靠同一录音重评累计独立成功日。
- 重建时保留原始答案、分数及辅助信息。无法恢复的维度/独立性保留unknown；旧计划更新时间不冒充精确答题时间。
- 独立性unknown的旧成绩可以展示，但不推进独立能力状态或next_due。已删除词条的历史可留在档案，不由投影重建成新的活动词条。
- 删除录音后的提交次数仍保留；音频缺失不补成0分。清空学习资料后，以epoch拒绝旧任务和同步回执。
- 阶段0先为以上每种来源建立fixture，再编写能力投影；不得等 `PlanBuilder` 出现后才定义测试输入。

### 3.2 learner_model是可重建投影

目标SQLite版本暂定v11；若实际开发前数据库已有新版本，顺延迁移号，不重复使用版本号。投影由确定性代码更新，模型没有写入工具。

```sql
CREATE TABLE learner_model (
  word_id INTEGER NOT NULL,
  dimension TEXT NOT NULL,
  state TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  correct INTEGER NOT NULL DEFAULT 0,
  assisted INTEGER NOT NULL DEFAULT 0,
  independent_attempts INTEGER NOT NULL DEFAULT 0,
  independent_correct INTEGER NOT NULL DEFAULT 0,
  streak INTEGER NOT NULL DEFAULT 0,
  last_success_day TEXT,
  last_at INTEGER,
  next_due INTEGER,
  score REAL,
  origin TEXT NOT NULL,
  rule_version INTEGER NOT NULL,
  PRIMARY KEY (word_id, dimension)
);
CREATE INDEX learner_due ON learner_model(next_due, state);
```

`attempts/correct`记录有效作答总数及其正确数；辅助次数单列。只有独立性已知的有效作答进入独立统计；`streak`按不同有效作答日增长，不按按钮点击次数增长。

下表是首版可测试的工程启发规则，不是语言能力证书；保存 `rule_version`，后续改阈值可回放原事件验证，不能改写历史成绩。

| 维度/结果 | 状态转换 |
| --- | --- |
| 无有效证据 | unknown；`next_due=null`，不进到期池 |
| spelling/listening独立答对 | 首个成功日为learning；连续两个不同成功日为solid |
| spelling/listening答错 | learning，连胜清零；同日修正仍记录，但不能反复推进复习间隔 |
| 辅助后答对 | shaky；不增加独立正确或连胜，需后续独立验证 |
| meaning | 独立作答不足3次为learning；达到3次且正确率低于0.6为shaky；正确率至少0.8且连胜跨至少两日为solid；其余为learning |
| pronunciation | 首版以有效0～100词级分数为依据：低于60为learning，60至不足80为shaky，至少80为成功；跨至少两日、不同录音成功后为solid，否则learning |
| 失败/取消的评测或缺失指标 | 不增加有效能力attempt，不改能力状态；服务提交次数另计 |
| 自评与电脑认识标记 | 不生成成绩或独立连胜 |

失败、辅助成功和独立成功的先后按事件顺序处理；最新真实失败不能被之前的平均分覆盖。同日只允许一次独立成功日增长，与现有 `ReviewSchedule` 的同日抑制保持一致。

复习日期只在真实有效完成后写入。**现有 `apps/android-speech/.../learning/daily/ReviewSchedule.kt` 只有 `record()` 与 `exposure()` 两个方法，两个都写数据库，间隔表 `[1,1,3,7,14,30]` 硬编码在 `record()` 内部，并没有现成的纯计算函数可复用。** 本阶段的动作是把它**抽成纯函数并冻结旧间隔表**作为旧计划的兼容路径；`learner_model` 用自己的一套规则与 `rule_version`，两套规则各自演进、不互相改写。投影重建只调用纯函数，不调用有副作用的 `record()`；明确旧skill到新dimension的映射，不能让 `study_reviews` 与 `learner_model` 各算各的日期。首版由新投影供新计划读取，旧计划继续原兼容路径。

### 3.3 衰减、覆盖与语言边界

- 读时计算 `effective_due`：solid超过60天未验证则不晚于今天复测；shaky超过30天则将已知到期日提前3天。衰减不反复扣天，不写回“新成绩”，未知日期仍未知。
- AI自然语言记忆只能提供解释；真实作答优先于电脑接触和认识标记。
- 用户手动“已掌握”另存覆盖记录及时间，不篡改原始能力状态。展示可优先使用覆盖，但最晚7天后验证；下一次真实答错解除本次覆盖。
- 电脑来源变化只改来源元数据，不重算或归零手机成绩；`origin`为phone/desktop/both描述数据归属，不是能力评分。
- 本轮只实现英语四个维度。dimension保留文本键，但日语/德语还需要语言身份、词键、题型、评分与TTS适配，不能承诺“只改配置即可支持新语言”。
