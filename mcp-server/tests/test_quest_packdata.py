import json
import os
import sys
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge import quest_packdata


BOOK = Path("config/betterquesting/DefaultQuests")
LANG = Path("config/txloader/forceload/betterquesting/lang")
TRANSLATION = "betterquesting.quest.AAAAAAAAAAAAAAAAAAAAAQ.name=测试样本\n"


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")


@pytest.fixture
def game(tmp_path):
    root = tmp_path / "example game"
    write(root / BOOK / "QuestSettings.json", {})
    write(root / BOOK / "QuestLinesOrder.txt", "AAAAAAAAAAAAAAAAAAAAAA==: Example\n")
    write(root / BOOK / "Quests/test/one.json", {
        "questIDLow:4": 1, "questIDHigh:4": 0,
        "properties:10": {"betterquesting:10": {"name:8": "Example", "desc:8": "Sample."}},
        "tasks:9": {"0:10": {"taskID:8": "bq_standard:retrieval", "requiredItems:9": {
            "0:10": {"id:8": "example:widget", "Count:3": 7, "Damage:2": 3}}}},
    })
    write(root / BOOK / "QuestLines/test/QuestLine.json", {
        "questLineIDLow:4": 0, "questLineIDHigh:4": 0,
        "properties:10": {"betterquesting:10": {"name:8": "Example chapter"}},
    })
    write(root / BOOK / "QuestLines/test/one.json", {"questIDLow:4": 1, "questIDHigh:4": 0})
    write(root / LANG / "zh_CN.lang", TRANSLATION)
    return root


def test_archive_contains_only_selected_static_inputs(game, tmp_path, monkeypatch):
    for unwanted in ("accounts.json", "saves/example/betterquesting/QuestProgress.json",
                     "config/betterquesting/QuestProgress.json", BOOK / "other.json",
                     BOOK / "Quests/test/one.json.bak", LANG / "en_US.lang"):
        write(game / unwanted, "unused")
    exporter = tmp_path / "external tool.py"
    exporter.touch()
    output = tmp_path / "output"
    archives = []

    def inspect(command, **kwargs):
        assert command[:6] == [sys.executable, "-I", "-X", "utf8", str(exporter), "quests"]
        options = dict(zip(command[6::2], command[7::2]))
        assert options["--lang"] == "zh_CN" and Path(options["--out"]) == output
        archive = Path(options["--client"])
        archives.append(archive)
        with zipfile.ZipFile(archive) as pack:
            assert set(pack.namelist()) == {path.as_posix() for path in (
                BOOK / "QuestSettings.json", BOOK / "QuestLinesOrder.txt", BOOK / "Quests/test/one.json",
                BOOK / "QuestLines/test/QuestLine.json", BOOK / "QuestLines/test/one.json", LANG / "zh_CN.lang",
            )}
            assert pack.read((LANG / "zh_CN.lang").as_posix()) == (game / LANG / "zh_CN.lang").read_bytes()
        write(output / "meta.json", {"quests": {"format": 1, "quests": 1}})
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(quest_packdata.subprocess, "run", inspect)
    assert quest_packdata.export_quests(game, output, exporter) == {"format": 1, "quests": 1}
    assert len(archives) == 1 and not archives[0].exists()


def test_exporter_failure_reports_diagnostic(game, tmp_path):
    exporter = tmp_path / "failing exporter.py"
    exporter.write_text("import sys\nprint('invalid quest', file=sys.stderr)\nsys.exit(4)\n", encoding="utf-8")
    output = tmp_path / "output"
    with pytest.raises(RuntimeError, match=r"Quest export failed \(4\): invalid quest"):
        quest_packdata.export_quests(game, output, exporter)
    assert not output.exists()


def test_rejects_input_as_output_and_language_traversal(game, tmp_path):
    exporter = tmp_path / "exporter.py"
    exporter.touch()
    with pytest.raises(ValueError, match="outside the game"):
        quest_packdata.export_quests(game, game / "nested", exporter)
    with pytest.raises(ValueError, match="language"):
        quest_packdata.export_quests(game, tmp_path / "out", exporter, language="../../user")


def test_static_directory_link_cannot_include_world_files(game, tmp_path):
    world = game / "saves/example"
    write(world / "progress.json", {})
    link = game / BOOK / "Quests/external"
    try:
        link.symlink_to(world, target_is_directory=True)
    except OSError as error:
        if sys.platform != "win32":
            raise
        # On Windows junctions do not require the symlink privilege.
        import _winapi
        try:
            _winapi.CreateJunction(str(world), str(link))
        except OSError:
            pytest.skip(f"directory link unavailable: {error}")
    exporter = tmp_path / "exporter.py"
    exporter.touch()
    with pytest.raises(ValueError, match="Linked static input"):
        quest_packdata.export_quests(game, tmp_path / "out", exporter)


@pytest.mark.skipif(not os.environ.get("MCB_PACKDATA_EXPORTER"), reason="supply a trusted upstream exporter")
@pytest.mark.parametrize("language, expected_name, translated", [("zh_CN", "测试样本", 1), ("en_US", "Example", 0)])
def test_upstream_exporter_on_synthetic_book(game, tmp_path, language, expected_name, translated):
    out = tmp_path / "packdata"
    write(out / "meta.json", {"guide": {"format": 1}})
    summary = quest_packdata.export_quests(game, out, Path(os.environ["MCB_PACKDATA_EXPORTER"]), language)
    assert (summary["quests"], summary["lines"], summary["lang"], summary["translated"]) == (1, 1, language, translated)
    assert json.loads((out / "meta.json").read_text(encoding="utf-8"))["guide"] == {"format": 1}
    quest = json.loads((out / "quests.jsonl").read_text(encoding="utf-8"))
    assert (quest["id"], quest["name"], quest["lines"]) == ("1", expected_name, ["0"])
    assert quest["tasks"] == [{"type": "retrieval", "items": [{"id": "example:widget", "meta": 3, "count": 7}]}]
    chapters = json.loads((out / "questlines.json").read_text(encoding="utf-8"))["lines"]
    assert [(c["id"], c["name"], c["quests"]) for c in chapters] == [("0", "Example chapter", ["1"])]
