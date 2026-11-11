import copy
from types import SimpleNamespace
import pytest
from minecraft_codex_bridge.bridge import BridgeError,_REQUEST_CONTEXT,request_context
from minecraft_codex_bridge.navigation_gate import closed_container_before_navigation
from minecraft_codex_bridge.server import invoke_control
import minecraft_codex_bridge.navigation_gate as gate

ARGS={"expectedSessionId":"session","expectedWorld":"world","dimension":0,"x":1.5,"y":4,"z":2.5,"strategy":"walking","timeout_ms":6000}
HEALTH={"protocolVersion":1,"sessionId":"session","profile":"developer","guiNativeWaitVersion":1,"readiness":{"localIntegratedServer":True},
        "background":{"enabled":True,"helperInstalled":True,"pauseOnLostFocus":False},"capabilities":["player.navigate","client.background","client.snapshot"]}

def snapshot(closed=False):
    return {"sessionId":"session","profile":"developer","worldLoaded":True,"world":{"worldFolder":"world","dimension":0},
        "background":copy.deepcopy(HEALTH["background"]),"player":{"health":20},"screenOpen":False,
        "serverControl":{"available":True,"playerId":"actual-player","clientContainer":{"windowId":0,"isInventoryContainer":True},
                         "openContainer":{"windowId":0 if closed else 51,"isInventoryContainer":closed}}}

class Client:
    class Configuration:
        def read(self):return "unchanged-runtime"
    configuration=Configuration()
    def __init__(self,values,*,nav_error=False):self.values=values;self.calls=[];self.nav_error=nav_error
    async def invoke(self,operation,args):assert operation=="bridge.health";return copy.deepcopy(HEALTH)
    async def call(self,operation,args,**kw):
        self.calls.append((operation,copy.deepcopy(_REQUEST_CONTEXT.get())))
        if operation=="client.snapshot":return self.values.pop(0)
        assert operation=="player.navigate"
        if self.nav_error:raise BridgeError("unsupported","native movement failed",outcome_unknown=False)
        return {"arrivalConfirmed":True}

@pytest.fixture(autouse=True)
def no_real_wait(monkeypatch):
    async def sleep(seconds):assert seconds==.1
    monkeypatch.setattr(gate.anyio,"sleep",sleep)

async def test_client_close_lag_waits_without_repeating_close_or_navigation():
    c=Client([snapshot(),snapshot(),snapshot(True)])
    context={"version":1,"expectedSessionId":"session","expectedProfile":"developer","expectedWorld":"world","requireBackground":True}
    with request_context(context):r=await invoke_control(c,"player.navigate",ARGS)
    assert r["arrivalConfirmed"] and r["containerCloseSynchronization"]["probes"]==3
    assert [op for op,_ in c.calls]==["client.snapshot"]*3+["player.navigate"]
    assert all(ctx is None for op,ctx in c.calls if op=="client.snapshot") and c.calls[-1][1]==context

async def test_already_closed_does_not_add_polling_or_change_navigation():
    c=Client([snapshot(True)]);r=await invoke_control(c,"player.navigate",ARGS)
    assert r=={"arrivalConfirmed":True} and [op for op,_ in c.calls]==["client.snapshot","player.navigate"]

@pytest.mark.parametrize("fault",["world","actor","gui","other_container","protection","dimension"])
async def test_changed_context_stops_before_any_navigation(fault):
    second=snapshot(True)
    if fault=="world":second["world"]["worldFolder"]="other"
    if fault=="actor":second["serverControl"]["playerId"]="other"
    if fault=="gui":second["screenOpen"]=True
    if fault=="other_container":second["serverControl"]["openContainer"]={"windowId":52,"isInventoryContainer":False}
    if fault=="protection":second["background"]["enabled"]=False
    if fault=="dimension":second["world"]["dimension"]=1
    c=Client([snapshot(),second])
    with pytest.raises(BridgeError):await invoke_control(c,"player.navigate",ARGS)
    assert [op for op,_ in c.calls]==["client.snapshot","client.snapshot"]

async def test_pending_close_timeout_never_dispatches_or_repeats_navigation(monkeypatch):
    times=iter([0,2]);monkeypatch.setattr(gate,"time",SimpleNamespace(monotonic=lambda:next(times)))
    c=Client([snapshot()])
    with pytest.raises(BridgeError) as e:await invoke_control(c,"player.navigate",ARGS)
    assert e.value.details["original_action_executed"] is False and c.calls==[("client.snapshot",None)]

async def test_native_refusal_after_close_confirmation_is_never_replayed():
    c=Client([snapshot(),snapshot(True)],nav_error=True)
    with pytest.raises(BridgeError):await invoke_control(c,"player.navigate",ARGS)
    assert sum(op=="player.navigate" for op,_ in c.calls)==1

async def test_legacy_capability_keeps_original_navigation_path():
    assert await closed_container_before_navigation(Client([]),ARGS,{}) is None
