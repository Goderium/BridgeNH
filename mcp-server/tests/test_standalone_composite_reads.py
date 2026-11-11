import copy
import json
import httpx
import pytest
from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import Adapter,invoke_current_world_read
from test_inspect_many_player_inventory import observation, add_composite, combined_args, PLAYER_ID
from test_workflows import MANUAL, PROTECTED

POINT={"x":-44,"y":3,"z":125}
STRUCTURE={**POINT,"dimension":0,"captureId":"capture","candidatePositions":[{**POINT,"group":0}],"limit":1}

@pytest.fixture
async def client():
    state={"calls":[],"fault":None,"protected":False}
    def snapshot():
        _,e=observation();s=e["snapshot"];s["world"]["singleplayer"]=True
        s["background"]=copy.deepcopy(PROTECTED if state["protected"] else MANUAL)
        s["serverControl"]={"playerId":PLAYER_ID}
        return s
    async def respond(request):
        body=json.loads(request.content);state["calls"].append(body);op=body["operation"];args=body.get("args",{})
        if op=="bridge.health":
            return httpx.Response(200,json={"requestId":body["requestId"],"ok":True,"data":{"protocolVersion":1,"sessionId":"session","profile":"developer","capabilities":["client.snapshot","world.structure","world.inspect_many"],"readiness":{"localIntegratedServer":True}}})
        if op=="client.snapshot":
            s=snapshot()
            if state["fault"]=="remote":s["world"]["singleplayer"]=False
            if state["fault"]=="missing_world":s["world"].pop("worldFolder")
            return httpx.Response(200,json={"requestId":body["requestId"],"ok":True,"data":s})
        assert op in ["world.structure","world.inspect_many"]
        current=snapshot()
        if op=="world.inspect_many":
            d,_=observation();e=add_composite(d,current,d["playerInventory"],args)
        else:
            d={**POINT,"source":"integrated_server","dimension":0,"sessionId":"session"}
            if "candidatePositions" in args:
                d["candidateObservation"]={"version":1,"source":"client_public_structure_element_getter","readOnly":True,
                    "ownerBound":True,"captureMembershipVerified":True,"captureId":"capture","sessionId":"session","dimension":0,**POINT,
                    "complete":False,"processedCount":1,"nextCandidateOffset":1,"positions":[{"status":"known_suggestions"}]}
            if args.get("includeSurvivalInventory"):
                d["serverPlayerInventory"]={"source":"integrated_server","inventorySource":"actual_player_inventory","complete":True,
                    "slots":[{"index":i,"item":None} for i in range(41)],"totalSlots":41,"truncated":False,"cursorItem":None,
                    "playerId":PLAYER_ID,"sessionId":"session","dimension":0}
            e={"version":1,"operation":op,"phase":"after","snapshot":current}
        fault=state["fault"]
        if fault=="wrong_session":e["snapshot"]["sessionId"]="changed"
        if fault=="wrong_dimension":e["snapshot"]["world"]["dimension"]=1
        if fault=="bool_dimension":e["snapshot"]["world"]["dimension"]=False
        if fault=="wrong_phase":e["phase"]="before"
        if fault=="bool_version":e["version"]=True
        if fault=="missing_candidates":d.pop("candidateObservation",None)
        if fault=="wrong_capture":d["candidateObservation"]["captureId"]="other"
        if fault=="wrong_actor":d["serverPlayerInventory"]["playerId"]="other"
        if fault=="partial_inventory":d["serverPlayerInventory"]["slots"].pop()
        if fault=="bad_combined_inventory":d["playerInventory"]["complete"]=False
        result={"requestId":body["requestId"],"ok":True,"data":d}
        if fault!="missing_context":result["context"]=e
        return httpx.Response(200,json=result)
    c=BridgeClient(Configuration({"MCB_URL":"http://127.0.0.1:25671","MCB_TOKEN":"standalone-read-test"}),transport=httpx.MockTransport(respond))
    yield c,state
    await c.aclose()

async def test_standalone_candidates_use_real_after_context_without_taking_over(client):
    c,s=client;r=await invoke_current_world_read(c,"world.structure",STRUCTURE)
    assert r["candidateObservation"]["complete"] is False
    assert [b["operation"] for b in s["calls"]]==["bridge.health","client.snapshot","world.structure"]
    assert s["calls"][-1]["includeContext"]=={"version":1,"expectedSessionId":"session","expectedProfile":"developer","expectedWorld":"new-world","requireBackground":False}

async def test_standalone_finite_inventory_reuses_complete_server_ledger_and_actor_binding(client):
    c,s=client;args={**POINT,"dimension":0,"captureId":"capture","includeSurvivalInventory":True,"limit":1}
    r=await Adapter(c)._call_tool("mcb_world_structure",args)
    assert r.structuredContent["ok"] and len(r.structuredContent["data"]["serverPlayerInventory"]["slots"])==41
    assert len(s["calls"])==3 and not s["calls"][-1]["includeContext"]["requireBackground"]

@pytest.mark.parametrize("protected",[False,True])
async def test_standalone_combined_inventory_preserves_existing_background_choice(client,protected):
    c,s=client;s["protected"]=protected;r=await Adapter(c)._call_tool("mcb_world_inspect_many",combined_args())
    assert r.structuredContent["ok"] is protected
    assert [b["operation"] for b in s["calls"]]==(["bridge.health","client.snapshot","world.inspect_many"] if protected else ["bridge.health","client.snapshot"])
    if protected:assert s["calls"][-1]["includeContext"]["requireBackground"] is True

@pytest.mark.parametrize("fault",["remote","missing_world","missing_context","wrong_session","wrong_dimension","bool_dimension","bool_version","wrong_phase","missing_candidates","wrong_capture"])
async def test_candidate_context_failures_never_return_server_only_success_or_replay(client,fault):
    c,s=client;s["fault"]=fault;r=await Adapter(c)._call_tool("mcb_world_structure",STRUCTURE)
    assert not r.structuredContent["ok"]
    assert sum(b["operation"]=="world.structure" for b in s["calls"])==(0 if fault in ["remote","missing_world"] else 1)
    assert not any(b["operation"] in ["client.background","player.place_block"] for b in s["calls"])

@pytest.mark.parametrize("fault",["wrong_actor","partial_inventory"])
async def test_finite_inventory_is_not_accepted_for_another_actor_or_partial_slots(client,fault):
    c,s=client;s["fault"]=fault;r=await Adapter(c)._call_tool("mcb_world_structure",{**POINT,"dimension":0,"captureId":"capture","includeSurvivalInventory":True})
    assert not r.structuredContent["ok"] and len(s["calls"])==3

async def test_combined_inventory_still_validates_full_native_receipt(client):
    c,s=client;s.update(protected=True,fault="bad_combined_inventory")
    r=await Adapter(c)._call_tool("mcb_world_inspect_many",combined_args())
    assert not r.structuredContent["ok"] and r.structuredContent["error"]["code"]=="MALFORMED_RESPONSE" and len(s["calls"])==3
