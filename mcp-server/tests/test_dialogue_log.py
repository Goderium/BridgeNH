import json
import uuid
import pytest
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.dialogue_log import DialogueLog
import minecraft_codex_bridge.dialogue_log as journal_module


def message(name="Alice"):
    return {"id": str(uuid.uuid4()), "playerUuid": str(uuid.uuid4()), "playerName": name, "permissionGroup": "user", "mode": "say", "text": "not copied to metadata log", "token": "never copied"}


def binding():
    return {k: str(uuid.uuid4()) for k in ("threadId", "sessionId", "leaseId")}


def test_persistent_mapping_keeps_metadata_without_content_or_credentials(tmp_path):
    path, state, first, second = tmp_path / "state.json", binding(), message(), message("Bob")
    log = DialogueLog(path, state)
    a, b = log.register(first, "server"), log.register(second, "private")
    assert a != b and log.register(first, "server") == a
    reopened = DialogueLog(path, state)
    assert reopened.resolve(a) == first["id"] and reopened.resolve(b) == second["id"]
    with reopened.connect() as db:
        metadata = json.loads(db.execute("SELECT metadata FROM messages WHERE ref=?", (a,)).fetchone()[0])
    assert metadata["playerUuid"] == first["playerUuid"] and metadata["threadId"] == state["threadId"]
    assert metadata["replyChannel"] == "server" and not {"text", "token"} & metadata.keys()
    assert metadata["permissionGroup"] == "user"
    assert metadata["senderKind"] == "player"


@pytest.mark.parametrize("changed", ["threadId", "sessionId", "leaseId"])
def test_reference_cannot_cross_any_binding(tmp_path, changed):
    path, state = tmp_path / "state.json", binding()
    ref = DialogueLog(path, state).register(message(), "server")
    with pytest.raises(BridgeError) as error:
        DialogueLog(path, {**state, changed: str(uuid.uuid4())}).resolve(ref)
    assert error.value.code == "DIALOGUE_REPLY_NOT_FOUND"


def test_log_bounds_and_stale_reference_never_fall_back_to_latest(tmp_path, monkeypatch):
    monkeypatch.setattr(journal_module, "MAX_MESSAGES", 3)
    monkeypatch.setattr(journal_module, "MAX_EVENTS", 5)
    log = DialogueLog(tmp_path / "state.json", binding())
    first = log.register(message(), "server")
    for _ in range(6):
        row = message()
        log.register(row, "server")
        for _ in range(6):
            assert log.record(row["id"], "delivered", turnId=str(uuid.uuid4()), token="not recorded", text="not recorded")
    with log.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 3
        assert db.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 5
        assert all("not recorded" not in data for data, in db.execute("SELECT metadata FROM events"))
        assert db.execute("PRAGMA auto_vacuum").fetchone()[0] == 1
    with pytest.raises(BridgeError) as error:
        log.resolve(first)
    assert error.value.code == "DIALOGUE_REPLY_NOT_FOUND"


def test_corrupt_log_returns_no_path_or_guessed_target(tmp_path):
    log = DialogueLog(tmp_path / "state.json", binding())
    log.path.write_bytes(b"not sqlite")
    with pytest.raises(BridgeError) as error:
        log.resolve("ab12cd34ef")
    assert error.value.code == "DIALOGUE_LOG_UNAVAILABLE"
    assert str(tmp_path) not in str(error.value)
