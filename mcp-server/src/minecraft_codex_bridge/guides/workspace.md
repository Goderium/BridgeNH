# BridgeNH agent workspace and records

The Chinese operating guide ships with the source and Python package. Both `mcb_help({"topic":"workspace"})` and the MCP guide resource return it offline. This English edition is for human reference.

Use [capabilities](capabilities.md) for the complete tool-purpose index and [maintenance](maintenance.md) for hot updates, schema discovery and reconnect boundaries. Both are offline `mcb_help` topics; exact executable parameters come from `mcb_help(topic="tools",tool=...)` or the direct frontend tool schema.

## Objective and installation handoff

The [startup instructions](#reusable-startup-instructions) are the portable rule set for native operation, documentation language and recordkeeping.

Reuse the selected project and shared knowledge base, or a suitable `bridgenh-workspace/` directory. Keep records outside game saves, mod directories, Python runtimes and disposable installer folders. Create only missing records; ask for a location only if context cannot establish one. Read and merge existing `AGENTS.md` rules; load the file explicitly if the host does not.

For missing installation information, use `install.ps1 -Inspect -NonInteractive` and its `questions`; reuse valid configuration. Source configuration is `.mcb.local.json`; installed configuration defaults to `%APPDATA%/MinecraftCodexBridge/config.json`. An explicit configuration path takes precedence.

For explicit per-chat binding, follow [overview](overview.md#binding-permissions-and-policy); installation profiles are not defaults.

Create only directories that have a use:

```text
bridgenh-workspace/
  AGENTS.md
  EXPERIENCE.md
  knowledge/
    index.md
    mechanics/
    integration/
    troubleshooting/
  tasks/
    <task-id>/
      state.md
      evidence/
```

Use the project's existing local exclusions for personal records/configuration.

## Reusable startup instructions

Merge applicable instructions below into the operations project's agent file.

```text
Operate the selected GTNH client through BridgeNH native tools. Read the packaged operating guides in Chinese through mcb_help. Use English for other BridgeNH project documentation. Consult the companion edition when translating or reviewing it. Reply in the user's preferred language.

Read the existing project rules, related task state and experience index. Merge records into the selected workspace outside game saves, installed mods and disposable packages.

Search mcb_packdata for published quests, guides and recipes. Check changed recipes in live NEI. For reusable operating methods, query mcb_knowledge. For present inventories, machine state and action results, read the game. Find tools by purpose in the capabilities guide, then request the exact schema with mcb_help. Consult the detailed guide for the operation at hand.

For live work, bind this chat to the intended Prism directory and instance name. Verify session, world/server, dimension, policy and applicable capabilities. Resolve targets and GUI identities from current observations. Ask about missing details only when neither tools nor established configuration can supply them.

Plan materials, layout, access and dependencies once. Use the dedicated API for each supported operation. Reserve the virtual mouse for uncovered GUI gestures or explicit pointer tests. Reuse batches, bounded waits and built-in checks. After partial completion, timeout or unknown effects, reconcile the original action and actual inventory or world changes before continuing. Verify synchronized state and received products.

If a required step needs Computer Use, tell the user immediately and report an error under the workspace guide. This includes read-only checks, preparation and troubleshooting. Record any manual assistance. Use the testing guide to select relevant checks with the existing permissions and world, and identify pending native acceptance.

Keep current progress, uncertainties and recovery in task state, short lessons in EXPERIENCE.md and reusable mechanisms in knowledge/. Record source, version and verification scope, distinguishing observations from hypotheses and user preferences. At handoff, update the indexes and link evidence. Sanitize material written into the BridgeNH project. For tool changes, update the capability index and bilingual guides, then follow the maintenance guide to verify the loaded catalog.
```

## Record boundaries

| Record | Content |
| --- | --- |
| `tasks/<task-id>/state.md` | Goal and acceptance, selected environment, completed/remaining actions, uncertain effects, recovery order, tests and evidence links. |
| `EXPERIENCE.md` | Short trigger, useful method or mistake, preference/correction, applicability, evidence link and follow-up. |
| `knowledge/` | Reusable mechanisms, integration boundaries, diagnosis, version scope and sources; no temporary task coordinates or order state. |

A concise lesson template:

```text
Title/category: operating method | user preference | capability gap
Applies to: mod versions, environment, relevant permissions/policy
Status: unverified | partially verified | verified | needs review
Trigger/observation: what happened; user report, live readback or source evidence
Handling/result: what was done and actually verified; remaining uncertainty
Evidence: task reference, log, source location or documentation
Knowledge link: existing topic, if a reusable conclusion exists
Last verified: YYYY-MM-DD; change only after an actual recheck
```

A knowledge entry keeps its conclusion, prerequisites, evidence and limits. Versioned wiki/source material does not override conflicting live observations without investigation. Preserve a user's correction as reported; mark mechanisms needing experiments as unverified. Merge aliases and related entries instead of creating duplicates.

## Native-control gap and feedback template

As soon as a necessary observation or action requires Computer Use, tell the user which step is blocked and which native or CLI route was tried. This counts as an error even for a desktop read, setup or diagnosis. Report it in the selected instance with `mcb_run_log`. To open a new error, supply `category`, `title` and `observed`.

```json
{"action":"report","category":"error","title":"Desktop required for a readiness check","observed":"Native status did not expose the required readiness state.","expected":"Read readiness through the CLI or native tool."}
```

Keep the returned `id` in `tasks/<task-id>/state.md`. Update the same error with `action="report"` and its `id`, including the versions and steps in `reproduction`, the desktop action and readback in `observed`, and the proposed fix and native retest in `suggestion`. If the journal is unavailable, record those details in task state, deliver the feedback in this chat and file it when logging resumes.

At handoff, state the task outcome, the desktop assistance used and any remaining work. Cite the evidence and open issue ID or task reference. Full native acceptance stays pending until the same step succeeds through its intended interface with identity, permission and replay guards intact. Verify other steps independently. A screenshot can document a visual defect.

The bound client's virtual GUI mouse is native. When it compensates for a missing specialized operation, record that gap and the observed result. When the test specifically targets pointer display, hover or clicks, record the pointer behavior against its own criteria.

## Reusing built-in knowledge and logs

Use `mcb_knowledge` with `lessons` or `query` for relevant operating lessons; `learn` records evidence, `review` checks it and `retire` marks obsolete knowledge. Use current schemas and returned evidence IDs. If unavailable, maintain project files without blocking unrelated work. Cross-link structured lessons with files instead of copying entire logs. Connection-scoped evidence never authorizes a different server or action replay.

Use `mcb_run_log` to read or export historical evidence. `mcb_logs` and diagnostics inspect the selected client's logs/process; `mcb_recording(view="summary")` reviews passive history. Preserve dispatched/acknowledged/observed/verified/unknown distinctions. A history gap or missing stop marker leaves the outcome unknown.

For acceptance layers, see the [testing guide](testing.md).
