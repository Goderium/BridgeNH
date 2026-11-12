"""Fixed Windows Prism launch workflow. No login, input injection or shell commands."""

from __future__ import annotations

import configparser
import ctypes
import hashlib
import os
import re
import subprocess
import threading
import time
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import anyio

from .bridge import BridgeError, Configuration, strict_json

GUI_READY_TIMEOUT = 15.0
DISPATCH_TIMEOUT = 5.0
JAVA_OBSERVE_TIMEOUT = 10.0
PROCESS_QUERY_TIMEOUT = 3.0
CLI_INVOCATION = re.compile(r'(?:^|\s)"?(?:--help|--version|--launch|-h|-v|-l)(?=["=\s]|$)', re.IGNORECASE)
PROCESS_QUERY = r"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$rows = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe' OR Name='prismlauncher.exe'")
if ($rows.Count -gt 64) { throw 'Process inspection limit exceeded' }
$result = @(foreach ($row in $rows) {
    $windowHandle = 0
    $responding = $false
    if ($row.Name -ieq 'prismlauncher.exe') {
        $guiProcess = Get-Process -Id $row.ProcessId -ErrorAction SilentlyContinue
        if ($null -ne $guiProcess) {
            $windowHandle = $guiProcess.MainWindowHandle.ToInt64()
            $responding = $guiProcess.Responding
        }
    }
    [PSCustomObject]@{ name=$row.Name; processId=$row.ProcessId; path=$row.ExecutablePath;
        commandLine=$row.CommandLine; windowHandle=$windowHandle; responding=$responding }
})
ConvertTo-Json -InputObject $result -Compress -Depth 4
"""


@dataclass(frozen=True)
class LaunchConfiguration:
    prism_exe: Path
    instance_path: Path

    @property
    def instance_id(self) -> str:
        return self.instance_path.name


@dataclass(frozen=True, repr=False)
class Process:
    process_id: int
    name: str
    path: str | None
    command_line: str | None
    window_handle: int = 0
    responding: bool = False


def _read_bounded(path: Path, maximum: int = 65_536) -> bytes:
    with path.open("rb") as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError("Metadata too large")
    return raw


def read_launch_configuration(configuration: Configuration) -> LaunchConfiguration:
    """Resolve only configured files, or the standard portable Prism runtime location."""
    env = configuration.environ
    instance_value = env.get("MCB_INSTANCE_PATH")
    if not instance_value:
        runtime_value = env.get("MCB_RUNTIME_FILE")
        if not runtime_value:
            raise BridgeError("LAUNCH_CONFIGURATION", "Set MCB_INSTANCE_PATH and MCB_PRISM_EXE, or a standard Prism MCB_RUNTIME_FILE path.")
        runtime = Path(runtime_value)
        if not runtime.is_absolute() or [part.casefold() for part in runtime.parts[-4:]] != [".minecraft", "config", "codexbridge", "runtime.json"]:
            raise BridgeError("LAUNCH_CONFIGURATION", "Runtime-based launch requires an absolute instance/.minecraft/config/codexbridge/runtime.json path.")
        instance_value = str(runtime.parents[3])
    try:
        instance = Path(instance_value)
        if not instance.is_absolute():
            raise ValueError("Relative instance")
        instance = instance.resolve(strict=True)
        prism_value = env.get("MCB_PRISM_EXE")
        if not prism_value:
            if instance.parent.name.casefold() != "instances":
                raise ValueError("Nonstandard instance path")
            prism_value = str(instance.parents[1] / "prismlauncher.exe")
        prism = Path(prism_value)
        if not prism.is_absolute() or prism.name.casefold() != "prismlauncher.exe":
            raise ValueError("Not Prism")
        prism = prism.resolve(strict=True)
        if not prism.is_file() or prism.name.casefold() != "prismlauncher.exe" or not instance.is_dir():
            raise ValueError("Missing launcher or instance")
        if not 1 <= len(instance.name) <= 256 or instance.name.startswith("-") or any(ord(char) < 32 for char in instance.name):
            raise ValueError("Unsafe instance ID")
        instance_cfg = configparser.ConfigParser(interpolation=None)
        instance_cfg.read_string(_read_bounded(instance / "instance.cfg").decode("utf-8-sig"))
        if instance_cfg.get("General", "InstanceType", fallback="") != "OneSix":
            raise ValueError("Unsupported instance format")
        pack = strict_json(_read_bounded(instance / "mmc-pack.json"))
        if not isinstance(pack, dict) or type(pack.get("formatVersion")) is not int or pack["formatVersion"] != 1:
            raise ValueError("Invalid pack metadata")
        components = pack.get("components")
        if not isinstance(components, list) or len(components) > 64 or not all(isinstance(component, dict) for component in components):
            raise ValueError("Invalid components")
        minecraft = [component for component in components if component.get("uid") == "net.minecraft"]
        if len(minecraft) != 1 or minecraft[0].get("version") != "1.7.10":
            raise ValueError("Not Minecraft 1.7.10")
        # --launch accepts an instance ID, so verify Prism's configured directory resolves to this instance.
        launcher_cfg = configparser.ConfigParser(interpolation=None)
        cfg_path = prism.parent / "prismlauncher.cfg"
        if not (prism.parent / "portable.txt").is_file():
            raise ValueError("Portable data directory not verified")
        launcher_cfg.read_string(_read_bounded(cfg_path).decode("utf-8-sig"))
        instances_value = launcher_cfg.get("General", "InstanceDir", fallback="instances")
        instances = Path(instances_value)
        if not instances.is_absolute():
            instances = prism.parent / instances
        if instances.resolve(strict=True) != instance.parent:
            raise ValueError("Instance does not belong to this Prism directory")
    except (OSError, ValueError, UnicodeError, configparser.Error, RecursionError):
        raise BridgeError("LAUNCH_CONFIGURATION", "Verify the fixed portable PrismLauncher.exe/portable.txt/prismlauncher.cfg, instance.cfg, Minecraft 1.7.10 mmc-pack.json, and Prism InstanceDir. No files were changed.") from None
    return LaunchConfiguration(prism, instance)


def _normalized_path(value: str | Path) -> str:
    return str(value).replace("\\", "/").casefold().rstrip("/")


def _target_java(processes: list[Process], configuration: LaunchConfiguration) -> Process | None:
    pattern = re.compile(re.escape(_normalized_path(configuration.instance_path)) + r"(?=$|[/;\"'\s])")
    for process in processes:
        if process.name.casefold() not in {"java.exe", "javaw.exe"}:
            continue
        if not process.command_line:
            raise BridgeError("PROCESS_INSPECTION_INCOMPLETE", "A Java command line could not be read; duplicate-client exclusion cannot be confirmed.")
        if pattern.search(_normalized_path(process.command_line)):
            return process
    return None


def _prism_processes(processes: list[Process], configuration: LaunchConfiguration) -> list[Process]:
    matching = []
    for process in processes:
        if process.name.casefold() != "prismlauncher.exe" or not process.path or _normalized_path(process.path) != _normalized_path(configuration.prism_exe):
            continue
        if not process.command_line:
            raise BridgeError("PROCESS_INSPECTION_INCOMPLETE", "A matching Prism process's data-directory arguments could not be read; profile reuse cannot be verified.")
        directory = re.search(r'(?:^|\s)(?:--dir|-d)(?:=|\s+)(?:"([^"]+)"|(\S+))', process.command_line, re.IGNORECASE)
        if directory and _normalized_path(directory.group(1) or directory.group(2)) != _normalized_path(configuration.prism_exe.parent):
            continue
        matching.append(process)
    return matching


def _ready_prism(processes: list[Process], configuration: LaunchConfiguration, backend) -> Process | None:
    for process in _prism_processes(processes, configuration):
        if process.command_line and not CLI_INVOCATION.search(process.command_line) and process.responding and (process.window_handle or backend.gui_idle(process.process_id)):
            return process
    return None


class WindowsBackend:
    def __init__(self):
        if os.name != "nt":
            raise BridgeError("LAUNCH_UNSUPPORTED_PLATFORM", "The fixed Prism launcher workflow currently supports Windows only.")
        self.kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self.user32 = ctypes.WinDLL("user32", use_last_error=True)
        self.kernel32.GetWindowsDirectoryW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint]
        self.kernel32.GetWindowsDirectoryW.restype = ctypes.c_uint
        buffer = ctypes.create_unicode_buffer(32_768)
        length = self.kernel32.GetWindowsDirectoryW(buffer, len(buffer))
        if not 0 < length < len(buffer):
            raise BridgeError("PROCESS_QUERY_FAILED", "Cannot locate the Windows process-query executable.")
        self.powershell = Path(buffer.value) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"

    def gui_idle(self, process_id: int) -> bool:
        """A hidden normal GUI may have no visible MainWindowHandle; never show or activate it."""
        kernel = self.kernel32
        kernel.OpenProcess.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.c_uint]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        self.user32.WaitForInputIdle.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        self.user32.WaitForInputIdle.restype = ctypes.c_uint
        handle = kernel.OpenProcess(0x100000 | 0x0400, False, process_id)
        if not handle:
            return False
        try:
            return self.user32.WaitForInputIdle(handle, 0) == 0
        finally:
            kernel.CloseHandle(handle)

    @contextmanager
    def mutex(self, configuration: LaunchConfiguration):
        name = "Local\\MinecraftCodexBridgeLaunch-" + hashlib.sha256(_normalized_path(configuration.prism_exe).encode("utf-8")).hexdigest()
        kernel = self.kernel32
        kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
        kernel.CreateMutexW.restype = ctypes.c_void_p
        kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        kernel.WaitForSingleObject.restype = ctypes.c_uint
        kernel.ReleaseMutex.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel.CreateMutexW(None, False, name)
        if not handle:
            raise BridgeError("LAUNCH_LOCK_FAILED", "Cannot acquire the Prism launch mutex.")
        acquired = False
        try:
            status = kernel.WaitForSingleObject(handle, 0)
            if status == 258:
                raise BridgeError("LAUNCH_IN_PROGRESS", "Another bridge caller is launching through this Prism instance; inspect state before another request.")
            if status not in {0, 128}:
                raise BridgeError("LAUNCH_LOCK_FAILED", "The Prism launch mutex returned an unexpected status.")
            acquired = True
            yield
        finally:
            if acquired:
                kernel.ReleaseMutex(handle)
            kernel.CloseHandle(handle)

    def processes(self) -> list[Process]:
        try:
            result = subprocess.run(
                [str(self.powershell), "-NoProfile", "-NonInteractive", "-Command", PROCESS_QUERY],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                shell=False, timeout=PROCESS_QUERY_TIMEOUT, creationflags=subprocess.CREATE_NO_WINDOW,
            )
            if result.returncode != 0 or len(result.stdout) > 1_048_576:
                raise ValueError("Process query failed")
            values = strict_json(result.stdout)
            if not isinstance(values, list) or len(values) > 64:
                raise ValueError("Invalid process query")
            processes = []
            for value in values:
                if not isinstance(value, dict) or type(value.get("processId")) is not int or value["processId"] <= 0:
                    raise ValueError("Invalid process record")
                if not isinstance(value.get("name"), str) or type(value.get("windowHandle")) is not int or type(value.get("responding")) is not bool:
                    raise ValueError("Invalid process record")
                if any(value.get(key) is not None and not isinstance(value[key], str) for key in ("path", "commandLine")):
                    raise ValueError("Invalid process strings")
                processes.append(Process(value["processId"], value["name"], value.get("path"), value.get("commandLine"), value["windowHandle"], value["responding"]))
            return processes
        except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired, RecursionError):
            raise BridgeError("PROCESS_QUERY_FAILED", "The fixed Windows process query failed or timed out; no duplicate-safe launch decision is available.") from None

    def spawn(self, configuration: LaunchConfiguration, launch: bool = False):
        arguments = [str(configuration.prism_exe)]
        if launch:
            arguments.extend(["--launch", configuration.instance_id])
        try:
            startup = subprocess.STARTUPINFO()
            startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startup.wShowWindow = subprocess.SW_HIDE
            return subprocess.Popen(arguments, cwd=configuration.prism_exe.parent, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, shell=False, creationflags=subprocess.CREATE_NO_WINDOW, startupinfo=startup)
        except OSError:
            raise BridgeError("PRISM_START_FAILED", "Windows could not start the fixed Prism executable.", outcome_unknown=launch) from None

    monotonic = staticmethod(time.monotonic)
    sleep = staticmethod(time.sleep)


def _check_cancelled(cancelled: threading.Event) -> None:
    if cancelled.is_set():
        raise BridgeError("LAUNCH_CANCELLED", "Launch collection was cancelled; no further launch command will be issued.")


def _write_window_mode(configuration: LaunchConfiguration, mode: str) -> None:
    if mode not in {"background", "minimized", "normal"}:
        raise BridgeError("INVALID_ARGUMENTS", "windowMode must be background, minimized or normal.")
    directory = configuration.instance_path / ".minecraft/config/codexbridge"
    temporary = None
    try:
        directory.resolve().relative_to(configuration.instance_path.resolve())
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / "window-startup.txt"
        if target.is_symlink():
            raise ValueError("Symlink policy file")
        with tempfile.NamedTemporaryFile(dir=directory, prefix=".window-startup-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write((mode + "\n").encode("ascii"))
        os.replace(temporary, target)
    except (OSError, ValueError):
        raise BridgeError("LAUNCH_WINDOW_CONFIGURATION", "Cannot safely write the selected instance's window startup mode; no launch was dispatched.") from None
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def _launch(configuration: LaunchConfiguration, cancelled: threading.Event, backend: WindowsBackend, window_mode: str = "minimized") -> dict[str, Any]:
    if window_mode not in {"background", "minimized", "normal"}:
        raise BridgeError("INVALID_ARGUMENTS", "windowMode must be background, minimized or normal.")
    with backend.mutex(configuration):
        _check_cancelled(cancelled)
        processes = backend.processes()
        existing = _target_java(processes, configuration)
        if existing:
            raise BridgeError("CLIENT_ALREADY_RUNNING", "A Java process for the fixed target instance is already running. Use mcb_health/snapshot instead.", process_id=existing.process_id)
        prisms = _prism_processes(processes, configuration)
        ready = _ready_prism(processes, configuration, backend)
        normal = [process for process in prisms if process.command_line and not CLI_INVOCATION.search(process.command_line)]
        started = None
        if ready is None and not normal:
            _check_cancelled(cancelled)
            started = backend.spawn(configuration)
        deadline = backend.monotonic() + GUI_READY_TIMEOUT
        while ready is None:
            _check_cancelled(cancelled)
            if backend.monotonic() >= deadline:
                raise BridgeError("PRISM_NOT_READY", "No responsive normal Prism GUI appeared before the bounded startup timeout; no --launch command was issued.")
            processes = backend.processes()
            if _target_java(processes, configuration):
                raise BridgeError("CLIENT_ALREADY_RUNNING", "The target Java process appeared during Prism preparation; no duplicate launch was issued.")
            ready = _ready_prism(processes, configuration, backend)
            if ready is None:
                if started is not None and started.poll() is not None:
                    raise BridgeError("PRISM_START_FAILED", "The normal Prism process exited before its GUI became ready.", exit_code=started.returncode)
                backend.sleep(1.0)
        # Recheck immediately before the single dispatch, while retaining the cross-process mutex.
        _check_cancelled(cancelled)
        if _target_java(backend.processes(), configuration):
            raise BridgeError("CLIENT_ALREADY_RUNNING", "The target Java process is already running; no duplicate launch was issued.")
        _check_cancelled(cancelled)
        _write_window_mode(configuration, window_mode)
        _check_cancelled(cancelled)
        command = backend.spawn(configuration, launch=True)
        try:
            exit_code = command.wait(timeout=DISPATCH_TIMEOUT)
        except subprocess.TimeoutExpired:
            raise BridgeError("LAUNCH_DISPATCH_TIMEOUT", "Prism did not finish the launch dispatch before its timeout. Inspect client state before another request.", outcome_unknown=True) from None
        if exit_code != 0:
            raise BridgeError("PRISM_LAUNCH_FAILED", "Prism returned a failed launch dispatch; inspect Prism and client state before another request.", exit_code=exit_code, outcome_unknown=True)
        deadline = backend.monotonic() + JAVA_OBSERVE_TIMEOUT
        try:
            while True:
                _check_cancelled(cancelled)
                java = _target_java(backend.processes(), configuration)
                if java:
                    return {"status": "scheduled", "scheduled": True, "ready": False, "windowModeRequested": window_mode, "windowModeConfirmed": False, "instanceId": configuration.instance_id, "prismProcessId": ready.process_id, "launchProcessId": command.pid, "javaProcessId": java.process_id, "startup": {"version": 1, "stage": "java_started", "bridgeReady": False, "worldReady": False, "nextTool": "mcb_health", "retryAction": False}, "completion": "Use mcb_health once to observe the current session and readiness; Java startup does not mean Minecraft or a world is ready."}
                if backend.monotonic() >= deadline:
                    raise BridgeError("LAUNCH_NOT_OBSERVED", "Prism accepted dispatch, but no target Java process appeared before the bounded observation timeout. Inspect Prism before another request.", outcome_unknown=True)
                backend.sleep(1.0)
        except BridgeError as error:
            error.details["outcome_unknown"] = True
            raise


async def launch_client(configuration: Configuration | None = None, window_mode: str = "minimized") -> dict[str, Any]:
    """MCP and CLI share this workflow; request cancellation stops later dispatch stages."""
    settings = read_launch_configuration(configuration or Configuration())
    cancelled = threading.Event()

    def worker():
        return _launch(settings, cancelled, WindowsBackend(), window_mode)

    try:
        return await anyio.to_thread.run_sync(worker, abandon_on_cancel=True)
    except BridgeError as error:
        if error.details.get("outcome_unknown"):
            error.details["continuation"] = {"tool": "mcb_launch", "arguments": {"action": "status", "timeout_ms": 30000},
                "readOnly": True, "retryAction": False}
        raise
    except anyio.get_cancelled_exc_class():
        cancelled.set()
        raise


async def launch_status(client, timeout_ms: int = 30000) -> dict[str, Any]:
    """Bounded read-only continuation, also usable by a fresh MCP after a lost launch reply."""
    settings = diagnostic_configuration(client.configuration)
    started = time.monotonic()
    deadline = started + timeout_ms / 1000
    report = {"status": "waiting", "readOnly": True, "instancePath": str(settings.instance_path),
        "instanceId": settings.instance_id, "attempts": 0, "launchDispatched": False,
        "automaticActionRetries": False, "worldState": "not_observed", "windowExpired": False,
        "startup": {"version": 1, "stage": "unknown", "bridgeReady": False, "worldReady": False,
            "nextTool": "mcb_launch", "nextArguments": {"action": "status", "timeout_ms": timeout_ms}, "retryAction": False}}
    unavailable = {"CONFIGURATION_ERROR", "RUNTIME_DISCOVERY_FAILED", "BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE",
        "AUTH_FAILED", "INSTANCE_MISMATCH", "timeout", "cancelled"}
    try:
        with anyio.fail_after(timeout_ms / 1000):
            while True:
                report["attempts"] += 1
                reason = "BRIDGE_TIMEOUT"
                try:
                    with anyio.fail_after(min(2.5, max(.001, deadline - time.monotonic()))):
                        health = await client.invoke("bridge.health", {})
                    if _normalized_path(health.get("instancePath", "")) != _normalized_path(settings.instance_path):
                        raise BridgeError("INSTANCE_MISMATCH", "Startup observation does not match the fixed Prism instance.")
                    readiness = health.get("readiness", {})
                    if readiness.get("bridgeReady") is True:
                        report.update(status="ready", source="native_current_health", sessionId=health.get("sessionId"),
                            readiness=readiness, worldState="observed", windowExpired=False)
                        report["startup"] = {"version": 1, "stage": readiness.get("stage", "bridge_ready"),
                            "bridgeReady": True, "worldReady": readiness.get("worldReady") is True,
                            "nextTool": readiness.get("nextTool", "mcb_health"), "retryAction": False}
                        break
                    reason = "NATIVE_NOT_READY"
                except TimeoutError:
                    pass
                except BridgeError as error:
                    if error.code not in unavailable:
                        raise
                    reason = error.code
                # Reuse exact-instance process matching and its bounded Windows query.
                # An absent Java sample after dispatch is not proof of stopped startup.
                host = await anyio.to_thread.run_sync(
                    lambda: _host_diagnostics(client.configuration, reason), abandon_on_cancel=True)
                host["lifecycleRecovery"].update(nextTool="mcb_launch", nextArguments={"action": "status", "timeout_ms": timeout_ms},
                    launchPermittedByProcessState=False)
                report["hostObservation"] = host
                stage = "java_started" if host.get("javaRunning") else "java_not_observed" if host.get("processInspectionComplete") else "unknown"
                report["startup"]["stage"] = stage
                report["status"] = "unknown" if stage == "unknown" else "waiting"
                await anyio.sleep(min(1.0, max(0, deadline - time.monotonic())))
    except TimeoutError:
        report["windowExpired"] = True
    report["elapsed_ms"] = int((time.monotonic() - started) * 1000)
    report["scope"] = "Only current health and fixed-instance host processes; no spawn, stop, login, input, world load or replay. Java absence and query failure never authorize another launch. Continue status or inspect bounded logs."
    return report


def diagnostic_configuration(configuration: Configuration) -> LaunchConfiguration:
    """Keep the already pinned instance even when its runtime file has disappeared."""
    env = dict(configuration.environ)
    explicit_runtime = env.get("MCB_RUNTIME_FILE", "").strip().casefold() not in {"", "auto", "discover"}
    if not env.get("MCB_INSTANCE_PATH") and not explicit_runtime:
        runtime = configuration.runtime_path()
        if runtime is not None:
            env["MCB_RUNTIME_FILE"] = str(runtime)
    return read_launch_configuration(Configuration(env))


def _host_diagnostics(configuration: Configuration, native_error: str, backend=None) -> dict[str, Any]:
    result = {"source": "configured_instance_host_process_observation", "readOnly": True,
        "nativeDiagnosticsAvailable": False, "nativeFailure": native_error, "worldState": "not_observed",
        "automaticActionRetries": False}
    try:
        settings = diagnostic_configuration(configuration)
        backend = backend or WindowsBackend()
        processes = backend.processes()
        java = _target_java(processes, settings)
        prism = _ready_prism(processes, settings, backend)
        result.update(instancePath=str(settings.instance_path), processInspectionComplete=True,
            javaRunning=java is not None, javaProcessId=java.process_id if java else None,
            prismReady=prism is not None, prismProcessId=prism.process_id if prism else None)
        result["lifecycleRecovery"] = {"version": 1,
            "stage": "java_running_bridge_unavailable" if java else "client_stopped",
            "nextTool": "mcb_logs" if java else "mcb_launch", "retryAction": False,
            "launchPermittedByProcessState": java is None,
            "scope": "Exact configured instance only; process presence does not distinguish loading, a blocked client or a failed mod. Inspect bounded logs/current health before a new action. No process is launched, killed, activated or modified."}
    except BridgeError as error:
        result.update(processInspectionComplete=False, inspectionFailure=error.code)
        result["lifecycleRecovery"] = {"version": 1, "stage": "unknown", "retryAction": False,
            "launchPermittedByProcessState": False, "scope": "Target or process inspection could not be verified; do not infer that the client is stopped."}
    return result


async def client_diagnostics(client) -> dict[str, Any]:
    """Existing diagnostics entry remains useful while its fixed client is offline."""
    try:
        with anyio.fail_after(2.5):
            value = await client.invoke("client.diagnostics", {})
        return {**value, "source": "native_client_diagnostics", "nativeDiagnosticsAvailable": True}
    except TimeoutError:
        reason = "BRIDGE_TIMEOUT"
    except BridgeError as error:
        if error.code not in {"CONFIGURATION_ERROR", "RUNTIME_DISCOVERY_FAILED", "BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE",
                              "AUTH_FAILED", "INSTANCE_MISMATCH", "timeout", "cancelled"}:
            raise
        reason = error.code
    with anyio.fail_after(5):
        return await anyio.to_thread.run_sync(lambda: _host_diagnostics(client.configuration, reason), abandon_on_cancel=True)
