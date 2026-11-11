"""Pinned offline diagnostics/logs reuse launcher validation, without starting processes."""
import json

import httpx
import pytest

from minecraft_codex_bridge import launcher
from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.conveniences import read_logs
from minecraft_codex_bridge.server import Adapter
from test_launcher import launch_settings, configured, java, prism, Workflow
from test_workflows import bridge


@pytest.mark.parametrize("state", ["stopped", "running", "unreadable", "query_failed"])
def test_host_diagnostics_never_infer_stopped_or_launch_from_incomplete_process_evidence(launch_settings, state):
    processes = [prism(launch_settings)]
    if state == "running": processes.append(java(launch_settings))
    elif state == "unreadable":
        processes.append(launcher.Process(30, "java.exe", "java.exe", None))
    elif state == "query_failed": processes = BridgeError("PROCESS_QUERY_FAILED", "Fixture query unavailable")
    backend = Workflow([processes])
    report = launcher._host_diagnostics(configured(launch_settings), "CONFIGURATION_ERROR", backend)
    assert backend.commands == [] and not report["nativeDiagnosticsAvailable"]
    assert report["readOnly"] and report["worldState"] == "not_observed"
    assert "secret-not-returned" not in json.dumps(report)
    if state in {"unreadable", "query_failed"}:
        assert not report["processInspectionComplete"]
        assert report["lifecycleRecovery"]["stage"] == "unknown"
        assert not report["lifecycleRecovery"]["launchPermittedByProcessState"]
    else:
        assert report["processInspectionComplete"] and report["javaRunning"] is (state == "running")
        assert report["lifecycleRecovery"]["stage"] == ("client_stopped" if state == "stopped" else "java_running_bridge_unavailable")


async def test_existing_diagnostics_and_wait_entry_work_after_runtime_is_removed(launch_settings, monkeypatch):
    backend = Workflow([[prism(launch_settings), java(launch_settings)], [prism(launch_settings)]])
    monkeypatch.setattr(launcher, "WindowsBackend", lambda: backend)
    requests = []
    async def respond(request):
        requests.append(request)
        raise AssertionError("Missing runtime must not contact an arbitrary bridge")
    client = BridgeClient(configured(launch_settings), transport=httpx.MockTransport(respond))
    try:
        adapter = Adapter(client)
        result = await adapter.call_tool("mcb_wait_for", {"tool": "mcb_diagnostics", "args": {},
            "condition": {"path": "lifecycleRecovery.stage", "operator": "equals", "value": "client_stopped"},
            "timeout_ms": 3000, "interval_ms": 100})
        assert not result.isError, result.structuredContent
        assert result.structuredContent["data"]["matched"] and result.structuredContent["data"]["attempts"] == 2
        result = await adapter.call_tool("mcb_diagnostics", {})
        assert not result.isError and result.structuredContent["data"]["source"] == "configured_instance_host_process_observation"
        assert requests == [] and backend.commands == []
    finally:
        await client.aclose()


async def test_online_diagnostics_retains_native_source_without_host_inspection(bridge, monkeypatch):
    adapter, state = bridge
    monkeypatch.setattr(launcher, "WindowsBackend", lambda: (_ for _ in ()).throw(AssertionError("No host process scan for a healthy native read")))
    state["data"]["client.diagnostics"] = {"sessionId": "session", "worldLoaded": True, "lifecycle": {"status": "idle"}}
    result = await adapter.call_tool("mcb_diagnostics", {})
    assert not result.isError and result.structuredContent["data"]["nativeDiagnosticsAvailable"] is True
    assert result.structuredContent["data"]["worldLoaded"] is True


def test_offline_logs_need_exact_verified_prism_target_and_still_redact_sensitive_lines(launch_settings):
    config = configured(launch_settings)
    log = launch_settings.instance_path / ".minecraft/logs/latest.log"
    log.parent.mkdir(parents=True)
    log.write_text("[INFO] Booting client\n[ERROR] Missing mod dependency\n[ERROR] token=secret-must-not-leak\n", encoding="utf-8")
    result = read_logs(config, {"level": "ANY"})
    assert result["runtimeAvailable"] is False and result["bindingSource"] == "validated_portable_Prism_instance"
    assert result["lines"][:2] == ["[INFO] Booting client", "[ERROR] Missing mod dependency"]
    assert result["redactedLines"] == 1 and "secret-must-not-leak" not in json.dumps(result)
    (launch_settings.instance_path / "mmc-pack.json").write_text('{"formatVersion":1,"components":[]}', encoding="utf-8")
    with pytest.raises(BridgeError): read_logs(config, {})


def test_invalid_existing_runtime_is_not_bypassed_as_offline(launch_settings):
    config = configured(launch_settings)
    runtime = config.runtime_path()
    runtime.parent.mkdir(parents=True)
    runtime.write_text("malformed runtime", encoding="utf-8")
    with pytest.raises(BridgeError) as failure: read_logs(config, {})
    assert failure.value.code == "CONFIGURATION_ERROR"


def test_offline_log_refuses_conflicting_runtime_and_instance_targets(launch_settings, tmp_path):
    wrong = tmp_path / "other/.minecraft/config/codexbridge/runtime.json"
    wrong.parent.mkdir(parents=True)
    config = Configuration({**configured(launch_settings).environ, "MCB_RUNTIME_FILE": str(wrong)})
    with pytest.raises(BridgeError) as failure: read_logs(config, {})
    assert failure.value.code == "LOG_PATH_REFUSED"


def test_discovered_pinned_runtime_is_used_for_host_diagnostics(launch_settings):
    runtime = launch_settings.instance_path / ".minecraft/config/codexbridge/runtime.json"
    config = Configuration({"MCB_RUNTIME_FILE": "auto"})
    config._discovered_path = runtime
    assert launcher.diagnostic_configuration(config) == launch_settings
