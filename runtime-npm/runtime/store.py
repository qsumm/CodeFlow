"""SQLite-backed local state (runs/events/outbox/terminal)."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
  dispatch_id INTEGER PRIMARY KEY,
  tenant_id TEXT NOT NULL, workitem_id TEXT NOT NULL, attempt INTEGER NOT NULL,
  state TEXT NOT NULL, capsule_dir TEXT, provider TEXT NOT NULL,
  session_ref TEXT, error TEXT,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  dispatch_id INTEGER NOT NULL, ts TEXT NOT NULL, type TEXT NOT NULL,
  step_order INTEGER, step_id TEXT, step_name TEXT, payload TEXT,
  FOREIGN KEY (dispatch_id) REFERENCES runs(dispatch_id)
);
CREATE INDEX IF NOT EXISTS idx_events_dispatch ON events(dispatch_id, id);
CREATE TABLE IF NOT EXISTS outbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  dispatch_id INTEGER, frame_type TEXT NOT NULL, payload TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL, sent_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_outbox_state ON outbox(state, id);
CREATE TABLE IF NOT EXISTS terminal (
  dispatch_id INTEGER PRIMARY KEY, success INTEGER NOT NULL, finished_at TEXT NOT NULL
);
"""

ACTIVE_STATES = ("received", "preparing", "running")


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


class StateStore:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.executescript(_SCHEMA)
        self.conn.commit()

    # ---- runs ----
    def create_run(self, dispatch: dict, provider: str) -> bool:
        """Insert run row; returns False if dispatch already tracked."""
        try:
            with self._lock, self.conn:
                self.conn.execute(
                    "INSERT INTO runs (dispatch_id, tenant_id, workitem_id, attempt, state, provider, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?)",
                    (int(dispatch["dispatchId"]), str(dispatch.get("tenantId")), str(dispatch.get("workitemId")),
                     int(dispatch.get("attempt", 1)), "received", provider, _now(), _now()))
            return True
        except sqlite3.IntegrityError:
            return False

    def run_state(self, dispatch_id: int) -> str | None:
        row = self.conn.execute("SELECT state FROM runs WHERE dispatch_id=?", (dispatch_id,)).fetchone()
        return row[0] if row else None

    def update_run(self, dispatch_id: int, **fields) -> None:
        if not fields:
            return
        sets = ", ".join(f"{k}=?" for k in fields)
        vals = list(fields.values()) + [_now(), dispatch_id]
        with self._lock, self.conn:
            self.conn.execute(f"UPDATE runs SET {sets}, updated_at=? WHERE dispatch_id=?", vals)

    def set_session(self, dispatch_id: int, session_ref: str) -> None:
        self.update_run(dispatch_id, session_ref=session_ref)

    def get_session(self, dispatch_id: int) -> str | None:
        row = self.conn.execute("SELECT session_ref FROM runs WHERE dispatch_id=?", (dispatch_id,)).fetchone()
        return row[0] if row else None

    def active_runs(self) -> list[sqlite3.Row]:
        q = ",".join("?" * len(ACTIVE_STATES))
        return self.conn.execute(f"SELECT * FROM runs WHERE state IN ({q})", ACTIVE_STATES).fetchall()

    # ---- events ----
    def add_event(self, dispatch_id: int, type_: str, *, step_order=None, step_id=None,
                  step_name=None, payload: dict | None = None) -> None:
        with self._lock, self.conn:
            self.conn.execute(
                "INSERT INTO events (dispatch_id, ts, type, step_order, step_id, step_name, payload) VALUES (?,?,?,?,?,?,?)",
                (dispatch_id, _now(), type_, step_order, step_id, step_name,
                 json.dumps(payload, ensure_ascii=False) if payload else None))

    # ---- outbox ----
    def enqueue_frame(self, frame_type: str, payload: dict, dispatch_id: int | None = None) -> int:
        with self._lock, self.conn:
            cur = self.conn.execute(
                "INSERT INTO outbox (dispatch_id, frame_type, payload, created_at) VALUES (?,?,?,?)",
                (dispatch_id, frame_type, json.dumps(payload, ensure_ascii=False), _now()))
            return cur.lastrowid  # type: ignore[return-value]

    def pending_frames(self) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT id, dispatch_id, frame_type, payload FROM outbox WHERE state='pending' ORDER BY id").fetchall()

    def mark_sent(self, outbox_id: int) -> None:
        with self._lock, self.conn:
            self.conn.execute("UPDATE outbox SET state='sent', sent_at=? WHERE id=?", (_now(), outbox_id))

    # ---- terminal / idempotency ----
    def is_terminal(self, dispatch_id: int) -> bool:
        row = self.conn.execute("SELECT 1 FROM terminal WHERE dispatch_id=?", (dispatch_id,)).fetchone()
        return row is not None

    def mark_terminal(self, dispatch_id: int, success: bool) -> None:
        with self._lock, self.conn:
            self.conn.execute("INSERT OR REPLACE INTO terminal (dispatch_id, success, finished_at) VALUES (?,?,?)",
                              (dispatch_id, 1 if success else 0, _now()))
            self.conn.execute("UPDATE runs SET state=?, updated_at=? WHERE dispatch_id=?",
                              ("completed" if success else "failed", _now(), dispatch_id))
