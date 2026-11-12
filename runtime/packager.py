"""Task package download, integrity checks, capsule mounting."""
from __future__ import annotations

import hashlib
import json
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from urllib.request import urlopen

from .logging_util import log

SCHEMA_VERSION = "ainative.taskPackage.v1"


class PackageError(Exception):
    pass


@dataclass
class Capsule:
    dir: Path
    manifest: dict
    identity: dict = field(default_factory=dict)
    sdlc: dict = field(default_factory=dict)
    roster: dict = field(default_factory=dict)
    workitem_md: str = ""

    def current_step(self) -> dict | None:
        """Locate the step this dispatch is for (manifest.sdlcStepId), else the first step."""
        steps = self.sdlc.get("steps") or []
        if not steps:
            return None
        want = str(self.manifest.get("sdlcStepId") or "")
        for s in steps:
            if str(s.get("stepId") or s.get("id") or "") == want:
                return s
        return steps[0]


def download(url: str, dest: Path, timeout: int = 120) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    try:
        with urlopen(url, timeout=timeout) as resp, open(tmp, "wb") as f:
            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
        tmp.replace(dest)
    except Exception as e:
        tmp.unlink(missing_ok=True)
        raise PackageError(f"package_download_failed: {e}") from e


def check_md5(path: Path, expected: str) -> None:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    actual = h.hexdigest()
    if expected and actual != expected:
        raise PackageError(f"package_md5_mismatch: expected={expected} actual={actual}")


def _safe_extract(zip_path: Path, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    root = out_dir.resolve()
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            name = info.filename
            if name.startswith("/") or ".." in Path(name).parts or (name.endswith("/") is False and name.replace("\\", "/").startswith("__MACOSX")):
                raise PackageError(f"unsafe zip entry: {name}")
            target = (root / name).resolve()
            if not str(target).startswith(str(root)):
                raise PackageError(f"unsafe zip entry escapes capsule: {name}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, open(target, "wb") as dst:
                    while True:
                        chunk = src.read(256 * 1024)
                        if not chunk:
                            break
                        dst.write(chunk)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def mount(zip_path: Path, capsule_dir: Path) -> Capsule:
    """Extract, verify manifest schemaVersion + per-file digests, parse core docs."""
    capsule_dir.mkdir(parents=True, exist_ok=True)
    _safe_extract(zip_path, capsule_dir)
    manifest_path = capsule_dir / "manifest.json"
    if not manifest_path.exists():
        raise PackageError("capsule_invalid: manifest.json missing")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise PackageError(f"capsule_invalid: schemaVersion={manifest.get('schemaVersion')!r}")

    digests = manifest.get("fileDigests") or {}
    for name, expected in digests.items():
        f = capsule_dir / name
        if not f.exists():
            raise PackageError(f"capsule_invalid: package file missing: {name}")
        actual = _sha256(f)
        if actual != expected:
            raise PackageError(f"capsule_invalid: digest mismatch {name}: {expected} != {actual}")

    def _load(name: str) -> dict:
        p = capsule_dir / name
        if not p.exists():
            return {}
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise PackageError(f"capsule_invalid: {name} not valid JSON: {e}") from e

    identity = _load("identity.json")
    sdlc = _load("sdlc.json")
    roster = _load("roster.json")
    wi = capsule_dir / "workitem.md"
    return Capsule(dir=capsule_dir, manifest=manifest, identity=identity, sdlc=sdlc,
                   roster=roster, workitem_md=wi.read_text(encoding="utf-8") if wi.exists() else "")


def prepare(dispatch: dict, capsule_root: Path) -> tuple[Path, Capsule]:
    """Full P0 package pipeline: download -> md5 -> extract+verify -> Capsule."""
    attempt = int(dispatch.get("attempt", 1))
    pkg_dir = capsule_root / str(dispatch["tenantId"]) / str(dispatch["workitemId"]) / str(dispatch["dispatchId"]) / f"attempt{attempt}"
    zip_path = pkg_dir / "package.zip"
    log.info("package.download_start", dispatch_id=dispatch["dispatchId"], url_host=str(dispatch["downloadUrl"]).split("/")[2])
    download(dispatch["downloadUrl"], zip_path)
    check_md5(zip_path, str(dispatch.get("md5") or ""))
    (pkg_dir / "package.ok").write_text("ok\n")
    capsule = mount(zip_path, pkg_dir / "capsule")
    log.info("package.ready", dispatch_id=dispatch["dispatchId"], role=capsule.manifest.get("roleCode"))
    return pkg_dir, capsule
