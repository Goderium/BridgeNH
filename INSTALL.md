# BridgeNH installation

[English](INSTALL.md) | [简体中文](INSTALL_zh.md)

Use one compatible BridgeNH mod JAR per client; do not install it alongside an older MCB JAR.

## Startup window mode

`mcb_launch` accepts `windowMode`: `minimized` (default, minimized to the taskbar
without activation), `background` (visible without activation), or `normal`.
All three retain a taskbar button. The source CLI accepts `--window-mode` with the same values.
The selected mode is stored in the client's `config/codexbridge/window-startup.txt`
and also applies to manual Prism launches. A missing preference defaults to minimized.
This requires the BridgeNH early SDL window hook; restart the selected client after
updating its JAR. Verify the actual result with `mcb_window` after startup.

These modes control the game window on the Windows SDL backend. Prism login,
update and launch-progress dialogs remain launcher-controlled and can activate
before Java starts. BridgeNH does not suppress dialogs in a shared Prism process.

## Install from the download

Extract the ZIP, then double-click **install.cmd**. No administrator rights are
required. This runs the local PowerShell installer with a process-only execution
policy; it does not change the machine's execution policy. The package is not
code-signed. Obtain it from a trusted distributor and check its SHA-256 checksum.

Supported automatic installation: **Windows, an existing Minecraft 1.7.10
Prism/Forge instance, Python 3.11+ with pip/venv, and Codex**. Client prerequisite mods: **UniMixins, StructureLib and lwjgl3ify**.
Internet access to the configured Python package index is needed for MCP
dependencies. The ZIP is not an offline Python environment.

Horizon-QA and TST-QA are optional testing integrations; this installer does not
install or enable them. For validation, see the
[layered testing guide](mcp-server/src/minecraft_codex_bridge/guides/testing.md),
also available through `mcb_help({"topic":"testing"})`.

The installer finds installed Python and suggests known Prism locations. It asks
for the **Prism data directory first**, then the **instance name** within that
directory. Names can be display names from
Prism or instance folder names. Missing or ambiguous names require explicit
selection; the newest game is never selected automatically. If Python is absent,
install Python 3.11+ and rerun, or give the agent an existing interpreter path.
Automatic game launch retains MCB's existing **portable Prism** requirement;
use `-ManualLaunch` if you start the game yourself.

The installer creates an isolated MCP Python environment, verifies its imports,
installs only the MCB JAR, and registers one stdio MCP server. It reuses an existing
MCB registration found in Codex's global/current/known-project configs, preserving
unrelated settings and existing tool approval overrides. If several registrations
exist, it asks which config to update. It never increases an approval setting.
Legacy direct connection selectors in that registration are replaced by the
selected inventory profile. Every new chat explicitly binds its requested client with
`mcb_clients` and `mcb_connection`; the installation profile is not a chat default.

When a JAR change is needed, close the **selected** client before installing.
An identical JAR is left alone even if the client is running. Older MCB JARs are
backed up under the selected instance's
`.minecraft/config/codexbridge/mod-backups/`; no other mod is replaced.
Changed configuration files get timestamped `.bak` copies. A failed dependency
installation does not register an unusable MCP. Earlier successful phases may
remain after a later failure; the error and `logs/install.log` identify the phase.
Source-checkout installation logs use the ignored `build/logs/install.log` instead.
Rerun the same installer after fixing the reported issue.

Start the selected game after installation. The current chat's already running
MCP process is not replaced by editing its config. Use the host's reconnect when
available; otherwise use a fresh connection/chat, or restart Codex once if the
host requires it. Ordinary compatible Python worker changes still use MCB's
existing hot reload.

## Ask an agent to install

Say: **“Install BridgeNH from this extracted folder.”** The agent can run:

```powershell
.\install.ps1 -Inspect -NonInteractive
```

This is read-only and prints JSON. `status=needs_input` contains `questions`
with stable `key`, readable `question` and optional `choices`. Ask the user for
these missing values, then supply the corresponding named argument. Do not guess
between several game instances. Example:

```powershell
.\install.ps1 -Inspect -NonInteractive -PrismRoot '<Prism directory>' -InstanceName '<instance-name>'
.\install.ps1 -NonInteractive -PrismRoot '<Prism directory>' -InstanceName '<instance-name>'
```

The matching Python options are `--prism-root` and `--instance-name`. The legacy
`-Instance '<full instance path>'` and optional `-Prism '<prismlauncher.exe>'`
remain supported; do not mix the legacy instance selector with the new pair.

Other parameters: `-Python`, `-Profile`, `-Config`, `-CodexConfig`, `-Scope user`,
`-Jar`, `-ManualLaunch`. `-ConfigureOnly -RuntimePython '<existing MCP python.exe>'`
uses an already installed MCP and mod without reinstalling either. Exit 0 means
success/ready, 2 means missing input, and 1 means a failure. The wrapper emits JSON
for automation; normal installation prompts only when input is missing.

The existing MCP source/installed tool runner is shared:

```powershell
.\scripts\mcb.ps1 -Profile default -Action Snapshot
.\scripts\mcb.ps1 -Profile default -Action Call -Tool mcb_health
```

After the extracted folder is deleted, the registered MCP still works. The
installed interpreter can also run `-m minecraft_codex_bridge.setup --show-config`
and `-m minecraft_codex_bridge.tool_cli mcb_snapshot --transport in-process` with an
explicit profile/runtime selector, without checkout scripts. These are temporary CLI
connections, not the live chat's binding.

## Agent project and local knowledge

Read [AGENTS.md](AGENTS.md) for project rules, including documentation language. The [workspace guide](mcp-server/src/minecraft_codex_bridge/guides/workspace.md) provides a startup template, task records and native-control feedback. Merge its relevant rules into the selected project; keep notes outside the installer folder and game saves.

Installation inspection and success JSON include `agentGuide` with the source file and `mcb_help({"topic":"workspace"})` entry. The fixed resource is `minecraft-codex-bridge://agent-guides/workspace`. It remains available offline in the installed Python package after the extracted folder is removed, without a game session or token.

## Configuration locations

| Installation | Configuration | Python runtime |
| --- | --- | --- |
| Source checkout | `<checkout>/.mcb.local.json` (Git ignored) | `<checkout>/mcp-server/.venv/` |
| Download / MCP + mod only | `%APPDATA%/MinecraftCodexBridge/config.json` | sibling `runtime/` directory |

Explicit `MCB_CONFIG_FILE` or `-Config` overrides the location. The same version 1
JSON schema is used in both cases. For installer/standalone CLI operations,
`activeProfile` selects a named profile; `MCB_PROFILE` or `-Profile` selects another.
`activeProfile` may be omitted/null for an inventory-only configuration. Profile paths may be absolute or
relative to the config file. Keep credentials out of this file: the mod continues
to generate and rotate its token in the selected instance's existing
`.minecraft/config/codexbridge/runtime.json`.

New profiles keep the two selection fields separate:

```json
{
  "version": 1,
  "activeProfile": "default",
  "profiles": {
    "default": {
      "prismRoot": "<Prism directory>",
      "instanceName": "<instance-name>"
    }
  }
}
```

`prismRoot` may be relative to the configuration file. `instanceName` is a name,
not a path. The installer saves the resolved instance folder name. Legacy
`instancePath`/`prismExe` profiles remain readable; a profile must not mix
`instancePath` with the new pair. Configuration remains an inventory, not a chat binding.

Supervised chat MCP treats these profiles and launch paths as inventory only and
starts unbound. Use `mcb_clients(prismRoot="<absolute Prism directory>")`, then
`mcb_connection(action="bind", prismRoot="<same directory>", instanceName="<selected name>", ...)`.
Binding also requires the actual conversation ID, current binding generation and
a fresh request ID. A listed `clientId` remains an alternative to the directory/name
pair, particularly for ambiguous display names; do not combine both selectors.
Selection never falls back to another directory or a recently used client. Binding
status reports the resolved `prismRoot`, `instanceName` and full `instancePath`.
The host must launch a separate stdio frontend per conversation.
When MCP startup lacks `CODEX_THREAD_ID`, the first successful explicit bind pins the
caller-declared actual conversation ID; status labels this `explicit_bind_request`.
Obtain that ID from the chat's execution environment, never invent/copy another chat's
ID. Later mismatched owners are refused, even after unbind. The status and health tools
report the actual binding instead of inferring it from shared defaults. Switching is
explicit and refused while original-client work is unresolved. Compatible worker hot
reload retains the binding; a full frontend reconnect starts unbound again.

Legacy explicit `MCB_INSTANCE_PATH`, `MCB_RUNTIME_FILE`, or `MCB_URL`/`MCB_TOKEN`
remain supported by standalone in-process adapters. Do not leave stale selectors in
an external CLI launcher. They do not redirect a live chat connection. Different projects
still need access to the MCB registration; conversation binding does not edit Codex config.

Machine installation configuration is separate from player groups and operation policies. Read the [permissions and policy reference](docs/PERMISSIONS.md) before selecting them; neither grants game authority.

## Source builds and release packaging

Run Gradle from the repository root with **JDK 25**. Gradle automatically resolves
the Java 8 compilation toolchain and the fixed StructureLib, UniMixins,
RetroFuturaBootstrap, Gson and ASM APIs from Maven. No installed game or machine configuration
is needed to build. These APIs are not bundled in the mod.

```powershell
.\gradlew.bat --no-daemon build
python -m pip install -e ".\mcp-server[test]" "build>=1.2,<2"
python -m pytest mcp-server/tests
python -m build --outdir build/dist mcp-server
python scripts/prepare_release.py
```

The runtime JAR is `build/libs/BridgeNH-<version>.jar`; release assets and SHA-256
checksums are collected in `build/dist/`. After building, `install.ps1 -Scope source`
can configure a development installation. It does not need local dependency JARs.

Update `modVersion` in `gradle.properties` and `__version__` in
`mcp-server/src/minecraft_codex_bridge/_version.py` together. Release validation
rejects mismatched versions or tags. The Actions workflows build and test pushes
and pull requests; a matching `v<version>` tag additionally creates a GitHub
Release containing the runtime JAR, Windows ZIP, Python wheel/source distribution
and checksums. Prerelease versions are marked as prereleases.

The ZIP uses an explicit file allowlist: installer, Python package, runtime JAR,
usage instructions and a placeholder example. It excludes local config, tokens,
worlds, logs, caches and virtual environments. `manifest.json` contains file
hashes and the ZIP has a separate `.sha256` file.

Codex registration follows the official
[stdio MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).
