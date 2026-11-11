"""Optional exact public attachment contract, selected before native mutation."""
from copy import deepcopy

from .bridge import BridgeError

CONTRACT = {"version": 1, "operation": "player.place_block", "argument": "attachmentBinding", "guardedOnly": True,
    "exactAdjacentFace": True, "publicBlockIdentity": True, "publicGregTechIdentity": True, "clientServerRequired": True,
    "allPlayerSlots": 41, "normalReachAndRayRequired": True, "wholeTargetBodyExcluded": True,
    "reselectAllowed": False, "automaticReplayAllowed": False}
VECTORS = ((0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0))


def require_capability(health):
    value = (health or {}).get("placementAttachmentBinding")
    if not (isinstance(value, dict) and set(value) == set(CONTRACT)
            and all(type(value.get(key)) is type(expected) and value[key] == expected for key, expected in CONTRACT.items())):
        raise BridgeError("placement_attachment_capability_unavailable", "Exact frontier placement needs the complete advertised dual-side attachment contract.", outcome_unknown=False)
    from .workflows import _context_capabilities
    if "player.place_block" not in _context_capabilities(health)[1]:
        raise BridgeError("placement_attachment_capability_unavailable", "Exact attachment placement requires its standard native BEFORE guard.", outcome_unknown=False)


def from_plan(health, target, selected, frontier):
    require_capability(health)
    required = ("x", "y", "z", "dimension", "side", "registryName", "metadata")
    valid = isinstance(selected, dict) and selected.get("source") == "integrated_server" \
        and all(type(selected.get(key)) is int for key in ("x", "y", "z", "dimension", "side", "metadata")) \
        and 0 <= selected["side"] <= 5 and 0 <= selected["metadata"] <= 32766 \
        and selected["dimension"] == target["dimension"] and 0 <= selected["y"] <= 255 \
        and all(-30000000 <= selected[axis] <= 30000000 for axis in ("x", "z")) \
        and isinstance(selected.get("registryName"), str) and 1 <= len(selected["registryName"]) <= 256
    if valid:
        vector = VECTORS[selected["side"]]
        valid = all(selected[axis] + vector[index] == target[axis] for index, axis in enumerate(("x", "y", "z"))) \
            and any(all(selected[axis] == position[axis] for axis in ("x", "y", "z")) and selected["side"] == side
                    for position, side in frontier)
    gt = selected.get("gregtech") if isinstance(selected, dict) else None
    if isinstance(selected, dict) and "gregtech" in selected:
        valid = valid and isinstance(gt, dict) and type(gt.get("metaTileId")) is int and 0 <= gt["metaTileId"] <= 32767 \
            and isinstance(gt.get("metaClass"), str) and 1 <= len(gt["metaClass"]) <= 256
    if not valid:
        raise BridgeError("placement_attachment_plan_unavailable", "A frontier mutation needs this plan's exact public Block/GT adjacent face identity.", outcome_unknown=False)
    result = {"version": 1, **{key: deepcopy(selected[key]) for key in required}}
    if gt is not None:
        result["gregtech"] = {key: deepcopy(gt[key]) for key in ("metaTileId", "metaClass")}
    return result


def _typed_equal(left, right):
    if type(left) is not type(right):
        return False
    if isinstance(right, dict):
        return set(left) == set(right) and all(_typed_equal(left[key], value) for key, value in right.items())
    return left == right


def validate_receipt(result, binding):
    flags = ("attachmentBindingVerified", "clientAttachmentBindingVerified", "serverAttachmentBindingVerified", "serverConfirmed", "fullNbtConservation")
    attachment = result.get("attachment") if isinstance(result, dict) else None
    valid = isinstance(result, dict) and all(result.get(key) is True for key in flags) \
        and _typed_equal(result.get("attachmentBinding"), binding) and type(result.get("allPlayerSlots")) is int and result["allPlayerSlots"] == 41 \
        and isinstance(attachment, dict) and all(type(attachment.get(key)) is int and attachment[key] == binding[key] for key in ("x", "y", "z", "side"))
    if not valid:
        raise BridgeError("placement_attachment_receipt_unavailable", "The native receipt must confirm the exact dual-side attachment and all 41 full-NBT player slots; never replay.",
            outcome_unknown=True, automaticReplayAllowed=False, knownReceipt=deepcopy(result) if isinstance(result, dict) else None)
