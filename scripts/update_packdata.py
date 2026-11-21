"""Maintain the static quest book, GuideNH, NEI and tooltip catalogs for one Prism instance."""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp-server" / "src"))

from minecraft_codex_bridge import packdata, packdata_updates as updates
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.validation import validate_tool_arguments


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for action, help_text in {
        "fetch": "Export this instance's installed static books with the supplied exporter and update the catalog.",
        "fetch-nei": "Launch one isolated Windows Prism client and run a fresh native NEI export.",
        "fetch-tooltips": "Sample selected real item getters and their modifier modes into a verified NEI export copy.",
        "cleanup-nei": "After an interrupted capture, restore its exact temporary runner, settings and export world.",
        "finish-nei": "Verify and finish an already completed native capture without relaunching the client.",
        "check": "Hash explicitly supplied exports and identify stale datasets without publishing.",
        "check-inputs": "Compare installed static inputs against the signatures observed during collection.",
        "compare": "Preview record and coverage differences against this instance's committed catalog.",
        "update": "Validate and publish all supplied datasets, their links and audit trail in one transaction.",
        "get": "Read the catalog or the committed change history using mcb_packdata arguments.",
    }.items():
        command = sub.add_parser(action, help=help_text)
        command.add_argument("--instance", required=True, type=Path, help="Prism instance containing .minecraft")
        command.add_argument("--catalog-root", type=Path, help="Optional per-instance catalog parent (MCB_PACKDATA_ROOT)")
        if action == "check-inputs":
            command.add_argument("--datasets", nargs="+", choices=["nei","quests","guide","recex"],
                                 default=["nei","quests","guide","recex"])
            continue
        if action in {"cleanup-nei","finish-nei"}:
            command.add_argument("--out", required=True, type=Path, help="Existing output of the interrupted run")
            command.add_argument("--prism", type=Path)
            if action == "finish-nei":
                command.add_argument("--no-publish", action="store_true")
                command.add_argument("--audit-jsonl", type=Path)
            continue
        if action in {"fetch-nei", "fetch-tooltips"}:
            if action == "fetch-nei":
                command.add_argument("--provider-jar", required=True, type=Path, help="Production Alu NeiExport provider JAR")
            else:
                command.add_argument("--nei-base", required=True, type=Path, help="Complete NEI capture with matching input proof")
            command.add_argument("--tooltip-item", action="append", default=[], required=action=="fetch-tooltips",
                                 help="Exact registry:name@metadata (repeat for up to 32 distinct items)")
            command.add_argument("--tooltip-mode", action="append", choices=["normal", "shift", "ctrl"],
                                 help="Repeat to select modes (all three by default)")
            command.add_argument("--out", required=True, type=Path, help="New output directory outside the instance")
            command.add_argument("--prism", type=Path, help="Portable prismlauncher.exe")
            command.add_argument("--timeout", type=int, default=1800, help="Maximum acquisition time in seconds")
            command.add_argument("--no-publish", action="store_true", help="Acquire and verify without importing")
            command.add_argument("--audit-jsonl", type=Path, help="Write a semantic audit when publishing")
            command.add_argument("--benchmark-item", help="Optional registry name for a live NEI output query")
            command.add_argument("--benchmark-meta", type=int, default=0)
            command.add_argument("--benchmark-handler", help="Exact NEI overlay identifier for the query")
            continue
        if action == "get":
            command.add_argument("--args", default="{}", help="JSON query, defaults to catalog status")
            continue
        if action == "fetch":
            command.add_argument("--exporter", type=Path, required=True, help="Trusted local Alu make-packdata.py")
            command.add_argument("--language", default="zh_CN")
            command.add_argument("--out", type=Path, help="Optional new directory outside the instance to retain the captured books")
            command.add_argument("--no-publish", action="store_true", help="Only acquire the static books in --out")
        else:
            command.add_argument("--books", type=Path, help="Directory containing both quest and guide exports")
            command.add_argument("--quests", type=Path, help="Directory containing questlines.json and quests.jsonl")
            command.add_argument("--guide", type=Path, help="Directory containing guide.jsonl")
        command.add_argument("--nei", type=Path, help="Directory with the completed NEI export and optional tooltips")
        command.add_argument("--audit-jsonl", type=Path, help="Atomic full run and changed before/after records")
    args = parser.parse_args(argv)
    env = dict(os.environ)
    if args.catalog_root:
        env["MCB_PACKDATA_ROOT"] = str(args.catalog_root.resolve())
    try:
        with tempfile.TemporaryDirectory(prefix="mcb-packdata-") as temp:
            if args.action == "check-inputs":
                from minecraft_codex_bridge.packdata_inputs import check_install
                result = check_install(args.instance, args.datasets, env)
            elif args.action == "cleanup-nei":
                from packdata_nei import cleanup
                result = cleanup(args.instance, args.out, args.prism)
            elif args.action == "finish-nei":
                from packdata_nei import finish
                result = finish(args.instance, args.out, env, prism=args.prism, publish=not args.no_publish, audit_file=args.audit_jsonl)
            elif args.action in {"fetch-nei", "fetch-tooltips"}:
                from packdata_nei import acquire
                if bool(args.benchmark_item) != bool(args.benchmark_handler):
                    parser.error("Supply both --benchmark-item and --benchmark-handler")
                benchmark = (args.benchmark_item, args.benchmark_meta, args.benchmark_handler) if args.benchmark_item else None
                result = acquire(args.instance, args.out, getattr(args, "provider_jar", None), env, prism=args.prism,
                                 timeout=args.timeout, benchmark=benchmark, publish=not args.no_publish,
                                 tooltip_items=args.tooltip_item, tooltip_modes_requested=args.tooltip_mode or ("normal","shift","ctrl"),
                                 base=getattr(args, "nei_base", None), audit_file=args.audit_jsonl)
            elif args.action == "get":
                arguments = json.loads(args.args)
                if not isinstance(arguments, dict):
                    raise ValueError("--args must contain a JSON object")
                path = packdata.database(args.instance.resolve(strict=True), env)
                if arguments.get("action") in ("update_status", "changes"):
                    result = updates.query_updates(path, arguments)
                else:
                    validate_tool_arguments("mcb_packdata", arguments)
                    result = packdata.query(path, arguments)
            else:
                if args.action == "fetch":
                    if args.no_publish and (not args.out or args.nei or args.audit_jsonl):
                        parser.error("Book acquisition without publication needs --out and takes neither --nei nor --audit-jsonl")
                    destination = Path(temp)
                    if args.out:
                        destination = args.out.resolve()
                        if destination.is_relative_to(args.instance.resolve()) or destination.exists():
                            parser.error("--out must be a new directory outside the selected instance")
                        destination.mkdir(parents=True)
                    sources = updates.fetch_books(args.instance, destination, args.exporter, args.language)
                else:
                    sources = {name: args.books for name in ("quests", "guide")} if args.books else {}
                    sources.update({name: path for name in ("quests", "guide") if (path := getattr(args, name))})
                if args.nei:
                    sources["nei"] = args.nei
                if args.action == "fetch" and args.no_publish:
                    result = {"mode":"fetch", "published":False, "output":str(destination),
                              "datasets":{key:source is not None for key,source in sources.items()}}
                else:
                    result = updates.maintain(args.instance, sources, env=env, audit_file=args.audit_jsonl,
                                              mode="update" if args.action == "fetch" else args.action)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (BridgeError, OSError, ValueError, RuntimeError, KeyError, TypeError, sqlite3.Error,
            subprocess.SubprocessError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"packdata update: {exc}\n")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
