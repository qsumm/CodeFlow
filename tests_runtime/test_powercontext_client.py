"""PowerContext 客户端单测:全 mock HTTP,验证降级契约。"""
import json
import unittest
from unittest import mock

import sys
sys.path.insert(0, "/Users/summer/AINative/runtime")
from powercontext_client import (  # noqa: E402
    PowerContextSettings, prepare_context, remember, bind_workspace,
)

S = PowerContextSettings(base_url="http://127.0.0.1:8077", timeout_sec=2.0)


def _fake_urlopen(status=200, body=b"{}"):
    m = mock.MagicMock()
    m.status = status
    m.read.return_value = body
    m.__enter__ = mock.MagicMock(return_value=m)
    m.__exit__ = mock.MagicMock(return_value=False)
    return m


class FromEnvTests(unittest.TestCase):
    def test_unconfigured_returns_none(self):
        self.assertIsNone(PowerContextSettings.from_env({}))

    def test_configured(self):
        st = PowerContextSettings.from_env({"POWERCONTEXT_BASE_URL": "http://x:1/"})
        self.assertEqual(st.base_url, "http://x:1")
        self.assertEqual(st.timeout_sec, 8.0)

    def test_bad_timeout_falls_back(self):
        st = PowerContextSettings.from_env({"POWERCONTEXT_BASE_URL": "http://x",
                                            "POWERCONTEXT_TIMEOUT_SEC": "abc"})
        self.assertEqual(st.timeout_sec, 8.0)


class PrepareTests(unittest.TestCase):
    def test_unconfigured_none(self):
        self.assertIsNone(prepare_context(1, "q", settings=None))

    def test_ready_returns_content(self):
        body = json.dumps({"status": "ready", "content": "CTX"}).encode()
        with mock.patch("powercontext_client.urllib.request.urlopen", return_value=_fake_urlopen(200, body)):
            self.assertEqual(prepare_context("scp_x", "q", settings=S), "CTX")

    def test_empty_status_returns_none(self):
        body = json.dumps({"status": "empty", "content": ""}).encode()
        with mock.patch("powercontext_client.urllib.request.urlopen", return_value=_fake_urlopen(200, body)):
            self.assertIsNone(prepare_context("scp_x", "q", settings=S))

    def test_http_error_returns_none(self):
        import urllib.error
        with mock.patch("powercontext_client.urllib.request.urlopen",
                        side_effect=urllib.error.HTTPError("u", 500, "x", None, None)):
            self.assertIsNone(prepare_context("scp_x", "q", settings=S))


class RememberTests(unittest.TestCase):
    def test_remember_success(self):
        bodies = [json.dumps({"scope_id": "scp_x"}).encode(), json.dumps({"changed": True}).encode()]
        with mock.patch("powercontext_client.urllib.request.urlopen",
                        side_effect=[_fake_urlopen(200, b) for b in bodies]):
            self.assertTrue(remember(10000, "decision", "text", settings=S))

    def test_remember_unbound_false(self):
        with mock.patch("powercontext_client.urllib.request.urlopen",
                        return_value=_fake_urlopen(200, json.dumps({"scope_id": None}).encode())):
            self.assertFalse(remember(10000, "decision", "t", settings=S))


class BindTests(unittest.TestCase):
    def test_bind_put_success(self):
        with mock.patch("powercontext_client.urllib.request.urlopen",
                        return_value=_fake_urlopen(200, b"{}")):
            self.assertTrue(bind_workspace(10000, "scp_x", settings=S))


if __name__ == "__main__":
    unittest.main()
