"""Shared native GUI acceptance evidence; no input, polling or relaxed stock identity."""
from __future__ import annotations

import re


def phantom_items_confirmed(synchronization: dict) -> bool:
    if synchronization.get("fullPhantomSlotsEqual") is True:
        return True
    count = synchronization.get("phantomDisplayInitializedCount")
    total = synchronization.get("phantomItemSlotCount")
    return (type(synchronization.get("phantomDisplayInitializationVersion")) is int
            and synchronization["phantomDisplayInitializationVersion"] == 1
            and synchronization.get("phantomDisplayInitializationVerified") is True
            and synchronization.get("phantomConfigurationConfirmed") is True
            and type(count) is int and type(total) is int and 0 < count <= total <= 512)


def wait_failure(data: dict) -> dict:
    receipt = data.get("guiWait", {})
    diagnostic = receipt.get("diagnostic", {})
    reason = diagnostic.get("reason") or diagnostic.get("container", {}).get("reason")
    if receipt.get("observationReady") is True:
        reason = "declared_conditions_pending" if receipt.get("unmetConditions") else "acceptance_deadline_expired"
    elif diagnostic.get("screenObserved") is False:
        reason = "screen_not_observed"
    if not isinstance(reason, str) or not re.fullmatch(r"[a-z_]{1,80}", reason):
        reason = "synchronization_pending"
    return {"code": "GUI_WAIT_TIMEOUT", "reason": reason,
            "message": f"Native GUI acceptance timed out ({reason}); inspect the retained evidence and never replay clicks.",
            "replayAllowed": False, "guiWait": receipt}
