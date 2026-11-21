"""Run the upstream PACKDATA quest exporter on an unpacked GTNH installation."""

from __future__ import annotations

import json
import re
import stat
import subprocess
import sys
import tempfile
import zipfile
from collections.abc import Iterator
from pathlib import Path


_BOOK = Path("config/betterquesting/DefaultQuests")
_LANG = Path("config/txloader/forceload/betterquesting/lang")


def _mode(path: Path) -> int:
    info = path.lstat()
    if (stat.S_ISLNK(info.st_mode)
            or getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT):
        raise ValueError(f"Linked static input is unsupported: {path}")
    return info.st_mode


def _static_path(game_dir: Path, relative: Path) -> Path:
    path = game_dir
    for part in relative.parts:
        path = path / part
        _mode(path)
    return path


def _json_files(folder: Path) -> Iterator[Path]:
    for path in sorted(folder.iterdir()):
        mode = _mode(path)
        if stat.S_ISDIR(mode):
            yield from _json_files(path)
        elif stat.S_ISREG(mode) and path.suffix == ".json":
            yield path


def _quest_sources(game_dir: Path, language: str) -> Iterator[Path]:
    book = _static_path(game_dir, _BOOK)
    yield _static_path(book, Path("QuestSettings.json"))
    for name in ("Quests", "QuestLines"):
        yield from _json_files(_static_path(book, Path(name)))
    for optional in (_BOOK / "QuestLinesOrder.txt", _LANG / (language + ".lang")):
        try:
            path = _static_path(game_dir, optional)
        except FileNotFoundError:
            continue
        yield path


def export_quests(game_dir: Path, output_dir: Path, exporter: Path, language: str = "zh_CN") -> dict:
    """Export quests.jsonl and questlines.json; return meta.json's upstream quests section.

    Supply the unpacked .minecraft directory, an export staging directory outside
    that installation, and a trusted local copy of alu_gtnh_aiplayer's make-packdata.py.
    Other PACKDATA sections can use the same staging directory in sequence.
    All localization, ID normalization and task/reward processing belong to the exporter.
    """
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", language):
        raise ValueError("Expected a language name such as zh_CN")
    game_dir = Path(game_dir).resolve(strict=True)
    output_dir = Path(output_dir).resolve()
    exporter = Path(exporter).resolve(strict=True)
    if not exporter.is_file():
        raise ValueError("Expected a local make-packdata.py file")
    if output_dir.is_relative_to(game_dir):
        raise ValueError("Choose an output directory outside the game installation")

    with tempfile.TemporaryDirectory(prefix="mcb-quest-packdata-") as directory:
        archive = Path(directory) / "betterquesting-static.zip"
        with zipfile.ZipFile(archive, "w", strict_timestamps=False) as pack:
            for path in _quest_sources(game_dir, language):
                if not stat.S_ISREG(_mode(path)):
                    raise ValueError(f"Expected a regular static quest file: {path}")
                pack.write(path, path.relative_to(game_dir).as_posix())
        result = subprocess.run(
            [sys.executable, "-I", "-X", "utf8", str(exporter), "quests", "--client", str(archive),
             "--out", str(output_dir), "--lang", language],
            cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if result.returncode:
            raise RuntimeError(f"Quest export failed ({result.returncode}): "
                               + (result.stderr or result.stdout).strip()[-2000:])
    return json.loads((output_dir / "meta.json").read_text(encoding="utf-8"))["quests"]
