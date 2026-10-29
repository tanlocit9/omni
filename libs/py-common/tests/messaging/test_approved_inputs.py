import pytest

from py_common.messaging.approved_inputs import approved_input_version


@pytest.mark.parametrize("bindings", [None, "[]", [None], [], [
    {"dataset": "eod", "partition": {"exchange": "hose"}, "dataVersion": ""}
]])
def test_missing_or_malformed_binding_fails_closed(bindings):
    with pytest.raises(ValueError):
        approved_input_version({"approvedInputs": bindings}, "eod", {"exchange": "hose"}, required=True)


def test_binding_is_exact_and_duplicates_fail_closed():
    binding = {"dataset": "eod", "partition": {"exchange": "hose"}, "dataVersion": "v1"}
    assert approved_input_version({"approvedInputs": [binding]}, "eod", {"exchange": "hose"}, required=True) == "v1"
    with pytest.raises(ValueError, match="ambiguous"):
        approved_input_version({"approvedInputs": [binding, binding]}, "eod", {"exchange": "hose"}, required=True)
    assert approved_input_version({}, "eod", {}, required=False) is None
