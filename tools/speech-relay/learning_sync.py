"""已配对手机的生词来源与可读学习备份，不读写输入法词频或手机成绩。"""
import json
import os
from pathlib import Path
import threading


class LearningSync:
    def __init__(self, root, vocabulary):
        self.root = Path(root)
        self.vocabulary = Path(vocabulary)
        self.lock = threading.Lock()

    def words(self):
        if not self.vocabulary.is_file():
            return {"words": [], "available": False}
        if self.vocabulary.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("电脑生词本过大")
        rows = {}
        for line in self.vocabulary.read_text(encoding="utf-8-sig").splitlines():
            parts = line.split("\t")
            if line.startswith("#") or len(parts) < 7 or parts[0] not in ("en", "en-US", "en-GB"):
                continue
            if not 1 <= len(parts[1]) <= 500:
                continue
            try:
                counts = [int(v) for v in parts[2:7]]
                if min(counts) < 0:
                    continue
            except ValueError:
                continue
            rows[parts[1].casefold()] = {"english": parts[1], "seen": counts[0], "committed": counts[1], "used": counts[2], "last": counts[4], "desktop_known": len(parts) > 7 and parts[7] == "1", "meaning": parts[8][:1000] if len(parts) > 8 else "", "example": parts[9][:1000] if len(parts) > 9 else ""}
        return {"words": list(rows.values()), "available": True}

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
