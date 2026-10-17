"""已鉴权更新文件的单区间续传；文件按哈希固定，不开放任意路径。"""

import re


def send_package(handler, updates, digest):
    if not re.fullmatch(r"[a-f0-9]{64}", digest):
        handler.reply(404, {"error": "安装包不存在"})
        return
    path = updates.root / (digest + ".apk")
    try:
        stream = path.open("rb")
    except OSError:
        handler.reply(404, {"error": "安装包已不可用，请重新检查更新"})
        return
    with stream:
        size = path.stat().st_size
        if not 0 < size <= 1024 ** 3:
            handler.reply(503, {"error": "安装包大小无效"})
            return
        etag = '"' + digest + '"'
        requested = handler.headers.get("Range")
        if handler.headers.get("If-Range") not in (None, etag):
            requested = None
        start, end = 0, size - 1
        if requested:
            try:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", requested)
                if not match or not any(match.groups()):
                    raise ValueError()
                if match[1]:
                    start = int(match[1]); end = min(int(match[2]), size - 1) if match[2] else size - 1
                else:
                    length = int(match[2])
                    if length <= 0:
                        raise ValueError()
                    start = max(0, size - length)
                if not 0 <= start <= end < size:
                    raise ValueError()
            except ValueError:
                handler.send_response(416)
                handler.send_header("Content-Range", f"bytes */{size}")
                handler.send_header("Content-Length", "0")
                handler.end_headers()
                return
        handler.connection.settimeout(60)
        handler.send_response(206 if requested else 200)
        handler.send_header("Content-Type", "application/vnd.android.package-archive")
        handler.send_header("Content-Disposition", f'attachment; filename="qingjian-{digest[:12]}.apk"')
        handler.send_header("Content-Length", str(end - start + 1))
        handler.send_header("Accept-Ranges", "bytes")
        handler.send_header("ETag", etag)
        handler.send_header("Cache-Control", "private, no-store")
        handler.send_header("X-Content-Type-Options", "nosniff")
        if requested:
            handler.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        handler.end_headers()
        if handler.command == "HEAD":
            return
        try:
            stream.seek(start)
            remaining = end - start + 1
            while remaining:
                block = stream.read(min(65536, remaining))
                if not block:
                    break
                handler.wfile.write(block)
                remaining -= len(block)
        except (OSError, ConnectionError):
            handler.close_connection = True
