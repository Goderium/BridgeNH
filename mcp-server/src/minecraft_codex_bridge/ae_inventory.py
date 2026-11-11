"""AE empty-hand preparation reusing the guarded native recipe inventory workflow."""
from __future__ import annotations

from copy import deepcopy

from .recipe_tasks import _RecipeTask, _require
from .workflows import _workflow_discovery, run_workflow


class NativePlayerInventory(_RecipeTask):
    def __init__(self, parent, snapshot):
        player = snapshot.get("player", {})
        _require(type(player.get("creative")) is bool and type(player.get("flying")) is bool,
                 "ae_hand_actor_unverified", "Inventory preparation requires the actual typed player mode and flight state.")
        self.original_creative, self.original_flying = player["creative"], player["flying"]
        super().__init__(parent.client, {**parent.args, "actualSurvival": not self.original_creative,
            "generateMaterials": False, "restoreInventory": False}, run_workflow)
        self.deadline = parent.deadline
        self.approach_health = parent.health or {}
        self.capabilities = self.approach_health.get("capabilities", [])
        self._workflow_discovery = _workflow_discovery(self.tracked_client, self.credentials, self.approach_health)
        self._workflow_background_required = True

    def require_flight(self, snapshot):
        # Inventory movement never moves the actor. Retain both actual modes,
        # without borrowing a recipe's travel/flight posture requirement.
        player = snapshot.get("player", {})
        flight = player.get("serverFlight", {})
        _require(player.get("creative") is self.original_creative and player.get("flying") is self.original_flying
            and flight.get("available") is True and flight.get("creative") is self.original_creative
            and flight.get("flying") is self.original_flying and flight.get("dimension") == self.dimension,
            "ae_hand_actor_changed", "Native inventory preparation must preserve the same actual client/server actor mode and flight state.")
        offset = player.get("eyeY", 0) - player.get("feetY", 0)
        _require(type(offset) in (int, float) and 1 <= offset <= 2,
                 "player_eye_height_unavailable", "Inventory preparation needs the actual finite eye/feet offset.")
        return offset

class AeHandPreparation(NativePlayerInventory):
    async def prepare(self):
        await self.initialize_recipe_context()
        await self.prepare_empty_hand(0, reserve=1, phase="ae_hand_preparation", protected_items=[])
        await self.verify_retained_inventory()

    def receipt(self):
        return {"status": "completed" if self.final_inventory_verified else "failed",
            "moves": deepcopy(self.borrowed_inventory), "cursorEmpty": self.final_cursor_empty,
            "retentionConfirmed": self.final_inventory_verified,
            "taggedContextFullNbtVerified": self.recipe_context_inventory is not None and self.final_inventory_verified,
            "phases": deepcopy(self.phases), "replayAllowed": False,
            "scope": "One native whole untagged hotbar stack moved into an actually empty backpack slot; source, cursor and unrelated tagged context verified immediately after preparation. The stack stays in the backpack; later goal inventory changes are separate."}
