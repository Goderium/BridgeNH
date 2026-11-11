"""General frame transforms retain exact native observations and independent effects."""
import copy
import itertools
import json

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.server import Adapter, _validate
from minecraft_codex_bridge.structure_transform import AXES, VECTORS, mapping, resolve_geometry, run_structure_transform, vector
from test_workflows import bridge, PROTECTED

IDENTITY = [[1,0,0],[0,1,0],[0,0,1]]
UP = [[0,0,1],[1,0,0],[0,-1,0]]
ALIGNMENT = {"direction":"WEST", "rotation":"normal", "flip":"none"}
TARGET_ALIGNMENT = {"direction":"UP", "rotation":"normal", "flip":"none"}


def frames():
    for permutation in itertools.permutations(range(3)):
        for signs in itertools.product((-1,1), repeat=3):
            yield [[signs[c] if permutation[c] == r else 0 for c in range(3)] for r in range(3)]


@pytest.mark.parametrize("source", list(frames()))
def test_all_signed_axis_frames_roundtrip_points_boxes_and_faces(source):
    for target in frames():
        forward, reverse = mapping(source,target), mapping(target,source)
        args = {"points":[{"id":"p","offset":{"x":-3,"y":2,"z":7}}],
            "bounds":[{"id":"box","min":{"x":-3,"y":-2,"z":-1},"max":{"x":5,"y":4,"z":7}}]}
        result = resolve_geometry(args,forward,{"x":0,"y":100,"z":0})
        assert vector(reverse,result["points"][0]["offset"]) == args["points"][0]["offset"]
        reverse_sides = resolve_geometry({},reverse,{"x":0,"y":100,"z":0})["sides"]
        for side in result["sides"]:
            assert reverse_sides[side["targetSide"]]["targetSide"] == side["sourceSide"]
            if side["sourceSide"] < 6:
                assert result["sides"][side["sourceSide"]^1]["targetSide"] == side["targetSide"]^1
        box = result["bounds"][0]["relativeBounds"]
        assert all(box["min"][a] <= box["max"][a] for a in AXES)
        assert (box["max"]["x"]-box["min"]["x"]+1)*(box["max"]["y"]-box["min"]["y"]+1)*(box["max"]["z"]-box["min"]["z"]+1) == 9*7*9
        corners = [vector(forward,dict(zip(AXES,c))) for c in itertools.product((-3,5),(-2,4),(-1,7))]
        assert box == {"min":{a:min(p[a] for p in corners) for a in AXES},"max":{a:max(p[a] for p in corners) for a in AXES}}


def arguments():
    return {"expectedSessionId":"session","expectedWorld":"frame-world","dimension":0,
        "source":{"x":10,"y":20,"z":30,"piece":"first","expectedAlignment":copy.deepcopy(ALIGNMENT)},
        "target":{"x":40,"y":50,"z":60,"piece":"first","expectedAlignment":copy.deepcopy(TARGET_ALIGNMENT)},
        "points":[{"id":"opaque-task-role","offset":{"x":-2,"y":1,"z":3}}],
        "bounds":[{"id":"declared-equipment","min":{"x":-1,"y":-2,"z":-3},"max":{"x":2,"y":3,"z":4}}]}


@pytest.fixture
async def frame_bridge():
    state = {"calls":[],"fault":None,"structure_reads":0,"final_source_reads":0}
    config = Configuration({"MCB_URL":"http://127.0.0.1:25671","MCB_TOKEN":"frame-fixture"})
    async def respond(request):
        body = json.loads(request.content)
        if request.url.path == "/v1/cancel":
            state["cancellation"] = True
            return httpx.Response(200,json={"requestId":body["requestId"],"ok":True,"data":{"status":"cancelled"}})
        op,args=body["operation"],body["args"]
        state["calls"].append((op,copy.deepcopy(args)))
        assert op in READ_OPERATIONS
        if op == "bridge.health":
            data={"protocolVersion":1,"sessionId":"session","profile":"developer",
                "capabilities":["client.snapshot","world.inspect","world.structure"]}
            if state["fault"] == "capability":data["capabilities"].remove("world.structure")
        elif op == "client.snapshot":
            data={"sessionId":"session","profile":"developer","worldLoaded":True,
                "world":{"worldName":"frame-world","dimension":0}}
            if state["fault"]=="world":data["world"]["dimension"]=1
        elif op == "world.inspect":
            data={**{a:args[a] for a in AXES},"dimension":0,"source":"integrated_server",
                "registryName":"fixture:controller","metadata":0,"tileClass":"Public.Tile","gregtech":{"metaTileId":42}}
            if args["x"]==10:
                state["final_source_reads"]+=1
                if state["fault"]=="replacement" and state["final_source_reads"]>1:data["metadata"]=1
        else:
            assert op=="world.structure"
            if state["fault"]=="slow":await anyio.sleep(.16)
            state["structure_reads"]+=1
            source=args["x"]==10
            alignment=copy.deepcopy(ALIGNMENT if source else TARGET_ALIGNMENT)
            if state["fault"]=="rotation" and state["structure_reads"]==3:alignment["rotation"]="upside_down"
            matrix=IDENTITY if source else UP
            offsets=[args.get("offset"+a,0) for a in "ABC"]
            abc={a:v-offsets[i] for i,(a,v) in enumerate(zip("abc",(2,3,4)))}
            xyz=vector(matrix,dict(zip(AXES,abc.values())))
            row={**abc,**{a:args[a]+xyz[a] for a in AXES}}
            if state["fault"]=="malformed_matrix" and args.get("offsetA")==1:row["x"]+=2
            if state["fault"]=="point_drift" and args.get("offsetB")==1:row["a"]+=1
            data={"controller":{**{a:args[a] for a in AXES},"dimension":0,"metaClass":"Public.Controller"},
                "alignment":alignment,"mode":0,"piece":args["piece"],"worldPositionsAvailable":True,"positions":[row]}
            if state["fault"]=="no_progress":data.update(positions=[],hasMore=True,nextOffset=0)
            if state["fault"]=="mode" and state["structure_reads"]==11:data["mode"]=1
        return httpx.Response(200,json={"requestId":body["requestId"],"ok":True,"data":data})
    client=BridgeClient(config,transport=httpx.MockTransport(respond))
    try:yield client,state
    finally:await client.aclose()


async def test_adapter_reuses_public_probe_contract_without_game_effects(frame_bridge):
    client,state=frame_bridge
    args=arguments()
    result=await Adapter(client).call_tool("mcb_structure_transform",args)
    assert not result.isError,result.structuredContent
    d=result.structuredContent["data"]
    assert d["matrix"]==UP and d["anchor"]=={"x":40,"y":50,"z":60}
    assert d["points"][0]["position"]=={"x":43,"y":48,"z":59}
    assert d["worldEffectsDispatched"] is False and d["placementVerified"] is False
    assert d["sides"][6]=={"sourceSide":6,"targetSide":6}
    assert d["coverage"]["requests"]==len(state["calls"]) and len(state["calls"])<=24


@pytest.mark.parametrize("fault",["capability","world","replacement","rotation","malformed_matrix","point_drift","no_progress","mode"])
async def test_missing_or_changed_frame_fails_without_fallback_or_actions(frame_bridge,fault):
    client,state=frame_bridge
    state["fault"]=fault
    with pytest.raises(BridgeError):await run_structure_transform(client,arguments())
    assert all(op in READ_OPERATIONS for op,_ in state["calls"])


async def test_shared_budget_and_proposed_anchor_are_explicit(frame_bridge):
    client,state=frame_bridge
    args={**arguments(),"maxRequests":8}
    with pytest.raises(BridgeError,match="budget"):await run_structure_transform(client,args)
    assert len(state["calls"])==8
    state["calls"].clear()
    args={**arguments(),"anchor":{"x":-10,"y":100,"z":-20}}
    result=await run_structure_transform(client,args)
    assert result["anchorSource"]=="caller_proposed"
    assert result["points"][0]["position"]=={"x":-7,"y":98,"z":-21}


@pytest.mark.parametrize("bad",["duplicate","inverted","overflow","bool"])
async def test_invalid_geometry_is_not_silently_normalized(frame_bridge,bad):
    client,state=frame_bridge
    args=arguments()
    if bad=="duplicate":args["bounds"][0]["id"]=args["points"][0]["id"]
    elif bad=="inverted":args["bounds"][0]["min"]["x"]=3
    elif bad=="overflow":args["anchor"]={"x":0,"y":0,"z":0}
    else:args["points"][0]["offset"]["x"]=True
    if bad=="bool":
        with pytest.raises(BridgeError):_validate("mcb_structure_transform",args)
    else:
        with pytest.raises(BridgeError):await run_structure_transform(client,args)
    if bad in {"duplicate","inverted","bool"}:assert state["calls"]==[]


async def test_sequence_binds_actual_side_as_a_typed_scalar(bridge):
    adapter,state=bridge
    state["background"]=PROTECTED.copy()
    state["health_flags"].update(interactionBlockTargeting=True,interactionViewBinding=True)
    state["data"]["world.inspect"]={"source":"integrated_server","facing":3}
    result=await adapter.call_tool("mcb_sequence",{"expectedSessionId":"session","expectedWorld":"test-world","steps":[
        {"id":"observed","kind":"read","tool":"mcb_world_inspect","args":{"x":1,"y":2,"z":3}},
        {"id":"use","kind":"action","tool":"mcb_interact","args":{"action":"use","x":1,"y":2,"z":3},
         "bind":{"side":{"step":"observed","path":"facing"}}}]})
    assert not result.isError,result.structuredContent
    assert [c["args"]["side"] for c in state["calls"] if c["operation"]=="player.interact"]==[3]


@pytest.mark.parametrize("side",[True,None,-1,6])
async def test_sequence_rejects_invalid_bound_click_faces_before_action(bridge,side):
    adapter,state=bridge
    state["background"]=PROTECTED.copy()
    state["health_flags"].update(interactionBlockTargeting=True,interactionViewBinding=True)
    state["data"]["world.inspect"]={"facing":side}
    result=await adapter.call_tool("mcb_sequence",{"expectedSessionId":"session","expectedWorld":"test-world","steps":[
        {"id":"observed","kind":"read","tool":"mcb_world_inspect","args":{"x":1,"y":2,"z":3}},
        {"id":"use","kind":"action","tool":"mcb_interact","args":{"action":"use","x":1,"y":2,"z":3},
         "bind":{"side":{"step":"observed","path":"facing"}}}]})
    assert result.isError
    assert not any(c["operation"]=="player.interact" for c in state["calls"])


async def test_both_frame_reads_share_one_deadline(frame_bridge):
    client,state=frame_bridge
    state["fault"]="slow"
    with pytest.raises((TimeoutError, BridgeError)) as caught:
        await run_structure_transform(client,{**arguments(),"timeout_ms":1000})
    assert state["structure_reads"]<10
    if isinstance(caught.value, TimeoutError):
        assert state["cancellation"]
    else:
        # A deadline can expire between RPCs under load; there is then no
        # in-flight request to cancel. It must still stop at the same deadline.
        assert caught.value.details["reason"] in {"placement_deadline", "transform_deadline"}


@pytest.mark.parametrize("fault", [None, "capability", "matrix", "allowed", "duplicate", "source_mismatch", "incomplete"])
async def test_proposed_alignment_needs_no_target_reference_and_binds_public_native_catalog(frame_bridge, monkeypatch, fault):
    client, state = frame_bridge
    original = client.call
    async def call(op, args, **kwargs):
        if op == "world.structure" and args.get("alignmentFrames"):
            rows = [{"alignment": copy.deepcopy(ALIGNMENT), "axisTransform": IDENTITY, "sourceControllerAlignmentAllowed": True},
                {"alignment": copy.deepcopy(TARGET_ALIGNMENT), "axisTransform": UP, "sourceControllerAlignmentAllowed": False}]
            if fault == "matrix": rows[1]["axisTransform"] = [[1, 1, 0], [0, 0, 1], [0, 0, 0]]
            elif fault == "allowed": rows[1]["sourceControllerAlignmentAllowed"] = 1
            elif fault == "duplicate": rows.append(copy.deepcopy(rows[1]))
            elif fault == "source_mismatch": rows[0]["axisTransform"] = UP
            return {"mode": "alignment_frames", "version": 1, "complete": fault != "incomplete", "readOnly": True,
                "worldEffectsDispatched": False, "alignment": copy.deepcopy(ALIGNMENT), "frames": rows,
                "controller": {"x": 10, "y": 20, "z": 30, "dimension": 0, "metaClass": "Public.Controller"}}
        result = await original(op, args, **kwargs)
        if op == "bridge.health" and fault != "capability": result["structureAlignmentFramesVersion"] = 1
        return result
    monkeypatch.setattr(client, "call", call)
    args = arguments()
    args.pop("target")
    args.update(targetAlignment=copy.deepcopy(TARGET_ALIGNMENT), anchor={"x": 100, "y": 100, "z": 100})
    _validate("mcb_structure_transform", args)
    if fault:
        with pytest.raises(BridgeError): await run_structure_transform(client, args)
    else:
        result = await run_structure_transform(client, args)
        assert result["targetFrame"]["controller"] is None and result["targetFrame"]["placed"] is False
        assert result["targetFrame"]["sourceControllerAlignmentAllowed"] is False
        assert result["matrix"] == UP and result["points"][0]["position"] == {"x": 103, "y": 98, "z": 99}
    assert all(args.get("x") != 40 for _, args in state["calls"])


def test_proposed_frame_and_native_view_schemas_do_not_accept_ambiguous_queries():
    args = arguments()
    args["targetAlignment"] = TARGET_ALIGNMENT
    with pytest.raises(BridgeError): _validate("mcb_structure_transform", args)
    args.pop("target")
    with pytest.raises(BridgeError): _validate("mcb_structure_transform", args)
    _validate("mcb_world_structure", {"x": 1, "y": 2, "z": 3, "alignmentFrames": True})
    for extra in ({"piece": "first"}, {"captureId": "prior"}, {"offsetA": 0, "offsetB": 0, "offsetC": 0, "piece": "first"}):
        with pytest.raises(BridgeError):
            _validate("mcb_world_structure", {"x": 1, "y": 2, "z": 3, "alignmentFrames": True, **extra})
