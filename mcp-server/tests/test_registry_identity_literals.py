"""Real Forge registry namespaces retain exact case and reject command separators."""

import pytest

from minecraft_codex_bridge.recipe_tasks import _identity, _same, _Unavailable
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.validation import tool_validator


REAL_IDENTITIES = ('IC2:itemFluidCell', 'Railcraft:fluid.creosote.cell', 'minecraft:stone')
INVALID_IDENTITIES = (
    'IC2:itemFluidCell 1 0', 'IC2:itemFluidCell\n', 'IC2:itemFluidCell\r',
    'IC2:itemFluidCell\t', 'IC2:itemFluidCell\x00', 'IC2:itemFluidCell\x1f',
    'IC2:itemFluidCell\x7f', 'IC2:itemFluidCell;give', 'IC2:itemFluidCell|give',
    'IC2:itemFluidCell{tag:1}', 'IC2:itemFluidCell:extra', ':itemFluidCell', 'IC2:',
)


def declaration(tool, registry_name):
    item = {'registryName': registry_name, 'metadata': 0}
    context = {'expectedSessionId': 'session', 'expectedWorld': 'test-world'}
    point = {'x': 10, 'y': 20, 'z': 10}
    if tool == 'mcb_supply_items':
        return {**context, 'items': [{**item, 'count': 1}]}
    if tool == 'mcb_recipe_once':
        return {**context, 'machine': point, 'input': {**point, 'x': 11},
            'output': {**point, 'x': 12}, 'outputItem': item, 'material': item,
            'selection': 'first_supported'}
    return {**context, 'dimension': 0, 'controller': point, 'controllerItem': item,
        'blueprintHotbarSlot': 0, 'supportItem': item, 'supportHotbarSlot': 3,
        'requestedPorts': [{'role': 'item_output', 'hotbarSlot': 2, 'ordinaryOutputItem': item}],
        'generateMaterials': True, 'declaredSupplies': [
            {'hotbarSlot': slot, 'item': item, 'count': 1} for slot in range(3)]}


@pytest.mark.parametrize('tool', ['mcb_supply_items', 'mcb_recipe_once', 'mcb_blueprint_build'])
@pytest.mark.parametrize('registry_name', REAL_IDENTITIES)
def test_real_namespaces_validate_with_exact_case(tool, registry_name):
    arguments = declaration(tool, registry_name)
    tool_validator(tool).validate(arguments)
    assert registry_name in str(arguments)


@pytest.mark.parametrize('registry_name', REAL_IDENTITIES)
def test_backend_literal_inputs_accept_real_namespace_case(registry_name):
    arguments = declaration('mcb_recipe_once', registry_name)
    arguments.update(recipeLookup='backend_match', backendInputs=[{
        'source': 'registry', 'registryName': registry_name, 'metadata': 0, 'count': 1}])
    tool_validator('mcb_recipe_once').validate(arguments)


@pytest.mark.parametrize('registry_name', REAL_IDENTITIES)
def test_observed_identity_preserves_case_and_exact_matching(registry_name):
    observed = {'registryName': registry_name, 'metadata': 0, 'hasTag': False}
    identity = _identity(observed)
    assert identity == {'registryName': registry_name, 'metadata': 0}
    assert _same(observed, identity)
    if registry_name != registry_name.lower():
        assert not _same(observed, {**identity, 'registryName': registry_name.lower()})


@pytest.mark.parametrize('registry_name', INVALID_IDENTITIES)
def test_observed_identity_rejects_command_injection(registry_name):
    with pytest.raises(_Unavailable):
        _identity({'registryName': registry_name, 'metadata': 0, 'hasTag': False})


@pytest.mark.parametrize('tool', ['mcb_supply_items', 'mcb_recipe_once', 'mcb_blueprint_build'])
@pytest.mark.parametrize('registry_name', INVALID_IDENTITIES)
async def test_invalid_literal_is_refused_before_any_client_access(tool, registry_name):
    result = await Adapter(object()).call_tool(tool, declaration(tool, registry_name))
    assert result.isError is True
    assert result.structuredContent['error']['code'] == 'INVALID_ARGUMENTS'


@pytest.mark.parametrize('tool', ['mcb_supply_items', 'mcb_recipe_once', 'mcb_blueprint_build'])
def test_literal_name_length_limit_is_preserved(tool):
    arguments = declaration(tool, 'IC2:' + 'a' * 125)
    assert not tool_validator(tool).is_valid(arguments)
