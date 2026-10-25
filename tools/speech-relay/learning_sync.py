"""已配对手机的生词来源与可读学习备份，不读写输入法词频或手机成绩。"""
import json
import os
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
import threading


# 词库里只取英语这一族：手机的学习资料是英中的，其他语言的译词在手机上用不上。
LANGUAGES = ("en", "en-US", "en-GB")

# 上下文的契约：窗口天数、响应字节上限。
CONTEXT_SCHEMA = 1
CONTEXT_DAYS = 30
CONTEXT_MAX_BYTES = 2 * 1024 * 1024
CONTEXT_MAX_GLOSSES = 2000

# 词性缩写，与 crates/qingjian-core/src/candidate/part_of_speech.rs 的 FromStr 一致。
POSITIONS = {"n", "noun", "v", "verb", "adj", "adv", "pron", "prep", "conj", "num",
             "m", "mw", "part", "int", "interj", "phr", "phrase"}


def parse_day(text):
    """桌面的首见／末见是 ISO 日历日期（格式见 crates/qingjian-learning/src/vocabulary_book/file.rs）。

    日期坏掉时返回 None。桌面端对日期列用 ? 短路，日期一坏整行丢弃，下次保存重写文件时
    该词条就没了；这里跟它一致，不宽容保留，否则手机会看到一个桌面端认为不存在的词。
    """
    value = text.strip()
    if len(value) != 10 or value[4] != "-" or value[7] != "-":
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def midnight_seconds(day):
    """ISO 日期转 UTC 午夜的 Unix 秒数。旧的 `last` 字段按秒数解释，协议不变。"""
    return int(datetime.combine(day, time.min, tzinfo=timezone.utc).timestamp())


def parse_sense(field):
    """`[词性. ]译词[|读音]` → (词性, 译词, 读音)。规则与 crates/qingjian-translate 的 parse_sense 一致。"""
    reading = None
    if "|" in field:
        text, tail = field.split("|", 1)
        if tail.strip():
            field, reading = text.strip(), tail.strip()
    head, space, rest = field.partition(" ")
    if space and head.endswith("."):
        abbreviation = head.strip().rstrip(".").lower()
        if abbreviation in POSITIONS:
            return abbreviation + ".", rest.strip(), reading
    return None, field, reading


def local_now():
    """本机时间，带时区偏移。手机按这个算「今天」，不混用日期、秒数、毫秒。"""
    return datetime.now().astimezone().isoformat(timespec="seconds")


def encoded_size(value):
    """按 reply() 的序列化方式算字节数。分隔符必须一致，否则算出来的上限对不上。"""
    return len(json.dumps(value, ensure_ascii=False).encode("utf-8"))


class LearningSync:
    def __init__(self, root, vocabulary, app_events=None, intents_file=None):
        self.root = Path(root)
        self.vocabulary = Path(vocabulary)
        self.app_events = Path(app_events) if app_events else (self.root / "learning-app-events.json")
        self.intents_file = Path(intents_file) if intents_file else (self.root / "learning-intents.json")
        self.lock = threading.Lock()

    def _read_rows(self):
        """读桌面的生词本。返回 (词条列表, 日期坏掉的行数)。

        日期坏掉的整行跳过，与桌面端一致（见 parse_day）。词条按词条本身去重，
        后出现的覆盖先出现的，与桌面端写回时取后一条为准一致。
        """
        rows = {}
        invalid_dates = 0
        for line in self.vocabulary.read_text(encoding="utf-8-sig").splitlines():
            parts = line.split("\t")
            if line.startswith("#") or len(parts) < 7 or parts[0] not in LANGUAGES:
                continue
            if not 1 <= len(parts[1]) <= 500:
                continue
            try:
                counts = [int(v) for v in parts[2:5]]
                if min(counts) < 0:
                    continue
            except ValueError:
                continue
            first = parse_day(parts[5])
            last = parse_day(parts[6])
            if first is None or last is None:
                invalid_dates += 1
                continue
            rows[parts[1].casefold()] = {
                "language": parts[0],
                "english": parts[1],
                "seen": counts[0],
                "committed": counts[1],
                "used": counts[2],
                "first": first.isoformat(),
                "last": last.isoformat(),
                "desktop_known": len(parts) > 7 and parts[7] == "1",
                "origin_text": parts[8][:1000] if len(parts) > 8 else "",
                "example": parts[9][:1000] if len(parts) > 9 else "",
                "apps": None,
            }
        return list(rows.values()), invalid_dates

    def _personal_glosses(self):
        """个人释义表原样读出：一种学习语言一个文件（`user-glossary-<语言>.tsv`），与词库同目录。

        桌面端不比对随包表：那要它能读 .qj，得为此写一个 Python 读器，而随包表本来就该在
        手机上。差集在手机端做——手机拿本地随包表比对后再合并，个人表优先。

        返回 (释义条目, 是否因条数上限被截断)。截断必须报出去，静默少几条的话手机会把
        缺的释义当成「桌面没有」，转头去问 AI。
        """
        result = []
        for path in sorted(self.vocabulary.parent.glob("user-glossary-*.tsv")):
            language = path.stem[len("user-glossary-"):]
            if language not in LANGUAGES:
                continue
            try:
                lines = path.read_text(encoding="utf-8-sig").splitlines()
            except OSError:
                continue
            for line in lines:
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if not parts[0] or not 1 <= len(parts[0]) <= 500:
                    continue
                for cell in parts[1:]:
                    if not cell.strip():
                        continue
                    part_of_speech, text, reading = parse_sense(cell)
                    if len(result) >= CONTEXT_MAX_GLOSSES:
                        return result, True
                    result.append({"language": language, "word": parts[0],
                                   "pos": part_of_speech, "text": text[:500], "reading": reading})
        return result, False

    def words(self):
        if not self.vocabulary.is_file():
            return {"words": [], "available": False, "invalid_date_rows": 0}
        if self.vocabulary.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("电脑生词本过大")
        rows, invalid_dates = self._read_rows()
        words = []
        for row in rows:
            words.append({
                "english": row["english"],
                "seen": row["seen"],
                "committed": row["committed"],
                "used": row["used"],
                "last": midnight_seconds(parse_day(row["last"])),
                "desktop_known": row["desktop_known"],
                "meaning": row["origin_text"],
                "example": row["example"],
            })
        return {"words": words, "available": True, "invalid_date_rows": invalid_dates}

    def context(self, days=CONTEXT_DAYS):
        """电脑来源的上下文：窗口内的词 + 个人释义表。

        窗口只按 `last` 过滤（上游只有首见／末见两个日期，没有逐日历史）。30 天没碰的词
        不会出现在响应里，手机因此看不到它的累计计数——这不是「词被删除」，响应里不返回
        该词只表示窗口外，手机不得据此清空已有记录。
        """
        envelope = {
            "schema": CONTEXT_SCHEMA,
            "generated_at": local_now(),
            "days": days,
            "apps_status": "not_collected",
            "truncated": False,
            "total": 0,
            "available": False,
            "invalid_date_rows": 0,
            "words": [],
            "glosses": [],
            "glosses_truncated": False,
        }
        if not self.vocabulary.is_file():
            return envelope
        if self.vocabulary.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("电脑生词本过大")
        rows, invalid_dates = self._read_rows()
        today = datetime.now().astimezone().date()
        floor = today - timedelta(days=days)
        inside = []
        for row in rows:
            p_day = parse_day(row["last"])
            if p_day is not None and p_day >= floor:
                inside.append(row)

        # 检查应用日聚合统计（30 天滑动窗口）
        app_stats_by_word = {}
        if self.app_events.is_file():
            try:
                app_data = json.loads(self.app_events.read_text(encoding="utf-8"))
                if not app_data.get("enabled", True):
                    envelope["apps_status"] = "disabled"
                else:
                    envelope["apps_status"] = "collected"
                    records = app_data.get("records", {})
                    for date_str, word_map in records.items():
                        day_obj = parse_day(date_str)
                        if day_obj is not None and day_obj >= floor:
                            for word_key, app_counts in word_map.items():
                                if word_key not in app_stats_by_word:
                                    app_stats_by_word[word_key] = {}
                                for raw_app, counts in app_counts.items():
                                    clean_app = os.path.basename(raw_app)[:64]
                                    if clean_app not in app_stats_by_word[word_key]:
                                        app_stats_by_word[word_key][clean_app] = [0, 0]
                                    app_stats_by_word[word_key][clean_app][0] += counts[0]
                                    app_stats_by_word[word_key][clean_app][1] += counts[1]
            except (ValueError, OSError):
                envelope["apps_status"] = "not_collected"
        else:
            envelope["apps_status"] = "not_collected"

        # 关联词汇应用聚合信息（前 3 名 + other）
        for row in inside:
            w_key = f"{row['language']}:{row['english']}"
            if w_key in app_stats_by_word and app_stats_by_word[w_key]:
                sorted_apps = sorted(app_stats_by_word[w_key].items(), key=lambda p: (-(p[1][0] + p[1][1]), p[0]))
                apps_list = []
                for app_name, (c, u) in sorted_apps[:3]:
                    apps_list.append({"app": app_name, "commit_with_gloss": c, "gloss_typed": u})
                if len(sorted_apps) > 3:
                    other_c = sum(p[1][0] for p in sorted_apps[3:])
                    other_u = sum(p[1][1] for p in sorted_apps[3:])
                    apps_list.append({"app": "other", "commit_with_gloss": other_c, "gloss_typed": other_u})
                row["apps"] = apps_list
            else:
                row["apps"] = None

        # 稳定排序：上屏多的在前，同数按语言、词排。截断时也按这个顺序，留下的总是更相关的。
        inside.sort(key=lambda row: (-row["committed"], row["language"], row["english"]))
        envelope["total"] = len(inside)
        envelope["available"] = True
        envelope["invalid_date_rows"] = invalid_dates
        glosses, glosses_truncated = self._personal_glosses()
        envelope["glosses"] = glosses
        envelope["glosses_truncated"] = glosses_truncated
        kept, truncated = self._fit(inside, envelope)
        envelope["words"] = kept
        envelope["truncated"] = truncated
        return envelope

    def _fit(self, words, envelope):
        """按字节上限裁剪词条。返回 (留下的词条, 是否截断)。

        上限按 UTF-8 字节算，响应整体不超过 2 MiB；超了就把靠后的词条丢掉，
        并标 truncated 与总数，手机据此知道这不是全部。

        个人释义表先占额度、再排词条：它是「桌面独有、手机拿不到」的数据，丢掉就
        只能回去问 AI；词条这边丢掉的只是本轮候选，窗口外本来也会再出现。释义表
        自己就撑爆上限时，连它一起裁——上限是硬约定，不能因为某一段优先就破例。
        """
        base = encoded_size(envelope)
        budget = CONTEXT_MAX_BYTES - base
        if budget < 0:
            # 释义表自己就撑爆上限：把它裁到放得下。base 是按当前（未裁的）释义表算的，
            # 加上它的序列化长度正好还原出「空列表时还剩多少额度」。
            envelope["glosses"] = self._fit_glosses(envelope["glosses"], budget + encoded_size(envelope["glosses"]))
            envelope["glosses_truncated"] = True
            base = encoded_size(envelope)
            budget = CONTEXT_MAX_BYTES - base
        kept = []
        used = 0
        for word in words:
            size = encoded_size(word) + 2          # 词条之间的 ", "
            if used + size > budget:
                return kept, True
            kept.append(word)
            used += size
        return kept, False

    def _fit_glosses(self, glosses, budget):
        """个人释义表超上限时按原顺序裁到放得下为止。"""
        kept = []
        used = 0
        for gloss in glosses:
            size = encoded_size(gloss) + 2
            if used + size > budget:
                return kept
            kept.append(gloss)
            used += size
        return kept

    def backup(self, data):
        if not isinstance(data, dict) or set(data) != {"schema", "created", "tables"} or data["schema"] != 1:
            raise ValueError("学习备份格式无效")
        allowed = {
            "words", "word_materials", "messages", "conversations", "learning_actions",
            "word_workspaces", "oral_recordings", "quiz_attempts", "oral_assessments",
            "learning_events", "independent_exams", "learner_memory", "ai_usage",
            "study_plans", "study_items", "study_questions", "study_reviews",
            "desktop_sources", "learner_model", "user_overrides", "study_item_stages",
            "plan_revisions", "study_intents"
        }
        if not isinstance(data["tables"], dict) or not set(data["tables"]) <= allowed:
            raise ValueError("备份包含不支持的配置")
        for rows in data["tables"].values():
            if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
                raise ValueError("备份记录无效")
        body = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        if len(body) > 20 * 1024 * 1024:
            raise ValueError("备份超过20MiB上限")
        with self.lock:
            self.root.mkdir(parents=True, exist_ok=True)
            target = self.root / "phone-learning-latest.json"
            temporary = self.root / "phone-learning-latest.tmp"
            with temporary.open("wb") as stream:
                stream.write(body)
                stream.flush()
                os.fsync(stream.fileno())
            if target.is_file():
                os.replace(target, self.root / "phone-learning-previous.json")
            os.replace(temporary, target)
        return {"ok": True, "bytes": len(body)}

    def intents(self, now_millis=None):
        """读取未过期的待练意图列表。"""
        if now_millis is None:
            now_millis = int(datetime.now().timestamp() * 1000)
        with self.lock:
            if not self.intents_file.is_file():
                return {"schema": 1, "generated_at": local_now(), "total": 0, "intents": []}
            try:
                data = json.loads(self.intents_file.read_text(encoding="utf-8"))
                items = data.get("intents", []) if isinstance(data, dict) else []
            except (ValueError, OSError):
                return {"schema": 1, "generated_at": local_now(), "total": 0, "intents": []}
            valid = [
                item for item in items
                if isinstance(item, dict) and item.get("expires_at", 0) > now_millis
            ]
            return {"schema": 1, "generated_at": local_now(), "total": len(valid), "intents": valid}

    def add_intent(self, english, meaning="", dimension="", source="desktop", intent_id=None, created_at=None, expires_at=None):
        """向待练队列添加意图，按 ID 或 (english, source) 幂等去重。"""
        english = english.strip()
        if not 1 <= len(english) <= 100:
            raise ValueError("英文词条长度必须在 1 到 100 字符")
        now = int(datetime.now().timestamp() * 1000)
        created = created_at or now
        expires = expires_at or (created + 30 * 86400 * 1000)
        iid = intent_id or f"desktop:{created}:{english.lower()}"

        with self.lock:
            self.root.mkdir(parents=True, exist_ok=True)
            items = []
            if self.intents_file.is_file():
                try:
                    data = json.loads(self.intents_file.read_text(encoding="utf-8"))
                    items = data.get("intents", []) if isinstance(data, dict) else []
                except (ValueError, OSError):
                    items = []
            if any(it.get("id") == iid for it in items):
                return {"ok": True, "id": iid, "status": "existing"}

            new_intent = {
                "id": iid,
                "english": english,
                "meaning": meaning[:500],
                "dimension": dimension[:50],
                "source": source,
                "created_at": created,
                "expires_at": expires
            }
            items.append(new_intent)
            self._write_intents(items)
            return {"ok": True, "id": iid, "status": "added"}

    def ack_intents(self, acked_ids):
        """确认已拉取落库的意图 ID，将其从活动队列中移出。严格幂等。"""
        if not isinstance(acked_ids, list):
            raise ValueError("acked_ids 必须是列表")
        acked_set = {str(x) for x in acked_ids}
        with self.lock:
            if not self.intents_file.is_file():
                return {"ok": True, "acked_count": 0}
            try:
                data = json.loads(self.intents_file.read_text(encoding="utf-8"))
                items = data.get("intents", []) if isinstance(data, dict) else []
            except (ValueError, OSError):
                return {"ok": True, "acked_count": 0}

            remaining = [it for it in items if it.get("id") not in acked_set]
            removed_count = len(items) - len(remaining)
            self._write_intents(remaining)
            return {"ok": True, "acked_count": removed_count}

    def _write_intents(self, items):
        temporary = self.intents_file.with_suffix(".tmp")
        payload = json.dumps({"schema": 1, "intents": items}, ensure_ascii=False, indent=2).encode("utf-8")
        with temporary.open("wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.intents_file)
