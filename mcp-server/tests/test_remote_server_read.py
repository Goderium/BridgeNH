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

@pytest.mark.parametrize('kind', ['self','blocks','machine'])
def test_remote_data_requires_actual_connection_and_dimension(kind):
    schema=TOOL_BY_NAME['mcb_server_observe'].schema
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({'expectedSessionId':'session','kind':kind},schema)

def test_machine_coordinates_required():
    schema=TOOL_BY_NAME['mcb_server_observe'].schema
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({'expectedSessionId':'session','kind':'machine','dimension':0,'expectedConnectionId':'connection'},schema)
