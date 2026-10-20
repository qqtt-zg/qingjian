"""获取锁定哈希的官方依赖；模型只解压到本 Android 工程的 assets。"""
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

root = Path(__file__).resolve().parent
cache = root.parent.parent / ".local/downloads/android-speech"
cache.mkdir(parents=True, exist_ok=True)
for item in json.loads((root / "dependencies.json").read_text()):
    target = cache / item["name"]
    if not target.exists():
        urllib.request.urlretrieve(item["url"], target)
    with target.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != item["sha256"]:
        raise RuntimeError(f"依赖校验失败，请移除后重试：{target.name}")
    if target.suffix == ".aar":
        dest = root / "app/libs" / target.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(target.read_bytes())
    else:
        assets = root / "app/src/main/assets"
        assets.mkdir(parents=True, exist_ok=True)
        with tarfile.open(target) as archive:
            members = archive.getmembers()
            if item["name"] == "kokoro-multi-lang-v1_0.tar.bz2":
                # 中英文共用同一模型；补齐词典与分词数据，辅助脚本不随包。
                required = {"model.onnx", "tokens.txt", "voices.bin", "lexicon-us-en.txt", "lexicon-zh.txt", "date-zh.fst", "number-zh.fst", "phone-zh.fst", "LICENSE"}
                members = [member for member in members if member.name.split("/", 1)[-1] in required or member.name.startswith("kokoro-multi-lang-v1_0/espeak-ng-data/") or member.name.startswith("kokoro-multi-lang-v1_0/dict/") and not member.name.endswith(".py")]
            archive.extractall(assets, members=members, filter="data")
print("官方依赖校验与准备完成")
