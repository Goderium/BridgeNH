import json
from pathlib import Path
import os
import subprocess
import sys
from types import SimpleNamespace
import zipfile

import pytest

from minecraft_codex_bridge import installation as install, setup


def instance(tmp_path):
    path = tmp_path / 'Prism/instances/中文 test'
    (path / '.minecraft/mods').mkdir(parents=True)
    (path / 'instance.cfg').write_text('InstanceType=OneSix\n', encoding='utf-8')
    (path / 'mmc-pack.json').write_text(json.dumps({'formatVersion': 1, 'components': [
        {'uid': 'net.minecraft', 'version': '1.7.10'}]}), encoding='utf-8')
    (path.parent.parent / 'prismlauncher.exe').touch()
    return path


def jar(path, marker='new'):
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('mcmod.info', json.dumps([{'modid': 'codexbridge', 'mcversion': '1.7.10'}]))
        entrypoint = ('com/Goderium/BridgeNH/BridgeNH.class' if path.name.startswith('BridgeNH-')
                      else 'dev/codex/minecraftbridge/CodexBridgeMod.class')
        archive.writestr(entrypoint, marker)
    return path


def test_codex_merge_preserves_unrelated_and_permissions_and_is_idempotent(tmp_path):
    original = b'''# untouched user preferences
model = "example"
[features]
multi_agent = false
[mcp_servers.other]
command = "keep"
[mcp_servers.minecraft_codex_bridge]
command = "old-python"
args = ["old/run.py"]
tool_timeout_sec = 85
default_tools_approval_mode = "approve"
[mcp_servers.minecraft_codex_bridge.env]
MCB_RUNTIME_FILE = "old-runtime"
CUSTOM = "keep"
[mcp_servers.minecraft_codex_bridge.tools.mcb_chat]
approval_mode = "approve"
[projects."C:/my project"]
trust_level = "trusted"
'''
    python, config = tmp_path / '中文 runtime/python.exe', tmp_path / 'config.json'
    updated, name = install.codex_registration(original, python, config, 'test')
    data = install.toml_data(updated)
    assert name == 'minecraft_codex_bridge'
    assert data['features'] == {'multi_agent': False}
    assert data['projects'] == install.toml_data(original)['projects']
    entry = data['mcp_servers'][name]
    assert entry['tool_timeout_sec'] == 85
    assert entry['tools']['mcb_chat']['approval_mode'] == 'approve'
    assert entry['env'] == {'CUSTOM': 'keep', 'MCB_CONFIG_FILE': str(config), 'MCB_PROFILE': 'test', 'PYTHONUTF8': '1'}
    assert entry['args'] == ['-m', 'minecraft_codex_bridge']
    assert b'# untouched user preferences' in updated
    assert install.codex_registration(updated, python, config, 'test')[0] == updated


def test_codex_ambiguous_or_invalid_config_refuses(tmp_path):
    with pytest.raises(install.InstallError, match='Multiple'):
        install.codex_registration(b'[mcp_servers.mcb]\n[mcp_servers.minecraft_codex_bridge]\n', tmp_path, tmp_path, 'test')
    with pytest.raises(install.InstallError, match='valid TOML'):
        install.codex_registration(b'invalid =', tmp_path, tmp_path, 'test')
    with pytest.raises(install.InstallError, match='inline/dotted'):
        install.codex_registration(b'mcp_servers = { mcb = { command = "old" } }', tmp_path, tmp_path, 'test')


def test_atomic_configuration_backups_and_conflict(tmp_path):
    path = tmp_path / 'config.json'
    assert install.atomic_write(path, b'original')
    assert install.atomic_write(path, b'new', expected=b'original')
    assert [item.read_bytes() for item in tmp_path.glob('*.bak')] == [b'original']
    assert not install.atomic_write(path, b'new')
    with pytest.raises(install.InstallError, match='changed'):
        install.atomic_write(path, b'overwrite', expected=b'original')
    assert path.read_bytes() == b'new'


def test_mod_replace_backup_repeat_and_unrelated_mods(tmp_path, monkeypatch):
    selected = instance(tmp_path)
    mods = selected / '.minecraft/mods'
    old = jar(mods / 'minecraft-codex-bridge-0.0.1.jar', 'old')
    original = old.read_bytes()
    other = mods / 'TST.jar'
    other.write_bytes(b'unrelated')
    source = jar(tmp_path / 'minecraft-codex-bridge-0.1.0.jar')
    calls = []
    monkeypatch.setattr(install, 'assert_instance_stopped', lambda path: calls.append(path))
    result = install.deploy_mod(source, selected)
    assert result['changed'] and not old.exists()
    assert Path(result['backups'][0]).read_bytes() == original
    assert Path(result['path']).read_bytes() == source.read_bytes()
    assert other.read_bytes() == b'unrelated'
    assert len(calls) == 2
    assert install.deploy_mod(source, selected)['changed'] is False
    assert len(calls) == 2


def test_mod_running_target_and_invalid_jar_refuse_before_writes(tmp_path, monkeypatch):
    selected = instance(tmp_path)
    source = jar(tmp_path / 'minecraft-codex-bridge-0.1.0.jar')
    def running(path):
        raise install.InstallError('running')
    monkeypatch.setattr(install, 'assert_instance_stopped', running)
    with pytest.raises(install.InstallError, match='running'):
        install.deploy_mod(source, selected)
    assert not list((selected / '.minecraft/mods').iterdir())
    source.write_bytes(b'not a mod')
    with pytest.raises(install.InstallError, match='JAR'):
        install.deploy_mod(source, selected)


def test_inspection_questions_and_multiple_instances_do_not_write(tmp_path, monkeypatch):
    monkeypatch.setenv('CODEX_HOME', str(tmp_path / 'codex'))
    monkeypatch.delenv('MCB_CONFIG_FILE', raising=False)
    monkeypatch.setattr(setup, 'discover_prism_roots', lambda prism: [tmp_path / 'a', tmp_path / 'b'])
    options = setup.parser().parse_args(['--scope', 'user', '--config', str(tmp_path / 'config.json'), '--inspect'])
    plan = setup.inspect(options)
    assert plan['status'] == 'needs_input'
    assert next(q for q in plan['questions'] if q['key'] == 'prism-root')['choices'] == [str(tmp_path / 'a'), str(tmp_path / 'b')]
    assert not (tmp_path / 'config.json').exists()
    assert not (tmp_path / 'codex').exists()
    guide = plan['agentGuide']
    assert guide['tool'] == 'mcb_help' and guide['arguments'] == {'topic': 'workspace'}
    assert guide['workspaceCreated'] is False
    assert Path(guide['sourceFile']).is_file()
    assert 'EXPERIENCE.md' in Path(guide['sourceFile']).read_text(encoding='utf-8')


def test_existing_project_registration_discovered_without_global_duplicate(tmp_path):
    home = tmp_path / 'codex'
    home.mkdir()
    project = tmp_path / 'project'
    (project / '.codex').mkdir(parents=True)
    (project / '.codex/config.toml').write_text('[mcp_servers.minecraft_codex_bridge]\ncommand="old"', encoding='utf-8')
    (home / 'config.toml').write_text('[projects.' + json.dumps(str(project)) + ']\ntrust_level="trusted"', encoding='utf-8')
    assert setup.existing_codex_configs(home, None) == [project / '.codex/config.toml']


@pytest.mark.skipif(os.name != 'nt', reason='Windows installer execution')
def test_failed_runtime_install_does_not_write_registration(tmp_path, monkeypatch):
    selected = instance(tmp_path)
    payload = tmp_path / 'payload'
    (payload / 'mcp-server').mkdir(parents=True)
    (payload / 'mcp-server/pyproject.toml').touch()
    source = jar(tmp_path / 'minecraft-codex-bridge-0.1.0.jar')
    config, codex = tmp_path / 'config.json', tmp_path / 'codex.toml'
    options = setup.parser().parse_args(['--scope', 'user', '--config', str(config), '--codex-config', str(codex),
        '--instance', str(selected), '--payload', str(payload), '--jar', str(source)])
    plan = setup.inspect(options)
    assert not plan['questions']
    monkeypatch.setattr(setup, 'assert_instance_stopped', lambda path: None)
    monkeypatch.setattr(setup, 'verify_bundle', lambda path: None)
    def broken(*args, **kwargs):
        raise install.InstallError('pip failed')
    monkeypatch.setattr(setup, '_run', broken)
    with pytest.raises(install.InstallError, match='pip failed'):
        setup.execute(plan, options)
    assert not config.exists() and not codex.exists()
    assert not list((selected / '.minecraft/mods').iterdir())


def test_bridgenh_upgrade_replaces_legacy_jar_and_keeps_backup(tmp_path, monkeypatch):
    selected = instance(tmp_path)
    old = jar(selected / '.minecraft/mods/minecraft-codex-bridge-0.0.1.jar', 'old')
    old_bytes = old.read_bytes()
    source = jar(tmp_path / 'BridgeNH-0.1.0.jar')
    monkeypatch.setattr(install, 'assert_instance_stopped', lambda path: None)
    result = install.deploy_mod(source, selected)
    assert result['changed'] is True and not old.exists()
    assert Path(result['backups'][0]).read_bytes() == old_bytes
    assert Path(result['path']).name == 'BridgeNH-0.1.0.jar'
    assert install.deploy_mod(source, selected)['changed'] is False


def test_source_inspection_uses_root_build_without_client_api_jars(tmp_path, monkeypatch):
    selected = instance(tmp_path)
    payload = tmp_path / 'source'
    (payload / 'mcp-server').mkdir(parents=True)
    (payload / 'mcp-server/pyproject.toml').touch()
    (payload / 'build.gradle').touch()
    (payload / 'build/libs').mkdir(parents=True)
    source = jar(payload / 'build/libs/BridgeNH-0.1.0.jar')
    monkeypatch.delenv('MCB_CONFIG_FILE', raising=False)
    options = setup.parser().parse_args(['--scope', 'source', '--inspect', '--manual-launch',
        '--payload', str(payload), '--instance', str(selected), '--codex-config', str(tmp_path / 'codex.toml')])
    plan = setup.inspect(options)
    assert plan['status'] == 'ready' and not plan['questions']
    assert plan['mod']['source'] == str(source)
    assert 'build' not in plan['_config']
    assert not (payload / '.mcb.local.json').exists()
    setup.verify_bundle(payload)


def test_stale_instance_becomes_missing_input(tmp_path):
    options = setup.parser().parse_args(['--scope', 'user', '--inspect', '--instance', str(tmp_path / 'gone'),
        '--config', str(tmp_path / 'config.json'), '--codex-config', str(tmp_path / 'codex.toml')])
    plan = setup.inspect(options)
    assert plan['status'] == 'needs_input'
    assert any(question['key'] == 'instance' for question in plan['questions'])


@pytest.mark.skipif(os.name != 'nt', reason='Windows bootstrap')
def test_powershell_bootstrap_preserves_argument_boundaries(tmp_path):
    selected = instance(tmp_path)
    config, codex = tmp_path / '中文 config.json', tmp_path / 'codex.toml'
    script = Path(__file__).resolve().parents[2] / 'install.ps1'
    result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(script),
        '-Inspect', '-NonInteractive', '-ConfigureOnly', '-Scope', 'user', '-Python', sys.executable,
        '-Config', str(config), '-CodexConfig', str(codex), '-PrismRoot', str(selected.parent.parent),
        '-InstanceName', selected.name, '-ManualLaunch'],
        capture_output=True, timeout=30)
    assert result.returncode == 0, result.stderr.decode('utf-8', errors='replace')
    plan = json.loads(result.stdout.decode('utf-8-sig'))
    assert plan['status'] == 'ready' and plan['instancePath'] == str(selected)
    assert plan['prismRoot'] == str(selected.parent.parent) and plan['instanceName'] == selected.name
    assert plan['configFile'] == str(config) and plan['codexConfig'] == str(codex)
    assert not config.exists() and not codex.exists()
