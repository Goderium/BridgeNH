"""Nearby configuration preserves declaration order and partial native receipts."""
import pytest

from minecraft_codex_bridge.assembly_plan import install_connections
from minecraft_codex_bridge.ae_tasks import AeTask
from minecraft_codex_bridge.bridge import BridgeError
from test_ae_goals import COMMON, TARGET


class Task:
    def __init__(self, fail=False):
        self.calls, self.fail = [], fail

    async def install(self, declaration):
        self.calls.append(("install", declaration["x"]))
        return {"target": {"x": declaration["x"], "y": 4, "z": 5}, "part": {"side": 2}, "serverConfirmed": True}

    async def configure(self, declaration):
        self.calls.append(("configure", declaration["target"]["x"]))
        if self.fail: raise BridgeError("AE_OWNER_CHANGED", "changed")
        return {"serverConfirmed": True}


def plan(config_order):
    return {"install": [{"x": 1}, {"x": 2}], "configure": [
        {"target": {"x": x, "y": 4, "z": 5, "targetIdentity": {"part": {"side": 2}}}} for x in config_order]}


@pytest.mark.parametrize("order,expected", [([1, 2], [("install", 1), ("configure", 1), ("install", 2), ("configure", 2)]),
    ([2, 1], [("install", 1), ("install", 2), ("configure", 2), ("configure", 1)])])
async def test_configuration_moves_forward_only_for_next_exact_installed_part(order, expected):
    task = Task()
    report = {"installed": [], "configured": []}
    await install_connections(task, plan(order), report)
    assert task.calls == expected and len(report["installed"]) == len(report["configured"]) == 2


async def test_early_config_failure_retains_first_install_and_does_not_place_later_parts():
    task = Task(fail=True)
    report = {"installed": [], "configured": []}
    with pytest.raises(BridgeError): await install_connections(task, plan([1, 2]), report)
    assert task.calls == [("install", 1), ("configure", 1)]
    assert len(report["installed"]) == 1 and report["configured"] == []
    assert report["stage"] == "configure" and report["pendingIndex"] == 0


async def test_matching_authoritative_configuration_needs_no_gui_or_action():
    class Matched(AeTask):
        async def configuration(self, target):
            return {"priority": 10, "settings": {"BLOCK": "YES"}, "complete": True}
        async def approach(self, *a, **kw):
            pytest.fail("Already matched server configuration must not open a GUI")
    result = await Matched(None, COMMON, None).configure({"target": TARGET, "priority": 10, "settings": {"BLOCK": "YES"}})
    assert result["alreadyMatched"] and result["serverConfirmed"] and result["after"] == result["before"]
