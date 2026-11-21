"""Check explicitly selected pack inputs against the files observed during export."""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import tempfile

from .packdata import database, _file_state, _read
from .quest_packdata import _mode

# Paths are relative to the instance. Only static definitions enter the manifest.
NEI_INPUTS = [
    (".minecraft/mods", (".jar", ".zip")), (".minecraft/scripts", (".zs", ".json")),
    (".minecraft/config/GregTech", (".cfg", ".json")), (".minecraft/config/GTPlusPlus", (".cfg", ".json")),
    (".minecraft/config/MineTweaker3.cfg", (".cfg",)), ("mmc-pack.json", (".json",)),
    ("patches", (".json",)),
]
BOOK_INPUTS = [
    (".minecraft/config/betterquesting/DefaultQuests/QuestSettings.json", (".json",)),
    (".minecraft/config/betterquesting/DefaultQuests/Quests", (".json",)),
    (".minecraft/config/betterquesting/DefaultQuests/QuestLines", (".json",)),
    (".minecraft/config/betterquesting/DefaultQuests/QuestLinesOrder.txt", (".txt",)),
]
SETS = {"nei": NEI_INPUTS, "recex": NEI_INPUTS, "quests": BOOK_INPUTS,
        "guide": [(".minecraft/config/guidenh", (".zip",))]}


def _list(instance, relative, suffixes):
    path = instance
    for part in Path(relative).parts:
        path = path / part
        try:
            _mode(path)
        except FileNotFoundError:
            return
    def visit(folder):
        for child in sorted(folder.iterdir()):
            mode = _mode(child)
            if stat.S_ISDIR(mode):
                yield from visit(child)
            elif stat.S_ISREG(mode) and child.suffix.lower() in suffixes:
                yield child
    if path.is_dir():
        yield from visit(path)
    elif path.suffix.lower() in suffixes:
        yield path


def _digest(files):
    payload = json.dumps(files, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()


def capture_inputs(instance, datasets, *, quest_language="zh_CN"):
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", quest_language):
        raise ValueError("Invalid quest capture language")
    instance = Path(instance).resolve(strict=True)
    if not (instance / ".minecraft").is_dir():
        raise ValueError("Choose a Prism instance")
    hashed = {}
    groups = {}
    for dataset in datasets:
        files = {}
        specs = SETS[dataset]
        if dataset == "quests":
            loc = f".minecraft/config/txloader/forceload/betterquesting/lang/{quest_language}.lang"
            specs = [*specs, (loc, (".lang",))]
        for relative, suffixes in specs:
            for path in _list(instance, relative, suffixes):
                name = path.relative_to(instance).as_posix()
                # Carpenter's Blocks rewrites its texture atlas at launch under the mods directory.
                if name.casefold() == ".minecraft/mods/carpentersblocks/carpentersblockscachedresources.zip":
                    continue
                if name not in hashed:
                    if len(hashed) >= 50000:
                        raise ValueError("Static pack input file limit exceeded")
                    before = _file_state(path)
                    with path.open("rb") as stream:
                        hashed[name] = hashlib.file_digest(stream, "sha256").hexdigest()
                    if _file_state(path) != before:
                        raise ValueError(f"Input changed during hashing: {name}")
                files[name] = hashed[name]
        groups[dataset] = {"sha256": _digest(files), "files": files}
    return {"version": 1, "instanceKey": database(instance, {}).parent.name, "datasets": groups}


def _write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=path.parent,delete=False) as tmp:
        stage = Path(tmp.name)
        try:
            json.dump(value, tmp, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            tmp.flush()
            os.fsync(tmp.fileno())
        except BaseException:
            tmp.close()
            stage.unlink(missing_ok=True)
            raise
    try:
        os.replace(stage, path)
    finally:
        stage.unlink(missing_ok=True)


def write_export_inputs(output, initial, final):
    if initial != final:
        raise ValueError("Pack inputs changed during export; regenerate this capture")
    path = Path(output) / "export-inputs.json"
    previous = _read(path) if path.is_file() else initial
    if previous.get("version") != 1 or previous.get("instanceKey") != final["instanceKey"]:
        raise ValueError("Output already contains another instance's input manifest")
    _write_json(path, {**final,"datasets":{**previous["datasets"], **final["datasets"]}})


def read_export_inputs(directory, dataset, instance):
    path = Path(directory) / "export-inputs.json"
    if not path.is_file():
        return None
    proof = _read(path)
    if proof.get("version") != 1 or proof.get("instanceKey") != database(instance, {}).parent.name:
        raise ValueError("Export input manifest belongs to another instance or version")
    group = proof["datasets"].get(dataset)
    if group is None:
        return None
    files = group["files"]
    if not isinstance(files, dict) or len(files) > 50000 or any(
            not isinstance(name, str) or len(name)>1024 or Path(name).is_absolute() or ".." in Path(name).parts
            or not isinstance(value,str) or not re.fullmatch("[0-9a-f]{64}", value) for name,value in files.items()
    ) or _digest(files) != group.get("sha256"):
        raise ValueError("Invalid pack input signature")
    return group


def signature_summary(proof):
    return {"sha256":proof["sha256"], "files":len(proof["files"])} if proof is not None else None


def retain(db, dataset, proof):
    db.execute("DELETE FROM pack_input_baselines WHERE dataset=?", (dataset,))
    if proof is not None:
        db.execute("INSERT INTO pack_input_baselines VALUES(?,?)", (dataset, json.dumps(proof, ensure_ascii=False)))


def check_install(instance, datasets, env=None):
    instance = Path(instance).resolve(strict=True)
    path = database(instance, os.environ if env is None else env)
    if not path.is_file():
        raise ValueError("No published catalog for this instance")
    saved = {}
    with closing(sqlite3.connect(path.resolve().as_uri()+"?mode=ro",uri=True)) as db:
        db.execute("BEGIN")
        has_baselines = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='pack_input_baselines'").fetchone()
        for dataset in datasets:
            row = db.execute("SELECT info FROM sources WHERE dataset=?", (dataset,)).fetchone()
            baseline = db.execute("SELECT proof FROM pack_input_baselines WHERE dataset=?", (dataset,)).fetchone() if has_baselines else None
            saved[dataset] = (json.loads(row[0]) if row else {}, json.loads(baseline[0]) if baseline else None)
    quest_stamp = saved.get("quests", ({}, None))[0]
    language = quest_stamp.get("export", {}).get("lang") or "zh_CN"
    actual = capture_inputs(instance, datasets, quest_language=language)
    results = {}
    for dataset, group in actual["datasets"].items():
        stamp, proof = saved[dataset]
        data = {"revision":stamp.get("revision"), "observed":signature_summary(group),
                "captureSha256": (stamp.get("inputSignature") or {}).get("sha256")}
        if not proof or signature_summary(proof) != stamp.get("inputSignature"):
            data["status"] = "unverified_export_inputs" if stamp else "missing_dataset"
        else:
            old, new = proof["files"], group["files"]
            data["status"] = "matched" if proof["sha256"] == group["sha256"] else "refresh_recommended"
            for key, names in (("added",new.keys()-old.keys()),("removed",old.keys()-new.keys()),
                               ("modified",[k for k in old.keys()&new.keys() if old[k]!=new[k]])):
                data[key]={"count":len(names), "examples":sorted(names)[:12]}
        if data["status"] != "matched":
            data["next"] = {
                "nei": "Capture the selected pack with fetch-nei, then compare the new export.",
                "quests": "Use fetch to read the installed quest definitions and compare the publication.",
                "guide": "Use fetch to read the installed GuideNH archives and compare the publication.",
                "recex": "Verify the independent RecEx export against its originating installation.",
            }[dataset]
        results[dataset] = data
    report = {"checkedAt":datetime.now(timezone.utc).isoformat(timespec="seconds"),"datasets":results}
    # Independent dataset receipts keep separate checks from overwriting one another.
    for dataset, value in results.items():
        _write_json(path.with_name("inputs-"+dataset+".json"), {"checkedAt":report["checkedAt"], **value})
    return report


def last_checks(path, sources):
    results = {}
    for dataset, stamp in sources.items():
        if dataset not in SETS:
            continue
        recent = path.with_name("inputs-"+dataset+".json")
        if recent.is_file() and recent.stat().st_size <= 32768:
            try:
                check = _read(recent)
                if (isinstance(check, dict) and check.get("revision") == stamp["revision"]
                        and check.get("captureSha256") == (stamp.get("inputSignature") or {}).get("sha256")):
                    results[dataset] = check
            except (OSError, ValueError):
                results[dataset] = {"status": "input_check_unreadable"}
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, required=True)
    parser.add_argument("--datasets", nargs="+", choices=list(SETS), required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(check_install(args.instance,args.datasets),ensure_ascii=False, indent=2))
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.exit(1, f"packdata-inputs: {error}\n")


if __name__ == "__main__":
    main()
