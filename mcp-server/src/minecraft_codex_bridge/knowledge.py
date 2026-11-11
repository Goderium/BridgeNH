"""Bounded local experience summaries. History is advice, never live game evidence.

Only fixed typed projections reach disk: no screenshots, NBT, messages, command
text, credentials, full DTOs or caller-written plans. SQLite work runs outside
the game/stdio event loop. A failed recorder never retries a game operation.
"""
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
import uuid
from typing import Any, Mapping

from . import operation_knowledge


SCHEMA_VERSION = 1
APPLICATION_ID = 0x4D43424B
MAX_RECORDS = 50_000
MAX_DIAGNOSTICS = 2048
MAX_DATABASE_BYTES = 256 * 1024 * 1024
SUMMARY_TTL = 180 * 86400
DIAGNOSTIC_TTL = 7 * 86400
MAX_HINTS = 3
GOALS = frozenset({"mcb_recipe_once", "mcb_blueprint_build", "mcb_qa_test_once",
    "mcb_approach", "mcb_sequence", "mcb_placement_preflight", "mcb_ae_install", "mcb_ae_configure", "mcb_ae_order"})
METHOD_VALUES = {
    "strategy": {"walk", "fly", "teleport", "walking", "ability_flight", "developer_teleport"},
    "recipeLookup": {"backend_match", "catalog"},
    "recipeMode": {"single", "public_multi"},
    "outputMode": {"inventory", "provider_cache"},
    "inventoryPreparation": {"clear", "preserve"},
    "catalystMode": {"physical", "configured_ghost"},
    "action": {"use", "attack", "click", "set_value", "activate", "probe", "status", "run", "stop", "cancel", "open", "close",
        "start", "resume", "take", "priority_open", "priority_set", "host_setting", "terminal_search", "terminal_setting",
        "terminal_type_filter", "resource_take", "resource_deposit", "resource_container", "craft_open", "craft_request",
        "crafting_output", "pattern_set", "cell_config_set", "cell_config_clear", "pattern_mode", "pattern_multiply",
        "pattern_page", "pattern_encode", "pattern_clear", "preview_start", "cpu_select", "job_cancel", "job_pause"},
}
BOOL_ARGUMENTS = ("actualSurvival", "generateMaterials", "interact", "move", "requireFullFlow", "restoreInventory")
FACT_BOOLS = ("complete", "partial", "available", "arrivalConfirmed", "fullFlowVerified", "testPassed",
    "reportsComplete", "reportCoherent", "spaceVerified", "controllerStopped", "recoveryVerified",
    "placementConfirmed", "cleanupConfirmed", "materialSupplyConfirmed", "outputRecovered",
    "completeDualFullNbtConserved", "original_action_executed", "nativeSubmitted", "structureFormed",
    "serverStopConfirmed", "alreadyInRange", "interactionEffectConfirmed", "movementDispatched", "interactionDispatched",
    "componentCoverageComplete", "confirmed")
UNKNOWN_KEYS = ("outcomeUnknown", "outcome_unknown", "sideEffectsPossible")
IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]{1,128}\Z")
_CAPTURE: ContextVar["Capture | None"] = ContextVar("mcb_knowledge_capture", default=None)
_PHASE: ContextVar[str | None] = ContextVar("mcb_operation_phase", default=None)


@contextmanager
def operation_phase(phase):
    """Internal workflow labels only; never caller step IDs or arbitrary DTOs."""
    token = _PHASE.set(phase if isinstance(phase, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,79}", phase) else "unclassified")
    try:
        yield
    finally:
        _PHASE.reset(token)


def record_wait_sample():
    capture = _CAPTURE.get()
    if capture is not None:
        capture.wait_samples += 1


def _hash(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
        allow_nan=False, separators=(",", ":")).encode("utf-8")).hexdigest()


def _identifier(value: Any) -> str | None:
    return value if isinstance(value, str) and IDENTIFIER.fullmatch(value) else None


def _number(value: Any) -> float | None:
    return float(value) if type(value) in (int, float) and 0 <= value <= 1e12 and math.isfinite(value) else None


def _facts(value: Any) -> dict:
    if not isinstance(value, dict):
        return {}
    result = {key: value[key] for key in FACT_BOOLS if type(value.get(key)) is bool}
    for key in ("status", "stage", "phase", "errorCode", "code", "stopReason"):
        identifier = _identifier(value.get(key))
        if identifier is not None:
            result[key] = identifier
    for key in ("elapsedMs", "elapsed_ms", "elapsedMillis"):
        duration = _number(value.get(key))
        if duration is not None:
            result["elapsedMs"] = duration
            break
    for key in UNKNOWN_KEYS:
        if type(value.get(key)) is bool:
            result[key] = value[key]
    return result


def _without_timing(value: dict) -> dict:
    return {key: _without_timing(item) if isinstance(item, dict) else item
        for key, item in value.items() if key != "elapsedMs"}


@dataclass
class Capture:
    name: str
    call_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    parent_call_id: str | None = None
    goal_digest: str | None = None
    wait_samples: int = 0
    phases: dict = field(default_factory=dict)
    phases_limited: bool = False
    methods: dict = field(default_factory=dict)
    methods_limited: bool = False
    expected_session: str | None = None
    session: str | None = None
    instance: str | None = None
    versions: dict = field(default_factory=dict)
    capability_digest: str | None = None
    world: str | None = None
    dimension: int | None = None
    profile: str | None = None
    remote_connection: str | None = None
    environment: str = "unknown"
    inconsistent: bool = False
    health_observed: bool = False
    observations: int = 0
    trace: list = field(default_factory=list)
    sites: list = field(default_factory=list)

    def observe(self, operation: str, value: dict, context: dict | None, instance: str | None, session: str | None, arguments=None):
        actual = value.get("sessionId", session)
        if isinstance(actual, str):
            if (self.session is not None and actual != self.session) or (self.expected_session is not None and actual != self.expected_session):
                self.inconsistent = True
            self.session = actual
        if isinstance(instance, str):
            if self.instance is not None and self.instance != instance:
                self.inconsistent = True
            self.instance = instance
        if operation == "bridge.health":
            self.health_observed = True
            from .operation_policy import environment
            self.environment = environment(value)
            for key in ("protocolVersion", "modVersion", "gameVersion"):
                item = value.get(key)
                if type(item) is int or _identifier(item) is not None:
                    self.versions[key] = item
            capabilities = value.get("capabilities")
            if isinstance(capabilities, list) and len(capabilities) <= 512 and all(_identifier(item) for item in capabilities):
                fixed = {key: item for key, item in value.items() if key.endswith("Version") and type(item) is int}
                self.capability_digest = _hash([sorted(capabilities), fixed])
            elif isinstance(capabilities, dict) and len(capabilities) <= 512 and all(_identifier(key) and type(item) is bool for key, item in capabilities.items()):
                self.capability_digest = _hash(capabilities)
            if value.get("profile") in {"developer", "player"}:
                self.profile = value["profile"]
            remote = value.get("serverBridge")
            if self.environment == "remote_server" and isinstance(remote, dict) and isinstance(remote.get("connectionId"), str):
                self.remote_connection = _hash(remote["connectionId"])
        snapshot = context.get("snapshot") if isinstance(context, dict) else None
        if operation == "client.snapshot":
            snapshot = value
        if isinstance(snapshot, dict):
            world = snapshot.get("world")
            if isinstance(world, dict):
                name = world.get("worldFolder") or world.get("worldName")
                if isinstance(name, str) and 1 <= len(name) <= 512:
                    digest = _hash(name)
                    if self.world is not None and self.world != digest:
                        self.inconsistent = True
                    self.world = digest
                dimension = world.get("dimension")
                if type(dimension) is int:
                    if self.dimension is not None and self.dimension != dimension:
                        self.inconsistent = True
                    self.dimension = dimension
        self.observations += 1
        phase = _PHASE.get()
        if phase is not None:
            if phase in self.phases or len(self.phases) < 32:
                self.phases[phase] = self.phases.get(phase, 0) + 1
            else:
                self.phases_limited = True
        method = {key: arguments[key] for key, choices in METHOD_VALUES.items()
            if isinstance(arguments, dict) and isinstance(arguments.get(key), str) and arguments[key] in choices}
        if "action" in method:
            key = (operation, method["action"])
            if key in self.methods or len(self.methods) < 32:
                self.methods[key] = self.methods.get(key, 0) + 1
            else:
                self.methods_limited = True
        if len(self.trace) < 24:
            self.trace.append({"operation": operation, **_facts(value), "phase": phase, "method": method})
        # Positions are a separate expiring index, not reusable inventory/formation proof.
        if operation in {"world.inspect", "machine.inspect", "server.observe"} \
                and value.get("source") in {"integrated_server", "remote_server_tick"}:
            position = {axis: value[axis] for axis in ("x", "y", "z") if type(value.get(axis)) is int}
            identity = {key: value[key] for key in ("registryName", "metaClass") if _identifier(value.get(key))}
            if len(position) == 3 and identity:
                if type(value.get("dimension")) is int:
                    site = {**position, **identity, "dimension": value["dimension"], "requiresLiveRevalidation": True}
                    existing = next((index for index, item in enumerate(self.sites)
                        if all(item[key] == site[key] for key in ("x", "y", "z", "dimension"))), None)
                    if existing is not None:
                        self.sites[existing] = site
                    elif len(self.sites) < 8:
                        self.sites.append(site)


@contextmanager
def capture_scope(name: str, arguments: dict):
    capture = Capture(name, expected_session=arguments.get("expectedSessionId"))
    parent = _CAPTURE.get()
    capture.parent_call_id = parent.call_id if parent is not None else None
    if name == "mcb_ae_order" and isinstance(arguments.get("requestId"), str):
        capture.goal_digest = _hash([name, arguments.get("expectedSessionId"), arguments["requestId"]])
    token = _CAPTURE.set(capture)
    try:
        yield capture
    finally:
        _CAPTURE.reset(token)


def observe_response(operation: str, value: dict, context: dict | None, instance: str | None, session: str | None, arguments=None):
    capture = _CAPTURE.get()
    if capture is not None:
        try:
            capture.observe(operation, value, context, instance, session, arguments)
        except (ValueError, TypeError, OverflowError, RecursionError):
            capture.inconsistent = True


class KnowledgeStore:
    """Short independent SQLite transactions; bounded locks/storage, no worker queue."""
    def __init__(self, environ: Mapping[str, str] | None = None):
        environment = os.environ if environ is None else environ
        root = environment.get("LOCALAPPDATA") or environment.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
        self.path = Path(environment.get("MCB_KNOWLEDGE_PATH") or Path(root) / "MinecraftCodexBridge" / "knowledge" / "experience.sqlite3")
        self.mode_override = environment.get("MCB_KNOWLEDGE_MODE") or None
        if self.mode_override not in {None, "off", "summary", "diagnostic"}:
            self.mode_override = "off"
        self.context: dict | None = None
        self.manifest_cache: tuple | None = None
        self.maintenance_at = 0.0
        self.last_error: str | None = None
        self.gate = threading.Lock()
        self.environment_diagnostic_until = time.time() + 900
        self.source_digest: str | None = None

    def check_session(self, session: str | None):
        context = self.context
        if context is not None and (not isinstance(session, str) or context.get("session") != _hash(session)):
            self.context = None

    def _connect(self):
        if self.path.exists() and self.path.stat().st_size > MAX_DATABASE_BYTES:
            raise ValueError("knowledge_database_budget")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=0.025)
        try:
            db.execute("PRAGMA busy_timeout=25")
            application = db.execute("PRAGMA application_id").fetchone()[0]
            if application != APPLICATION_ID:
                existing = db.execute("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1").fetchone()
                if application != 0 or existing is not None:
                    raise ValueError("not_a_knowledge_database")
            db.execute("PRAGMA synchronous=NORMAL")
            db.execute("PRAGMA journal_size_limit=1048576")
            # DELETE journaling avoids an unbounded WAL after abandoned readers.
            db.execute("PRAGMA journal_mode=DELETE")
            page_size = db.execute("PRAGMA page_size").fetchone()[0]
            db.execute(f"PRAGMA max_page_count={MAX_DATABASE_BYTES // page_size}")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, SCHEMA_VERSION):
                raise ValueError("knowledge_schema_unsupported")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS experiences (
                    id TEXT PRIMARY KEY, binding TEXT NOT NULL, tool TEXT NOT NULL, method TEXT NOT NULL,
                    status TEXT NOT NULL, evidence TEXT NOT NULL, first_at REAL NOT NULL, last_at REAL NOT NULL,
                    occurrences INTEGER NOT NULL, witnesses TEXT NOT NULL, elapsed_sum REAL NOT NULL,
                    elapsed_min REAL NOT NULL, elapsed_max REAL NOT NULL, summary TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS experiences_lookup ON experiences(binding, tool, last_at DESC);
                CREATE TABLE IF NOT EXISTS diagnostics (id INTEGER PRIMARY KEY, record_id TEXT NOT NULL,
                    created REAL NOT NULL, summary TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS diagnostics_lookup ON diagnostics(record_id, created DESC);
                CREATE TABLE IF NOT EXISTS sites (id TEXT PRIMARY KEY, binding TEXT NOT NULL, world TEXT NOT NULL,
                    dimension INTEGER NOT NULL, last_at REAL NOT NULL, summary TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS sites_lookup ON sites(binding, world, dimension, last_at DESC);
                CREATE TABLE IF NOT EXISTS operation_lessons (
                    id TEXT PRIMARY KEY, binding TEXT NOT NULL, tool TEXT NOT NULL, kind TEXT NOT NULL,
                    retired INTEGER NOT NULL, last_at REAL NOT NULL, summary TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS operation_lessons_lookup ON operation_lessons(binding,tool,last_at DESC);
            """)
            db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            db.execute(f"PRAGMA application_id={APPLICATION_ID}")
            db.commit()
            return db
        except BaseException:
            db.close()
            raise

    def _mode(self, db):
        rows = dict(db.execute("SELECT key,value FROM settings"))
        mode = self.mode_override or rows.get("mode", "summary")
        until = float(rows.get("diagnostic_until", "0"))
        if self.mode_override == "diagnostic":
            until = self.environment_diagnostic_until
        if mode == "diagnostic" and time.time() >= until:
            mode = "summary"
        return mode, until

    def _manifest(self, instance: str | None, session: str):
        if instance is None:
            return None
        directory = Path(instance) / ".minecraft" / "mods"
        try:
            stamp = directory.stat().st_mtime_ns
            cached = self.manifest_cache
            if cached is not None and cached[:3] == (instance, stamp, session) and time.monotonic() - cached[3] < 30:
                return cached[4]
            rows = []
            # Only conventional version subdirectories; no recursive dependency scan.
            for folder in (directory, directory / "1.7.10"):
                if not folder.is_dir():
                    continue
                for path in folder.iterdir():
                    if path.suffix.lower() != ".jar" or not path.is_file():
                        continue
                    if len(rows) >= 4096:
                        return None
                    state = path.stat()
                    rows.append((path.relative_to(directory).as_posix(), state.st_size, state.st_mtime_ns))
            digest = _hash(sorted(rows)) if rows else None
            self.manifest_cache = (instance, stamp, session, time.monotonic(), digest)
            return digest
        except OSError:
            return None

    def _binding(self, capture: Capture):
        if capture.inconsistent or not capture.health_observed or not capture.session or not capture.capability_digest:
            return None
        manifest = self._manifest(capture.instance, capture.session)
        if self.source_digest is None:
            sources = sorted(Path(__file__).parent.glob("*.py"))
            if len(sources) > 128:
                return None
            digest = hashlib.sha256()
            for path in sources:
                with path.open("rb") as stream:
                    raw = stream.read(1_048_577)
                if len(raw) > 1_048_576:
                    return None
                digest.update(path.name.encode("utf-8") + b"\0" + hashlib.sha256(raw).digest())
            self.source_digest = digest.hexdigest()
        compatible = manifest is not None and capture.environment == "local_integrated" and len(capture.versions) == 3
        value = {"contract": SCHEMA_VERSION, "instance": _hash(capture.instance) if capture.instance else None,
            "versions": capture.versions, "capabilities": capture.capability_digest, "profile": capture.profile,
            "manifest": manifest, "session": None if compatible else _hash(capture.session),
            "remoteConnection": capture.remote_connection, "environment": capture.environment, "adapterSource": self.source_digest}
        return {"id": _hash(value), "scope": "same_instance_versions_capabilities_manifest" if compatible else "same_live_session",
            "manifestEvidence": "jar_names_sizes_mtimes; compatibility_hint_only" if manifest else "unavailable",
            "world": capture.world, "dimension": capture.dimension, "session": _hash(capture.session)}

    def _maintain(self, db, now):
        if time.monotonic() - self.maintenance_at >= 60:
            db.execute("DELETE FROM experiences WHERE last_at < ?", (now - SUMMARY_TTL,))
            db.execute("DELETE FROM diagnostics WHERE created < ?", (now - DIAGNOSTIC_TTL,))
            db.execute("DELETE FROM sites WHERE last_at < ?", (now - 7 * 86400,))
            self.maintenance_at = time.monotonic()
        for table, limit, order in (("experiences", MAX_RECORDS, "last_at"), ("diagnostics", MAX_DIAGNOSTICS, "created"), ("sites", 4096, "last_at")):
            count = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            if count > limit:
                db.execute(f"DELETE FROM {table} WHERE id IN (SELECT id FROM {table} ORDER BY {order} ASC LIMIT ?)", (count - limit,))

    def _hints(self, db, context, tool, method, exclude_id, limit=MAX_HINTS):
        if context is None:
            return []
        rows = db.execute("SELECT id,evidence,occurrences,last_at,summary,elapsed_sum,elapsed_min,elapsed_max FROM experiences "
            "WHERE binding=? AND tool=? AND method=? AND id<>? AND last_at>=? ORDER BY last_at DESC LIMIT ?",
            (context["id"], tool, method, exclude_id, time.time() - SUMMARY_TTL, limit)).fetchall()
        hints = []
        for row in rows:
            summary = json.loads(row[4])
            hints.append({"id": row[0], "evidence": row[1], "occurrences": row[2], "lastObservedUtc": row[3],
                "status": summary["status"], "method": summary["method"],
                "failure": summary["facts"].get("failure"), "compatibilityScope": context["scope"],
                "timing": {"meanMs": round(row[5] / row[2], 3), "minMs": row[6], "maxMs": row[7]},
                "historicalOnly": True, "requiresLiveRevalidation": True})
        return hints

    def finish(self, capture: Capture, arguments: dict, payload: dict, timing: dict, *, read_only: bool):
        if not self.gate.acquire(blocking=False):
            return {"status": "local_knowledge_busy", "gameResultPreserved": True}
        try:
            return self._finish(capture, arguments, payload, timing, read_only=read_only)
        finally:
            self.gate.release()

    def _finish(self, capture: Capture, arguments: dict, payload: dict, timing: dict, *, read_only: bool):
        """Return only relevant older summaries, then merge this fixed projection."""
        if self.mode_override == "off":
            return None
        outcome = payload.get("data") if isinstance(payload.get("data"), dict) else {}
        error = payload.get("error") or outcome.get("error")
        if isinstance(error, dict) and error.get("code") in {"SESSION_CHANGED", "INSTANCE_MISMATCH", "WORLD_CHANGED", "PROFILE_CHANGED", "AUTH_FAILED"}:
            capture.inconsistent = True
        context = self._binding(capture)
        if capture.inconsistent:
            self.context = None
        elif context is not None:
            self.context = context
        failed = payload.get("ok") is False or isinstance(error, dict)
        relevant = capture.name in GOALS or failed or not read_only
        if context is None or not relevant and not capture.sites and capture.name != "mcb_health":
            return None
        method = {key: arguments[key] for key, values in METHOD_VALUES.items()
            if isinstance(arguments.get(key), str) and arguments[key] in values}
        method.update({key: arguments[key] for key in BOOL_ARGUMENTS if type(arguments.get(key)) is bool})
        if "openGui" in arguments:
            method["openGui"] = True
        if "singleBlockPlacement" in arguments:
            method["singleBlockPlacement"] = True
        method_key = json.dumps(method, sort_keys=True, separators=(",", ":"))
        facts = _facts(outcome)
        acceptance = outcome.get("acceptance")
        if isinstance(acceptance, dict):
            facts["acceptance"] = _facts(acceptance)
            for key in ("registrationAcceptance", "stopAcceptance"):
                if isinstance(acceptance.get(key), dict):
                    facts["acceptance"][key] = _facts(acceptance[key])
        for key in ("emergencyStop", "movement", "cleanup", "retainedPlayerContext"):
            if isinstance(outcome.get(key), dict):
                facts[key] = _facts(outcome[key])
        if isinstance(error, dict):
            facts["failure"] = _facts(error)
            receipt = error.get("receipt")
            if isinstance(receipt, dict):
                facts["receipt"] = _facts(receipt)
        unknown = any(value.get(key) is True for value in (outcome, error if isinstance(error, dict) else {}) for key in UNKNOWN_KEYS)
        if outcome.get("partial") is True:
            unknown = True
        status = "failed" if failed else _identifier(outcome.get("status")) or "returned"
        # Keep the actual acceptance scope; planned/submitted/legacy QA never become production proof.
        evidence = "uncertain" if unknown else "observed_failure" if failed else "returned_only"
        if not failed and not unknown and status == "completed":
            if capture.name == "mcb_recipe_once" and isinstance(acceptance, dict) and acceptance.get("controllerStopped") is True:
                evidence = "recipe_task_acceptance"
            elif capture.name == "mcb_qa_test_once" and outcome.get("fullFlowVerified") is True:
                evidence = "qa_full_flow_acceptance"
            elif capture.name == "mcb_approach":
                evidence = "interaction_arrival_only"
                if outcome.get("interactionEffectConfirmed") is True:
                    evidence = "declared_gui_acceptance_only"
            elif capture.name == "mcb_blueprint_build" and isinstance(acceptance, dict) \
                    and acceptance.get("structureFormed") is True \
                    and isinstance(acceptance.get("registrationAcceptance"), dict) \
                    and acceptance["registrationAcceptance"].get("complete") is True:
                evidence = "structure_task_acceptance"
            elif capture.name in {"mcb_blueprint_build", "mcb_sequence", "mcb_placement_preflight"}:
                evidence = "declared_task_scope_only"
        summary = {"tool": capture.name, "method": method, "status": status, "facts": facts,
            "compatibilityScope": context["scope"], "manifestEvidence": context["manifestEvidence"],
            "scope": "historical_task_receipt; not live contents, formation, permission or replay authority"}
        calls = timing.get("bridgeCalls")
        if type(calls) is int and 0 <= calls <= 1_000_000:
            summary["bridgeCalls"] = calls
        operations = timing.get("bridgeByOperation")
        if isinstance(operations, list):
            summary["operations"] = [{"operation": item["operation"], "calls": item["calls"]}
                for item in operations[:12] if isinstance(item, dict) and _identifier(item.get("operation"))
                and type(item.get("calls")) is int and 0 <= item["calls"] <= 1_000_000]
        key_facts = _without_timing(facts)
        identifier = _hash([context["id"], capture.name, method, status, evidence, key_facts])
        duration = _number(timing.get("adapterElapsedMs")) or 0.0
        now = time.time()
        try:
            db = self._connect()
            try:
                mode, until = self._mode(db)
                if mode == "off":
                    return None
                hints = self._hints(db, context, capture.name, method_key, identifier) if relevant else []
                recorded = False
                occurrences = 0
                if relevant:
                    row = db.execute("SELECT witnesses,occurrences FROM experiences WHERE id=?", (identifier,)).fetchone()
                    witnesses = json.loads(row[0]) if row else []
                    occurrences = min(row[1] + 1, 2147483647) if row else 1
                    witness = _hash(capture.session)
                    if witness not in witnesses and len(witnesses) < 32:
                        witnesses.append(witness)
                    db.execute("""INSERT INTO experiences VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(id) DO UPDATE SET last_at=excluded.last_at,
                        occurrences=MIN(experiences.occurrences+1,2147483647), witnesses=excluded.witnesses,
                        elapsed_sum=experiences.elapsed_sum+excluded.elapsed_sum,
                        elapsed_min=MIN(experiences.elapsed_min,excluded.elapsed_min),
                        elapsed_max=MAX(experiences.elapsed_max,excluded.elapsed_max),summary=excluded.summary""",
                        (identifier, context["id"], capture.name, method_key, status, evidence, now, now, 1,
                         json.dumps(witnesses), duration, duration, duration,
                         json.dumps(summary, ensure_ascii=False, allow_nan=False, separators=(",", ":"))))
                    recorded = True
                if mode == "diagnostic" and relevant:
                    phases = outcome.get("phases")
                    trace = {"tool": capture.name, "recordId": identifier, "observations": capture.observations,
                        "operations": capture.trace, "phases": [_facts(item) for item in phases[:32]] if isinstance(phases, list) else [],
                        "bridgeCalls": timing.get("bridgeCalls"), "adapterElapsedMs": duration,
                        "bridgeRoundTripSumMs": _number(timing.get("bridgeRoundTripSumMs")),
                        "timingScope": "adapter before local knowledge I/O; excludes model planning and host approval",
                        "scope": "bounded_semantic_trace; excludes paths, views, items, NBT and images"}
                    stages = outcome.get("timings_ms")
                    if isinstance(stages, dict):
                        trace["taskStagesMs"] = {key: _number(stages[key]) for key in
                            ("preflight", "recipeDecision", "preparation", "executionAcceptance", "total") if _number(stages.get(key)) is not None}
                    db.execute("INSERT INTO diagnostics(record_id,created,summary) VALUES (?,?,?)",
                        (identifier, now, json.dumps(trace, separators=(",", ":"))))
                if context["world"] is not None and type(context["dimension"]) is int:
                    for site in capture.sites:
                        if site["dimension"] != context["dimension"]:
                            continue
                        key = _hash([context["id"], context["world"], context["dimension"],
                            {axis: site[axis] for axis in ("x", "y", "z")}])
                        db.execute("INSERT OR REPLACE INTO sites VALUES (?,?,?,?,?,?)", (key, context["id"], context["world"],
                            context["dimension"], now, json.dumps(site, separators=(",", ":"))))
                self._maintain(db, now)
                db.commit()
                self.last_error = None
                if not relevant and capture.name != "mcb_health":
                    return None
                operating = operation_knowledge.compact(operation_knowledge.lessons(db, context,
                    tool=None if capture.name == "mcb_health" else capture.name,
                    method=None if capture.name == "mcb_health" else method, limit=2, unknown=unknown))
                if operating["lessons"]:
                    hints = []  # Explicit query still exposes raw receipts; do not repeat them beside distilled advice.
                if not relevant:
                    return {"mode": mode, "operatingLessons": operating, "historicalOnly": True, "automaticReplayAllowed": False}
                return {"mode": mode, "recorded": recorded, "recordId": identifier, "evidence": evidence, "occurrences": occurrences,
                    "hints": hints, "operatingLessons": operating, "historicalOnly": True, "automaticReplayAllowed": False,
                    "diagnosticUntilUtc": until if mode == "diagnostic" else None}
            finally:
                db.close()
        except (OSError, sqlite3.Error, ValueError, TypeError, RecursionError):
            self.last_error = "local_knowledge_unavailable"
            return {"recorded": False, "status": self.last_error, "gameResultPreserved": True} if relevant else None

    def command(self, arguments: dict, *, token=""):
        if not self.gate.acquire(blocking=False):
            raise ValueError("local_knowledge_busy")
        try:
            return self._command(arguments, token=token)
        finally:
            self.gate.release()

    def _command(self, arguments: dict, *, token=""):
        """Local receipts/lessons; no Minecraft action or executable policy promotion."""
        action = arguments.get("action", "status")
        db = self._connect()
        try:
            now = time.time()
            if action == "learn":
                return operation_knowledge.learn(db, self.context, arguments, token)
            if action in {"lessons", "review"}:
                return operation_knowledge.lessons(db, self.context, tool=arguments.get("tool"),
                    limit=arguments.get("limit", MAX_HINTS), review=action == "review", identity=arguments.get("id"))
            if action == "retire":
                db.execute("UPDATE operation_lessons SET retired=1,last_at=? WHERE id=?", (now, arguments["id"]))
                db.commit()
                return {"retired": db.total_changes > 0, "id": arguments["id"], "sourceCodeChanged": False}
            if action == "configure":
                if self.mode_override is not None:
                    return {"configured": False, "reason": "MCB_KNOWLEDGE_MODE_environment_override"}
                mode = arguments["mode"]
                until = now + arguments.get("diagnosticSeconds", 900) if mode == "diagnostic" else 0
                for key, value in (("mode", mode), ("diagnostic_until", str(until))):
                    db.execute("INSERT OR REPLACE INTO settings VALUES (?,?)", (key, value))
                db.commit()
            elif action == "forget":
                # Exact record only; never removes game files, DB directory or another store.
                db.execute("DELETE FROM experiences WHERE id=?", (arguments["id"],))
                db.execute("DELETE FROM diagnostics WHERE record_id=?", (arguments["id"],))
                db.execute("DELETE FROM operation_lessons WHERE id=?", (arguments["id"],))
                db.commit()
                return {"forgotten": db.total_changes > 0, "id": arguments["id"]}
            elif action in {"query", "candidates", "sites", "diagnostics"}:
                if self.context is None:
                    return {"available": False, "reason": "current_compatible_context_not_observed", "hints": []}
                limit = arguments.get("limit", MAX_HINTS)
                if action == "diagnostics":
                    record = db.execute("SELECT id FROM experiences WHERE id=? AND binding=? AND last_at>=?",
                        (arguments["id"], self.context["id"], now - SUMMARY_TTL)).fetchone()
                    rows = db.execute("SELECT created,summary FROM diagnostics WHERE created>=? "
                        "AND record_id=? ORDER BY created DESC LIMIT ?",
                        (now - DIAGNOSTIC_TTL, arguments["id"], limit)).fetchall() if record else []
                    return {"diagnostics": [{"observedUtc": row[0], **json.loads(row[1])} for row in rows],
                        "historicalOnly": True, "automaticReplayAllowed": False}
                if action == "sites":
                    context = self.context
                    if context["world"] is None or type(context["dimension"]) is not int:
                        return {"available": False, "reason": "current_world_not_observed", "sites": []}
                    rows = db.execute("SELECT summary,last_at FROM sites WHERE binding=? AND world=? AND dimension=? "
                        "AND last_at>=? ORDER BY last_at DESC LIMIT ?", (context["id"], context["world"], context["dimension"],
                        now - 7 * 86400, limit)).fetchall()
                    return {"sites": [{**json.loads(row[0]), "lastObservedUtc": row[1]} for row in rows],
                        "historicalOnly": True, "requiresLiveRevalidation": True}
                query = "SELECT id,evidence,occurrences,witnesses,summary FROM experiences WHERE binding=? AND last_at>=?"
                parameters = [self.context["id"], now - SUMMARY_TTL]
                if "tool" in arguments:
                    query += " AND tool=?"
                    parameters.append(arguments["tool"])
                if action == "candidates":
                    query += " AND occurrences>=3 AND evidence IN ('recipe_task_acceptance','qa_full_flow_acceptance','structure_task_acceptance')"
                query += " ORDER BY last_at DESC LIMIT 128"
                hints = []
                for row in db.execute(query, parameters):
                    if action == "candidates" and len(json.loads(row[3])) < 2:
                        continue
                    hints.append({"id": row[0], "evidence": row[1], "occurrences": row[2],
                        "independentSessions": len(json.loads(row[3])), **json.loads(row[4]),
                        "historicalOnly": True, "requiresLiveRevalidation": True})
                    if len(hints) >= limit:
                        break
                return {"hints": hints, "purpose": "manual_generalization_review" if action == "candidates" else "relevant_history",
                    "automaticPolicyPromotion": False, "automaticReplayAllowed": False}
            self._maintain(db, now)
            db.commit()
            mode, until = self._mode(db)
            return {"mode": mode, "diagnosticUntilUtc": until if mode == "diagnostic" else None,
                "environmentOverride": self.mode_override is not None,
                "records": db.execute("SELECT COUNT(*) FROM experiences").fetchone()[0],
                "diagnostics": db.execute("SELECT COUNT(*) FROM diagnostics").fetchone()[0],
                "sites": db.execute("SELECT COUNT(*) FROM sites").fetchone()[0],
                "operatingLessons": db.execute("SELECT COUNT(*) FROM operation_lessons WHERE retired=0").fetchone()[0],
                "limits": {"records": MAX_RECORDS, "diagnostics": MAX_DIAGNOSTICS, "databaseBytes": MAX_DATABASE_BYTES,
                    "summaryTtlDays": 180, "diagnosticTtlDays": 7, "siteTtlDays": 7, "automaticHints": MAX_HINTS,
                    "operatingLessons": operation_knowledge.MAX_LESSONS, "distillationScanRecords": operation_knowledge.SCAN_LIMIT},
                "distillation": {"automatic": True, "lessonActions": ["lessons", "learn", "review", "retire"],
                    "sourcePromotion": "Review generic candidates for client capabilities/MCP workflows/tool guidance; source review and regression tests required.",
                    "automaticPolicyPromotion": False, "freeTextAuthority": "agent_reported; evidence references do not prove the proposed interpretation"},
                "compatibleContextObserved": self.context is not None, "lastError": self.last_error,
                "dataPolicy": "typed receipts and bounded redacted agent-reported operating lessons; no screenshots/NBT/full responses/credentials/plans",
                "pathConfiguration": "MCB_KNOWLEDGE_PATH; default OS user data directory"}
        finally:
            db.close()
