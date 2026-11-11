import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.interaction_approach import _validate_plan


def proof(strategy, jump=1, drop=3):
    target = {"x": 0, "y": 64, "z": 0}
    slots = 1 + 33 * (1 + jump + drop if strategy == "walking" else 1)
    return {"sharedInteractionVersion": 1, "sharedCandidateVersion": 2,
            "walkingMaxJumpBlocks": jump, "walkingMaxDropBlocks": drop,
            "readOnly": True, "submitted": False, "partial": False, "complete": True,
            "status": "no_candidate", "movementStrategy": strategy, "useNormalReach": True,
            "current": {"x": 0, "feetY": 64, "z": 0, "reachPlayerId": "actor"},
            "targets": [target], "targetCount": 1,
            "coverage": {"candidateSlots": slots, "candidatesExamined": slots, "unknownChecks": 0}}


def validate(value, strategy):
    _validate_plan(value, [{"x": 0, "y": 64, "z": 0}], (0, 64, 0), "actor", strategy, current_only=False)


@pytest.mark.parametrize("strategy", ["walking", "ability_flight"])
@pytest.mark.parametrize("jump", [0, 1])
@pytest.mark.parametrize("drop", [0, 1, 2, 3])
def test_configured_complete_candidate_set(strategy, jump, drop):
    validate(proof(strategy, jump, drop), strategy)


@pytest.mark.parametrize("field,value", [("walkingMaxJumpBlocks", True), ("walkingMaxJumpBlocks", 2),
                                       ("walkingMaxDropBlocks", -1), ("walkingMaxDropBlocks", 4)])
def test_unverified_limits_cannot_authorize_group_split(field, value):
    data = proof("walking")
    data[field] = value
    with pytest.raises(BridgeError):
        validate(data, "walking")


def test_old_slot_count_cannot_claim_new_complete_search():
    data = proof("walking")
    data["coverage"].update(candidateSlots=34, candidatesExamined=34)
    with pytest.raises(BridgeError):
        validate(data, "walking")
