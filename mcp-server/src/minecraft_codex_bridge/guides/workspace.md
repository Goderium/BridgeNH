# BridgeNH agent workspace and records

Agent reference: English; [Chinese companion](workspace_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

This guide ships with the source and Python package. `mcb_help({"topic":"workspace"})` reads it offline; MCP guide resources also serve English only.

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
Use BridgeNH to operate the requested Minecraft client through native tools. Maintain reusable experience, task state and a local knowledge base during authorized work.

For routine use and development, read English documentation only and exclude *_zh.md from file and content searches. Use rg --files -g '!*_zh.md' for file discovery and rg -g '!*_zh.md' ... for content searches, or equivalent exclusions in other tools. Read relevant Chinese companions only when the current task requires maintaining, translating or reviewing them, including synchronizing translations after English edits. Limit the exception to those documents, keep English as the instruction source and restore the search exclusion afterward. Reply in the user's preferred language.

Read existing project rules, the related task state, EXPERIENCE.md and the knowledge index; retrieve only relevant topics. Reuse the selected workspace and merge existing records. Keep notes outside game saves, installed mods and disposable packages.

Explicitly bind this chat to the selected Prism directory and instance name. Verify live session, world/server, dimension, capability coverage and effective policy. Do not reuse historical IDs or another chat's binding. Ask only for information that cannot be obtained from current tools or established configuration.

Before searching source for an available feature, read the relevant capabilities index and detailed guide, then query the current tool description/schema. Use the maintenance guide for compatible hot reload, host rediscovery and reconnect boundaries. A stale host list does not establish that the worker lacks a feature; a server notification does not prove native host refresh. When maintaining tools, update the index and relevant English/Chinese explanations together.

Compose existing general tools for each task. Prefer structured observations, semantic GUI controls, native events, batch reads and bounded waits. Plan complex materials, layout, ports, orientation, access and dependencies once, then update changed parts. Preserve built-in preflight and acceptance; do not repeat them at every outer step. Use normal polling near one second unless a supported waiter defines otherwise.

After timeout, partial completion or unknown effects, inspect the same action and reconcile before continuing. Do not replay a batch or replace its request ID to bypass uncertainty. Plans, submitted receipts and cached text are not completion. Verify real blocks, synchronized settings, full inventory identities and actual products.

When a required operation has no native path, record a capability gap with evidence and native completion criteria. Do not silently use Computer Use, OS input, manual clicks or save edits. An explicitly authorized temporary workaround remains external assistance and leaves the native gap open.

Choose relevant regression, optional QA and live client/server checks using the testing guide. QA is optional; do not install it, raise permissions or switch worlds solely for validation. Record skipped/unavailable layers and unresolved acceptance. testPassed is not fullFlowVerified; mocked tests, QA and singleplayer results cannot stand in for native GUI or dedicated-server acceptance.

Record useful methods, failures, user corrections and tool gaps promptly. Task state holds current locations, orders, progress, recovery and evidence. EXPERIENCE.md holds short searchable conclusions. knowledge/ holds reusable mechanisms with versions, source, scope and verification status. Separate preferences, observations, source conclusions and hypotheses; revise obsolete entries and update verification dates only after real checks.

At a checkpoint or handoff, save unresolved actions and recovery steps, update relevant indexes and briefly report changes, evidence and remaining gaps. Link supporting records instead of copying full logs/chat histories. Sanitize content before writing it into the BridgeNH (MCB) project.
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

Use one issue/task reference consistently in project records and `mcb_run_log` reports. Record failed native observations as well as failed actions, including controls currently usable only through Computer Use.

```text
Issue/task reference:
Goal and required native step:
Environment: BridgeNH/mod versions, local or remote, policy/capability scope
Preconditions and minimal reproduction: native tools and arguments
Expected native behavior:
Observed behavior: error, missing coverage, partial effects, actual readback
External assistance: none | proposed | explicitly authorized and used
Why the native path is incomplete:
Evidence and recovery state:
Proposed general capability/fix:
Acceptance: complete the same task through native tools, verify real effects,
  require no external input, preserve identity/permission/no-replay guards
Status: open | implemented, awaiting native verification | verified
```

A screenshot can support a visual defect; it does not replace structured state or prove a completed action. Do not mark a gap resolved merely because a human clicked the control. Keep working on independent native steps when possible and report the concrete blocker.

## Reusing built-in knowledge and logs

Use `mcb_knowledge` with `lessons` or `query` for relevant operating lessons; `learn` records evidence, `review` checks it and `retire` marks obsolete knowledge. Use current schemas and returned evidence IDs. If unavailable, maintain project files without blocking unrelated work. Cross-link structured lessons with files instead of copying entire logs. Connection-scoped evidence never authorizes a different server or action replay.

Use `mcb_run_log` to read/export relevant events and report issue findings. `mcb_logs` and diagnostics inspect the selected client's logs/process; `mcb_recording(view="summary")` reviews passive history. Preserve dispatched/acknowledged/observed/verified/unknown distinctions. A history gap or missing stop marker is unknown, not proof that nothing happened.

For acceptance layers, see the [testing guide](testing.md).
