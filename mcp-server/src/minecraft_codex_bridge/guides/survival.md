# Basic survival actions

Bind the intended client. Read the current session, dimension, player, inventory and `lifecycleTarget.expectedWorld`. Survival actions operate on the player's actual items. General controls also work in creative mode under the current policy. Creative supplies and test-server rules have separate entry points.

| Goal | Native operation and result |
| --- | --- |
| Eat or maintain hunger | See [food](food.md). An ordinary food use consumes a serving. A selected Healing Axe restores hunger through the item's own ticks. Switching to another tool suspends its effect. |
| Mine a block and collect its drop | Select the tool, aim at the loaded block, then call `mcb_mine` with the observed block ID, metadata, position and selected hotbar slot. It owns a bounded hold on that target. Inspect `mcb_task_status` for its ID and stop reason. Check the block and the actual matching inventory count before and after walking within pickup range. Tool-specific area effects, another player and disappearing drops can affect the outcome. |
| Walk or follow | Use `mcb_navigate` or `mcb_approach` for the observed destination or interactable face. `mcb_follow` tracks a loaded player in supported integrated worlds. Remote ground navigation has its own advertised coverage. |
| Use an item, equipment or an animal | Use `mcb_hotbar`, current inventory/equipment slots and normal GUI clicks. For a visible animal use `mcb_entity_interact` with the observed crosshair UUID and dimension, optionally pinning the held item. The `accepted` field reports the client callback. Before another use, check the animal and inventory for the actual effect, such as wool received and shears durability consumed. |
| Deposit or withdraw a stack | Open the chest through a reachable face. Read the current screen, window, actual source slot, stack and cursor. `mcb_chest_transfer` makes one normal shift-click in a vanilla chest. Its quantities describe the immediate client container. A client can predict zero movement before the server accepts the click. Compare synchronized counts on both sides before another transfer. For a tagged stack, read the same slot with `mcb_gui_tooltip` and use `item.nbt` only when `nbtTruncated` is false. Other container types and partial-stack splits use the normal `mcb_slot_click` rules. |
| Craft or process an item | Read a recipe from the instance [pack data](packdata.md) or NEI. Use real ingredients and an observed crafting grid or machine GUI. Read the output slot, take the product, then verify consumed inputs, returned tools/containers and received products. For supported quantitative machine workflows see [operations](operations.md). |
| Monitor a machine process | Before advancing, check actual inputs, machine progress, intermediate buffers and delivered stock. After an interruption, read the current inventory and reconcile it with the task record. |
| Use an ME terminal | Read the opened terminal's synchronized stock and select an exact resource. Use its native actions for withdrawal or deposit and verify player and terminal counts. See [AE2](ae.md). |
| Read a quest or submit materials | Look up static requirements in [pack data](packdata.md). Read `mcb_quest_progress` for the current player, then use `mcb_quest_step` for one supported action. Check shared progress, actual submitted items and received rewards. |

## Quantitative collection and replenishment

Open a material order with `mcb_survival_order`. In `gather` mode, only items delivered by this order count towards the goal. In `replenish` mode, take from the listed sources in order until the destination holds `quantity` of the exact item. A target of 32 with 20 already stored requires 12 more. Supply the quantity as decimal text. Match the registry name, `damage` and full NBT. Omit `nbt` to select untagged items only.

```json
{"action":"open","orderId":"iron-refill","mode":"replenish","quantity":"32","item":{"registryName":"minecraft:iron_ingot","damage":0},"sources":[{"kind":"chest","x":10,"y":64,"z":12},{"kind":"chest","x":13,"y":64,"z":12}],"destination":{"kind":"chest","x":16,"y":64,"z":12},"expectedSessionId":"<session>","expectedWorld":"<lifecycleTarget.expectedWorld>","dimension":0}
```

Substitute the observed session, world, dimension and coordinates. A gather order uses `gather.bounds` to limit the search. For mining, supply the block identity, metadata and tool slot. The current storage endpoint fields and collection bounds are listed by `mcb_help(topic="tools",tool="mcb_survival_order")`.

Use `kind=chest` for a vanilla chest, `kind=drawer` with the compartment index for supported finite standard drawers, or `kind=me` with the mounted face for an active physical item terminal. The terminal uses the player's ME insertion or extraction permission. Supply accessible locations and a free hotbar slot for opening storage. Walking between endpoints uses the bound client's available survival navigation.

1. Open a unique `orderId` in this conversation. Advance using its latest `expectedRevision`, current session, world and dimension. Each call processes at most `maxSteps` steps.
2. Read `gathered` for actual collection, `withdrawn` for stock taken from sources and `delivered` for material accepted at the destination. `inTransit` is the accounted quantity still awaiting delivery. `remaining` is the shortage, or `null` before the first destination observation. Inspect `reason` for a partial result.
3. If a reply is lost, read `status`. Use `reconcile` for a pending material change. After reconnecting, supply the current session and world target. Reconciliation checks the original instance, conversation, player, server or save and dimension. Inconclusive evidence leaves the action pending.
4. Other work may run between advances. Cancel an order at a settled boundary. Transferred items remain at their observed locations.

## Continue or insert work

A prepared set of native actions can run as one `mcb_sequence`. For an interruptible task, use `mcb_action_chain` with named segments. Each segment contains ordinary sequence steps. Every mutation needs a subsequent wait or assertion linked to the action through `accepts`, or an inline `after` condition. Choose a condition that verifies the intended effect. An inventory delta relative to a fresh baseline can establish pickup or delivery.

1. `open` validates the segments and binds the chain to the declared session, lifecycle world and dimension. It returns a chain ID and revision.
2. `advance` runs one segment and returns its receipt. A caller can continue by sending the next advance when ready. A queued native input task must reach idle before another segment starts.
3. At a boundary, `insert` prepends new segments to the pending work. For example, insert a meal between mining and walking, then reselect the mining tool in the segment that needs it. Bind fresh item and target identities inside their own segments. A sequence already executing completes or stops under its own rules.
4. Use the latest ID and revision for every advance, insertion or cancellation. `status` recovers the latest receipt after a lost reply. Failed or uncertain segments stop continuation. Reconcile their effects before making a fresh plan. A compatible worker reload retains the queue and requires an explicit advance. An MCP reconnection creates a new binding.

World changes, player handoff and active native tasks have distinct receipts. To stop an actual held input, use `mcb_task_cancel` with its observed task ID. Cancelling a pending chain discards its future work.
