from pathlib import Path
from types import SimpleNamespace
import time
import pytest

from minecraft_codex_bridge import tools
from minecraft_codex_bridge.catalog_refresh import _Declarations
from minecraft_codex_bridge.qa_tasks import _QaTask, _QaFailure, _RecordingClient


def client_fixture():
    credentials=object()
    return SimpleNamespace(configuration=SimpleNamespace(read=lambda: credentials))


def task_fixture():
    return _QaTask(client_fixture(),{"expectedSessionId":"runtime","expectedWorld":"QAWorld"},lambda *args: None,time.monotonic())


def remote_snapshot(connection="server-epoch"):
    return {"sessionId":"runtime","profile":"developer","worldLoaded":True,"paused":False,
            "world":{"dimension":41,"singleplayer":False,"worldName":"QAWorld","worldFolder":"QAWorld","identitySource":"remote_server_handshake"},
            "serverBridge":{"ready":True,"qaExecutionSupported":True,"connectionId":connection,"dimension":41},
            "player":{"serverFlight":{"available":True,"source":"remote_server_tick","dimension":41,"ageMillis":10,"worldTick":100}}}


def test_remote_qa_context_accepts_authoritative_connection_without_integrated_server():
    task=task_fixture()
    task.snapshot(remote_snapshot())
    assert task.dimension==41 and task.observation_source=="remote_server_tick"
    assert task.client.remote_binding=={"sessionId":"runtime","dimension":41,"connectionId":"server-epoch"}


@pytest.mark.parametrize("change",["epoch","permission","pose_source","pose_age","world_name","dimension","runtime"])
def test_remote_qa_context_never_accepts_identity_or_authority_drift(change):
    task=task_fixture(); task.snapshot(remote_snapshot()); value=remote_snapshot()
    if change=="epoch": value["serverBridge"]["connectionId"]="different-epoch"
    elif change=="permission": value["serverBridge"]["qaExecutionSupported"]=False
    elif change=="pose_source": value["player"]["serverFlight"]["source"]="client"
    elif change=="pose_age": value["player"]["serverFlight"]["ageMillis"]=1001
    elif change=="world_name": value["world"].update(worldName="other",worldFolder="other")
    elif change=="dimension": value["world"]["dimension"]=42
    elif change=="runtime": value["sessionId"]="other"
    with pytest.raises(_QaFailure): task.snapshot(value)


def test_remote_qa_does_not_silently_fall_back_to_local_world():
    task=task_fixture(); task.snapshot(remote_snapshot()); value=remote_snapshot()
    value["world"]["singleplayer"]=True
    with pytest.raises(_QaFailure): task.snapshot(value)


def test_entire_catalog_understands_bounded_dictionary_union_schemas():
    source=Path(tools.__file__).read_bytes()
    parsed=_Declarations().read(source)
    actual={spec.name:spec for spec in parsed}
    for name in ("mcb_server_observe","mcb_qa_catalog","mcb_qa_run","mcb_qa_test_once"):
        assert actual[name].schema==tools.TOOL_BY_NAME[name].schema


@pytest.mark.anyio
async def test_qa_transport_pins_connection_and_rejects_wrong_receipt():
    calls=[]
    client=client_fixture()
    async def call(operation,args,**kwargs):
        calls.append((operation,args))
        return {"sessionId":"runtime","dimension":41,"connectionId":"wrong-epoch","transportSource":"remote_server_tick"}
    client.call=call
    wrapper=_RecordingClient(client,client.configuration.read(),8)
    wrapper.remote_binding={"sessionId":"runtime","dimension":41,"connectionId":"server-epoch"}
    with pytest.raises(_QaFailure): await wrapper._request("qa.status",{"runId":"run"})
    assert calls==[("qa.status",{"runId":"run","expectedSessionId":"runtime","expectedConnectionId":"server-epoch"})]


@pytest.mark.parametrize("host",["127.0.0.1","localhost","qa.example.test"])
def test_native_server_connect_schema_and_takeover(host):
    from minecraft_codex_bridge.validation import tool_validator
    spec=tools.TOOL_BY_NAME["mcb_connect_server"]
    tool_validator(spec.name).validate({"host":host,"port":25565,"expectedSessionId":"live-runtime"})
    assert spec.operation=="client.connect" and spec.takeover
    assert spec.schema["required"]==["host","port","expectedSessionId"]


@pytest.mark.parametrize("args",[
    {"host":"http://localhost","port":25565,"expectedSessionId":"runtime"},
    {"host":"localhost:25565","port":25565,"expectedSessionId":"runtime"},
    {"host":"localhost","port":0,"expectedSessionId":"runtime"},
    {"host":"localhost","port":65536,"expectedSessionId":"runtime"},
    {"host":"localhost","port":25565},
])
def test_native_server_connect_refuses_urls_ports_and_missing_session(args):
    from minecraft_codex_bridge.validation import tool_validator
    from jsonschema.exceptions import ValidationError
    with pytest.raises(ValidationError): tool_validator("mcb_connect_server").validate(args)
