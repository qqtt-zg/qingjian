"""单并发、有界且按请求 ID 绑定词条的临时工作流；不落盘词条或结果。"""

from collections import deque
from concurrent.futures import ThreadPoolExecutor
import re
import threading
import time
import uuid


class NoteJobs:
    def __init__(self, generate):
        self.generate = generate
        self.lock = threading.RLock()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="chinese-note")
        self.jobs = {}
        self.starts = deque()
        self.closed = False

    @staticmethod
    def valid_id(value):
        if not isinstance(value, str): raise ValueError("请求编号无效")
        try:
            if str(uuid.UUID(value)) != value: raise ValueError
        except ValueError:
            raise ValueError("请求编号无效") from None
        return value

    def start(self, data):
        if not isinstance(data, dict) or set(data) != {"request_id", "english"}:
            raise ValueError("只能提交英文词条和请求编号")
        request_id = self.valid_id(data["request_id"])
        english = data["english"]
        if not isinstance(english, str) or not 1 <= len(english) <= 100 or english != english.strip() or not re.fullmatch(r"[A-Za-z][A-Za-z0-9 '\-.,()/&]*", english):
            raise ValueError("请使用 1～100 字的英文单词或短语")
        with self.lock:
            now = time.monotonic()
            self.jobs = {k: v for k, v in self.jobs.items() if v[1]["state"] == "running" or now - v[0] < 1800}
            if request_id in self.jobs:
                result = self.jobs[request_id][1]
                if result["english"] != english: return 409, {"error": "同一请求编号不能用于不同词条"}
                return (202 if result["state"] == "running" else 200), dict(result)
            if self.closed: return 503, {"error": "电脑连接工具正在关闭"}
            if any(v[1]["state"] == "running" for v in self.jobs.values()):
                return 429, {"error": "电脑正在补全另一条笔记，请稍后再试"}
            while self.starts and now - self.starts[0] >= 60: self.starts.popleft()
            if len(self.starts) >= 10: return 429, {"error": "请求较频繁，请一分钟后重试"}
            while len(self.jobs) >= 128: self.jobs.pop(next(iter(self.jobs)))
            result = {"request_id": request_id, "english": english, "state": "running"}
            self.jobs[request_id] = (now, result)
            self.starts.append(now)
            self.pool.submit(self.run, request_id, english)
            return 202, dict(result)

    def run(self, request_id, english):
        try:
            meaning = self.generate(english)
            update = {"state": "succeeded", "meaning": meaning}
        except ValueError as error:
            update = {"state": "failed", "error": str(error)}
        except Exception:
            update = {"state": "failed", "error": "电脑暂时无法生成笔记，请检查云配置后重试"}
        with self.lock:
            self.jobs[request_id][1].update(update)

    def get(self, request_id):
        self.valid_id(request_id)
        with self.lock:
            item = self.jobs.get(request_id)
            if not item or time.monotonic() - item[0] >= 1800:
                return 404, {"error": "请求记录已过期，请重新补全"}
            return 200, dict(item[1])

    def close(self):
        with self.lock: self.closed = True
        self.pool.shutdown(wait=False, cancel_futures=True)
