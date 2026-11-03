import pytest

from py_common.messaging.approved_inputs import approved_input_version


@pytest.mark.parametrize(
    "bindings",
    [
        None,
        "[]",
        [None],
        [],
        [{"dataset": "eod", "partition": {"exchange": "hose"}, "dataVersion": ""}],
    ],
)
def test_missing_or_malformed_binding_fails_closed(bindings):
    with pytest.raises(ValueError):
        approved_input_version(
            {"approvedInputs": bindings}, "eod", {"exchange": "hose"}, required=True
        )


def test_binding_is_exact_and_duplicates_fail_closed():
    binding = {"dataset": "eod", "partition": {"exchange": "hose"}, "dataVersion": "v1"}
    assert (
        approved_input_version(
            {"approvedInputs": [binding]}, "eod", {"exchange": "hose"}, required=True
        )
        == "v1"
    )
    with pytest.raises(ValueError, match="ambiguous"):
        approved_input_version(
            {"approvedInputs": [binding, binding]},
            "eod",
            {"exchange": "hose"},
            required=True,
        )
    assert approved_input_version({}, "eod", {}, required=False) is None


@pytest.mark.parametrize("change", [None, "work", "day", "attempt", "missing"])
def test_enrolled_binding_keeps_complete_upstream_identity(change):
    binding = {"dataset": "eod", "partition": {"exchange": "hose"}, "dataVersion": "sha256:" + "b" * 64,
        "atomicWorkId": "work:" + "a" * 64, "businessDate": "2026-11-03",
        "atomicAttemptId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}
    if change == "work": binding["atomicWorkId"] = "hose-hpg"
    if change == "day": binding["businessDate"] = "20261103"
    if change == "attempt": binding["atomicAttemptId"] = "aaaa"
    if change == "missing": binding.pop("atomicWorkId")
    metadata = {"approvedInputs": [binding], "outputCompletion": {}}
    if change is None:
        assert approved_input_version(metadata,"eod",{"exchange":"hose"},required=True) == "sha256:" + "b" * 64
    else:
        with pytest.raises(ValueError):
            approved_input_version(metadata,"eod",{"exchange":"hose"},required=True)


def test_work_inventory_requires_unique_complete_bindings_and_exact_manifest_scopes():
    from types import SimpleNamespace
    from py_common.messaging.approved_inputs import approved_input_bindings, approved_manifest_version
    binding={"dataset":"eod","partition":{"exchange":"hose","code":"hpg"},"dataVersion":"sha256:"+"a"*64,
             "atomicWorkId":"work:"+"b"*64,"atomicAttemptId":"00000000-0000-0000-0000-000000000001","businessDate":"2026-11-02"}
    metadata={"outputCompletion":{},"approvedInputs":[binding]}
    assert approved_input_bindings(metadata)==[binding]
    for raw in ({"outputCompletion":{}},{**metadata,"approvedInputs":[None]},
                {**metadata,"approvedInputs":[binding,binding]}):
        with pytest.raises(ValueError):
            approved_input_bindings(raw)
    manifest=SimpleNamespace(dataVersion=binding["dataVersion"],workProofs=[],workScope={"atomicWorkId":binding["atomicWorkId"]})
    assert approved_manifest_version(metadata,"eod",binding["partition"],manifest,required=True)==binding["dataVersion"]
    with pytest.raises(ValueError,match="Missing approved work input"):
        approved_manifest_version(metadata,"indicators",{},manifest,required=True)
    assert approved_manifest_version({},"eod",{},manifest,required=False) is None
