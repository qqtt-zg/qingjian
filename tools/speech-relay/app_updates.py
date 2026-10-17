"""个人 Android 更新包存储；先落盘不可变 APK，再原子切换版本清单。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile


class AppUpdates:
    def __init__(self, root):
        self.root = Path(root)

    def latest(self):
        try:
            data = json.loads((self.root / "latest.json").read_text(encoding="utf-8"))
        except FileNotFoundError:
            return None
        if (data.get("package") != "org.qingjian.speech"
                or type(data.get("version_code")) is not int or data["version_code"] <= 0
                or not isinstance(data.get("version_name"), str) or not 1 <= len(data["version_name"]) <= 100
                or type(data.get("size")) is not int or not 0 < data["size"] <= 1024 ** 3
                or not re.fullmatch(r"[a-f0-9]{64}", str(data.get("sha256", "")))):
            raise ValueError("更新清单无效")
        if (self.root / (data["sha256"] + ".apk")).stat().st_size != data["size"]:
            raise ValueError("更新安装包不完整")
        return data

    def publish(self, source, version_code, version_name):
        source = Path(source)
        if source.suffix.lower() != ".apk" or not 0 < source.stat().st_size <= 1024 ** 3:
            raise ValueError("请选择有效的 APK")
        with zipfile.ZipFile(source) as archive:
            if "AndroidManifest.xml" not in archive.namelist() or not any(
                    name.startswith("lib/arm64-v8a/") for name in archive.namelist()):
                raise ValueError("更新包必须包含 ARM64 应用")
        if version_code <= 0 or not 1 <= len(version_name) <= 100:
            raise ValueError("更新版本无效")
        self.root.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=self.root, suffix=".tmp", delete=False) as output:
                temporary = Path(output.name)
                with source.open("rb") as stream:
                    while chunk := stream.read(1024 * 1024):
                        digest.update(chunk)
                        output.write(chunk)
            info = {"package": "org.qingjian.speech", "version_code": version_code,
                    "version_name": version_name, "size": temporary.stat().st_size, "sha256": digest.hexdigest()}
            temporary.replace(self.root / (info["sha256"] + ".apk"))
            with tempfile.NamedTemporaryFile(dir=self.root, suffix=".json", mode="w", encoding="utf-8", delete=False) as output:
                temporary = Path(output.name)
                json.dump(info, output, ensure_ascii=False)
            temporary.replace(self.root / "latest.json")
            return info
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="发布到本机手机连接服务，下次检查更新即可获取")
    parser.add_argument("apk")
    parser.add_argument("version_code", type=int)
    parser.add_argument("version_name")
    parser.add_argument("--root", type=Path, default=Path(os.environ.get("APPDATA", str(Path.home()))) / "Qingjian/speech/updates")
    args = parser.parse_args()
    print(json.dumps(AppUpdates(args.root).publish(args.apk, args.version_code, args.version_name), ensure_ascii=False))
