#!/usr/bin/env python3
"""MiniMax Anthropic 端点本地确定性代理。

背景:claude CLI 对 --settings 里 ANTHROPIC_MODEL 的目录校验依赖
GET {BASE_URL}/v1/models;高负载下该请求偶发失败 → CLI 以
unrecognized_model 致命退出(实录 CR 步骤三连失败根因)。
本代理把 /v1/models 静态化(本地即时返回,永不失败),其余请求
按原协议流式透传到 MiniMax。

用法: python3 scripts/minimax_proxy.py  (监听 127.0.0.1:15723)
"""
import json
import os
import ssl
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://api.minimaxi.com/anthropic"
KEY = os.environ.get("MINIMAX_KEY", "")
PORT = int(os.environ.get("MINIMAX_PROXY_PORT", "15723"))

# 静态模型目录(与 MiniMax /v1/models 实测响应同构,2026-11-05 抓取)
CATALOG = {"data": [
    {"id": "MiniMax-M3.1-Flash", "type": "model", "display_name": "MiniMax-M3.1-Flash", "created_at": "2026-09-16T10:55:18Z"},
    {"id": "MiniMax-M3", "type": "model", "display_name": "MiniMax-M3", "created_at": "2026-06-01T00:00:00Z"},
    {"id": "MiniMax-M2.7", "type": "model", "display_name": "MiniMax-M2.7", "created_at": "2026-03-18T02:00:00Z"},
    {"id": "MiniMax-M2.7-highspeed", "type": "model", "display_name": "MiniMax-M2.7-Highspeed", "created_at": "2026-03-18T02:00:00Z"},
    {"id": "MiniMax-M2.5", "type": "model", "display_name": "MiniMax-M2.5", "created_at": "2025-12-01T00:00:00Z"},
], "first_id": "MiniMax-M3.1-Flash", "has_more": False}

HOP = {"connection", "keep-alive", "transfer-encoding", "content-length",
       "host", "accept-encoding"}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _upstream_req(self, method):
        n = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(n) if n else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP}
        if KEY:
            headers["Authorization"] = f"Bearer {KEY}"
            headers["x-api-key"] = KEY
        req = urllib.request.Request(UPSTREAM + self.path, data=body, headers=headers, method=method)
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return urllib.request.urlopen(req, timeout=1800, context=ctx)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            b = json.dumps(CATALOG).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        self._passthrough("GET")

    def do_POST(self):
        self._passthrough("POST")

    def _passthrough(self, method):
        try:
            up = self._upstream_req(method)
        except urllib.error.HTTPError as e:
            b = e.read() or b"{}"
            self.send_response(e.code)
            self.send_header("Content-Type", e.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        except Exception as e:
            b = json.dumps({"type": "error", "error": {"type": "internal", "message": str(e)}}).encode()
            self.send_response(502)
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        try:
            self.send_response(up.status)
            for k, v in up.headers.items():
                if k.lower() in HOP or k.lower() == "content-length":
                    continue
                self.send_header(k, v)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            while True:
                chunk = up.read1(65536) if hasattr(up, "read1") else up.read(65536)
                if not chunk:
                    break
                self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            up.close()

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
