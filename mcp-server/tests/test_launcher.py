"""Launcher metadata and workflow mocks; never open Prism or launch Java in tests."""

import asyncio
import json
import os
import subprocess
import threading
from contextlib import contextmanager
from pathlib import Path

import pytest

from minecraft_codex_bridge import launcher
from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.launcher import LaunchConfiguration, Process, _launch, _prism_processes, _target_java, read_launch_configuration


@pytest.fixture
def launch_settings(tmp_path):
    root = tmp_path / "PrismLauncher"
    instance = root / "instances" / "test-instance"
    instance.mkdir(parents=True)
    prism = root / "prismlauncher.exe"
    prism.write_bytes(b"fixture-only-never-executed")
    (root / "portable.txt").write_text("Portable mode fixture", encoding="utf-8")
    (root / "prismlauncher.cfg").write_text("[General]\nInstanceDir=instances\n", encoding="utf-8")
    (instance / "instance.cfg").write_text("[General]\nInstanceType=OneSix\nname=fixture\n", encoding="utf-8")
    (instance / "mmc-pack.json").write_text(json.dumps({"formatVersion": 1, "components": [{"uid": "net.minecraft", "version": "1.7.10"}]}), encoding="utf-8")
    return LaunchConfiguration(prism, instance)


def configured(settings):
    return Configuration({"MCB_PRISM_EXE": str(settings.prism_exe), "MCB_INSTANCE_PATH": str(settings.instance_path)})


def test_launch_configuration_explicit_and_missing_runtime_file(launch_settings):
    assert read_launch_configuration(configured(launch_settings)) == launch_settings
    runtime = launch_settings.instance_path / ".minecraft" / "config" / "codexbridge" / "runtime.json"
    assert not runtime.exists()
    assert read_launch_configuration(Configuration({"MCB_RUNTIME_FILE": str(runtime)})) == launch_settings


@pytest.mark.parametrize("change", ["wrong_version", "duplicate_minecraft", "wrong_instance_type", "wrong_exe", "relative", "other_instances", "oversize", "malformed", "nonportable"])
def test_launch_configuration_rejects_unverified_target(launch_settings, change):
    env = dict(configured(launch_settings).environ)
    pack = launch_settings.instance_path / "mmc-pack.json"
    if change == "wrong_version":
        pack.write_text(json.dumps({"formatVersion": 1, "components": [{"uid": "net.minecraft", "version": "1.12.2"}]}), encoding="utf-8")
    elif change == "duplicate_minecraft":
        pack.write_text(json.dumps({"formatVersion": 1, "components": [{"uid": "net.minecraft", "version": "1.7.10"}] * 2}), encoding="utf-8")
    elif change == "wrong_instance_type":
        (launch_settings.instance_path / "instance.cfg").write_text("[General]\nInstanceType=Legacy\n", encoding="utf-8")
    elif change == "wrong_exe":
        env["MCB_PRISM_EXE"] = str(launch_settings.prism_exe.with_name("arbitrary.exe"))
    elif change == "relative":
        env["MCB_INSTANCE_PATH"] = "relative-instance"
    elif change == "other_instances":
        other = launch_settings.prism_exe.parent / "other-instances"
        other.mkdir()
        (launch_settings.prism_exe.parent / "prismlauncher.cfg").write_text("[General]\nInstanceDir=other-instances\n", encoding="utf-8")
    elif change == "oversize":
        pack.write_bytes(b" " * 65_537)
    elif change == "malformed":
        pack.write_bytes(b'{"formatVersion":1,"formatVersion":1}')
    elif change == "nonportable":
        (launch_settings.prism_exe.parent / "portable.txt").unlink()
    with pytest.raises(BridgeError) as error:
        read_launch_configuration(Configuration(env))
    assert error.value.code == "LAUNCH_CONFIGURATION"


@pytest.mark.parametrize("environment", [{}, {"MCB_RUNTIME_FILE": "runtime.json"}, {"MCB_RUNTIME_FILE": "C:/wrong/config/runtime.json"}])
def test_launch_missing_or_nonstandard_configuration(environment):
    with pytest.raises(BridgeError) as error:
        read_launch_configuration(Configuration(environment))
    assert error.value.code == "LAUNCH_CONFIGURATION"


def prism(settings, *, ready=True):
    return Process(10, "prismlauncher.exe", str(settings.prism_exe), str(settings.prism_exe), 123 if ready else 0, ready)


def java(settings):
    return Process(20, "javaw.exe", "javaw.exe", f'javaw.exe -Djava.library.path="{settings.instance_path}/natives" secret-not-returned')


class Command:
    def __init__(self, *, code=0, timeout=False, running=False):
        self.pid = 30
        self.returncode = None if running else code
        self.timeout = timeout

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        if self.timeout:
            raise subprocess.TimeoutExpired("fixture launch", timeout)
        return self.returncode


class Workflow:
    def __init__(self, states, *, command=None):
        self.states = iter(states)
        self.last = []
        self.commands = []
        self.command = command or Command()
        self.clock = 0
        self.lock = threading.Lock()
        self.lock_released = False

    @contextmanager
    def mutex(self, _):
        if not self.lock.acquire(blocking=False):
            raise BridgeError("LAUNCH_IN_PROGRESS", "Busy")
        try:
            yield
        finally:
            self.lock.release()
            self.lock_released = True

    def processes(self):
        self.last = next(self.states, self.last)
        if isinstance(self.last, Exception):
            raise self.last
        return self.last

    def spawn(self, configuration, launch=False):
        self.commands.append((configuration, launch))
        return self.command if launch else Command(running=True)

    def monotonic(self):
        return self.clock

    def gui_idle(self, _):
        return False

    def sleep(self, duration):
        self.clock += duration


def test_launch_starts_normal_gui_then_dispatches_once(launch_settings):
    gui = prism(launch_settings)
    backend = Workflow([[], [], [gui], [gui], [gui, java(launch_settings)]])
    result = _launch(launch_settings, threading.Event(), backend)
    assert [launch for _, launch in backend.commands] == [False, True]
    assert result["status"] == "scheduled" and result["scheduled"] is True
    assert result["ready"] is False and result["javaProcessId"] == 20
    assert result["startup"] == {"version": 1, "stage": "java_started", "bridgeReady": False,
        "worldReady": False, "nextTool": "mcb_health", "retryAction": False}
    assert "secret-not-returned" not in json.dumps(result)
    assert backend.lock_released is True
    assert result["windowModeRequested"] == "minimized" and result["windowModeConfirmed"] is False
    assert (launch_settings.instance_path / ".minecraft/config/codexbridge/window-startup.txt").read_text() == "minimized\n"


@pytest.mark.parametrize("mode", ["background", "minimized", "normal"])
def test_launch_mode_is_written_only_for_selected_instance_before_dispatch(launch_settings, mode):
    gui = prism(launch_settings)
    class CheckedWorkflow(Workflow):
        def spawn(self, configuration, launch=False):
            if launch:
                assert (configuration.instance_path / ".minecraft/config/codexbridge/window-startup.txt").read_text() == mode + "\n"
            return super().spawn(configuration, launch)
    backend = CheckedWorkflow([[gui], [gui], [gui, java(launch_settings)]])
    result = _launch(launch_settings, threading.Event(), backend, mode)
    assert result["windowModeRequested"] == mode
    assert not list(launch_settings.instance_path.rglob(".window-startup-*"))


def test_running_or_invalid_launch_does_not_change_window_preference(launch_settings):
    target = launch_settings.instance_path / ".minecraft/config/codexbridge/window-startup.txt"
    target.parent.mkdir(parents=True)
    target.write_text("normal\n")
    for mode, code in [("minimized", "CLIENT_ALREADY_RUNNING"), ("invalid", "INVALID_ARGUMENTS")]:
        backend = Workflow([[java(launch_settings)]])
        with pytest.raises(BridgeError) as error:
            _launch(launch_settings, threading.Event(), backend, mode)
        assert error.value.code == code
        assert not backend.commands and target.read_text() == "normal\n"


def test_launch_window_mode_rejects_status_and_unknown_modes():
    from minecraft_codex_bridge.server import _validate
    for mode in ("background", "minimized", "normal"):
        _validate("mcb_launch", {"windowMode": mode})
        with pytest.raises(BridgeError):
            _validate("mcb_launch", {"action": "status", "windowMode": mode})
    with pytest.raises(BridgeError):
        _validate("mcb_launch", {"windowMode": "hidden"})


def test_window_mode_write_failure_preserves_previous_preference_and_never_dispatches(launch_settings, monkeypatch):
    target = launch_settings.instance_path / ".minecraft/config/codexbridge/window-startup.txt"
    target.parent.mkdir(parents=True)
    target.write_text("normal\n")
    def refuse_replace(*args):
        raise PermissionError("fixture write denied")
    monkeypatch.setattr("minecraft_codex_bridge.launcher.os.replace", refuse_replace)
    gui = prism(launch_settings)
    backend = Workflow([[gui], [gui]])
    with pytest.raises(BridgeError) as error:
        _launch(launch_settings, threading.Event(), backend, "minimized")
    assert error.value.code == "LAUNCH_WINDOW_CONFIGURATION"
    assert not backend.commands and target.read_text() == "normal\n"
    assert not list(target.parent.glob(".window-startup-*"))


def test_launch_reuses_existing_responsive_gui(launch_settings):
    gui = prism(launch_settings)
    backend = Workflow([[gui], [gui], [gui, java(launch_settings)]])
    _launch(launch_settings, threading.Event(), backend)
    assert [launch for _, launch in backend.commands] == [True]


def test_launch_waits_existing_gui_instead_of_opening_second(launch_settings):
    gui = prism(launch_settings)
    backend = Workflow([[prism(launch_settings, ready=False)], [gui], [gui], [gui, java(launch_settings)]])
    _launch(launch_settings, threading.Event(), backend)
    assert [launch for _, launch in backend.commands] == [True]


def test_hidden_normal_gui_uses_input_idle_without_showing_window(launch_settings):
    gui = Process(10, "prismlauncher.exe", str(launch_settings.prism_exe), str(launch_settings.prism_exe), 0, True)
    backend = Workflow([[gui], [gui], [gui, java(launch_settings)]])
    backend.gui_idle = lambda _: True
    result = _launch(launch_settings, threading.Event(), backend)
    assert result["status"] == "scheduled"
    assert [launch for _, launch in backend.commands] == [True]


@pytest.mark.parametrize("argument", ["--help", "-h", "--version", "-v", "--launch test", "-l test", '"--help"'])
def test_cli_processes_are_never_reused_as_normal_gui(launch_settings, argument):
    helper = Process(11, "prismlauncher.exe", str(launch_settings.prism_exe), f'"{launch_settings.prism_exe}" {argument}', 999, True)
    gui = prism(launch_settings)
    backend = Workflow([[helper], [helper, gui], [helper, gui], [helper, gui, java(launch_settings)]])
    result = _launch(launch_settings, threading.Event(), backend)
    assert result["prismProcessId"] == gui.process_id
    assert [launch for _, launch in backend.commands] == [False, True]


@pytest.mark.parametrize("stage", ["initial", "pre_dispatch"])
def test_launch_refuses_duplicate_target_java(launch_settings, stage):
    gui = prism(launch_settings)
    states = [[java(launch_settings)]] if stage == "initial" else [[gui], [gui, java(launch_settings)]]
    backend = Workflow(states)
    with pytest.raises(BridgeError) as error:
        _launch(launch_settings, threading.Event(), backend)
    assert error.value.code == "CLIENT_ALREADY_RUNNING"
    assert backend.commands == []


def test_target_process_matching_uses_path_boundary(launch_settings):
    assert _target_java([java(launch_settings)], launch_settings).process_id == 20
    neighbor = Process(21, "java.exe", "java.exe", str(launch_settings.instance_path) + "-other/natives")
    assert _target_java([neighbor], launch_settings) is None
    unrelated = Process(22, "prismLauncher.exe", "prismLauncher.exe", str(launch_settings.instance_path))
    assert _target_java([unrelated], launch_settings) is None


@pytest.mark.parametrize("process_name", ["java.exe", "javaw.exe"])
@pytest.mark.parametrize("windows_separators", [False, True])
def test_prism_entrypoint_with_only_fixed_instance_natives_is_still_the_target(process_name, windows_separators):
    launch_settings = LaunchConfiguration(Path("D:/PrismLauncher/prismlauncher.exe"), Path("D:/PrismLauncher/instances/ExampleClient"))
    instance = str(launch_settings.instance_path).replace("\\", "/")
    native_path = instance + "/natives"
    if windows_separators:
        native_path = native_path.replace("/", "\\")
    command_line = f'"{process_name}" -Djava.library.path="{native_path}" org.prismlauncher.EntryPoint'
    assert ".minecraft" not in command_line
    target = Process(20, process_name, process_name, command_line)
    assert _target_java([target], launch_settings) is target
    backend = Workflow([[target]])
    with pytest.raises(BridgeError) as error:
        _launch(launch_settings, threading.Event(), backend)
    assert error.value.code == "CLIENT_ALREADY_RUNNING" and backend.commands == []


@pytest.mark.parametrize("neighbor_suffix", ["-other", "-copy", "_other"])
def test_prism_entrypoint_of_neighbor_instance_is_not_the_fixed_target(neighbor_suffix):
    launch_settings = LaunchConfiguration(Path("D:/PrismLauncher/prismlauncher.exe"), Path("D:/PrismLauncher/instances/ExampleClient"))
    command_line = f'javaw.exe -Djava.library.path="{launch_settings.instance_path}{neighbor_suffix}/natives" org.prismlauncher.EntryPoint'
    neighbor = Process(21, "javaw.exe", "javaw.exe", command_line)
    assert _target_java([neighbor], launch_settings) is None


def test_prism_gui_reuse_requires_same_data_directory(launch_settings):
    correct = Process(10, "prismlauncher.exe", str(launch_settings.prism_exe), f'prism --dir "{launch_settings.prism_exe.parent}"', 1, True)
    wrong = Process(11, "prismlauncher.exe", str(launch_settings.prism_exe), 'prism --dir "C:/other-profile"', 2, True)
    assert _prism_processes([correct, wrong], launch_settings) == [correct]
    unknown = Process(12, "prismlauncher.exe", str(launch_settings.prism_exe), None, 3, True)
    with pytest.raises(BridgeError) as error:
        _prism_processes([unknown], launch_settings)
    assert error.value.code == "PROCESS_INSPECTION_INCOMPLETE"


def test_unreadable_java_refuses_duplicate_uncertainty(launch_settings):
    backend = Workflow([[Process(20, "javaw.exe", "javaw.exe", None)]])
    with pytest.raises(BridgeError) as error:
        _launch(launch_settings, threading.Event(), backend)
    assert error.value.code == "PROCESS_INSPECTION_INCOMPLETE"
    assert backend.commands == []


@pytest.mark.parametrize("mode,code", [("gui", "PRISM_NOT_READY"), ("dispatch", "LAUNCH_DISPATCH_TIMEOUT"), ("exit", "PRISM_LAUNCH_FAILED"), ("java", "LAUNCH_NOT_OBSERVED"), ("query", "PROCESS_QUERY_FAILED")])
def test_launch_reports_actual_bounded_failures(launch_settings, mode, code):
    gui = prism(launch_settings)
    if mode == "gui":
        backend = Workflow([[prism(launch_settings, ready=False)]])
    elif mode == "query":
        backend = Workflow([BridgeError("PROCESS_QUERY_FAILED", "Failed")])
    else:
        command = Command(timeout=mode == "dispatch", code=1 if mode == "exit" else 0)
        backend = Workflow([[gui]], command=command)
    with pytest.raises(BridgeError) as error:
        _launch(launch_settings, threading.Event(), backend)
    assert error.value.code == code
    if mode in {"dispatch", "exit", "java"}:
        assert error.value.details["outcome_unknown"] is True
    assert len(backend.commands) <= 1
    assert backend.lock_released


def test_launch_mutex_refuses_concurrent_dispatch(launch_settings):
    backend = Workflow([])
    backend.lock.acquire()
    try:
        with pytest.raises(BridgeError) as error:
            _launch(launch_settings, threading.Event(), backend)
        assert error.value.code == "LAUNCH_IN_PROGRESS"
        assert not backend.commands
    finally:
        backend.lock.release()


def test_cancelled_launch_never_spawns(launch_settings):
    cancelled = threading.Event()
    cancelled.set()
    backend = Workflow([])
    with pytest.raises(BridgeError) as error:
        _launch(launch_settings, cancelled, backend)
    assert error.value.code == "LAUNCH_CANCELLED" and not backend.commands


async def test_async_launch_cancellation_stops_before_dispatch(launch_settings, monkeypatch):
    entered = threading.Event()
    finished = threading.Event()

    def worker(configuration, cancelled, backend, window_mode):
        assert window_mode == "minimized"
        entered.set()
        cancelled.wait(timeout=1)
        assert cancelled.is_set()
        finished.set()

    monkeypatch.setattr(launcher, "WindowsBackend", lambda: object())
    monkeypatch.setattr(launcher, "_launch", worker)
    task = asyncio.create_task(launcher.launch_client(configured(launch_settings)))
    while not entered.is_set():
        await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    while not finished.is_set():
        await asyncio.sleep(0.01)
    assert finished.is_set()


@pytest.mark.skipif(os.name != "nt", reason="Windows native process parameters")
def test_windows_spawn_has_fixed_args_hidden_helpers_and_no_shell(launch_settings, monkeypatch):
    calls = []

    def popen(arguments, **options):
        calls.append((arguments, options))
        return Command()

    monkeypatch.setattr(subprocess, "Popen", popen)
    backend = launcher.WindowsBackend()
    backend.spawn(launch_settings)
    backend.spawn(launch_settings, launch=True)
    assert calls[0][0] == [str(launch_settings.prism_exe)]
    assert calls[1][0] == [str(launch_settings.prism_exe), "--launch", launch_settings.instance_id]
    for _, options in calls:
        assert options["shell"] is False
        assert options["cwd"] == launch_settings.prism_exe.parent
        assert options["creationflags"] & subprocess.CREATE_NO_WINDOW
        assert options["startupinfo"].wShowWindow == subprocess.SW_HIDE
        assert options["startupinfo"].dwFlags & subprocess.STARTF_USESHOWWINDOW


@pytest.mark.skipif(os.name != "nt", reason="Windows named mutex")
def test_windows_native_mutex_blocks_another_thread(launch_settings):
    backend = launcher.WindowsBackend()
    outcomes = []

    def competing():
        try:
            with backend.mutex(launch_settings):
                outcomes.append("unexpected_acquire")
        except BridgeError as error:
            outcomes.append(error.code)

    with backend.mutex(launch_settings):
        thread = threading.Thread(target=competing)
        thread.start()
        thread.join(timeout=2)
        assert not thread.is_alive()
        assert outcomes == ["LAUNCH_IN_PROGRESS"]
    with backend.mutex(launch_settings):
        pass


@pytest.mark.skipif(os.name != "nt", reason="Windows process query")
def test_windows_query_is_fixed_readonly_and_diagnostics_hide_raw_command_lines(monkeypatch):
    calls = []

    def run(arguments, **options):
        calls.append((arguments, options))
        return subprocess.CompletedProcess(arguments, 0, json.dumps([{"name": "javaw.exe", "processId": 20, "path": None, "commandLine": "javaw accessToken=secret-not-returned", "windowHandle": 0, "responding": False}]).encode("utf-8"))

    monkeypatch.setattr(subprocess, "run", run)
    records = launcher.WindowsBackend().processes()
    assert "secret-not-returned" not in repr(records)
    assert calls[0][0][-1] == launcher.PROCESS_QUERY
    assert calls[0][1]["shell"] is False
    assert calls[0][1]["timeout"] == launcher.PROCESS_QUERY_TIMEOUT
    assert calls[0][1]["stderr"] == subprocess.DEVNULL


async def test_uncertain_launch_returns_readonly_continuation(launch_settings, monkeypatch):
    def failed(*args):
        raise BridgeError("LAUNCH_NOT_OBSERVED", "Late Java", outcome_unknown=True)
    monkeypatch.setattr(launcher, "WindowsBackend", lambda: object())
    monkeypatch.setattr(launcher, "_launch", failed)
    with pytest.raises(BridgeError) as error:
        await launcher.launch_client(configured(launch_settings))
    assert error.value.details["continuation"] == {"tool": "mcb_launch", "arguments": {"action": "status", "timeout_ms": 30000}, "readOnly": True, "retryAction": False}


async def test_status_observes_late_java_then_fresh_native_health_without_dispatch(launch_settings, monkeypatch):
    backend = Workflow([[prism(launch_settings)], [prism(launch_settings), java(launch_settings)]])
    monkeypatch.setattr(launcher, "WindowsBackend", lambda: backend)
    class Client:
        configuration = configured(launch_settings)
        calls = 0
        async def invoke(self, operation, args):
            assert operation == "bridge.health" and args == {}
            self.calls += 1
            if self.calls < 3:
                raise BridgeError("CONFIGURATION_ERROR", "Runtime not written yet")
            return {"instancePath": str(launch_settings.instance_path), "sessionId": "fresh-session",
                "readiness": {"bridgeReady": True, "worldReady": False, "stage": "main_menu", "nextTool": "mcb_load_world"}}
    client = Client()
    result = await launcher.launch_status(client, 5000)
    assert result["status"] == "ready" and result["sessionId"] == "fresh-session" and result["attempts"] == 3
    assert result["readOnly"] and not result["launchDispatched"] and not result["windowExpired"]
    assert not result["startup"]["worldReady"] and result["startup"]["nextTool"] == "mcb_load_world"
    assert backend.commands == [] and "secret-not-returned" not in json.dumps(result)
    assert result["elapsed_ms"] >= 1900


@pytest.mark.parametrize("mode", ["absent", "unreadable", "wrong_instance", "native_timeout"])
async def test_status_expiration_never_proves_a_failed_launch_or_authorizes_retry(launch_settings, monkeypatch, mode):
    backend = Workflow([BridgeError("PROCESS_QUERY_FAILED", "fixture") if mode == "unreadable" else [prism(launch_settings)]])
    monkeypatch.setattr(launcher, "WindowsBackend", lambda: backend)
    class Client:
        configuration = configured(launch_settings)
        async def invoke(self, operation, args):
            if mode == "wrong_instance":
                return {"instancePath": str(launch_settings.instance_path)+"-neighbor", "readiness": {"bridgeReady": True}}
            raise BridgeError("timeout" if mode == "native_timeout" else "CONFIGURATION_ERROR", "fixture")
    result = await launcher.launch_status(Client(), 1000)
    assert result["status"] in {"waiting", "unknown"} and result["windowExpired"]
    assert not result["startup"]["bridgeReady"] and not result["startup"]["retryAction"]
    assert not result["hostObservation"]["lifecycleRecovery"]["launchPermittedByProcessState"]
    assert result["startup"]["nextArguments"]["action"] == "status" and not backend.commands


async def test_status_cancellation_never_spawns_or_leaves_a_relaunch_worker(launch_settings, monkeypatch):
    entered = asyncio.Event()
    class Client:
        configuration = configured(launch_settings)
        async def invoke(self, operation, args):
            entered.set()
            await asyncio.Event().wait()
    monkeypatch.setattr(launcher, "WindowsBackend", lambda: (_ for _ in ()).throw(AssertionError("No spawn or host query after cancellation")))
    task = asyncio.create_task(launcher.launch_status(Client(), 5000))
    await entered.wait(); task.cancel()
    with pytest.raises(asyncio.CancelledError): await task


def test_launch_status_schema_keeps_launch_backward_compatible_and_status_bounded():
    from minecraft_codex_bridge.server import _validate
    for args in ({}, {"action": "launch"}, {"action": "status"}, {"action": "status", "timeout_ms": 60000}):
        _validate("mcb_launch", args)
    for args in ({"timeout_ms": 5000}, {"action": "launch", "timeout_ms": 5000}, {"action": "status", "timeout_ms": 60001}, {"action": "restart"}):
        with pytest.raises(BridgeError): _validate("mcb_launch", args)
