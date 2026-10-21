"""已配对手机的生词来源与可读学习备份，不读写输入法词频或手机成绩。"""
import json
import os
from datetime import date, datetime, time, timezone
from pathlib import Path
import threading


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


class LearningSync:
    def __init__(self, root, vocabulary):
        self.root = Path(root)
        self.vocabulary = Path(vocabulary)
        self.lock = threading.Lock()

    def words(self):
        if not self.vocabulary.is_file():
            return {"words": [], "available": False, "invalid_date_rows": 0}
        if self.vocabulary.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("电脑生词本过大")
        rows = {}
        invalid_dates = 0
        for line in self.vocabulary.read_text(encoding="utf-8-sig").splitlines():
            parts = line.split("\t")
            if line.startswith("#") or len(parts) < 7 or parts[0] not in ("en", "en-US", "en-GB"):
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
            rows[parts[1].casefold()] = {"english": parts[1], "seen": counts[0], "committed": counts[1], "used": counts[2], "last": midnight_seconds(last), "desktop_known": len(parts) > 7 and parts[7] == "1", "meaning": parts[8][:1000] if len(parts) > 8 else "", "example": parts[9][:1000] if len(parts) > 9 else ""}
        return {"words": list(rows.values()), "available": True, "invalid_date_rows": invalid_dates}

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
