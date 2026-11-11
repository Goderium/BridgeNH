"""Opt-in bounded item vectors using the ordinary native recipe task controls."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .whole_stack_transfer import plan as plan_whole_stack, execute as execute_whole_stack

from .recipe_tasks import (
    INTEGRATED_CIRCUIT, MACHINE_SLOT_BINDING, MAX_TRANSFER_COUNT, SimpleRecipe,
    _RecipeTask, _action, _assert, _entries, _identity, _match, _quantity,
    _read, _require, _same, _wait, inventory_baseline_assertion, inventory_slots, item_count, power_widget,
    validate_recipe_conditions,
)


@dataclass(frozen=True)
class MultiRecipe(SimpleRecipe):
    consumed_items: tuple[tuple[dict[str, Any], int], ...] = ()
    output_items: tuple[tuple[dict[str, Any], int], ...] = ()
    catalysts: tuple[dict[str, Any], ...] = ()
    fluid_inputs: tuple[tuple[str, int], ...] = ()
    fluid_outputs: tuple[tuple[str, int], ...] = ()


def _key(identity: dict[str, Any]) -> tuple[str, int]:
    return identity["registryName"], identity["metadata"]


def _declared_slot(arguments: dict[str, Any], name: str, ordinal: int, total: int, legacy: str) -> int | None:
    declarations = arguments.get(name)
    if declarations is not None:
        _require(len(declarations) == total, "multi_gui_declaration_count_mismatch",
                 "Ordered native slot declarations must cover the complete raw identity group exactly.")
        _require(legacy not in arguments or not declarations or arguments[legacy] == declarations[0],
                 "multi_gui_declaration_conflict", "A legacy first slot must agree with the ordered first declaration.")
        return declarations[ordinal]
    return arguments.get(legacy) if ordinal == 0 else None


def supported_multi_recipe(candidate: dict[str, Any], arguments: dict[str, Any]) -> MultiRecipe:
    """Preserve the original raw-condition guards and expand only finite item groups."""
    validate_recipe_conditions(candidate, arguments)
    inputs = _entries(candidate.get("items", {}).get("inputs"))
    outputs = _entries(candidate.get("items", {}).get("outputs"))
    fluid_inputs = _fluid_group(candidate.get("fluids", {}).get("inputs"))
    fluid_outputs = _fluid_group(candidate.get("fluids", {}).get("outputs"))
    _require(0 <= len(inputs) <= 8 and 0 <= len(outputs) <= 8
             and type(candidate.get("chanceDenominator")) is int and candidate["chanceDenominator"] == 10_000,
             "multi_recipe_group_budget", "Finite public item inputs and outputs are each bounded to eight entries.")
    _require(all(isinstance(entry, dict) and type(entry.get("chance")) is int
                 and entry["chance"] == 10_000 for entry in inputs + outputs),
             "probability_recipe_unsupported", "Every actual item input and output must be deterministic.")
    consumed: list[tuple[dict[str, Any], int]] = []
    catalysts: list[dict[str, Any]] = []
    for entry in inputs:
        stack = entry.get("stack")
        identity = _identity(stack, wildcard=True)
        count = _quantity(stack.get("count"))
        _require(type(entry.get("nonConsumedCatalyst")) is bool
                 and entry["nonConsumedCatalyst"] == (count == 0),
                 "catalyst_semantics_unavailable", "Public count-zero catalyst semantics must remain explicit.")
        if identity["metadata"] == 32_767:
            matches = [item for item in arguments["backendInputs"]
                       if item["registryName"] == identity["registryName"] and item["count"] == count]
            _require(count > 0 and len(matches) == 1, "wildcard_material_required",
                     "Each wildcard consumed entry requires one uniquely declared concrete finite backend template.")
            identity = _identity({**matches[0], "hasTag": False})
        if count:
            _require(1 <= count <= 64, "exact_transfer_budget",
                     "Each finite consumed item is bounded to one 64-item native source.")
            consumed.append((identity, count))
        else:
            catalysts.append(identity)
    products = [(_identity(entry.get("stack")), _quantity(entry["stack"].get("count"))) for entry in outputs]
    _require((consumed or fluid_inputs) and (products or fluid_outputs) and all(1 <= count <= 64 for _, count in products),
             "multi_recipe_group_budget", "A recipe requires actual consumables and positive deterministic products.")
    _require(all(item["metadata"] != 32_767 for item in catalysts), "catalyst_type_unsupported",
             "Every physical catalyst needs one concrete actual identity.")
    all_inputs = [identity for identity, _ in consumed] + catalysts
    _require(len({_key(item) for item in all_inputs}) == len(all_inputs)
             and len({_key(item) for item, _ in products}) == len(products),
             "multi_recipe_identity_overlap", "Repeated consumed/catalyst or product identities cannot share independent quantity proofs.")
    declared = arguments["outputItems"]
    _require(len(declared) == len(products) and {_key(item) for item in declared} == {_key(item) for item, _ in products}
             and (not products or arguments.get("outputItem") in declared), "multi_output_recipe_mismatch",
             "The explicit complete output identity set must match every raw product; the legacy query product is one member.")
    shared = arguments["machine"] == arguments["input"] == arguments["output"]
    _require(not shared or not {_key(item) for item in all_inputs} & {_key(item) for item, _ in products},
             "shared_inventory_recipe_identity_overlap", "Shared input, catalyst and output identities require independent quantities.")
    ghost = arguments.get("catalystMode", "physical") == "configured_ghost"
    _require(not ghost or len(catalysts) == 1 and catalysts[0]["registryName"] == INTEGRATED_CIRCUIT,
             "configured_ghost_recipe_required", "The existing configured ghost path requires exactly one public zero-count integrated circuit.")
    _require(all(count * arguments.get("executions", 1) <= 64 for _, count in consumed + products),
             "multi_recipe_quantity_budget", "Every finite total source and retrieved product is bounded to one 64-item quantity.")
    if "material" in arguments:
        _require(bool(consumed) and arguments["material"] == consumed[0][0], "material_recipe_mismatch",
                 "The optional legacy material selector must match the first raw consumed item.")
    for field, raw in (("fluidContainerInputs", fluid_inputs), ("fluidContainerOutputs", fluid_outputs)):
        declared_fluids = arguments.get(field, [])
        _require(len(declared_fluids) == len(raw)
                 and {(entry["fluid"], entry["amount"]) for entry in declared_fluids} == set(raw),
                 "multi_fluid_declaration_mismatch", "Every raw fluid identity/amount needs exactly one explicit actual container stack declaration.")
    _require(not (fluid_inputs or fluid_outputs) or arguments.get("executions", 1) == 1,
             "multi_fluid_execution_budget", "Whole actual container-stack fluid recipes currently execute exactly once.")
    for name, group, legacy in (("inputGuiSlots", consumed, "inputGuiSlot"),
                                ("catalystGuiSlots", catalysts, "catalystGuiSlot"),
                                ("outputGuiSlots", products, "outputGuiSlot")):
        if name in arguments:
            _declared_slot(arguments, name, 0, len(group), legacy)
    return MultiRecipe(consumed[0][0] if consumed else {}, consumed[0][1] if consumed else 0,
                       products[0][0] if products else {}, products[0][1] if products else 0,
                       catalysts[0] if catalysts else None, candidate["rawEUt"], candidate["rawDurationTicks"],
                       str(candidate.get("category") or "")[:128], 0, "public_backend_find",
                       tuple(consumed), tuple(products), tuple(catalysts), tuple(fluid_inputs), tuple(fluid_outputs))


def _fluid_group(group: Any) -> list[tuple[str, int]]:
    entries = _entries(group)
    _require(len(entries) <= 8, "multi_recipe_group_budget", "Finite raw fluid groups are bounded to eight entries.")
    result = []
    for entry in entries:
        _require(isinstance(entry, dict) and type(entry.get("chance")) is int and entry["chance"] == 10_000,
                 "probability_recipe_unsupported", "Every actual fluid input and output must be deterministic.")
        stack = entry.get("stack")
        _require(isinstance(stack, dict) and stack.get("hasTag") is False
                 and isinstance(stack.get("fluid"), str) and 1 <= len(stack["fluid"]) <= 128
                 and type(stack.get("amount")) is int and 1 <= stack["amount"] <= 2_147_483_647,
                 "multi_fluid_identity_unavailable", "Raw fluid identities and positive amounts must be explicitly untagged and typed.")
        result.append((stack["fluid"], stack["amount"]))
    _require(len({fluid for fluid, _ in result}) == len(result), "multi_fluid_identity_overlap",
             "Each raw fluid group needs distinct exact runtime fluid identities.")
    return result


def _bag(slots: list[dict[str, Any]]) -> dict[tuple[str, int], int]:
    result: dict[tuple[str, int], int] = {}
    for slot in slots:
        stack = slot["item"]
        if stack is None:
            continue
        identity = _identity(stack)
        key = _key(identity)
        result[key] = result.get(key, 0) + _quantity(stack.get("count"))
    return {key: value for key, value in result.items() if value}


def _owner(observation: dict[str, Any], position: dict[str, Any]) -> dict[str, Any]:
    gt = observation.get("gregtech")
    _require(observation.get("source") == "integrated_server"
             and all(type(observation.get(key)) is type(value) and observation.get(key) == value for key, value in position.items())
             and isinstance(gt, dict) and type(gt.get("metaTileId")) is int
             and isinstance(gt.get("metaClass"), str) and isinstance(observation.get("registryName"), str),
             "multi_owner_unavailable", "A native quantity proof requires the exact actual integrated-server owner.")
    return {**position, "registryName": observation["registryName"], "metaTileId": gt["metaTileId"], "metaClass": gt["metaClass"]}


class _MultiRecipeTask(_RecipeTask):
    def __init__(self, *args: Any, **kwargs: Any):
        super().__init__(*args, **kwargs)
        self.active_declared_slot: int | None = None
        self.multi_input_baseline: dict[tuple[str, int], int] = {}
        self.multi_output_baseline: dict[tuple[str, int], int] = {}
        self.multi_output_owner_baseline: dict[tuple[str, int], int] = {}
        self.multi_output_owner: dict[str, Any] | None = None
        self.fed_items: dict[tuple[str, int], int] = {}
        self.item_feed_bindings: list[dict[str, Any]] = []
        self.retrievals: list[dict[str, Any]] = []
        from .fluid_recipe_tasks import FluidRecipeResources
        self.fluid_resources = FluidRecipeResources(self)

    def item_bag(self, slots: list[dict[str, Any]], *, player: bool = False) -> dict[tuple[str, int], int]:
        if player and getattr(self, "recipe_context_inventory", None) is not None:
            slots = self.recipe_context_player_slots(slots)
        return _bag([slot for slot in slots if not player or slot["index"] not in self.fluid_resources.player_indices])

    async def read_multi_output_baseline(self, expected: dict[tuple[str, int], int], phase: str) -> dict[str, Any]:
        self.phase = phase
        observation = await self.call("world.inspect", self.position("output"))
        self.validate_multi_output_baseline(observation, expected, phase)
        return observation

    def validate_multi_output_baseline(self, observation: dict[str, Any], expected: dict[tuple[str, int], int], phase: str) -> None:
        _require(_owner(observation, self.position("output")) == self.multi_output_owner,
                 "multi_output_owner_changed", "The complete output baseline must still belong to the original exact actual owner before feed/power.")
        self.validate_single_tanks(observation)
        _require(_bag(inventory_slots(observation)) == expected, "multi_output_baseline_changed",
                 "The complete actual output inventory drifted from its retained baseline plus finite accepted feeds; later feed/power is blocked without replay.")
        self.phases.append({"phase": phase, "status": "completed", "completeOutputInventoryChecked": True,
                            "scope": "Original actual owner and complete item quantities before the next native feed or power action; shared named tanks retain their separate conservation checks."})

    def validate_single_tanks(self, observation: dict[str, Any]) -> None:
        if self.fluid_resources.enabled:
            self.fluid_resources.validate_tanks(observation)
        else:
            super().validate_single_tanks(observation)

    def validate_registered_ports(self, machine: dict[str, Any]) -> None:
        if self.fluid_resources.enabled and not self.single_inventory:
            self.fluid_resources.validate_registered_ports(machine)
        else:
            super().validate_registered_ports(machine)

    def backend_query(self) -> dict[str, Any]:
        if not self.fluid_resources.enabled:
            return super().backend_query()
        query = {**self.position("machine"), "mode": "backend_match", "inputs": self.args["backendInputs"]}
        if "outputItem" in self.args:
            query["output"] = {"source": "registry", **self.args["outputItem"]}
        if self.args.get("fluidContainerInputs"):
            query["fluidInputs"] = [{key: entry[key] for key in ("fluid", "amount")} for entry in self.args["fluidContainerInputs"]]
        return query

    def validate_backend_resources(self, data: dict[str, Any]) -> None:
        if self.fluid_resources.enabled:
            self.fluid_resources.validate_backend_resources(data)
        else:
            super().validate_backend_resources(data)

    async def failure_resources(self) -> dict[str, Any] | None:
        return await self.fluid_resources.failure_diagnostics() if self.fluid_resources.enabled else None

    def protected_player_indices(self) -> set[int]:
        return super().protected_player_indices() | self.fluid_resources.player_indices

    async def validate_protected_sources(self, player: dict[str, Any], gui: dict[str, Any] | None = None) -> None:
        await self.fluid_resources.validate_player(player)
        if gui is not None:
            for row in self.fluid_resources.rows:
                self.fluid_resources.source_binding(gui, row["playerInventoryIndex"], row["currentStack"])

    def parse_candidate(self, candidate: dict[str, Any]) -> MultiRecipe:
        return supported_multi_recipe(candidate, self.args)

    def backend_required_inputs(self, selected: MultiRecipe) -> list[tuple[dict[str, Any], int]]:
        return list(selected.consumed_items) + [(identity, 1) for identity in selected.catalysts]

    async def select_recipe(self) -> MultiRecipe:
        selected = await super().select_recipe()
        self.recipe_decision.update(consumedItems=[{**item, "perExecution": count} for item, count in selected.consumed_items],
                                    outputItems=[{**item, "perExecution": count} for item, count in selected.output_items],
                                    catalysts=list(selected.catalysts),
                                    fluidInputs=[{"fluid": fluid, "amount": amount} for fluid, amount in selected.fluid_inputs],
                                    fluidOutputs=[{"fluid": fluid, "amount": amount} for fluid, amount in selected.fluid_outputs])
        return selected

    def validate_auto_input_inventory(self, server: dict[str, Any]) -> list[dict[str, Any]]:
        self.bind_input_inventory_owner(server)
        slots = inventory_slots(server)
        self.validate_single_tanks(server)
        expected = dict(self.multi_input_baseline)
        for key, count in self.fed_items.items():
            expected[key] = expected.get(key, 0) + count
        _require(_bag(slots) == {key: value for key, value in expected.items() if value},
                 "multi_input_inventory_changed", "Complete real input quantities must equal the retained baseline plus the accepted finite feeds.")
        if self.configured_ghost:
            self.validate_configured_ghost_inventory(slots)
        return slots

    def resolve_input_gui_slot(self, gui: dict[str, Any], server_slots: list[dict[str, Any]], *, exclude_catalyst: bool = True) -> None:
        indexed = {slot["index"]: slot["item"] for slot in server_slots}
        bindings = self.current_machine_gui_bindings(gui)
        eligible = [slot for slot in bindings if slot.get("machineInventoryRole") == "consumed_input"
                    and slot.get("consumedInput") is True and slot["machineInventoryIndex"] in indexed
                    and indexed[slot["machineInventoryIndex"]] is None and slot.get("item") is None
                    and (self.active_declared_slot is None or slot["slot"] == self.active_declared_slot)]
        _require(bool(eligible), "input_gui_binding_unavailable",
                 "Each multi-item feed needs a fresh empty publicly bound consumed-input destination.")
        selected = min(eligible, key=lambda slot: slot["machineInventoryIndex"])
        index = selected["machineInventoryIndex"]
        _require(sum(slot["machineInventoryIndex"] == index for slot in bindings) == 1,
                 "input_gui_binding_ambiguous", "The actual consumed-input entry must map to exactly one native slot.")
        self.input_gui_slot = selected["slot"]
        self.input_gui_binding = {"guiSlot": selected["slot"], "inventoryIndex": index,
            "owner": self.input_inventory_owner, "binding": MACHINE_SLOT_BINDING, "role": "consumed_input",
            "selection": "caller_declared_consumed_input" if self.active_declared_slot is not None else "lowest_empty_consumed_input_index"}

    async def refresh_auto_input_gui(self, gui: dict[str, Any]) -> dict[str, Any]:
        server = await self.call("world.inspect", self.position("input"))
        slots = self.validate_auto_input_inventory(server)
        self.resolve_input_gui_slot(gui, slots)
        return gui

    def validate_declared_single_slots(self, gui: dict[str, Any]) -> None:
        self.validate_auto_input_binding(gui)
        proof = {"guiSlot": self.input_gui_slot, "inventoryIndex": self.input_gui_binding["inventoryIndex"]}
        if proof not in self.declared_gui_slots:
            self.declared_gui_slots.append(proof)

    async def feed_item(self, identity: dict[str, Any], amount: int, ordinal: int, total: int, *, catalyst: bool = False) -> None:
        self.active_declared_slot = _declared_slot(self.args, "catalystGuiSlots" if catalyst else "inputGuiSlots",
                                                  ordinal, total, "catalystGuiSlot" if catalyst else "inputGuiSlot")
        gui = await self.open_gui("input")
        await self.refresh_auto_input_gui(gui)
        await self.transfer(identity, amount, self.input_gui_slot, "material", close_after=False)
        key = _key(identity)
        self.fed_items[key] = self.fed_items.get(key, 0) + amount
        self.item_feed_bindings.append({"item": identity, "count": amount, "catalyst": catalyst, **self.input_gui_binding})
        await self.validate_fresh_feed()

    async def validate_fresh_feed(self) -> None:
        self.validate_auto_input_inventory(await self.call("world.inspect", self.position("input")))

    def output_source(self, gui: dict[str, Any], owner: dict[str, Any], server: list[dict[str, Any]],
                      identity: dict[str, Any], ordinal: int, *, catalyst: bool = False) -> dict[str, Any]:
        indexed = {slot["index"]: slot["item"] for slot in server}
        declared = None if catalyst else _declared_slot(self.args, "outputGuiSlots", ordinal, len(self.recipe.output_items), "outputGuiSlot")
        candidates = []
        all_slots = inventory_slots(gui, "")
        for slot in all_slots:
            actual_owner = slot.get("machineInventoryOwner")
            if slot.get("machineInventoryBindingVerified") is True and slot.get("machineInventoryBinding") == MACHINE_SLOT_BINDING \
                    and slot.get("playerInventoryBindingVerified") is False and isinstance(actual_owner, dict) \
                    and all(type(actual_owner.get(key)) is type(value) and actual_owner.get(key) == value for key, value in owner.items()):
                index = slot.get("machineInventoryIndex")
                _require(type(index) is int and index in indexed, "multi_output_binding_unavailable",
                         "Public output slot indices must belong to the complete real output inventory.")
                if _same(slot.get("item"), identity) and slot["item"] == indexed[index] \
                        and (declared is None or slot["slot"] == declared):
                    _require(sum(other.get("machineInventoryBindingVerified") is True
                                 and other.get("machineInventoryIndex") == index for other in all_slots) == 1,
                             "multi_output_binding_ambiguous", "The actual product stack must map to one native source slot.")
                    candidates.append(slot)
        _require(len(candidates) == 1, "multi_output_binding_unavailable",
                 "Native retrieval needs one current public actual owner/index product source; no output offset is guessed.")
        return candidates[0]

    async def retrieve_product(self, identity: dict[str, Any], amount: int, ordinal: int, *, catalyst: bool = False) -> None:
        target = "input" if catalyst else "output"
        self.phase = ("recover_catalyst_" if catalyst else "retrieve_product_") + str(ordinal + 1)
        phase = await self.read_owner_phase([self.position(target)])
        before_server = phase[0][0] if phase is not None else await self.call("world.inspect", self.position(target))
        owner = _owner(before_server, self.position(target))
        _require(owner == (self.input_inventory_owner if catalyst else self.multi_output_owner), "multi_output_owner_changed", "Native retrieval must retain the original exact real resource owner.")
        self.validate_single_tanks(before_server)
        before_owner_slots = inventory_slots(before_server)
        before_player = phase[1] if phase is not None else await self.call("inventory.inspect", {})
        before_player_slots = inventory_slots(before_player, "")
        _require(before_player.get("cursorItem") is None, "carried_stack_not_empty", "Retrieval begins with an observed empty real cursor.")
        gui = await self.open_gui(target)
        source = self.output_source(gui, owner, before_owner_slots, identity, ordinal, catalyst=catalyst)
        count = _quantity(source["item"].get("count"))
        _require(count >= amount and (count == amount or amount <= MAX_TRANSFER_COUNT),
                 "multi_retrieval_budget", "A finite product is retrieved as one complete stack or at most 16 ordinary placements.")
        player_indexed = {slot["index"]: slot["item"] for slot in before_player_slots}
        destinations = [slot for slot in inventory_slots(gui, "") if slot.get("playerInventoryBindingVerified") is True
                        and slot.get("inventoryBinding") == "public_isHere_unique_actual_player_inventory_slot"
                        and slot.get("inventorySource") == "current_player_inventory"
                        and type(slot.get("playerInventoryIndex")) is int and 9 <= slot["playerInventoryIndex"] <= 35
                        and slot["playerInventoryIndex"] not in self.protected_player_indices()
                        and slot["playerInventoryIndex"] in player_indexed and player_indexed[slot["playerInventoryIndex"]] is None
                        and slot.get("item") is None]
        _require(bool(destinations), "multi_retrieval_capacity_unavailable",
                 "Every product needs a publicly bound empty actual normal storage destination.")
        destination = min(destinations, key=lambda slot: slot["playerInventoryIndex"])
        _require(sum(slot.get("playerInventoryBindingVerified") is True
                     and slot.get("playerInventoryIndex") == destination["playerInventoryIndex"] for slot in gui["slots"]) == 1,
                 "multi_retrieval_binding_ambiguous", "The empty actual player destination must have one native GUI binding.")
        native = plan_whole_stack(self, gui, source, destination, identity, amount, "machine_to_player", owner)
        if getattr(self, "recipe_context_inventory", None) is not None:
            self.queue_recipe_context_guard()
        if native is not None:
            after_server, after_player = await execute_whole_stack(self, native)
        else:
            source_proof = {"slot": source["slot"], "machineInventoryBindingVerified": True,
                            "machineInventoryBinding": MACHINE_SLOT_BINDING, "machineInventoryIndex": source["machineInventoryIndex"],
                            "playerInventoryBindingVerified": False, **_match(identity, count),
                            **{"machineInventoryOwner." + key: value for key, value in owner.items()}}
            destination_proof = {"slot": destination["slot"], "playerInventoryBindingVerified": True,
                                 "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot",
                                 "inventorySource": "current_player_inventory",
                                 "playerInventoryIndex": destination["playerInventoryIndex"], "item": None}
            steps = [_read("before", "mcb_gui", fields=["screenId", "windowId", "slots", "totalSlots", "truncated"]),
                     _assert("screen", "before", "screenId", gui["screenId"]), _assert("window", "before", "windowId", gui["windowId"]),
                     _assert("coverage", "before", "truncated", False),
                     _assert("source", "before", "slots", source_proof, "matches_one"),
                     _assert("destination", "before", "slots", destination_proof, "matches_one"),
                     _action("pickup_product", "mcb_slot_click", {"windowId": gui["windowId"], "slot": source["slot"], "mode": 0, "button": 0}),
                     _wait("carried", "mcb_inventory", {}, "cursorItem.count", count, fields=["cursorItem"]),
                     _assert("carried_registry", "carried", "cursorItem.registryName", identity["registryName"]),
                     _assert("carried_metadata", "carried", "cursorItem.metadata", identity["metadata"]),
                     _assert("carried_untagged", "carried", "cursorItem.hasTag", False)]
            if count == amount:
                steps.append(_action("place_product", "mcb_slot_click", {"windowId": gui["windowId"], "slot": destination["slot"], "mode": 0, "button": 0}))
            else:
                steps.extend(_action("place_product_" + str(index), "mcb_slot_click",
                                     {"windowId": gui["windowId"], "slot": destination["slot"], "mode": 0, "button": 1}) for index in range(amount))
                steps.append(_action("return_baseline", "mcb_slot_click", {"windowId": gui["windowId"], "slot": source["slot"], "mode": 0, "button": 0}))
            steps.append(_wait("cursor_empty", "mcb_inventory", {}, "cursorItem", None))
            await self.workflow(self.phase, steps)
            phase = await self.read_owner_phase([self.position(target)])
            after_server = phase[0][0] if phase is not None else await self.call("world.inspect", self.position(target))
            after_player = phase[1] if phase is not None else await self.call("inventory.inspect", {})
        _require(_owner(after_server, self.position(target)) == owner and after_player.get("cursorItem") is None,
                 "multi_retrieval_owner_or_cursor_changed", "Retrieval must preserve the real owner and end with an empty actual cursor.")
        self.validate_single_tanks(after_server)
        owner_expected, player_expected = self.item_bag(before_owner_slots), self.item_bag(before_player_slots, player=True)
        key = _key(identity)
        owner_expected[key] = owner_expected.get(key, 0) - amount
        player_expected[key] = player_expected.get(key, 0) + amount
        _require(_bag(inventory_slots(after_server)) == {key: value for key, value in owner_expected.items() if value}
                 and self.item_bag(inventory_slots(after_player, ""), player=True) == {key: value for key, value in player_expected.items() if value},
                 "multi_retrieval_conservation_failed", "Complete actual owner and player inventories must conserve every finite item across native retrieval.")
        await self.fluid_resources.validate_player(after_player)
        if getattr(self, "recipe_context_inventory", None) is not None:
            await self.account_recipe_context_item(destination["playerInventoryIndex"], identity, amount, after_player,
                "recover_catalyst" if catalyst else "retrieve_product")
        self.retrievals.append({"item": identity, "count": amount, "owner": owner, "resource": "physical_catalyst" if catalyst else "product",
                                "sourceGuiSlot": source["slot"], "destinationPlayerIndex": destination["playerInventoryIndex"],
                                "actualOwnerDecrease": amount, "actualPlayerIncrease": amount, "cursorEmpty": True})

    async def execute(self) -> dict[str, Any]:
        self.fluid_resources.validate_declarations()
        _require(not self.provider_cache, "multi_provider_scope_unsupported", "The public multi-item path accepts complete real inventory quantities.")
        _require(not self.configured_ghost or self.single_inventory, "configured_ghost_machine_required", "Configured ghost mode keeps its existing common single-machine scope.")
        _require(not self.configured_ghost or "catalystGuiSlot" not in self.args and "catalystGuiSlots" not in self.args,
                 "configured_ghost_physical_destination_forbidden", "A configured ghost must never receive a physical slot transfer.")
        await self.preflight()
        self.timing_stage_set("recipeDecision")
        self.recipe = recipe = await self.select_recipe()
        await self.fluid_resources.prepare()
        self.timing_stage_set("preparation")
        executions = self.args.get("executions", 1)
        # Ghost-switch preflight can perform intervening native work. Its player
        # preparation keeps the original later read instead of crossing that boundary.
        phase = None if self.configured_ghost else await self.read_owner_phase([self.position("input"), self.position("output")])
        before_input = phase[0][0] if phase is not None else await self.call("world.inspect", self.position("input"))
        before_output = phase[0][1] if phase is not None else before_input if self.single_inventory else await self.call("world.inspect", self.position("output"))
        self.bind_input_inventory_owner(before_input)
        self.multi_output_owner = _owner(before_output, self.position("output"))
        self.validate_single_tanks(before_input)
        self.validate_single_tanks(before_output)
        input_slots, output_slots = inventory_slots(before_input), inventory_slots(before_output)
        if self.single_inventory:
            self.machine_inventory_indices = {slot["index"] for slot in input_slots}
            self.validate_single_tanks(before_input)
        products = {_key(identity): count for identity, count in recipe.output_items}
        catalyst_keys = {_key(identity) for identity in recipe.catalysts}
        self.multi_output_owner_baseline = _bag(output_slots)
        self.multi_output_baseline = {key: count for key, count in self.multi_output_owner_baseline.items() if key in products}
        _require(set(self.multi_output_owner_baseline) <= set(products) | (catalyst_keys if self.single_inventory else set()), "multi_output_not_isolated",
                 "The complete output inventory must contain only declared product baselines.")
        self.multi_input_baseline = _bag(input_slots)
        allowed = catalyst_keys | (set(products) if self.single_inventory else set())
        _require(set(self.multi_input_baseline) <= allowed, "input_not_isolated", "Every real input baseline must be a retained catalyst or shared product baseline.")
        for identity in recipe.catalysts:
            _require(item_count(input_slots, identity) in (0, 1), "catalyst_quantity_unsupported", "Each physical catalyst is absent or exactly one actual retained item.")
        if self.configured_ghost:
            machine = await self.inspect_registered_machine()
            old_circuits = [slot for slot in input_slots if slot["item"] is not None
                            and slot["item"].get("registryName") == INTEGRATED_CIRCUIT and not _same(slot["item"], recipe.catalyst)]
            if old_circuits:
                await self.preflight_ghost_switch(machine, before_input)
            if any(_same(slot.get("item"), recipe.catalyst) for slot in input_slots):
                self.validate_configured_ghost_inventory(input_slots)
        physical_missing = [] if self.configured_ghost else [identity for identity in recipe.catalysts if item_count(input_slots, identity) == 0]
        required = [(identity, count * executions) for identity, count in recipe.consumed_items] + [(identity, 1) for identity in physical_missing]
        # Prove all quantity/capacity budgets before any inventory clear or supply.
        initial_player = phase[1] if phase is not None else await self.call("inventory.inspect", {})
        initial_player_slots = inventory_slots(initial_player, "")
        self.item_bag(initial_player_slots, player=True)
        _require(initial_player.get("cursorItem") is None, "carried_stack_not_empty", "Finite multi-item preparation needs an actual empty cursor.")
        for identity, count in recipe.output_items:
            _require(self.multi_output_baseline.get(_key(identity), 0) == 0 or count <= MAX_TRANSFER_COUNT,
                     "multi_retrieval_budget", "An existing product baseline requires a bounded native partial retrieval.")
        for identity, count in recipe.consumed_items:
            existing = item_count(self.ordinary_source_slots(initial_player_slots), identity)
            _require(count <= MAX_TRANSFER_COUNT or executions == 1 and (
                self.inventory_preparation_mode == "clear"
                or existing == count or self.args.get("generateMaterials", False) and existing < count),
                "exact_transfer_budget", "Larger item feeds require one exact whole source stack; partial placement remains bounded to 16.")
        if self.inventory_preparation_mode == "clear":
            await self.clear_player_inventory()
        missing = await self.ensure_materials(required, grant=False)
        self.single_feed_grants = await self.prove_single_feed_grants(required, missing)
        await self.prepare_empty_hand(missing, reserve=1 if self.single_feed_grants else None)
        await self.open_gui("input")
        await self.ensure_materials(required)
        prepared_inventory = await self.call("inventory.inspect", {})
        inventory_prepared = self.item_bag(inventory_slots(prepared_inventory, ""), player=True)
        await self.fluid_resources.validate_player(prepared_inventory)
        if self.configured_ghost:
            await self.inspect_configured_ghost(await self.open_gui("input"), "configured_ghost_preflight")
        for ordinal, identity in enumerate(recipe.catalysts):
            if not self.configured_ghost and identity in physical_missing:
                await self.feed_item(identity, 1, ordinal, len(recipe.catalysts), catalyst=True)
        # Newly fed physical catalysts become the stable retained input baseline.
        for identity in physical_missing:
            self.multi_input_baseline[_key(identity)] = 1
            if self.single_inventory:
                self.multi_output_owner_baseline[_key(identity)] = 1
        self.fed_items = {}
        self.timing_stage_set("executionAcceptance")
        for execution in range(executions):
            await self.read_multi_output_baseline(self.multi_output_owner_baseline,
                "multi_output_baseline_before_feed_" + str(execution + 1))
            await self.fluid_resources.feed()
            for ordinal, (identity, amount) in enumerate(recipe.consumed_items):
                await self.feed_item(identity, amount, ordinal, len(recipe.consumed_items))
            self.validate_registered_ports(await self.inspect_registered_machine())
            gui = await self.open_gui("machine")
            controls = await self.control_widgets(gui["screenId"], semantic="power_switch", reason="power_widget_coverage_incomplete")
            widget = power_widget(controls, False, allow_public_semantic=self.single_api_verified)
            if self.configured_ghost:
                await self.inspect_configured_ghost(gui, "configured_ghost_before_power_" + str(execution + 1))
            self.phase = "execute_" + str(execution + 1)
            steps = [_read("current_machine", "mcb_machine_inspect", self.position("machine")),
                     *[_assert("machine_" + key, "current_machine", key, value) for key, value in {
                         **self.position("machine"), "metaTileId": self.machine_id, "metaClass": self.machine_class,
                         "state.active": False, "state.allowedToWork": False}.items()],
                     _action("power_on", "mcb_gui_widget_action", {"screenId": gui["screenId"], "widgetId": widget["widgetId"], "action": "set_selected", "selected": 1})]
            if self.single_inventory:
                steps.insert(-1, _assert("progress_idle", "current_machine", "state.progressTicks", 0))
                steps.insert(-1, _assert("max_progress_idle", "current_machine", "state.maxProgressTicks", 0))
            else:
                steps.insert(-1, _assert("machine_formed", "current_machine", "processing.structureFormed", True))
            steps.insert(-1, _read("baseline", "mcb_world_inspect", self.position("output"), ["inventory"]))
            power_expected = dict(self.multi_output_owner_baseline)
            if self.single_inventory:
                power_expected.update(self.fed_items)
            from . import native_wait
            self.phase = "multi_output_baseline_before_power_" + str(execution + 1)
            prepared = await native_wait.prepare(self, power_expected, self.phase)
            if prepared is None:
                native_plan = None
                power_baseline = await self.read_multi_output_baseline(power_expected, self.phase)
            else:
                native_plan, power_baseline = prepared
            steps.insert(-1, inventory_baseline_assertion("baseline", power_baseline))
            if native_plan is None:
                for ordinal, (identity, count) in enumerate(recipe.output_items):
                    step = _wait("product_" + str(ordinal), "mcb_world_inspect", self.position("output"), "inventory.slots",
                                 count, operator="item_count_delta", fields=["inventory"])
                    step["condition"].update(match=_match(identity), baseline={"step": "baseline", "path": "inventory.slots"})
                    steps.append(step)
                steps.extend(self.fluid_resources.output_wait_steps())
            else:
                steps.append(native_wait.product_step(self, native_plan, power_baseline))
            steps.extend([_action("power_off", "mcb_gui_widget_action", {"screenId": gui["screenId"], "widgetId": widget["widgetId"], "action": "set_selected", "selected": 0}),
                          *([native_wait.stop_step(self, native_plan, power_baseline)] if native_plan is not None else [
                          _wait("stopped", "mcb_machine_inspect", self.position("machine"), "state.allowedToWork", False,
                                fields=["metaTileId", "metaClass", "state"]),
                          _wait("inactive", "mcb_machine_inspect", self.position("machine"), "state.active", False,
                                fields=["metaTileId", "metaClass", "state"])]),
                          _action("close", "mcb_close_gui", {})])
            report = await self.workflow("execute_" + str(execution + 1), steps)
            self.opened_gui = None
            _require(_bag(inventory_slots({"inventory": self.value(report, "baseline", "inventory")})) == power_expected,
                     "multi_output_baseline_changed", "The same fixed complete output baseline must precede the native power action.")
            if self.configured_ghost:
                await self.inspect_configured_ghost(await self.open_gui("machine"), "configured_ghost_acceptance_" + str(execution + 1))
            machine = await self.inspect_registered_machine()
            self.validate_registered_ports(machine)
            phase = await self.read_owner_phase([self.position("input"), self.position("output"),
                *[self.fluid_resources.position(row) for row in self.fluid_resources.rows]])
            after_input = phase[0][0] if phase is not None else await self.call("world.inspect", self.position("input"))
            after_output = phase[0][1] if phase is not None else after_input if self.single_inventory else await self.call("world.inspect", self.position("output"))
            self.bind_input_inventory_owner(after_input)
            _require(_owner(after_output, self.position("output")) == self.multi_output_owner,
                     "multi_output_owner_changed", "Every deterministic output must belong to the original exact actual output owner.")
            await self.fluid_resources.accept_recipe(phase[0][2:] if phase is not None else None)
            self.validate_single_tanks(after_input)
            self.validate_single_tanks(after_output)
            output_expected = dict(self.multi_output_owner_baseline)
            for identity, count in recipe.output_items:
                output_expected[_key(identity)] = output_expected.get(_key(identity), 0) + count
            _require(_bag(inventory_slots(after_output)) == output_expected, "multi_output_delta_mismatch",
                     "Every deterministic product and the full actual output inventory must equal the fixed baseline plus exactly one execution.")
            input_expected = dict(self.multi_input_baseline)
            if self.single_inventory:
                input_expected.update(output_expected)
            _require(_bag(inventory_slots(after_input)) == {key: value for key, value in input_expected.items() if value},
                     "input_or_catalyst_acceptance_failed", "Every real consumable must be fully consumed and every physical catalyst retained with no foreign items.")
            if self.single_inventory:
                self.validate_single_tanks(after_input)
            if self.configured_ghost:
                self.validate_configured_ghost_inventory(inventory_slots(after_input))
            self.fed_items = {}
            self.power_dispatched = False
            for ordinal, (identity, count) in enumerate(recipe.output_items):
                await self.retrieve_product(identity, count, ordinal)
            await self.fluid_resources.recover()
            _require(_bag(inventory_slots(await self.call("world.inspect", self.position("output")))) == self.multi_output_owner_baseline,
                     "multi_output_baseline_not_restored", "Native retrieval must leave the entire original real output baseline intact.")
            self.executions_completed = execution + 1
        recovery = [] if self.configured_ghost else list(recipe.catalysts) if self.args.get("recoverExistingCatalysts", False) else physical_missing
        for ordinal, identity in enumerate(recovery):
            await self.retrieve_product(identity, 1, ordinal, catalyst=True)
            self.multi_input_baseline.pop(_key(identity), None)
            if self.single_inventory:
                self.multi_output_owner_baseline.pop(_key(identity), None)
        # No successful report without real player conservation of every feed,
        # catalyst placement and all retrieved product quantities.
        player_expected = dict(inventory_prepared)
        for identity, count in recipe.consumed_items:
            player_expected[_key(identity)] = player_expected.get(_key(identity), 0) - count * executions
        for identity in physical_missing:
            player_expected[_key(identity)] = player_expected.get(_key(identity), 0) - 1
        for identity in recovery:
            player_expected[_key(identity)] = player_expected.get(_key(identity), 0) + 1
        for identity, count in recipe.output_items:
            player_expected[_key(identity)] = player_expected.get(_key(identity), 0) + count * executions
        # Only the first final fluid owner precedes all GUI actions. Later rows
        # are still inspected after each preceding native GUI/tooltip phase.
        phase = await self.read_owner_phase([self.position("input"),
            *[self.fluid_resources.position(row) for row in self.fluid_resources.rows[:1]]])
        final_player = phase[1] if phase is not None else await self.call("inventory.inspect", {})
        _require(final_player.get("cursorItem") is None
                 and self.item_bag(inventory_slots(final_player, ""), player=True) == {key: value for key, value in player_expected.items() if value},
                 "multi_player_conservation_failed", "The complete final actual player inventory must conserve all preparation, consumption and native product retrieval.")
        if phase is None:
            await self.validate_fresh_feed()
            await self.fluid_resources.validate_final()
        else:
            self.validate_auto_input_inventory(phase[0][0])
            await self.fluid_resources.validate_final(player=final_player,
                first_owner=phase[0][1] if self.fluid_resources.rows else None)
        self.validate_registered_ports(await self.inspect_registered_machine())
        acceptance = {"consumedItems": [{**identity, "count": count * executions} for identity, count in recipe.consumed_items],
                      "outputDeltas": [{**identity, "count": count * executions} for identity, count in recipe.output_items],
                      "retrievedItems": [{**identity, "count": count * executions} for identity, count in recipe.output_items],
                      "catalystsRetained": [identity for identity in recipe.catalysts if identity not in recovery], "controllerStopped": True,
                      "recoveredCatalysts": recovery, "preservedCatalystBaseline": [identity for identity in recipe.catalysts if identity not in recovery],
                      "fluidResources": self.fluid_resources.acceptance(), "completeRecovery": True,
                      "registeredCoreStopCoverage": self.core_stop_coverage,
                      "allRegisteredCoresStopClaimed": self.core_stop_coverage == "public_registered_modules",
                      "playerInventoryConserved": True, "finalCursorEmpty": True,
                      "source": "Complete real owner/player quantities after finite native GUI feeds, public power, controller/core stop and native product retrieval."}
        self.recipe_acceptance = acceptance
        if self.args.get("restoreInventory", False) and self.borrowed_inventory:
            await self.restore_borrowed_inventory()
        else:
            await self.verify_retained_inventory()
        return {"status": "completed", "available": True, "nonAtomic": True, "rolledBack": False,
                "recipeMode": "public_multi", "selection": "first_supported", "uniqueRecipeClaimed": False,
                "executions": executions, "recipe": self.recipe_decision, "acceptance": acceptance,
                "nativeFeedBindings": self.item_feed_bindings, "nativeRetrievals": self.retrievals}
