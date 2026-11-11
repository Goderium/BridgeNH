"""Current-position transient reads never replay navigation or interaction."""
import anyio
import pytest

from minecraft_codex_bridge.bridge import BridgeError
from test_shared_interaction_approach import Client, run


class BudgetClient(Client):
    def __init__(self, failures=1, fault=None):
        super().__init__(in_range=False)
        self.failures, self.retry_fault, self.current_reads = failures, fault, 0

    async def invoke(self, operation, payload):
        result = await super().invoke(operation, payload)
        if operation == "player.plan_approach" and payload["currentOnly"]:
            self.current_reads += 1
            if self.current_reads > 1:
                if self.retry_fault == "transport":
                    raise BridgeError("BRIDGE_UNAVAILABLE", "read failed")
                if self.retry_fault == "deadline":
                    await anyio.sleep(.3)
                if self.retry_fault == "actor":
                    result.context["snapshot"]["serverControl"]["playerId"] = "changed"
                if self.retry_fault == "pose":
                    result.context["snapshot"]["player"]["x"] += .1
                    result["current"]["x"] += .1
                    result["selected"]["feet"]["x"] += .1
            if self.current_reads <= self.failures:
                result.update(status="partial", partial=True, complete=False, selected=None,
                              currentOnly=True, stopReason="tick_budget")
                if self.retry_fault == "ray_limit": result["stopReason"] = "ray_limit"
                if self.retry_fault == "wrong_target": result["targets"] = [{"x": 10, "y": 64, "z": 0}]
                if self.retry_fault == "wide_search": result["coverage"]["candidateSlots"] = 34
        return result


@pytest.mark.parametrize("failures", [1, 2])
async def test_budget_recheck_reuses_arrival_then_uses_once(failures):
    client = BudgetClient(failures)
    result = await run(client, targets=[{"x": 0, "y": 64, "z": 0}], interact=True)
    assert result["status"] == "completed"
    assert len(client.moves) == len(client.uses) == 1
    assert client.current_reads == failures + 1
    assert result["approachReadRetry"]["additionalReads"] == failures
    assert result["approachReadRetry"]["mutationReplayed"] is False


@pytest.mark.parametrize("fault,reads", [("ray_limit", 1), ("wrong_target", 1), ("wide_search", 1),
    ("transport", 2), ("actor", 2), ("pose", 2), ("deadline", 2), (None, 3)])
async def test_unverified_or_exhausted_read_stops_without_extra_move_or_use(fault, reads):
    client = BudgetClient(9 if fault is None else 1, fault)
    with pytest.raises(BridgeError):
        await run(client, targets=[{"x": 0, "y": 64, "z": 0}], interact=True)
    assert client.current_reads == reads
    assert len(client.moves) == 1 and not client.uses
