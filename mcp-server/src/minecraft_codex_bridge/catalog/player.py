"""Player tool schemas and native operation declarations."""

from .common import (
    AE_PART_IDENTITY_SCHEMA,
    DIMENSION,
    EXPECTED_SESSION,
    EXPECTED_WORLD,
    LIVE_ID,
    POSITION,
    REGISTRY_NAME,
    ToolSpec,
    integer,
    obj,
)
from .gui import (
    GUI_ACCEPTANCE_SCHEMA,
)


PLACEMENT_ATTACHMENT_SCHEMA = obj({"version": {"type": "integer", "const": 1}, **POSITION, "dimension": DIMENSION,
    "side": integer(0, 5), "registryName": {"type": "string", "minLength": 1, "maxLength": 256},
    "metadata": integer(0, 32766), "gregtech": obj({"metaTileId": integer(0, 32767),
        "metaClass": {"type": "string", "minLength": 1, "maxLength": 256}}, ["metaTileId", "metaClass"])},
    ["version", "x", "y", "z", "dimension", "side", "registryName", "metadata"])


INTERACT_SAMPLE_SCHEMA = obj({
    "expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD,
    **POSITION, "side": integer(0, 5), "targetPart": AE_PART_IDENTITY_SCHEMA,
    "count": {**integer(1, 16), "default": 3},
    "interval_ms": {**integer(100, 2500), "default": 1300},
    "timeout_ms": {**integer(500, 30000), "default": 10000},
}, ["expectedSessionId", "expectedWorld", "x", "y", "z"])


INTERACTION_HELD_SCHEMA = obj({"slot": integer(0, 8), "registryName": REGISTRY_NAME, "metadata": integer(0, 32767)},
                              ["slot", "registryName", "metadata"])


NAVIGATION_XYZ_SCHEMA = obj({"x": {"type": "number", "minimum": -29999999, "maximum": 29999999}, "y": {"type": "number", "minimum": 0, "maximum": 253}, "z": {"type": "number", "minimum": -29999999, "maximum": 29999999}}, ["x","y","z"])


NAVIGATION_LEAF_GOAL_SCHEMA = {"oneOf": [obj({"type": {"const": "point"}, **NAVIGATION_XYZ_SCHEMA["properties"]}, ["type","x","y","z"]), obj({"type": {"const": "box"}, "min": NAVIGATION_XYZ_SCHEMA, "max": NAVIGATION_XYZ_SCHEMA}, ["type","min","max"])]}


INTERACTION_HIT_POINT_SCHEMA = obj({axis: {"type": "number", "minimum": 0, "maximum": 1} for axis in ("x", "y", "z")}, ["x", "y", "z"])


NAVIGATION_INTERACTION_TARGETS = {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True, "items": obj({**POSITION, "hitPoint": INTERACTION_HIT_POINT_SCHEMA,
    "wrenchFacing": {"type": "boolean", "const": True, "description": "Movement-only GT wrench endpoint: prove the same native face centre required by a later checked wrench click. Requires approachWrenchFacingVersion=1, one allowed side and a whole GT machine identity."},
    "allowedSides": {"type": "array", "minItems": 1, "maxItems": 6, "uniqueItems": True, "items": integer(0, 5)},
    "targetIdentity": obj({"registryName": {"type": "string", "minLength": 3, "maxLength": 256}, "metadata": integer(0, 65535),
        "metaTileId": integer(0, 32767), "metaClass": {"type": "string", "minLength": 1, "maxLength": 256}, "part": AE_PART_IDENTITY_SCHEMA}, ["registryName"])}, ["x", "y", "z"]) | {"allOf": [{"if": {"required": ["hitPoint"]}, "then": {"required": ["allowedSides", "targetIdentity"], "properties": {"allowedSides": {"maxItems": 1}}, "not": {"required": ["wrenchFacing"]}}}, {"if": {"required": ["wrenchFacing"]}, "then": {"required": ["allowedSides", "targetIdentity"], "properties": {"allowedSides": {"maxItems": 1}, "targetIdentity": {"properties": {"registryName": {"const": "gregtech:gt.blockmachines"}}, "not": {"required": ["part"]}}}}}]}}


NAVIGATION_GOAL_SCHEMA = {"oneOf": [*NAVIGATION_LEAF_GOAL_SCHEMA["oneOf"], obj({"type": {"const": "any"}, "goals": {"type": "array", "minItems": 1, "maxItems": 8, "items": NAVIGATION_LEAF_GOAL_SCHEMA}}, ["type","goals"]),
    obj({"type": {"const": "nearby"}, "center": NAVIGATION_XYZ_SCHEMA, "radius": {"type":"number","minimum":2,"maximum":6,"default":3},
         "minDistance": {"type":"number","minimum":0,"maximum":5.5,"default":1.25}, "targetHeight": {"type":"number","minimum":.2,"maximum":3,"default":1.8}}, ["type","center"]),
    obj({"type": {"const": "interaction"}, "targets": NAVIGATION_INTERACTION_TARGETS}, ["type", "targets"])]}


T_MCB_INTERACT_SAMPLE = ToolSpec("mcb_interact_sample", None, "Bounded repeated real native right-clicks on one already-reachable target; never substitutes machine setters. Exact local developer session/world, count1..16, interval100..2500ms and total deadline<=30s. Preflight/takeover once; native context/reach/view validation per action. Collect incremental visible chat with seq/clientTicks/receivedAtMillis, history-gap/truncation and individual action receipts. Stops on error, timeout or cancellation with completed receipts retained, no replay. interval is a wall-clock wait, not a server-tick guarantee. Instrument text is retained; only recognized labeled average/worst nanosecond fields are parsed, never positional numbers. RPC/wall time is not machine CPU time. This actively uses the held item, so equip the intended meter before calling.", INTERACT_SAMPLE_SCHEMA)


T_MCB_INVENTORY = ToolSpec("mcb_inventory", "inventory.inspect", "Inspect player inventory slots, selected hotbar slot and cursor stack. includeNbt adds bounded raw NBT (4096 characters per stack); reject nbtTruncated for identity proofs. Prefer live stack/GUI tooltip data over local source and screenshots. Versioned help is available for unfamiliar GTNH NEI/AE controls.", obj({"includeNbt": {"type": "boolean", "default": False}}), True)


T_MCB_LOOK = ToolSpec("mcb_look", "player.look", "Set the local player's yaw (-360000 through 360000) and pitch (-90 through 90), preserving accumulated yaw when restoring a snapshot. The mod applies the action on the game thread.", obj({"yaw": {"type": "number", "minimum": -360_000, "maximum": 360_000}, "pitch": {"type": "number", "minimum": -90, "maximum": 90}}, ["yaw", "pitch"]))


T_MCB_HOTBAR = ToolSpec("mcb_hotbar", "player.hotbar", "Select a player hotbar slot (0 through 8).", obj({"slot": integer(0, 8)}, ["slot"]))


T_MCB_PLACE_BLOCK = ToolSpec("mcb_place_block", "player.place_block", "Place the selected real hotbar ItemBlock (or expectedPart with exact attachmentBinding and partPlacement=adjacent|host) at exact nearby x/y/z in local developer context. Chooses one loaded, reachable, visible attachment face and binds view. Actual survival requires authoritative matching serverTarget, serverConfirmed and exactly one source deduction proven by complete serverBefore/serverAfter inventories; submitted alone is not placement or consumption. Unconstrained creative receipts retain their prior semantics; exact binding or facing uses authoritative confirmation with zero source consumption. Every standalone placement binds its current session and explicit expectedWorld through the native accounting path. expectedWorld and optional dimension guard current context. Default replace=false permits air only; replace=true permits native replaceable targets, never solid-block breaking. Optional attachmentBinding pins one exact adjacent XYZ/side and public Block/GT identity through dual client/server body/ray/reach and all41 native inventory confirmation. Bound failures never choose another neighbor or replay. Omission retains existing placement. Optional jumpUnderfoot=true uses one bounded native jump/sneak/use input from a stationary supported survival pose inside the empty target, with clear headroom, no Jump Boost, and a non-tile ItemBlock. Requires jumpPlacementVersion=1 and cannot combine with attachmentBinding or part/facing/flight options; confirms one full-NBT inventory deduction and grounded client/server landing one block higher. Native held use callback count is not asserted. No setBlock, force loading or retries.", obj({**POSITION, "jumpUnderfoot": {"type": "boolean", "const": True}, "hotbarSlot": integer(0, 8), "expectedWorld": EXPECTED_WORLD, "dimension": DIMENSION, "replace": {"type": "boolean", "default": False}, "requireSurvivalFlight": {"type": "boolean", "default": False}, "expectedPlacementFacing": {"enum": ["NORTH", "SOUTH", "WEST", "EAST"], "description": "Optional GT placement constraint (creative requires placementFacingCreativeVersion=1): filter actual native attachment rays and require matching server/client view before one click. No tile rotation or retry; verify actual controller alignment afterward."}, "attachmentBinding": PLACEMENT_ATTACHMENT_SCHEMA, "expectedPart": AE_PART_IDENTITY_SCHEMA, "partPlacement": {"enum": ["adjacent", "host"]}}, ["x", "y", "z", "hotbarSlot", "expectedWorld"]) | {"allOf": [
        {"if": {"required": ["jumpUnderfoot"]}, "then": {"properties": {"replace": {"const": False}}, "not": {"anyOf": [{"required": [key]} for key in ("expectedPart", "partPlacement", "expectedPlacementFacing", "requireSurvivalFlight", "attachmentBinding")]}}},
        {"if": {"required": ["expectedPart"]}, "then": {"required": ["attachmentBinding"], "properties": {"replace": {"const": False}}}},
        {"if": {"required": ["partPlacement"]}, "then": {"required": ["expectedPart"]}}]})


T_MCB_INPUT = ToolSpec("mcb_input", "player.input", "Hold supported game key bindings for 1 through 200 client ticks, then automatically release. Actions are never retried; inspect state after a timeout.", obj({"keys": {"type": "array", "items": {"type": "string", "enum": ["forward", "back", "left", "right", "jump", "sneak", "sprint", "use", "attack"]}, "minItems": 1, "maxItems": 9, "uniqueItems": True}, "ticks": integer(1, 200)}, ["keys", "ticks"]))


T_MCB_NAVIGATE = ToolSpec("mcb_navigate", "player.navigate", "Uses the saved mcb_policy movement; execution strategy is internal. One client-planned goal navigation in the exact session/world/dimension. Path classifies the current actual flying state, not flight permission alone; otherwise uses grounded walking with bounded jumps/drops. Interaction goals currently use the grounded executor. Native owning-thread planning checks loaded full-body geometry, support and fresh client/server pose. No automatic digging/bridging. TP needs selected tp policy and actual normal tp permission; unavailable TP resolves path before dispatch, never after an uncertain move. finishFlying requests retained real flight, otherwise land normally. Segmented coordinate/interaction routes have bounded planning, timeout and candidate budgets; unknown/unloaded geometry refuses. arrivalTargets bind native crosshair feedback on the final segment; actual body/support, flight, settling and dual-owner arrival remain required. includeDetails adds bounded route/trajectory evidence. Completed means fresh native client/server arrival evidence; submission is not arrival or machine acceptance. No replay after timeout, lost connection or other unknown outcome.", obj({
        "x": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999}, "y": {"type": "number", "minimum": 0, "maximum": 253}, "z": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999},
        "dimension": DIMENSION, "expectedWorld": EXPECTED_WORLD, "expectedSessionId": EXPECTED_SESSION,
        "strategy": {"enum": ["walking", "walking_step", "ability_flight", "developer_teleport"], "default": "walking"},
        "timeout_ms": {**integer(250, 60000), "default": 6000}, "finishFlying": {"type": "boolean", "default": False},
        "segmented": {"type": "boolean", "default": False},
        "includeDetails": {"type": "boolean", "default": False, "description": "Include bounded planned route and actual trajectory records in this navigation receipt; default retains counts and full actual pose ACK."},
        "goal": NAVIGATION_GOAL_SCHEMA,
        "arrivalTargets": {**NAVIGATION_INTERACTION_TARGETS, "description": "Optional exact interaction targets for XYZ walking/flight. Only the final segment uses fresh actual native crosshair hits to release keys and finish view. Safe dual-owner settled arrival is still required; unknown or lost hits retain the original route and existing errors. Interaction goals already carry targets."},
        "requireSurvivalActor": {"type": "boolean", "default": False, "description": "Explicit strict survival comparison; generic walking/flight accept either unchanged game mode."},
        "waypoints": {"type": "array", "maxItems": 2, "items": obj({"x": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999}, "y": {"type": "number", "minimum": 0, "maximum": 253}, "z": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999}}, ["x", "y", "z"])},
        "lookAt": obj({"x": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999}, "y": {"type": "number", "minimum": 0, "maximum": 253}, "z": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999}}, ["x", "y", "z"]),
    }, ["dimension", "expectedWorld", "expectedSessionId"]) | {"allOf": [{"if": {"required": ["goal"], "properties": {"goal": {"required": ["type"], "properties": {"type": {"const": "interaction"}}}}}, "then": {"properties": {"strategy": {"const": "walking"}, "segmented": {"const": False}}, "not": {"anyOf": [{"required": ["lookAt"]}, {"required": ["waypoints"]}]}}}], "oneOf": [{"required": ["x", "y", "z"], "not": {"required": ["goal"]}}, {"required": ["goal"], "not": {"anyOf": [{"required": ["x"]}, {"required": ["y"]}, {"required": ["z"]}]}}]})


T_MCB_INTERACT = ToolSpec("mcb_interact", "player.interact", "Normal player use/attack at crosshair or explicit nearby x/y/z/side. Optional hitPoint={x,y,z} binds a block-local surface point in [0,1], with native ray tolerance 0.02 per axis (interactionHitPointVersion=1); reuse it in approach targets. This constrains the click, not its world effect. Reach and visible-face checks precede aiming/action. Explicit use requires interactionViewBinding; explicit attack requires interactionAttackViewBinding. Both aim and synchronize look, retaining target view by default. Developer restoreView=true preserves the original view; receipts describe submission, never world effects. For standalone distant block use, prefer mcb_approach rather than hand-computed feet/view; goal tasks already own approach. Submit a GUI-open interaction once, then observe its actual expected screen/window and synchronized text/slots/widgets with bounded read-only waits; delayed opening or placeholder content never permits another click. Optional expectedHeld={slot,registryName,metadata} checks the selected hand before aiming/use; it does not assert full NBT/count identity. Optional sneaking is use-only and restores normal sneak packets. Use with blueprint+sneaking on the actual controller builds; switch away from blueprint/tools before opening a machine GUI. Verify elevation/volume conflicts and subsequent real blocks/structure, not a hologram GUI. Paused server operations return world_paused.", {**obj({"action": {"type": "string", "enum": ["use", "attack"]}, **POSITION, "side": integer(0, 5), "hitPoint": INTERACTION_HIT_POINT_SCHEMA, "targetPart": AE_PART_IDENTITY_SCHEMA, "expectedHeld": INTERACTION_HELD_SCHEMA, "wrenchFacing": {"type": "boolean", "const": True, "description": "Native material GT wrench face-centre use with positive real tool durability; verify server facing afterward."}, "sneaking": {"type": "boolean"}, "restoreView": {"type": "boolean", "default": False}, "requireSurvivalFlight": {"type": "boolean", "default": False}}, ["action"]), "dependentRequired": {"x": ["y", "z"], "y": ["x", "z"], "z": ["x", "y"], "side": ["x", "y", "z"], "targetPart": ["x", "y", "z"], "restoreView": ["x", "y", "z"]}, "if": {"required": ["sneaking"]}, "then": {"properties": {"action": {"const": "use"}}},
        "allOf": [{"if": {"required": ["hitPoint"]}, "then": {"required": ["x", "y", "z", "side"], "not": {"required": ["wrenchFacing"]}}}, {"if": {"required": ["wrenchFacing"]}, "then": {"required": ["x", "y", "z", "side", "expectedHeld"], "properties": {"action": {"const": "use"}, "sneaking": {"const": False}}, "not": {"required": ["targetPart"]}}}, {"if": {"required": ["requireSurvivalFlight"], "properties": {"requireSurvivalFlight": {"const": True}}},
            "then": {"required": ["x", "y", "z"], "properties": {"action": {"const": "use"}}}}]})


T_MCB_CHAT = ToolSpec("mcb_chat", "chat.send", "Send up to 100 characters through native Forge client commands and player chat (Minecraft 1.7.10 packet limit). Works in player profile, singleplayer, LAN and servers without a server MCB requirement; the game/server enforces command permissions. sent is only submission, never proof of execution or permission. Inspect native feedback and actual state; commands are never retried. /give is not inventory receipt; await actual pickup/slot counts before moving. Survival marking uses NEI ghosts, never grants.", obj({"message": {"type": "string", "minLength": 1, "maxLength": 100, "pattern": r"^[^\r\n\x00]+$"}}, ["message"]))


T_MCB_SUPPLY_ITEMS = ToolSpec("mcb_supply_items", "inventory.supply", "Batch 1..8 distinct untagged creative supplies through native commands in one server-thread operation. Exact expectedSessionId/expectedWorld and empty cursor required. Default retains the empty-inventory contract. With nativeSupplyPreserveInventoryVersion=1, preserveInventory=true preserves existing inventory and reserves capacity for the entire batch in the 36 ordinary slots before any command, accounting for exact identity/NBT and public stack limits. No equipment/offhand capacity or inventory clearing. Each count is additional stock, not a target total. Recipe/build tasks already own their supply; do not grant twice. Native command receipts are not inventory acceptance: reconcile the actual synchronized inventory once after the batch. Partial/unknown receipts never permit replay or chat fallback.", obj({
        "expectedSessionId": LIVE_ID, "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128},
        "preserveInventory": {"type": "boolean", "default": False},
        "items": {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True,
            "items": obj({"registryName": {"type": "string", "minLength": 3, "maxLength": 128, "pattern": r"^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+$", "not": {"pattern": r"[\s\x00-\x1F\x7F]"}},
                "metadata": integer(0, 32766), "count": integer(1, 64)}, ["registryName", "metadata", "count"]) }},
        ["expectedSessionId", "expectedWorld", "items"]))


T_MCB_APPROACH = ToolSpec("mcb_approach", None, "Uses the saved mcb_policy movement; execution strategy is internal. Prefer one call for standalone controller/port use goals. Recipe/build/QA tasks already own approach; do not duplicate it. Supply 1..8 exact block targets with observed targetIdentity/allowedSides when available. The client computes live 3D collision/support, normal dual reach, any visible point on an allowed face, safe direct travel before detours, braking and final view. Safe actually reachable feet are accepted even off the planned endpoint; precise mcb_navigate remains available. Single walking targets on walkingInteractionGoalVersion>=2 use native interaction-volume routing without a separate stand preview. move=false previews; multiple targets share stands and only complete known absence permits grouping. move=true executes the first group and proves actual dual arrival plus current target views. For one target, interact=true immediately submits exactly one normal right-click/use within this workflow, removing an agent round trip; hold the intended item first. Capability refusal occurs before movement. For a GUI-open goal add openGui:{}: one native client END-tick wait proves the actual dual-owner window and complete slots/NBT, returning actionable GUI state without guessed classes or another agent call. Optional bounded content predicates require already observed paths/values; unrelated custom widget state is not inferred. Without openGui, interaction is submission only. Never replay delayed use. Default compact success omits repeated geometry/route traces; includeDetails=true retains them, and failures retain diagnostics. Default interact=false retains movement-only behavior. Further groups need explicit requests. Flight requires already real dual flight. No implicit strategy fallback, retry, grants, mode change or persisted plans. Same actor/session/world/dimension throughout; failures retain actual phase timing, partial movement and use receipts.", obj({
        "expectedSessionId": LIVE_ID, "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128}, "dimension": DIMENSION,
        "targets": NAVIGATION_INTERACTION_TARGETS,
        "move": {"type": "boolean", "default": True},
        "interact": {"type": "boolean", "default": False, "description": "With move=true and one target, perform one normal use immediately after verified arrival."},
        "openGui": {**GUI_ACCEPTANCE_SCHEMA, "description": "With interact=true, wait in the client for the real matching native server/client window and every full slot/NBT. Empty object needs no guessed class. Optional observed content predicates also wait for custom text/widgets; no automatic replay."},
        "includeDetails": {"type": "boolean", "default": False},
        "timeout_ms": {**integer(1000, 60000), "default": 10000,
            "description": "Outer approach deadline. Distant walking/flight reuse bounded native segmented navigation; nearby interaction searches keep their own 15000ms bound."}},
        ["expectedSessionId", "expectedWorld", "dimension", "targets"]) | {"allOf": [{"if": {"required": ["interact"], "properties": {"interact": {"const": True}}},
            "then": {"properties": {"move": {"const": True}, "targets": {"maxItems": 1}}}},
            {"if": {"required": ["openGui"]}, "then": {"required": ["interact"], "properties": {"interact": {"const": True}}}}]}, False, idempotent=False)


T_MCB_FLIGHT_CHECK = ToolSpec("mcb_flight_check", "player.flight_check", "Inspect or explicitly probe actual native flying in unchanged survival or creative mode. action=inspect returns the bounded session-memory flightCapability; existing allowFlying alone remains unknown. action=probe acquires flight with ordinary double-jump and a bounded collision/support-checked vertical +2-block native route if no current confirmed/denied evidence exists. Missing actual permission records canFly=false without inputs; actual dual airborne flight records true. Gear/world/dimension/player/mode/permission changes and five-minute idle TTL invalidate old evidence. Probe retains flight, reports its native arrival receipt and never grants abilities, toggles game mode, retries or teleports. A timeout/unsafe route remains unknown, not proof of inability. Same-session snapshot.flightCapability is available without another call.", obj({"action": {"enum": ["inspect", "probe"], "default": "inspect"}, "expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD, "dimension": DIMENSION, "timeout_ms": integer(250, 6500)}, ["expectedSessionId", "expectedWorld", "dimension"]))


T_MCB_FLIGHT = ToolSpec("mcb_flight", "player.flight", "Set creative flight via normal player abilities synchronization in the expected local developer world. Requires action=set, flying and expectedWorld. Take off with a short normal jump and wait for onGround=false before enabling; set on the ground is refused. Observe snapshot flight's server sample before elevated teleport/interaction. Never automatically retried.", obj({"action": {"const": "set"}, "flying": {"type": "boolean"}, "expectedWorld": EXPECTED_WORLD}, ["action", "flying", "expectedWorld"]))


T_MCB_TELEPORT = ToolSpec("mcb_teleport", "dev.teleport", "Protected local teleport under saved tp policy. Requires current normal tp command permission; creative alone is insufficient. Exact world/session/dimension, loaded full body/support and fresh client/server arrival remain required. Unsupported creative destinations may enable existing flight permission, never grant it. changeDimension permits explicit native transfer; allowTrackingChunkLoad additionally permits its required tracking window and possible new chunks. No automatic retry", {**obj({"x": {"type": "number", "minimum": -30_000_000, "maximum": 30_000_000}, "y": {"type": "number", "minimum": 0, "maximum": 255}, "z": {"type": "number", "minimum": -30_000_000, "maximum": 30_000_000}, "dimension": DIMENSION, "expectedWorld": EXPECTED_WORLD, "expectedSessionId": EXPECTED_SESSION, "requireFlying": {"type": "boolean"}, "changeDimension": {"type": "boolean", "default": False}, "allowTrackingChunkLoad": {"type": "boolean", "default": False}}, ["x", "y", "z", "expectedWorld"]), "allOf": [{"if": {"required": ["allowTrackingChunkLoad"], "properties": {"allowTrackingChunkLoad": {"const": True}}}, "then": {"required": ["changeDimension", "dimension"], "properties": {"changeDimension": {"const": True}}}}]})
