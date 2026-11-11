import json
from pathlib import Path

import pytest

from minecraft_codex_bridge import local_config as config
from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.backend_supervisor import Supervisor


def write_config(tmp_path, **updates):
    path = tmp_path / config.LOCAL_NAME
    data = {'version': 1, 'activeProfile': 'test', 'profiles': {
        'test': {'instancePath': 'instances/中文 test', 'prismExe': 'Prism/prismlauncher.exe'},
        'other': {'instancePath': 'instances/other'}}}
    data.update(updates)
    path.write_text(json.dumps(data), encoding='utf-8')
    return path


def test_source_config_profile_and_relative_paths(tmp_path, monkeypatch):
    path = write_config(tmp_path)
    monkeypatch.setattr(config, 'source_root', lambda: tmp_path)
    env = config.configured_environment({})
    assert env['MCB_CONFIG_FILE'] == str(path)
    assert env['MCB_INSTANCE_PATH'] == str(tmp_path / 'instances/中文 test')
    assert env['MCB_PRISM_EXE'] == str(tmp_path / 'Prism/prismlauncher.exe')
    chosen = config.configured_environment({'MCB_CONFIG_FILE': str(path), 'MCB_PROFILE': 'other'})
    assert chosen['MCB_INSTANCE_PATH'] == str(tmp_path / 'instances/other')
    assert 'MCB_PRISM_EXE' not in chosen


def test_explicit_transport_does_not_inherit_unrelated_machine_config(tmp_path, monkeypatch):
    write_config(tmp_path)
    monkeypatch.setattr(config, 'source_root', lambda: tmp_path)
    for env in ({'MCB_RUNTIME_FILE': 'runtime.json'}, {'MCB_URL': 'http://127.0.0.1:2', 'MCB_TOKEN': 'test'},
                {'MCB_INSTANCE_PATH': str(tmp_path / 'different')}, {'MCB_INSTANCE_ROOT': str(tmp_path)}):
        assert config.configured_environment(env) == env
        assert Configuration(env).environ == env
    assert Configuration({}).environ == {}


def test_invalid_explicit_config_never_falls_back(tmp_path):
    with pytest.raises(BridgeError) as caught:
        Configuration({'MCB_CONFIG_FILE': str(tmp_path / 'missing')})
    assert caught.value.code == 'CONFIGURATION_ERROR'
    path = write_config(tmp_path)
    with pytest.raises(config.LocalConfigError, match='Unknown'):
        config.configured_environment({'MCB_CONFIG_FILE': str(path), 'MCB_PROFILE': 'missing'})
    path.write_text('{"version":1,"version":1}', encoding='utf-8')
    with pytest.raises(config.LocalConfigError, match='Duplicate'):
        config.read_config(path)


def test_supervisor_never_binds_from_profile_defaults(tmp_path):
    path = write_config(tmp_path)
    supervisor = Supervisor(environment={'MCB_CONFIG_FILE': str(path)})
    write_config(tmp_path, activeProfile='other')
    worker = Configuration(supervisor.environment)
    assert supervisor.connection_status()['bound'] is False
    with pytest.raises(BridgeError, match='This conversation has no client'):
        worker.runtime_path()


def test_inventory_config_does_not_require_a_default(tmp_path):
    path = write_config(tmp_path, activeProfile=None)
    assert config.read_config(path)['activeProfile'] is None
    with pytest.raises(config.LocalConfigError, match='explicit MCB profile'):
        config.selected_profile(path)
    assert config.selected_profile(path, 'other')[1] == 'other'


def test_profile_credentials_refused(tmp_path):
    path = write_config(tmp_path, profiles={'test': {'instancePath': 'test', 'token': 'not-stored'}})
    with pytest.raises(config.LocalConfigError, match='credentials'):
        config.read_config(path)
