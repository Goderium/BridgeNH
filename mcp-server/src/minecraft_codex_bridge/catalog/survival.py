"""Basic actions whose semantics require a real survival actor or inventory."""

from .common import DIMENSION, EXPECTED_SESSION, EXPECTED_WORLD, POSITION, REGISTRY_NAME, ToolSpec, integer, obj


T_MCB_MINE = ToolSpec(
    "mcb_mine", "player.mine",
    "Mine one observed block with the currently selected survival tool through native per-tick attack. "
    "Aim first and pass the snapshot's lifecycleTarget.expectedWorld, dimension, block identity and hotbar slot. "
    "Target, tool or control changes stop the hold. Read mcb_task_status, then verify block state and actual pickup separately.",
    obj({**POSITION, "registryName": REGISTRY_NAME, "metadata": integer(0, 15), "hotbarSlot": integer(0, 8),
         "expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD, "dimension": DIMENSION,
         "ticks": integer(1, 200)}, ["x", "y", "z", "registryName", "metadata", "hotbarSlot",
                                      "expectedSessionId", "expectedWorld", "dimension"]),
    idempotent=False,
)
