"""验证持久配对、重启后的事件顺序和显式撤销；使用临时目录中的合成凭证。"""

import json
import io
import contextlib
import http.client
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from pairing import PairingStore
from server import SpeechState
import server


class PairingTests(unittest.TestCase):
    def test_credentials_and_event_sequence_survive_restart_without_text_history(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pairing.json"
            first = PairingStore(path)
            state = SpeechState(first.data["receive_token"], [], first)
            previous = state.publish("first synthetic sentence")
            second = PairingStore(path)
            self.assertEqual(first.data, second.data)
            self.assertNotIn("first synthetic sentence", path.read_text())
            restarted = SpeechState(second.data["receive_token"], [], second)
            self.assertEqual(restarted.text, "")
            self.assertEqual(restarted.publish("next sentence"), previous + 1)
            self.assertEqual(restarted.poll(previous)["text"], "next sentence")
            self.assertNotEqual(restarted.token, restarted.publish_token)

    def test_only_explicit_reset_revokes_both_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pairing.json"
            old = PairingStore(path)
            new = PairingStore(path, reset=True)
            for key in ("receive_token", "publish_token"):
                self.assertNotEqual(old.data[key], new.data[key])
            self.assertEqual(new.data["sequence"], 0)

    def test_corrupt_file_is_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pairing.json"
            for data in ([], {}, {"receive_token": "x"}, {"receive_token": "a"*32, "publish_token": "a"*32, "sequence": 0}):
                path.write_text(json.dumps(data))
                original = path.read_bytes()
                with self.assertRaises(ValueError):
                    PairingStore(path)
                self.assertEqual(original, path.read_bytes())

    def test_failed_sequence_write_does_not_accept_or_publish_event(self):
        with tempfile.TemporaryDirectory() as directory:
            store = PairingStore(Path(directory) / "pairing.json")
            state = SpeechState(store.data["receive_token"], [], store)
            with patch.object(store, "save", side_effect=OSError("disk unavailable")):
                with self.assertRaises(OSError):
                    state.publish("synthetic")
            self.assertEqual((state.sequence, state.text), (0, ""))

    def test_main_reuses_complete_pairing_url_and_continues_http_events_after_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            connection = Path(directory) / "connection.toml"
            observed = []
            servers = []
            original_server = server.SpeechServer

            def create(address, state):
                self.assertEqual(address[0], "127.0.0.1")
                instance = original_server((address[0], 0), state)
                servers.append(instance)
                return instance

            def opened(url):
                receive_token = url.split("#", 1)[1]
                settings = connection.read_text()
                self.assertNotIn(receive_token, settings)
                state = servers[-1].state
                client = http.client.HTTPConnection(*servers[-1].server_address, timeout=2)
                client.request("POST", "/api/speech", body=b"hello", headers={"Authorization": "Bearer " + state.publish_token, "Content-Type": "text/plain"})
                response = client.getresponse()
                self.assertEqual(response.status, 202)
                sequence = json.loads(response.read())["sequence"]
                client.close()
                observed.append((url, sequence, state.public_url))

            for _ in range(2):
                with patch("sys.argv", ["server.py", "--tailscale", "--open", "--state-file", str(connection)]), \
                        patch("server.SpeechServer", side_effect=create), \
                        patch("server.TailscaleLink.start", return_value="https://pc.tailtest.ts.net:8443"), \
                        patch("server.webbrowser.open", side_effect=opened), \
                        patch("server.time.sleep", side_effect=KeyboardInterrupt), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(server.main(), 0)
                self.assertFalse(connection.exists())
                self.assertTrue(connection.with_name("pairing.json").exists())
            self.assertEqual(observed[0][0], observed[1][0])
            self.assertEqual(observed[1][1], observed[0][1] + 1)
            self.assertEqual(observed[1][2], "https://pc.tailtest.ts.net:8443")
