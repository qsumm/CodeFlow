"""Minimal RFC6455 WebSocket client (text frames), stdlib only.

Implements the subset needed by the protocol: client->server masked text frames,
server->client unmasked text/binary/ping/close, client-initiated close.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import threading

_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WsClosed(Exception):
    pass


class WsIdleTimeout(WsClosed):
    """Read timeout with no inbound frames; the connection may still be alive."""
    pass


class WsClient:
    def __init__(self, host: str, port: int, path: str, use_tls: bool = False,
                 extra_headers: dict | None = None, timeout: float = 60.0):
        self.host, self.port, self.path = host, port, path
        self.use_tls = use_tls
        self.extra_headers = extra_headers or {}
        self.timeout = timeout
        self.sock: socket.socket | None = None
        self._send_lock = threading.Lock()
        self._leftover = b""

    # ---- handshake -------------------------------------------------------
    def connect(self) -> None:
        raw = socket.create_connection((self.host, self.port), timeout=self.timeout)
        if self.use_tls:
            ctx = ssl.create_default_context()
            raw = ctx.wrap_socket(raw, server_hostname=self.host.split(":")[0])
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {self.host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
        )
        for k, v in self.extra_headers.items():
            req += f"{k}: {v}\r\n"
        req += "\r\n"
        raw.sendall(req.encode())
        raw.settimeout(self.timeout)
        status, headers = self._read_http_response(raw)
        if status != 101:
            raw.close()
            raise WsClosed(f"handshake rejected: HTTP {status}: {headers.get('x-error', headers)}")
        expect = base64.b64encode(hashlib.sha1((key + _GUID).encode()).digest()).decode()
        if headers.get("sec-websocket-accept") != expect:
            raw.close()
            raise WsClosed("bad Sec-WebSocket-Accept")
        self.sock = raw

    def _read_http_response(self, sock: socket.socket) -> tuple[int, dict]:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                raise WsClosed("connection closed during handshake")
            data += chunk
            if len(data) > 65536:
                raise WsClosed("handshake response too large")
        head, _, rest = data.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        status = int(lines[0].split(" ")[1]) if len(lines[0].split(" ")) > 1 else 0
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        # stash leftover bytes for frame reader
        self._leftover = rest
        return status, headers

    # ---- frames ----------------------------------------------------------
    @staticmethod
    def _encode_frame(opcode: int, payload: bytes) -> bytes:
        mask = os.urandom(4)
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return header + mask + masked

    def _recv_exact(self, n: int) -> bytes:
        assert self.sock is not None
        buf = bytearray(self._leftover)
        self._leftover = b""
        while len(buf) < n:
            try:
                chunk = self.sock.recv(n - len(buf))
            except socket.timeout as e:
                raise WsIdleTimeout("read idle timeout") from e
            except OSError as e:
                raise WsClosed(f"socket error: {e}") from e
            if not chunk:
                raise WsClosed("connection closed")
            buf.extend(chunk)
        return bytes(buf)

    def recv_frame(self) -> tuple[int, bytes]:
        """Returns (opcode, payload). Auto-answers ping; raises WsClosed on close."""
        if self.sock is None:
            raise WsClosed("not connected")
        b1, b2 = self._recv_exact(2)
        opcode = b1 & 0x0F
        masked = b2 & 0x80
        n = b2 & 0x7F
        if n == 126:
            (n,) = struct.unpack(">H", self._recv_exact(2))
        elif n == 127:
            (n,) = struct.unpack(">Q", self._recv_exact(8))
        mask = self._recv_exact(4) if masked else None
        payload = self._recv_exact(n) if n else b""
        if mask:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        if opcode == 0x9:  # ping -> pong
            self._send_raw(0xA, payload)
            return self.recv_frame()
        if opcode == 0x8:
            raise WsClosed(f"server closed: {payload[:120]!r}")
        if opcode in (0x1, 0x2, 0x0):
            return opcode, payload
        return self.recv_frame()  # ignore unknown (e.g. pong)

    def _send_raw(self, opcode: int, payload: bytes) -> None:
        if self.sock is None:
            raise WsClosed("not connected")
        with self._send_lock:
            self.sock.sendall(self._encode_frame(opcode, payload))

    def send_text(self, text: str) -> None:
        self._send_raw(0x1, text.encode())

    def send_json(self, obj: dict) -> None:
        self.send_text(json.dumps(obj, ensure_ascii=False))

    def recv_json(self) -> dict:
        while True:
            _, payload = self.recv_frame()
            return json.loads(payload.decode())

    def close(self) -> None:
        if self.sock is not None:
            try:
                self._send_raw(0x8, struct.pack(">H", 1000))
            except OSError:
                pass
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None
