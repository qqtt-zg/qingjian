"""本机设置与手机状态的同步；配置不包含朗读正文或配对凭证。"""

import json
from pathlib import Path
import threading
import time
import re

BUILTIN = ["男声 1", "女声 1", "男声 2", "女声 2", "男声 3", "女声 3", "男声 4", "女声 4"]


class SpeechControl:
    def __init__(self, root, packs):
        self.path = Path(root) / "control.json"
        self.packs = packs
        self.lock = threading.RLock()
        self.value = {"revision": 0, "enabled": True, "pack": "builtin", "speaker": 0}
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
            if "enabled" in data:
                if type(data["enabled"]) is not bool:
                    raise ValueError("朗读开关无效")
                updated["enabled"] = data["enabled"]
            if "pack" in data:
                pack = data["pack"]
                if pack != "builtin":
                    self.packs.info(pack)
                updated["pack"] = pack
            if "speaker" in data:
                if type(data["speaker"]) is not int or not 0 <= data["speaker"] < 1024:
                    raise ValueError("音色编号无效")
                updated["speaker"] = data["speaker"]
            if updated["pack"] == "builtin" and updated["speaker"] >= len(BUILTIN):
                raise ValueError("内置音色编号无效")
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
        catalog = data.get("catalog", [])
        if not isinstance(catalog, list) or len(catalog) > 100:
            raise ValueError("手机音色列表无效")
        clean = []
        for item in catalog:
            if not isinstance(item, dict):
                raise ValueError("手机音色列表格式无效")
            pack = item.get("id")
            if pack != "builtin" and (not isinstance(pack, str) or not re.fullmatch(r"[a-f0-9]{64}", pack)):
                raise ValueError("手机音色编号无效")
            count = item.get("speakers", 0)
            if type(count) is not int or not 1 <= count <= 1024:
                raise ValueError("手机音色数量无效")
            clean.append({"id": pack, "speakers": count})
        with self.lock:
            active = data.get("active_pack", "builtin")
            speaker = data.get("active_speaker", 0)
            revision = data.get("control_revision", -1)
            if active != "builtin" and (not isinstance(active, str) or not re.fullmatch(r"[a-f0-9]{64}", active)):
                raise ValueError("手机当前音色编号无效")
            if type(speaker) is not int or not 0 <= speaker < 1024 or type(revision) is not int:
                raise ValueError("手机音色状态无效")
            if sum(item["speakers"] for item in clean) > 4096:
                raise ValueError("手机音色列表过大")
            self.device = {"status": data.get("status", ""), "catalog": clean, "active_pack": active, "active_speaker": speaker, "control_revision": revision}
            self.last_seen = time.monotonic()

    def desktop(self):
        with self.lock:
            catalog = self.packs.catalog()
            counts = {item["id"]: item["speakers"] for item in self.device.get("catalog", [])}
            choices = [{"key": f"builtin:{index}", "label": "内置 · " + name} for index, name in enumerate(BUILTIN)]
            for pack in catalog:
                for index in range(counts.get(pack["id"], 1)):
                    choices.append({"key": f"{pack['id']}:{index}", "label": f"{pack['name']} · 音色 {index+1}"})
            current = dict(self.value)
            if self.device.get("control_revision", -1) >= current["revision"]:
                current["pack"] = self.device.get("active_pack", current["pack"])
                current["speaker"] = self.device.get("active_speaker", current["speaker"])
            return {"control": current, "phone_connected": time.monotonic() - self.last_seen < 35,
                    "phone_status": self.device.get("status", "尚未连接支持控制同步的手机 App"), "choices": choices, "packs": catalog}
