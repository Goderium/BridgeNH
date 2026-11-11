"""Read bounded passive recordings from the selected instance, including offline history."""
from __future__ import annotations

from collections import Counter
import json
import math
import os
from pathlib import Path
import re
import time

from .bridge import BridgeError, Configuration
from .conveniences import bounded_value, json_size

MAX_FILE = 32 * 1024 * 1024
MAX_LINE = 16 * 1024
MAX_FILES = 257
PAGE_BYTES = 24 * 1024
SUMMARY_BYTES = 48 * 1024
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
FILENAME = re.compile(r"recording-(" + UUID + r")\.jsonl\Z")
LIMITATIONS = (
    "Historical sampled observations, not every-frame replay or current world state. "
    "Disk reads do not observe live enabled/queue/flushed counters. Missing stop event means unknown, not active. "
    "Client intent is not server acceptance; nearby observations are not proof of causation. "
    "Mods bypassing native hooks may be absent; continuous sequences do not prove complete input coverage."
)


def _refuse(message, code="RECORDING_INVALID"):
    raise BridgeError(code, message)


def _root(configuration: Configuration) -> Path:
    runtime = configuration.runtime_path()
    if runtime is None or [p.casefold() for p in runtime.parts[-4:]] != [".minecraft", "config", "codexbridge", "runtime.json"]:
        _refuse("Select a standard instance/.minecraft/config/codexbridge/runtime.json first.", "RECORDING_CONFIG_REQUIRED")
    runtime = runtime.absolute()
    game = runtime.parents[2].resolve(strict=True)
    if runtime.resolve() != game / "config/codexbridge/runtime.json":
        _refuse("The runtime path may not redirect outside its fixed game directory.", "RECORDING_PATH_REFUSED")
    root = game / "mcb-recordings"
    if root.resolve() != root:
        _refuse("The recording directory may not redirect to another location.", "RECORDING_PATH_REFUSED")
    return root


def _files(root: Path):
    if not root.exists():
        return []
    result = []
    with os.scandir(root) as entries:
        for index, entry in enumerate(entries):
            if index >= 4096:
                _refuse("Recording directory enumeration budget exceeded.", "RECORDING_BUDGET")
            match = FILENAME.fullmatch(entry.name)
            if not match:
                continue
            path = root / entry.name
            if path.is_symlink() or path.resolve(strict=True) != path or not entry.is_file(follow_symlinks=False):
                _refuse("A recording path redirects or is not a regular file.", "RECORDING_PATH_REFUSED")
            stat = entry.stat(follow_symlinks=False)
            if stat.st_size > MAX_FILE:
                _refuse("A recording exceeds its fixed 32 MiB bound.", "RECORDING_BUDGET")
            result.append({"recordingId": match[1], "bytes": stat.st_size, "modifiedMillis": stat.st_mtime_ns // 1_000_000})
            if len(result) > MAX_FILES:
                _refuse("Recording directory file count exceeds its bound.", "RECORDING_BUDGET")
    return sorted(result, key=lambda item: (item["modifiedMillis"], item["recordingId"]), reverse=True)


def _invalid_constant(_value):
    raise ValueError("Non-finite JSON")


def _event(line: bytes, recording_id: str):
    try:
        event = json.loads(line, parse_constant=_invalid_constant)
        if (not isinstance(event, dict) or event.get("recordingId") != recording_id
                or type(event.get("seq")) is not int or event["seq"] < 1
                or type(event.get("timeMillis")) is not int or not 0 <= event["timeMillis"] <= 253402300799999
                or not isinstance(event.get("sessionId"), str) or not 1 <= len(event["sessionId"]) <= 128
                or not isinstance(event.get("kind"), str) or not 1 <= len(event["kind"]) <= 128
                or not isinstance(event.get("source"), str) or not 1 <= len(event["source"]) <= 128
                or not isinstance(event.get("details"), dict)):
            raise ValueError("Invalid event envelope")
        # Reject huge exponents decoded as infinity, including inside nested details.
        json_size(event)
        return event
    except (ValueError, UnicodeError, RecursionError, OverflowError):
        _refuse("A complete recording line has invalid JSON, identity or event fields; no successful summary returned.")


def _short(event, details):
    return {"seq": event["seq"], "timeMillis": event["timeMillis"], "kind": event["kind"],
            "source": event["source"], "details": bounded_value(details, 1024)}


class Summary:
    def __init__(self, limit):
        self.counts = Counter()
        self.sources = Counter()
        self.first = self.last = None
        self.movement_count = 0
        self.first_pose = self.last_pose = None
        self.bounds = {}
        self.highlights = []
        self.highlight_limit = limit
        self.highlights_omitted = 0
        self.highlight_bytes = 0
        self.transitions = {}
        self.unlisted_changes = 0
        self.slot_deltas = Counter()
        self.slot_baselines = Counter()
        self.slot_unknown = Counter()

    def add(self, event):
        kind, source, detail = event["kind"], event["source"], event["details"]
        # Bound cardinality even for a locally altered file.
        if len(self.counts) >= 128 and kind not in self.counts or len(self.sources) >= 128 and source not in self.sources:
            _refuse("Event category budget exceeded.", "RECORDING_BUDGET")
        self.counts[kind] += 1
        self.sources[source] += 1
        if self.first is None:
            self.first = event["timeMillis"]
        self.last = event["timeMillis"]
        if kind == "movement_sample":
            self.movement_count += 1
            pose = detail.get("pose", {})
            if isinstance(pose, dict):
                pose = {key: value for key, value in pose.items() if key in {
                    "dimension", "x", "y", "z", "yaw", "pitch", "onGround", "flying", "creative", "sneaking", "selectedHotbarSlot"}}
                self.first_pose = self.first_pose or bounded_value(pose, 1024)
                self.last_pose = bounded_value(pose, 1024)
                dimension = pose.get("dimension")
                if type(dimension) is int and all(type(pose.get(key)) in (int, float) and math.isfinite(pose[key]) for key in ("x", "y", "z")):
                    if dimension not in self.bounds and len(self.bounds) >= 8:
                        _refuse("Movement dimension budget exceeded.", "RECORDING_BUDGET")
                    box = self.bounds.setdefault(dimension, {key: [pose[key], pose[key]] for key in ("x", "y", "z")})
                    for key in box:
                        box[key] = [min(box[key][0], pose[key]), max(box[key][1], pose[key])]
        elif kind == "gui_slots_observed":
            if detail.get("baseline") is True:
                self.slot_baselines[source] += 1
            elif detail.get("baseline") is False:
                self.slot_deltas[source] += 1
            else:
                self.slot_unknown[source] += 1
        if kind == "block_state_observed" and source == "server_world" and (detail.get("blockIdentityChanged") is True or detail.get("aePartsChanged") is True):
            transition = {key: detail[key] for key in ("dimension", "x", "y", "z", "before", "after") if key in detail}
            # Collapse identical sampled transitions, never call this a count of player actions.
            signature = json.dumps(transition, sort_keys=True, ensure_ascii=False, allow_nan=False)
            if signature in self.transitions:
                self.transitions[signature]["observations"] += 1
                self.transitions[signature]["lastSeq"] = event["seq"]
                return
            if len(self.transitions) < 32:
                self.transitions[signature] = {"firstSeq": event["seq"], "lastSeq": event["seq"],
                    "observations": 1, **{k: bounded_value(v, 1024) for k, v in transition.items()}}
            else:
                self.unlisted_changes += 1
        elif kind not in {"command_submitted", "chat_feedback", "gui_changed", "gui_button", "gui_mouse_input", "gui_key_input", "recording_stopped"}:
            return
        entry = _short(event, detail)
        cost = json_size(entry)
        if len(self.highlights) < self.highlight_limit and self.highlight_bytes + cost <= 16 * 1024:
            self.highlights.append(entry)
            self.highlight_bytes += cost
        else:
            self.highlights_omitted += 1

    def result(self):
        return {"eventCounts": dict(self.counts), "sourceCounts": dict(self.sources),
            "firstTimeMillis": self.first, "lastTimeMillis": self.last,
            "movement": {"samples": self.movement_count, "first": self.first_pose, "last": self.last_pose,
                         "boundsByDimension": self.bounds},
            "guiSlots": {"baselineObservations": dict(self.slot_baselines), "deltaObservations": dict(self.slot_deltas),
                         "unknownObservations": dict(self.slot_unknown)},
            "serverBlockChanges": {"distinctListedTransitions": len(self.transitions), "entries": list(self.transitions.values()),
                "unlistedObservations": self.unlisted_changes, "complete": self.unlisted_changes == 0,
                "scope": "Identical before/after observations grouped by position; not a count of player actions; causation unproven."},
            "highlights": self.highlights, "highlightsOmitted": self.highlights_omitted,
            "highlightsComplete": self.highlights_omitted == 0}


def read_recording(configuration: Configuration, arguments: dict):
    """No game RPC, input, directory creation or recording mutation."""
    try:
        return _read(configuration, arguments)
    except OSError:
        _refuse("The selected fixed recording could not be read. Files were not changed.", "RECORDING_UNAVAILABLE")
    except (ValueError, TypeError, OverflowError, RecursionError):
        _refuse("Recording data exceeds the supported typed observation format.")


def _read(configuration, args):
    root = _root(configuration)
    files = _files(root)
    view = args["view"]
    limit = args.get("limit", 32)
    base = {"readerVersion": 1, "source": "instance_recording_file", "instancePath": str(root.parent.parent),
            "directory": str(root), "liveStateObserved": False, "automaticReplayAllowed": False}
    if view == "list":
        offset = args.get("offset", 0)
        return {**base, "recordings": files[offset:offset + limit], "total": len(files),
                "nextOffset": min(offset + limit, len(files)), "hasMore": offset + limit < len(files)}
    recording_id = args.get("recordingId", files[0]["recordingId"] if files else "")
    if not re.fullmatch(UUID, recording_id):
        _refuse("Select a recording UUID from view=list; no recording is available or the identity is invalid.", "RECORDING_NOT_FOUND")
    if not any(item["recordingId"] == recording_id for item in files):
        _refuse("The selected recording is not present in this instance.", "RECORDING_NOT_FOUND")
    path = root / ("recording-" + recording_id + ".jsonl")
    since, kinds = args.get("since", 0), set(args.get("kinds", []))
    summary = Summary(limit)
    page, page_bytes, next_since, page_full = [], 0, since, False
    last_seq, session_id, read_bytes, lines, omitted = 0, None, 0, 0, 0
    gaps, gap_count, missing, incomplete_tail = [], 0, 0, 0
    terminal = None
    stopped = started = False
    deadline = time.monotonic() + 8
    if path.is_symlink() or path.resolve(strict=True) != path:
        _refuse("Recording path changed to a redirect.", "RECORDING_PATH_REFUSED")
    expected = path.stat()
    with path.open("rb") as stream:
        initial = os.fstat(stream.fileno())
        if (expected.st_dev, expected.st_ino) != (initial.st_dev, initial.st_ino):
            _refuse("Recording file identity changed before reading.", "RECORDING_CHANGED")
        if initial.st_size > MAX_FILE:
            _refuse("Recording exceeded its read budget.", "RECORDING_BUDGET")
        while read_bytes < initial.st_size:
            if time.monotonic() > deadline:
                _refuse("Recording scan deadline exceeded; no partial scan presented as complete.", "RECORDING_BUDGET")
            line = stream.readline(min(MAX_LINE + 1, initial.st_size - read_bytes))
            if not line:
                _refuse("Recording shrank during the read.", "RECORDING_CHANGED")
            read_bytes += len(line)
            if len(line) > MAX_LINE:
                _refuse("Recording event exceeds its 16 KiB bound.", "RECORDING_BUDGET")
            if not line.endswith(b"\n"):
                incomplete_tail = len(line)
                break
            event = _event(line, recording_id)
            if stopped or event["seq"] <= last_seq:
                _refuse("Recording contains duplicate/reversed sequences or events after its stop marker.")
            if session_id is None:
                session_id = event["sessionId"]
                started = event["seq"] == 1 and event["kind"] == "recording_started"
            if event["sessionId"] != session_id:
                _refuse("A recording contains mixed client sessions.")
            if event["seq"] != last_seq + 1:
                gap_count += 1
                missing += event["seq"] - last_seq - 1
                if len(gaps) < 16: gaps.append({"firstMissingSeq": last_seq + 1, "lastMissingSeq": event["seq"] - 1})
            last_seq = event["seq"]
            lines += 1
            omitted += event["details"].get("payloadOmitted") is True
            if event["kind"] == "recording_stopped":
                terminal = bounded_value(event["details"].get("reason"), 512)
                stopped = True
            if last_seq <= since:
                continue
            selected = not kinds or event["kind"] in kinds
            if view == "summary":
                if selected: summary.add(event)
            elif not page_full:
                cost = json_size(event)
                if selected and (len(page) >= limit or page_bytes + cost > PAGE_BYTES):
                    page_full = True
                    continue
                next_since = last_seq
                if selected:
                    page.append(event)
                    page_bytes += cost
        final = os.fstat(stream.fileno())
        current = path.stat()
        if ((initial.st_dev, initial.st_ino) != (current.st_dev, current.st_ino)
                or final.st_size < initial.st_size
                or final.st_size == initial.st_size and final.st_mtime_ns != initial.st_mtime_ns):
            _refuse("Recording changed incompatibly during its snapshot read.", "RECORDING_CHANGED")
    if since > last_seq:
        _refuse("since exceeds this recording's last complete on-disk sequence.", "RECORDING_CURSOR")
    result = {**base, "recordingId": recording_id, "sessionId": session_id, "path": str(path),
        "snapshotBytes": initial.st_size, "grewDuringRead": final.st_size > initial.st_size,
        "latestOnDiskSeq": last_seq, "recordedStopReason": terminal,
        "recordingState": "stopped" if stopped else "unknown",
        "integrity": {"scope": "entire_complete_line_prefix_of_snapshot", "completeLines": lines,
            "startMarkerPresent": started,
            "sequenceContinuous": gap_count == 0, "gapCount": gap_count, "missingEvents": missing,
            "gaps": gaps, "gapsTruncated": gap_count > len(gaps), "payloadOmissions": omitted,
            "incompleteTailBytes": incomplete_tail, "snapshotComplete": incomplete_tail == 0},
        "window": {"afterSeq": since, "throughSeq": last_seq, "kinds": sorted(kinds)}, "limitations": LIMITATIONS}
    if view == "summary":
        result["summary"] = summary.result()
        result["nextSince"] = last_seq
        # Only presentation lists are shortened; counts and integrity cover the declared window.
        while json_size(result) > SUMMARY_BYTES:
            if result["summary"]["highlights"]:
                result["summary"]["highlights"].pop()
                result["summary"]["highlightsOmitted"] += 1
                result["summary"]["highlightsComplete"] = False
            elif result["summary"]["serverBlockChanges"]["entries"]:
                changes = result["summary"]["serverBlockChanges"]
                removed = changes["entries"].pop()
                changes["distinctListedTransitions"] -= 1
                changes["unlistedObservations"] += removed["observations"]
                changes["complete"] = False
            else:
                _refuse("Summary exceeds its presentation budget.", "RECORDING_BUDGET")
    else:
        result.update(events=page, nextSince=next_since, hasMore=page_full)
    return result
