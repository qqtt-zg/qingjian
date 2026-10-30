"""公网隧道进程的地址校验与关闭测试；不连接第三方服务。"""

import io
import subprocess
import unittest
from unittest.mock import Mock, patch

from tunnel import QuickTunnel, NamedTunnel


class QuickTunnelTests(unittest.TestCase):
    def process(self, output):
        process = Mock()
        process.stdout = io.StringIO(output)
        process.poll.return_value = None
        return process

    def test_only_official_https_quick_tunnel_address_is_accepted(self):
        tunnel = QuickTunnel("cloudflared", 21835)
        process = self.process("http://unsafe.trycloudflare.com\nhttps://other.example\n"
                               "https://sample-speech.trycloudflare.com |\n")
        with patch("tunnel.shutil.which", return_value="cloudflared"), \
                patch("tunnel.subprocess.Popen", return_value=process) as spawn:
            self.assertEqual(tunnel.start(), "https://sample-speech.trycloudflare.com")
            self.assertIn("http://127.0.0.1:21835", spawn.call_args.args[0])
            tunnel.close()
        process.terminate.assert_called_once()

    def test_missing_or_failed_tool_does_not_return_pairing_address(self):
        with patch("tunnel.shutil.which", return_value=None):
            with self.assertRaises(RuntimeError):
                QuickTunnel("missing", 21835).start()
        process = self.process("unable to connect\n")
        process.poll.return_value = 1
        with patch("tunnel.shutil.which", return_value="cloudflared"), \
                patch("tunnel.subprocess.Popen", return_value=process):
            tunnel = QuickTunnel("cloudflared", 21835)
            with self.assertRaises(RuntimeError):
                tunnel.start()
            tunnel.close()

    def test_shutdown_kills_only_owned_process_if_termination_times_out(self):
        tunnel = QuickTunnel("cloudflared", 21835)
        tunnel.process = self.process("")
        tunnel.process.wait.side_effect = [subprocess.TimeoutExpired("cloudflared", 5), 0]
        tunnel.close()
        tunnel.process.terminate.assert_called_once()
        tunnel.process.kill.assert_called_once()


class NamedTunnelTests(unittest.TestCase):
    def process(self, output):
        process = Mock()
        process.stdout = io.StringIO(output)
        process.poll.return_value = None
        return process

    def test_named_tunnel_accepts_registered_connection_log_and_sets_token_env(self):
        tunnel = NamedTunnel("cloudflared", "speech.qingjian.app", "eyJhIjoiZXhhbXBsZSJ9")
        process = self.process("INF Starting tunnel\nINF Registered tunnel connection connIndex=0 protocol=http2\n")
        with patch("tunnel.shutil.which", return_value="cloudflared"), \
                patch("tunnel.subprocess.Popen", return_value=process) as spawn:
            url = tunnel.start()
            self.assertEqual(url, "https://speech.qingjian.app")
            self.assertEqual(spawn.call_args.kwargs["env"].get("TUNNEL_TOKEN"), "eyJhIjoiZXhhbXBsZSJ9")
            tunnel.close()
        process.terminate.assert_called_once()

    def test_named_tunnel_identifies_invalid_token(self):
        tunnel = NamedTunnel("cloudflared", "speech.qingjian.app", "bad-token")
        process = self.process("Provided Tunnel token is not valid.\n")
        process.poll.return_value = 1
        with patch("tunnel.shutil.which", return_value="cloudflared"), \
                patch("tunnel.subprocess.Popen", return_value=process):
            with self.assertRaises(RuntimeError) as context:
                tunnel.start()
            self.assertIn("令牌格式无效", str(context.exception))
            tunnel.close()

    def test_named_tunnel_identifies_deleted_tunnel(self):
        tunnel = NamedTunnel("cloudflared", "speech.qingjian.app", "eyJhIjoiZXhhbXBsZSJ9")
        process = self.process("INF Starting tunnel\nERR Register tunnel error: Failed to get tunnel\n")
        process.poll.return_value = 1
        with patch("tunnel.shutil.which", return_value="cloudflared"), \
                patch("tunnel.subprocess.Popen", return_value=process):
            with self.assertRaises(RuntimeError) as context:
                tunnel.start()
            self.assertIn("未找到该隧道", str(context.exception))
            tunnel.close()


if __name__ == "__main__":
    unittest.main()
