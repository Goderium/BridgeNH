"""Index static GTNH exports for a single explicitly selected Prism instance."""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import time

from .bridge import BridgeError
from .packdata_links import OPTIONAL, book_edges, edges, link, optional_entries, related

DATASETS = {"quests": ("chapter", "quest"), "guide": ("guide",), "nei": ("handler", "recipe", "item", *OPTIONAL),
            "recex": ("registry_recipe",)}
KINDS = {kind: dataset for dataset, kinds in DATASETS.items() for kind in kinds}
ITEM_LINKS_VERSION = {"nei": 2, "quests": 1, "guide": 1, "recex": 1}
MAX_LINE = 4 * 1024 * 1024
PAGE_CHARS = 6000


def database(instance: Path, env) -> Path:
    key = hashlib.sha256(os.path.normcase(str(instance.resolve())).encode()).hexdigest()
    local = Path(env.get("LOCALAPPDATA") or Path.home() / ".local/share") / "MinecraftCodexBridge/packdata"
    return Path(env.get("MCB_PACKDATA_ROOT") or local) / key / "catalog.sqlite3"


def bound_database(configuration) -> Path:
    if configuration is None:
        raise BridgeError("CLIENT_NOT_BOUND", "Select a Prism instance before reading its pack data.")
    binding = configuration.conversation_binding
    env = configuration.environ
    instance = binding.get("instancePath") if binding is not None else env.get("MCB_INSTANCE_PATH")
    if not instance and binding is None:
        runtime = env.get("MCB_RUNTIME_FILE", "")
        if runtime and runtime.casefold() not in {"auto", "discover"}:
            path = Path(runtime).resolve()
            if path.parts[-4:] == (".minecraft", "config", "codexbridge", "runtime.json"):
                instance = path.parents[3]
    if not instance:
        raise BridgeError("CLIENT_NOT_BOUND", "Bind this chat to the instance whose pack data you want to read.")
    return database(Path(instance), env)


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def _required(row, key):
    value = row[key]
    if not isinstance(value, str) or not value:
        raise ValueError(f"Missing text field {key}")
    return value


def _rows(path):
    with path.open("rb") as stream:
        while line := stream.readline(MAX_LINE + 1):
            if len(line) > MAX_LINE:
                raise ValueError(f"Oversized record in {path.name}")
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"Expected an object in {path.name}")
            yield row


def _read(path):
    if path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError(f"Oversized metadata in {path.name}")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _schema(db):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS sources(dataset TEXT PRIMARY KEY, info TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS pack_input_baselines(dataset TEXT PRIMARY KEY, proof TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS entries(
            id INTEGER PRIMARY KEY, kind TEXT NOT NULL, ref TEXT NOT NULL, title TEXT NOT NULL,
            lang TEXT NOT NULL, terms TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(kind,ref));
        CREATE TABLE IF NOT EXISTS ingredients(
            entry INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
            direction TEXT NOT NULL, resource TEXT NOT NULL, meta INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS item_ores(
            resource TEXT NOT NULL, meta INTEGER NOT NULL, ore TEXT NOT NULL,
            PRIMARY KEY(resource,meta,ore)) WITHOUT ROWID;
        CREATE TABLE IF NOT EXISTS recipe_handlers(
            entry INTEGER PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE, handler TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS handler_recipes ON recipe_handlers(handler,entry);
        CREATE INDEX IF NOT EXISTS resource_lookup ON ingredients(resource,direction,meta,entry);
        CREATE INDEX IF NOT EXISTS entry_ingredients ON ingredients(entry);
        CREATE INDEX IF NOT EXISTS ore_members ON item_ores(ore,resource,meta);
        CREATE INDEX IF NOT EXISTS list_entries ON entries(kind,id);
    """)


def _entry(db, kind, ref, title, lang, terms, row):
    if not isinstance(row, dict) or not ref or len(ref) > 512:
        raise ValueError(f"Invalid {kind} identifier")
    payload = _json(row)
    return db.execute("INSERT INTO entries(kind,ref,title,lang,terms,payload) VALUES(?,?,?,?,?,?)",
                      (kind, ref, title[:300], lang, (ref+" "+title+" "+terms).casefold(), payload)).lastrowid


def _ingredients(row):
    # Alternative lists are limited by the exporter. Preserve ore keys and full rows for later inspection.
    for field, direction in (("in", "input"), ("out", "output"), ("fin", "input"), ("fout", "output"), ("other", "other")):
        for slot in row.get(field, []):
            if "id" in slot:
                yield direction, _required(slot, "id"), slot.get("meta", -1)
            if "f" in slot:
                yield direction, "fluid:" + _required(slot, "f"), -1
            if "ore" in slot:
                yield direction, "ore:" + _required(slot, "ore"), -1
            for alt in slot.get("any", []):
                yield direction, alt[0], alt[1]


def _insert(db, directory, dataset, info):
    files = {}
    coverage = {}

    def source(name):
        path = directory / name
        files[name] = _file_state(path)
        return path

    if dataset == "quests":
        for row in _read(source("questlines.json"))["lines"]:
            _entry(db, "chapter", _required(row, "id"), _required(row, "name"), info.get("lang",""), _json(row.get("en","")), row)
        for row in _rows(source("quests.jsonl")):
            text = " ".join(str(row.get(k, "")) for k in ("en", "desc", "descEn", "tasks", "rewards"))
            entry = _entry(db, "quest", _required(row,"id"), _required(row,"name"), info.get("lang",""), text, row)
            link(db, entry, book_edges("quest", row))
    elif dataset == "guide":
        for row in _rows(source("guide.jsonl")):
            book, lang, path = (_required(row,k) for k in ("book","lang","path"))
            entry = _entry(db, "guide", f"{book}:{lang}:{path}", _required(row,"title"), lang,
                           row["text"]+" "+_json(row.get("items",[])), row)
            link(db, entry, book_edges("guide", row))
    elif dataset == "recex":
        from .recex_packdata import registry_edges
        for i, row in enumerate(_rows(source("registry-recipes.jsonl")), 1):
            method = _required(row, "providerType")
            ref = f"{i}-{hashlib.sha256(_json(row).encode()).hexdigest()[:20]}"
            entry = _entry(db, "registry_recipe", ref, row.get("machine") or method, "", method, row)
            link(db, entry, registry_edges(row))
    else:
        db.execute("DELETE FROM item_ores")
        handlers = {}
        for row in _read(source("handlers.json")):
            key = _required(row, "key")
            handlers[key] = row.get("name") or key
            method = "failed" if row.get("failed") else str(row.get("read", "unspecified"))
            coverage[method] = coverage.get(method, 0) + 1
            entry = _entry(db, "handler", key, handlers[key], "", str(row.get("mod","")), row)
            link(db, entry, edges(row.get("machines", []), "machine_handler"))
        for i, row in enumerate(_rows(source("recipes.jsonl")), 1):
            handler = _required(row, "h")
            if handler not in handlers:
                raise ValueError(f"Recipe {i} names an unknown handler")
            ref = f"{i}-{hashlib.sha256(_json(row).encode()).hexdigest()[:20]}"
            rid = _entry(db, "recipe", ref, handlers[handler], "", handler, row)
            db.execute("INSERT INTO recipe_handlers VALUES(?,?)", (rid,handler))
            db.executemany("INSERT INTO ingredients VALUES(?,?,?,?)", ((rid,*edge) for edge in set(_ingredients(row))))
        if (directory / "items.jsonl").is_file():
            for row in _rows(source("items.jsonl")):
                key = f"{_required(row,'id')}@{row.get('meta',0)}"
                _entry(db, "item", key, row.get("name") or key, "", str(row.get("en","")), row)
                db.executemany("INSERT OR IGNORE INTO item_ores VALUES(?,?,?)",
                               ((row["id"], row.get("meta",0), "ore:" + ore) for ore in row.get("ores", [])))
        optional_entries(db, directory, source, info)
    return files, coverage


def _file_state(path):
    stat = path.stat()
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns


def import_dataset(db, directory, dataset, metadata, *, instance=None):
    """Replace one dataset inside the caller's write transaction."""
    info = metadata.get(dataset)
    if not isinstance(info, dict) or type(info.get("format")) is not int or info["format"] != 1:
        raise ValueError(f"{dataset}: expected a version 1 meta.json section")
    for kind in DATASETS[dataset]:
        db.execute("DELETE FROM entries WHERE kind=?", (kind,))
    if dataset == "nei" and "tooltips" in metadata:
        if not isinstance(metadata["tooltips"], dict):
            raise ValueError("Invalid tooltip export metadata")
        info = {**info, "tooltipsExport": metadata["tooltips"]}
    files, coverage = _insert(db, directory, dataset, info)
    counts = {kind: db.execute("SELECT COUNT(*) FROM entries WHERE kind=?", (kind,)).fetchone()[0]
              for kind in DATASETS[dataset]}
    declared = {"quests": {"chapter":"lines","quest":"quests"}, "guide":{"guide":"pages"},
                "nei":{"handler":"pages","recipe":"recipes","item":"items","carving":"carving"},
                "recex":{"registry_recipe":"recipes"}}[dataset]
    for kind, name in declared.items():
        if (kind == "item" and "items.jsonl" not in files) or (kind in OPTIONAL and OPTIONAL[kind] not in files):
            continue
        if type(info.get(name)) is int and counts[kind] != info[name]:
            raise ValueError(f"{kind} count mismatch: manifest declares {info[name]}, read {counts[kind]}")
    hashes = {}
    for filename, initial in files.items():
        path_to_file = directory / filename
        with path_to_file.open("rb") as stream:
            hashes[filename] = hashlib.file_digest(stream, "sha256").hexdigest()
        if _file_state(path_to_file) != initial:
            raise ValueError(f"{filename} changed during import. Repeat with a completed export.")
    stamp = {"itemLinksVersion": ITEM_LINKS_VERSION[dataset], "counts": counts, "export": info, "sha256": hashes,
             "revision": hashlib.sha256(_json([hashes, info]).encode()).hexdigest(),
             "indexedAt": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    from .packdata_inputs import read_export_inputs, retain, signature_summary
    proof = read_export_inputs(directory, dataset, instance) if instance is not None else None
    retain(db, dataset, proof)
    if proof is not None:
        stamp["inputSignature"] = signature_summary(proof)
    if dataset == "nei":
        stamp["handlerReadMethods"] = coverage
    db.execute("INSERT OR REPLACE INTO sources VALUES(?,?)", (dataset, _json(stamp)))
    return stamp


def import_exports(instance, directory, datasets, env=None, *, remove=()):
    directory = Path(directory).resolve(strict=True)
    path = database(Path(instance), os.environ if env is None else env)
    path.parent.mkdir(parents=True, exist_ok=True)
    manifest = directory / "meta.json"
    initial_manifest = _file_state(manifest)
    input_manifest = directory / "export-inputs.json"
    initial_inputs = _file_state(input_manifest) if input_manifest.exists() else None
    metadata = _read(manifest)
    with closing(sqlite3.connect(path, timeout=30)) as db, db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA foreign_keys=ON")
        _schema(db)
        db.execute("BEGIN IMMEDIATE")
        report = {}
        for dataset in remove:
            for kind in DATASETS[dataset]:
                db.execute("DELETE FROM entries WHERE kind=?", (kind,))
            db.execute("DELETE FROM sources WHERE dataset=?", (dataset,))
            db.execute("DELETE FROM pack_input_baselines WHERE dataset=?", (dataset,))
        for dataset in datasets:
            report[dataset] = import_dataset(db, directory, dataset, metadata, instance=instance)["counts"]
        if (_file_state(input_manifest) if input_manifest.exists() else None) != initial_inputs:
            raise ValueError("export-inputs.json changed during import. Repeat with a completed export.")
        if _file_state(manifest) != initial_manifest:
            raise ValueError("meta.json changed during import. Repeat with a completed export.")
    return {"database": str(path), "datasets": report}


def _status(db):
    sources = {}
    for dataset, raw in db.execute("SELECT dataset,info FROM sources ORDER BY dataset"):
        data = json.loads(raw)
        # Provider metadata can include per-handler details; those are available on handler lookup.
        fields = ("format","source","pack","lang","generatedAt","quests","lines","translated","pages","chars","recipes",
                  "items","structures","carving","seconds","exporter","provider","rawSha256")
        provider = {key: data["export"][key] for key in fields if isinstance(data["export"].get(key),(str,int,float,bool))
                    and len(str(data["export"][key])) <= 200}
        if dataset == "recex":
            counts = data["export"].get("recordsByProvider", {})
            provider["recordsByProvider"] = {key: n for key, n in sorted(counts.items())[:32]
                                             if len(key) <= 100 and type(n) is int}
        if dataset == "guide":
            provider["source"] = [{k: str(pack[k])[:128] for k in ("file","version") if k in pack}
                                  for pack in data["export"].get("source", [])[:32] if isinstance(pack,dict)]
        sources[dataset] = {**data, "export": provider}
    return sources


def _publication_notice(row):
    report = json.loads(row["report"])
    updated = {name: data for name, data in report["datasets"].items() if data.get("needsUpdate")}
    return {"id": row["update_id"], "datasets": sorted(updated),
            "newBaselines": sorted(name for name, data in updated.items() if data.get("baseline")),
            "changes": {name: data["changes"] for name, data in updated.items() if data.get("changes")}}


def query(path, args):
    action = args.get("action", "status")
    base = {"source": "local_pack_export", "liveStateObserved": False}
    if not path.is_file():
        if action == "status":
            return {**base, "available": False, "datasets": {}}
        if action in {"changes", "update_status"}:
            from .packdata_updates import query_updates
            return query_updates(path, args)
        raise BridgeError("PACKDATA_MISSING", "Prepare the selected instance's local catalog. See mcb_help(topic=packdata).")
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2)) as db:
        deadline = time.monotonic() + 3
        db.set_progress_handler(lambda: int(time.monotonic() > deadline), 10000)
        db.row_factory = sqlite3.Row
        db.execute("BEGIN")
        sources = _status(db)
        if action == "status":
            from .packdata_inputs import last_checks
            status = {**base, "available": bool(sources), "datasets": sources, "inputChecks": last_checks(path, sources)}
            status["refreshRecommended"] = sorted(dataset for dataset, check in status["inputChecks"].items()
                                                  if check.get("status") == "refresh_recommended")
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='pack_updates'").fetchone():
                recent = db.execute("SELECT update_id,report FROM pack_updates ORDER BY seq DESC LIMIT 1").fetchone()
                if recent:
                    notice = _publication_notice(recent)
                    status["lastUpdate"] = {**notice, "changesAction": "changes"}
                    known = args.get("knownUpdateId")
                    if known is not None:
                        status["lastUpdate"]["changedSinceKnown"] = known != notice["id"]
                        previous = db.execute("SELECT seq FROM pack_updates WHERE update_id=?", (known,)).fetchone()
                        if previous is None:
                            status["updatesSinceKnown"] = {"knownUpdateFound": False, "historyAction": "update_status"}
                        else:
                            rows = db.execute("SELECT update_id,report FROM pack_updates WHERE seq>? "
                                              "ORDER BY seq DESC LIMIT 6", (previous["seq"],)).fetchall()
                            status["updatesSinceKnown"] = {
                                "knownUpdateFound": True, "updates": [_publication_notice(row) for row in rows[:5]],
                                "hasMore": len(rows) > 5, "historyAction": "update_status"}
            return status
        if action in {"changes", "update_status"}:
            from .packdata_updates import query_updates
            return query_updates(path, args)
        if action == "related":
            return related(db, sources, args)
        kind = args["kind"]
        dataset = KINDS[kind]
        if dataset not in sources or not sources[dataset]["counts"].get(kind):
            raise BridgeError("PACKDATA_MISSING", f"The selected instance has no indexed {kind} records. See mcb_help(topic=packdata).")
        revision = sources[dataset].get("revision")
        if "expectedRevision" in args and args["expectedRevision"] != revision:
            raise BridgeError("PACKDATA_CHANGED", "The selected dataset was replaced. Restart this lookup from its first page.")
        base.update(kind=kind, indexedAt=sources[dataset]["indexedAt"], revision=revision)
        offset = args.get("offset", 0)
        if action == "get":
            row = db.execute("SELECT payload FROM entries WHERE kind=? AND ref=?", (kind, args["id"])).fetchone()
            if row is None:
                raise BridgeError("PACKDATA_NOT_FOUND", "Unknown record in the selected instance's catalog.")
            payload = row[0]
            if not offset and len(payload) <= PAGE_CHARS:
                return {**base, "record": json.loads(payload)}
            end = min(len(payload), offset + PAGE_CHARS)
            return {**base, "id": args["id"], "jsonFragment": payload[offset:end], "offset": offset,
                    "nextOffset": end if end < len(payload) else None, "totalCharacters": len(payload)}
        cond, params = ["e.kind=?"], [kind]
        if handler := args.get("handler"):
            if sources[dataset].get("itemLinksVersion") != ITEM_LINKS_VERSION[dataset]:
                raise BridgeError("PACKDATA_REINDEX_REQUIRED", "Update the NEI dataset to index its exact handler links.")
            # For a combined lookup probe the handler of each matched resource. Materializing every
            # recipe in a large handler can cost more than looking up the product itself.
            if args.get("resource"):
                cond.append("EXISTS (SELECT 1 FROM recipe_handlers h WHERE h.entry=e.id AND h.handler=?)")
            else:
                cond.append("e.id IN (SELECT entry FROM recipe_handlers WHERE handler=?)")
            params.append(handler)
        if q := args.get("query"):
            cond.append("instr(e.terms,?)>0")
            params.append(q.casefold())
        if lang := args.get("language"):
            cond.append("e.lang=? COLLATE NOCASE")
            params.append(lang)
        if resource := args.get("resource"):
            clause = ["resource=?"]
            params.append(resource)
            if "direction" in args:
                clause.append("direction=?")
                params.append(args["direction"])
            if "metadata" in args:
                clause.append("meta IN (?,32767)")
                params.append(args["metadata"])
            selected = "SELECT entry FROM ingredients WHERE " + " AND ".join(clause)
            if kind == "recipe" and args.get("direction") in (None, "input") and not resource.startswith(("ore:", "fluid:")):
                membership = "SELECT ore FROM item_ores WHERE resource=?"
                params.append(resource)
                if "metadata" in args:
                    membership += " AND meta IN (?,32767)"
                    params.append(args["metadata"])
                selected += f" UNION SELECT entry FROM ingredients WHERE direction='input' AND resource IN ({membership})"
            cond.append("e.id IN (" + selected + ")")
        limit = args.get("limit",12)
        rows = db.execute("SELECT ref,title,lang FROM entries e WHERE " + " AND ".join(cond)
                          + " ORDER BY e.id LIMIT ? OFFSET ?", (*params,limit+1,offset)).fetchall()
        results = []
        size = 0
        for r in rows[:limit]:
            entry = {"id": r["ref"], "title": r["title"], "language": r["lang"]}
            size += len(_json(entry).encode())
            if size > 24000:
                break
            results.append(entry)
        return {**base, "results": results, "nextOffset": offset+len(results) if len(rows)>len(results) else None}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="Export the quest book and GuideNH from the selected local client.")
    export.add_argument("--instance", type=Path, required=True)
    export.add_argument("--exporter", type=Path, required=True, help="Path to the upstream make-packdata.py")
    export.add_argument("--out", type=Path, required=True)
    export.add_argument("--language", default="zh_CN")
    import_cmd = commands.add_parser("import", help="Index existing Alu-format local exports.")
    import_cmd.add_argument("--instance", type=Path, required=True)
    import_cmd.add_argument("--source", type=Path, required=True)
    import_cmd.add_argument("--datasets", nargs="+", choices=list(DATASETS), required=True)
    lookup = commands.add_parser("query", help="Read the selected offline index with mcb_packdata arguments.")
    lookup.add_argument("--instance", type=Path, required=True)
    lookup.add_argument("--args", default="{}", help="MCP argument object as JSON (defaults to status).")
    args = parser.parse_args(argv)
    try:
        instance = args.instance.resolve(strict=True)
        game = instance / ".minecraft"
        if not game.is_dir():
            raise ValueError("Select a Prism instance containing .minecraft")
        if args.command == "query":
            from .validation import validate_tool_arguments
            arguments = json.loads(args.args)
            validate_tool_arguments("mcb_packdata", arguments)
            report = query(database(instance, os.environ), arguments)
        else:
            from .packdata_updates import fetch_books, maintain
            if args.command == "export":
                if args.out.resolve().is_relative_to(instance):
                    raise ValueError("Store the export outside the game instance.")
                args.out.mkdir(parents=True, exist_ok=True)
                sources = fetch_books(instance, args.out, args.exporter, args.language)
            else:
                sources = {dataset: args.source for dataset in args.datasets}
            publication = maintain(instance, sources)
            # Preserve the original CLI's compact counts and expose the audited publication ID.
            counts = {}
            for name, change in publication["datasets"].items():
                stamp = change.get("after") or change.get("before")
                if sources[name] is not None and stamp:
                    counts[name] = stamp["counts"]
            report = {"database": str(database(instance, os.environ)), "datasets": counts,
                      "updateId": publication["updateId"]}
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (BridgeError, RuntimeError, OSError, KeyError, ValueError, TypeError, sqlite3.Error, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"packdata: {exc}\n")


if __name__ == "__main__":
    main()
