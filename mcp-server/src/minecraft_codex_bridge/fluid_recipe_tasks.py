"""Explicit actual container stacks and ordinary public native fluid-slot controls."""

from __future__ import annotations

import copy
from typing import Any

import anyio

from .recipe_tasks import (_require, _read, _assert, _action, _wait, inventory_slots,
                           registered_components)


PLAYER_BINDING = "public_isHere_unique_actual_player_inventory_slot"
FLUID_SLOT_CLASS = "com.cleanroommc.modularui.widgets.slot.FluidSlot"
NAMED_TANK_BINDING = "public_basic_machine_named_tank_identity"
NAMED_TANK_API = "gregtech.api.metatileentity.implementations.MTEBasicMachine.getFluidTank/getFluidOutputTank"
NAMED_TANK_GETTERS = {"input": "getFluidTank", "output": "getFluidOutputTank"}
EMPTY_CAPACITY_REASON = "registered_empty_capacity_requires_fluid_identity"
REGISTRY_CAPACITY_QUERY_SOURCE = "public_fluid_container_registry_getContainerCapacity"


def public_stack(stack: Any) -> dict[str, Any]:
    _require(isinstance(stack, dict) and isinstance(stack.get("registryName"), str)
             and type(stack.get("metadata")) is int and 0 <= stack["metadata"] <= 32_766
             and type(stack.get("count")) is int and 1 <= stack["count"] <= 64
             and type(stack.get("hasTag")) is bool,
             "fluid_container_stack_unavailable", "An actual container needs typed public item fields and one finite complete stack.")
    return {key: stack[key] for key in ("registryName", "metadata", "count", "hasTag")}


def unknown_registry_empty_capacity(value: dict[str, Any]) -> bool:
    capacity = value.get("capacity")
    return (value.get("source") == "public_fluid_container_registry" and value.get("empty") is True
            and "fluid" in value and value["fluid"] is None and isinstance(capacity, dict)
            and capacity.get("available") is False and "value" not in capacity
            and capacity.get("reason") == EMPTY_CAPACITY_REASON)


def container_semantics(tooltip: dict[str, Any], stack: dict[str, Any], *,
                        allow_unknown_empty_capacity: bool = False) -> dict[str, Any]:
    _require(public_stack(tooltip.get("item")) == public_stack(stack), "fluid_container_tooltip_stack_changed",
             "The current native slot tooltip must describe the same exact observed supplied stack.")
    value = tooltip.get("fluidContainer")
    _require(isinstance(value, dict) and type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1
             and value.get("source") in ("public_fluid_container_registry", "public_container_item_api")
             and value.get("scope") == "observed_stack_copy_public_api_read"
             and value.get("itemHasTag") is stack["hasTag"] and type(value.get("probeMutatedCopy")) is bool
             and value.get("available") is True and type(value.get("empty")) is bool,
             "fluid_container_semantics_unavailable", "Only the current bounded-copy public container read may establish actual fluid semantics.")
    capacity = value.get("capacity")
    _require(isinstance(capacity, dict) and capacity.get("available") is True
             and type(capacity.get("value")) is int and 1 <= capacity["value"] <= 2_147_483_647
             or allow_unknown_empty_capacity and unknown_registry_empty_capacity(value),
             "fluid_container_capacity_unavailable", "The actual public container must expose its positive finite capacity.")
    fluid = value.get("fluid")
    if value["empty"]:
        _require("fluid" in value and fluid is None, "fluid_container_semantics_unavailable",
                 "An empty actual container must explicitly publish a null fluid.")
    else:
        _require(isinstance(fluid, dict) and isinstance(fluid.get("fluidName"), str)
                 and 1 <= len(fluid["fluidName"]) <= 128 and fluid.get("hasTag") is False
                 and type(fluid.get("amount")) is int and 1 <= fluid["amount"] <= capacity["value"],
                 "fluid_container_semantics_unavailable", "The actual supplied fluid must have an exact untagged runtime name and positive amount.")
    return copy.deepcopy(value)


def tank_observation(observation: dict[str, Any]) -> dict[str, Any]:
    fluids = observation.get("fluids")
    _require(isinstance(fluids, dict) and fluids.get("truncated") is False
             and type(fluids.get("totalTanks")) is int and fluids["totalTanks"] == 1
             and isinstance(fluids.get("tanks"), list) and len(fluids["tanks"]) == 1,
             "fluid_tank_coverage_unavailable", "Each declared actual fluid owner currently needs exactly one complete public tank.")
    tank = fluids["tanks"][0]
    _require(isinstance(tank, dict) and type(tank.get("index")) is int and tank["index"] == 0
             and type(tank.get("capacity")) is int and 1 <= tank["capacity"] <= 2_147_483_647,
             "fluid_tank_identity_unavailable", "The complete actual tank needs one exact zero index and a positive typed capacity.")
    amount = tank.get("amount", 0)
    fluid = tank.get("fluid")
    _require(type(amount) is int and 0 <= amount <= tank["capacity"]
             and (amount == 0 and fluid is None or amount > 0 and isinstance(fluid, str) and 1 <= len(fluid) <= 128)
             and ("hasTag" not in tank or tank["hasTag"] is False),
             "fluid_tank_state_unavailable", "Actual tank fluid and amount must be complete, typed and internally consistent.")
    return {"index": 0, "capacity": tank["capacity"], "fluid": fluid, "amount": amount}


def named_tanks(observation: dict[str, Any], position: dict[str, Any]) -> dict[str, dict[str, Any]]:
    from .multi_recipe_tasks import _owner
    owner = _owner(observation, position)
    fluids = observation.get("fluids")
    _require(isinstance(fluids, dict), "named_fluid_tank_coverage_unavailable", "A complete public named tank observation is required.")
    value = fluids.get("namedTanks")
    _require(isinstance(value, dict) and type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1
             and value.get("available") is True and value.get("source") == "integrated_server"
             and value.get("scope") == "complete_actual_owner_named_tanks_public_api_read"
             and value.get("binding") == NAMED_TANK_BINDING and value.get("publicApi") == NAMED_TANK_API
             and value.get("owner") == owner and isinstance(value.get("owner"), dict)
             and all(type(value["owner"].get(key)) is type(expected) for key, expected in owner.items())
             and type(value.get("totalTanks")) is int and value["totalTanks"] == 2 and value.get("truncated") is False
             and isinstance(value.get("tanks"), list) and len(value["tanks"]) == 2,
             "named_fluid_tank_coverage_unavailable", "Shared fluid owners need the complete typed public named input/output pair bound to this exact actual owner.")
    result = {}
    for tank in value["tanks"]:
        _require(isinstance(tank, dict) and tank.get("role") in NAMED_TANK_GETTERS and tank["role"] not in result
                 and tank.get("publicGetter") == NAMED_TANK_GETTERS[tank["role"]]
                 and type(tank.get("capacity")) is int and 0 <= tank["capacity"] <= 2_147_483_647
                 and type(tank.get("empty")) is bool and "fluid" in tank,
                 "named_fluid_tank_identity_unavailable", "Each actual named tank needs one distinct public role/getter and finite nonnegative typed capacity.")
        fluid = tank["fluid"]
        _require(tank["empty"] and fluid is None or not tank["empty"] and isinstance(fluid, dict)
                 and isinstance(fluid.get("fluid"), str) and 1 <= len(fluid["fluid"]) <= 128
                 and type(fluid.get("amount")) is int and 1 <= fluid["amount"] <= tank["capacity"] and fluid.get("hasTag") is False,
                 "named_fluid_tank_state_unavailable", "Named tank state must publish explicit empty/null or one exact finite untagged actual fluid.")
        result[tank["role"]] = {"role": tank["role"], "publicGetter": tank["publicGetter"], "owner": owner,
            "named": True, "capacity": tank["capacity"], "fluid": None if fluid is None else fluid["fluid"],
            "amount": 0 if fluid is None else fluid["amount"]}
    _require(set(result) == set(NAMED_TANK_GETTERS), "named_fluid_tank_coverage_unavailable", "Both actual named roles must be present exactly once.")
    return result


def named_widget_binding(value: Any, tank: dict[str, Any]) -> dict[str, Any]:
    _require(isinstance(value, dict) and type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1
             and value.get("available") is True and value.get("source") == "client_display"
             and value.get("scope") == "current_native_widget_actual_named_machine_tank_identity"
             and value.get("binding") == NAMED_TANK_BINDING and value.get("owner") == tank["owner"]
             and isinstance(value.get("owner"), dict)
             and all(type(value["owner"].get(key)) is type(expected) for key, expected in tank["owner"].items())
             and value.get("role") == tank["role"] and value.get("publicGetter") == tank["publicGetter"]
             and value.get("widgetPublicGetter") == "getFluidTank" and type(value.get("totalNamedTanks")) is int
             and value["totalNamedTanks"] == 2 and value.get("truncated") is False and value.get("serverAcceptance") is False,
             "named_fluid_widget_binding_unavailable", "The actual native fluid widget needs this exact owner and public named tank identity; client binding never proves server acceptance.")
    return copy.deepcopy(value)


def fluid_widget_selector(tank: dict[str, Any], *, fill: bool) -> dict[str, Any]:
    result = {"semantic": "fluid_slot", "fluidSlot.phantom.available": True, "fluidSlot.phantom.value": False,
        "fluidSlot." + ("canFill" if fill else "canDrain") + ".available": True,
        "fluidSlot." + ("canFill" if fill else "canDrain") + ".value": True,
        "fluidSlot.capacity.available": True, "fluidSlot.capacity.value": tank["capacity"]}
    if tank.get("named"):
        result["fluidSlot.machineTankBinding.role"] = tank["role"]
    return result


def fluid_widget(data: dict[str, Any], tank: dict[str, Any], *, fill: bool) -> dict[str, Any]:
    candidates = [entry for entry in data["widgets"] if entry.get("semantic") == "fluid_slot"]
    if tank.get("named"):
        candidates = [entry for entry in candidates if entry.get("fluidSlot", {}).get("machineTankBinding", {}).get("role") == tank["role"]]
    selector = fluid_widget_selector(tank, fill=fill)
    def matches(entry: dict[str, Any]) -> bool:
        for path, expected in selector.items():
            actual = entry
            for part in path.split("."):
                if not isinstance(actual, dict) or part not in actual:
                    return False
                actual = actual[part]
            if type(actual) is not type(expected) or actual != expected:
                return False
        return type(tank["capacity"]) is int and tank["capacity"] > 0
    eligible = [entry for entry in candidates if matches(entry)]
    # A lone refused control retains its original detailed permission/API error.
    # Mixed controls require one proven actual tank slot; ghost locks do not count.
    if eligible or len(candidates) != 1:
        candidates = eligible
    _require(len(candidates) == 1, "fluid_slot_binding_unavailable",
             "One actual public native FluidSlot is required; no widget or tank offset is guessed.")
    widget = candidates[0]
    value = widget.get("fluidSlot")
    if tank.get("named"):
        named_widget_binding(value.get("machineTankBinding") if isinstance(value, dict) else None, tank)
    _require(widget.get("class") == FLUID_SLOT_CLASS and widget.get("kind") == "fluid_slot"
             and widget.get("enabled") is True and widget.get("valid") is True
             and isinstance(widget.get("widgetId"), str) and 1 <= len(widget["widgetId"]) <= 128
             and "activate" in widget.get("supportedActions", []) and isinstance(value, dict)
             and value.get("available") is True and value.get("nativeClickAvailable") is True,
             "fluid_slot_api_unavailable", "The current native widget must expose the fixed public FluidSlot API and normal activate action.")
    for name, expected in (("canFill" if fill else "canDrain", True), ("phantom", False)):
        proof = value.get(name)
        _require(isinstance(proof, dict) and proof.get("available") is True and proof.get("value") is expected,
                 "fluid_slot_permission_unavailable", "Actual public non-phantom fill/drain permissions must allow this native operation.")
    cap, fluid = value.get("capacity"), value.get("fluid")
    _require(isinstance(cap, dict) and cap.get("available") is True and type(cap.get("value")) is int
             and cap["value"] > 0 and cap["value"] == tank["capacity"] and isinstance(fluid, dict) and fluid.get("available") is True
             and fluid.get("empty") is (tank["amount"] == 0),
             "fluid_slot_display_mismatch", "The unique current public client tank display must agree with this actual complete server tank.")
    if tank["amount"]:
        actual = fluid.get("value")
        _require(isinstance(actual, dict) and actual.get("fluid") == tank["fluid"]
                 and type(actual.get("amount")) is int and actual["amount"] == tank["amount"]
                 and actual.get("hasTag") is False, "fluid_slot_display_mismatch",
                 "A nonempty current native fluid display needs exact untagged runtime fluid and amount.")
    else:
        _require("value" in fluid and fluid["value"] is None, "fluid_slot_display_mismatch", "An empty fluid display must explicitly publish null.")
    return widget


class FluidRecipeResources:
    def __init__(self, task: Any):
        self.task = task
        self.rows = [{**copy.deepcopy(row), "direction": direction} for field, direction in
                     (("fluidContainerInputs", "input"), ("fluidContainerOutputs", "output"))
                     for row in task.args.get(field, [])]
        self.enabled = bool(self.rows)
        self.shared_named = self.enabled and task.single_inventory
        self.player_indices = {row["playerInventoryIndex"] for row in self.rows}
        self.registered_owners: dict[tuple[int, int, int], dict[str, Any]] = {}
        self.prepared = False
        self.receipts: list[dict[str, Any]] = []
    def validate_declarations(self) -> None:
        task = self.task
        if self.enabled:
            if self.shared_named:
                _require(all(row["owner"] == task.args["machine"] for row in self.rows)
                         and len({row["direction"] for row in self.rows}) == len(self.rows),
                         "named_fluid_declaration_mismatch", "A shared BasicMachine supports at most one exact declared fluid per public named role at the actual shared owner.")
            else:
                _require(all(row["owner"] != task.args["machine"]
                         and (not task.args["backendInputs"] or row["owner"] != task.args["input"])
                         and (not task.args["outputItems"] or row["owner"] != task.args["output"]) for row in self.rows),
                     "fluid_owner_overlap", "Declared fluid owners must remain separate from the controller and ordinary item ports.")
            _require(len(self.player_indices) == len(self.rows), "fluid_container_source_overlap",
                     "Every declared actual container stack needs a distinct explicit player inventory index.")
            coordinates = [self.coordinates(row) for row in self.rows]
            _require(self.shared_named or len(set(coordinates)) == len(coordinates), "fluid_owner_overlap",
                     "Each fluid declaration currently requires its own one-tank actual owner.")
            _require(task.inventory_preparation_mode == "preserve", "fluid_container_preservation_required",
                     "Actual supplied fluid containers require preserve inventory preparation; no clear or registry-generated container substitution.")

    @staticmethod
    def coordinates(row: dict[str, Any]) -> tuple[int, int, int]:
        return tuple(row["owner"][axis] for axis in ("x", "y", "z"))

    def position(self, row: dict[str, Any]) -> dict[str, Any]:
        return {**row["owner"], "dimension": self.task.dimension}

    def tank(self, observation: dict[str, Any], row: dict[str, Any]) -> dict[str, Any]:
        return named_tanks(observation, self.position(row))[row["direction"]] if self.shared_named else tank_observation(observation)

    def validate_registered_ports(self, machine: dict[str, Any]) -> None:
        task = self.task
        _require(machine.get("state", {}).get("active") is False and machine.get("state", {}).get("allowedToWork") is False
                 and machine.get("processing", {}).get("structureFormed") is True,
                 "formed_stopped_machine_required", "Fluid binding requires the actual formed, disabled, inactive controller.")
        entries, task.core_stop_coverage, reason = registered_components(machine.get("components"), stopped=True, allow_fluid_inputs=True)
        components = machine["components"]
        roles = {role["role"]: role["count"] for role in components["roles"]}
        _require("fluid_output" in roles and not any(entry["role"] == "fluid_output" for entry in components["unavailableRoles"]),
                 "registered_fluid_coverage_unavailable", "Fluid output role coverage must be explicit and complete.")
        for role, direction in (("fluid_input", "input"), ("fluid_output", "output")):
            declared = [row for row in self.rows if row["direction"] == direction]
            ports = [entry for entry in entries if entry["role"] == role]
            _require(len(ports) == len(declared), "registered_fluid_owner_mismatch", "Every registered fluid port must match one declared isolated actual owner.")
            for row in declared:
                matches = [entry for entry in ports if all(type(entry.get(axis)) is int and entry[axis] == row["owner"][axis] for axis in ("x", "y", "z"))]
                _require(len(matches) == 1 and matches[0].get("valid") is True
                         and type(matches[0].get("dimension")) is int and matches[0]["dimension"] == task.dimension
                         and type(matches[0].get("metaTileId")) is int and isinstance(matches[0].get("metaClass"), str),
                         "registered_fluid_owner_mismatch", "The exact declared owner must be one valid registered actual fluid port with typed ID/class/dimension.")
                current = {key: matches[0][key] for key in ("x", "y", "z", "dimension", "metaTileId", "metaClass")}
                previous = self.registered_owners.get(self.coordinates(row))
                _require(previous is None or current == previous, "registered_fluid_owner_changed", "Registered fluid owner identity changed.")
                self.registered_owners[self.coordinates(row)] = current
        for role, target, present in (("item_input", "input", bool(task.args["backendInputs"])),
                                     ("item_output", "output", bool(task.args["outputItems"]))):
            ports = [entry for entry in entries if entry["role"] == role]
            _require(len(ports) == int(present), "isolated_port_binding_unavailable", "All actual item ports must be covered by this finite declaration.")
            if present:
                _require(ports[0].get("valid") is True and ports[0].get("dimension") == task.dimension
                         and all(ports[0].get(axis) == task.args[target][axis] for axis in ("x", "y", "z")),
                         "isolated_port_binding_unavailable", "The unique actual item port must match the declared target.")
            else:
                _require(any(row["direction"] == target and row["owner"] == task.args[target] for row in self.rows),
                         "fluid_only_target_mismatch", "A fluid-only input/output target must name one of its actual declared fluid owners.")
        task.registered_multiblock_verified = True
        task.phases.append({"phase": "registered_fluid_preflight", "status": "completed",
                            "registeredCoreStopCoverage": task.core_stop_coverage, "coverageReason": reason})

    def validate_backend_resources(self, data: dict[str, Any]) -> None:
        query = data.get("query")
        _require(isinstance(query, dict), "backend_multiblock_input_copy_unavailable", "Native resource-copy evidence must be explicit.")
        inputs, declared = query.get("inputs"), self.task.args["backendInputs"]
        _require(isinstance(inputs, list) and len(inputs) == len(declared)
                 and all(isinstance(actual, dict) and actual.get("hasTag") is False
                         and all(type(actual.get(key)) is type(wanted[key]) and actual[key] == wanted[key]
                                 for key in ("registryName", "metadata", "count")) for actual, wanted in zip(inputs, declared)),
                 "backend_multiblock_input_copy_unavailable", "Native item copies must match all explicitly declared finite item templates.")
        fluids = [{"fluid": row["fluid"], "amount": row["amount"], "hasTag": False}
                  for row in self.rows if row["direction"] == "input"]
        _require(query.get("fluids") == fluids and isinstance(query.get("fluids"), list)
                 and all(type(actual.get("amount")) is int for actual in query["fluids"])
                 and "specialSlot" in query and query["specialSlot"] is None
                 and query.get("caching") is False and query.get("ignoreStackSizes") is False,
                 "backend_fluid_copy_unavailable", "The native query must echo the exact ordered untagged actual fluid templates without cache/ignored counts/special slot.")
        if data["matched"]:
            validation = data.get("validation")
            output_present = "outputItem" in self.task.args
            _require(isinstance(validation, dict)
                     and all(validation.get(key) is True for key in ("inputMatch", "enabled", "notFake", "voltageCompatible"))
                     and validation.get("outputUnknown") is False
                     and validation.get("outputChecked") is output_present
                     and (validation.get("outputMatch") is True if output_present else "outputMatch" in validation and validation["outputMatch"] is None),
                     "backend_multiblock_candidate_unvalidated", "Native input/flags/voltage and actual presence or absence of the optional item filter must be explicitly validated.")

    async def inspect(self, row: dict[str, Any], expected: tuple[str | None, int] | None = None) -> dict[str, Any]:
        observation = await self.task.call("world.inspect", self.position(row))
        return self.validate_observation(row, observation, expected)

    def validate_observation(self, row: dict[str, Any], observation: dict[str, Any],
                             expected: tuple[str | None, int] | None = None) -> dict[str, Any]:
        from .multi_recipe_tasks import _owner
        owner = _owner(observation, self.position(row))
        if not self.task.single_inventory:
            registered = self.registered_owners.get(self.coordinates(row))
            _require(registered is not None and all(owner[key] == value and type(owner[key]) is type(value) for key, value in registered.items()),
                     "registered_fluid_owner_changed", "Fresh actual tank owner must remain the exact registered declared fluid owner.")
        if "actualOwner" in row:
            _require(owner == row["actualOwner"], "fluid_owner_changed", "The exact original actual fluid owner changed.")
        tank = self.tank(observation, row)
        if "tankCapacity" in row:
            _require(tank["capacity"] == row["tankCapacity"], "fluid_tank_capacity_changed", "The actual tank capacity changed.")
        if expected is not None:
            _require((tank["fluid"], tank["amount"]) == expected, "fluid_tank_conservation_failed", "The complete actual fluid tank must equal the exact expected fluid and conserved amount.")
        if "ownerInventory" in row and not self.shared_named:
            _require(inventory_slots(observation) == row["ownerInventory"], "fluid_owner_inventory_changed", "Native fluid handling must preserve every actual owner inventory slot.")
        return observation

    def validate_tanks(self, observation: dict[str, Any]) -> None:
        if self.shared_named:
            tanks = named_tanks(observation, self.task.position("machine"))
            for direction, tank in tanks.items():
                row = next((entry for entry in self.rows if entry["direction"] == direction), None)
                expected = row.get("expectedTank", (None, 0)) if row is not None else (None, 0)
                _require((tank["fluid"], tank["amount"]) == expected, "fluid_tank_conservation_failed",
                         "Both complete public named tank roles must equal their exact accepted state; undeclared roles must remain empty.")
            return
        row = next((row for row in self.rows if all(observation.get(axis) == row["owner"][axis] for axis in ("x", "y", "z"))), None)
        if row is None:
            # Item owners keep the original complete empty-tank rule.
            from .recipe_tasks import _RecipeTask
            _RecipeTask.validate_single_tanks(self.task, observation)
            return
        tank = tank_observation(observation)
        expected = row.get("expectedTank", (None, 0))
        _require((tank["fluid"], tank["amount"]) == expected, "fluid_tank_conservation_failed", "Actual declared owner tank changed outside its accepted native operation.")

    async def open(self, row: dict[str, Any]) -> dict[str, Any]:
        key = "fluid_port"
        self.task.args[key] = row["owner"]
        self.task.opened_gui = None
        try:
            return await self.task.open_gui(key)
        finally:
            self.task.args.pop(key, None)
            self.task.opened_gui = None

    @staticmethod
    def source_binding(gui: dict[str, Any], index: int, stack: Any) -> dict[str, Any]:
        matches = [slot for slot in inventory_slots(gui, "") if slot.get("playerInventoryBindingVerified") is True
                   and slot.get("machineInventoryBindingVerified") is False
                   and slot.get("inventoryBinding") == PLAYER_BINDING and slot.get("inventorySource") == "current_player_inventory"
                   and type(slot.get("playerInventoryIndex")) is int and slot["playerInventoryIndex"] == index]
        _require(len(matches) == 1 and (matches[0].get("item") is None if stack is None else public_stack(matches[0].get("item")) == public_stack(stack)),
                 "fluid_container_player_binding_unavailable", "The exact current actual supplied player entry needs one unique native GUI slot binding.")
        return matches[0]

    async def tooltip(self, gui: dict[str, Any], binding: dict[str, Any], stack: dict[str, Any], *,
                      container_fluid: str | None = None, allow_unknown_empty_capacity: bool = False) -> dict[str, Any]:
        args = {"screenId": gui["screenId"], "slot": binding["slot"], "advanced": False}
        if container_fluid is not None:
            args["containerFluid"] = container_fluid
        result = await self.task.call("gui.tooltip", args)
        _require(result.get("screenId") == gui["screenId"] and type(result.get("slot")) is int and result["slot"] == binding["slot"],
                 "fluid_container_tooltip_context_changed", "Actual fluid tooltip must retain the current source screen and native slot.")
        return container_semantics(result, stack, allow_unknown_empty_capacity=allow_unknown_empty_capacity)

    async def prepare(self) -> None:
        if not self.enabled:
            return
        for operation in ("gui.tooltip",):
            caps = self.task.capabilities
            _require(operation in caps if isinstance(caps, list) else caps.get(operation) is True,
                     "required_capability_unavailable", "Fluid preparation requires the advertised actual slot tooltip read.")
        player = await self.task.call("inventory.inspect", {})
        indexed = {slot["index"]: slot["item"] for slot in inventory_slots(player, "")}
        _require(player.get("cursorItem") is None, "carried_stack_not_empty", "Actual fluid preparation needs an empty real cursor.")
        from .multi_recipe_tasks import _owner
        for row in self.rows:
            source = public_stack(indexed.get(row["playerInventoryIndex"]))
            _require(source["count"] == row["count"], "fluid_container_source_count_mismatch", "The declaration must consume/recover exactly this actual complete source stack.")
            identities = [item for item, _ in self.task.recipe.consumed_items + self.task.recipe.output_items] + list(self.task.recipe.catalysts)
            _require(not any(all(source[key] == item[key] for key in ("registryName", "metadata")) for item in identities),
                     "fluid_container_item_overlap", "An explicitly tracked actual fluid container cannot share an ordinary consumed/product/catalyst identity.")
            observation = await self.inspect(row, (None, 0))
            tank = self.tank(observation, row)
            _require(row["amount"] <= tank["capacity"], "fluid_tank_capacity_unavailable", "The exact fluid amount must fit the isolated actual tank.")
            row.update(actualOwner=_owner(observation, self.position(row)), tankCapacity=tank["capacity"],
                       ownerInventory=copy.deepcopy(inventory_slots(observation)), expectedTank=(None, 0),
                       currentStack=source, initialStack=copy.deepcopy(source))
            gui = await self.open(row)
            binding = self.source_binding(gui, row["playerInventoryIndex"], source)
            semantics = await self.tooltip(gui, binding, source,
                                           container_fluid=row["fluid"] if row["direction"] == "output" else None)
            self.check_semantics(row, semantics, initial=True)
            row["initialSemantics"] = semantics
            fluid_widget(await self.task.control_widgets(gui["screenId"], reason="fluid_slot_coverage_unavailable"), tank,
                         fill=row["direction"] == "input")
        self.prepared = True

    def check_semantics(self, row: dict[str, Any], semantics: dict[str, Any], *, initial: bool) -> None:
        empty = row["direction"] == "output" if initial else row["direction"] == "input"
        _require(semantics["empty"] is empty, "fluid_container_direction_mismatch", "The actual supplied/returned container must have the expected public empty/filled state.")
        if not empty:
            actual = semantics["fluid"]
            _require(actual["fluidName"] == row["fluid"] and actual["amount"] * row["count"] == row["amount"],
                     "fluid_container_amount_mismatch", "Actual per-container fluid semantics times the whole supplied stack must equal the exact declared raw fluid amount.")
        elif row["direction"] == "output" and initial:
            _require(semantics["source"] != "public_fluid_container_registry"
                     or semantics["capacity"].get("source") == REGISTRY_CAPACITY_QUERY_SOURCE
                     and semantics["capacity"].get("fluidName") == row["fluid"],
                     "fluid_container_capacity_query_unavailable", "An actual registry-empty output needs positive public capacity queried for this exact declared runtime fluid.")
            _require(row["count"] == 1 and row["amount"] <= semantics["capacity"]["value"]
                     or semantics["capacity"]["value"] * row["count"] == row["amount"],
                     "fluid_container_amount_mismatch", "One actual output container may hold the exact partial tank amount; a multi-container output stack must cover complete capacities.")
        if not initial:
            if semantics["capacity"].get("available") is True:
                _require(semantics["capacity"]["value"] == row["initialSemantics"]["capacity"]["value"],
                         "fluid_container_capacity_changed", "Native transformed actual container capacity must preserve the initial public capacity.")
            else:
                _require(row["direction"] == "input" and unknown_registry_empty_capacity(semantics),
                         "fluid_container_capacity_unavailable", "Only an explicitly empty returned input shell may retain the known public registry capacity unknown.")

    async def validate_player(self, player: dict[str, Any]) -> None:
        if not self.prepared:
            return
        indexed = {slot["index"]: slot["item"] for slot in inventory_slots(player, "")}
        for row in self.rows:
            _require(public_stack(indexed.get(row["playerInventoryIndex"])) == row["currentStack"],
                     "fluid_container_player_conservation_failed", "Every explicitly tracked actual container stack must remain at its conserved source entry with its accepted public item state.")

    async def transfer(self, row: dict[str, Any], *, fill: bool) -> None:
        task = self.task
        phase = await task.read_owner_phase([self.position(row)])
        before = self.validate_observation(row, phase[0][0], row["expectedTank"]) if phase is not None else await self.inspect(row, row["expectedTank"])
        tank = self.tank(before, row)
        owner_inventory = inventory_slots(before) if self.shared_named else row["ownerInventory"]
        before_player = phase[1] if phase is not None else await task.call("inventory.inspect", {})
        await self.validate_player(before_player)
        slots = inventory_slots(before_player, "")
        _require(before_player.get("cursorItem") is None, "carried_stack_not_empty", "Native fluid operation needs the actual empty cursor.")
        gui = await self.open(row)
        source = self.source_binding(gui, row["playerInventoryIndex"], row["currentStack"])
        semantics = await self.tooltip(gui, source, row["currentStack"],
                                       container_fluid=row["fluid"] if row["direction"] == "output" else None)
        self.check_semantics(row, semantics, initial=True)
        controls = await task.control_widgets(gui["screenId"], reason="fluid_slot_coverage_unavailable")
        widget = fluid_widget(controls, tank, fill=fill)
        widget_proof = {"widgetId": widget["widgetId"], "class": FLUID_SLOT_CLASS,
                        "semantic": "fluid_slot", "kind": "fluid_slot", "valid": True, "enabled": True,
                        "fluidSlot.nativeClickAvailable": True, "fluidSlot.phantom.available": True, "fluidSlot.phantom.value": False,
                        "fluidSlot." + ("canFill" if fill else "canDrain") + ".available": True,
                        "fluidSlot." + ("canFill" if fill else "canDrain") + ".value": True,
                        "fluidSlot.capacity.available": True, "fluidSlot.capacity.value": tank["capacity"],
                        "fluidSlot.fluid.available": True, "fluidSlot.fluid.empty": tank["amount"] == 0}
        if tank["amount"]:
            widget_proof.update({"fluidSlot.fluid.value.fluid": tank["fluid"], "fluidSlot.fluid.value.amount": tank["amount"],
                                 "fluidSlot.fluid.value.hasTag": False})
        else:
            widget_proof["fluidSlot.fluid.value"] = None
        unique_widget = fluid_widget_selector(tank, fill=fill)
        if self.shared_named:
            widget_proof["fluidSlot.machineTankBinding"] = named_widget_binding(widget["fluidSlot"]["machineTankBinding"], tank)
            unique_widget["fluidSlot.machineTankBinding.role"] = row["direction"]
        proof = {"slot": source["slot"], "playerInventoryBindingVerified": True, "inventoryBinding": PLAYER_BINDING,
                 "machineInventoryBindingVerified": False,
                 "inventorySource": "current_player_inventory", "playerInventoryIndex": row["playerInventoryIndex"],
                 **{"item." + key: value for key, value in row["currentStack"].items()}}
        steps = [_read("gui_before", "mcb_gui", fields=["screenId", "windowId", "slots", "truncated"]),
                 _assert("screen", "gui_before", "screenId", gui["screenId"]),
                 _assert("window", "gui_before", "windowId", gui["windowId"]),
                 _assert("coverage", "gui_before", "truncated", False),
                 _assert("source", "gui_before", "slots", proof, "matches_one"),
                 _action("pickup_containers", "mcb_slot_click", {"windowId": gui["windowId"], "slot": source["slot"], "mode": 0, "button": 0}),
                 _wait("carried", "mcb_inventory", {}, "cursorItem.count", row["count"], fields=["cursorItem"])]
        steps.extend(_assert("carried_" + key, "carried", "cursorItem." + key, value)
                     for key, value in row["currentStack"].items() if key != "count")
        steps.extend([_read("controls", "mcb_gui_widgets", {"screenId": gui["screenId"], "actionableOnly": True, "limit": 128},
                            fields=["screenId", "widgets", "hasMore", "partial", "scanTruncated", "byteLimited"]),
                      _assert("same_screen", "controls", "screenId", gui["screenId"]),
                      *[_assert("control_" + flag, "controls", flag, False) for flag in ("hasMore", "partial", "scanTruncated", "byteLimited")],
                      _assert("same_widget", "controls", "widgets", widget_proof, "matches_one"),
                      _assert("unique_fluid_slot", "controls", "widgets", unique_widget, "matches_one"),
                      _read("owner_before", "mcb_world_inspect", self.position(row), ["x", "y", "z", "dimension", "source", "registryName", "gregtech", "fluids", "inventory"]),
                      *[_assert("owner_" + key, "owner_before", key if key not in ("metaClass", "metaTileId") else "gregtech." + key, value)
                        for key, value in row["actualOwner"].items()],
                      _assert("owner_source", "owner_before", "source", "integrated_server"),
                      _assert("complete_tank_before", "owner_before", "fluids.namedTanks" if self.shared_named else "fluids",
                              before["fluids"]["namedTanks"] if self.shared_named else before["fluids"]),
                      _assert("complete_owner_inventory", "owner_before", "inventory.slots", owner_inventory),
                      _assert("complete_owner_slot_count", "owner_before", "inventory.totalSlots", len(owner_inventory)),
                      _assert("complete_owner_inventory_coverage", "owner_before", "inventory.truncated", False),
                      _action("activate_fluid_stack", "mcb_gui_widget_action", {"screenId": gui["screenId"], "widgetId": widget["widgetId"], "action": "activate"})])
        task.queue_recipe_context_guard()
        await task.workflow("fluid_" + ("fill_" if fill else "drain_") + str(row["playerInventoryIndex"]), steps)
        expected = (row["fluid"], row["amount"]) if fill else (None, 0)
        row["expectedTank"] = expected
        cursor = None
        for _ in range(20):
            phase = await task.read_owner_phase([self.position(row)])
            observed = phase[0][0] if phase is not None else await task.call("world.inspect", self.position(row))
            tank_now = self.tank(observed, row)
            player = phase[1] if phase is not None else await task.call("inventory.inspect", {})
            current = player.get("cursorItem")
            if (tank_now["fluid"], tank_now["amount"]) == expected and isinstance(current, dict) and current.get("count") == row["count"]:
                public_stack(current)
                # A complete second read allows native cursor synchronization to settle.
                await anyio.sleep(.05)
                second = await task.call("inventory.inspect", {})
                if second.get("cursorItem") is not None and second["cursorItem"].get("count") == row["count"]:
                    cursor = public_stack(second["cursorItem"])
                    break
            await anyio.sleep(.05)
        _require(cursor is not None, "fluid_stack_synchronization_unavailable", "One dispatched activation did not yield a complete exact tank delta and whole returned cursor stack; no action was replayed.")
        accepted = await self.inspect(row, expected)
        if self.shared_named:
            other = "output" if row["direction"] == "input" else "input"
            _require(inventory_slots(accepted) == owner_inventory
                     and named_tanks(accepted, self.position(row))[other] == named_tanks(before, self.position(row))[other],
                     "named_fluid_transfer_conservation_failed", "Native shared-tank transfer must preserve every actual machine item and the entire other named tank.")
        fresh = await task.call("gui.inspect", {})
        _require(fresh.get("screenId") == gui["screenId"] and fresh.get("windowId") == gui["windowId"],
                 "fluid_return_context_changed", "Native return must retain this exact source screen and window.")
        destination = self.source_binding(fresh, row["playerInventoryIndex"], None)
        return_steps = [_read("return_gui", "mcb_gui", fields=["screenId", "windowId", "slots", "truncated"]),
                        _assert("return_screen", "return_gui", "screenId", gui["screenId"]),
                        _assert("return_window", "return_gui", "windowId", gui["windowId"]),
                        _assert("return_coverage", "return_gui", "truncated", False),
                        _assert("empty_destination", "return_gui", "slots", {"slot": destination["slot"], "item": None,
                                "playerInventoryIndex": row["playerInventoryIndex"], "playerInventoryBindingVerified": True,
                                "machineInventoryBindingVerified": False,
                                "inventoryBinding": PLAYER_BINDING, "inventorySource": "current_player_inventory"}, "matches_one"),
                        _read("return_cursor", "mcb_inventory", fields=["cursorItem"]),
                        *[_assert("cursor_" + key, "return_cursor", "cursorItem." + key, value) for key, value in cursor.items()],
                        _action("return_actual_containers", "mcb_slot_click", {"windowId": gui["windowId"], "slot": destination["slot"], "mode": 0, "button": 0}),
                        _wait("cursor_empty", "mcb_inventory", {}, "cursorItem", None)]
        await task.workflow("fluid_return_" + str(row["playerInventoryIndex"]), return_steps)
        after_player = await task.call("inventory.inspect", {})
        after_slots = inventory_slots(after_player, "")
        _require(after_player.get("cursorItem") is None
                 and [slot for slot in slots if slot["index"] != row["playerInventoryIndex"]]
                     == [slot for slot in after_slots if slot["index"] != row["playerInventoryIndex"]],
                 "fluid_player_conservation_failed", "Every unaffected actual player slot must remain unchanged across this native container operation.")
        row["currentStack"] = public_stack(next(slot["item"] for slot in after_slots if slot["index"] == row["playerInventoryIndex"]))
        _require(row["currentStack"]["count"] == row["count"], "fluid_container_player_conservation_failed", "All transformed actual containers must return as the same whole count.")
        fresh = await task.call("gui.inspect", {})
        binding = self.source_binding(fresh, row["playerInventoryIndex"], row["currentStack"])
        returned = await self.tooltip(fresh, binding, row["currentStack"],
                                      allow_unknown_empty_capacity=row["direction"] == "input")
        self.check_semantics(row, returned, initial=False)
        row["returnedSemantics"] = returned
        await self.inspect(row, expected)
        if task.recipe_context_inventory is not None:
            await task.accept_recipe_context(copy.deepcopy(task.recipe_context_inventory), after_player,
                "fluid_container_" + row["direction"], mutable_indices={row["playerInventoryIndex"]})
        self.receipts.append({"direction": row["direction"], "owner": row["actualOwner"], "fluid": row["fluid"], "amount": row["amount"],
                              "playerInventoryIndex": row["playerInventoryIndex"], "count": row["count"], "nativeActivations": 1,
                              "returnedStack": row["currentStack"], "returnedSemantics": returned, "serverTankDeltaAccepted": True,
                              "cursorEmpty": True, "unaffectedActualPlayerSlotsPreserved": True})
        if self.shared_named:
            self.receipts[-1].update(tankRole=row["direction"], publicGetter=NAMED_TANK_GETTERS[row["direction"]],
                                    tankBinding=NAMED_TANK_BINDING, otherNamedTankAndMachineItemsPreserved=True)

    async def feed(self) -> None:
        for row in self.rows:
            if row["direction"] == "input":
                await self.transfer(row, fill=True)

    def output_wait_steps(self) -> list[dict[str, Any]]:
        if self.shared_named:
            return [_wait("fluid_product_" + str(index), "mcb_world_inspect", self.position(row), "fluids.namedTanks.tanks",
                          {"role": "output", "publicGetter": "getFluidOutputTank", "empty": False,
                           "fluid": {"fluid": row["fluid"], "amount": row["amount"], "hasTag": False}},
                          operator="matches_one", fields=["fluids"])
                    for index, row in enumerate(self.rows) if row["direction"] == "output"]
        return [_wait("fluid_product_" + str(index), "mcb_world_inspect", self.position(row), "fluids.tanks",
                      {"index": 0, "fluid": row["fluid"], "amount": row["amount"]}, operator="matches_one", fields=["fluids"])
                for index, row in enumerate(self.rows) if row["direction"] == "output"]

    def native_output_conditions(self, owner_index) -> list[dict[str, Any]]:
        return [{"owner": owner_index(self.position(row)), "kind": "fluid_match", "fluid": row["fluid"], "amount": row["amount"],
            **({"tankKind": "named_output"} if self.shared_named else {"tankKind": "forge", "tankIndex": 0})}
            for row in self.rows if row["direction"] == "output"]

    async def accept_recipe(self, observations: list[dict[str, Any]] | None = None) -> None:
        _require(observations is None or len(observations) == len(self.rows), "fluid_owner_coverage_unavailable",
                 "Same-phase recipe acceptance must retain every declared complete fluid owner.")
        for index, row in enumerate(self.rows):
            expected = (None, 0) if row["direction"] == "input" else (row["fluid"], row["amount"])
            row["expectedTank"] = expected
            if observations is None:
                await self.inspect(row, expected)
            else:
                self.validate_observation(row, observations[index], expected)

    async def recover(self) -> None:
        for row in self.rows:
            if row["direction"] == "output":
                await self.transfer(row, fill=False)

    async def validate_final(self, *, player: dict[str, Any] | None = None,
                             first_owner: dict[str, Any] | None = None) -> None:
        if not self.enabled:
            return
        await self.validate_player(player if player is not None else await self.task.call("inventory.inspect", {}))
        for index, row in enumerate(self.rows):
            if index == 0 and first_owner is not None:
                self.validate_observation(row, first_owner, (None, 0))
            else:
                await self.inspect(row, (None, 0))
            gui = await self.open(row)
            binding = self.source_binding(gui, row["playerInventoryIndex"], row["currentStack"])
            self.check_semantics(row, await self.tooltip(gui, binding, row["currentStack"],
                                 allow_unknown_empty_capacity=row["direction"] == "input"), initial=False)
        if self.shared_named:
            self.validate_tanks(await self.task.call("world.inspect", self.task.position("machine")))

    def acceptance(self) -> dict[str, Any]:
        return {"enabled": self.enabled, "containerTransfers": self.receipts,
                "allDeclaredTanksRecovered": self.enabled, "hiddenNbtIdentityClaimed": False,
                "source": "Explicit actual whole stack provenance, public container fluid semantics, native actions and complete real owner/player/tank conservation."}

    async def failure_diagnostics(self) -> dict[str, Any]:
        player = await self.task.call("inventory.inspect", {})
        indexed = {slot["index"]: slot["item"] for slot in inventory_slots(player, "")}
        owners = []
        for row in self.rows:
            observed = await self.task.call("world.inspect", self.position(row))
            owners.append({"declaredOwner": self.position(row), "actualOwner": {key: observed.get(key) for key in ("x", "y", "z", "dimension", "source", "registryName")},
                           "gregtech": {key: observed.get("gregtech", {}).get(key) for key in ("metaTileId", "metaClass")},
                           "fluids": observed.get("fluids"), "inventory": observed.get("inventory"),
                           "playerInventoryIndex": row["playerInventoryIndex"], "actualPlayerStack": indexed.get(row["playerInventoryIndex"]),
                           "lastAcceptedTank": row.get("expectedTank"), "lastAcceptedContainerStack": row.get("currentStack")})
        return {"available": True, "cursorItem": player.get("cursorItem"), "owners": owners,
                "source": "Fresh read-only current resource observations after failure; accepted checkpoints are separately labelled. No replay or automatic recovery."}
