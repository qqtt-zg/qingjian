"""桌面启动器的仓库工具路径与安装布局回归，不启动真实连接。"""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import desktop


class DesktopToolTests(unittest.TestCase):
    def assert_start_uses(self, entry, tool, path_tool=None):
        process = Mock()
        process.poll.return_value = None
        state = entry.parents[2] / "test-state"
        with patch.object(desktop, "__file__", str(entry)), \
                patch.object(desktop, "ROOT", state), \
                patch.object(desktop, "snapshot", return_value={"running": False}), \
                patch.object(desktop.shutil, "which", return_value=path_tool), \
                patch.object(desktop.subprocess, "Popen", return_value=process) as spawn, \
                patch.object(desktop, "request", return_value={"pair_url": "https://test.invalid/"}):
            desktop.start()
        self.assertEqual(spawn.call_args.args[0][-1], str(tool))

    def test_repository_tool_is_outside_cargo_target_and_preferred_over_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tool = root / ".local/tools/cloudflared.exe"
            tool.parent.mkdir(parents=True)
            tool.touch()
            self.assert_start_uses(root / "tools/speech-relay/desktop.py", tool, "path-tool")

    def test_installed_tool_is_still_loaded_from_application_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tool = root / "tools/cloudflared.exe"
            tool.parent.mkdir()
            tool.touch()
            self.assert_start_uses(root / "tools/speech-relay/desktop.py", tool)

    def test_path_tool_remains_available_without_a_bundled_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assert_start_uses(root / "tools/speech-relay/desktop.py", "path-tool", "path-tool")

    def test_notes_reuses_current_relay_without_changing_pairing(self):
        with patch.object(desktop,"start") as start, patch.object(desktop,"request",return_value={"control":{"allow_notes":False}}) as request:
            desktop.start_notes()
            start.assert_called_once()
            request.assert_any_call("/api/admin/control",{"allow_notes":True})

    def test_named_tunnel_config_is_preferred_when_present(self):
        process = Mock()
        process.poll.return_value = None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state"
            state.mkdir(parents=True, exist_ok=True)
            (state / "named-tunnel.toml").write_text('host = "speech.test"\ntoken = "eyJhIjoiMSJ9"\n', encoding="utf-8")
            entry = root / "tools/speech-relay/desktop.py"
            with patch.object(desktop, "__file__", str(entry)), \
                    patch.object(desktop, "ROOT", state), \
                    patch.object(desktop, "snapshot", return_value={"running": False}), \
                    patch.object(desktop.shutil, "which", return_value="cloudflared"), \
                    patch.object(desktop.subprocess, "Popen", return_value=process) as spawn, \
                    patch.object(desktop, "request", return_value={"pair_url": "https://speech.test/#" + "a"*32}):
                desktop.start()
            args = spawn.call_args.args[0]
            self.assertIn("--named", args)
            self.assertIn("--named-host", args)
            self.assertIn("speech.test", args)
            self.assertIn("eyJhIjoiMSJ9", args)
            self.assertEqual(args[-1], "cloudflared")

    def test_notes_restarts_old_helper_before_authorization(self):
        with patch.object(desktop,"start") as start, patch.object(desktop,"request",return_value={"control":{}}) as request, \
                patch.object(desktop,"snapshot",return_value={"running":False}), patch.object(desktop.time,"sleep"):
            desktop.start_notes()
            self.assertEqual(start.call_count,2)
            self.assertEqual([call.args[0] for call in request.call_args_list],
                             ["/api/admin","/api/admin/stop","/api/admin/control"])


if __name__ == "__main__":
    unittest.main()
