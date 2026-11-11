"""Validate the shared mod/MCP version before building or publishing."""
from __future__ import annotations

import argparse
import ast
import json
import os
from pathlib import Path
import re

from packaging.version import Version

ROOT = Path(__file__).resolve().parents[1]


def metadata(root: Path = ROOT, ref: str = '') -> dict:
    properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    name, version = properties['modName'].strip(), properties['modVersion'].strip()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]*', name):
        raise ValueError('Invalid modName.')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:(?:a|b|rc)\d+|-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?', version):
        raise ValueError('Use a version such as 0.1.0 or 0.1.0-rc.1.')
    normalized = Version(version)
    tree = ast.parse((root / 'mcp-server/src/minecraft_codex_bridge/_version.py').read_text(encoding='utf-8'))
    versions = [ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == '__version__' for target in node.targets)]
    if versions != [version]:
        raise ValueError('modVersion and the MCP __version__ must match exactly.')
    if ref.startswith('refs/tags/') and ref != 'refs/tags/v' + version:
        raise ValueError('Release tag must be v' + version + '.')
    if (root / 'LICENSE').read_bytes() != (root / 'mcp-server/LICENSE').read_bytes():
        raise ValueError('Root and Python package LICENSE files must match.')
    return {'version': version, 'python_version': str(normalized), 'jar_name': f'{name}-{version}.jar',
            'bundle_name': f'{name}-{version}-windows.zip', 'prerelease': str(normalized.is_prerelease).lower()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', default=os.environ.get('GITHUB_REF', ''))
    parser.add_argument('--github-output', action='store_true')
    options = parser.parse_args()
    result = metadata(ref=options.ref)
    if options.github_output:
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
            for key, value in result.items():
                stream.write(f'{key}={value}\n')
    print(json.dumps(result, indent=2))
