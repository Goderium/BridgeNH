"""A tail's real slot deduction follows both recorded whole-stack moves; native physics is mocked."""
import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from test_construction_tasks import native_construction, stack
from test_survival_structure_seeds import task, install_finite_native_receipts, server_inventory_from_state
import test_survival_frontier_connections as frontier


@pytest.mark.parametrize('count', [1, 3])
async def test_native_tail_debits_nested_bootstrap_reservation_and_final_retention(native_construction, monkeypatch, count):
    client, state = native_construction
    instance = task(client)
    point = frontier.setup(instance, state)[0]
    for slot in (1, 8, 9, 10):
        state['items'][slot] = None
    state['items'][13] = stack(frontier.ELECTRIC, count)
    parent = {'hotbarIndex':8,'storageIndex':13,'item':frontier.ELECTRIC.copy(),'count':count,
        'nbtIdentity':'','status':'borrowed','movementDispatched':True,'movementConfirmed':True,
        'movedCount':32,'nativeConsumedCount':32-count,'survivalMaterialLedgerIndices':[0,1]}
    instance.borrowed_inventory = [parent]
    instance.survival_material_ledger = [{'phase':'initial_seed'},{'phase':'native_shell'}]
    frontier.install_reads(instance, state, monkeypatch)
    moves = frontier.install_moves(instance, state, monkeypatch)
    await instance.read_position_candidates([point])
    choice = next(c for c in instance.frontier_seed_choices(await instance.frontier_inventory(),[point])[
        frontier.key(point)] if c['sourceIndex']==13)
    slot = await instance.prepare_frontier_seed_source(choice)
    child = instance._frontier_seed_borrow
    assert slot == 1 and child['originalIndex']==13 and child['count']==parent['count']==count
    instance.survival_inventory = copy.deepcopy(server_inventory_from_state(state)['slots'])
    placements = install_finite_native_receipts(instance, state, monkeypatch)
    record = {'position':{a:point[a] for a in ('x','y','z')}}
    await instance.place(record['position'],slot,'survival_shell_tail_place',record)
    assert len(placements)==1 and record['serverConsumptionConfirmed'] and record['placementConfirmed']
    assert child['count']==parent['count']==count-1
    assert parent['nativeConsumedCount']==33-count and parent['survivalMaterialLedgerIndices']==[0,1,2]
    assert child['survivalMaterialLedgerIndices']==[2]
    await instance.release_frontier_seed_source()
    # The movement fixture performs the actual exchange in its native DTO state.
    instance.survival_inventory = copy.deepcopy(server_inventory_from_state(state)['slots'])
    await instance.verify_retained_inventory()
    assert instance.retained_inventory_verified
    assert parent.get('restorationConfirmed') is (True if count==1 else None)
    assert parent['status']==('consumed' if count==1 else 'retained')
    assert moves==([(13,1,'seed_source_retrieve')] if count==1 else
        [(13,1,'seed_source_retrieve'),(1,13,'seed_source_return')])


@pytest.mark.parametrize('fault', ['parent_count','parent_nbt','duplicate_destination','cycle'])
def test_contradictory_borrow_chain_never_partially_updates_reservations(native_construction, fault):
    client, _ = native_construction
    instance = task(client)
    parent={'hotbarIndex':8,'storageIndex':13,'item':frontier.ELECTRIC.copy(),'count':3,
        'nbtIdentity':'','movementDispatched':True,'movementConfirmed':True,'status':'borrowed'}
    child={'originalIndex':13,'temporaryIndex':1,'item':frontier.ELECTRIC.copy(),'count':3,
        'nbtIdentity':'','movementDispatched':True,'movementConfirmed':True,'status':'retrieved'}
    instance.borrowed_inventory=[parent]
    instance.material_moves=[child]
    if fault=='parent_count':parent['count']=2
    elif fault=='parent_nbt':parent['nbtIdentity']='{different:1}'
    elif fault=='duplicate_destination':instance.borrowed_inventory.append(copy.deepcopy(parent))
    else:parent['hotbarIndex']=1
    deduction={**frontier.ELECTRIC,'playerInventoryIndex':1,'count':1,
        'sourceCountBefore':3,'sourceCountAfter':2,'nbtIdentity':''}
    instance.survival_material_ledger=[{'deductions':[deduction]}]
    before=copy.deepcopy((instance.borrowed_inventory,instance.material_moves))
    with pytest.raises(BridgeError) as caught:
        instance.account_survival_inventory_moves([deduction])
    assert caught.value.details['reason']=='survival_moved_material_ledger_mismatch'
    assert (instance.borrowed_inventory,instance.material_moves)==before
