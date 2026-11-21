"""Cooperative execution of independently verifiable segments through the native sequence runner."""
from copy import deepcopy
from uuid import uuid4

from .bridge import BridgeError
from .conveniences import json_size
from .validation import validate_tool_arguments
from .workflows import _background, validate_plan


def refuse(code, message):
    raise BridgeError(code, message, original_action_executed=False, outcome_unknown=False)


class ActionChain:
    def __init__(self):
        self.id = None
        self.revision = 0
        self.status = "idle"
        self.pending = []
        self.completed = []
        self.current = None
        self.last = None
        self.started = False

    def checkpoint(self):
        if self.id is None:
            return None
        if self.current is not None or self.status == "running":
            refuse("CHAIN_BUSY", "Wait for the current segment to reach a boundary.")
        return deepcopy({**self.view(), "pending": self.pending, "started": self.started,
                         "session": self.session, "world": self.world, "dimension": self.dimension})

    @classmethod
    def restore(cls, state):
        import re
        result = cls()
        if state is None:
            return result
        if (not isinstance(state, dict) or set(state) != {"chainId", "revision", "status", "current", "completed",
                "pending", "last", "started", "session", "world", "dimension"} or state["current"] is not None
                or not isinstance(state["chainId"], str) or not re.fullmatch(r"[a-f0-9]{32}", state["chainId"]) or type(state["revision"]) is not int
                or state["revision"] < 0 or state["status"] not in {"ready", "blocked", "completed", "cancelled"}
                or type(state["started"]) is not bool or type(state["completed"]) is not list
                or type(state["pending"]) is not list or len(state["completed"]) > 16
                or not all(isinstance(key, str) and len(key) <= 32 for key in state["completed"])
                or len(set(state["completed"])) != len(state["completed"])
                or (state["status"] == "ready") != bool(state["pending"]) and state["status"] != "blocked"):
            raise ValueError("Invalid action chain checkpoint")
        result.id, result.revision, result.status = state["chainId"], state["revision"], state["status"]
        result.session, result.world, result.dimension = state["session"], state["world"], state["dimension"]
        result.started, result.completed, result.last = state["started"], list(state["completed"]), deepcopy(state["last"])
        if state["pending"]:
            validate_tool_arguments("mcb_action_chain", {"action": "open", "expectedSessionId": result.session,
                "expectedWorld": result.world, "dimension": result.dimension, "segments": state["pending"]})
            result.pending = result.segments(state["pending"], result.session, result.world)
        return result

    def view(self):
        return {"chainId": self.id, "revision": self.revision, "status": self.status,
                "current": self.current, "completed": list(self.completed),
                "pending": [group["id"] for group in self.pending], "last": self.last}

    def segments(self, groups, session, world):
        ids = {group["id"] for group in groups}
        existing = {group["id"] for group in self.pending} | set(self.completed)
        if self.current:
            existing.add(self.current)
        if len(ids) != len(groups) or ids & existing or len(ids | existing) > 16:
            refuse("CHAIN_SEGMENTS", "Use at most 16 distinct segment IDs per chain.")
        if json_size(self.pending + groups) > 65536:
            refuse("PLAN_TOO_LARGE", "Pending chain segments exceed 64 KiB.")
        for group in groups:
            plan = {"expectedSessionId": session, "expectedWorld": world, "steps": group["steps"]}
            validate_tool_arguments("mcb_sequence", plan)
            steps = validate_plan(plan, False)
            actions = {step["id"] for step in steps if step["kind"] == "action"}
            accepted = {key for step in steps if step["kind"] in {"assert", "wait"} for key in step.get("accepts", [])}
            if actions - accepted:
                refuse("CHAIN_ACCEPTANCE_REQUIRED", "Every action needs a later acceptance condition in its own segment.")
        return deepcopy(groups)

    def check(self, args):
        if self.id is None or self.id != args["chainId"] or args["expectedRevision"] != self.revision:
            refuse("CHAIN_CHANGED", "Read current chain status before editing or advancing.")
        if self.status != "ready" and not (self.status == "blocked" and args["action"] == "cancel"):
            refuse("CHAIN_NOT_READY", "Only a chain at a verified boundary can advance or accept an insertion.")

    async def call(self, args, client, lock, runner):
        action = args.get("action", "status")
        if action == "status":
            return self.view()
        if action == "open":
            if self.status in {"ready", "running"}:
                refuse("CHAIN_BUSY", "Finish or cancel the current chain first.")
            replacement = ActionChain()
            replacement.session = args["expectedSessionId"]
            replacement.world = args["expectedWorld"]
            replacement.dimension = args["dimension"]
            replacement.pending = replacement.segments(args["segments"], replacement.session, replacement.world)
            replacement.id = uuid4().hex
            replacement.status = "ready"
            self.__dict__.update(vars(replacement))
            return self.view()
        self.check(args)
        if action == "insert":
            self.pending[:0] = self.segments(args["segments"], self.session, self.world)
            self.revision += 1
            return self.view()
        if action == "cancel":
            self.pending.clear()
            self.status = "cancelled"
            self.revision += 1
            return self.view()

        # Reserve the boundary before any await. A second caller cannot dispatch it.
        group = self.pending[0]
        self.status = "running"
        self.revision += 1
        self.current = group["id"]
        runner_started = False
        try:
            snapshot = await client.invoke("client.snapshot", {})
            target = snapshot.get("lifecycleTarget") or {}
            if (snapshot.get("sessionId") != self.session or target.get("expectedWorld") != self.world
                    or not snapshot.get("worldLoaded") or (snapshot.get("world") or {}).get("dimension") != self.dimension):
                refuse("CHAIN_WORLD_CHANGED", "The original session, world or dimension no longer matches.")
            if self.started and not _background(snapshot.get("background")):
                refuse("CHAIN_CONTROL_RELEASED", "Control was returned to the player. Resume with a fresh plan.")
            native = snapshot.get("inputTask")
            if not isinstance(native, dict) or native.get("status") not in {"active", "idle"}:
                refuse("CHAIN_INPUT_STATE_UNAVAILABLE", "The bridge did not identify the current native input owner.")
            if native["status"] == "active":
                self.status = "ready"
                self.current = None
                return {**self.view(), "waitingForNativeTask": native.get("taskId"), "dispatched": False}
            plan = {"expectedSessionId": self.session, "expectedWorld": self.world, "steps": group["steps"]}
            runner_started = True
            report = await runner(client, plan, lock, _require_background=self.started, _expected_dimension=self.dimension)
            self.last = {"segment": group["id"], "report": report}
            if report.get("status") != "completed" or not report.get("checkpoint", {}).get("effectAcceptanceComplete"):
                self.status = "blocked"
            else:
                self.started = self.started or report.get("dispatchedActions", 0) > 0
                self.completed.append(group["id"])
                self.pending.pop(0)
                self.status = "ready" if self.pending else "completed"
        except BaseException as error:
            self.status = "blocked"
            detail = error.as_dict() if isinstance(error, BridgeError) else {"code": "CHAIN_INTERRUPTED"}
            self.last = {"segment": group["id"], "report": {"status": "failed", "error": detail,
                "outcomeUnknown": runner_started and detail.get("outcome_unknown", True),
                "checkpoint": {"effectAcceptanceComplete": False}}}
            raise
        finally:
            self.current = None
        return self.view()
