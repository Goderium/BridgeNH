"""History reads work offline, retain evidence boundaries and never operate the game."""
import json
import os
from pathlib import Path
import uuid

import pytest

from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.player_recordings import read_recording, MAX_LINE, PAGE_BYTES, SUMMARY_BYTES
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import SEQUENCE_READ_NAMES


@pytest.fixture
def recording(tmp_path):
    game = tmp_path / 'chosen/.minecraft'
    root = game / 'mcb-recordings'
    root.mkdir(parents=True)
    # No runtime file, Java process, launcher or credentials required to read known local files.
    config = Configuration({'MCB_RUNTIME_FILE': str(game / 'config/codexbridge/runtime.json')})
    rid = str(uuid.uuid4())
    path = root / f'recording-{rid}.jsonl'
    return config, path, rid


def event(rid, seq, kind='movement_sample', source='client_state', details=None):
    return {'recordingId': rid, 'sessionId': 'session-one', 'seq': seq, 'timeMillis': 1000 + seq,
            'kind': kind, 'source': source, 'details': details or {}}


def save(path, rows):
    path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')


def read(recording, **args):
    return read_recording(recording[0], {'view': 'summary', **args})


def test_offline_summary_distinguishes_attempts_baselines_and_repeated_state_observations(recording):
    config, path, rid = recording
    change = {'dimension': 0, 'x': 2, 'y': 4, 'z': 8, 'aePartsChanged': True,
        'before': {'aeParts': [{'side': 6}, {'side': 2}]}, 'after': {'aeParts': [{'side': 2}]}}
    rows = [event(rid, 1, 'recording_started'),
        event(rid, 2, details={'pose': {'dimension':0,'x':1,'y':4,'z':9}}),
        event(rid, 3, details={'pose': {'dimension':0,'x':3,'y':4,'z':7}}),
        event(rid, 4, 'command_submitted', 'native_gui_chat', {'text':'/time set day'}),
        event(rid, 5, 'gui_slots_observed', 'server_container', {'baseline':True}),
        event(rid, 6, 'gui_slots_observed', 'server_container', {'baseline':False}),
        event(rid, 7, 'block_break_requested'),
        event(rid, 8, 'block_state_observed', 'client_state', change),
        event(rid, 9, 'block_state_observed', 'server_world', change),
        event(rid, 10, 'block_state_observed', 'server_world', change),
        event(rid, 11, 'recording_stopped', 'recorder', {'reason':'user_stopped'})]
    save(path, rows)
    original = path.read_bytes()
    result = read(recording)
    assert path.read_bytes() == original and result['liveStateObserved'] is False
    assert result['integrity']['sequenceContinuous'] and result['integrity']['startMarkerPresent']
    assert result['integrity']['completeLines'] == 11 and result['recordingState'] == 'stopped'
    s = result['summary']
    assert s['movement']['samples'] == 2 and s['movement']['boundsByDimension'][0]['x'] == [1,3]
    assert s['guiSlots']['baselineObservations'] == {'server_container':1}
    assert s['guiSlots']['deltaObservations'] == {'server_container':1}
    assert s['serverBlockChanges']['distinctListedTransitions'] == 1
    assert s['serverBlockChanges']['entries'][0]['observations'] == 2
    assert s['eventCounts']['block_break_requested'] == 1
    assert result['recordedStopReason'] == 'user_stopped'


def test_filtered_pages_advance_over_nonmatching_events_and_bind_old_file(recording):
    _, path, rid = recording
    save(path, [event(rid, n, 'command_submitted' if n in {2,4,7} else 'movement_sample') for n in range(1,10)])
    page = read(recording, view='events', kinds=['command_submitted'], limit=1, recordingId=rid)
    assert [e['seq'] for e in page['events']] == [2] and page['nextSince'] == 3 and page['hasMore']
    page = read(recording, view='events', kinds=['command_submitted'], since=page['nextSince'], limit=2, recordingId=rid)
    assert [e['seq'] for e in page['events']] == [4,7] and page['nextSince'] == 9 and not page['hasMore']
    assert read(recording, since=3, kinds=['command_submitted'])['summary']['eventCounts'] == {'command_submitted':2}
    new_id = str(uuid.uuid4())
    new_path = path.with_name(f'recording-{new_id}.jsonl')
    save(new_path, [event(new_id,1)])
    os.utime(new_path, ns=(path.stat().st_atime_ns,path.stat().st_mtime_ns+1_000_000_000))
    listing = read(recording, view='list', limit=1)
    assert listing['recordings'][0]['recordingId'] == new_id and listing['hasMore']
    assert read(recording, recordingId=rid)['latestOnDiskSeq'] == 9
    assert read(recording)['recordingId'] == new_id
    with pytest.raises(BridgeError, match='since exceeds'):
        read(recording, recordingId=rid, since=10)


def test_gap_payload_omission_and_partial_tail_remain_explicit(recording):
    _, path, rid = recording
    save(path, [event(rid,1,'recording_started'),event(rid,4,details={'payloadOmitted':True})])
    with path.open('ab') as stream: stream.write(b'{"seq":5')
    r = read(recording)
    assert r['recordingState'] == 'unknown' and r['latestOnDiskSeq'] == 4
    assert r['integrity']['missingEvents'] == 2 and not r['integrity']['sequenceContinuous']
    assert r['integrity']['payloadOmissions'] == 1 and r['integrity']['incompleteTailBytes'] == 8
    assert r['integrity']['snapshotComplete'] is False


@pytest.mark.parametrize('bad', ['malformed', 'duplicate', 'mixed_session', 'wrong_identity', 'nan', 'exponent', 'utf8', 'after_stop'])
def test_corrupt_complete_lines_are_not_successful_summaries(recording, bad):
    _, path, rid = recording
    rows = [event(rid,1,'recording_started'),event(rid,2)]
    if bad == 'duplicate': rows[1]['seq'] = 1
    if bad == 'mixed_session': rows[1]['sessionId'] = 'other'
    if bad == 'wrong_identity': rows[1]['recordingId'] = str(uuid.uuid4())
    if bad == 'after_stop': rows[0]['kind'] = 'recording_stopped'
    save(path,rows)
    if bad in {'malformed','nan','exponent','utf8'}:
        with path.open('ab') as stream:
            stream.write({'malformed':b'{oops}\n','nan':b'{"value":NaN}\n','exponent':b'{"value":1e999}\n','utf8':b'\xff\n'}[bad])
    with pytest.raises(BridgeError) as error: read(recording)
    assert error.value.code == 'RECORDING_INVALID'


def test_response_and_event_bounds(recording):
    _, path, rid = recording
    save(path,[event(rid,n,'command_submitted',details={'text':'x'*10000}) for n in range(1,20)])
    page=read(recording,view='events',limit=128)
    assert len(json.dumps(page['events'],separators=(',',':')).encode()) < PAGE_BYTES + 128
    assert page['hasMore'] and len(page['events']) == 2
    summary=read(recording,limit=128)
    assert len(json.dumps(summary,separators=(',',':')).encode()) <= SUMMARY_BYTES
    assert summary['summary']['eventCounts']['command_submitted'] == 19
    path.write_bytes(b'x'*(MAX_LINE+1))
    with pytest.raises(BridgeError) as error: read(recording)
    assert error.value.code == 'RECORDING_BUDGET'


def test_symlink_cannot_cross_selected_instance(recording, tmp_path):
    _, path, rid=recording
    outside=tmp_path/'outside.jsonl'
    save(outside,[event(rid,1)])
    try: path.symlink_to(outside)
    except OSError: pytest.skip('Platform does not permit test symlinks')
    with pytest.raises(BridgeError) as error: read(recording)
    assert error.value.code == 'RECORDING_PATH_REFUSED'


class OfflineClient:
    def __init__(self, config): self.configuration=config
    async def invoke(self,*args): raise AssertionError('History read must not call or take over the game')


@pytest.mark.asyncio
async def test_adapter_and_fixed_runner_share_the_offline_history_entry(recording):
    _, path, rid=recording
    save(path,[event(rid,1,'recording_started')])
    adapter=Adapter(OfflineClient(recording[0]))
    result=await adapter._call_tool('mcb_recording',{'view':'summary'})
    assert result.structuredContent['ok'] and result.structuredContent['data']['recordingId']==rid
    assert 'mcb_recording' not in SEQUENCE_READ_NAMES  # Never misroute a disk view to a native operation.


@pytest.mark.asyncio
@pytest.mark.parametrize('args', [
    {'view':'events','recordingId':'../other'}, {'view':'summary','path':'arbitrary'},
    {'view':'list','since':0}, {'view':'live','kinds':['movement_sample']},
    {'view':'summary','offset':1}, {'view':'events','limit':129}, {'view':'summary','since':True},
    {'view':'summary','kinds':[]}, {'view':'unknown'}, {'view':'summary','instance':'other'},
])
async def test_invalid_cross_view_controls_and_paths_refused_before_io(recording,args):
    result=await Adapter(OfflineClient(recording[0]))._call_tool('mcb_recording',args)
    assert result.structuredContent['error']['code']=='INVALID_ARGUMENTS'


def test_empty_directory_does_not_create_recording_files(recording):
    _, path, _=recording
    assert read(recording,view='list')['recordings']==[] and not path.exists()
    with pytest.raises(BridgeError) as error: read(recording)
    assert error.value.code=='RECORDING_NOT_FOUND'


def test_omitted_slot_baseline_is_unknown_not_a_delta(recording):
    _, path, rid=recording
    save(path,[event(rid,1,'gui_slots_observed','server_container',{'payloadOmitted':True})])
    slots=read(recording)['summary']['guiSlots']
    assert slots['unknownObservations']=={'server_container':1} and slots['deltaObservations']=={}


@pytest.mark.parametrize('redirect', ['file','directory','runtime'])
def test_resolved_path_redirects_refused_without_platform_symlink_permission(recording, monkeypatch, tmp_path, redirect):
    config,path,rid=recording
    save(path,[event(rid,1)])
    target={'file':path,'directory':path.parent,'runtime':config.runtime_path()}[redirect]
    original=Path.resolve
    def resolve(candidate,*args,**kwargs):
        return tmp_path/'outside' if candidate==target else original(candidate,*args,**kwargs)
    monkeypatch.setattr(Path,'resolve',resolve)
    with pytest.raises(BridgeError) as error: read(recording)
    assert error.value.code=='RECORDING_PATH_REFUSED'


def test_concurrent_append_is_outside_captured_prefix_and_read_next_time(recording,monkeypatch):
    import minecraft_codex_bridge.player_recordings as module
    _,path,rid=recording
    save(path,[event(rid,1,'recording_started')])
    original=module._event
    def append_once(line,identity):
        with path.open('ab') as output: output.write((json.dumps(event(rid,2))+'\n').encode())
        return original(line,identity)
    with monkeypatch.context() as change:
        change.setattr(module,'_event',append_once)
        result=read(recording)
    assert result['latestOnDiskSeq']==1 and result['grewDuringRead']
    assert read(recording,since=1)['summary']['eventCounts']=={'movement_sample':1}


def test_timeout_and_oversize_file_refuse_success(recording,monkeypatch):
    import minecraft_codex_bridge.player_recordings as module
    _,path,rid=recording
    save(path,[event(rid,1,'recording_started')])
    clock=iter([0,9])
    with monkeypatch.context() as change:
        change.setattr(module.time,'monotonic',lambda:next(clock))
        with pytest.raises(BridgeError) as error: read(recording)
        assert error.value.code=='RECORDING_BUDGET'
    with path.open('wb') as output: output.truncate(module.MAX_FILE+1)
    with pytest.raises(BridgeError) as error: read(recording)
    assert error.value.code=='RECORDING_BUDGET'
