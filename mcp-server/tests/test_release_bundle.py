"""Exercise real release assembly without touching any game or user config."""
import hashlib
import importlib
import io
import json
from pathlib import Path
import tarfile
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
    checksum = hashlib.sha256(output.read_bytes()).hexdigest()
    assert output.with_suffix('.zip.sha256').read_bytes() == f'{checksum}  {output.name}\n'.encode('ascii')
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


def test_prepared_assets_have_portable_checksums(tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(str(ROOT / 'scripts'))
    prepare_release = importlib.import_module('prepare_release')
    for name in ('gradle.properties', 'mcp-server/src/minecraft_codex_bridge/_version.py',
                 'LICENSE', 'mcp-server/LICENSE'):
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / name).read_bytes())
    release = prepare_release.metadata(tmp_path)
    jar = tmp_path / 'build/libs' / release['jar_name']
    jar.parent.mkdir(parents=True)
    license_bytes = (tmp_path / 'LICENSE').read_bytes()
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('mcmod.info', json.dumps([{'modid': 'codexbridge', 'mcversion': '1.7.10',
                                                  'name': 'BridgeNH', 'version': release['version']}]))
        archive.writestr('META-INF/LICENSE', license_bytes)
        archive.writestr('com/Goderium/BridgeNH/BridgeNH.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x34')
    output = tmp_path / 'build/dist'
    output.mkdir()
    package = 'minecraft_codex_bridge_mcp-' + release['python_version']
    with zipfile.ZipFile(output / (package + '-py3-none-any.whl'), 'w') as archive:
        archive.writestr(package + '.dist-info/METADATA',
                         f"Version: {release['python_version']}\nRequires-Python: >=3.11\n")
        archive.writestr(package + '.dist-info/licenses/LICENSE', license_bytes)
        archive.writestr('minecraft_codex_bridge/guides/workspace.md', '# Fixture guide\n')
    with tarfile.open(output / (package + '.tar.gz'), 'w:gz') as archive:
        info = tarfile.TarInfo(package + '/LICENSE')
        info.size = len(license_bytes)
        archive.addfile(info, io.BytesIO(license_bytes))

    result = prepare_release.prepare(tmp_path)

    raw = (output / 'SHA256SUMS.txt').read_bytes()
    assert b'\r' not in raw and raw.endswith(b'\n')
    hashes = {name: checksum for checksum, name in (line.split('  ', 1) for line in raw.decode('ascii').splitlines())}
    assert len(hashes) == 5
    assert hashes == result['sha256']
    assert hashes == {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in hashes}


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
