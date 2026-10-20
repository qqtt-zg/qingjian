"""云请求协议、密钥边界、限流与固定规则的隔离验证，不使用真实密钥。"""

import io
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

from notes.jobs import NoteJobs
from notes.provider import generate, load_config, NoRedirect


class ProviderTests(unittest.TestCase):
    def config(self):
        return {"url": "https://fixture.invalid/v1/chat/completions", "key": "secret-fixture", "model": "fixture"}

    def test_provider_only_sends_fixed_workflow_and_never_exports_key(self):
        raw = json.dumps({"choices": [{"message": {"content": '{"meaning":"控制；调节"}'}}]}).encode()
        class Response(io.BytesIO):
            pass
        captured = []
        def fake_open(request, timeout):
            captured.append(request)
            self.assertEqual(timeout, 40)
            self.assertEqual(request.get_header("Authorization"), "Bearer secret-fixture")
            body = json.loads(request.data)
            self.assertEqual(body["max_tokens"], 512)
            self.assertEqual(json.loads(body["messages"][1]["content"]), {"english": "control"})
            self.assertNotIn("tools", body)
            self.assertNotIn("secret-fixture", request.data.decode())
            return Response(raw)
        with patch("notes.provider.load_config", return_value=self.config()), patch("urllib.request.OpenerDirector.open", side_effect=fake_open):
            self.assertEqual(generate("control"), "控制；调节")
        self.assertEqual(len(captured), 1)

    def test_redirect_and_raw_error_body_are_never_forwarded(self):
        self.assertIsNone(NoRedirect().redirect_request(None,None,302,"",{},"https://evil.invalid"))
        for code in (302,401,429,500):
            error = urllib.error.HTTPError("https://fixture.invalid",code,"secret-fixture",{},io.BytesIO(b"secret-fixture"))
            with patch("notes.provider.load_config",return_value=self.config()), patch("urllib.request.OpenerDirector.open",side_effect=error):
                with self.assertRaises(ValueError) as caught: generate("control")
                self.assertNotIn("secret-fixture",str(caught.exception))

    def test_bad_or_oversized_provider_output_fails_closed(self):
        for raw in (b"x"*65537,b"{}",b"not json",b'{"choices":[]}'):
            with patch("notes.provider.load_config",return_value=self.config()), patch("urllib.request.OpenerDirector.open",return_value=io.BytesIO(raw)):
                with self.assertRaises(ValueError): generate("control")

    def test_key_precedence_and_local_http(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"config.toml"
            content='[predict]\nenabled=true\nbase_url="http://127.0.0.1:8317/v1"\nmodel="fixture"\napi_key_env="QJ_NOTES_FIXTURE"\n'
            path.write_text(content,encoding="utf-8")
            path.with_name(".env").write_text('QJ_NOTES_FIXTURE="file-key"\n',encoding="utf-8")
            with patch.dict(os.environ,{"QJ_NOTES_FIXTURE":"environment-key"}):
                self.assertEqual(load_config(path)["key"],"environment-key")
                path.write_text(content+'api_key="explicit-key"\n',encoding="utf-8")
                self.assertEqual(load_config(path)["key"],"explicit-key")
            for url in ("https://user:pass@host.invalid/v1","https://host.invalid/v1?key=secret","file:///tmp/config","http://192.168.1.1/v1"):
                path.write_text(content.replace("http://127.0.0.1:8317/v1",url),encoding="utf-8")
                with self.assertRaises(ValueError): load_config(path)

    def test_failed_jobs_and_rate_limit_do_not_auto_retry(self):
        calls=[]
        def fail(word):
            calls.append(word)
            raise ValueError("固定错误信息")
        jobs=NoteJobs(fail)
        try:
            for index in range(10):
                request_id="00000000-0000-4000-8000-%012d" % index
                jobs.start({"request_id":request_id,"english":"control"})
                for _ in range(100):
                    result=jobs.get(request_id)[1]
                    if result["state"]=="failed": break
                    time.sleep(.001)
                self.assertEqual(result["error"],"固定错误信息")
                self.assertEqual(jobs.start({"request_id":request_id,"english":"control"})[0],200)
            self.assertEqual(len(calls),10)
            self.assertEqual(jobs.start({"request_id":"00000000-0000-4000-8000-999999999999","english":"help"})[0],429)
        finally: jobs.close()


if __name__ == "__main__":
    unittest.main()
