"""Creative item generation and portable block copies."""

from .common import DIMENSION, EXPECTED_SESSION, EXPECTED_WORLD, LIVE_ID, POSITION, REGISTRY_NAME, ToolSpec, integer, obj


T_MCB_COPY_BLOCK = ToolSpec("mcb_copy_block", "inventory.copy_block",
    "Copy one loaded block to an empty inventory slot in the bound local creative world. Pass its observed block registryName and metadata. For GT machines and pipes also pass expectedMetaTileId. GT uses portable item NBT including covers and meta-specific settings. Other blocks use their Forge pick item. Inspect the returned authoritative destination; never replay an uncertain grant.",
    obj({**POSITION, "expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD, "dimension": DIMENSION,
         "registryName": REGISTRY_NAME, "metadata": integer(0, 15), "expectedMetaTileId": integer(0, 32767),
         "side": integer(0, 5), "destinationSlot": integer(0, 35)}, ["x", "y", "z", "expectedSessionId", "expectedWorld", "dimension", "registryName", "metadata", "destinationSlot"]), idempotent=False)

T_MCB_SUPPLY_ITEMS = ToolSpec("mcb_supply_items", "inventory.supply", "Batch 1..8 distinct untagged creative supplies through native commands in one server-thread operation. Exact expectedSessionId/expectedWorld and empty cursor required. Default retains the empty-inventory contract. With nativeSupplyPreserveInventoryVersion=1, preserveInventory=true preserves existing inventory and reserves capacity for the entire batch in the 36 ordinary slots before any command, accounting for exact identity/NBT and public stack limits. No equipment/offhand capacity or inventory clearing. Each count is additional stock, not a target total. Recipe/build tasks already own their supply; do not grant twice. Native command receipts are not inventory acceptance: reconcile the actual synchronized inventory once after the batch. Partial/unknown receipts never permit replay or chat fallback.", obj({
        "expectedSessionId": LIVE_ID, "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128},
        "preserveInventory": {"type": "boolean", "default": False},
        "items": {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True,
            "items": obj({"registryName": {"type": "string", "minLength": 3, "maxLength": 128, "pattern": r"^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+$", "not": {"pattern": r"[\s\x00-\x1F\x7F]"}},
                "metadata": integer(0, 32766), "count": integer(1, 64)}, ["registryName", "metadata", "count"]) }},
        ["expectedSessionId", "expectedWorld", "items"]))
