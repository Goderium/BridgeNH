"""Native survival food tool."""

from .common import REGISTRY_NAME, ToolSpec, integer, obj


T_MCB_FOOD = ToolSpec(
    "mcb_food", "survival.food",
    "Select one observed real hotbar stack for the living survival actor under a writable policy. "
    "eat submits one native edible-item use and waits for server use completion, one serving consumed and a hunger gain. "
    "healing_axe selects ExtraUtilities:defoliageAxe without clicking, observes the first server hunger gain when hungry "
    "and leaves it selected for continued passive feeding every 40 world ticks. Switching slots ends that effect. "
    "Full hunger returns already_full for food or selected_at_full for the axe. Inspect partial receipts before any further use. "
    "See mcb_help(topic=food).",
    obj({"action": {"enum": ["eat", "healing_axe"]}, "slot": integer(0, 8), "registryName": REGISTRY_NAME,
         "metadata": integer(0, 32767), "timeout_ms": {**integer(250, 6500), "default": 5000}},
        ["action", "slot", "registryName", "metadata"]),
    takeover=True, idempotent=False,
)
