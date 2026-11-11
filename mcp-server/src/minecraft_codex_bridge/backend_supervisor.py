"""Own one restartable worker without replacing the host's MCP stdio connection."""
from __future__ import annotations

import asyncio
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import site
import sys
import tempfile

import anyio

from .backend_catalog import catalog_change, validate_contract
from .backend_connections import ConversationConnections
from .backend_transport import BackendError, MAX_FRAME, PROTOCOL, decode, encode, fingerprint, runtime_versions

STABLE_FILES = ("__init__.py", "_version.py", "frontend.py", "backend_supervisor.py", "backend_catalog.py", "backend_connections.py", "connection_binding.py", "backend_transport.py", "local_config.py", "prism_instances.py")


def source_files(root):
    result, total = {}, 0
    for path in sorted(root.rglob("*")):
        if not (path.suffix == ".py" or path.suffix == ".md" and path.parent.name == "guides"):
            continue
        if path.is_symlink() or not path.is_file():
            raise BackendError("BACKEND_SOURCE_INVALID")
        with path.open("rb") as stream:
            data = stream.read(MAX_FRAME - total + 1)
        total += len(data)
        if len(result) >= 512 or total > MAX_FRAME:
            raise BackendError("BACKEND_SOURCE_TOO_LARGE")
        result[path.relative_to(root).as_posix()] = data
    return result


def manifest(files):
    return fingerprint({name: hashlib.sha256(data).hexdigest() for name, data in files.items()})


class Snapshot:
    def __init__(self, root, stable, directory=None):
        self.temp = tempfile.TemporaryDirectory(prefix="mcb-worker-", dir=directory)
        # Windows TEMP may use an 8.3 alias; handshake paths must use the same canonical root.
        self.root = Path(self.temp.name).resolve()
        try:
            files = source_files(root)
            if manifest({name: files.get(name, b"") for name in STABLE_FILES}) != stable:
                raise BackendError("BACKEND_FRONTEND_RECONNECT_REQUIRED")
            self.fingerprint = manifest(files)
            for name, data in files.items():
                target = self.root / "minecraft_codex_bridge" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            # No bytecode copied; every import is from this immutable revision, never live source.
            if manifest(source_files(root)) != self.fingerprint:
                raise BackendError("BACKEND_SOURCE_CHANGED_DURING_SNAPSHOT")
        except BaseException:
            self.close()
            raise

    def close(self):
        self.temp.cleanup()


class Child:
    def __init__(self, snapshot, environment):
        self.snapshot = snapshot
        self.environment = environment
        self.process = None
        self.last_id = 0
        self.ready = None
        self.broken = False
        self.stderr_task = None

    async def start(self):
        code = "import json,runpy,site,sys;deps=json.loads(sys.argv[2]);[site.addsitedir(p) for p in deps];sys.path[:0]=[sys.argv[1]]+deps;runpy.run_module('minecraft_codex_bridge.backend_worker',run_name='__main__')"
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        # Windows venv python.exe is a redirector process: target the real interpreter so our process handle
        # owns the worker itself, retaining this environment's dependency directories explicitly.
        executable = getattr(sys, "_base_executable", sys.executable) if os.name == "nt" else sys.executable
        self.process = await asyncio.create_subprocess_exec(executable, "-I", "-B", "-c", code, str(self.snapshot.root), json.dumps(site.getsitepackages()),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            limit=MAX_FRAME + 1, env=self.environment, creationflags=flags)
        # Drain stderr independently so diagnostics cannot deadlock the protocol; never echo credentials.
        self.stderr_task = asyncio.create_task(self.drain_stderr())
        self.ready = decode(await self.process.stdout.readline())
        if (self.ready.get("type") != "ready" or self.ready.get("protocol") != PROTOCOL
                or self.ready.get("pid") != self.process.pid or self.ready.get("adapterReady") is not True
                or Path(self.ready.get("packageRoot", "")).resolve() != self.snapshot.root / "minecraft_codex_bridge"
                or self.ready.get("contractFingerprint") != fingerprint(self.ready.get("contract"))):
            raise BackendError("BACKEND_HANDSHAKE_INVALID")
        return self

    async def drain_stderr(self):
        while await self.process.stderr.read(4096):
            pass

    async def rpc(self, method, params=None):
        if self.broken or self.process.returncode is not None:
            raise BackendError("BACKEND_UNAVAILABLE")
        self.last_id += 1
        data = encode({"id": self.last_id, "method": method, "params": params or {}})
        try:
            self.process.stdin.write(data)
            await self.process.stdin.drain()
            reply = decode(await self.process.stdout.readline())
            if not isinstance(reply, dict) or reply.get("id") != self.last_id or type(reply.get("ok")) is not bool:
                raise BackendError("BACKEND_REPLY_INVALID")
        except anyio.get_cancelled_exc_class():
            # Supervisor.call sends a targeted cancel and drains this exact response before admitting another call.
            self.broken = True
            raise
        except BaseException:
            self.broken = True
            raise
        if not reply["ok"]:
            error = reply.get("error", {})
            raise BackendError(error.get("code", "BACKEND_REPLY_INVALID"), outcome_unknown=error.get("outcomeUnknown") is True)
        return reply["result"]

    async def cancel_pending(self):
        try:
            self.process.stdin.write(encode({"cancel": self.last_id}))
            await self.process.stdin.drain()
            reply = decode(await self.process.stdout.readline())
            if not isinstance(reply, dict) or reply.get("id") != self.last_id or type(reply.get("ok")) is not bool:
                raise BackendError("BACKEND_CANCEL_UNCONFIRMED")
            self.broken = False
        except BaseException:
            self.broken = True
            raise

    async def stop(self):
        await self.rpc("shutdown")
        await self.process.wait()
        await self.close()

    async def close(self):
        # Called only for this service's child: an inactive candidate, or service teardown.
        if self.process is not None:
            if self.process.returncode is None:
                self.process.terminate()
                try:
                    await asyncio.wait_for(self.process.wait(), 3)
                except asyncio.TimeoutError:
                    self.process.kill()
                    await self.process.wait()
            if self.stderr_task is not None:
                await self.stderr_task
        self.snapshot.close()


def error_result(code, *, unknown=False):
    return {"ok": False, "error": {"code": code, "outcomeUnknown": unknown,
                                  "automaticActionRetries": False}}


class Supervisor(ConversationConnections):
    def __init__(self, root=None, *, environment=None, snapshot_directory=None):
        self.root = Path(root or Path(__file__).parent).resolve()
        self.init_connections(environment)
        self.snapshot_directory = snapshot_directory
        self.stable = manifest({name: (self.root / name).read_bytes() for name in STABLE_FILES})
        self.runtime = runtime_versions()
        self.child = None
        self.contract = None
        self.generation = 0
        self.catalog_generations = {"tools": 0, "resources": 0}
        self.state = "starting"
        self.last_error = None
        self.gate = anyio.Lock()
        self.restart_records = {}
        self.dispatched = False

    async def candidate(self, *, environment=None):
        snapshot = Snapshot(self.root, self.stable, self.snapshot_directory)
        child = Child(snapshot, dict(self.environment if environment is None else environment))
        try:
            await child.start()
            if child.ready.get("runtime") != self.runtime:
                raise BackendError("BACKEND_DEPENDENCIES_RECONNECT_REQUIRED")
            validate_contract(child.ready["contract"])
            if self.contract is not None and child.ready["contract"]["instructions"] != self.contract["instructions"]:
                raise BackendError("BACKEND_INSTRUCTIONS_RECONNECT_REQUIRED")
            return child
        except BaseException:
            with anyio.CancelScope(shield=True):
                await child.close()
            raise

    def accept_binding(self, child):
        if "MCB_CHAT_BINDING" in self.environment:
            return
        runtime = child.ready.get("runtimeFile")
        if runtime:
            self.environment["MCB_RUNTIME_FILE"] = runtime

    async def start(self):
        child = None
        try:
            with anyio.fail_after(15):
                child = await self.candidate()
                await child.rpc("activate")
            self.child = child
            self.contract = deepcopy(child.ready["contract"])
            self.catalog_generations = {"tools": 1, "resources": 1}
            self.accept_binding(child)
            self.generation = 1
            self.state = "running"
        except Exception as error:
            if child is not None:
                await child.close()
            self.state = "unavailable"
            self.last_error = error.code if isinstance(error, BackendError) else "BACKEND_START_FAILED"

    def status(self):
        child = self.child
        alive = child is not None and child.process.returncode is None and not child.broken
        return {"frontendPid": os.getpid(), "workerPid": child.process.pid if child else None,
            "generation": self.generation, "state": self.state if alive or child is None else "faulted",
            "workerAlive": alive, "busy": self.gate.locked(), "lastError": self.last_error,
            "sourceFingerprint": child.snapshot.fingerprint if child else None,
            "catalogFingerprint": fingerprint(self.contract) if self.contract else None,
            "catalogHotReloadVersion": 1, "catalogGenerations": dict(self.catalog_generations),
            "connection": self.connection_status(),
            "scope": "this_mcp_connection_worker", "automaticActionRetries": False}

    async def call(self, method, params):
        if self.gate.locked():
            raise BackendError("BACKEND_BUSY_NOT_DISPATCHED")
        try:
            self.gate.acquire_nowait()
        except anyio.WouldBlock:
            raise BackendError("BACKEND_BUSY_NOT_DISPATCHED") from None
        try:
            if self.state != "running" or self.child is None:
                raise BackendError("BACKEND_UNAVAILABLE_NOT_DISPATCHED")
            try:
                self.dispatched = True
                with anyio.fail_after(650):
                    try:
                        return await self.child.rpc(method, params)
                    except anyio.get_cancelled_exc_class():
                        with anyio.CancelScope(shield=True):
                            try:
                                with anyio.fail_after(5):
                                    await self.child.cancel_pending()
                            except Exception:
                                self.child.broken = True
                                self.state, self.last_error = "faulted", "BACKEND_CANCEL_UNCONFIRMED"
                        raise
            except Exception as error:
                if self.child.broken or self.child.process.returncode is not None:
                    self.state = "faulted"
                    self.last_error = "BACKEND_CALL_OUTCOME_UNKNOWN"
                    raise BackendError(self.last_error, outcome_unknown=True) from None
                raise error
        finally:
            self.gate.release()

    async def restart(self, args):
        identifier = args["requestId"]
        previous = self.restart_records.get(identifier)
        if previous is not None:
            if previous["args"] != args:
                return error_result("BACKEND_RESTART_ID_CONFLICT")
            return deepcopy(previous["result"] or {"ok": False, "error": {"code": "BACKEND_RESTART_IN_PROGRESS"}})
        if len(self.restart_records) >= 64:
            return error_result("BACKEND_RESTART_CAPACITY")
        if self.gate.locked():
            return error_result("BACKEND_BUSY_NOT_DISPATCHED")
        try:
            self.gate.acquire_nowait()
        except anyio.WouldBlock:
            return error_result("BACKEND_BUSY_NOT_DISPATCHED")
        record = {"args": deepcopy(args), "result": None}
        self.restart_records[identifier] = record
        old, candidate, prepared, retiring = self.child, None, False, False
        previous_state = self.state
        catalog_missing = self.contract is None
        result = None
        with anyio.CancelScope(shield=True):
            try:
                if args["expectedGeneration"] != self.generation:
                    raise BackendError("BACKEND_GENERATION_CHANGED")
                if self.state == "faulted" or old is not None and (old.broken or old.process.returncode is not None):
                    raise BackendError("BACKEND_RECONNECT_REQUIRED", outcome_unknown=self.dispatched)
                self.state = "restarting"
                with anyio.fail_after(args.get("timeout_ms", 30000) / 1000):
                    checkpoint = None
                    if old is not None:
                        checkpoint = await old.rpc("prepare_restart")
                        prepared = True
                        # Discovery can first succeed after startup. Pin the actual current selection before spawning.
                        binding = checkpoint["binding"]
                        runtime = binding.get("runtimeFile") if binding.get("offline") else binding["connection"].get("runtimeFile")
                        if runtime:
                            self.environment["MCB_RUNTIME_FILE"] = runtime
                    candidate = await self.candidate()
                    change = catalog_change(self.contract, candidate.ready["contract"])
                    expected = args.get("expectedSourceFingerprint")
                    if expected is not None and candidate.snapshot.fingerprint != expected:
                        raise BackendError("BACKEND_SOURCE_FINGERPRINT_CHANGED")
                    restored = {"restoredOrders": 0}
                    if checkpoint is not None:
                        restored = await candidate.rpc("restore_state", checkpoint)
                        if (restored.get("stateFingerprint") != checkpoint.get("stateFingerprint")
                                or restored.get("binding") != checkpoint["binding"]):
                            raise BackendError("BACKEND_STATE_HANDOFF_UNCONFIRMED")
                        await old.rpc("verify_binding", {"binding": checkpoint["binding"]})
                        retiring = True
                        await old.stop()
                        self.child = None
                    await candidate.rpc("activate")
                    self.child, candidate = candidate, None
                    self.contract = deepcopy(self.child.ready["contract"])
                    for kind in self.catalog_generations:
                        if change[kind]["changed"]:
                            self.catalog_generations[kind] += 1
                    self.accept_binding(self.child)
                    self.generation += 1
                    self.state, self.last_error = "running", None
                    result = {"ok": True, "data": {**self.status(), "busy": False, "previousWorkerPid": old.process.pid if old else None,
                        "restoredOrders": restored["restoredOrders"],
                        "restoredFailedActions": restored.get("restoredFailedActions", 0),
                        "stateFingerprint": checkpoint.get("stateFingerprint") if checkpoint is not None else None,
                        "gameReadiness": "not_observed",
                        "catalogUpdate": change,
                        "clientCatalogRefresh": "not_observable",
                        "connectionPreserved": True}}
                    result["data"]["catalogReconnectRequired"] = catalog_missing
            except Exception as error:
                self.last_error = error.code if isinstance(error, BackendError) else "BACKEND_RESTART_FAILED"
                # Only a pre-commit failure with a healthy original channel can restore its admission gate.
                if old is not None and not retiring and not old.broken and old.process.returncode is None:
                    try:
                        if prepared:
                            with anyio.fail_after(3):
                                await old.rpc("activate")
                        self.state = "running"
                    except Exception:
                        self.state = "faulted"
                else:
                    self.state = "faulted" if old is not None or previous_state == "faulted" else "unavailable"
                result = error_result(self.last_error, unknown=getattr(error, "outcome_unknown", False))
                result["data"] = {**self.status(), "busy": False}
            finally:
                try:
                    if candidate is not None:
                        await candidate.close()
                finally:
                    record["result"] = deepcopy(result)
                    self.gate.release()
        return result

    async def close(self):
        with anyio.CancelScope(shield=True):
            if self.child is not None:
                await self.child.close()
