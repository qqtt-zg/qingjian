"""隔离目录验证生词来源、备份边界与鉴权，不访问真实用户资料。"""
import datetime
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error

from learning_sync import LearningSync
from server import SpeechServer, SpeechState


class LearningSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sync = LearningSync(self.root / "backups", self.root / "words.tsv")

    def tearDown(self):
        self.temp.cleanup()

    def test_vocabulary_old_and_new_columns(self):
        # 首见／末见用真实格式的 ISO 日期。桌面的词库就是这个样子（见 crates/qingjian-learning/src/vocabulary_book/file.rs）。
        self.sync.vocabulary.write_text(
            "# 语言\t译词\t看到轮次\t上屏次数\t用过次数\t首见\t末见\t认识\t中文\t例句\n"
            "en\tcontrol\t4\t2\t1\t2026-09-01\t2026-10-18\t1\t控制\tcontrol the volume\n"
            "en\tready\t2\t0\t0\t2026-09-05\t2026-10-20\n"
            "zh\t坏行\t4\t4\t4\t4\t4\n"
            "en\tbad\tx\t0\t0\t0\t0\n",
            encoding="utf-8")
        result = self.sync.words()
        rows = result["words"]
        self.assertEqual([r["english"] for r in rows], ["control", "ready"])
        self.assertEqual(rows[0]["meaning"], "控制")
        self.assertTrue(rows[0]["desktop_known"])
        self.assertEqual(rows[1]["meaning"], "")
        self.assertEqual(result["invalid_date_rows"], 0)

    def test_last_is_utc_midnight_seconds(self):
        self.sync.vocabulary.write_text(
            "en\tdeploy\t42\t11\t3\t2026-08-01\t2026-10-18\t0\t部署到测试环境\t把这个版本部署到测试环境\n",
            encoding="utf-8")
        row = self.sync.words()["words"][0]
        expected = int(datetime.datetime(2026, 10, 18, tzinfo=datetime.timezone.utc).timestamp())
        self.assertEqual(row["last"], expected)

    def test_broken_date_skips_the_row_like_the_desktop_does(self):
        # 桌面端对日期列用 ? 短路：日期一坏整行丢弃，下次保存重写文件时该词条就没了。
        # 中继端必须一样，不能宽容保留，否则手机会看到一个桌面端认为不存在的词。
        self.sync.vocabulary.write_text(
            "en\tgood\t1\t1\t0\t2026-09-01\t2026-10-18\t0\t好\t好的\n"
            "en\tbroken\t3\t1\t0\t2026-09-01\t不是日期\t0\t坏\t坏的\n"
            "en\tmissing\t1\t0\t0\t2026-09-01\t\t0\t缺\t缺的\n",
            encoding="utf-8")
        result = self.sync.words()
        self.assertEqual([r["english"] for r in result["words"]], ["good"])
        self.assertEqual(result["invalid_date_rows"], 2)

    def test_missing_vocabulary_is_explicit(self):
        self.assertEqual(self.sync.words(), {"words": [], "available": False, "invalid_date_rows": 0})

    def test_readable_latest_and_previous_backup(self):
        first = {"schema": 1, "created": 1, "tables": {"learning_events": [{"word": "control"}]}}
        self.sync.backup(first)
        second = {**first, "created": 2}
        self.sync.backup(second)
        self.assertEqual(json.loads((self.sync.root / "phone-learning-latest.json").read_text(encoding="utf-8")), second)
        self.assertEqual(json.loads((self.sync.root / "phone-learning-previous.json").read_text(encoding="utf-8")), first)
        self.assertEqual(len(list(self.sync.root.glob("*.json"))), 2)

    def test_backup_rejects_settings_and_bad_rows(self):
        for tables in ({"credentials": []}, {"words": "oops"}, {"words": ["oops"]}):
            with self.assertRaises(ValueError):
                self.sync.backup({"schema": 1, "created": 0, "tables": tables})
        self.assertFalse(self.sync.root.exists())

    def test_pair_authentication_and_body_limits(self):
        state = SpeechState("receive-token", [])
        state.learning = self.sync
        server = SpeechServer(("127.0.0.1", 0), state)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        base = "http://127.0.0.1:" + str(server.server_port)
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(base + "/api/learning/words")
            self.assertEqual(error.exception.code, 401)
            request = urllib.request.Request(base + "/api/learning/words", headers={"Authorization": "Bearer receive-token"})
            self.assertFalse(json.load(opener.open(request))["available"])
            request = urllib.request.Request(base + "/api/learning/backup", b'{}', {"Authorization": "Bearer receive-token", "Content-Type": "text/plain"})
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(request)
            self.assertEqual(error.exception.code, 400)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
