"""L1: loopback WS echo server to test the hand-rolled client (handshake/frames/close)."""
import socket
import struct
import threading

import pytest

from runtime.wsclient import WsClient, WsClosed

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def _recv_all(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def _ws_echo_server(sock: socket.socket, ready: threading.Event):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            return
        data += chunk
    import base64, hashlib
    key = ""
    for line in data.decode("latin-1").split("\r\n"):
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    sock.sendall(("HTTP/1.1 101 Switching Protocols\r\n"
                  "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                  f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
    ready.set()
    # echo text frames unmasked
    while True:
        hdr = _recv_all(sock, 2)
        opcode = hdr[0] & 0x0F
        n = hdr[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", _recv_all(sock, 2))[0]
        elif n == 127:
            n = struct.unpack(">Q", _recv_all(sock, 8))[0]
        masked = bool(hdr[1] & 0x80)
        mask = _recv_all(sock, 4) if masked else None
        payload = _recv_all(sock, n) if n else b""
        if masked and n:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        if opcode == 0x8:
            break
        if opcode == 0x1:
            if len(payload) < 126:
                frame = bytes([0x81, len(payload)]) + payload
            elif len(payload) < 65536:
                frame = bytes([0x81, 126]) + struct.pack(">H", len(payload)) + payload
            else:
                frame = bytes([0x81, 127]) + struct.pack(">Q", len(payload)) + payload
            sock.sendall(frame)
    sock.close()


@pytest.fixture()
def echo_server():
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]
    ready = threading.Event()
    holder = {}

    def accept_thread():
        conn, _ = srv.accept()
        holder["conn"] = conn
        _ws_echo_server(conn, ready)

    t = threading.Thread(target=accept_thread, daemon=True)
    t.start()
    yield port, ready
    srv.close()


def test_handshake_send_recv_close(echo_server):
    port, ready = echo_server
    c = WsClient("127.0.0.1", port, "/ws/executor?executorId=1&token=x")
    c.connect()
    assert ready.wait(3), "server did not finish handshake"
    c.send_json({"type": "HEARTBEAT", "protocolFeatures": ["OPEN_RUNTIME_V1"]})
    opcode, payload = c.recv_frame()
    assert opcode == 0x1
    assert b"HEARTBEAT" in payload
    # large frame (300KB) survives
    big = {"type": "X", "blob": "a" * 300_000}
    c.send_json(big)
    _, p2 = c.recv_frame()
    assert len(p2) > 300_000 and json.loads(p2)["blob"] == "a" * 300_000
    c.close()


def test_close_detection(echo_server):
    port, ready = echo_server
    c = WsClient("127.0.0.1", port, "/ws/executor")
    c.connect()
    ready.wait(3)
    holder_conn = None
    c.close()
    with pytest.raises(WsClosed):
        c.recv_frame()


import json  # noqa: E402  (used above)
