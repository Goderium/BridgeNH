from pathlib import Path


def pytest_configure(config):
    if config.option.basetemp is None:
        target = Path(__file__).resolve().parents[1] / "build" / "tmp" / "agents-mcp-tests"
        target.parent.mkdir(parents=True, exist_ok=True)
        config.option.basetemp = str(target)
