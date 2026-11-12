"""Public downloads must not distribute workstation resource forks or metadata."""
from pathlib import Path
import zipfile


def test_help_skill_archive_exposes_only_functional_content():
    archive_path = (Path(__file__).resolve().parents[2]
                    / 'frontend/public/help/assets/ainative.zip')
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        assert [entry.filename for entry in entries] == ['ainative/SKILL.md']
        assert archive.comment == b''
        assert all(entry.extra == b'' and entry.comment == b'' for entry in entries)
        content = archive.read('ainative/SKILL.md')
        assert content.startswith(b'---\n')
        assert b'name: ainative' in content
        assert b'com.alibaba.security' not in content
