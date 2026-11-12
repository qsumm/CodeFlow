"""JSON-line structured logging."""
from __future__ import annotations

import json
import sys
import threading
import time

_LEVELS = {"debug": 10, "info": 20, "warn": 30, "error": 40}
_lock = threading.Lock()


class JsonLogger:
    def __init__(self, level: str = "info", stream=None):
        self.level = _LEVELS.get(level, 20)
        self.stream = stream or sys.stdout

    def _log(self, level: str, event: str, **kw):
        if _LEVELS[level] < self.level:
            return
        rec = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "level": level, "event": event}
        rec.update({k: v for k, v in kw.items() if v is not None})
        line = json.dumps(rec, ensure_ascii=False, default=str)
        with _lock:
            self.stream.write(line + "\n")
            self.stream.flush()

    def debug(self, event, **kw): self._log("debug", event, **kw)
    def info(self, event, **kw): self._log("info", event, **kw)
    def warn(self, event, **kw): self._log("warn", event, **kw)
    def error(self, event, **kw): self._log("error", event, **kw)


log = JsonLogger()
