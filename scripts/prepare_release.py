"""Assemble verified release assets after Gradle, pytest and python -m build."""
from __future__ import annotations

import email
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import zipfile

from package_release import build
from release_metadata import ROOT, metadata


def prepare(root: Path = ROOT) -> dict:
    release = metadata(root)
    output = root / 'build/dist'
    version = release['python_version']
    wheel = output / f'minecraft_codex_bridge_mcp-{version}-py3-none-any.whl'
    sdist = output / f'minecraft_codex_bridge_mcp-{version}.tar.gz'
    jar = root / 'build/libs' / release['jar_name']
    with zipfile.ZipFile(jar) as archive:
        info = json.loads(archive.read('mcmod.info'))
        if not any(mod.get('modid') == 'codexbridge' and mod.get('name') == 'BridgeNH'
                   and mod.get('version') == release['version'] for mod in info):
            raise ValueError('Built mod identity/version does not match the release.')
        if archive.read('META-INF/LICENSE') != (root / 'LICENSE').read_bytes():
            raise ValueError('Missing or mismatched mod license.')
        classes = [name for name in archive.namelist() if name.endswith('.class')]
        if not classes or any(not name.startswith('com/Goderium/BridgeNH/') for name in classes):
            raise ValueError('Unexpected bundled class outside the BridgeNH namespace.')
        if any(int.from_bytes(archive.read(name)[6:8], 'big') != 52 for name in classes):
            raise ValueError('Mod classes must target Java 8.')
    with zipfile.ZipFile(wheel) as archive:
        meta_name = next(name for name in archive.namelist() if name.endswith('.dist-info/METADATA'))
        wheel_meta = email.message_from_bytes(archive.read(meta_name))
        if wheel_meta['Version'] != version or wheel_meta['Requires-Python'] != '>=3.11':
            raise ValueError('Unexpected wheel version or Python requirement.')
        if not any(name.endswith('/LICENSE') for name in archive.namelist()):
            raise ValueError('Wheel license is missing.')
        if 'minecraft_codex_bridge/guides/workspace.md' not in archive.namelist():
            raise ValueError('Wheel agent guides are missing.')
    with tarfile.open(sdist, 'r:gz') as archive:
        if not any(name.endswith('/LICENSE') for name in archive.getnames()):
            raise ValueError('Source distribution license is missing.')
    allowed = {jar.name, wheel.name, sdist.name, release['bundle_name'], release['bundle_name'] + '.sha256', 'SHA256SUMS.txt'}
    unexpected = [path.name for path in output.iterdir() if path.name not in allowed]
    if unexpected:
        raise ValueError('Remove unrelated/stale release assets first: ' + ', '.join(unexpected))
    copied_jar = output / jar.name
    shutil.copy2(jar, copied_jar)
    bundle = output / release['bundle_name']
    build(jar, bundle)
    artifacts = sorted([copied_jar, wheel, sdist, bundle, bundle.with_suffix('.zip.sha256')])
    hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in artifacts}
    (output / 'SHA256SUMS.txt').write_text(''.join(f'{value}  {name}\n' for name, value in hashes.items()), encoding='ascii')
    return {'directory': str(output.resolve()), **release, 'sha256': hashes}


if __name__ == '__main__':
    print(json.dumps(prepare(), indent=2))
