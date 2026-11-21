"""Structured resource references in optional pack exports and player-facing books."""
import json
from collections import defaultdict

from .bridge import BridgeError

OPTIONAL = {"structure": "structures.jsonl", "carving": "carving.jsonl",
            "block": "blocks.jsonl", "tooltip": "tooltips.jsonl"}


def edges(items, role):
    for item in items:
        if isinstance(item, dict) and isinstance(item.get("id"), str) and item["id"]:
            meta = item.get("meta", -1)
            if type(meta) is int:
                yield role, item["id"], meta


def link(db, entry, references):
    db.executemany("INSERT INTO ingredients VALUES(?,?,?,?)",
                   ((entry, *edge) for edge in set(references)))


def book_edges(kind, row):
    if kind == "guide":
        yield from edges(row.get("items") or [], "guide_item")
        yield from edges([row.get("icon")], "guide_icon")
    else:
        for field, role in (("tasks", "quest_task"), ("rewards", "quest_reward")):
            for section in row.get(field, []):
                if isinstance(section, dict):
                    yield from edges(section.get("items") or [], role)


def tooltip_modes(rows, info):
    for row in rows:
        if "mode" in row:
            yield row
            continue
        if "tip" not in row:
            raise ValueError("Missing normal tooltip text")
        lang = (info.get("tooltipsExport") or {}).get("lang") or info.get("lang")
        if not lang:
            raise ValueError("Legacy tooltip exports need a recorded language")
        for field, mode in (("tip", "normal"), ("shift", "shift"), ("ctrl", "ctrl")):
            if field in row:
                yield {"id": row["id"], "meta": row.get("meta", 0), "language": lang, "mode": mode,
                       "lines": row[field]}


def optional_entries(db, directory, source, info):
    from .packdata import _entry, _required, _rows
    for kind, filename in OPTIONAL.items():
        if not (directory / filename).is_file():
            continue
        rows = _rows(source(filename))
        if kind == "tooltip":
            rows = tooltip_modes(rows, info)
        if kind == "block":
            # Texture exporters can list the same block through several registries.
            variants = defaultdict(dict)
            for row in rows:
                key = (_required(row, "id"), row.get("meta", 0))
                variants[key][json.dumps(row, sort_keys=True, ensure_ascii=False)] = row
            rows = ({"id": key, "meta": meta, "variants": [parts[k] for k in sorted(parts)]}
                    for (key, meta), parts in variants.items())
        controllers = set()
        for row in rows:
            lang = ""
            if kind == "carving":
                ref = _required(row, "group")
                title = ref
                references = edges(row["variants"], "carving_variant")
            else:
                item = _required(row, "id")
                meta = row.get("meta", 0)
                if type(meta) is not int or not 0 <= meta <= 65535:
                    raise ValueError(f"Invalid {kind} metadata")
                ref = f"{item}@{meta}"
                title = row.get("name") or ref
                references = [(kind, item, meta)]
                if kind == "structure":
                    tiers = row["tiers"]
                    if not isinstance(tiers, list) or not tiers or any(type(t) is not int for t in tiers):
                        raise ValueError("Invalid exported structure tiers")
                    controllers.add(ref)
                    ref += ":" + ",".join(map(str, sorted(tiers)))
                    references.extend(edges(row.get("blocks", []), "structure_material"))
                elif kind == "tooltip":
                    mode = _required(row, "mode")
                    lang = _required(row, "language")
                    if mode not in {"normal", "shift", "ctrl"} or not isinstance(row.get("lines"), list) or not all(isinstance(line, str) for line in row["lines"]):
                        raise ValueError("Invalid exported tooltip mode or lines")
                    ref += f":{mode}:{lang}"
            terms = " ".join(row["lines"]) if kind == "tooltip" else ""
            entry = _entry(db, kind, ref, title, lang, terms, row)
            link(db, entry, references)
        if kind == "structure" and type(info.get("structures")) is int and len(controllers) != info["structures"]:
            raise ValueError("Distinct structure controllers differ from the export manifest")


def related(db, sources, args):
    """Sample typed links in one SQLite transaction, keeping each source's revision."""
    from .packdata import ITEM_LINKS_VERSION, KINDS
    resource = args["resource"]
    meta = args.get("metadata")
    limit = args.get("limit", 4)
    kinds = [args["kind"]] if "kind" in args else ["item", "handler", "recipe", "registry_recipe", "quest", "guide", *OPTIONAL]
    result = {}
    for kind in kinds:
        dataset = KINDS[kind]
        stamp = sources.get(dataset, {})
        revision = stamp.get("revision")
        if dataset in args.get("expectedRevisions", {}) and revision != args["expectedRevisions"][dataset]:
            raise BridgeError("PACKDATA_CHANGED", f"The {dataset} dataset changed during the linked lookup.")
        if not stamp or not stamp["counts"].get(kind):
            result[kind] = {"indexed": False}
            continue
        if kind not in ("item", "recipe") and stamp.get("itemLinksVersion") != ITEM_LINKS_VERSION[dataset]:
            result[kind] = {"indexed": False, "requiresReindex": True, "revision": revision}
            continue
        offset = args.get("offset", 0)
        if kind == "item":
            if meta is None:
                # Exact registry name. The following '@' sorts immediately before 'A'.
                found = db.execute("SELECT ref,title,'item' AS roles FROM entries WHERE kind='item' AND ref>=? AND ref<? ORDER BY ref LIMIT ? OFFSET ?",
                                   (resource+"@", resource+"A", limit+1, offset))
            else:
                found = db.execute("SELECT ref,title,'item' AS roles FROM entries WHERE kind='item' AND ref=? LIMIT ? OFFSET ?",
                                   (f"{resource}@{meta}", limit+1, offset))
        else:
            sql = """SELECT e.ref,e.title,group_concat(DISTINCT i.direction) AS roles
                     FROM ingredients i JOIN entries e ON e.id=i.entry
                     WHERE i.resource=? AND e.kind=? """
            params = [resource, kind]
            if meta is not None:
                sql += "AND i.meta IN (?,-1,32767) "
                params.append(meta)
            found = db.execute(sql + "GROUP BY i.entry ORDER BY i.entry LIMIT ? OFFSET ?", (*params, limit+1, offset))
        rows = found.fetchall()
        result[kind] = {"indexed": True, "revision": revision,
                        "records": [{"id": r["ref"], "title": r["title"], "roles": sorted(r["roles"].split(","))}
                                    for r in rows[:limit]],
                        "nextOffset": offset+limit if len(rows)>limit else None}
    return {"source": "local_pack_export", "liveStateObserved": False, "resource": resource,
            "metadata": meta, "links": result}
