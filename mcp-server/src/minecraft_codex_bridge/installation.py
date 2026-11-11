"""Small standard-library installation primitives; no game or MCP imports."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


class InstallError(ValueError):
    pass


def atomic_write(path: Path, content: bytes, *, expected: bytes | None = None) -> bool:
    old = path.read_bytes() if path.exists() else None
    if expected is not None and old != expected:
        raise InstallError("File changed during installation: " + str(path))
    if old == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    if old is not None:
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        backup = path.with_name(path.name + '.' + stamp + '.bak')
        backup.write_bytes(old)
    fd, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if (path.read_bytes() if path.exists() else None) != old:
            raise InstallError("File changed during installation: " + str(path))
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return True


def validate_instance(path: Path) -> Path:
    path = path.resolve(strict=True)
    try:
        pack = json.loads((path / 'mmc-pack.json').read_text(encoding='utf-8-sig'))
        minecraft = [item for item in pack['components'] if item.get('uid') == 'net.minecraft']
        config = (path / 'instance.cfg').read_text(encoding='utf-8-sig')
        if (pack.get('formatVersion') != 1 or len(minecraft) != 1 or minecraft[0].get('version') != '1.7.10'
                or not re.search(r'^InstanceType=OneSix\s*$', config, re.MULTILINE)
                or not (path / '.minecraft/mods').is_dir()):
            raise ValueError()
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise InstallError("Select an existing Minecraft 1.7.10 Prism/Forge instance: " + str(path)) from error
    return path


def validate_jar(path: Path) -> Path:
    path = path.resolve(strict=True)
    if not re.fullmatch(r'(?:BridgeNH|minecraft-codex-bridge)-[a-zA-Z0-9.+_-]+\.jar', path.name) or re.search(r'-(dev|sources)\.jar$', path.name):
        raise InstallError('Expected the MCB runtime JAR.')
    try:
        with zipfile.ZipFile(path) as archive:
            entry = archive.getinfo('mcmod.info')
            if entry.file_size > 65536:
                raise ValueError()
            info = json.loads(archive.read(entry))
            if not isinstance(info, list) or not any(mod.get('modid') == 'codexbridge' and mod.get('mcversion') == '1.7.10' for mod in info):
                raise ValueError()
            entrypoints = ('com/Goderium/BridgeNH/BridgeNH.class', 'dev/codex/minecraftbridge/CodexBridgeMod.class')
            if not any(entrypoint in archive.namelist() for entrypoint in entrypoints):
                raise ValueError()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise InstallError('JAR does not identify the Minecraft 1.7.10 MCB mod.') from error
    return path


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def assert_instance_stopped(instance: Path):
    if os.name != 'nt':
        raise InstallError('Automatic mod deployment currently requires Windows; deploy the JAR manually on this OS.')
    command = "$ErrorActionPreference='Stop'; @(Get-CimInstance Win32_Process -Filter \"Name = 'javaw.exe' OR Name = 'java.exe'\" | Select-Object ProcessId,CommandLine) | ConvertTo-Json -Compress"
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', command],
                            capture_output=True, text=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise InstallError('Cannot inspect Java processes; target shutdown cannot be verified.')
    processes = json.loads(result.stdout or '[]')
    if isinstance(processes, dict):
        processes = [processes]
    pattern = re.compile(re.escape(str(instance).replace('\\', '/').casefold()) + r'(?=[/\s"\x27]|$)')
    for process in processes:
        line = process.get('CommandLine')
        if not line:
            raise InstallError('A Java command line is unreadable; target shutdown cannot be verified.')
        if pattern.search(line.replace('\\', '/').casefold()):
            raise InstallError('Exit only the selected Minecraft instance, then rerun installation.')


def mod_plan(jar: Path, instance: Path) -> tuple[Path, list[Path], bool]:
    jar, instance = validate_jar(jar), validate_instance(instance)
    mods = instance / '.minecraft/mods'
    existing = sorted({path for pattern in ('BridgeNH-*.jar', 'minecraft-codex-bridge-*.jar') for path in mods.glob(pattern)})
    for old in existing:
        validate_jar(old)
    target = mods / jar.name
    same = existing == [target] and digest(jar) == digest(target)
    return target, existing, same


def deploy_mod(jar: Path, instance: Path) -> dict:
    target, existing, same = mod_plan(jar, instance)
    if same:
        return {'path': str(target), 'changed': False, 'sha256': digest(target)}
    assert_instance_stopped(instance.resolve())
    backup_dir = instance.resolve() / '.minecraft/config/codexbridge/mod-backups'
    backups = []
    for old in existing:
        backup_dir.mkdir(parents=True, exist_ok=True)
        # Keep backups short enough for legacy Windows MAX_PATH installs.
        backup = backup_dir / (digest(old)[:16] + '.jar.bak')
        if not backup.exists():
            shutil.copy2(old, backup)
        if digest(backup) != digest(old):
            raise InstallError('Mod backup verification failed.')
        backups.append((old, backup))
    fd, temporary = tempfile.mkstemp(prefix='mcb-install-', suffix='.tmp', dir=target.parent)
    os.close(fd)
    try:
        shutil.copyfile(jar, temporary)
        if digest(Path(temporary)) != digest(jar):
            raise InstallError('Mod copy verification failed.')
        assert_instance_stopped(instance.resolve())
        os.replace(temporary, target)
        for old, _ in backups:
            if old != target:
                old.unlink()
    except BaseException:
        for old, backup in backups:
            shutil.copy2(backup, old)
        if target not in existing and target.exists():
            target.unlink()
        raise
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return {'path': str(target), 'changed': True, 'sha256': digest(target), 'backups': [str(b) for _, b in backups]}


def toml_data(content: bytes) -> dict:
    try:
        import tomllib
        return tomllib.loads(content.decode('utf-8-sig'))
    except ImportError as error:
        raise InstallError('Installer requires Python 3.11 or newer (MCP runtime supports 3.10+).') from error
    except (UnicodeError, ValueError) as error:
        raise InstallError('Existing Codex configuration is not valid TOML; it was not modified.') from error


def _toml_value(value):
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if type(value) is bool:
        return 'true' if value else 'false'
    if type(value) in (int, float):
        return repr(value)
    if isinstance(value, list):
        return '[' + ', '.join(_toml_value(item) for item in value) + ']'
    if isinstance(value, dict):
        return '{' + ', '.join(json.dumps(k) + ' = ' + _toml_value(v) for k, v in value.items()) + '}'
    raise InstallError('Unsupported value in existing MCB registration; configuration was not modified.')


def codex_registration(content: bytes, python: Path, config: Path, profile: str, server_name=None) -> tuple[bytes, str]:
    data = toml_data(content)
    servers = data.get('mcp_servers', {})
    matches = [name for name, entry in servers.items() if name in ('mcb', 'minecraft_codex_bridge') or 'minecraft_codex_bridge' in str(entry.get('args', [])) or 'Minecraft-Codex-Bridge' in str(entry.get('args', []))]
    if len(matches) > 1 or server_name and matches and matches != [server_name]:
        raise InstallError('Multiple/conflicting MCB registrations exist; choose and consolidate them before installation.')
    name = server_name or (matches[0] if matches else 'mcb')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', name):
        raise InstallError('Unsupported MCP registration name.')
    entry = deepcopy(servers.get(name, {}))
    for field in ('url', 'bearer_token_env_var', 'http_headers', 'env_http_headers'):
        entry.pop(field, None)
    entry.update(command=str(python.resolve()), args=['-m', 'minecraft_codex_bridge'])
    entry.setdefault('startup_timeout_sec', 30)
    entry.setdefault('tool_timeout_sec', 70)
    env = entry.setdefault('env', {})
    # Stale direct selectors must not silently defeat the newly selected profile.
    for field in ('MCB_INSTANCE_PATH', 'MCB_RUNTIME_FILE', 'MCB_URL', 'MCB_TOKEN', 'MCB_PRISM_EXE', 'MCB_INSTANCE_ROOT', 'MCB_PRISM_ROOT'):
        env.pop(field, None)
    env.update(MCB_CONFIG_FILE=str(config.resolve()), MCB_PROFILE=profile, PYTHONUTF8='1')
    text = content.decode('utf-8-sig')
    lines = text.splitlines(keepends=True)
    kept, removing, found = [], False, False
    for line in lines:
        if re.match(r'^\s*\[', line):
            header = re.match(r'^\s*\[([^\]]+)\]\s*(?:#.*)?$', line.strip())
            removing = False
            if header:
                # Parse the table header itself, avoiding quoted-key heuristics.
                try:
                    table = toml_data((header.group(0) + '\n').encode())
                    removing = name in table.get('mcp_servers', {})
                except InstallError:
                    pass
            found = found or removing
        if not removing:
            kept.append(line)
    if name in servers and not found:
        raise InstallError('MCB uses an inline/dotted TOML layout; migrate it to a [mcp_servers.NAME] table first.')
    block = '\n[mcp_servers.' + name + ']\n' + ''.join(json.dumps(key) + ' = ' + _toml_value(value) + '\n' for key, value in entry.items())
    result = (''.join(kept).rstrip() + '\n' + block).encode('utf-8')
    expected = deepcopy(data)
    expected.setdefault('mcp_servers', {})[name] = entry
    if toml_data(result) != expected:
        raise InstallError('Cannot safely preserve the existing Codex configuration; nothing was written.')
    # Preserve the original bytes when the registration already matches.
    return (content if data == expected else result), name
