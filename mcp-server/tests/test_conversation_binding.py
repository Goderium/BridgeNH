"""Real isolated MCP workers, explicit chat selection and authenticated fixture clients."""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import threading

import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.backend_supervisor import Supervisor
from minecraft_codex_bridge.backend_transport import BackendError
from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.client_connections import switch_blockers
from minecraft_codex_bridge.connection_binding import binding_environment, client_id, initial_binding
from minecraft_codex_bridge.frontend import make_frontend
from test_backend_supervisor import copy_package, mock_worker


class Game:
    def __init__(self, path):
        self.path = path.resolve()
        self.session = path.name + "-session-1"
        self.token = path.name + "-fixture-credential"
        self.operations = []
        game = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.headers.get('Authorization') != 'Bearer ' + game.token:
                    self.send_response(401); self.end_headers(); return
                operation = request['operation']
                game.operations.append(operation)
                data = ({'protocolVersion': 1, 'instancePath': str(game.path), 'sessionId': game.session,
                         'profile': 'player', 'capabilities': ['bridge.health', 'client.window'],
                         'readiness': {'stage': 'main_menu'}} if operation == 'bridge.health' else
                        {'instancePath': str(game.path), 'window': {'mode': 'windowed'}})
                raw = json.dumps({'requestId': request['requestId'], 'ok': True, 'data': data}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers(); self.wfile.write(raw)
        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.runtime = self.path / '.minecraft/config/codexbridge/runtime.json'
        self.runtime.parent.mkdir(parents=True)
        (self.path / 'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=' + path.name + '\n', encoding='utf-8')
        self.write_runtime()

    def write_runtime(self):
        self.runtime.write_text(json.dumps({'protocolVersion': 1, 'instancePath': str(self.path),
            'sessionId': self.session, 'baseUrl': 'http://127.0.0.1:' + str(self.server.server_port),
            'token': self.token}), encoding='utf-8')

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


@pytest.fixture
def games(tmp_path):
    first = Game(tmp_path / 'instances/first')
    second = Game(tmp_path / 'instances/second')
    config = tmp_path / 'clients.json'
    config.write_text(json.dumps({'version': 1, 'activeProfile': 'first', 'profiles': {
        'first': {'instancePath': str(first.path)}, 'alias': {'instancePath': str(first.path)},
        'second': {'instancePath': str(second.path)}}}), encoding='utf-8')
    try:
        yield first, second, config
    finally:
        first.close(); second.close()


def environment(tmp_path, config, owner='chat-one'):
    return {**os.environ, 'CODEX_THREAD_ID': owner, 'MCB_CONFIG_FILE': str(config),
            'MCB_INSTANCE_ROOT': str(tmp_path / 'instances'), 'MCB_KNOWLEDGE_MODE': 'off',
            'MCB_KNOWLEDGE_PATH': str(tmp_path / 'knowledge.sqlite3'),
            'LOCALAPPDATA': str(tmp_path / 'local-app-data')}


@pytest.fixture
async def conversation(tmp_path, games):
    instance = Supervisor(copy_package(tmp_path), environment=environment(tmp_path, games[2]), snapshot_directory=tmp_path)
    await instance.start()
    assert instance.state == 'running', instance.status()
    try:
        yield instance
    finally:
        await instance.close()


def bind_args(instance, game, request='bind'):
    return {'action': 'bind', 'clientId': client_id(game.path), 'expectedConversationId': instance.connection['conversationId'],
            'expectedBindingGeneration': instance.connection['generation'], 'requestId': request}


async def test_new_connection_ignores_all_default_target_settings_and_makes_no_game_calls(tmp_path, games):
    first, second, config = games
    env = {**environment(tmp_path, config), 'MCB_PROFILE': 'first', 'MCB_INSTANCE_PATH': str(second.path),
           'MCB_RUNTIME_FILE': str(first.runtime), 'MCB_URL': 'http://127.0.0.1:1', 'MCB_TOKEN': 'ignored'}
    instance = Supervisor(copy_package(tmp_path), environment=env, snapshot_directory=tmp_path)
    await instance.start()
    try:
        async with create_connected_server_and_client_session(make_frontend(instance)) as session:
            status = (await session.call_tool('mcb_connection', {})).structuredContent['data']
            assert not status['bound'] and not status['defaultBinding'] and status['instancePath'] is None
            clients = (await session.call_tool('mcb_clients', {})).structuredContent['data']['clients']
            assert len(clients) == 2
            assert set(next(row for row in clients if row['clientId'] == client_id(first.path))['profiles']) == {'first', 'alias'}
            assert all(row['liveStatus'] == 'not_probed' for row in clients)
            for tool, args in [('mcb_health', {}), ('mcb_window', {}), ('mcb_background', {'enabled': True})]:
                result = await session.call_tool(tool, args)
                assert result.structuredContent['error']['code'] == 'CLIENT_NOT_BOUND'
            assert not (await session.call_tool('mcb_help', {})).isError
            assert not first.operations and not second.operations
            result = await session.call_tool('mcb_backend_restart', {'requestId': 'unbound-reload', 'expectedGeneration': 1})
            assert not result.isError and not instance.connection_status()['bound']
            assert not first.operations and not second.operations
    finally:
        await instance.close()


async def test_one_mcp_session_binds_switches_unbinds_and_preserves_hot_reload_binding(conversation, games):
    first, second, config = games
    async with create_connected_server_and_client_session(make_frontend(conversation)) as session:
        args = bind_args(conversation, first)
        args.pop('clientId')
        args.update(prismRoot=str(first.path.parent.parent), instanceName=first.path.name)
        bound = await session.call_tool('mcb_connection', args)
        assert not bound.isError, bound
        assert bound.structuredContent['data']['instancePath'] == str(first.path)
        assert bound.structuredContent['data']['sessionIdAtBind'] == first.session
        assert bound.structuredContent['data']['prismRoot'] == str(first.path.parent.parent)
        assert bound.structuredContent['data']['instanceName'] == first.path.name
        assert first.operations == ['bridge.health'] and not second.operations
        duplicate = await session.call_tool('mcb_connection', args)
        assert duplicate.structuredContent == bound.structuredContent
        assert first.operations == ['bridge.health']
        health = (await session.call_tool('mcb_health', {})).structuredContent['data']
        assert health['mcpConnection']['conversationId'] == 'chat-one'
        assert health['mcpConnection']['liveStatus'] == 'online'
        assert health['mcpConnection']['currentSessionId'] == first.session
        assert health['mcpConnection']['gameAccessProfile'] == 'player'
        assert health['instancePath'] == str(first.path)
        original = deepcopy(conversation.connection)
        config.write_text(config.read_text().replace('"activeProfile": "first"', '"activeProfile": "second"'), encoding='utf-8')
        before_calls = list(first.operations)
        restarted = await session.call_tool('mcb_backend_restart', {'requestId': 'reload-bound', 'expectedGeneration': 2})
        assert not restarted.isError, restarted
        assert conversation.connection == original and first.operations == before_calls
        first.session = 'first-session-2'; first.token = 'rotated-fixture-credential'; first.write_runtime()
        health = (await session.call_tool('mcb_health', {})).structuredContent['data']
        assert health['sessionId'] == 'first-session-2'
        assert health['mcpConnection']['sessionIdAtBind'] == original['sessionIdAtBind']
        switch = await session.call_tool('mcb_connection', bind_args(conversation, second, 'switch'))
        assert not switch.isError and switch.structuredContent['data']['previousClientStateTransferred'] is False
        window = (await session.call_tool('mcb_window', {})).structuredContent['data']
        assert window['instancePath'] == str(second.path)
        unbind = await session.call_tool('mcb_connection', {'action': 'unbind', 'expectedConversationId': 'chat-one',
            'expectedBindingGeneration': 2, 'requestId': 'unbind'})
        assert not unbind.isError and not unbind.structuredContent['data']['bound']
        assert (await session.call_tool('mcb_health', {})).structuredContent['error']['code'] == 'CLIENT_NOT_BOUND'


async def test_wrong_chat_stale_generation_busy_and_duplicate_ids_cannot_change_target(conversation, games):
    first = games[0]
    args = bind_args(conversation, first)
    wrong = await conversation.change_connection({**args, 'expectedConversationId': 'another-chat'})
    assert wrong['error']['code'] == 'CONVERSATION_MISMATCH'
    async with conversation.gate:
        busy = await conversation.change_connection(args)
        assert busy['error']['code'] == 'BACKEND_BUSY_NOT_DISPATCHED'
    stale = await conversation.change_connection({**args, 'expectedBindingGeneration': 4})
    assert stale['error']['code'] == 'BINDING_GENERATION_CHANGED'
    assert (await conversation.change_connection(args))['error']['code'] == 'CONNECTION_REQUEST_ID_CONFLICT'
    assert not conversation.connection_status()['bound'] and not first.operations


async def test_failed_new_target_keeps_old_worker_and_binding_without_fallback(conversation, games):
    first, second, _ = games
    assert (await conversation.change_connection(bind_args(conversation, first)))['ok']
    before = conversation.status()
    second.runtime.unlink()
    failed = await conversation.change_connection(bind_args(conversation, second, 'offline'))
    assert not failed['ok'] and failed['error']['code'] == 'CONFIGURATION_ERROR'
    assert conversation.status()['workerPid'] == before['workerPid'] and conversation.state == 'running'
    assert conversation.connection_status()['instancePath'] == str(first.path)
    assert not second.operations
    result = await conversation.call('call_tool', {'name': 'mcb_health', 'arguments': {}})
    assert result['structuredContent']['data']['instancePath'] == str(first.path)


async def test_same_instance_name_in_another_prism_directory_selects_only_that_directory(conversation, games, tmp_path):
    other = Game(tmp_path / 'another Prism/instances/first')
    try:
        async with create_connected_server_and_client_session(make_frontend(conversation)) as session:
            root = str(other.path.parent.parent)
            listed = (await session.call_tool('mcb_clients', {'prismRoot': root})).structuredContent['data']['clients']
            assert len(listed) == 1 and listed[0]['instancePath'] == str(other.path)
            assert not other.operations and not games[0].operations and not games[1].operations
            args = {'action': 'bind', 'prismRoot': root, 'instanceName': 'first',
                    'expectedConversationId': 'chat-one', 'expectedBindingGeneration': 0, 'requestId': 'explicit-root'}
            bound = await session.call_tool('mcb_connection', args)
            assert not bound.isError and bound.structuredContent['data']['instancePath'] == str(other.path)
            health = (await session.call_tool('mcb_health', {})).structuredContent['data']
            assert health['instancePath'] == str(other.path)
            assert not games[0].operations and not games[1].operations
    finally:
        other.close()


async def test_missing_ambiguous_and_incomplete_named_selectors_preserve_current_binding(conversation, games):
    first, second, _ = games
    assert (await conversation.change_connection(bind_args(conversation, first)))['ok']
    original = deepcopy(conversation.connection)
    async with create_connected_server_and_client_session(make_frontend(conversation)) as session:
        args = {'action': 'bind', 'prismRoot': str(first.path.parent.parent), 'instanceName': 'missing',
                'expectedConversationId': 'chat-one', 'expectedBindingGeneration': 1, 'requestId': 'missing-name'}
        missing = await session.call_tool('mcb_connection', args)
        assert missing.structuredContent['error']['code'] == 'PRISM_INSTANCE_NOT_FOUND'
        for game in (first, second):
            (game.path / 'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=Same name\n', encoding='utf-8')
        ambiguous = await session.call_tool('mcb_connection', {**args, 'instanceName': 'Same name', 'requestId': 'ambiguous-name'})
        assert ambiguous.structuredContent['error']['code'] == 'PRISM_INSTANCE_AMBIGUOUS'
        incomplete = {key: value for key, value in args.items() if key != 'instanceName'}
        assert (await session.call_tool('mcb_connection', incomplete)).isError
        mixed = {**args, 'clientId': client_id(second.path)}
        assert (await session.call_tool('mcb_connection', mixed)).isError
        assert conversation.connection == original and first.operations == ['bridge.health'] and not second.operations


async def test_host_without_conversation_environment_uses_explicit_chat_owner(tmp_path, games):
    env = environment(tmp_path, games[2]); env.pop('CODEX_THREAD_ID')
    instance = Supervisor(copy_package(tmp_path), environment=env, snapshot_directory=tmp_path)
    await instance.start()
    try:
        assert instance.connection_status()['conversationId'] is None
        invalid = await instance.change_connection({**bind_args(instance, games[0]), 'expectedConversationId': ' '})
        assert invalid['error']['code'] == 'CONVERSATION_ID_REQUIRED' and not games[0].operations
        args = {**bind_args(instance, games[0]), 'expectedConversationId': 'chat-from-caller-context'}
        reply = await instance.change_connection(args)
        assert reply['ok'] and reply['data']['ownerSource'] == 'explicit_bind_request'
        assert reply['data']['conversationId'] == 'chat-from-caller-context'
        wrong = await instance.change_connection({**bind_args(instance, games[1], 'other-chat'), 'expectedConversationId': 'another-chat'})
        assert wrong['error']['code'] == 'CONVERSATION_MISMATCH'
        assert not games[1].operations
        reload = await instance.restart({'requestId': 'owner-reload', 'expectedGeneration': 2})
        assert reload['ok'] and instance.connection_status()['conversationId'] == 'chat-from-caller-context'
        unbind = await instance.change_connection({'action': 'unbind', 'expectedConversationId': 'chat-from-caller-context',
            'expectedBindingGeneration': 1, 'requestId': 'release-client'})
        assert unbind['ok'] and not unbind['data']['bound']
        assert unbind['data']['conversationId'] == 'chat-from-caller-context'
        assert not (await instance.change_connection({**bind_args(instance, games[1], 'cannot-reassign-owner'),
            'expectedConversationId': 'another-chat'}))['ok']
    finally:
        await instance.close()


async def test_independent_frontends_do_not_share_selection(tmp_path, games, conversation):
    other_env = environment(tmp_path, games[2], 'chat-two')
    other = Supervisor(conversation.root, environment=other_env, snapshot_directory=tmp_path)
    await other.start()
    try:
        assert (await conversation.change_connection(bind_args(conversation, games[0])))['ok']
        assert not other.connection_status()['bound']
        assert (await other.change_connection(bind_args(other, games[1])))['ok']
        assert conversation.connection_status()['instancePath'] == str(games[0].path)
        assert other.connection_status()['instancePath'] == str(games[1].path)
        refused = await other.change_connection({**bind_args(other, games[0], 'wrong-owner'), 'expectedConversationId': 'chat-one'})
        assert refused['error']['code'] == 'CONVERSATION_MISMATCH'
    finally:
        await other.close()


@pytest.mark.parametrize('kind', ['pending', 'uncertain', 'failed', 'movement'])
async def test_unfinished_original_client_state_blocks_switch(tmp_path, kind):
    worker = mock_worker(tmp_path)
    try:
        if kind == 'pending': worker.adapter.ae_order_records['fixture'] = {'phase': 'native_pending'}
        elif kind == 'uncertain': worker.uncertain = True
        elif kind == 'failed': worker.failed_actions.append({'fixture': True})
        else: worker.movement_failures.append({'fixture': True})
        reply = await worker.dispatch('prepare_connection_change', {})
        assert reply['blockers'] and not worker.prepared
    finally:
        await worker.client.aclose()


async def test_owned_dialogue_relay_blocks_switch(tmp_path, monkeypatch):
    worker = mock_worker(tmp_path)
    import minecraft_codex_bridge.dialogue as dialogue
    binding = initial_binding({'CODEX_THREAD_ID': 'chat-one'})
    binding.update(instancePath=str(tmp_path), clientId=client_id(tmp_path), bindingId='test')
    worker.client.configuration.conversation_binding = binding
    monkeypatch.setattr(dialogue, 'state_path', lambda _config: tmp_path / 'relay.json')
    monkeypatch.setattr(dialogue, 'read_state', lambda _path: {'threadId': 'chat-one'})
    monkeypatch.setattr(dialogue, 'relay_running', lambda *_args: True)
    try:
        assert switch_blockers(worker) == ['owned_dialogue_relay']
    finally:
        await worker.client.aclose()


def test_binding_owner_mismatch_cannot_be_loaded():
    binding = initial_binding({'CODEX_THREAD_ID': 'first'})
    with pytest.raises(BridgeError, match='Invalid conversation binding'):
        Configuration(binding_environment({'CODEX_THREAD_ID': 'second'}, binding))


async def test_dialogue_uses_bound_owner_when_mcp_startup_omits_thread_id(tmp_path, monkeypatch):
    import minecraft_codex_bridge.dialogue as dialogue
    worker = mock_worker(tmp_path)
    binding = initial_binding({})
    binding.update(conversationId='actual-chat', ownerSource='explicit_bind_request',
                   instancePath=str(tmp_path), clientId=client_id(tmp_path), bindingId='test')
    worker.client.configuration.conversation_binding = binding
    monkeypatch.delenv('CODEX_THREAD_ID', raising=False)
    observed = []
    class Host:
        def __init__(self, pipe, thread):
            observed.append(thread)
        async def verify(self):
            raise BridgeError('FIXTURE_STOP_BEFORE_HOST_CALL', 'fixture')
    monkeypatch.setattr(dialogue, 'AppToolsHost', Host)
    try:
        with pytest.raises(BridgeError, match='fixture'):
            await dialogue.manage(worker.client, {'action': 'start'})
        assert observed == ['actual-chat']
    finally:
        await worker.client.aclose()


def test_bound_launcher_and_offline_helpers_cannot_use_previous_default(tmp_path, games):
    first, second, config = games
    selected_prism = str(tmp_path / 'selected-prism/prismlauncher.exe')
    env = {**environment(tmp_path, config), 'MCB_PROFILE': 'first', 'MCB_INSTANCE_PATH': str(first.path),
           'MCB_RUNTIME_FILE': str(first.runtime), 'MCB_PRISM_EXE': str(tmp_path / 'old/prismlauncher.exe'),
           'MCB_URL': 'http://127.0.0.1:1', 'MCB_TOKEN': 'previous-credential'}
    binding = initial_binding(env)
    unbound = Configuration(binding_environment(env, binding))
    assert not any(key in unbound.environ for key in
                   ('MCB_PROFILE', 'MCB_INSTANCE_PATH', 'MCB_RUNTIME_FILE', 'MCB_PRISM_EXE', 'MCB_URL', 'MCB_TOKEN'))
    binding.update(instancePath=str(second.path), clientId=client_id(second.path), bindingId='explicit', prismExe=selected_prism)
    bound = Configuration(binding_environment(env, binding))
    assert bound.environ['MCB_INSTANCE_PATH'] == str(second.path)
    assert Path(bound.environ['MCB_RUNTIME_FILE']) == second.runtime
    assert bound.environ['MCB_PRISM_EXE'] == selected_prism
    assert not any(key in bound.environ for key in ('MCB_PROFILE', 'MCB_URL', 'MCB_TOKEN'))
    assert bound.inventory_environ['MCB_INSTANCE_PATH'] == str(first.path)


async def test_offline_bound_client_reports_unavailable_and_never_falls_back(conversation, games):
    first, second, _ = games
    assert (await conversation.change_connection(bind_args(conversation, first)))['ok']
    first.runtime.unlink()
    reply = await conversation.call('call_tool', {'name': 'mcb_health', 'arguments': {}})
    data = reply['structuredContent']
    assert data['ok'] is False
    assert data['mcpConnection']['liveStatus'] == 'unavailable'
    assert data['mcpConnection']['instancePath'] == str(first.path)
    assert conversation.connection_status()['bound']
    assert not second.operations


@pytest.mark.parametrize('replacement', ['missing_identity', 'other_instance'])
async def test_replaced_runtime_cannot_redirect_bound_calls(conversation, games, replacement):
    first, second, _ = games
    assert (await conversation.change_connection(bind_args(conversation, first)))['ok']
    runtime = json.loads(second.runtime.read_text())
    if replacement == 'missing_identity':
        runtime.pop('instancePath'); runtime.pop('sessionId')
    first.runtime.write_text(json.dumps(runtime), encoding='utf-8')
    reply = await conversation.call('call_tool', {'name': 'mcb_window', 'arguments': {}})
    assert reply['structuredContent']['error']['code'] in {'BINDING_IDENTITY_UNCONFIRMED', 'INSTANCE_MISMATCH'}
    assert first.operations == ['bridge.health'] and not second.operations


async def test_activation_failure_after_retirement_stays_closed(conversation, games, monkeypatch):
    original = conversation.candidate
    async def candidate(**kwargs):
        child = await original(**kwargs)
        rpc = child.rpc
        async def fail_activation(method, params=None):
            if method == 'activate':
                raise BackendError('FIXTURE_ACTIVATION_FAILURE')
            return await rpc(method, params)
        child.rpc = fail_activation
        return child
    monkeypatch.setattr(conversation, 'candidate', candidate)
    args = bind_args(conversation, games[0])
    failed = await conversation.change_connection(args)
    assert failed['error']['code'] == 'FIXTURE_ACTIVATION_FAILURE'
    assert failed['data']['backendState'] == 'faulted'
    assert not failed['data']['bound'] and conversation.child is None
    assert await conversation.change_connection(args) == failed
    retry = await conversation.change_connection({**args, 'requestId': 'another-request'})
    assert retry['error']['code'] == 'BACKEND_UNAVAILABLE_NOT_DISPATCHED'
    assert games[0].operations == ['bridge.health']
    with pytest.raises(BackendError, match='BACKEND_UNAVAILABLE_NOT_DISPATCHED'):
        await conversation.call('call_tool', {'name': 'mcb_health', 'arguments': {}})
