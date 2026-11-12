import hashlib
import json
import zipfile

import pytest

from runtime.packager import PackageError, check_md5, mount


def _make_pkg(tmp_path, files: dict, digests: dict | None = None, version="ainative.taskPackage.v1"):
    base = tmp_path / "pkg"
    base.mkdir(exist_ok=True)
    for name, content in files.items():
        p = base / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    if digests is None:
        digests = {n: "sha256:" + hashlib.sha256(c.encode()).hexdigest() for n, c in files.items()}
    manifest = {"schemaVersion": version, "packageId": "pkg_1", "fileDigests": digests}
    (base / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    zpath = tmp_path / "pkg.zip"
    with zipfile.ZipFile(zpath, "w") as z:
        for p in sorted(base.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(base))
    return zpath


FILES = {
    "workitem.md": "# 工单\n修复登录超时",
    "identity.json": json.dumps({"name": "dev", "roleCode": "dev"}),
    "sdlc.json": json.dumps({"steps": [{"stepId": "s1", "stepOrder": 1, "name": "开发", "goal": "g"}]}),
}


def test_md5_ok_and_mismatch(tmp_path):
    z = _make_pkg(tmp_path, FILES)
    good = hashlib.md5(z.read_bytes()).hexdigest()
    check_md5(z, good)
    with pytest.raises(PackageError, match="md5_mismatch"):
        check_md5(z, "0" * 32)


def test_mount_ok_and_current_step(tmp_path):
    z = _make_pkg(tmp_path, FILES)
    cap = mount(z, tmp_path / "cap")
    assert cap.manifest["packageId"] == "pkg_1"
    assert cap.identity["roleCode"] == "dev"
    step = cap.current_step()
    assert step["stepId"] == "s1"


def test_mount_digest_mismatch(tmp_path):
    z = _make_pkg(tmp_path, FILES, digests={**{n: "sha256:" + hashlib.sha256(c.encode()).hexdigest() for n, c in FILES.items()},
                                            "workitem.md": "sha256:" + "0" * 64})
    with pytest.raises(PackageError, match="digest mismatch"):
        mount(z, tmp_path / "cap2")


def test_mount_bad_version(tmp_path):
    z = _make_pkg(tmp_path, FILES, version="future.v2")
    with pytest.raises(PackageError, match="schemaVersion"):
        mount(z, tmp_path / "cap3")


def test_zip_escape_rejected(tmp_path):
    base = tmp_path / "evil"
    base.mkdir()
    zpath = tmp_path / "evil.zip"
    with zipfile.ZipFile(zpath, "w") as z:
        z.writestr("../escape.txt", "x")
    with pytest.raises(PackageError, match="unsafe"):
        mount(zpath, tmp_path / "cap4")
    assert not (tmp_path / "escape.txt").exists()
