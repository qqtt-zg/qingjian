"""本机设置与手机状态的同步；配置不包含朗读正文或配对凭证。"""

import json
from pathlib import Path
import threading
import time


class SpeechControl:
    def __init__(self, root):
        self.path = Path(root) / "control.json"
        self.lock = threading.RLock()
        self.value = {"revision": 0, "enabled": True, "allow_notes": False}
        self.device = {}
        self.last_seen = 0.0
        if self.path.is_file():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                self.change(data, initial=True)
            except (OSError, ValueError, TypeError):
                pass

    def snapshot(self):
        with self.lock:
            return dict(self.value)

    def change(self, data, initial=False):
        if not isinstance(data, dict):
            raise ValueError("控制设置格式无效")
        with self.lock:
            updated = dict(self.value)
            if "allow_notes" in data:
                if type(data["allow_notes"]) is not bool:
                    raise ValueError("笔记工作流开关无效")
                updated["allow_notes"] = data["allow_notes"]
            if "enabled" in data:
                if type(data["enabled"]) is not bool:
                    raise ValueError("朗读开关无效")
                updated["enabled"] = data["enabled"]
            # 兼容旧版 control.json 中可能残留的 pack/speaker 字段，静默忽略
            if initial:
                updated["revision"] = max(0, int(data.get("revision", 0)))
            else:
                updated["revision"] += 1
                self.path.parent.mkdir(parents=True, exist_ok=True)
                temporary = self.path.with_suffix(".tmp")
                temporary.write_text(json.dumps(updated), encoding="utf-8")
                temporary.replace(self.path)
            self.value = updated

    def acknowledge(self, data):
        if not isinstance(data, dict) or not isinstance(data.get("status", ""), str) or len(data.get("status", "")) > 300:
            raise ValueError("手机状态无效")
        with self.lock:
            revision = data.get("control_revision", -1)
            if type(revision) is not int:
                raise ValueError("手机状态版本无效")
            self.device = {"status": data.get("status", ""), "control_revision": revision}
            self.last_seen = time.monotonic()

    def desktop(self):
        with self.lock:
            return {
                "control": dict(self.value),
                "phone_connected": time.monotonic() - self.last_seen < 35,
                "phone_status": self.device.get("status", "尚未连接支持控制同步的手机 App"),
            }
