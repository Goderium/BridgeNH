"""List client identities and probe one explicitly selected instance, without takeover."""
from pathlib import Path

from .bridge import BridgeError
from .connection_binding import client_id
from .local_config import LocalConfigError, config_path, read_config, resolved_profile
from .prism_instances import PrismInstanceError, list_prism_instances, resolve_prism_instance
from .runtime_discovery import discover_runtime_candidates

MAX_CLIENTS = 64


def client_inventory(environ, prism_root=None):
    entries = {}
    def add(path, profile=None, source=None, prism=None, metadata=None):
        path = Path(path).resolve()
        key = client_id(path)
        if key not in entries:
            if len(entries) >= MAX_CLIENTS:
                return
            runtime = path / ".minecraft/config/codexbridge/runtime.json"
            entries[key] = {"clientId": key, "name": path.name, "instancePath": str(path),
                            "prismRoot": None, "instanceName": path.name, "displayName": path.name,
                            "profiles": [], "prismExe": None, "sources": [], "runtimeFilePresent": runtime.is_file(),
                            "liveStatus": "not_probed"}
        if profile and profile not in entries[key]["profiles"]:
            entries[key]["profiles"].append(profile)
        if source and source not in entries[key]["sources"]:
            entries[key]["sources"].append(source)
        if prism and entries[key]["prismExe"] is None:
            entries[key]["prismExe"] = str(Path(prism).resolve())
        if metadata:
            entries[key].update({key: metadata[key] for key in ('prismRoot', 'instanceName', 'displayName') if key in metadata})
    if prism_root is not None:
        try:
            for selected in list_prism_instances(prism_root):
                add(selected['instancePath'], source='prism_directory', prism=selected['prismExe'], metadata=selected)
        except PrismInstanceError as error:
            raise BridgeError(error.code, str(error)) from None
        return _inventory(entries)
    path = config_path(environ)
    if path.is_file() or environ.get("MCB_CONFIG_FILE"):
        try:
            config = read_config(path)
            for name, profile in config["profiles"].items():
                selected = resolved_profile(profile, path)
                if selected.get("instancePath"):
                    add(selected["instancePath"], name, "configured_profile", selected.get("prismExe"), selected)
        except LocalConfigError as error:
            raise BridgeError("CONFIGURATION_ERROR", str(error)) from None
    # Old launch settings are candidates only. They never select a target.
    if environ.get("MCB_INSTANCE_PATH"):
        add(environ["MCB_INSTANCE_PATH"], source="launch_candidate", prism=environ.get("MCB_PRISM_EXE"))
    runtime = environ.get("MCB_RUNTIME_FILE", "")
    if runtime and runtime.casefold() not in {"auto", "discover"}:
        candidate = Path(runtime)
        if len(candidate.parts) >= 5 and [p.casefold() for p in candidate.parts[-4:]] == [".minecraft", "config", "codexbridge", "runtime.json"]:
            add(candidate.parents[3], source="launch_candidate")
    for candidate in discover_runtime_candidates(dict(environ)):
        add(candidate.instance_path, source="runtime_discovery")
    return _inventory(entries)


def _inventory(entries):
    return {"clients": sorted(entries.values(), key=lambda row: row["instancePath"].casefold()),
            "limit": MAX_CLIENTS, "limitReached": len(entries) >= MAX_CLIENTS,
            "selection": "none; list only, explicit conversation binding required",
            "runtimeFileMeaning": "A runtime file is not proof the client is online. Binding performs authenticated health."}


def named_client(prism_root, instance_name):
    try:
        selected = resolve_prism_instance(prism_root, instance_name)
    except PrismInstanceError as error:
        raise BridgeError(error.code, str(error)) from None
    return {**selected, 'clientId': client_id(selected['instancePath']), 'profiles': []}


async def probe_bound_client(client):
    configuration = client.configuration
    binding = configuration.conversation_binding
    if not binding or binding["instancePath"] is None:
        raise BridgeError("CLIENT_NOT_BOUND", "Select a client for this conversation first.")
    before = configuration.read()
    health = await client.invoke("bridge.health", {})
    after = configuration.read()
    if before != after:
        raise BridgeError("SESSION_CHANGED", "Client restarted during binding; existing binding was preserved.")
    if (before.instance_path is None or before.session_id is None
            or Path(before.instance_path).resolve() != Path(binding["instancePath"]).resolve()
            or health.get("instancePath") != before.instance_path or health.get("sessionId") != before.session_id):
        raise BridgeError("BINDING_IDENTITY_UNCONFIRMED", "The selected runtime and live client identity did not agree.")
    return {"sessionId": before.session_id, "instancePath": before.instance_path,
            "gameAccessProfile": health.get("profile"), "readiness": health.get("readiness")}


def switch_blockers(worker):
    blockers = []
    if any(record.get("phase") not in {"completed", "blocked", "canceled"}
           for record in worker.adapter.ae_order_records.values()):
        blockers.append("pending_orders")
    if worker.uncertain or worker.failed_actions or worker.movement_failures:
        blockers.append("unresolved_actions")
    binding = worker.client.configuration.conversation_binding
    if binding and binding["instancePath"] is not None:
        from .dialogue import state_path, read_state, relay_running
        path = state_path(worker.client.configuration)
        state = read_state(path)
        if state.get("threadId") == binding["conversationId"] and relay_running(path, state):
            blockers.append("owned_dialogue_relay")
    return blockers
