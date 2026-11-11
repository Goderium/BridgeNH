import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, capability_scope, request_context


async def exercise(*, remote=False, unguarded=False, changed_session=False, failure=False):
    calls = []
    def respond(request):
        body = json.loads(request.content); operation = body["operation"]; calls.append(operation)
        data = {"protocolVersion": 1, "sessionId": "session", "capabilities": ["gui.inspect"],
                "readiness": {"localIntegratedServer": not remote, "remoteServerReady": remote}}
        if operation != "bridge.health":
            if failure:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                                                "error": {"code": "SESSION_CHANGED", "message": "Changed."}})
            data = {"slots": []}
        envelope = {"requestId": body["requestId"], "ok": True, "data": data}
        if "includeContext" in body:
            envelope["context"] = {"version": 1, "operation": operation, "phase": "after", "snapshot": {}}
        return httpx.Response(200, json=envelope)
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "scope-test-token"}),
                          transport=httpx.MockTransport(respond))
    context = None if unguarded else {"version": 1, "expectedSessionId": "changed" if changed_session else "session",
                                     "expectedProfile": "developer", "expectedWorld": "Debug", "requireBackground": True}
    try:
        with capability_scope():
            await client.invoke("bridge.health", {})
            with request_context(context):
                for _ in range(2):
                    try:
                        await client.invoke("gui.inspect", {})
                    except BridgeError:
                        assert failure
        return calls
    finally:
        await client.aclose()


async def test_scoped_local_guard_reuses_only_health_and_still_reads_every_time():
    assert await exercise() == ["bridge.health", "gui.inspect", "gui.inspect"]


@pytest.mark.parametrize("kwargs", [{"remote": True}, {"unguarded": True}, {"changed_session": True}])
async def test_unbound_or_remote_call_never_uses_local_capability_cache(kwargs):
    assert (await exercise(**kwargs)).count("bridge.health") == 3


async def test_native_error_invalidates_cached_capabilities_before_followup():
    assert (await exercise(failure=True)).count("bridge.health") == 2
