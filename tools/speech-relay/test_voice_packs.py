"""数据包路径、大小和来源目录的边界测试，不加载真实模型。"""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from voice_packs import VoicePacks, safe_path


class VoicePackTests(unittest.TestCase):
    def test_rejects_unsafe_relative_paths(self):
        for name in ("../a", "/a", "C:/a", "a\\b", "a/../b", "a//b", "a/./b"):
            with self.assertRaises(ValueError):
                safe_path(name)

    def test_rejects_metadata_escape_and_executables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            (source / "model.onnx").write_bytes(b"model")
            (source / "tokens.txt").write_text("tokens")
            packs = VoicePacks(root / "out")
            (source / "voicepack.json").write_text(json.dumps({"version": 1, "engine": "vits", "name": "test", "model": "../escape", "tokens": "tokens.txt"}))
            with self.assertRaises(ValueError):
                packs.add(source)
            (source / "voicepack.json").unlink()
            (source / "native.so").write_bytes(b"no executables")
            with self.assertRaises(ValueError):
                packs.add(source)

    def test_reimport_is_deterministic_and_size_and_symlinks_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            (source / "model.onnx").write_bytes(b"model")
            (source / "tokens.txt").write_text("tokens")
            packs = VoicePacks(root / "out")
            self.assertEqual(packs.add(source)["id"], packs.add(source)["id"])
            with patch("voice_packs.MAX_EXPANDED", 1):
                with self.assertRaises(ValueError):
                    packs.add(source)
            with patch.object(Path, "is_symlink", return_value=True):
                with self.assertRaises(ValueError):
                    packs.add(source)
