# Execution database

`mcb_packdata` reads one bound Prism instance's published SQLite catalog, including while its game is closed. The default location on Windows is under `%LOCALAPPDATA%/MinecraftCodexBridge/packdata`. Use the same `MCB_PACKDATA_ROOT` for the importer and adapter when overriding it.

## Choose the source

| Task | Source |
| --- | --- |
| Survival materials, recipe discovery, quest definitions and manuals | Read the published catalog. Repeat planning against the recorded revision. For a finite order, supply the observed inventory and machine pools to [the production planner](planner.md). |
| A player's current progress, available items, energy, machine formation or delivery | Read the bound client or authorized server. Check actual consumption and output before advancing the order. |
| A changed recipe, GUI handler, modifier tooltip or discrepancy | Inspect [live NEI](nei.md) on the affected client with its actual scripts, display language and handler. Capture a new version for future offline work. |
| Creative and mod development | Compare exports before and after the change and test the affected behavior in the designated world. Creative supply and test fixtures use their existing explicit policy and actor requirements. |

## Look up records

`status` lists dataset revisions, original file hashes, handler coverage and the last recorded installation checks. Pass the previous `knownUpdateId` to see the five most recent new publications, including changes in other datasets. `hasMore` or an unknown ID directs the caller to the paginated history. `update_status` lists committed runs. `changes` reads their differences by update ID, kind or change, with before/after previews. Use the full JSONL audit for complete changed records. A new baseline identifies available coverage. A handler or optional dataset that stops reporting needs separate verification.

```json
{"action":"search","kind":"quest","query":"Motors","limit":5}
{"action":"related","resource":"gregtech:gt.blockmachines","metadata":201,"limit":3}
{"action":"search","kind":"recipe","handler":"gt.recipe.alloysmelter","resource":"gregtech:gt.metaitem.01","metadata":11300,"direction":"output"}
```

`related` returns exact item links from handlers, recipes, quest tasks and rewards, guide icons and item lists, structure controllers and materials, carving groups, blocks, tooltips and available RecEx records. Follow a handler ID with `search(kind="recipe", handler=...)`. Search text separately for mentions without a structured item identity. Use `get(kind,id,expectedRevision)` for full records. Follow `nextOffset` for long records or result pages. Use the returned dataset revisions as `expectedRevisions` when paging a related kind.

Recipe searches accept registry names, `fluid:<name>` or `ore:<key>`. NEI item input queries include known ore memberships and exported alternatives. Read quantities, probabilities, zero-count catalysts and handler-specific conditions in the original record. RecEx `registry_recipe` preserves the independent exporter’s registry row, source type and machine. Its coverage can differ from NEI.

## Maintain the database

Run the scripts from the source or extracted installer root with the adapter's Python environment. For quest and GuideNH capture use the pinned [Alu exporter](https://github.com/aruru-project/alu_gtnh_aiplayer/blob/2abfae1a423f407f36cd9f73c0ede81a01e9a706/tools/make-packdata.py). To compare or publish existing exports, supply their completed directories. Only the selected datasets are replaced.

```text
python scripts/update_packdata.py fetch --instance "<instance>" --exporter "<checkout>/tools/make-packdata.py" --language zh_CN --out "<workspace>/books" --no-publish
python scripts/update_packdata.py compare --instance "<instance>" --books "<workspace>/books"
python scripts/update_packdata.py update --instance "<instance>" --books "<workspace>/books"
python scripts/update_packdata.py get --instance "<instance>"
python scripts/update_packdata.py check --instance "<instance>" --nei "<export>"
python scripts/update_packdata.py compare --instance "<instance>" --nei "<export>" --audit-jsonl "<workspace>/preview.jsonl"
python scripts/update_packdata.py update --instance "<instance>" --nei "<export>" --audit-jsonl "<workspace>/changes.jsonl"
python scripts/update_packdata.py check-inputs --instance "<instance>" --datasets nei quests guide
```

The last command hashes the selected instance's static inputs on demand. It records additions, removals and changes relative to an export carrying an input manifest. Older exports report unverified inputs. NEI checks cover mod JARs, scripts, selected GT configuration and Prism component metadata. Server-side overrides and dynamic recipe providers require live checks. For example, the AE2 facade handler can return a different number of display recipes across launches with identical recorded inputs. Compare the affected handler and recipe before revising a production plan. Routine catalog queries only read the most recent check.

For a fresh NEI capture, use a stopped, isolated Windows Prism instance with the target pack, a JDK supporting `javac --release 8` and a production-remapped Alu JAR containing `alu.aiplayer.export.NeiExport`. Build the provider from the version listed in the [upstream export instructions](https://github.com/aruru-project/alu_gtnh_aiplayer/blob/2abfae1a423f407f36cd9f73c0ede81a01e9a706/docs/PACKDATA.md). The script launches that instance, loads a temporary world, captures the registered handlers and archives the world on exit. Use a new output directory for each capture. To review changes before publishing, run:

```text
python scripts/update_packdata.py fetch-nei --instance "<instance>" --provider-jar "<alu-exporter.jar>" --out "<workspace>/nei-new" --no-publish
python scripts/update_packdata.py fetch-tooltips --instance "<instance>" --nei-base "<workspace>/nei-new" --tooltip-item "gregtech:gt.blockmachines@140" --out "<workspace>/nei-sampled" --no-publish
python scripts/update_packdata.py compare --instance "<instance>" --nei "<workspace>/nei-sampled" --audit-jsonl "<workspace>/samples-preview.jsonl"
python scripts/update_packdata.py update --instance "<instance>" --nei "<workspace>/nei-sampled"
```

`fetch-nei` also accepts repeated `--tooltip-item` options in the same launch. `fetch-tooltips` enriches a complete baseline whose instance and installation inputs still match. Select up to 32 registry names with metadata. The default samples normal, Shift and Ctrl views in the client language and reports unavailable views. Other sampled items and modes are retained. Structure, carving and block exports are indexed when supplied. If launch acknowledgement is uncertain, retain the output directory. After the selected process exits, use `finish-nei --instance <instance> --out <output> --no-publish` to verify and finalize a completed run. For an incomplete run, `cleanup-nei` with the same selectors restores that run’s temporary files.

The temporary export world runs while the window is unfocused. The runner may close its initial vanilla pause menu once if the window has stayed in the background. Bringing the window to the foreground leaves any subsequent pause menu under player control. On exit the runner restores the focus setting. After an interruption, `cleanup-nei` restores the backed-up options and window mode once the selected client has stopped.

For an independent [RecEx](https://github.com/GTNewHorizons/RecEx) export, install the adapter's `recex` extra and supply the completed JSON file.

```text
python -m minecraft_codex_bridge.recex_packdata --instance "<instance>" --source "<export>/recipes.json" --mode compare
python -m minecraft_codex_bridge.recex_packdata --instance "<instance>" --source "<export>/recipes.json" --mode update
```
