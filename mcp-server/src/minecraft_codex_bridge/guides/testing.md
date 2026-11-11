# BridgeNH layered validation and optional QA

Agent reference: English; [Chinese companion](testing_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

Applies to Minecraft 1.7.10, the existing BridgeNH regression suites, optional Horizon-QA/TST-QA adapters and native client operation. Read offline with `mcb_help({"topic":"testing"})`.

## Choose evidence for the change

State the behavior, expected result and environment first. Run the smallest relevant existing checks; expand only for new changes, failures or unresolved coverage. Routine movement/orders do not require development tests. An installed-package user does not need a source build.

| Change/goal | Regression evidence | Optional QA | Native acceptance |
| --- | --- | --- | --- |
| Validation, arithmetic, receipts, waiting | Existing Python/Java unit and protocol tests | Only if game execution matters | Affected tool path when required |
| Recipe consumption, ME buffers and delivery | Identity/NBT/long counts and failure boundaries | Existing finite recipes, ME output or declarations | Real logistics, order and collection |
| Orientation, ports and layout | Transform/binding regressions | Existing cases or generic declarations | Actual placement and structure checks |
| Flight, approach, rays, mining and GUI | Control/synchronization/failure regressions | Server logic covers only part | Actual agent-facing native client path |
| Cross-dimension or sustained production | Boundary/protocol checks | Bounded local portions | Normal ticks, loading, IO and required duration |
| Docs, installation and configuration | Offline reads, parsing, links, bounds and affected config checks | Unneeded | Reconnection/read-only interfaces only when relevant |

Mocked tests, in-game QA, an integrated server and a dedicated server are distinct evidence. QA does not replace GUI/player acceptance; singleplayer results do not establish remote-server support. Source developers reuse `mcp-server/tests` and the root Gradle `bridgeSelfTest` task; compile Java changes as required by the project. Ordinary distribution users need no development dependencies.

The native-control target requires end-to-end operation without Computer Use, external input or human completion of missing steps. If required, report that assistance and keep native acceptance open using [workspace.md](workspace.md).

## QA remains optional

Horizon-QA provides a test framework; TST-QA provides related cases and public private-dimension integration. Neither is required for ordinary BridgeNH use, server dialogue, permissions or reads. Do not install/enable QA, elevate policy or switch client/world without task authorization. Client prerequisites remain UniMixins, StructureLib and lwjgl3ify.

Discover relevant QA through current health and `mcb_qa_catalog(section="capabilities")`; query cases with bounded filters. Tool names alone do not prove that QA is installed, executable or already run. Missing mods, disabled policy, unsupported APIs, insufficient permission or absent cases are unexecuted layers with reasons. Continue independent authorized checks, but never mark skipped validation as passed or lower required acceptance silently.

A submitted QA failure/timeout/unknown result must still be tracked. Optional availability is not permission to ignore a failed run or submit it again.

## Policy and ownership

`mcb_policy` and local `/mcb policy qa auto|off|observe|test|manage` use `config/codexbridge/policies.json`, scoped by save/server. With default groups, `auto` means survival=off, survival_efficient=observe, creative=test, full=manage. Explicit QA settings only narrow the mode ceiling; lowering the main mode immediately narrows effective access.

| QA level | Scope |
| --- | --- |
| `off` | Capability discovery remains available; no new observation of unrelated runs or tests. |
| `observe` | Catalog, status and results; no test-area creation/cleanup. |
| `test` | Exact reviewed finite cases or supported declarations in an authorized test area. The private runner advertises `tstqa-controlled-policy-v1`; arbitrary Java callbacks are not a sandbox. No undeclared/global runall/diagnostic management. |
| `manage` | Supported management operations with real game/server authority and ownership/scope checks. QA teleport also requires `movement=tp` and actual command permission. |

Policy grants no OP. Dialogue roles grant no OP. `readOnly` blocks new tests and fixture cleanup. After downgrade/read-only, explicit owned-run reads, supported normal stop and release of finished records remain possible; they do not authorize other players' runs or global cancellation. The current private runner's normal stop drains supported running debug-source cases; it does not force-interrupt arbitrary finite callbacks.

`mcb_qa_command(command="clear",args=[runId])` clears only a finished owned area whose blocks still match the expected state. `forget` releases the finished record/lease and leaves blocks. Confirm runner support. Missing QA or disabled policy returns capability unavailability; distinguish pre-dispatch refusal from post-submission uncertainty.

## Reuse the existing execution flow

1. Bind the actual client, world/server, session, dimension and authority. Remote execution requires advertised server QA support. Use an authorized dedicated test area; preparation may place/remove blocks. Do not run on production machinery or create/reset worlds merely because a guide mentions testing.
2. Find installed cases first. Reuse finite recipe, ME output and numerical-boundary cases. Within supported semantics, express a new case with immutable `machineFlows`; do not add a per-machine BridgeNH tool or hard-coded complete production chain. Record unexpressible mechanisms as gaps rather than substituting an unrelated recipe.
3. Prefer `mcb_qa_test_once` for a known case. It owns fresh catalog/fixture planning, space preflight, one run, waits, result pages and post-checks. Do not duplicate these checks outside it. Selection queries do not replace fresh execution guards; time/request/space budgets still apply.
4. Require `requireFullFlow=true` for a finite machine flow. Use false only when native assertions/legacy scope are the intended result and explain that limit. Generic declarations retain their 1..10 limit and exact identity/recipe/fixture evidence.
5. Continue existing runs with `mcb_qa_run/status/results` and their actual runId. Use native `waitMillis` when advertised; otherwise ordinary agent polling is about one second. Do not add a faster outer poller or replace existing waiters.
6. Infinite diagnostics are not finite passes. Where authorized, preserve separate observation-ready, quantified output, normal-stop and recovery evidence. Stop only the owned run, never globally cancel.

QA can fix inputs/expectations while operating tools stay general. Native placement, GUI input and AE ordering still use reusable tools. Reuse existing workflow evidence instead of constructing the same machine for every layer.

## Truthful results, timing and cleanup

- `submitted` proves submission only. `testPassed` differs from `fullFlowVerified`; a full flow needs actual preparation, consumption, production, collection, stopping and required stability evidence.
- Result pages must belong to one runId. Partial/truncated/old/unowned/missing data stays unknown. Global runner state is not an owned-run result.
- Preserve complete item/NBT identity and decimal long quantities. Distinguish local ME provider buffers, storage cells, CPU receipt and player collection. Creative supply or injected boundary data does not prove survival cost, real manufacture or total capacity.
- GT-specific fast-forward advances only supported GT blocks/power tasks in the test area, not AE or global world time. AE delivery, cross-dimension logistics and timing acceptance require normal ticks; do not bypass a workflow's timing guards.
- Measure preparation-to-acceptance elapsed time, agent calls, internal requests and recovery cost. Separate accelerated/simulated time from real-world duration.
- Clean only verified task-owned test products. `forget` does not remove blocks. Preserve evidence still needed for diagnosis; do not expand cleanup to unrelated structures or a world rollback.

## Record the result

Reuse `tasks/<task-id>/state.md` from [workspace.md](workspace.md):

```text
Goal/change and acceptance:
Environment: versions, client/server, world/dimension, actual permissions/policy
Regression: passed | failed | skipped | unavailable; command, evidence, limits
In-game QA: passed | failed | skipped | unavailable; reason/case selection
QA correlation: actual selector/runId, testPassed/fullFlowVerified, pages, timing
Native client / dedicated server: separate actions, evidence and results
External assistance required or used; remaining native-control gaps:
Uncertain effects, recovery and cleanup:
Total elapsed time and call cost:
```

Add regressions nearest the root cause. Keep task evidence in task notes and reusable findings in the knowledge base. A server without QA can maintain the same records and use normal BridgeNH features.
