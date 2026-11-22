# Plan a finite production line

`mcb_plan_production` builds a multi-stage graph from the bound instance's indexed NEI recipes and calls the pinned GTNH Factory Flow solver with its HiGHS WASM asset. It reads the catalog while the game is closed. The returned plan describes the supplied inventory, machine pools and tick horizon. Physical machine placement, actual power delivery, recipe completion and collection each require an in-game observation.

## Prepare the solver

Publish the instance's NEI index as described in the [pack data guide](packdata.md). Install Node.js 22 or later and use the MCP Python environment. From the BridgeNH source or extracted installer directory, run the following once for the selected instance. The Git object store must contain the named Alu commit. A sparse checkout is sufficient. No npm install is needed.

```powershell
git clone --no-checkout https://github.com/aruru-project/alu_gtnh_aiplayer.git <alu-checkout>
python scripts/plan_production.py --instance <Prism-instance-directory> --install-solver <alu-checkout>
python scripts/plan_production.py --instance <Prism-instance-directory> --request <request.json>
```

The installer reads immutable Git blobs, verifies asset digests and places them with their license notices beside this instance's index. The MCP process must see the same index root and a `node` executable. The solver runs in a bounded subprocess with an 18-second deadline. Each run verifies the runtime and WASM hashes. The reference machine catalog and GregTech metadata confirm the model and the declared tier for supported single-block electric machines. The actual number installed and live supply come from the caller. [Solver versions, licenses and hashes](https://github.com/aruru-project/alu_gtnh_aiplayer/blob/2abfae1a423f407f36cd9f73c0ede81a01e9a706/brain/vendor/gtnh-planner/provenance.json) · [GregTech machine identifiers](https://github.com/GTNewHorizons/GT5-Unofficial/blob/bb00f3f1023e20d3cce84cf09abb442d22a3a5a5/src/main/java/gregtech/api/enums/MetaTileEntityIDs.java)

## Plan four bronze plates

The same JSON can be passed to the MCP tool or the CLI. Gather item identities, recipe IDs, handler keys and available machine variants from `mcb_packdata`. Use exact `namespace:item@metadata` and `fluid:name` identifiers. Item quantities are units and fluid quantities are mB. The target is a final available quantity. Existing target stock counts toward it. Raw feed boundaries keep deficits in the specified material.

```json
{
  "target": {"resource": "gregtech:gt.metaitem.01@17300", "amount": 4},
  "inventory": [
    {"resource": "gregtech:gt.metaitem.01@11035", "amount": 3},
    {"resource": "gregtech:gt.metaitem.01@11057", "amount": 1},
    {"resource": "gregtech:gt.integrated_circuit@1", "amount": 1}
  ],
  "feedstocks": ["gregtech:gt.metaitem.01@11035", "gregtech:gt.metaitem.01@11057"],
  "machines": [
    {"handler":"gt.recipe.alloysmelter","machine":"gregtech:gt.blockmachines@201","tier":"LV","count":1},
    {"handler":"gt.recipe.metalbender","machine":"gregtech:gt.blockmachines@221","tier":"LV","count":1}
  ],
  "horizonTicks": 1200
}
```

In GTNH 2.9.0 the alloy smelter produces four bronze ingots in 200 ticks at 15 EU/t. The bender runs four 76-tick batches at 24 EU/t with circuit 1 retained. The machines take 504 ticks in series, averaging 8.58 EU/t over the 1200-tick window. Both machines running simultaneously would draw 39 EU/t.

For a chosen route, supply `expectedRevision` from this instance's NEI status and one `recipeChoices` entry per selected output. Use the exact output as `resource` and the matching `mcb_packdata` search result’s `id` as `recipeId`. Record IDs can change when the export changes. Pin the intermediate alongside the product to specify both stages. An unavailable or incompatible pinned record is rejected. To evaluate tin dust, replace the tin inventory and raw-feed entry with `gregtech:gt.metaitem.01@2057`. The export contains a separate alloy recipe for it.

## Read the result

For an electric blast furnace, declare the controller as `machine`, the heating coil key as `coilTier`, and the exact standard energy hatch as `energyHatch`. The model uses one standard hatch per furnace at the declared tier. For example, an HV furnace with cupronickel coils uses `gregtech:gt.blockmachines@1000`, `cupronickel` and `gregtech:gt.blockmachines@43`. Check the formed structure, maintenance, exhaust and power delivery in the world.

A returned reagent can supply a later batch. Include the initial charge in `inventory` and declare its resource in `feedstocks` to model a supply boundary. Read the ordered steps for the first use and the return before planning the next withdrawal. For example, the tungstate route returns calcium chloride after acid treatment.

- `steps` lists integer batches in material order, each recipe reference, consumed and produced totals, retained tools and the selected material per input slot. A GT zero-count mold or configured circuit is reserved for reuse. The model uses only exported option sets and ore membership for an explicitly named ore key. An exact GT ingredient does not gain arbitrary ore-equivalent substitutes. Unused inputs and deterministic surplus are reported under `remainingInventory`. Reserved tools are listed separately.
- `flows` shows boundary amounts and their expected and solved rates in items/s or mB/s. The graph connects intermediate outputs to downstream inputs. A molten-bronze route can use the MV fluid extractor, a LV fluid solidifier and a retained plate mold. One ingot supplies 144 mB and each plate consumes 144 mB. An initial 144 mB of fluid can replace one extraction batch.
- `missing` describes supplies needed before the conditional batch can run. `machinePools` compares fractional demand with whole installed machines. `averageEUt`, `integerBuildPeakEUt` and `serialScheduleTicks` use different denominators. Each serial stage may divide independent batches among available identical machines with enough fixtures. The serial schedule is a sufficient bound. An overrun can require scheduling analysis even when average capacity is adequate.
- Read `status`, `issues`, `guards`, `rejectedRecipes` and native solver diagnostics. Candidate choice favors the declared stock. The first funded route can end the search, so it does not prove a global optimum. A recursive material dependency without a proven finite startup sequence is flagged. Probabilistic yields, ambiguous or lossy NBT, wildcards, unverified special conditions, unmodelled machines, multiblock configuration and search limits require another source or route. Exporters may omit hidden NBT or native restrictions. A tag-sensitive process needs original in-game recipe and stack verification.
- `installationInputs` reports the most recent matching capture. A `refresh_recommended` result calls for re-export or a native recheck. `not_checked` means no matching input check is available. A file match covers the recorded inputs only. Confirm the bound world's machine, power, inputs, retained tools, intermediate products and final delivery before recording actual production.
