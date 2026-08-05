from __future__ import annotations

import pytest

from py_common.kafka import decode_json_object_payload


def test_decode_json_object_payload_accepts_dict():
    payload = {"symbolKey": "HOSE-HPG"}

    assert decode_json_object_payload(payload, "Test job") == payload


def test_decode_json_object_payload_accepts_bytes():
    assert decode_json_object_payload(b'{"symbolKey":"HOSE-HPG"}', "Test job") == {
        "symbolKey": "HOSE-HPG"
    }


def test_decode_json_object_payload_accepts_json_string():
    assert decode_json_object_payload('{"symbolKey":"HOSE-HPG"}', "Test job") == {
        "symbolKey": "HOSE-HPG"
    }


def test_decode_json_object_payload_rejects_non_object_json():
    with pytest.raises(ValueError, match="Test job payload must be a JSON object"):
        decode_json_object_payload('["HOSE-HPG"]', "Test job")
