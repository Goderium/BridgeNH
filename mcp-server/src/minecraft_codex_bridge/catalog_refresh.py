"""Refresh only bundled schema declarations; executable backend changes require restart."""
from __future__ import annotations

import ast
from contextlib import asynccontextmanager
from dataclasses import replace
import hashlib
import json
import logging
import os
from pathlib import Path
from weakref import WeakKeyDictionary
from copy import deepcopy

import anyio
import jsonschema
from mcp.server.lowlevel import NotificationOptions, Server

from .tools import TOOL_BY_NAME, TOOLS
from . import tools as bundled_tools
from .validation import tool_validator
from .catalog.sources import DECLARATION_FILES


MAX_SOURCE_BYTES = 262_144
MAX_CATALOG_BYTES = 2_097_152
MAX_DECLARATION_STEPS = 100_000
MAX_DECLARATION_COLLECTION = 8192
MAX_DECLARATION_NODES = 32_768
MAX_DECLARATION_DEPTH = 64
MAX_BACKEND_FILES = 128
MAX_BACKEND_BYTES = 1_048_576
MAX_TOTAL_BACKEND_BYTES = 8_388_608


def _signature(path: Path) -> tuple[int, int, int, int]:
    state = path.stat()
    return state.st_dev, state.st_ino, state.st_size, state.st_mtime_ns


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _bounded_read(path: Path, maximum: int) -> bytes:
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("schema_declaration_budget")
    return data


def declaration_source(paths=None) -> bytes:
    """Read the fixed declaration bundle for interpretation, never execute source.

    Internal imports only connect declarations already included in dependency
    order. Remove those import statements so the existing interpreter sees the
    same flat declaration scope; external builders remain fixed loaded objects.
    """
    root = Path(__file__).parent
    paths = paths if paths is not None else [root / name for name in DECLARATION_FILES]
    chunks, total = [], 0
    modules = {Path(name).stem for name in DECLARATION_FILES if name.startswith("catalog/")}
    for path in paths:
        source = _bounded_read(path, MAX_SOURCE_BYTES)
        total += len(source)
        if total > MAX_SOURCE_BYTES:
            raise ValueError("source_size")
        lines = source.decode("utf-8-sig").splitlines(keepends=True)
        for node in ast.parse(source).body:
            if isinstance(node, ast.ImportFrom) and node.level == 1 and (
                    node.module in modules or node.module is not None and node.module.startswith("catalog.")):
                for index in range(node.lineno - 1, node.end_lineno):
                    lines[index] = "\n"
        chunks.append("".join(lines).encode("utf-8"))
    return b"\n".join(chunks)


def _catalog_fingerprint(tools) -> str:
    data = [{"name": tool.name, "operation": tool.operation, "schema": tool.schema, "description": tool.description,
        "readOnly": tool.read_only, "takeover": tool.takeover, "idempotent": tool.idempotent} for tool in tools]
    encoded = json.dumps(data, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_CATALOG_BYTES:
        raise ValueError("catalog_size")
    return _digest(encoded)


def _declaration_contract(source: bytes):
    tree = ast.parse(source)
    stack, nodes = [(tree, 0)], 0
    while stack:
        node, depth = stack.pop()
        nodes += 1
        if depth > MAX_DECLARATION_DEPTH or nodes > MAX_DECLARATION_NODES:
            raise ValueError("schema_declaration_budget")
        stack.extend((child, depth + 1) for child in ast.iter_child_nodes(node))
    definitions = [ast.dump(node, include_attributes=False) for node in tree.body
        if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))]
    calls = {ast.dump(node.func, include_attributes=False) for node in ast.walk(tree) if isinstance(node, ast.Call)}
    protected = {node.name for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))}
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            protected.update(alias.asname or alias.name.split(".")[0] for alias in node.names)
    rebound = {node.id for node in ast.walk(tree) if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del))}
    if protected & rebound:
        raise ValueError("schema_builder_code_changed")
    # Imported/helper execution stays fixed. This is a local deployment contract,
    # not a Python sandbox for arbitrary caller-supplied source.
    return definitions, calls


class _Declarations:
    """Interpret data declarations without exec, eval, imports or arbitrary calls."""
    def __init__(self):
        self.steps = 0
        self.values = {name: deepcopy(value) for name, value in vars(bundled_tools).items()
            if name.isupper() and not callable(value)}
        self.builders = {name: getattr(bundled_tools, name) for name in
            ("obj", "integer", "decimal_quantity", "named_actions", "policy_schema", "ToolSpec")}
        self.builders.update(replace=replace, frozenset=frozenset, list=list, tuple=tuple, next=next)

    def tick(self):
        self.steps += 1
        if self.steps > MAX_DECLARATION_STEPS:
            raise ValueError("schema_declaration_budget")

    def iterable(self, value):
        if type(value) not in (list, tuple, set, frozenset, dict) or len(value) > MAX_DECLARATION_COLLECTION:
            raise ValueError("schema_declaration_expression")
        return value

    def assign(self, target, value, scope):
        self.tick()
        if isinstance(target, ast.Name) and target.id not in self.builders:
            scope[target.id] = value
        elif isinstance(target, (ast.Tuple, ast.List)):
            values = self.iterable(value)
            if len(values) != len(target.elts):
                raise ValueError("schema_declaration_expression")
            for child, part in zip(target.elts, values):
                self.assign(child, part, scope)
        elif isinstance(target, ast.Subscript):
            container, key = self.expression(target.value, scope), self.expression(target.slice, scope)
            if type(container) is not dict or type(key) not in (str, int):
                raise ValueError("schema_declaration_expression")
            container[key] = value
        else:
            raise ValueError("schema_declaration_expression")

    def comprehension(self, node, scope):
        result = []
        def expand(index, local):
            self.tick()
            if index == len(node.generators):
                result.append((self.expression(node.key, local), self.expression(node.value, local))
                    if isinstance(node, ast.DictComp) else self.expression(node.elt, local))
                if len(result) > MAX_DECLARATION_COLLECTION:
                    raise ValueError("schema_declaration_budget")
                return
            generator = node.generators[index]
            if generator.is_async:
                raise ValueError("schema_declaration_expression")
            for value in self.iterable(self.expression(generator.iter, local)):
                following = dict(local)
                self.assign(generator.target, value, following)
                if all(self.expression(condition, following) for condition in generator.ifs):
                    expand(index + 1, following)
        expand(0, dict(scope))
        return dict(result) if isinstance(node, ast.DictComp) else iter(result) if isinstance(node, ast.GeneratorExp) else result

    def expression(self, node, scope):
        self.tick()
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, ast.Name):
            if node.id not in scope:
                raise ValueError("schema_declaration_expression")
            return scope[node.id]
        if isinstance(node, (ast.List, ast.Tuple, ast.Set)):
            entries = []
            for child in node.elts:
                if isinstance(child, ast.Starred):
                    entries.extend(self.iterable(self.expression(child.value, scope)))
                else:
                    entries.append(self.expression(child, scope))
                if len(entries) > MAX_DECLARATION_COLLECTION:
                    raise ValueError("schema_declaration_budget")
            return tuple(entries) if isinstance(node, ast.Tuple) else set(entries) if isinstance(node, ast.Set) else entries
        if isinstance(node, ast.Dict):
            entries = {}
            for key, value in zip(node.keys, node.values):
                following = self.expression(value, scope)
                if key is None:
                    if type(following) is not dict:
                        raise ValueError("schema_declaration_expression")
                    entries.update(self.iterable(following))
                else:
                    entries[self.expression(key, scope)] = following
                if len(entries) > MAX_DECLARATION_COLLECTION:
                    raise ValueError("schema_declaration_budget")
            return entries
        if isinstance(node, (ast.ListComp, ast.DictComp, ast.GeneratorExp)):
            return self.comprehension(node, scope)
        if isinstance(node, ast.Subscript):
            value, key = self.expression(node.value, scope), self.expression(node.slice, scope)
            if type(value) not in (dict, list, tuple) or type(key) not in (str, int):
                raise ValueError("schema_declaration_expression")
            return value[key]
        if isinstance(node, ast.Attribute):
            value = self.expression(node.value, scope)
            if type(value) is not bundled_tools.ToolSpec or node.attr not in {
                    "name", "operation", "description", "schema", "read_only", "takeover", "idempotent"}:
                raise ValueError("schema_declaration_expression")
            return getattr(value, node.attr)
        if isinstance(node, ast.IfExp):
            return self.expression(node.body if self.expression(node.test, scope) else node.orelse, scope)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            value = self.expression(node.operand, scope)
            if type(value) not in (int, float):
                raise ValueError("schema_declaration_expression")
            return -value
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
            left,right=self.expression(node.left,scope),self.expression(node.right,scope)
            if type(left) is not dict or type(right) is not dict:
                raise ValueError("schema_declaration_expression")
            value={**left,**right}
            if len(value)>MAX_DECLARATION_COLLECTION:
                raise ValueError("schema_declaration_budget")
            return value
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            left, right = self.expression(node.left, scope), self.expression(node.right, scope)
            if type(left) is not type(right) or type(left) not in (str, list, tuple, int, float):
                raise ValueError("schema_declaration_expression")
            value = left + right
            if isinstance(value, (str, list, tuple)) and len(value) > MAX_DECLARATION_COLLECTION:
                raise ValueError("schema_declaration_budget")
            return value
        if isinstance(node, ast.BoolOp) and isinstance(node.op, ast.And):
            value = None
            for part in node.values:
                value = self.expression(part, scope)
                if not value:
                    break
            return value
        if isinstance(node, ast.Compare):
            left = self.expression(node.left, scope)
            for operation, part in zip(node.ops, node.comparators):
                right = self.expression(part, scope)
                if isinstance(operation, ast.Eq):
                    matches = left == right
                elif isinstance(operation, ast.NotEq):
                    matches = left != right
                elif isinstance(operation, ast.In):
                    matches = left in self.iterable(right)
                elif isinstance(operation, ast.NotIn):
                    matches = left not in self.iterable(right)
                elif isinstance(operation, ast.IsNot):
                    matches = left is not right
                else:
                    raise ValueError("schema_declaration_expression")
                if not matches:
                    return False
                left = right
            return True
        if isinstance(node, ast.Call):
            args = [self.expression(part, scope) for part in node.args]
            kwargs = {part.arg: self.expression(part.value, scope) for part in node.keywords if part.arg is not None}
            if len(kwargs) != len(node.keywords):
                raise ValueError("schema_declaration_expression")
            if isinstance(node.func, ast.Name) and node.func.id in self.builders:
                if node.func.id == "replace" and (not args or type(args[0]) is not bundled_tools.ToolSpec):
                    raise ValueError("schema_declaration_expression")
                return self.builders[node.func.id](*args, **kwargs)
            if isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name):
                value = self.expression(node.func.value, scope)
                key, method = node.func.value.id, node.func.attr
                if (key, method) in {("TOOLS", "append"), ("TOOLS", "extend"),
                        ("NEI_DETAIL_BRANCHES", "append"), ("tooltip_selectors", "append")} and type(value) is list:
                    if len(value) >= MAX_DECLARATION_COLLECTION or kwargs or len(args) != 1:
                        raise ValueError("schema_declaration_budget")
                    if method == "append":
                        value.append(args[0])
                    else:
                        value.extend(self.iterable(args[0]))
                    return None
                if (key, method) == ("detail_branch", "update") and type(value) is dict and not kwargs and len(args) == 1 and type(args[0]) is dict:
                    value.update(self.iterable(args[0]))
                    return None
            raise ValueError("schema_declaration_expression")
        raise ValueError("schema_declaration_expression")

    def statements(self, body, scope):
        for node in body:
            self.tick()
            if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef)):
                continue  # Their unchanged definitions were checked; no code is loaded.
            if isinstance(node, ast.Assign):
                value = self.expression(node.value, scope)
                for target in node.targets:
                    self.assign(target, value, scope)
            elif isinstance(node, ast.Expr):
                self.expression(node.value, scope)
            elif isinstance(node, ast.If):
                self.statements(node.body if self.expression(node.test, scope) else node.orelse, scope)
            elif isinstance(node, ast.For) and not node.orelse:
                for value in self.iterable(self.expression(node.iter, scope)):
                    self.assign(node.target, value, scope)
                    self.statements(node.body, scope)
            else:
                raise ValueError("schema_declaration_expression")

    def read(self, source):
        self.statements(ast.parse(source).body, self.values)
        return self.values["TOOLS"]


class ToolCatalogRefresh:
    """One service's fixed local catalog, with no request-selectable file or code path."""
    def __init__(self, *, source_path: Path | None = None, backend_paths: list[Path] | None = None, poll_interval: float = 1.0):
        self.source_path = source_path or Path(__file__).with_name("tools.py")
        self._source_paths = [source_path] if source_path is not None else [Path(__file__).parent / name for name in DECLARATION_FILES]
        self._bundled = source_path is None
        self._discover_backend = backend_paths is None
        self.poll_interval = poll_interval
        self._lock = anyio.Lock()
        self._sessions = WeakKeyDictionary()
        source = self._read_source()
        self._source_signature = self._signatures()
        self._source_digest = _digest(source)
        self._definitions, self._allowed_calls = _declaration_contract(source)
        files = backend_paths if backend_paths is not None else self._backend_paths()
        if len(files) > MAX_BACKEND_FILES:
            raise ValueError("schema_declaration_budget")
        self._backend = {}
        backend_bytes = 0
        for path in files:
            if path not in self._source_paths:
                data = _bounded_read(path, MAX_BACKEND_BYTES)
                backend_bytes += len(data)
                if backend_bytes > MAX_TOTAL_BACKEND_BYTES:
                    raise ValueError("schema_declaration_budget")
                self._backend[path] = _signature(path), _digest(data)
        self._backend_changed = False
        self._original = tuple(TOOLS)
        self.fingerprint = _catalog_fingerprint(TOOLS)
        self.generation = 0
        self.source_status = "current"
        self.reason = None
        self.notifications_sent = 0
        self.notification_status = "not_needed"

    def _read_source(self):
        return declaration_source(self._source_paths) if self._bundled else _bounded_read(self.source_path, MAX_SOURCE_BYTES)

    def _signatures(self):
        return tuple(_signature(path) for path in self._source_paths)

    def _backend_paths(self):
        # Include nested packages and detect additions/removals, not just edits
        # to files that happened to exist when this process started.
        return sorted(path for path in Path(__file__).parent.rglob("*.py") if path not in self._source_paths)

    def remember_session(self, session) -> None:
        if session not in self._sessions:
            self._sessions[session] = self.generation

    def diagnostics(self) -> dict:
        return {"pid": os.getpid(), "schemaFingerprint": self.fingerprint, "catalogGeneration": self.generation,
            "sourceStatus": self.source_status, "reason": self.reason, "reloadMode": "schema_and_description_only",
            "toolsListChangedSupported": True, "notificationsSent": self.notifications_sent,
            "notificationStatus": self.notification_status, "clientCatalogRefresh": "not_observable",
            "limits": {"sourceBytes": MAX_SOURCE_BYTES, "catalogBytes": MAX_CATALOG_BYTES,
                "astNodes": MAX_DECLARATION_NODES, "astDepth": MAX_DECLARATION_DEPTH,
                "declarationSteps": MAX_DECLARATION_STEPS, "collectionEntries": MAX_DECLARATION_COLLECTION,
                "backendFiles": MAX_BACKEND_FILES, "backendFileBytes": MAX_BACKEND_BYTES,
                "totalBackendBytes": MAX_TOTAL_BACKEND_BYTES}}

    def _backend_unchanged(self) -> bool:
        if self._backend_changed:
            return False
        if self._discover_backend and set(self._backend_paths()) != self._backend.keys():
            self._backend_changed = True
            return False
        for path, (signature, digest) in list(self._backend.items()):
            try:
                current = _signature(path)
            except OSError:
                self._backend_changed = True
                return False
            if current != signature:
                if current[2] > MAX_BACKEND_BYTES or _digest(_bounded_read(path, MAX_BACKEND_BYTES)) != digest:
                    self._backend_changed = True
                    return False
                self._backend[path] = current, digest
        return True

    def _load_declarations(self, source: bytes):
        definitions, calls = _declaration_contract(source)
        if definitions != self._definitions or not calls.issubset(self._allowed_calls):
            raise ValueError("schema_builder_code_changed")
        declarations = _Declarations()
        following = declarations.read(source)
        # This fixed trusted builder uses its loaded module's SCREEN_ID global.
        # Do not silently publish a mixture of updated data and old helper globals.
        if any(declarations.values[name] != getattr(bundled_tools, name)
                for name in ("SCREEN_ID", "NONDESTRUCTIVE_OPERATIONS")):
            raise ValueError("schema_builder_code_changed")
        if len(following) != len(self._original):
            raise ValueError("tool_topology_changed")
        approved = []
        for original, candidate in zip(self._original, following):
            if any(getattr(original, key) != getattr(candidate, key) for key in ("name", "operation", "read_only", "takeover", "idempotent")):
                raise ValueError("tool_dispatch_or_permissions_changed")
            if type(candidate.description) is not str or not isinstance(candidate.schema, dict):
                raise ValueError("invalid_catalog")
            jsonschema.Draft202012Validator.check_schema(candidate.schema)
            approved.append(replace(original, description=candidate.description, schema=candidate.schema))
        fingerprint = _catalog_fingerprint(approved)
        return approved, fingerprint

    async def refresh(self, workflow_lock=None) -> bool:
        async with self._lock:
            try:
                if not self._backend_unchanged():
                    self.source_status, self.reason = "restart_required", "backend_code_changed"
                    return False
                signature = self._signatures()
                if signature == self._source_signature:
                    return False
                self._source_signature = signature
                if sum(item[2] for item in signature) > MAX_SOURCE_BYTES:
                    raise ValueError("source_size")
                source = self._read_source()
                if len(source) > MAX_SOURCE_BYTES:
                    raise ValueError("source_size")
                digest = _digest(source)
                if digest == self._source_digest:
                    self.source_status, self.reason = "current", None
                    return False
                approved, fingerprint = self._load_declarations(source)
                if workflow_lock is None:
                    self._publish(approved, fingerprint, digest)
                else:
                    async with workflow_lock:
                        self._publish(approved, fingerprint, digest)
            except Exception as error:
                reason = str(error) if isinstance(error, ValueError) and str(error) in {
                    "source_size", "catalog_size", "schema_builder_code_changed", "tool_topology_changed",
                    "tool_dispatch_or_permissions_changed", "invalid_catalog", "schema_declaration_budget",
                    "schema_declaration_expression"} else "schema_source_unavailable_or_invalid"
                self.source_status, self.reason = "restart_required", reason
                logging.getLogger(__name__).warning("Bundled MCP catalog update refused; service restart required (details withheld).")
                return False
            await self._notify()
            return True

    def _publish(self, approved, fingerprint: str, source_digest: str) -> None:
        # Existing imports keep these shared containers; no await separates publication and invalidation.
        TOOLS[:] = approved
        TOOL_BY_NAME.clear()
        TOOL_BY_NAME.update({tool.name: tool for tool in approved})
        tool_validator.cache_clear()
        self._source_digest = source_digest
        self.source_status, self.reason = "current", None
        if fingerprint != self.fingerprint:
            self.fingerprint = fingerprint
            self.generation += 1

    async def _notify(self) -> None:
        sessions = list(self._sessions.items())
        self.notification_status = "not_needed" if sessions else "no_connected_sessions"
        for session, generation in sessions:
            if generation == self.generation:
                continue
            try:
                with anyio.fail_after(2):
                    await session.send_tool_list_changed()
                self._sessions[session] = self.generation
                self.notifications_sent += 1
                self.notification_status = "sent"
            except Exception:
                self.notification_status = "failed"

    async def watch(self, workflow_lock) -> None:
        while True:
            await anyio.sleep(self.poll_interval)
            await self.refresh(workflow_lock)

    @asynccontextmanager
    async def lifespan(self, server):
        async with anyio.create_task_group() as group:
            group.start_soon(self.watch, server.catalog_workflow_lock)
            try:
                yield {"toolCatalog": self}
            finally:
                group.cancel_scope.cancel()


class CatalogServer(Server):
    def create_initialization_options(self, notification_options=None, experimental_capabilities=None):
        options = notification_options or NotificationOptions()
        options.tools_changed = True
        return super().create_initialization_options(options, experimental_capabilities)
