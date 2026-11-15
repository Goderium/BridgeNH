import jsonschema
import pytest
from minecraft_codex_bridge.tools import TOOL_BY_NAME, OBSERVATION_NAMES
from minecraft_codex_bridge.bridge import READ_OPERATIONS

def test_remote_read_registered_as_observation():
    spec = TOOL_BY_NAME['mcb_server_observe']
    assert spec.operation == 'server.observe'
    assert spec.read_only and not spec.takeover
    assert spec.name in OBSERVATION_NAMES
    assert spec.operation in READ_OPERATIONS
    jsonschema.Draft202012Validator.check_schema(spec.schema)

def test_remote_read_schema_and_page_bound():
    schema = TOOL_BY_NAME['mcb_server_observe'].schema
    jsonschema.validate({'expectedSessionId':'session','kind':'status'}, schema)
    valid = {'expectedSessionId':'session','kind':'blocks', 'dimension':180, 'expectedConnectionId':'connection',
             'positions':[{'x':i,'y':4,'z':0} for i in range(64)]}
    jsonschema.validate(valid, schema)
    valid['positions'].append({'x':64,'y':4,'z':0})
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate(valid, schema)
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate({'expectedSessionId':'session','kind':'command'}, schema)

def test_schematic_remote_binding_and_negative_y_offset():
    schema = TOOL_BY_NAME['mcb_schematic_goal'].schema
    args = {'expectedSessionId':'session','expectedWorld':'MpServer','expectedConnectionId':'connection','dimension':180,
            'anchor':{'x':0,'y':4,'z':0}, 'cells':[{'x':0,'y':-1,'z':0,'kind':'air'}]}
    jsonschema.validate(args, schema)
    assert 'local client' in TOOL_BY_NAME['mcb_schematic_goal'].description

@pytest.mark.parametrize('kind', ['self','blocks','machine','logs','diagnostics','mods'])
def test_remote_data_requires_actual_connection_and_dimension(kind):
    schema=TOOL_BY_NAME['mcb_server_observe'].schema
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({'expectedSessionId':'session','kind':kind},schema)

def test_machine_coordinates_required():
    schema=TOOL_BY_NAME['mcb_server_observe'].schema
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({'expectedSessionId':'session','kind':'machine','dimension':0,'expectedConnectionId':'connection'},schema)


@pytest.mark.parametrize('extra', [
    {'kind':'logs'}, {'kind':'logs','log':'fml-server-latest','level':'ANY','query':'exception','limit':128},
    {'kind':'diagnostics'}, {'kind':'mods','offset':64,'limit':64},
])
def test_server_information_has_explicit_bounded_schemas(extra):
    jsonschema.validate(dict(expectedSessionId='session',expectedConnectionId='connection',dimension=0,**extra),
        TOOL_BY_NAME['mcb_server_observe'].schema)


@pytest.mark.parametrize('extra', [
    {'kind':'logs','log':'../server.properties'}, {'kind':'logs','path':'logs/latest.log'},
    {'kind':'logs','limit':129}, {'kind':'logs','level':'DEBUG'}, {'kind':'logs','query':'x'*257},
    {'kind':'mods','limit':65}, {'kind':'mods','offset':-1}, {'kind':'mods','offset':10001},
    {'kind':'logs','offset':1}, {'kind':'diagnostics','query':'x'}, {'kind':'self','log':'latest'},
    {'kind':'diagnostics','limit':1}, {'kind':'mods','includeEvidence':True},
])
def test_server_information_refuses_paths_cross_kind_options_and_unbounded_reads(extra):
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(dict(expectedSessionId='session',expectedConnectionId='connection',dimension=0,**extra),
            TOOL_BY_NAME['mcb_server_observe'].schema)


from test_operation_policy import current_remote


@pytest.mark.parametrize('extra', [{'kind':'logs','level':'ANY','limit':2}, {'kind':'diagnostics'}, {'kind':'mods','offset':16,'limit':16}])
async def test_server_information_uses_one_bound_read_without_takeover(current_remote, extra):
    adapter, state = current_remote
    args = dict(expectedSessionId='session',expectedConnectionId='connection',dimension=180,**extra)
    state['machine'] = dict(source='remote_server_tick',sessionId='session',connectionId='connection',dimension=180,
        lines=['fixture observation'],readOnly=True)
    result = await adapter.call_tool('mcb_server_observe', args)
    assert not result.isError and result.structuredContent['data'] == state['machine']
    assert [row['operation'] for row in state['calls']] == ['bridge.health','server.observe']
    assert state['calls'][-1]['args'] == args
