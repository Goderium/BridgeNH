"""Exercise real release assembly without touching any game or user config."""
import importlib
import json
from pathlib import Path
import zipfile

import pytest

from minecraft_codex_bridge.setup import verify_bundle
from minecraft_codex_bridge.installation import InstallError

ROOT = Path(__file__).resolve().parents[2]


def test_portable_bundle_contains_license_and_detects_tampering(tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(str(ROOT / 'scripts'))
    package_release = importlib.import_module('package_release')
    jar = tmp_path / 'BridgeNH-0.1.0.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('mcmod.info', json.dumps([{'modid': 'codexbridge', 'mcversion': '1.7.10'}]))
        archive.writestr('com/Goderium/BridgeNH/BridgeNH.class', b'test fixture')
    output = tmp_path / 'release.zip'
    package_release.build(jar, output)
    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        assert 'BridgeNH/LICENSE' in names and 'BridgeNH/mcp-server/LICENSE' in names
        assert not any(part in name for name in names for part in ('.mcb.local.json', '.venv/', '__pycache__/', 'runtime.json'))
        archive.extractall(tmp_path / 'extracted')
    payload = tmp_path / 'extracted/BridgeNH'
    verify_bundle(payload)
    (payload / 'mcp-server/src/minecraft_codex_bridge/_version.py').write_text('tampered', encoding='utf-8')
    with pytest.raises(InstallError, match='checksum mismatch'):
        verify_bundle(payload)


def test_release_metadata_rejects_mismatched_tag_and_version(tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(str(ROOT / 'scripts'))
    release = importlib.import_module('release_metadata')
    current = release.metadata(ROOT)
    assert release.metadata(ROOT, 'refs/tags/v' + current['version'])['jar_name'] == 'BridgeNH-' + current['version'] + '.jar'
    with pytest.raises(ValueError, match='Release tag'):
        release.metadata(ROOT, 'refs/tags/v999.0.0')
    (tmp_path / 'gradle.properties').write_text('modName=BridgeNH\nmodVersion=0.1.0\n', encoding='utf-8')
    version = tmp_path / 'mcp-server/src/minecraft_codex_bridge/_version.py'
    version.parent.mkdir(parents=True)
    version.write_text('__version__ = "0.2.0"\n', encoding='utf-8')
    with pytest.raises(ValueError, match='must match exactly'):
        release.metadata(tmp_path)
