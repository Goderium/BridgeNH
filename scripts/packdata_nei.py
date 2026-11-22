"""One-shot native NEI acquisition in an explicitly selected Windows Prism export client."""
import asyncio
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from uuid import uuid4
import zipfile

from minecraft_codex_bridge import launcher, packdata, packdata_inputs, packdata_updates
from minecraft_codex_bridge.bridge import Configuration
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.packdata_links import tooltip_modes


RUNNER = "BridgeNH-Offline-Packdata.jar"
SETTINGS = ("options.txt", "config/codexbridge/window-startup.txt")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _build_runner(prism, provider, state, options):
    libraries = prism.parent / "libraries"
    forge = list((libraries / "net/minecraftforge/forge").glob("1.7.10-*/forge-*-universal.jar"))
    logging = sorted((libraries / "org/apache/logging/log4j/log4j-api").glob("*/*.jar"))
    gson = sorted((libraries / "com/google/code/gson/gson").glob("*/*.jar"))
    if len(forge) != 1 or not logging or not gson:
        raise ValueError("Expected one Minecraft 1.7.10 Forge universal JAR, Log4j API and Gson under Prism libraries")
    classes = state / "classes"
    classes.mkdir()
    source = Path(__file__).resolve().parent / "packdata_runner"
    javac = shutil.which("javac")
    if not javac:
        raise ValueError("A JDK with javac and --release 8 support is required")
    subprocess.run([javac, "--release", "8", "-proc:none", "-encoding", "UTF-8", "-cp",
                    os.pathsep.join(map(str, (forge[0], logging[0], gson[-1]))), "-d", str(classes),
                    *map(str, sorted(source.glob("*.java")))], check=True)
    runner = state / RUNNER
    with zipfile.ZipFile(runner, "x", zipfile.ZIP_DEFLATED) as out:
        if provider is not None:
            with zipfile.ZipFile(provider) as src:
                if "alu/aiplayer/export/NeiExport.class" not in src.namelist():
                    raise ValueError("The supplied production-remapped provider does not contain Alu NeiExport")
                for name in src.namelist():
                    if re.fullmatch(r"alu/aiplayer/export/[A-Za-z0-9_$/]+\.class", name) or name.upper() in {"LICENSE", "LICENSE.TXT"}:
                        out.writestr(name, src.read(name))
        for compiled in classes.rglob("*.class"):
            out.write(compiled, compiled.relative_to(classes).as_posix())
        out.writestr("mcb-export.properties", "".join(f"{k}={base64.b64encode(str(v).encode()).decode()}\n" for k, v in options.items()))
    return runner


def _archive_world(game, state, save):
    roots = [game / "saves" / save, game / "saves/NEI/local" / save, game / "journeymap/data/sp" / save,
             game / "TCNodeTracker" / save]
    vp = game / "visualprospecting/server"
    if vp.is_dir():
        roots.extend(p for p in vp.glob(save + "_*") if re.fullmatch(re.escape(save)+r"_[a-fA-F0-9-]{36}", p.name))
    for source in roots:
        if not source.exists():
            continue
        target = state / "generated-world" / source.relative_to(game)
        if source.resolve() != source or not source.resolve().is_relative_to(game) or not target.resolve().is_relative_to(state) or target.exists():
            raise ValueError("Unexpected export world or archive path")
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "Move-Item -LiteralPath $env:MCB_EXPORT_FROM -Destination $env:MCB_EXPORT_TO -ErrorAction Stop"],
                       env={**os.environ, "MCB_EXPORT_FROM": str(source), "MCB_EXPORT_TO": str(target)}, check=True,
                       creationflags=subprocess.CREATE_NO_WINDOW)


def _configuration(instance, prism):
    config = Configuration({"MCB_INSTANCE_PATH":str(instance),
                            "MCB_PRISM_EXE":str(prism or instance.parents[1]/"prismlauncher.exe")})
    setting = launcher.read_launch_configuration(config)
    if setting.instance_path != instance:
        raise ValueError("Resolved launch configuration differs from the selected export instance")
    return config, setting


def _running(platform, setting, out):
    processes = platform.processes()
    pid_file = out/"runner.pid"
    if pid_file.is_file():
        value = pid_file.read_text(encoding="ascii").strip()
        if value.isdecimal() and int(value) > 0:
            if any(p.process_id == int(value) and p.name.lower() in {"java.exe","javaw.exe"} for p in processes):
                return True
    clients = [p for p in processes if p.command_line and "org.prismlauncher" in p.command_line.lower()
               and "entrypoint" in p.command_line.lower()]
    return launcher._target_java(clients, setting) is not None


def _await_exit(platform, setting, out, run, deadline, clock=time.monotonic, pause=time.sleep):
    absent_since = None
    while clock() < deadline:
        try:
            running = _running(platform, setting, out)
        except BridgeError as error:
            if error.code not in {"PROCESS_QUERY_FAILED","PROCESS_INSPECTION_INCOMPLETE"}:
                raise
            absent_since = None
            pause(2)
            continue
        if running:
            absent_since = None
        else:
            if absent_since is None:
                absent_since = clock()
            complete = (out/"export.ok").is_file() or (out/"export.failed").is_file()
            started = (out/"runner.started").is_file()
            # Prism's Java validation helpers can exit before the real client starts.
            if clock()-absent_since >= (6 if complete else 18 if started else 90):
                if complete:
                    return
                raise RuntimeError(f"The isolated export client exited without a completion marker for {run}")
        pause(2)
    raise TimeoutError(f"Export timeout. Inspect the isolated client and retained request at {out}")


def cleanup(instance, output, prism=None):
    """Complete only this stopped client's recorded temporary runner, settings and uniquely named world."""
    instance = Path(instance).resolve(strict=True)
    out = Path(output).resolve(strict=True)
    state = out/".run"
    game = (instance/".minecraft").resolve(strict=True)
    spec = packdata._read(state/"run.json")
    _, setting = _configuration(instance, prism)
    run = spec["runId"]
    if (not re.fullmatch("[a-f0-9]{32}", run) or spec["world"] != "BridgeNH-Export-" + run
            or spec["instance"] != instance.name or out.is_relative_to(instance)):
        raise ValueError("This export run does not identify the selected instance")
    runner = state/RUNNER
    if digest(runner) != spec["runnerSha256"]:
        raise ValueError("Archived runner differs from the export request")
    with zipfile.ZipFile(runner) as jar:
        embedded = dict(line.split("=", 1) for line in jar.read("mcb-export.properties").decode().splitlines())
        for key, value in (("id",run), ("game",str(game)), ("out",str(out))):
            if base64.b64decode(embedded[key]).decode() != value:
                raise ValueError("Archived runner targets a different capture")
    if (state/"cleanup.ok").is_file():
        return {"runId":run, "cleanup":"already_completed"}
    if _running(launcher.WindowsBackend(), setting, out):
        raise RuntimeError(f"Close only the selected export client, then run cleanup-nei for {out}")
    permissions = spec["savedSettings"]
    if set(permissions) != set(SETTINGS):
        raise ValueError("Unexpected settings manifest in the export backup")
    for relative, expected in permissions.items():
        if expected is not None and digest(state/Path(relative).name) != expected:
            raise ValueError("Export settings backup changed")
    active = game/"mods"/RUNNER
    phase = state/"cleanup.started"
    if active.exists():
        if digest(active) != spec["runnerSha256"]:
            raise ValueError("Installed export runner changed")
        phase.write_text(run, encoding="ascii")
        active.unlink()
    elif not phase.is_file() or phase.read_text(encoding="ascii") != run:
        raise ValueError("The installed runner is missing, without an earlier cleanup receipt")
    for relative, expected in permissions.items():
        target = game/relative
        if expected is None:
            target.unlink(missing_ok=True)
        elif not target.is_file() or digest(target) != expected:
            with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as tmp:
                staging = Path(tmp.name)
            try:
                shutil.copyfile(state/target.name, staging)
                os.replace(staging, target)
            finally:
                staging.unlink(missing_ok=True)
    _archive_world(game, state, spec["world"])
    (state/"cleanup.ok").write_text(run, encoding="ascii")
    return {"runId":run, "cleanup":"completed"}


def _verify(out, run):
    failure = out / "export.failed"
    if failure.is_file():
        with failure.open(encoding="utf-8", errors="replace") as marker:
            if marker.readline(128).rstrip("\r\n") == run:
                trace = marker.read(65536).splitlines()
                causes = [line.strip()[:350] for line in trace[1:] if line.startswith("Caused by:")]
                detail = " | ".join([*trace[:1], *causes])[:2400]
                raise ValueError(f"Native NEI capture failed ({failure}): {detail}")
    if failure.exists() or not (out/"export.ok").is_file() or (out / "export.ok").read_text(encoding="utf-8") != run:
        raise ValueError("This acquisition has no matching successful exporter completion")
    meta = packdata._read(out / "meta.json")["nei"]
    if type(meta.get("format")) is not int or meta["format"] != 1:
        raise ValueError("Unsupported NEI manifest format")
    handlers = packdata._read(out / "handlers.json")
    counts = Counter(row["h"] for row in packdata._rows(out / "recipes.jsonl"))
    observed = {"recipes": sum(counts.values()), "pages": len(handlers),
                "items": sum(1 for _ in packdata._rows(out / "items.jsonl"))}
    if any(type(meta.get(k)) is not int or v <= 0 or v != meta[k] for k, v in observed.items()):
        raise ValueError("Incomplete or mismatched NEI manifest counts")
    if len({h["key"] for h in handlers}) != len(handlers) or counts.keys() - {h["key"] for h in handlers}:
        raise ValueError("Recipe handler identities do not match the export")
    for handler in handlers:
        if handler.get("recipes", 0) != counts[handler["key"]]:
            raise ValueError("A handler's declared row count does not match the exported recipes")
    if any(out.glob("*.part")):
        raise ValueError("The provider left an unfinished export file")
    return {**observed, "handlerReadMethods":dict(Counter(h.get("read", "unknown") for h in handlers)),
            "emptyHandlers":sum(not counts[h["key"]] for h in handlers),
            "failedHandlers":[h["key"] for h in handlers if h.get("failed")],
            "partialHandlers":[h["key"] for h in handlers if h.get("partial")]}


def _file_manifest(out):
    names = ("meta.json", *packdata_updates.FILES["nei"].values())
    return {key:digest(out/key) for key in names if (out/key).is_file()}


def _selections(items, modes):
    items, modes = list(dict.fromkeys(items)), list(dict.fromkeys(modes))
    if len(items) > 32 or any(not re.fullmatch(r"[A-Za-z0-9_.|/-]+:[A-Za-z0-9_.|/-]+@[0-9]{1,5}", key) or
                              int(key.rsplit("@",1)[1]) > 65535 for key in items):
        raise ValueError("Select up to 32 exact registry:name@metadata identifiers (0..65535)")
    if not modes or set(modes) - {"normal", "shift", "ctrl"}:
        raise ValueError("Choose normal, shift or ctrl as the tooltip mode")
    return items, modes


def _copy_base(base, out, instance, proof):
    if packdata_inputs.read_export_inputs(base, "nei", instance) != proof:
        raise ValueError("Tooltip enrichment needs a base NEI export with matching verified installed inputs")
    names = {"meta.json", "nei-provenance.json", *packdata_updates.FILES["nei"].values()}
    initial = {name: packdata._file_state(base/name) for name in names if (base/name).is_file()}
    for name in initial:
        shutil.copyfile(base/name, out/name)
    if initial != {name: packdata._file_state(base/name) for name in names if (base/name).is_file()}:
        raise ValueError("Base export changed while copying its manifest and data")
    catalog = packdata._read(base/"export-files.json")
    if catalog.get("version") != 1 or catalog.get("sha256") != _file_manifest(out):
        raise ValueError("Base export files differ from the completed capture manifest")


def _merge_samples(out):
    coverage = packdata._read(out/"tooltip-sample-report.json")
    samples = list(packdata._rows(out/"tooltip-samples.jsonl"))
    if coverage["samples"] != len(samples):
        raise ValueError("Incomplete sampled tooltip capture")
    meta = packdata._read(out/"meta.json")
    if coverage["lang"] != meta["nei"].get("lang"):
        raise ValueError("Selected tooltip and base export languages differ")
    current = out/"tooltips.jsonl"
    def identity(row):
        return row["id"], row.get("meta",0), row["mode"], row["language"]
    added = {identity(row):row for row in samples}
    if len(added) != len(samples):
        raise ValueError("Repeated sampled tooltip identity")
    merged = 0
    stage = out/"tooltips.jsonl.part"
    if current.is_file() or added:
        with stage.open("x", encoding="utf-8", newline="\n") as writer:
            def emit(row):
                writer.write(json.dumps(row, ensure_ascii=False, separators=(",",":")) + "\n")
            if current.is_file():
                info = {**meta["nei"], "tooltipsExport":meta.get("tooltips",{})}
                for row in tooltip_modes(packdata._rows(current), info):
                    emit(added.pop(identity(row), row))
                    merged += 1
            for row in added.values():
                emit(row)
                merged += 1
        os.replace(stage, current)
    meta["tooltips"] = {**meta.get("tooltips",{}), "lang":coverage["lang"], "views":merged,
                        "lastSample":coverage, "scope":"selected"}
    packdata_inputs._write_json(out/"meta.json", meta)
    return coverage


def acquire(instance, output, provider, env, *, prism=None, timeout=1800, benchmark=None, publish=True,
            tooltip_items=(), tooltip_modes_requested=("normal","shift","ctrl"), base=None, audit_file=None):
    """Use a unique output, new local world and job token. A failed capture never replaces the catalog."""
    instance, out = Path(instance).resolve(strict=True), Path(output).resolve()
    provider = Path(provider).resolve(strict=True) if provider else None
    base = Path(base).resolve(strict=True) if base else None
    items, modes = _selections(tooltip_items, tooltip_modes_requested)
    if (provider is None) == (base is None) or (base is not None and not items):
        raise ValueError("Choose a native NEI provider, or a verified NEI base and at least one tooltip item")
    game = (instance / ".minecraft").resolve(strict=True)
    if out.is_relative_to(instance) or out.exists():
        raise ValueError("Choose a new export directory outside the selected instance")
    if not 120 <= timeout <= 7200:
        raise ValueError("Expected a 120–7200 second timeout")
    config, setting = _configuration(instance, prism)
    platform = launcher.WindowsBackend()
    if launcher._target_java(platform.processes(), setting):
        raise ValueError("Close the selected export client before starting a one-shot acquisition")
    active = game / "mods" / RUNNER
    if active.exists():
        raise ValueError("An export runner already exists in the selected instance; inspect its prior run first")
    out.mkdir(parents=True)
    state = out / ".run"
    state.mkdir()
    run = uuid4().hex
    item, meta, handler = benchmark or ("", 0, "")
    opts = {"game": str(game), "out": str(out), "id": run, "benchItem": item, "benchMeta": meta, "benchHandler": handler,
            "task":"tooltips" if base else "nei", "tooltipItems":"\n".join(items), "tooltipModes":",".join(modes)}
    runner = _build_runner(setting.prism_exe, provider, state, opts)
    before = packdata_inputs.capture_inputs(instance, ["nei"])
    if base is not None:
        _copy_base(base, out, instance, before["datasets"]["nei"])
    packdata_inputs._write_json(state / "initial-inputs.json", before)
    (out / ("request-" + run)).touch(exist_ok=False)
    saved = {}
    for relative in SETTINGS:
        file = game / relative
        saved[relative] = digest(file) if file.is_file() else None
        if saved[relative] is not None:
            shutil.copyfile(file, state/file.name)
    spec = {"runId": run, "runnerSha256": digest(runner),
            "entrypoint": "BridgeNH selected ItemStack.getTooltip" if base else "alu.aiplayer.export.NeiExport.run(File)",
            "source": "BridgeNH" if base else "aruru-project/alu_gtnh_aiplayer",
            "instance": instance.name, "world": "BridgeNH-Export-" + run, "savedSettings":saved}
    if provider is not None:
        spec["providerSha256"] = digest(provider)
    packdata_inputs._write_json(state / "run.json", spec)
    started = time.monotonic()
    # Compiling the runner and hashing installed mods can take long enough for a player to launch the instance.
    if _running(platform, setting, out):
        raise ValueError("The selected export client started during preparation; no runner was installed")
    with runner.open("rb") as original, active.open("xb") as destination:
        try:
            shutil.copyfileobj(original, destination)
        except BaseException:
            destination.close()
            active.unlink()
            raise
    try:
        try:
            launch = asyncio.run(launcher.launch_client(config, window_mode="minimized"))
            if launch.get("status") != "scheduled":
                raise RuntimeError("The selected Prism instance was not launched")
        except BridgeError as error:
            if not error.details.get("outcome_unknown"):
                raise
            # A single Prism dispatch was already made. Keep observing that request only.
        _await_exit(platform, setting, out, run, started + timeout)
    finally:
        cleanup(instance, out, prism)
    return finish(instance, out, env, prism=prism, publish=publish, audit_file=audit_file)


def finish(instance, output, env, *, prism=None, publish=True, audit_file=None):
    """Complete a successful native request without scheduling or replaying a client launch."""
    instance, out = Path(instance).resolve(strict=True), Path(output).resolve(strict=True)
    state = out/".run"
    spec = packdata._read(state/"run.json")
    run = spec["runId"]
    cleanup(instance, out, prism)
    observed = _verify(out, run)
    with zipfile.ZipFile(state/RUNNER) as jar:
        embedded = dict(line.split("=",1) for line in jar.read("mcb-export.properties").decode().splitlines())
    opts = {k:base64.b64decode(v).decode() for k,v in embedded.items()}
    items, modes = _selections(opts.get("tooltipItems","").splitlines(),opts.get("tooltipModes","normal,shift,ctrl").split(","))
    before = packdata._read(state/"initial-inputs.json")
    if (out/"export-files.json").is_file() and packdata._read(out/"export-files.json").get("sha256") != _file_manifest(out):
        raise ValueError("Completed export files changed before finalization")
    coverage = _merge_samples(out) if items else None
    final = packdata_inputs.capture_inputs(instance, ["nei"])
    if before != final:
        packdata_inputs._write_json(state/"final-inputs.json", final)
        a, b = before["datasets"]["nei"]["files"], final["datasets"]["nei"]["files"]
        drift = sorted(k for k in a.keys() | b.keys() if a.get(k) != b.get(k))
        raise ValueError(f"Pack inputs changed during export ({len(drift)} files): {', '.join(drift[:10])}. See .run manifests")
    packdata_inputs.write_export_inputs(out, before, final)
    elapsed = (out/"export.ok").stat().st_mtime - (out/("request-"+run)).stat().st_mtime
    receipt = {**spec, "counts":observed, "nativeElapsedSeconds":round(elapsed,3)}
    if items:
        receipt["tooltips"] = {"items":items, "modes":modes, **coverage}
    if opts.get("benchItem"):
        receipt["benchmark"] = {"item":opts["benchItem"], "metadata":int(opts["benchMeta"]), "handler":opts["benchHandler"]}
        if (out / "benchmark.txt").is_file():
            receipt["benchmark"]["samples"] = [{"nanos": int(ns), "matches": int(n)}
                for line in (out / "benchmark.txt").read_text().splitlines() for ns, n in [line.split()]]
        elif (out / "benchmark.failed").is_file():
            receipt["benchmark"]["error"] = (out / "benchmark.failed").read_text()[:350]
    packdata_inputs._write_json(out / ("tooltip-provenance.json" if opts.get("task")=="tooltips" else "nei-provenance.json"), receipt)
    packdata_inputs._write_json(out/"export-files.json", {"version":1, "runId":run, "sha256":_file_manifest(out)})
    if publish:
        if observed["failedHandlers"] or observed["partialHandlers"]:
            raise ValueError("The capture contains failed or partial recipe handlers. Inspect the retained snapshot before publication")
        receipt["publication"] = packdata_updates.maintain(instance, {"nei": out}, env=env,
                                                          preserve_coverage=True, audit_file=audit_file)
    return receipt
