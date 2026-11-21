import asyncio
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.action_chain import ActionChain
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import validate_tool_arguments


def read(group):
    return {"id": group, "steps": [{"id": "observe", "kind": "read", "tool": "mcb_inventory"}]}


def ref(chain, action):
    return {"action": action, "chainId": chain.id, "expectedRevision": chain.revision}


@pytest.fixture
def engine():
    queue = ActionChain()
    snap = {"sessionId": "session", "lifecycleTarget": {"expectedWorld": "world"}, "worldLoaded": True,
            "world": {"dimension": 0}, "inputTask": {"status":"idle"},
            "background":{"enabled":True,"helperInstalled":True,"pauseOnLostFocus":False}}
    async def invoke(name, args):
        assert name == "client.snapshot"
        return snap
    async def run(client, plan, lock, **options):
        assert options["_expected_dimension"] == 0
        return {"status":"completed","dispatchedActions":0,"checkpoint":{"effectAcceptanceComplete":True}}
    async def call(args, runner=run):
        validate_tool_arguments("mcb_action_chain", args)
        return await queue.call(args, SimpleNamespace(invoke=invoke), None, runner)
    return queue, snap, call


async def start(call):
    return await call({"action":"open","expectedSessionId":"session","expectedWorld":"world","dimension":0,
                       "segments":[read("first"), read("last")]})


async def test_insert_after_checkpoint_and_handoff(engine):
    queue, _, call = engine
    await start(call)
    original = ref(queue, "advance")
    first = await call(original)
    assert first["completed"] == ["first"] and first["pending"] == ["last"]
    with pytest.raises(BridgeError, match="status"):
        await call(original)
    await call({**ref(queue,"insert"), "segments":[read("inserted")]})
    copy = ActionChain.restore(queue.checkpoint())
    assert queue.checkpoint() == copy.checkpoint()
    assert (await call(ref(queue,"advance")))["completed"] == ["first","inserted"]
    assert (await call(ref(queue,"advance")))["status"] == "completed"


async def test_active_input_and_uncertain_segment_never_replay(engine):
    queue, snapshot, call = engine
    await start(call)
    snapshot["inputTask"] = {"status":"active","taskId":"mining"}
    response = await call(ref(queue,"advance"))
    assert not response["dispatched"] and response["waitingForNativeTask"] == "mining"
    assert queue.pending[0]["id"] == "first"
    snapshot["inputTask"] = {"status":"idle"}
    async def uncertain(*args, **kwargs):
        return {"status":"failed","outcomeUnknown":True}
    assert (await call(ref(queue,"advance"), uncertain))["status"] == "blocked"
    with pytest.raises(BridgeError):
        await call(ref(queue,"advance"))
    assert ActionChain.restore(queue.checkpoint()).status == "blocked"


async def test_no_second_dispatch_during_advance(engine):
    queue, _, call = engine
    await start(call)
    entered, finish = asyncio.Event(), asyncio.Event()
    async def wait(*args, **kwargs):
        entered.set()
        await finish.wait()
        return {"status":"completed","checkpoint":{"effectAcceptanceComplete":True}}
    pending = asyncio.create_task(call(ref(queue,"advance"), wait))
    await entered.wait()
    try:
        with pytest.raises(BridgeError):
            await call(ref(queue,"advance"))
    finally:
        finish.set()
        await pending


async def test_wrong_dimension_and_unaccepted_action(engine):
    queue, snapshot, call = engine
    await start(call)
    with pytest.raises(BridgeError, match="acceptance"):
        await call({**ref(queue,"insert"), "segments":[{"id":"move","steps":[
            {"kind":"action","id":"click","tool":"mcb_hotbar","args":{"slot":0}}]}]})
    snapshot["world"]["dimension"] = -1
    with pytest.raises(BridgeError, match="original"):
        await call(ref(queue,"advance"))
    assert queue.status == "blocked"
    assert queue.last["report"]["error"]["code"] == "CHAIN_WORLD_CHANGED"
    assert not queue.last["report"]["outcomeUnknown"]


def test_basic_operations_have_distinct_native_operations_and_schema():
    assert TOOL_BY_NAME["mcb_mine"].operation == "player.mine"
    assert TOOL_BY_NAME["mcb_entity_interact"].operation == "player.entity_interact"
    assert TOOL_BY_NAME["mcb_chest_transfer"].operation == "gui.chest_transfer"
    with pytest.raises(BridgeError):
        validate_tool_arguments("mcb_mine", {"x":0,"y":64,"z":0})
