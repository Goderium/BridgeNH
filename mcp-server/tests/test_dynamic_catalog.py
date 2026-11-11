"""Hot-added tools remain discoverable/callable through old MCP entrypoint names."""
from copy import deepcopy
import json

import pytest
from mcp import types
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.connection_binding import client_id, initial_binding
from minecraft_codex_bridge.dynamic_catalog import catalog_hint, describe_catalog, resolve_call
from minecraft_codex_bridge.frontend import make_frontend
from minecraft_codex_bridge.validation import validate_tool_arguments
from test_backend_supervisor import mock_worker
from test_conversation_binding import games, conversation, bind_args


def envelope(configuration, tool, args=None):
    return {**catalog_hint(configuration)['callGuards'], 'call': {'tool': tool, 'args': args or {}}}


def bind_fixture(worker, tmp_path):
    binding = initial_binding({'CODEX_THREAD_ID': 'chat-one'})
    binding.update(instancePath=str(tmp_path), clientId=client_id(tmp_path), bindingId='fixture-binding', generation=1)
    worker.client.configuration.conversation_binding = binding
    worker.active = True


async def test_real_mcp_can_discover_added_changed_removed_tool_without_listing_again(conversation, games):
    first, second, _ = games
    async with create_connected_server_and_client_session(make_frontend(conversation)) as session:
        initial_tools = {t.name for t in (await session.list_tools()).tools}
        assert 'mcb_fixture_window' not in initial_tools
        unbound = await session.call_tool('mcb_help', {'topic': 'tools', 'query': 'mcb_window'})
        assert not unbound.isError and unbound.structuredContent['data']['invocation']['callGuards'] is None
        assert not first.operations and not second.operations
        assert not (await session.call_tool('mcb_connection', bind_args(conversation, first))).isError
        path = conversation.root / 'catalog/native.py'
        original = path.read_bytes()
        addition = b'\nfrom dataclasses import replace as _test_replace\nNATIVE_TOOLS.append(_test_replace(T_MCB_WINDOW, name="mcb_fixture_window"))\n'
        path.write_bytes(original + addition)
        reload = await session.call_tool('mcb_backend_restart', {'requestId': 'add', 'expectedGeneration': 2})
        assert not reload.isError
        # Intentionally never call tools/list again: use the two old entrypoints.
        catalog = (await session.call_tool('mcb_help', {'topic': 'tools', 'tool': 'mcb_fixture_window'})).structuredContent['data']
        assert catalog['completeSchemas'] and catalog['entries'][0]['annotations']['readOnlyHint']
        args = {**catalog['invocation']['callGuards'], 'call': {'tool': 'mcb_fixture_window', 'args': {}}}
        result = await session.call_tool('mcb_sequence', args)
        assert not result.isError and result.structuredContent['data']['instancePath'] == str(first.path)
        assert result.structuredContent['toolInvocation']['tool'] == 'mcb_fixture_window'
        calls = list(first.operations)
        changed = addition.replace(b'name="mcb_fixture_window"', b'name="mcb_fixture_window", schema={"type":"object","properties":{"probe":{"type":"boolean"}},"required":["probe"],"additionalProperties":False}')
        path.write_bytes(original + changed)
        assert not (await session.call_tool('mcb_backend_restart', {'requestId': 'edit', 'expectedGeneration': 3})).isError
        stale = await session.call_tool('mcb_sequence', args)
        assert stale.structuredContent['error']['code'] == 'TOOL_CATALOG_CHANGED'
        current = (await session.call_tool('mcb_help', {'topic': 'tools', 'tool': 'mcb_fixture_window'})).structuredContent['data']
        invalid = await session.call_tool('mcb_sequence', {**args, **current['invocation']['callGuards']})
        assert invalid.structuredContent['error']['code'] == 'INVALID_ARGUMENTS'
        assert first.operations == calls
        valid = await session.call_tool('mcb_sequence', {**current['invocation']['callGuards'],
            'call': {'tool': 'mcb_fixture_window', 'args': {'probe': True}}})
        assert not valid.isError
        path.write_bytes(original)
        assert not (await session.call_tool('mcb_backend_restart', {'requestId': 'remove', 'expectedGeneration': 4})).isError
        missing = await session.call_tool('mcb_help', {'topic': 'tools', 'tool': 'mcb_fixture_window'})
        assert missing.structuredContent['error']['code'] == 'UNKNOWN_TOOL'
        assert not second.operations


@pytest.mark.parametrize('key,value,code', [
    ('expectedConversationId', 'another-chat', 'CONVERSATION_MISMATCH'),
    ('expectedBindingId', 'another-binding', 'BINDING_GENERATION_CHANGED'),
    ('expectedBindingGeneration', 9, 'BINDING_GENERATION_CHANGED'),
    ('expectedCatalogFingerprint', '0' * 64, 'TOOL_CATALOG_CHANGED'),
])
async def test_identity_and_catalog_guards_refuse_before_dispatch(tmp_path, key, value, code):
    worker = mock_worker(tmp_path)
    bind_fixture(worker, tmp_path)
    try:
        args = envelope(worker.client.configuration, 'mcb_window')
        args[key] = value
        reply = await worker.dispatch('call_tool', {'name': 'mcb_sequence', 'arguments': args})
        assert reply['structuredContent']['error']['code'] == code
        assert not worker.mutation_seen
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize('name,code', [('mcb_sequence', 'RECURSIVE_TOOL_CALL_REFUSED'),
    ('mcb_backend_restart', 'UNKNOWN_TOOL'), ('mcb_does_not_exist', 'UNKNOWN_TOOL')])
async def test_recursive_management_and_unknown_calls_refused(tmp_path, name, code):
    worker = mock_worker(tmp_path)
    bind_fixture(worker, tmp_path)
    try:
        reply = await worker.dispatch('call_tool', {'name': 'mcb_sequence', 'arguments': envelope(worker.client.configuration, name)})
        assert reply['structuredContent']['error']['code'] == code
        assert not worker.mutation_seen
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize('first_gateway', [True, False])
async def test_direct_and_compatibility_calls_share_unknown_action_replay_guard(tmp_path, monkeypatch, first_gateway):
    worker = mock_worker(tmp_path)
    bind_fixture(worker, tmp_path)
    calls = []
    async def unknown(name, args):
        calls.append((name, args))
        return types.CallToolResult(content=[], structuredContent={'ok': False,
            'error': {'code': 'FIXTURE_TIMEOUT', 'outcome_unknown': True}}, isError=True)
    monkeypatch.setattr(worker.adapter, 'call_tool', unknown)
    direct = {'name': 'mcb_chat', 'arguments': {'message': 'fixture'}}
    proxy = {'name': 'mcb_sequence', 'arguments': envelope(worker.client.configuration, 'mcb_chat', direct['arguments'])}
    try:
        first = deepcopy(proxy if first_gateway else direct)
        result = await worker.dispatch('call_tool', first)
        assert result['structuredContent']['error']['code'] == 'FIXTURE_TIMEOUT'
        assert first['name'] == 'mcb_chat'  # Exception/cancellation handlers see the canonical target too.
        assert worker.failed_actions[0]['tool'] == 'mcb_chat'
        for request in (proxy, direct):
            refused = await worker.dispatch('call_tool', deepcopy(request))
            assert refused['structuredContent']['error']['code'] == 'BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION'
        assert calls == [('mcb_chat', direct['arguments'])]
    finally:
        await worker.client.aclose()


async def test_read_errors_and_images_preserve_exact_result_without_poisoning_action_state(tmp_path, monkeypatch):
    worker = mock_worker(tmp_path)
    bind_fixture(worker, tmp_path)
    original = types.CallToolResult(content=[types.ImageContent(type='image', data='ZmFrZQ==', mimeType='image/png')],
        structuredContent={'ok': False, 'error': {'code': 'FIXTURE_READ_ERROR'}}, isError=True)
    async def reply(*_args):
        return deepcopy(original)
    monkeypatch.setattr(worker.adapter, 'call_tool', reply)
    try:
        result = await worker.dispatch('call_tool', {'name': 'mcb_sequence',
            'arguments': envelope(worker.client.configuration, 'mcb_screenshot')})
        assert result['isError'] and result['structuredContent']['error'] == original.structuredContent['error']
        assert result['content'][1]['type'] == 'image' and result['content'][1]['data'] == 'ZmFrZQ=='
        assert not worker.mutation_seen and not worker.failed_actions
    finally:
        await worker.client.aclose()


async def test_catalog_pages_are_current_bounded_and_revision_guarded(tmp_path):
    worker = mock_worker(tmp_path)
    try:
        first = describe_catalog(worker.client.configuration, {'topic': 'tools', 'limit': 1})
        assert len(first['entries']) == 1 and first['hasMore'] and not first['completeSchemas']
        with pytest.raises(BridgeError, match='fingerprint'):
            describe_catalog(worker.client.configuration, {'topic': 'tools', 'offset': 1})
        next_page = describe_catalog(worker.client.configuration, {'topic': 'tools', 'offset': 1, 'limit': 1,
            'expectedCatalogFingerprint': first['catalogFingerprint']})
        assert next_page['entries'][0]['name'] != first['entries'][0]['name']
        full = describe_catalog(worker.client.configuration, {'topic': 'tools', 'tool': 'mcb_window_action'})
        assert full['completeSchemas'] and 'expectedWindowId' in full['entries'][0]['inputSchema']['required']
        assert len(json.dumps(full).encode()) < 262144 and full['gameCalls'] == 0
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize('args', [
    {'topic': 'overview', 'tool': 'mcb_window'}, {'topic': 'tools', 'tool': 'mcb_window', 'offset': 1},
    {'topic': 'tools', 'limit': 65}, {'topic': 'tools', 'tool': '../private-file'},
])
def test_catalog_schema_rejects_mixed_and_unbounded_requests(args):
    with pytest.raises(BridgeError):
        validate_tool_arguments('mcb_help', args)


async def test_single_call_cannot_run_without_supervised_binding(tmp_path):
    worker = mock_worker(tmp_path)
    bind_fixture(worker, tmp_path)
    try:
        args = envelope(worker.client.configuration, 'mcb_window')
        result = await worker.adapter.call_tool('mcb_sequence', args)
        assert result.structuredContent['error']['code'] == 'SUPERVISED_MCP_REQUIRED'
        worker.client.configuration.conversation_binding = None
        with pytest.raises(BridgeError, match='Bind this conversation'):
            resolve_call(worker.client.configuration, args)
    finally:
        await worker.client.aclose()
