READ_QA = {"mcb_qa_catalog": {}, "mcb_qa_status": {}, "mcb_qa_results": {}}
ACTION_QA = {
    "mcb_qa_run": {"selector": "tstqa", "expectedWorld": "test"},
    "mcb_qa_command": {"command": "labels", "args": ["list"], "expectedWorld": "test"},
    "mcb_qa_cancel": {"runId": "run", "expectedWorld": "test"},
}
