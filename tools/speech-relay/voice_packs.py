"""把本机模型文件夹转换为有大小边界和 SHA-256 的数据包，不打包可执行代码。"""

import hashlib
import json
from pathlib import Path
import re
import zipfile
import tempfile
import os

MAX_PACK = 512 * 1024 * 1024
MAX_EXPANDED = 768 * 1024 * 1024
MAX_FILES = 8000


def safe_path(value):
    if not isinstance(value, str) or not value or len(value) > 240 or "\\" in value or ":" in value:
        raise ValueError("音色文件路径无效")
    if value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/")):
        raise ValueError("音色文件路径越界")
    return value


def validate_manifest(data, names):
    if not isinstance(data, dict) or data.get("version") != 1 or data.get("engine") not in ("kitten", "vits", "kokoro"):
        raise ValueError("不支持此音色包格式或引擎")
    if not isinstance(data.get("name"), str) or not 1 <= len(data["name"]) <= 120:
        raise ValueError("音色包名称无效")
    for key in ("model", "tokens"):
        if safe_path(data.get(key)) not in names:
            raise ValueError("音色包缺少必需文件")
    for key in ("voices", "lexicon"):
        if data.get(key) and safe_path(data[key]) not in names:
            raise ValueError("音色包缺少配套文件")
    if data["engine"] in ("kitten", "kokoro") and not data.get("voices"):
        raise ValueError("此模型需要配套 voices.bin")
    if data.get("data_dir"):
        prefix = safe_path(data["data_dir"]) + "/"
        if not any(name.startswith(prefix) for name in names):
            raise ValueError("音色包缺少发音数据目录")
    return data


class VoicePacks:
    def __init__(self, root):
        self.root = Path(root)

    def info(self, pack_id):
        if not isinstance(pack_id, str) or not re.fullmatch(r"[a-f0-9]{64}", pack_id):
            raise ValueError("音色编号无效")
        path = self.root / (pack_id + ".zip")
        if not path.is_file() or path.stat().st_size > MAX_PACK:
            raise ValueError("找不到音色包")
        metadata = json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))
        return {**metadata, "id": pack_id, "size": path.stat().st_size, "sha256": pack_id}

    def catalog(self):
        if not self.root.is_dir():
            return []
        result = []
        for path in sorted(self.root.glob("*.zip")):
            try:
                result.append(self.info(path.stem))
            except (OSError, ValueError):
                continue
        return result

    def add(self, folder):
        source = Path(folder).resolve(strict=True)
        if not source.is_dir():
            raise ValueError("请选择解压后的模型文件夹")
        files = []
        total = 0
        for path in source.rglob("*"):
            if path.is_symlink() or getattr(path, "is_junction", lambda: False)() or not path.resolve().is_relative_to(source):
                raise ValueError("音色文件夹不能包含符号链接")
            if not path.is_file():
                continue
            name = safe_path(path.relative_to(source).as_posix())
            if path.suffix.lower() in (".exe", ".dll", ".so", ".apk", ".dex", ".jar", ".py", ".js"):
                raise ValueError("音色包只能包含模型与发音数据")
            total += path.stat().st_size
            files.append((name, path))
            if total > MAX_EXPANDED or len(files) > MAX_FILES:
                raise ValueError("音色文件夹超过大小或文件数量限制")
        names = {name for name, _ in files}
        descriptor = source / "voicepack.json"
        if descriptor.is_file():
            if descriptor.stat().st_size > 32768:
                raise ValueError("音色说明文件过大")
            manifest = json.loads(descriptor.read_text(encoding="utf-8"))
        else:
            models = [name for name in names if "/" not in name and name.endswith(".onnx")]
            if len(models) != 1:
                raise ValueError("模型文件夹顶层应有且仅有一个 ONNX 模型")
            title = source.name.lower()
            engine = "kitten" if "kitten" in title else "kokoro" if "kokoro" in title else "vits"
            if "voices.bin" in names and engine == "vits":
                raise ValueError("无法确定音色类型，文件夹名应含 kitten 或 kokoro")
            manifest = {"version": 1, "engine": engine, "name": source.name[:120], "model": models[0], "tokens": "tokens.txt",
                        "voices": "voices.bin" if "voices.bin" in names else "", "lexicon": "lexicon.txt" if "lexicon.txt" in names else "",
                        "data_dir": "espeak-ng-data" if any(n.startswith("espeak-ng-data/") for n in names) else ""}
        validate_manifest(manifest, names)
        self.root.mkdir(parents=True, exist_ok=True)
        descriptor, filename = tempfile.mkstemp(prefix="import-", suffix=".tmp", dir=self.root)
        os.close(descriptor)
        temporary = Path(filename)
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
                archive.writestr(zipfile.ZipInfo("voicepack.json"), json.dumps(manifest, ensure_ascii=False))
                for name, path in sorted(files):
                    if name != "voicepack.json":
                        archive.write(path, name)
            if temporary.stat().st_size > MAX_PACK:
                raise ValueError("压缩音色包超过 512 MB")
            with temporary.open("rb") as stream:
                digest_state = hashlib.sha256()
                while chunk := stream.read(1024 * 1024):
                    digest_state.update(chunk)
                digest = digest_state.hexdigest()
            destination = self.root / (digest + ".zip")
            if not destination.exists():
                temporary.replace(destination)
            destination.with_suffix(".json").write_text(json.dumps({"name": manifest["name"], "engine": manifest["engine"], "expanded": total}), encoding="utf-8")
            return self.info(digest)
        finally:
            temporary.unlink(missing_ok=True)
