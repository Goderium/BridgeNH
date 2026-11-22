"""Schedule finite recipe batches when a later stage returns a starting material."""

from collections import Counter
from copy import deepcopy


class RouteLimit(Exception):
    """A bounded route search or schedule exhausted its computation budget."""


def schedule_returns(state, initial, target):
    returned = {key for step in state.steps for key in step["produced"] if key in state.missing}
    if not returned or state.reasons or not any(step["batches"] > 1 for step in state.steps):
        return state
    # Splitting a mixed-alternative slot requires the original assignment for every batch.
    if any(len(slot.get("batchesByResource", {})) > 1 for step in state.steps for slot in step["inputSlots"]):
        return state

    stock = Counter(initial)
    goal = target["resource"]
    reserved_goal = min(stock[goal], target["amount"])
    stock[goal] -= reserved_goal
    fixtures, missing, steps = Counter(), Counter(), []
    remaining = [step["batches"] for step in state.steps]
    costs = [{k: n // step["batches"] for k, n in step["consumed"].items()} for step in state.steps]

    def reserve(index):
        return Counter(state.steps[index]["reusables"]) - fixtures

    def run(index, count):
        step = deepcopy(state.steps[index])
        tools = reserve(index)
        stock.subtract(tools)
        fixtures.update(tools)
        step["consumed"] = {k: n * count for k, n in costs[index].items()}
        step["produced"] = {k: (n // step["batches"]) * count for k, n in step["produced"].items()}
        for slot in step["inputSlots"]:
            if slot["perBatch"]:
                key = next(iter(slot["batchesByResource"]))
                slot["batchesByResource"] = {key: count}
        step["batches"] = count
        stock.subtract(step["consumed"])
        stock.update(step["produced"])
        steps.append(step)
        remaining[index] -= count

    while any(remaining):
        advanced = False
        for i, count in enumerate(remaining):
            if not count:
                continue
            free = stock.copy()
            free.subtract(reserve(i))
            if any(n < 0 for n in free.values()):
                continue
            count = min([count, *[free[k] // n for k, n in costs[i].items()]])
            if count:
                if len(steps) == 32:
                    raise RouteLimit("returned-material schedule exceeded 32 stages; plan a smaller lot")
                run(i, count)
                advanced = True
        if not advanced:
            # Establish the smallest starting charge for the next batch. A returned
            # product cannot fund its own first use. Only declared deficits may be added.
            i = next(i for i, count in enumerate(remaining) if count)
            extra = (reserve(i) + Counter(costs[i])) - stock
            if not extra or not (extra + missing <= state.missing):
                return state
            stock.update(extra)
            missing.update(extra)
    if missing == state.missing or stock[goal] < target["amount"] - reserved_goal:
        return state
    stock[goal] -= target["amount"] - reserved_goal
    state.steps, state.available, state.fixtures, state.missing = steps, stock, fixtures, missing
    return state
