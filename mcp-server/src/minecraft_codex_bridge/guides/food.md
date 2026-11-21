# Survival food

Use `mcb_inventory` to identify a real hotbar stack. Close the GUI and use a living survival actor with normal hunger under a writable policy. Broader policy ceilings permit the same native survival action. Both actions leave the requested slot selected after use. Ordinary food at full hunger returns `already_full` before changing slots.

## Eat one serving

```json
{"action":"eat","slot":2,"registryName":"minecraft:apple","metadata":0}
```

Pass these arguments to `mcb_food` with the observed slot, registry name and metadata. BridgeNH performs one ordinary use of an item whose native use action is eating. It keeps the use binding active only while that serving is in use. The result `eaten` requires the server's finish-use signal for the same player and serving, one serving removed or its final container returned, and an increased food level in both the client state and a fresh server health update.

## Keep hunger replenishing

```json
{"action":"healing_axe","slot":4,"registryName":"ExtraUtilities:defoliageAxe","metadata":0}
```

In GTNH 2.9.0, Extra Utilities 1.2.12 runs the Healing Axe's passive effect while it is the selected item. At each multiple of 40 world ticks it adds one hunger point with a saturation modifier of 0.2. At 20 ticks per second this is one point about every two seconds. Heavy exertion can consume food faster. Leave the axe selected to continue toward full hunger. Changing the selected slot stops the passive benefit until the axe is selected again. [GTNH 2.9.0 mod list](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/2.9.0/README.md) · [Extra Utilities 1.2.12 release](https://www.curseforge.com/minecraft/mc-mods/extra-utilities/files/2264383)

Selecting an axe at full hunger returns `selected_at_full`. With a hunger deficit the tool waits for the first confirmed increase and returns `replenished`. Continued feeding is supplied by the item itself. When `passiveWhileSelected` is true, stop it by selecting the next real hotbar slot with `mcb_hotbar`. BridgeNH does not reselect the axe in the background.

## Read the result

While a serving is in use or the first axe gain is pending, another connected MCP worker can read `mcb_task_status`. The owner is `survival.food`. Call `mcb_task_cancel` with `action="cancel"`, the same `expectedSessionId` and its exact `taskId` to stop that observation and release its use key. The original call and cancellation report the same food receipt and task ID. Inspect the counts if use had already started. The axe keeps its ordinary passive effect while selected even after observation ends. Switch slots to stop feeding.

`timeout_ms` bounds the observation to 250–6500 ms and defaults to 5000. Compare `foodBefore`, `foodAfter`, `serverFoodAfter` when present, and the item counts. `foodGainVerified` reports an observed increase. For an already-full result it is false. A changed hand, interrupted use or unconfirmed effect returns a partial receipt. Check actual hunger and inventory before another use. A server running the updated BridgeNH also exposes the connected player's `food` and `foodSaturation` through `mcb_server_observe(kind=self)`.
