"""Inspectable, repeatable Windows installer. Inspect never changes files."""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from .installation import (InstallError, assert_instance_stopped, atomic_write, codex_registration,
                           deploy_mod, digest, mod_plan, toml_data, validate_instance)
from .local_config import (LOCAL_NAME, PROFILE_NAME, LocalConfigError, config_path, read_config,
                           resolve_path, selected_profile, source_root, user_config_path)
from .prism_instances import PrismInstanceError, list_prism_instances, prism_directory, resolve_prism_instance


def _question(key, title, choices=()):
    return {'key': key, 'question': title, 'choices': [str(choice) for choice in choices]}


def _single(paths):
    unique = sorted({path.resolve() for path in paths if path.is_file()})
    return unique[0] if len(unique) == 1 else None


def existing_codex_configs(home: Path, payload: Path | None) -> list[Path]:
    global_path = home / 'config.toml'
    data = toml_data(global_path.read_bytes()) if global_path.exists() else {}
    candidates = {global_path}
    for root in (Path.cwd(), payload):
        if root:
            candidates.update(parent / '.codex/config.toml' for parent in (root, *root.parents))
    # Codex already records known projects; do not scan drives for unrelated configuration.
    for project in list(data.get('projects', {}))[:256]:
        candidates.add(Path(project) / '.codex/config.toml')
    result = []
    for candidate in sorted(candidates):
        if not candidate.is_file():
            continue
        servers = toml_data(candidate.read_bytes()).get('mcp_servers', {})
        if any(name in ('mcb', 'minecraft_codex_bridge') or 'minecraft_codex_bridge' in str(entry.get('args', []))
               or 'Minecraft-Codex-Bridge' in str(entry.get('args', [])) for name, entry in servers.items()):
            result.append(candidate.resolve())
    return sorted(set(result))


def verify_bundle(payload: Path):
    manifest = payload / 'manifest.json'
    if not manifest.is_file():
        if (payload / 'build.gradle').is_file():
            return  # Source checkouts intentionally change during development.
        raise InstallError('Installer bundle is missing manifest.json; download/extract the complete package.')
    try:
        data = json.loads(manifest.read_text(encoding='utf-8'))
    except (ValueError, UnicodeError) as error:
        raise InstallError('Invalid bundle manifest JSON.') from error
    if not isinstance(data, dict):
        raise InstallError('Invalid bundle manifest object.')
    entries = data.get('files', {})
    if data.get('formatVersion') != 1 or not isinstance(entries, dict) or not 1 <= len(entries) <= 512:
        raise InstallError('Invalid bundle manifest.')
    for name, expected in entries.items():
        path = (payload / name).resolve()
        if not path.is_relative_to(payload.resolve()) or not path.is_file() or digest(path) != expected:
            raise InstallError('Bundle checksum mismatch: ' + name)
    for path in (payload / 'mcp-server/src').rglob('*.py'):
        if path.relative_to(payload).as_posix() not in entries:
            raise InstallError('Unexpected Python source in installer bundle.')


def discover_instances(prism=None):
    roots = []
    if prism:
        roots.append(Path(prism).resolve().parent)
    for key in ('LOCALAPPDATA', 'APPDATA'):
        if os.environ.get(key):
            roots.append(Path(os.environ[key]) / 'PrismLauncher')
    for key in ('MCB_PRISM_ROOT', 'MCB_INSTANCE_ROOT'):
        if os.environ.get(key):
            path = Path(os.environ[key])
            roots.append(path if key == 'MCB_PRISM_ROOT' else path.parent)
    executable = shutil.which('prismlauncher')
    if executable:
        roots.append(Path(executable).parent)
    candidates = set()
    for root in roots:
        directory = root / 'instances'
        if not directory.is_dir():
            continue
        for child in sorted(directory.iterdir())[:256]:
            try:
                candidates.add(validate_instance(child))
            except (OSError, InstallError):
                pass
    return sorted(candidates)


def discover_prism_roots(prism=None):
    candidates = []
    if prism:
        candidates.append(Path(prism).expanduser().resolve().parent)
    for key in ('LOCALAPPDATA', 'APPDATA'):
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]) / 'PrismLauncher')
    for key in ('MCB_PRISM_ROOT', 'MCB_INSTANCE_ROOT'):
        if os.environ.get(key):
            candidate = Path(os.environ[key]).expanduser()
            candidates.append(candidate if key == 'MCB_PRISM_ROOT' else candidate.parent)
    executable = shutil.which('prismlauncher')
    if executable:
        candidates.append(Path(executable).parent)
    roots = set()
    for candidate in candidates:
        try:
            roots.add(prism_directory(candidate.resolve())[0])
        except PrismInstanceError:
            continue
    return sorted(roots)


def named_instance_choices(root):
    choices = []
    for row in list_prism_instances(root):
        try:
            validate_instance(Path(row['instancePath']))
            choices.append(row['instanceName'])
        except (OSError, InstallError):
            continue
    return choices


def installation_target(options, current, config):
    """New installs ask for the Prism directory first, then an explicit instance name."""
    new_selector = bool(options.prism_root or options.instance_name)
    if options.instance and new_selector:
        raise InstallError('Use --prism-root and --instance-name together, or the legacy --instance path; do not mix selectors.')
    prism = options.prism or (str(resolve_path(current['prismExe'], config)) if current.get('prismExe') and not new_selector else None)
    use_named = new_selector or bool(current.get('prismRoot')) or not (options.instance or current.get('instancePath'))
    questions = []
    root = name = instance = None
    if use_named and not options.instance:
        root = options.prism_root or (str(resolve_path(current['prismRoot'], config)) if current.get('prismRoot') else None)
        # Selecting a different directory never inherits the previous instance name.
        name = options.instance_name or (current.get('instanceName') if not options.prism_root else None)
        if not root:
            questions.append(_question('prism-root',
                'Where is the Prism data directory? Select the folder containing instances/ or prismlauncher.cfg.', discover_prism_roots(prism)))
        else:
            try:
                root = str(prism_directory(Path(root).expanduser().resolve())[0])
            except PrismInstanceError as error:
                questions.append(_question('prism-root', str(error), discover_prism_roots(prism)))
                root = None
            if root:
                choices = named_instance_choices(root)
                if not name:
                    questions.append(_question('instance-name',
                        'Which Minecraft 1.7.10 instance in this Prism directory should BridgeNH use? Enter its display name or folder name.', choices))
                else:
                    try:
                        selected = resolve_prism_instance(root, name)
                        instance = str(validate_instance(Path(selected['instancePath'])))
                        name = selected['instanceName']
                        prism = prism or selected['prismExe']
                    except (PrismInstanceError, OSError, InstallError) as error:
                        questions.append(_question('instance-name', str(error), choices))
    else:
        instance = options.instance or str(resolve_path(current['instancePath'], config))
        try:
            instance = str(validate_instance(Path(instance)))
        except (OSError, InstallError):
            questions.append(_question('instance',
                'The configured instance is missing or invalid. Select an existing Minecraft 1.7.10 Prism instance.', discover_instances(prism)))
            instance = None
    if instance:
        inferred = Path(root) / 'prismlauncher.exe' if root else Path(instance).parent.parent / 'prismlauncher.exe'
        if not prism and inferred.is_file():
            prism = str(inferred)
        if prism:
            executable = Path(prism).expanduser().resolve()
            if executable.name.casefold() != 'prismlauncher.exe' or not executable.is_file():
                questions.append(_question('prism', 'The Prism executable is missing or invalid. Select prismlauncher.exe.'))
                prism = None
            else:
                prism = str(executable)
        elif not options.manual_launch:
            questions.append(_question('prism', 'Where is prismlauncher.exe? Use --manual-launch if you start Minecraft yourself.'))
    profile = {'prismRoot': root, 'instanceName': name} if root and instance else {'instancePath': instance}
    if prism and (not root or Path(prism) != Path(root) / 'prismlauncher.exe'):
        profile['prismExe'] = prism
    return {'instancePath': instance, 'prismRoot': root, 'instanceName': name if instance else None,
            'prismExe': prism, 'profile': profile, 'questions': questions}


def inspect(options) -> dict:
    payload = Path(options.payload).resolve() if options.payload else source_root()
    scope = options.scope or ('source' if payload and (payload / 'build.gradle').is_file() else 'user')
    if scope == 'source' and (not payload or not (payload / 'build.gradle').is_file()):
        raise InstallError('Source scope requires a complete source checkout.')
    path = Path(options.config).expanduser().resolve() if options.config else (
        Path(os.environ['MCB_CONFIG_FILE']).expanduser().resolve() if os.environ.get('MCB_CONFIG_FILE') else
        payload / LOCAL_NAME if scope == 'source' else user_config_path())
    old = read_config(path) if path.exists() else {'version': 1, 'activeProfile': 'default', 'profiles': {}}
    profile = options.profile or old.get('activeProfile') or 'default'
    if not PROFILE_NAME.fullmatch(profile):
        raise InstallError('Profile must use 1..64 English letters, digits, dots, underscores or hyphens.')
    current = old['profiles'].get(profile, {})
    target = installation_target(options, current, path)
    instance, prism, questions = target['instancePath'], target['prismExe'], target['questions']
    data = deepcopy(old)
    data['activeProfile'] = profile
    if instance:
        data['profiles'][profile] = target['profile']
    python = (payload / 'mcp-server/.venv/Scripts/python.exe' if scope == 'source' else path.parent / 'runtime/Scripts/python.exe')
    if options.configure_only:
        python = Path(options.runtime_python or data.get('installation', {}).get('pythonExe') or sys.executable).resolve()
        if not python.is_file():
            questions.append(_question('runtime-python', 'Where is the Python executable that already has MCB installed?'))
    data['installation'] = {**data.get('installation', {}), 'pythonExe': str(python), 'scope': scope}
    codex_home = Path(os.environ.get('CODEX_HOME') or Path.home() / '.codex')
    configured_codex = options.codex_config or data['installation'].get('codexConfig')
    candidates = existing_codex_configs(codex_home, payload) if not configured_codex else []
    if not configured_codex and len(candidates) > 1:
        questions.append(_question('codex-config', 'Several existing MCB registrations were found. Which Codex config should this installation update?', candidates))
    codex_path = Path(configured_codex).expanduser().resolve() if configured_codex else (candidates[0] if len(candidates) == 1 else codex_home / 'config.toml')
    data['installation']['codexConfig'] = str(codex_path)
    codex_old = codex_path.read_bytes() if codex_path.exists() else b''
    codex_new, name = codex_registration(codex_old, python, path, profile, options.server_name)
    jar = Path(options.jar).resolve() if options.jar else None
    if not options.configure_only:
        if not jar and payload:
            directory = payload / ('build/libs' if (payload / 'build.gradle').is_file() else 'mod')
            jar = _single(p for pattern in ('BridgeNH-*.jar', 'minecraft-codex-bridge-*.jar')
                          for p in directory.glob(pattern) if not p.name.endswith(('-dev.jar', '-sources.jar')))
        if not jar:
            questions.append(_question('jar', 'Where is the built BridgeNH runtime JAR?'))
        if not payload or not (payload / 'mcp-server/pyproject.toml').is_file():
            questions.append(_question('payload', 'Where is the extracted MCB installer bundle (containing mcp-server)?'))
    mod = None
    if jar and instance and not options.configure_only:
        try:
            mod_target, _, same = mod_plan(jar, Path(instance))
            mod = {'source': str(jar), 'target': str(mod_target), 'changed': not same, 'sha256': digest(jar)}
        except FileNotFoundError:
            questions.append(_question('jar', 'The selected JAR is missing. Select the built MCB runtime JAR.'))
    return {'status': 'needs_input' if questions else 'ready', 'questions': questions, 'scope': scope,
            'configFile': str(path), 'profile': profile, 'instancePath': instance, 'prismExe': prism,
            'prismRoot': target['prismRoot'], 'instanceName': target['instanceName'],
            'pythonExe': str(python), 'payload': str(payload) if payload else None,
            'codexConfig': str(codex_path), 'serverName': name, 'codexChanged': codex_old != codex_new,
            'agentGuide': {
                'tool': 'mcb_help', 'arguments': {'topic': 'workspace'},
                'packageResource': 'minecraft_codex_bridge/guides/workspace.md',
                'sourceFile': str(Path(__file__).resolve().parent / 'guides/workspace.md'),
                'recommendation': 'Use a dedicated agent project or reuse an existing one. The guide includes a starter prompt and proactive experience/local knowledge maintenance. Merge existing notes; ask only for missing context.',
                'workspaceCreated': False,
            },
            'mod': mod, '_config': data, '_codexOld': codex_old, '_codexNew': codex_new,
            '_configOld': path.read_bytes() if path.exists() else None}


def _run(command, log, *, env=None):
    with log.open('ab') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, env=env,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode:
        raise InstallError('Installation command failed; see ' + str(log))


def execute(plan, options):
    if plan['questions']:
        return plan
    if os.name != 'nt':
        raise InstallError('Automatic installation currently supports Windows.')
    if not options.configure_only:
        verify_bundle(Path(plan['payload']))
    config = Path(plan['configFile'])
    python = Path(plan['pythonExe'])
    mod = plan['mod']
    # All static validation and the running-client guard precede venv/config writes.
    if mod and mod['changed']:
        assert_instance_stopped(Path(plan['instancePath']))
    log = (Path(plan['payload']) / 'build/logs/install.log' if plan['scope'] == 'source'
           else config.parent / 'logs/install.log')
    log.parent.mkdir(parents=True, exist_ok=True)
    if not options.configure_only:
        if not python.exists():
            _run([sys.executable, '-m', 'venv', str(python.parent.parent)], log)
        package = str(Path(plan['payload']) / 'mcp-server')
        command = [str(python), '-m', 'pip', 'install', '--disable-pip-version-check']
        if plan['scope'] == 'source':
            command.append('--editable')
        command.append(package)
        _run(command, log)
    # Do not register a broken interpreter; validate real imports and the packaged tool contract.
    clean_env = dict(os.environ)
    clean_env.pop('PYTHONPATH', None)
    _run([str(python), '-I', '-c', 'from minecraft_codex_bridge.frontend import main; from minecraft_codex_bridge.tools import TOOLS; assert TOOLS'], log, env=clean_env)
    if mod:
        plan['mod'] = deploy_mod(Path(mod['source']), Path(plan['instancePath']))
    if (config.read_bytes() if config.exists() else None) != plan['_configOld']:
        raise InstallError('MCB configuration changed during setup; rerun inspection.')
    atomic_write(config, (json.dumps(plan['_config'], ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    codex_path = Path(plan['codexConfig'])
    if (codex_path.read_bytes() if codex_path.exists() else b'') != plan['_codexOld']:
        raise InstallError('Codex configuration changed during setup; MCB config was saved, registration was not changed. Rerun setup.')
    atomic_write(codex_path, plan['_codexNew'])
    plan.update(status='installed', logFile=str(log),
                nextStep='Read agentGuide for a recommended project directory, starter prompt and experience/knowledge maintenance. Start the selected Minecraft instance. If this chat already has MCB connected, reconnect its MCP registration or open a fresh chat when the host does not expose reconnect.')
    return plan


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--inspect', action='store_true', help='Read-only plan and missing-input questions.')
    result.add_argument('--non-interactive', action='store_true', help='Return needs_input instead of prompting.')
    result.add_argument('--configure-only', action='store_true', help='Use an already installed MCP runtime and mod.')
    result.add_argument('--manual-launch', action='store_true')
    result.add_argument('--scope', choices=('source', 'user'))
    for name in ('config', 'profile', 'prism-root', 'instance-name', 'instance', 'prism', 'jar', 'payload', 'codex-config', 'server-name',
                 'runtime-python'):
        result.add_argument('--' + name)
    result.add_argument('--deploy-only', action='store_true')
    result.add_argument('--show-config', action='store_true')
    return result


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    options = parser().parse_args()
    try:
        if options.deploy_only:
            if options.instance and (options.prism_root or options.instance_name):
                raise InstallError('Do not mix --instance with --prism-root/--instance-name.')
            selected = options.instance
            if options.prism_root and options.instance_name:
                selected = resolve_prism_instance(Path(options.prism_root).expanduser().resolve(), options.instance_name)['instancePath']
            if not options.jar or not selected:
                raise InstallError('--deploy-only requires --jar and either --prism-root/--instance-name or --instance.')
            result = deploy_mod(Path(options.jar), Path(selected))
        elif options.show_config:
            path = Path(options.config).resolve() if options.config else config_path()
            data, name, profile = selected_profile(path, options.profile)
            result = {'configFile': str(path), 'profile': name, **profile,
                      'pythonExe': data.get('installation', {}).get('pythonExe', sys.executable)}
        else:
            result = inspect(options)
            while result['questions'] and not options.inspect and not options.non_interactive:
                question = result['questions'][0]
                print(question['question'], file=sys.stderr)
                for number, choice in enumerate(question['choices'], 1):
                    print(str(number) + ': ' + choice, file=sys.stderr)
                value = input('> ').strip().strip('"')
                if not value:
                    raise InstallError('No answer supplied; installation stopped.')
                if value.isdigit() and 1 <= int(value) <= len(question['choices']):
                    value = question['choices'][int(value) - 1]
                setattr(options, question['key'].replace('-', '_'), value)
                result = inspect(options)
            if not options.inspect and not result['questions']:
                result = execute(result, options)
        print(json.dumps({key: value for key, value in result.items() if not key.startswith('_')}, ensure_ascii=False, indent=2))
        return 2 if result.get('status') == 'needs_input' else 0
    except (InstallError, LocalConfigError, PrismInstanceError, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
