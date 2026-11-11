"""Dependency, dispatch and nested-source reload boundaries after classification."""
import ast
from pathlib import Path
import subprocess
import sys

import pytest

from minecraft_codex_bridge import catalog_refresh, server
from minecraft_codex_bridge.application.adapter import Adapter
from minecraft_codex_bridge.application.dispatch import HANDLERS, validate_handlers
from minecraft_codex_bridge.catalog_refresh import ToolCatalogRefresh, declaration_source
from minecraft_codex_bridge.catalog.sources import DECLARATION_FILES
from minecraft_codex_bridge.tools import TOOLS, TOOL_BY_NAME
from minecraft_codex_bridge.validation import tool_validator
from test_backend_supervisor import copy_package


@pytest.fixture
def isolated_catalog(tmp_path, monkeypatch):
    root = copy_package(tmp_path)
    monkeypatch.setattr(catalog_refresh, "__file__", str(root / "catalog_refresh.py"))
    original = list(TOOLS)
    try:
        yield root, ToolCatalogRefresh()
    finally:
        TOOLS[:] = original
        TOOL_BY_NAME.clear()
        TOOL_BY_NAME.update({tool.name: tool for tool in original})
        tool_validator.cache_clear()


async def test_nested_schema_publication_preserves_order_permissions_and_shared_containers(isolated_catalog):
    root, catalog = isolated_catalog
    previous = [(t.name, t.operation, t.read_only, t.takeover, t.idempotent, t.destructive) for t in TOOLS]
    shared_tools, shared_index = TOOLS, TOOL_BY_NAME
    path = root / "catalog/player.py"
    original = path.read_text(encoding="utf-8")
    changed = original.replace('"Uses the saved mcb_policy movement;', '"Updated description. Uses the saved mcb_policy movement;', 1)
    assert changed != original
    path.write_text(changed, encoding="utf-8")
    assert await catalog.refresh()
    assert catalog.generation == 1
    assert shared_tools is TOOLS and shared_index is TOOL_BY_NAME
    assert previous == [(t.name, t.operation, t.read_only, t.takeover, t.idempotent, t.destructive) for t in TOOLS]
    assert TOOL_BY_NAME["mcb_navigate"].description.startswith("Updated description.")


@pytest.mark.parametrize("change", ["edit", "add", "delete"])
async def test_nested_executable_changes_require_reload_without_publishing(isolated_catalog, change):
    root, catalog = isolated_catalog
    before = catalog.fingerprint
    path = root / "application/control.py"
    if change == "edit":
        path.write_text(path.read_text(encoding="utf-8") + "\n# new code revision\n", encoding="utf-8")
    elif change == "delete":
        path.unlink()
    else:
        (root / "application/new_handler.py").write_text("# new module\n", encoding="utf-8")
    assert not await catalog.refresh()
    assert catalog.reason == "backend_code_changed"
    assert catalog.fingerprint == before and catalog.generation == 0


async def test_loaded_permission_helper_constants_cannot_be_redeclared_as_data(isolated_catalog):
    root, catalog = isolated_catalog
    path = root / "catalog/common.py"
    original = path.read_text(encoding="utf-8")
    path.write_text(original.replace('"gui.pointer", "gui.open_inventory"', '"gui.open_inventory"', 1), encoding="utf-8")
    assert not await catalog.refresh()
    assert catalog.reason == "schema_builder_code_changed"


def test_declaration_bundle_matches_loaded_catalog_and_every_composite_has_one_route():
    catalog = ToolCatalogRefresh()
    _, fingerprint = catalog._load_declarations(declaration_source())
    assert fingerprint == catalog.fingerprint
    assert len(TOOLS) == len(TOOL_BY_NAME)
    validate_handlers()
    assert server.Adapter is Adapter
    assert {name for name, spec in TOOL_BY_NAME.items() if spec.operation is None} <= HANDLERS.keys()
    root = Path(catalog_refresh.__file__).parent
    for name in DECLARATION_FILES:
        assert (root / name).is_file()
    # Code in the application layer must not depend on the transport entrypoint.
    for path in (root / "application").rglob("*.py"):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.ImportFrom):
                assert node.module not in {"server", "frontend", "backend_worker", "backend_supervisor"}


def test_frozen_frontend_does_not_import_application_or_game_code():
    script = '''
import sys
import minecraft_codex_bridge.frontend
assert not any(name.startswith("minecraft_codex_bridge.application") for name in sys.modules)
assert "minecraft_codex_bridge.tools" not in sys.modules
assert "minecraft_codex_bridge.bridge" not in sys.modules
'''
    subprocess.run([sys.executable, "-c", script], check=True, capture_output=True, timeout=15)
