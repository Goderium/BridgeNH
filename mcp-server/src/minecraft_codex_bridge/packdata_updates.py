"""Transactional, per-instance maintenance and semantic change history for offline PACKDATA."""
from __future__ import annotations

from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
import hashlib
from itertools import groupby
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import time
from uuid import uuid4

from . import packdata, packdata_inputs
from .bridge import BridgeError


FILES = {"recex": {"registry_recipe": "registry-recipes.jsonl"},
         "quests": {"chapter": "questlines.json", "quest": "quests.jsonl"},
         "guide": {"guide": "guide.jsonl"},
         "nei": {"handler": "handlers.json", "recipe": "recipes.jsonl", "item": "items.jsonl",
                 "structure": "structures.jsonl", "carving": "carving.jsonl", "block": "blocks.jsonl",
                 "tooltip": "tooltips.jsonl"}}
REQUIRED = {"registry_recipe", "chapter", "quest", "guide", "handler", "recipe"}
HINTS = {"registry_recipe": "Review the independent registry source and its affected machine recipes.",
         "quest": "Review changed objectives, prerequisites and rewards.",
         "chapter": "Refresh chapter membership and order.", "guide": "Refresh referenced pages and item links.",
         "recipe": "Recheck affected recipe choices, quantities and machine requirements.",
         "handler": "Recheck handler coverage and machine eligibility.", "item": "Refresh names and ore alternatives.",
         "structure": "Recheck controller tier and structure geometry.", "carving": "Refresh chisel variants.",
         "block": "Refresh block identity and display data.", "tooltip": "Refresh the exact language and modifier view."}


def _json(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":"))


def _digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _has(db, schema, table):
    return db.execute(f"SELECT 1 FROM {schema}.sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()


def _stamp(db, schema, dataset):
    if not _has(db, schema, "sources"):
        return {}
    row = db.execute(f"SELECT info FROM {schema}.sources WHERE dataset=?", (dataset,)).fetchone()
    return json.loads(row[0]) if row else {}


def _audit_schema(db):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS pack_updates(
            seq INTEGER PRIMARY KEY, update_id TEXT NOT NULL UNIQUE, report TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS pack_changes(
            seq INTEGER PRIMARY KEY, update_id TEXT NOT NULL, dataset TEXT NOT NULL, kind TEXT NOT NULL,
            change TEXT NOT NULL, identity TEXT NOT NULL, before_row TEXT, after_row TEXT, fields TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS pack_change_page ON pack_changes(update_id, dataset, kind, change, seq);
        CREATE INDEX IF NOT EXISTS pack_change_run ON pack_changes(update_id, seq);
    """)


def _source(instance, dataset, directory, old):
    directory = Path(directory).resolve(strict=True)
    manifest = directory / "meta.json"
    initial = packdata._file_state(manifest)
    metadata = packdata._read(manifest)
    info = metadata.get(dataset)
    if not isinstance(info, dict) or type(info.get("format")) is not int or info["format"] != 1:
        raise ValueError(f"{dataset}: expected version 1 metadata")
    provenance = {}
    provenance_path = directory / f"{dataset}-provenance.json"
    proof_path = directory / "export-inputs.json"
    states = {manifest: initial, proof_path: packdata._file_state(proof_path) if proof_path.exists() else None,
              provenance_path: packdata._file_state(provenance_path) if provenance_path.exists() else None}
    signature = packdata_inputs.signature_summary(packdata_inputs.read_export_inputs(directory, dataset, instance))
    if provenance_path.exists():
        supplied = packdata._read(provenance_path)
        if supplied.get("instance") and supplied["instance"] != instance.name:
            raise ValueError(f"{dataset}: provenance identifies another instance")
        provenance = {key: supplied[key] for key in ("source", "ref", "pack", "nei", "gregtech", "version",
                                                     "exporter", "entrypoint", "jarSha256", "providerSha256", "exporterSha256",
                                                     "runnerSha256") if key in supplied}
    hashes = {}
    changed = []
    for kind, filename in FILES[dataset].items():
        path = directory / filename
        if path.is_file():
            states[path] = packdata._file_state(path)
            with path.open("rb") as stream:
                hashes[filename] = hashlib.file_digest(stream, "sha256").hexdigest()
        elif kind in REQUIRED:
            raise ValueError(f"{dataset}: required export missing: {filename}")
        else:
            states[path] = None
        if hashes.get(filename) != old.get("sha256", {}).get(filename):
            changed.append(kind)
    effective_info = {**info, "tooltipsExport": metadata["tooltips"]} if dataset == "nei" and "tooltips" in metadata else info
    if dataset == "nei" and "tooltips.jsonl" in hashes and "tooltip" not in changed and (
            old.get("export", {}).get("tooltipsExport") != effective_info.get("tooltipsExport")):
        changed.append("tooltip")
    needs = (not old or bool(changed) or old.get("export") != effective_info or old.get("itemLinksVersion") != packdata.ITEM_LINKS_VERSION[dataset]
             or old.get("updateProvenance", {}) != provenance or old.get("inputSignature") != signature)
    return directory, metadata, states, hashes, provenance, {"needsUpdate": needs, "changedInputKinds": changed,
        "missingOptional": [kind for kind, file in FILES[dataset].items() if file not in hashes],
        "hints": [HINTS[kind] for kind in changed]}


def _unchanged(states):
    for path, initial in states.items():
        if (packdata._file_state(path) if path.exists() else None) != initial:
            raise ValueError(f"Source changed during update: {path.name}")


def _normalize(kind, row, grids):
    if kind == "recipe":
        # The NEI furnace page cycles one illustrative fuel every 48 ticks. Conversely
        # its dedicated fuel page fixes the fuel and cycles the illustrated smelting pair.
        # The source row in entries and its raw hash keep the complete observed display.
        if row["h"] in {"codechicken.nei.recipe.FurnaceRecipeHandler",
                        "ganymedes01.etfuturum.compat.nei.BlastFurnaceRecipeHandler",
                        "ganymedes01.etfuturum.compat.nei.SmokerRecipeHandler"}:
            row.pop("other", None)
        elif row["h"] == "codechicken.nei.recipe.FuelRecipeHandler":
            row.pop("in", None)
            row.pop("out", None)
        for key in ("in", "out", "other", "fin", "fout"):
            for slot in row.get(key, []):
                if "any" in slot:
                    slot["any"].sort(key=_json)
        # Grid coordinates retain shaped positions. A shapeless crafting input is a multiset.
        if row["h"] in grids and "in" in row:
            row["in"].sort(key=_json)
    elif kind == "handler":
        row.pop("ms", None)
        if "machines" in row:
            row["machines"].sort(key=_json)
    elif kind == "item" and "ores" in row:
        row["ores"].sort()
    elif kind == "quest" and "pre" in row:
        row["pre"].sort()
    elif kind == "structure" and "tiers" in row:
        row["tiers"].sort()
    return row


def _recipe_family(row, digest):
    # Match substitutions only when the handler and products identify a single residual pair.
    def identities(slots):
        return [{k: slot[k] for k in ("id", "meta", "f", "ore", "any") if k in slot} for slot in slots]
    output = [identities(row.get(k, [])) for k in ("out", "fout")]
    if row["h"] == "codechicken.nei.recipe.FuelRecipeHandler":
        return _json([row["h"], identities(row.get("other", []))])
    if not any(output):
        return _json([row["h"], "no_output", digest])
    return _json([row["h"], *output])


def _capture(db, schema, side, kinds):
    if not kinds:
        return
    grids = {ref for ref, payload in db.execute(f"SELECT ref,payload FROM {schema}.entries WHERE kind='handler'")
             if json.loads(payload).get("grid") is True} if "recipe" in kinds else set()
    def records():
        if "chapter" in kinds:
            order = [r[0] for r in db.execute(f"SELECT ref FROM {schema}.entries WHERE kind='chapter' ORDER BY id")]
            content = _json({"order": order})
            yield side, "chapter", "$chapter-order", _digest(content), "$chapter-order", content
        for kind in sorted(kinds):
            for ref, payload in db.execute(f"SELECT ref,payload FROM {schema}.entries WHERE kind=?", (kind,)):
                normalized = _normalize(kind, json.loads(payload), grids)
                content = _json(normalized)
                digest = _digest(content)
                if kind == "recipe":
                    family = _recipe_family(normalized, digest)
                elif kind == "registry_recipe":
                    from .recex_packdata import registry_edges
                    products = sorted((key, meta) for role, key, meta in registry_edges(normalized) if role == "output")
                    family = _json([normalized["providerType"], normalized["machine"], products or digest])
                else:
                    family = ref
                yield side, kind, family, digest, ref, content
    db.executemany("""INSERT INTO comparison(side,kind,family,digest,ref,content,n) VALUES(?,?,?,?,?,?,1)
        ON CONFLICT(side,kind,family,digest) DO UPDATE SET n=n+1""", records())


def _changes(db):
    # Cancel equal variants including their multiplicity before pairing replacements.
    sql = """SELECT kind,family,digest,MAX(CASE WHEN side=0 THEN ref END), MAX(CASE WHEN side=1 THEN ref END),
             MIN(content),SUM(CASE side WHEN 1 THEN n ELSE -n END) AS net
             FROM comparison GROUP BY kind,family,digest HAVING net!=0 ORDER BY kind,family,digest"""
    for (kind, family), rows in groupby(db.execute(sql), key=lambda row: (row[0], row[1])):
        old, new = [], []
        for _, _, digest, old_ref, new_ref, content, net in rows:
            side = {"ref": old_ref if net < 0 else new_ref, "sha256": digest, "count": abs(net), "record": json.loads(content)}
            (old if net < 0 else new).append(side)
        if len(old) == len(new) == 1:
            before, after = old[0], new[0]
            count = min(before["count"], after["count"])
            fields = sorted(k for k in before["record"].keys() | after["record"].keys()
                            if k not in before["record"] or k not in after["record"] or
                            _json(before["record"][k]) != _json(after["record"][k]))
            yield kind, family, "modified", {**before, "count": count}, {**after, "count": count}, fields
            before["count"] -= count
            after["count"] -= count
        for entry in old:
            if entry["count"]:
                yield kind, family, "removed", entry, None, []
        for entry in new:
            if entry["count"]:
                yield kind, family, "added", None, entry, []


def _coverage(stamp, dataset):
    files = stamp.get("sha256", {})
    return {kind: {"available": file in files, "rows": stamp.get("counts", {}).get(kind) if file in files else None}
            for kind, file in FILES[dataset].items()}


def _source_summary(stamp):
    if not stamp:
        return None
    info = stamp["export"]
    return {"revision": stamp["revision"], "sha256": stamp["sha256"], "counts": stamp["counts"],
            "inputSignature": stamp.get("inputSignature"),
            "provider": {key: info[key] for key in ("source", "pack", "lang", "format", "exporter", "provider",
                                                    "generatedAt", "translated", "books") if key in info},
            "handlerReadMethods": stamp.get("handlerReadMethods", {}),
            "tooltipCoverage": stamp.get("tooltipCoverage"),
            "provenance": stamp.get("updateProvenance", {})}


def _tooltip_coverage(db, metadata, stamp):
    if "tooltips.jsonl" not in stamp["sha256"]:
        return {"available":False}
    info = metadata.get("tooltips", {})
    modes = {}
    for lang, mode, count in db.execute("""SELECT lang,json_extract(payload,'$.mode'),count(*) FROM entries
            WHERE kind='tooltip' GROUP BY lang,json_extract(payload,'$.mode')"""):
        modes.setdefault(lang, {})[mode] = count
    sampled = info.get("lastSample", {})
    missing = Counter(str(row.get("reason", "unknown")) for row in sampled.get("missing", []))
    return {"available":True, "scope":info.get("scope", "supplied_export"), "languageModes":modes,
            "source":sampled.get("source", info.get("source")), "modifierSources":sampled.get("modifierSources", {}),
            "missingObservations":dict(missing)}


def _record(db, update_id, dataset, kind, identity, change, before, after, fields):
    db.execute("""INSERT INTO pack_changes(update_id,dataset,kind,change,identity,before_row,after_row,fields)
                  VALUES(?,?,?,?,?,?,?,?)""", (update_id, dataset, kind, change, identity, _json(before) if before
                  is not None else None, _json(after) if after is not None else None, _json(fields)))


def _process(db, baseline, instance, dataset, directory, update_id, mode, watched, preserve_coverage):
    old = _stamp(db, baseline, dataset)
    if directory is None:
        if dataset != "guide":
            raise ValueError("Only an observed absence of all installed GuideNH packs can retire a whole dataset.")
        report = {"baseline": not bool(old), "needsUpdate": bool(old), "missingOptional": ["guide"],
                  "before": _source_summary(old), "after": None, "changedInputKinds": ["guide"] if old else [],
                  "hints": ["Install or supply a GuideNH pack to restore guide coverage."]}
        if old and mode != "check":
            db.execute("DELETE FROM entries WHERE kind='guide'")
            db.execute("DELETE FROM sources WHERE dataset='guide'")
            packdata_inputs.retain(db, "guide", None)
            _record(db, update_id, dataset, "coverage", "guide", "modified", _coverage(old, dataset)["guide"],
                    {"available": False, "rows": None}, ["available", "rows"])
            report["changes"] = {"coverage": {"modified": 1}}
        return report
    directory, metadata, states, hashes, provenance, check = _source(instance, dataset, directory, old)
    for path, state in states.items():
        if path in watched and watched[path] != state:
            raise ValueError(f"Source changed between datasets: {path.name}")
        watched[path] = state
    report = {**check, "before": _source_summary(old), "baseline": not bool(old)}
    if mode == "check" or not check["needsUpdate"]:
        return report
    comparable = {kind for kind, file in FILES[dataset].items() if file in hashes and file in old.get("sha256", {})}
    retain_views = preserve_coverage and dataset == "nei" and bool(old)
    if retain_views:
        missing = old.get("sha256", {}).keys() - hashes.keys()
        if missing:
            raise ValueError("Native acquisition lost previously covered export files: " + ", ".join(sorted(missing)))
        db.execute("CREATE TEMP TABLE previous_tooltips AS SELECT ref FROM main.entries WHERE kind='tooltip'")
    # Include unchanged handler/recipe pairs when a different file gained coverage, but avoid
    # full recipe-table scans for a tooltip-only update if its inputs and grid interpretation are identical.
    scan = comparable.intersection(check["changedInputKinds"])
    if "handler" in scan and "recipe" in comparable:
        scan.add("recipe")
    db.execute("DELETE FROM comparison")
    _capture(db, baseline, 0, scan)
    stamp = packdata.import_dataset(db, directory, dataset, metadata, instance=instance)
    if retain_views and db.execute("""SELECT 1 FROM previous_tooltips t WHERE NOT EXISTS
            (SELECT 1 FROM entries e WHERE e.kind='tooltip' AND e.ref=t.ref) LIMIT 1""").fetchone():
        raise ValueError("Native acquisition omitted a previously sampled tooltip identity; keep the old catalog and enrich the capture")
    if stamp["sha256"] != hashes:
        raise ValueError(f"{dataset}: import did not consume the same source files")
    stamp["updateProvenance"] = provenance
    if dataset == "nei":
        stamp["tooltipCoverage"] = _tooltip_coverage(db, metadata, stamp)
    db.execute("UPDATE sources SET info=? WHERE dataset=?", (_json(stamp), dataset))
    _capture(db, "main", 1, scan)
    counts = Counter()
    for kind, family, action, before, after, fields in _changes(db):
        _record(db, update_id, dataset, kind, family, action, before, after, fields)
        counts[(kind, action)] += (before or after)["count"]
    prev, current = _coverage(old, dataset), _coverage(stamp, dataset)
    for name in current:
        if prev[name] != current[name]:
            _record(db, update_id, dataset, "coverage", name, "modified", prev[name], current[name], ["available", "rows"])
            counts[("coverage", "modified")] += 1
    if dataset == "nei" and old.get("handlerReadMethods", {}) != stamp.get("handlerReadMethods", {}):
        _record(db, update_id, dataset, "coverage", "handlerReadMethods", "modified",
                old.get("handlerReadMethods", {}), stamp.get("handlerReadMethods", {}), ["handlerReadMethods"])
        counts[("coverage", "modified")] += 1
    old_tooltips = old.get("tooltipCoverage", {"available":False} if "tooltips.jsonl" not in old.get("sha256", {}) else None)
    if dataset == "nei" and old_tooltips != stamp.get("tooltipCoverage"):
        _record(db, update_id, dataset, "coverage", "tooltipModes", "modified", old_tooltips,
                stamp["tooltipCoverage"], ["languageModes", "scope", "source", "modifierSources", "missingObservations"])
        counts[("coverage", "modified")] += 1
    return {**report, "after": _source_summary(stamp), "coverage": current,
            "changes": {k: {op: n for (kind, op), n in counts.items() if kind == k} for k in sorted({k for k, _ in counts})}}


def _open_read(path):
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=10)


def _enable_wal(db):
    # Two first-time writers can both hold a shared rollback-journal lock and request
    # its upgrade. SQLite may then return BUSY immediately despite its busy timeout.
    deadline = time.monotonic() + 60
    while True:
        try:
            db.execute("PRAGMA journal_mode=WAL").fetchone()
            return
        except sqlite3.OperationalError as error:
            if (getattr(error, "sqlite_errorcode", 0) & 255) not in (sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED) or time.monotonic() >= deadline:
                raise
            time.sleep(0.05)


def maintain(instance, sources, *, mode="update", env=None, audit_file=None, preserve_coverage=False):
    """Check source fingerprints, preview semantic changes or atomically publish all selected datasets."""
    started = time.monotonic()
    instance = Path(instance).resolve(strict=True)
    if not (instance / ".minecraft").is_dir() or mode not in {"check", "compare", "update"}:
        raise ValueError("Expected a Prism instance and check, compare or update mode")
    if not sources or sources.keys() - FILES.keys():
        raise ValueError("Select at least one named dataset")
    path = packdata.database(instance, os.environ if env is None else env)
    if path.resolve().is_relative_to(instance):
        raise ValueError("Store the offline catalog outside the game installation")
    if audit_file:
        target = Path(audit_file).resolve()
        if target.is_relative_to(instance) or target in {Path(str(path.resolve()) + suffix) for suffix in ("", "-wal", "-shm", "-journal")}:
            raise ValueError("Select an audit output outside the instance and separate from the database")
        for dataset, source in sources.items():
            if source is not None and target.parent == Path(source).resolve() and target.name in {
                    "meta.json", "export-inputs.json", f"{dataset}-provenance.json", *FILES[dataset].values()}:
                raise ValueError("An audit cannot replace a source export")
    if mode == "update":
        path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path if mode == "update" else "", timeout=60, uri=True)
    with closing(connection) as db, db:
        baseline = "main"
        if mode != "update" and path.is_file():
            db.execute("ATTACH DATABASE ? AS previous", (path.resolve().as_uri() + "?mode=ro",))
            baseline = "previous"
        if mode == "update":
            _enable_wal(db)
        db.execute("PRAGMA foreign_keys=ON")
        packdata._schema(db)
        _audit_schema(db)
        db.execute("""CREATE TEMP TABLE comparison(side INTEGER, kind TEXT, family TEXT, digest TEXT, ref TEXT,
                   content TEXT, n INTEGER, PRIMARY KEY(kind,family,digest,side)) WITHOUT ROWID""")
        db.execute("BEGIN IMMEDIATE" if mode == "update" else "BEGIN")
        update_id = uuid4().hex
        report = {"mode": mode, "updateId": None, "source": "local_pack_export", "liveStateObserved": False,
                  "comparisonPolicy":"Observed export records, preserving multiplicity. Known NEI furnace/fuel display cycles and handler timings excluded.",
                  "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "datasets": {}}
        watched = {}
        for dataset, directory in sorted(sources.items()):
            report["datasets"][dataset] = _process(db, baseline, instance, dataset, directory, update_id, mode, watched, preserve_coverage)
        _unchanged(watched)
        changed = any(row["needsUpdate"] for row in report["datasets"].values())
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        if changed and mode != "check":
            report["updateId"] = update_id
            db.execute("INSERT INTO pack_updates(update_id,report) VALUES(?,?)", (update_id, _json(report)))
        db.commit()
        if audit_file:
            try:
                _export_audit(db, update_id, report, Path(audit_file))
            except OSError as error:
                if mode == "update" and report["updateId"]:
                    raise RuntimeError(f"Catalog committed as {update_id}, but the audit file could not be written: {error}") from error
                raise
        return report


def _export_audit(db, update_id, report, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent, delete=False) as out:
            name = Path(out.name)
            out.write(_json({"type": "run", **report}) + "\n")
            for row in db.execute("SELECT dataset,kind,change,identity,before_row,after_row,fields FROM pack_changes WHERE update_id=? ORDER BY seq", (update_id,)):
                out.write(_json(_change_row(row, True)) + "\n")
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
    finally:
        if name is not None:
            name.unlink(missing_ok=True)


def _change_row(row, full=False):
    dataset, kind, change, key, before, after, fields = row
    def view(text):
        side = json.loads(text) if text else None
        if side and "record" in side and not full:
            record = side.pop("record")
            side["preview"] = _json(record)[:650]
        return side
    return {"dataset": dataset, "kind": kind, "change": change, "identity": key if full or len(key) < 400 else _digest(key),
            "fields": json.loads(fields), "before": view(before), "after": view(after)}


def query_updates(path, args):
    """Read the bound catalog's committed update summary or a bounded page of semantic changes."""
    base = {"source": "local_pack_update_history", "liveStateObserved": False}
    action = args.get("action", "update_status")
    if action not in {"update_status", "changes"}:
        raise ValueError("Expected update_status or changes")
    limit, offset = args.get("limit", 10), args.get("offset", 0)
    if type(limit) is not int or not 1 <= limit <= 50 or type(offset) is not int or offset < 0:
        raise ValueError("Expected limit 1..50 and nonnegative offset")
    empty = {**base, "available": False, "results": [], "nextOffset": None}
    if not Path(path).is_file():
        return empty
    with closing(_open_read(Path(path))) as db:
        db.execute("BEGIN")
        if not _has(db, "main", "pack_updates"):
            return empty
        if action == "update_status":
            rows = db.execute("SELECT report FROM pack_updates ORDER BY seq DESC LIMIT ? OFFSET ?", (limit+1, offset))
            def result(row):
                run = json.loads(row[0])
                return {**run, "datasets": {key: {**{k: d[k] for k in ("baseline", "changedInputKinds", "changes",
                        "missingOptional", "hints") if k in d}, "previousRevision": (d.get("before") or {}).get("revision"),
                        "revision": (d.get("after", d.get("before")) or {}).get("revision"),
                        "provider": (d.get("after", d.get("before")) or {}).get("provenance", {})}
                        for key, d in run["datasets"].items()}}
        else:
            identifier = args.get("updateId")
            if identifier is None:
                row = db.execute("SELECT update_id FROM pack_updates ORDER BY seq DESC LIMIT 1").fetchone()
                if row is None:
                    return empty
                identifier = row[0]
            elif not db.execute("SELECT 1 FROM pack_updates WHERE update_id=?", (identifier,)).fetchone():
                raise BridgeError("PACKDATA_UPDATE_NOT_FOUND", "Unknown update in this instance's catalog.")
            filters, values = ["update_id=?"], [identifier]
            for column in ("dataset", "kind", "change"):
                if args.get(column):
                    filters.append(column + "=?")
                    values.append(args[column])
            sql = """SELECT dataset,kind,change,identity,before_row,after_row,fields FROM pack_changes WHERE """ + " AND ".join(filters)
            rows = db.execute(sql+" ORDER BY seq LIMIT ? OFFSET ?", (*values, limit+1, offset))
            result = _change_row
            base["updateId"] = identifier
        results, size, next_offset = [], 0, None
        for row in rows:
            item = result(row)
            size += len(_json(item).encode())
            if len(results) == limit or results and size > 24000:
                next_offset = offset + len(results)
                break
            results.append(item)
        return {**base, "available": True, "results": results, "nextOffset": next_offset}


def fetch_books(instance, directory, exporter, language):
    """Acquire only installed static books; each invocation uses a fresh private directory."""
    from .quest_packdata import export_quests
    instance = Path(instance).resolve(strict=True)
    game = instance / ".minecraft"
    initial = packdata_inputs.capture_inputs(instance, ["quests", "guide"], quest_language=language)
    exporter = Path(exporter).resolve(strict=True)
    with exporter.open("rb") as stream:
        runner_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    export_quests(game, directory, exporter, language)
    sources = {"quests": directory, "guide": None}
    packs = sorted((game / "config/guidenh").glob("*.zip"))
    if packs:
        subprocess.run([sys.executable, "-I", "-X", "utf8", str(exporter), "guide", "--out", str(directory),
                        *(arg for pack in packs for arg in ("--pack", str(pack)))], check=True, stdout=sys.stderr)
        sources["guide"] = directory
    packdata_inputs.write_export_inputs(directory, initial,
                                        packdata_inputs.capture_inputs(instance, ["quests", "guide"], quest_language=language))
    with exporter.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != runner_hash:
            raise ValueError("Book exporter changed during collection")
    for dataset, selected in sources.items():
        if selected is not None:
            packdata_inputs._write_json(Path(directory)/f"{dataset}-provenance.json",
                {"source":"Alu PACKDATA compatible local exporter", "exporter":exporter.name, "exporterSha256":runner_hash,
                 "entrypoint":f"{exporter.name} {dataset}", "instance":instance.name})
    return sources
