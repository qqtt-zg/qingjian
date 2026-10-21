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
    def __init__(self, root, vocabulary):
        self.root = Path(root)
        self.vocabulary = Path(vocabulary)
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
                    result.append({"language": language, "word": parts[0],
                                   "pos": part_of_speech, "text": text[:500], "reading": reading})
                if len(result) >= CONTEXT_MAX_GLOSSES:
                    return result
        return result

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
            "words": [],
            "glosses": [],
        }
        if not self.vocabulary.is_file():
            return envelope
        if self.vocabulary.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("电脑生词本过大")
        rows, invalid_dates = self._read_rows()
        today = datetime.now().astimezone().date()
        floor = today - timedelta(days=days)
        inside = [row for row in rows if parse_day(row["last"]) >= floor]
        # 稳定排序：上屏多的在前，同数按语言、词排。截断时也按这个顺序，留下的总是更相关的。
        inside.sort(key=lambda row: (-row["committed"], row["language"], row["english"]))
        envelope["total"] = len(inside)
        envelope["available"] = True
        envelope["invalid_date_rows"] = invalid_dates
        envelope["glosses"] = self._personal_glosses()
        kept, truncated = self._fit(inside, envelope)
        envelope["words"] = kept
        envelope["truncated"] = truncated
        return envelope

    def _fit(self, words, envelope):
        """按字节上限裁剪词条。返回 (留下的词条, 是否截断)。

        上限按 UTF-8 字节算，响应整体不超过 2 MiB；超了就把靠后的词条丢掉，
        并标 truncated 与总数，手机据此知道这不是全部。
        """
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

    def backup(self, data):
        if not isinstance(data, dict) or set(data) != {"schema", "created", "tables"} or data["schema"] != 1:
            raise ValueError("学习备份格式无效")
        allowed = {"words", "word_materials", "messages", "conversations", "learning_actions", "word_workspaces", "oral_recordings", "quiz_attempts", "oral_assessments", "learning_events", "independent_exams", "learner_memory", "ai_usage", "study_plans", "study_items", "study_questions", "study_reviews", "desktop_sources"}
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
