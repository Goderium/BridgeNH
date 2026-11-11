"""Resolve explicit Prism directories and instance names using bounded local metadata."""
from __future__ import annotations

import configparser
from pathlib import Path

MAX_INSTANCES = 1024


class PrismInstanceError(ValueError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def _settings(path):
    with path.open('rb') as stream:
        raw = stream.read(65537)
    if len(raw) > 65536:
        raise ValueError('Prism metadata is too large.')
    text = raw.decode('utf-8-sig')
    first = next((line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith(('#', ';'))), '')
    parser = configparser.ConfigParser(interpolation=None)
    parser.read_string(text if first.startswith('[') else '[General]\n' + text)
    return parser


def _value(settings, key, fallback):
    value = settings.get('General', key, fallback=fallback).strip()
    return value[1:-1] if len(value) >= 2 and value[0] == value[-1] == '"' else value


def prism_directory(value):
    """Return the selected data directory and its configured InstanceDir; never scan other roots."""
    try:
        if not isinstance(value, (str, Path)) or not str(value).strip() or '\x00' in str(value):
            raise ValueError()
        root = Path(value).expanduser()
        if not root.is_absolute():
            raise ValueError()
        root = root.resolve(strict=True)
        if not root.is_dir():
            raise ValueError()
        config = root / 'prismlauncher.cfg'
        directory = _value(_settings(config), 'InstanceDir', 'instances') if config.is_file() else 'instances'
        if not directory:
            raise ValueError()
        instances = Path(directory)
        instances = (instances if instances.is_absolute() else root / instances).resolve(strict=True)
        if not instances.is_dir() or instances == root:
            raise ValueError()
        return root, instances
    except (OSError, ValueError, UnicodeError, configparser.Error):
        raise PrismInstanceError('PRISM_DIRECTORY_INVALID',
            'Select the absolute Prism data directory containing instances/ or prismlauncher.cfg with a valid InstanceDir.') from None


def list_prism_instances(value):
    root, instances = prism_directory(value)
    executable = root / 'prismlauncher.exe'
    rows = []
    try:
        for number, child in enumerate(instances.iterdir()):
            if number >= MAX_INSTANCES:
                raise PrismInstanceError('PRISM_INVENTORY_LIMIT', 'The selected Prism instance directory exceeds the inspection limit.')
            if not child.is_dir():
                continue
            try:
                settings = _settings(child / 'instance.cfg')
                if _value(settings, 'InstanceType', '') != 'OneSix':
                    continue
                display = _value(settings, 'name', child.name) or child.name
                if len(display) > 256 or any(ord(char) < 32 for char in display):
                    display = child.name
                rows.append({'prismRoot': str(root), 'instanceName': child.name,
                             'displayName': display, 'instancePath': str(child.resolve(strict=True)),
                             'prismExe': str(executable) if executable.is_file() else None})
            except (OSError, ValueError, UnicodeError, configparser.Error):
                continue
    except OSError:
        raise PrismInstanceError('PRISM_DIRECTORY_UNREADABLE', 'Cannot read the selected Prism instance directory.') from None
    return sorted(rows, key=lambda row: (row['instanceName'].casefold(), row['instancePath']))


def resolve_prism_instance(root, name):
    if not isinstance(name, str) or not 1 <= len(name) <= 256 or not name.strip() or any(ord(char) < 32 for char in name):
        raise PrismInstanceError('PRISM_INSTANCE_NAME_INVALID', 'Specify an instance name, not a full instance path.')
    matches = [row for row in list_prism_instances(root)
               if name.casefold() in {row['instanceName'].casefold(), row['displayName'].casefold()}]
    # A name is compared against metadata, never joined onto a filesystem path.
    if not matches:
        raise PrismInstanceError('PRISM_INSTANCE_NOT_FOUND', 'No instance with this name exists in the selected Prism directory.')
    if len(matches) != 1:
        raise PrismInstanceError('PRISM_INSTANCE_AMBIGUOUS',
            'Several instances match this name. List this Prism directory and select a unique instance folder name or clientId.')
    return matches[0]
