import json
from pathlib import Path

import pytest

from minecraft_codex_bridge import local_config, setup
from minecraft_codex_bridge.client_connections import client_inventory, named_client
from minecraft_codex_bridge.prism_instances import PrismInstanceError, list_prism_instances, resolve_prism_instance


def create_instance(root, identifier='test-folder', name='测试实例', directory='instances'):
    instance = root / directory / identifier
    (instance / '.minecraft/mods').mkdir(parents=True)
    (instance / 'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=' + name + '\n', encoding='utf-8')
    (instance / 'mmc-pack.json').write_text(json.dumps({'formatVersion': 1, 'components': [
        {'uid': 'net.minecraft', 'version': '1.7.10'}]}), encoding='utf-8')
    return instance.resolve()


def test_names_are_resolved_within_explicit_root_and_inventory_does_not_touch_unrelated_config(tmp_path):
    first = create_instance(tmp_path / 'Prism A')
    second = create_instance(tmp_path / 'Prism B')
    selected = named_client(str(tmp_path / 'Prism B'), '测试实例')
    assert selected['instancePath'] == str(second) and selected['instanceName'] == 'test-folder'
    assert resolve_prism_instance(tmp_path / 'Prism A', 'test-folder')['instancePath'] == str(first)
    inventory = client_inventory({'MCB_CONFIG_FILE': str(tmp_path / 'missing.json'), 'MCB_INSTANCE_PATH': str(second)}, str(tmp_path / 'Prism A'))
    assert len(inventory['clients']) == 1 and inventory['clients'][0]['instancePath'] == str(first)
    assert inventory['clients'][0]['displayName'] == '测试实例'
    assert inventory['clients'][0]['liveStatus'] == 'not_probed'


@pytest.mark.parametrize('absolute', [False, True])
def test_custom_prism_instance_directory_is_used(tmp_path, absolute):
    root = tmp_path / 'Prism'
    directory = tmp_path / 'Separate instances' if absolute else Path('Custom instances')
    selected = create_instance(root, directory=directory)
    root.mkdir(exist_ok=True)
    (root / 'prismlauncher.cfg').write_text('[General]\nInstanceDir=' + directory.as_posix() + '\n', encoding='utf-8')
    assert resolve_prism_instance(root, '测试实例')['instancePath'] == str(selected)


def test_duplicate_display_names_and_folder_name_collisions_are_refused(tmp_path):
    create_instance(tmp_path, 'first', 'Same name')
    create_instance(tmp_path, 'second', 'Same name')
    with pytest.raises(PrismInstanceError) as caught:
        resolve_prism_instance(tmp_path, 'Same name')
    assert caught.value.code == 'PRISM_INSTANCE_AMBIGUOUS'
    assert resolve_prism_instance(tmp_path, 'first')['instanceName'] == 'first'
    create_instance(tmp_path, 'third', 'first')
    with pytest.raises(PrismInstanceError, match='Several instances'):
        resolve_prism_instance(tmp_path, 'first')


@pytest.mark.parametrize('name', ['missing', '../outside', '/outside', 'instances/test-folder'])
def test_missing_or_path_like_names_never_fall_back(tmp_path, name):
    create_instance(tmp_path)
    with pytest.raises(PrismInstanceError) as caught:
        resolve_prism_instance(tmp_path, name)
    assert caught.value.code == 'PRISM_INSTANCE_NOT_FOUND'


def test_bad_prism_configuration_does_not_fall_back_to_default_directory(tmp_path):
    create_instance(tmp_path)
    (tmp_path / 'prismlauncher.cfg').write_text('[General]\nInstanceDir=missing\n', encoding='utf-8')
    with pytest.raises(PrismInstanceError) as caught:
        list_prism_instances(tmp_path)
    assert caught.value.code == 'PRISM_DIRECTORY_INVALID'


def test_separate_profile_fields_resolve_relative_root_and_keep_name_as_a_name(tmp_path):
    root = tmp_path / 'Prism 中文'
    selected = create_instance(root, name='Example Client 中文')
    (root / 'prismlauncher.exe').touch()
    config = tmp_path / 'config.json'
    config.write_text(json.dumps({'version': 1, 'activeProfile': 'test', 'profiles': {
        'test': {'prismRoot': 'Prism 中文', 'instanceName': 'Example Client 中文'}}}), encoding='utf-8')
    _, _, resolved = local_config.selected_profile(config)
    assert resolved['instancePath'] == str(selected) and resolved['instanceName'] == 'test-folder'
    env = local_config.configured_environment({'MCB_CONFIG_FILE': str(config)})
    assert env['MCB_INSTANCE_PATH'] == str(selected)
    assert env['MCB_PRISM_EXE'] == str(root / 'prismlauncher.exe')
    inventory = client_inventory({'MCB_CONFIG_FILE': str(config), 'MCB_INSTANCE_ROOT': str(tmp_path / 'no-other-instances')})
    assert inventory['clients'][0]['prismRoot'] == str(root)


@pytest.mark.parametrize('profile', [{'prismRoot': '/prism'}, {'instanceName': 'test'},
    {'prismRoot': '/prism', 'instanceName': 'test', 'instancePath': '/other'}])
def test_partial_or_conflicting_profile_selectors_are_rejected(tmp_path, profile):
    path = tmp_path / 'config.json'
    path.write_text(json.dumps({'version': 1, 'profiles': {'test': profile}}), encoding='utf-8')
    with pytest.raises(local_config.LocalConfigError, match='together'):
        local_config.read_config(path)


def test_installer_asks_directory_then_name_and_inspection_never_writes(tmp_path, monkeypatch):
    root = tmp_path / 'Prism'
    selected = create_instance(root)
    monkeypatch.setattr(setup, 'discover_prism_roots', lambda _prism: [root])
    config, codex = tmp_path / 'config.json', tmp_path / 'codex.toml'
    options = setup.parser().parse_args(['--inspect', '--configure-only', '--manual-launch', '--scope', 'user',
        '--config', str(config), '--codex-config', str(codex)])
    first = setup.inspect(options)
    assert [question['key'] for question in first['questions']] == ['prism-root']
    assert first['questions'][0]['choices'] == [str(root)]
    options.prism_root = str(root)
    second = setup.inspect(options)
    assert [question['key'] for question in second['questions']] == ['instance-name']
    assert second['questions'][0]['choices'] == ['test-folder']
    options.instance_name = '测试实例'
    plan = setup.inspect(options)
    assert plan['status'] == 'ready' and plan['instancePath'] == str(selected)
    assert plan['_config']['profiles']['default'] == {'prismRoot': str(root), 'instanceName': 'test-folder'}
    assert not config.exists() and not codex.exists() and not list((selected / '.minecraft/mods').iterdir())


def test_changing_prism_directory_does_not_inherit_previous_instance_name(tmp_path):
    old_root, new_root = tmp_path / 'Old Prism', tmp_path / 'New Prism'
    create_instance(old_root)
    create_instance(new_root)
    options = setup.parser().parse_args(['--prism-root', str(new_root), '--manual-launch'])
    target = setup.installation_target(options, {'prismRoot': str(old_root), 'instanceName': 'test-folder'}, tmp_path / 'config.json')
    assert target['instancePath'] is None and [q['key'] for q in target['questions']] == ['instance-name']
