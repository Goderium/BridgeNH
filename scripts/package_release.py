"""Build a portable installer ZIP from an allowlist, never from a checkout archive."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'mcp-server/src'))
from minecraft_codex_bridge.installation import validate_jar


def build(jar: Path, output: Path):
    jar = validate_jar(jar)
    files = {}
    for name in ('install.cmd', 'install.ps1', 'README.md', 'README_zh.md', 'INSTALL.md', 'INSTALL_zh.md',
                 'AGENTS.md', 'PROTOCOL.md', 'PROTOCOL_zh.md',
                 'docs/PERMISSIONS.md', 'docs/PERMISSIONS_zh.md',
                 'docs/PLAYER_DIALOGUE.md', 'docs/PLAYER_DIALOGUE_zh.md',
                 'docs/ARCHITECTURE.md', 'docs/ARCHITECTURE_zh.md',
                 'docs/NATIVE_CONTROL_GAPS.md', 'docs/NATIVE_CONTROL_GAPS_zh.md',
                 'LICENSE', 'mcb-config.example.json',
                 'scripts/mcb.ps1', 'scripts/mcb-common.ps1', 'mcp-server/pyproject.toml',
                 'mcp-server/README.md', 'mcp-server/README_zh.md', 'mcp-server/LICENSE', 'mcp-server/MANIFEST.in', 'mcp-server/codex-config.example.toml'):
        files[name] = (ROOT / name).read_bytes()
    package = ROOT / 'mcp-server/src/minecraft_codex_bridge'
    for path in sorted(package.rglob('*')):
        if path.is_file() and (path.suffix == '.py' or path.suffix == '.md' and path.parent.name == 'guides'):
            files[path.relative_to(ROOT).as_posix()] = path.read_bytes()
    files['mod/' + jar.name] = jar.read_bytes()
    manifest = {'formatVersion': 1, 'files': {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}}
    files['manifest.json'] = (json.dumps(manifest, indent=2) + '\n').encode('utf-8')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr('BridgeNH/' + name, data)
    result = {'file': str(output.resolve()), 'files': len(files), 'bytes': output.stat().st_size,
              'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}
    output.with_suffix(output.suffix + '.sha256').write_text(
        result['sha256'] + '  ' + output.name + '\n', encoding='ascii', newline='\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    options = parser.parse_args()
    print(json.dumps(build(options.jar, options.output), indent=2))
