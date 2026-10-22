"""隔离目录验证生词来源、备份边界与鉴权，不访问真实用户资料。"""
import datetime
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from unittest.mock import patch

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

    def _write_vocabulary(self, rows):
        self.sync.vocabulary.write_text(
            "# 语言\t译词\t看到轮次\t上屏次数\t用过次数\t首见\t末见\t认识\t中文\t例句\n" + rows,
            encoding="utf-8")

    def test_context_shape_window_and_order(self):
        today = datetime.date.today()
        recent = (today - datetime.timedelta(days=3)).isoformat()
        old = (today - datetime.timedelta(days=60)).isoformat()
        self._write_vocabulary(
            f"en\tdeploy\t42\t11\t3\t{old}\t{recent}\t0\t部署到测试环境\t把这个版本部署到测试环境\n"
            f"en\toldword\t5\t1\t0\t{old}\t{old}\t0\t老词\t老句子\n"
            f"en\tcandidate\t12\t4\t1\t{old}\t{recent}\t1\t候选词\t这个词是候选词\n")
        context = self.sync.context()
        self.assertEqual(context["schema"], 1)
        self.assertEqual(context["days"], 30)
        self.assertEqual(context["apps_status"], "not_collected")
        self.assertFalse(context["truncated"])
        self.assertTrue(context["available"])
        self.assertEqual(context["total"], 2)
        # 30 天没碰的词不在窗口里：这不是「词被删除」，只是窗口外
        self.assertEqual([w["english"] for w in context["words"]], ["deploy", "candidate"])
        word = context["words"][0]
        self.assertEqual(word["language"], "en")
        self.assertEqual(word["seen"], 42)
        self.assertEqual(word["committed"], 11)
        self.assertEqual(word["used"], 3)
        self.assertEqual(word["first"], old)
        self.assertEqual(word["last"], recent)
        self.assertFalse(word["desktop_known"])
        self.assertEqual(word["origin_text"], "部署到测试环境")
        self.assertEqual(word["example"], "把这个版本部署到测试环境")
        self.assertIsNone(word["apps"])
        self.assertTrue(context["generated_at"].endswith("+08:00") or "+" in context["generated_at"][-6:])

    def test_context_orders_by_committed_then_language_and_word(self):
        today = datetime.date.today()
        recent = (today - datetime.timedelta(days=1)).isoformat()
        self._write_vocabulary(
            f"en\tbeta\t1\t1\t0\t{recent}\t{recent}\t0\t乙\t乙句\n"
            f"en\talpha\t1\t1\t0\t{recent}\t{recent}\t0\t甲\t甲句\n"
            f"en\tgamma\t1\t9\t0\t{recent}\t{recent}\t0\t丙\t丙句\n")
        words = self.sync.context()["words"]
        self.assertEqual([w["english"] for w in words], ["gamma", "alpha", "beta"])

    def test_context_truncates_to_the_byte_limit(self):
        today = datetime.date.today()
        recent = (today - datetime.timedelta(days=1)).isoformat()
        self._write_vocabulary(
            "".join(f"en\tword{index}\t1\t{index}\t0\t{recent}\t{recent}\t0\t中文{index}\t句子{index}\n"
                    for index in range(50)))
        with patch("learning_sync.CONTEXT_MAX_BYTES", 1200):
            context = self.sync.context()
        self.assertTrue(context["truncated"])
        self.assertEqual(context["total"], 50)
        self.assertLess(len(context["words"]), 50)
        # 截断按上屏次数降序，留下的总是更相关的
        committed = [w["committed"] for w in context["words"]]
        self.assertEqual(committed, sorted(committed, reverse=True))
        body = json.dumps(context, ensure_ascii=False).encode("utf-8")
        self.assertLessEqual(len(body), 1200)

    def test_context_sends_the_personal_glossary_verbatim(self):
        today = datetime.date.today()
        recent = (today - datetime.timedelta(days=1)).isoformat()
        self._write_vocabulary(f"en\tdeploy\t1\t1\t0\t{recent}\t{recent}\t0\t部署\t部署句子\n")
        (self.root / "user-glossary-en.tsv").write_text(
            "# 词\t[词性. ]译词\n扭矩\tn. torque\n棘手\tadj. thorny\n", encoding="utf-8")
        context = self.sync.context()
        self.assertEqual(context["glosses"], [
            {"language": "en", "word": "扭矩", "pos": "n.", "text": "torque", "reading": None},
            {"language": "en", "word": "棘手", "pos": "adj.", "text": "thorny", "reading": None},
        ])

    def test_context_without_a_vocabulary_is_explicit(self):
        context = self.sync.context()
        self.assertFalse(context["available"])
        self.assertEqual(context["words"], [])
        self.assertEqual(context["glosses"], [])
        self.assertFalse(context["truncated"])
        # 字段形状不随「有没有词库」变：手机不该为了拿这个计数先判断 available。
        self.assertEqual(context["invalid_date_rows"], 0)
        self.assertFalse(context["glosses_truncated"])

    def test_context_counts_broken_dates_even_when_nothing_is_available(self):
        context = self.sync.context()
        self.assertEqual(context["invalid_date_rows"], 0)
        self.assertIn("invalid_date_rows", context)

    def test_context_reports_glossary_truncation(self):
        """个人释义表超过条数上限时要明说，不能静默少几条。"""
        today = datetime.date.today()
        recent = (today - datetime.timedelta(days=1)).isoformat()
        self._write_vocabulary(f"en\tdeploy\t1\t1\t0\t{recent}\t{recent}\t0\t部署\t部署句子\n")
        lines = ["# 词\t[词性. ]译词"] + [f"词{index}\tn. word{index}" for index in range(30)]
        (self.root / "user-glossary-en.tsv").write_text("\n".join(lines), encoding="utf-8")
        with patch("learning_sync.CONTEXT_MAX_GLOSSES", 10):
            context = self.sync.context()
        self.assertEqual(len(context["glosses"]), 10)
        self.assertTrue(context["glosses_truncated"])

    def test_context_keeps_the_response_within_the_byte_limit_even_for_glosses(self):
        """释义表自己撑爆上限时也要裁：2 MiB 是硬约定，不能因为释义优先就破例。"""
        today = datetime.date.today()
        recent = (today - datetime.timedelta(days=1)).isoformat()
        self._write_vocabulary(f"en\tdeploy\t1\t1\t0\t{recent}\t{recent}\t0\t部署\t部署句子\n")
        lines = ["# 词\t[词性. ]译词"]
        lines += [("词" * 200) + f"{index}\tn. " + ("x" * 400) for index in range(50)]
        (self.root / "user-glossary-en.tsv").write_text("\n".join(lines), encoding="utf-8")
        # 额度从「刚好放得下空信封」往上取几档，每一档都不能超
        for limit in (250, 400, 1000, 3000, 20000):
            with patch("learning_sync.CONTEXT_MAX_BYTES", limit):
                context = self.sync.context()
            body = json.dumps(context, ensure_ascii=False).encode("utf-8")
            self.assertLessEqual(len(body), limit, f"上限 {limit} 时响应 {len(body)} 字节")
            self.assertTrue(context["glosses_truncated"])

    def test_context_route_requires_pairing(self):
        state = SpeechState("receive-token", [])
        state.learning = self.sync
        server = SpeechServer(("127.0.0.1", 0), state)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        base = "http://127.0.0.1:" + str(server.server_port)
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(base + "/api/learning/context")
            self.assertEqual(error.exception.code, 401)
            request = urllib.request.Request(base + "/api/learning/context",
                                            headers={"Authorization": "Bearer receive-token"})
            body = json.load(opener.open(request))
            self.assertEqual(body["schema"], 1)
            self.assertIn("words", body)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

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
