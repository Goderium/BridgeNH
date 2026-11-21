"""Offline execution database queries against a bound instance."""
from .common import ToolSpec, integer, obj

PACK_DATASETS = ["quests", "guide", "nei", "recex"]
PACK_KINDS = ["chapter", "quest", "guide", "recipe", "handler", "item", "structure", "carving", "block", "tooltip", "registry_recipe"]
PACK_REVISION = {"type": "string", "pattern": "^[0-9a-f]{64}$"}
PACK_TERM = {"type": "string", "minLength": 1, "maxLength": 256}
PACK_PAGE = {"offset": integer(0, 10000000), "limit": integer(1, 32)}
PACK_LOOKUP = ToolSpec("mcb_packdata", None,
    "Read the bound instance's execution database offline. status reports indexed datasets and their last recorded input checks. related finds exact structured item "
    "references across recipes, quests, guides, structures and an independent RecEx registry snapshot. search returns IDs for get. "
    "update_status and changes report refresh history. Use live NEI to verify changed handlers or runtime behaviour. See mcb_help(topic=packdata).",
    {"oneOf": [
        obj({"action": {"const": "status"}, "knownUpdateId": {"type":"string","minLength":1,"maxLength":128}}),
        obj({"action": {"const": "update_status"}, **PACK_PAGE}, ["action"]),
        obj({"action": {"const": "changes"}, "updateId": {"type":"string","minLength":1,"maxLength":128},
             "dataset":{"enum":PACK_DATASETS},"kind":{"enum":[*PACK_KINDS,"coverage"]},
             "change":{"enum":["added","removed","modified"]}, **PACK_PAGE}, ["action"]),
        obj({"action":{"const":"related"}, "resource":PACK_TERM,"metadata":integer(0,65535),
             "kind":{"enum":["item","handler","recipe","quest","guide","structure","carving","block","tooltip","registry_recipe"]},
             "expectedRevisions": obj({dataset: PACK_REVISION for dataset in PACK_DATASETS}),
             "offset":integer(0,10000000),"limit":integer(1,12)}, ["action","resource"])
        | {"dependentRequired":{"offset":["kind"]}},
        obj({"action": {"const": "search"}, "kind": {"enum": PACK_KINDS}, "query":PACK_TERM, "handler":PACK_TERM,
             "language":{"type":"string","maxLength":24}, "resource":PACK_TERM, "direction":{"enum":["input","output","other"]},
             "metadata":integer(0,65535), "expectedRevision":PACK_REVISION, **PACK_PAGE}, ["action","kind"])
        | {"dependentRequired":{"direction":["resource"],"metadata":["resource"]},
           "allOf":[{"if":{"required":["resource"]},"then":{"properties":{"kind":{"enum":["recipe","registry_recipe"]}}}},
                    {"if":{"required":["handler"]},"then":{"properties":{"kind":{"const":"recipe"}}}}]},
        obj({"action":{"const":"get"}, "kind":{"enum":PACK_KINDS}, "id":{"type":"string","minLength":1,"maxLength":512},
             "expectedRevision":PACK_REVISION, "offset":integer(0,4194304)}, ["action","kind","id"])]}, True)
