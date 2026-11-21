"""Import an independent RecEx JSON snapshot into the selected instance's offline catalog."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile

from .packdata import _file_state
from .packdata_updates import maintain


def _headers(ijson, stream):
    groups = []
    machines = []
    for path, event, value in ijson.parse(stream):
        if path == "sources.item" and event == "start_map":
            machines = []
            groups.append({"type": None, "machines": machines})
        elif path == "sources.item.type" and event == "string":
            groups[-1]["type"] = value
        elif path == "sources.item.machines.item" and event == "start_map":
            machines.append(None)
        elif path == "sources.item.machines.item.n" and event == "string":
            machines[-1] = value
    if not groups or any(not g["type"] or not all(g["machines"]) for g in groups):
        raise ValueError("Missing RecEx source type or machine identity")
    return groups


def _recipes(ijson, stream, groups):
    group = machine = -1
    builder = None
    base = ""
    for path, event, value in ijson.parse(stream, use_float=True):
        if builder is not None:
            builder.event(event, value)
            if path == base and event == "end_map":
                yield {"providerType": groups[group]["type"],
                       "machine": groups[group]["machines"][machine] if ".machines." in base else None,
                       "recipe": builder.value}
                builder = None
        elif path == "sources.item" and event == "start_map":
            group += 1
            machine = -1
        elif path == "sources.item.machines.item" and event == "start_map":
            machine += 1
        elif path in {"sources.item.machines.item.recs.item", "sources.item.recipes.item"} and event == "start_map":
            base, builder = path, ijson.common.ObjectBuilder()
            builder.event(event, value)


def registry_edges(record):
    """Keep registry identities distinct from the NEI display alternatives."""
    recipe = record["recipe"]
    for field, role in (("iI", "input"), ("input", "input"), ("iO", "output"), ("o", "output"),
                        ("output", "output"), ("fI", "input"), ("fO", "output")):
        values = recipe.get(field, [])
        for item in values if isinstance(values, list) else [values]:
            if not isinstance(item, dict):
                continue
            if field in ("fI", "fO"):
                if isinstance(item.get("id"), str):
                    yield role, "fluid:" + item["id"], -1
            elif isinstance(item.get("id"), str):
                yield role, item["id"], item.get("m", -1)


def import_recex(instance, source, env=None, *, mode="update", audit_file=None):
    try:
        import ijson
    except ImportError as error:
        raise ValueError("Install the adapter's recex extra for the streaming importer.") from error
    source = Path(source).resolve(strict=True)
    instance = Path(instance).resolve(strict=True)
    if not (instance / ".minecraft").is_dir() or not source.is_file():
        raise ValueError("Select a Prism instance and one completed RecEx JSON export.")
    initial = _file_state(source)
    with tempfile.TemporaryDirectory(prefix="mcb-recex-") as folder, source.open("rb") as stream:
        root = Path(folder)
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
        stream.seek(0)
        groups = _headers(ijson, stream)
        stream.seek(0)
        counts = Counter()
        with (root / "registry-recipes.jsonl").open("w", encoding="utf-8", newline="\n") as out:
            for row in _recipes(ijson, stream, groups):
                counts[row["providerType"]] += 1
                out.write(json.dumps(row, ensure_ascii=False, separators=(",", ":"), allow_nan=False)+"\n")
        if _file_state(source) != initial:
            raise ValueError("RecEx export changed during import")
        info = {"format": 1, "provider": "RecEx", "rawSha256": digest, "recordsByProvider": dict(counts),
                "recipes": sum(counts.values())}
        (root / "meta.json").write_text(json.dumps({"recex": info}), encoding="utf-8")
        return maintain(instance, {"recex": root}, mode=mode, env=env, audit_file=audit_file)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--mode", choices=["check","compare","update"], default="update")
    parser.add_argument("--audit", type=Path)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(import_recex(args.instance, args.source, mode=args.mode, audit_file=args.audit), ensure_ascii=False))
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.exit(1, f"recex: {error}\n")


if __name__ == "__main__":
    main()
