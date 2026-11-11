from copy import deepcopy
import pytest
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate
from test_shared_interaction_approach import Client, run

TARGET = {'x':0,'y':64,'z':0,'allowedSides':[2], 'wrenchFacing':True,
          'targetIdentity':{'registryName':'gregtech:gt.blockmachines','metaTileId':70}}

class WrenchClient(Client):
    def __init__(self, *, capability=1, missing_flag=False, edge=False):
        super().__init__(in_range=False)
        self.capability, self.missing_flag, self.edge = capability, missing_flag, edge
    async def invoke(self, operation, payload):
        value = await super().invoke(operation,payload)
        if operation == 'bridge.health': value['approachWrenchFacingVersion'] = self.capability
        if operation == 'player.plan_approach':
            assert payload['wrenchFacing'] is True and payload['targets'][0] == TARGET
            for view in value['selected']['targetViews']:
                if not self.missing_flag: view['wrenchFacing'] = True
                if self.edge: view['hit']['x'] = .1
        return value

async def test_wrench_centre_survives_route_and_fresh_actual_recheck():
    client=WrenchClient()
    result=await run(client,targets=[TARGET])
    assert result['status']=='completed' and len(client.moves)==1 and not client.uses
    assert [p['currentOnly'] for op,p in client.calls if op=='player.plan_approach']==[False,True]

@pytest.mark.parametrize('capability',[None,True,False,0,1.0,2])
async def test_old_or_untyped_capability_refuses_before_planning_or_inputs(capability):
    client=WrenchClient(capability=capability)
    with pytest.raises(BridgeError) as error:await run(client,targets=[TARGET])
    assert error.value.code=='WRENCH_APPROACH_UNAVAILABLE'
    assert not client.moves and not client.uses and all(op!='player.plan_approach' for op,_ in client.calls)

@pytest.mark.parametrize('fault',['missing_flag','edge'])
async def test_ordinary_edge_evidence_cannot_qualify_as_wrench_endpoint(fault):
    client=WrenchClient(**{fault:True})
    with pytest.raises(BridgeError) as error:await run(client,targets=[TARGET])
    assert error.value.code=='APPROACH_RECEIPT_INVALID'
    assert not client.moves and not client.uses

async def test_combined_unbound_use_refuses_before_movement():
    client=WrenchClient()
    with pytest.raises(BridgeError) as error:await run(client,targets=[TARGET],interact=True)
    assert error.value.code=='WRENCH_APPROACH_MOVEMENT_ONLY'
    assert not client.moves and not client.uses

@pytest.mark.parametrize('change',[{'wrenchFacing':False},{'wrenchFacing':1},{'allowedSides':[0,1]},
    {'targetIdentity':{'registryName':'ae:other'}},{'targetIdentity':{'registryName':'gregtech:gt.blockmachines','part':{'registryName':'ae:part','metadata':1,'side':1}}}])
def test_wrench_endpoint_schema_refuses_ambiguous_targets(change):
    target={**deepcopy(TARGET),**change}
    with pytest.raises(BridgeError):_validate('mcb_approach',{'expectedSessionId':'s','expectedWorld':'w','dimension':0,'targets':[target]})
