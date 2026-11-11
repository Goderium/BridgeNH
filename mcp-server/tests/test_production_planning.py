from copy import deepcopy
import pytest

from minecraft_codex_bridge.production_planning import check_process
from minecraft_codex_bridge.server import Adapter, _validate
from minecraft_codex_bridge.bridge import BridgeError


def resource(name, role, amount=0, **extra):
    return {"id": name, "identity": {"kind": "fluid", "fluidName": name}, "role": role, "initial": str(amount), **extra}


def recipe(name, inputs, outputs, batches=1, **extra):
    return {"id": name, "machine": name, "source": "deterministic regression fixture", "batches": batches,
        "inputs": [{"resource": k, "amount": str(v)} for k, v in inputs.items()],
        "outputs": [{"resource": k, "amount": str(v)} for k, v in outputs.items()], **extra}


def process(resources, recipes):
    return {"resources": resources, "recipes": recipes, "machines": [r["machine"] for r in recipes]}


def titanium():
    return process([resource("rutile", "feed", 36), resource("carbon", "external"),
        resource("chlorine", "cycle", 4000), resource("magnesium", "cycle", 2), resource("sodium", "cycle", 4),
        resource("salt", "cycle"), resource("chloride", "intermediate"), resource("hot", "intermediate"),
        resource("mgchloride", "intermediate"), resource("titanium", "product", expectedFinal="12"), resource("co", "product")], [
        recipe("chlorination", {"rutile": 3, "carbon": 2, "chlorine": 4000}, {"chloride": 1000, "co": 2000}, 12),
        recipe("reduction", {"chloride": 1000, "magnesium": 2}, {"hot": 1, "mgchloride": 6}, 12),
        recipe("cooling", {"hot": 1}, {"titanium": 1}, 12),
        recipe("recovery", {"mgchloride": 6, "sodium": 4}, {"magnesium": 2, "salt": 8}, 12),
        recipe("electrolysis", {"salt": 2}, {"sodium": 1, "chlorine": 1000}, 48)])


def test_titanium_cycle_exact_two_batch_sizes_and_no_seed_consumption():
    for feed, batches in [(36, 12), (48, 16)]:
        plan = titanium()
        plan["resources"][0]["initial"] = str(feed)
        next(r for r in plan["resources"] if r["id"] == "titanium")["expectedFinal"] = str(batches)
        for r in plan["recipes"]:
            r["batches"] = batches * (4 if r["id"] == "electrolysis" else 1)
        _validate("mcb_operation_check", {"kind": "process", "process": plan})
        result = check_process(plan)
        assert result["planValid"] and not result["productionVerified"]
        balances = {r["resource"]: r for r in result["balances"]}
        for seed in ("chlorine", "magnesium", "sodium"):
            assert balances[seed]["final"] == balances[seed]["initial"]
        assert balances["co"]["final"] == str(2000 * batches)
        assert result["externalInputs"] == [{"resource": "carbon", "consumed": str(2 * batches)}]


def test_platinum_360_to_11000_reports_remainder_and_exact_pair_multiple():
    plan = process([resource("feed", "feed", 31), resource("solution", "intermediate"), resource("product", "product")], [
        recipe("upstream", {"feed": 1}, {"solution": 360}, 31), recipe("downstream", {"solution": 11000}, {"product": 1})])
    result = check_process(plan)
    assert not result["planValid"]
    assert {"code": "final_quantity_mismatch", "resource": "solution", "expected": "0", "actual": "160"} in result["issues"]
    assert result["pairwiseBatchMultiples"][0]["producerBatches"] == 275
    assert result["pairwiseBatchMultiples"][0]["consumerBatches"] == 9
    plan["resources"][0]["initial"] = "275"
    plan["recipes"][0]["batches"], plan["recipes"][1]["batches"] = 275, 9
    assert check_process(plan)["planValid"]


def test_missing_branch_missing_machine_and_cycle_bootstrap_are_not_success():
    plan = titanium()
    plan["recipes"] = [r for r in plan["recipes"] if r["id"] != "reduction"]
    result = check_process(plan)
    assert not result["planValid"] and any(i["code"] == "missing_producer" and i["resource"] == "hot" for i in result["issues"])
    plan = titanium()
    plan["machines"].remove("recovery")
    assert any(i["code"] == "missing_machine" for i in check_process(plan)["issues"])
    plan = titanium()
    next(r for r in plan["resources"] if r["id"] == "chlorine")["initial"] = "0"
    result = check_process(plan)
    assert not result["planValid"] and result["blocked"][0]["shortages"][0]["resource"] == "chlorine"


def test_exact_big_quantities_no_float_and_duplicate_identities_rejected():
    amount = 9223372036854775807
    plan = process([resource("a", "feed", amount), resource("b", "product")], [recipe("convert", {"a": amount}, {"b": amount})])
    assert check_process(plan)["balances"][1]["final"] == str(amount)
    plan["resources"][1]["identity"] = deepcopy(plan["resources"][0]["identity"])
    with pytest.raises(BridgeError):
        check_process(plan)


def test_nonconsumed_catalyst_requires_real_seed_but_is_not_deducted():
    plan = process([resource("feed", "feed", 4), resource("tool", "cycle", 1), resource("out", "product")], [
        recipe("run", {"feed": 1}, {"out": 1}, 4, catalysts=[{"resource": "tool", "amount": "1"}])])
    assert check_process(plan)["balances"][1]["final"] == "1"
    plan["resources"][1]["initial"] = "0"
    assert check_process(plan)["readiness"] == "blocked"


async def test_offline_entrypoint_does_not_require_or_access_a_game():
    class NoGame:
        async def invoke(self, *a, **kw): pytest.fail("offline process check touched game")
    result = await Adapter(NoGame()).call_tool("mcb_operation_check", {"kind": "process", "process": titanium()})
    assert not result.isError and result.structuredContent["data"]["planValid"]


@pytest.mark.parametrize("change", [lambda p: p["recipes"][0].update(chance=0.5),
    lambda p: p["resources"][0].update(initial="1.5"), lambda p: p["recipes"][0].update(batches=True)])
def test_unsupported_or_ambiguous_math_refused(change):
    plan = titanium()
    change(plan)
    with pytest.raises(BridgeError):
        _validate("mcb_operation_check", {"kind": "process", "process": plan})
