"""持久配对凭证及事件序号；不保存朗读正文，重启后手机可继续使用原地址。"""

import json
import os
from pathlib import Path
import re
import secrets


class PairingStore:
    def __init__(self, path, reset=False):
        self.path = Path(path)
        if self.path.exists() and not reset:
            if self.path.stat().st_size > 4096:
                raise ValueError("配对文件过大，请检查或主动重置配对。")
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(self.data, dict):
                raise ValueError("配对文件结构无效，请主动重置配对。")
            tokens = [self.data.get(key) for key in ("receive_token", "publish_token")]
            if any(not isinstance(token, str) or not re.fullmatch(r"[a-f0-9]{32}", token) for token in tokens) or tokens[0] == tokens[1]:
                raise ValueError("配对文件无效，请主动重置配对。")
            sequence = self.data.get("sequence")
            if type(sequence) is not int or not 0 <= sequence < 9007199254740991:
                raise ValueError("配对事件序号无效，请主动重置配对。")
        else:
            self.data = {"receive_token": secrets.token_hex(16), "publish_token": secrets.token_hex(16), "sequence": 0}
            self.save(0)

    def save(self, sequence):
        if not 0 <= sequence < 9007199254740991:
            raise ValueError("事件序号超出范围，请主动重置配对。")
        updated = {**self.data, "sequence": sequence}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(updated, stream)
        temporary.replace(self.path)
        self.data = updated
