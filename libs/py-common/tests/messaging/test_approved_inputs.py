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
