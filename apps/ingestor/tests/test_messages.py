from datetime import datetime

import pytest
from pydantic import ValidationError

from app.messaging.messages import SymbolJobMessage, SyncSymbolsJobMessage


def test_symbol_job_message_parses_scheduler_payload():
    message = SymbolJobMessage.model_validate(
        {
            "jobDefinitionId": "job-1",
            "executionId": "exec-1",
            "parentExecutionId": "parent-1",
            "source": "VCI",
            "symbolKey": "HOSE-FPT",
            "fromOffset": "2024-01-01T00:00:00Z",
            "toOffset": "2024-01-02T00:00:00Z",
            "metadata": {"sectorLevel": 1},
        }
    )

    assert message.job_definition_id == "job-1"
    assert message.execution_id == "exec-1"
    assert message.parent_execution_id == "parent-1"
    assert message.symbol_key == "HOSE-FPT"
    assert message.parse_symbol_key() == ("HOSE", "FPT")
    assert isinstance(message.from_offset, datetime)
    assert message.status_payload["jobDefinitionId"] == "job-1"
    assert message.status_payload["executionId"] == "exec-1"


def test_symbol_job_message_rejects_legacy_job_and_log_aliases():
    with pytest.raises(ValidationError):
        SymbolJobMessage.model_validate(
            {
                "jobId": "legacy-job",
                "logId": "legacy-log",
                "symbolKey": "HNX-ABC",
                "metadata": {},
            }
        )


def test_symbol_job_message_rejects_invalid_symbol_key():
    with pytest.raises(ValidationError):
        SymbolJobMessage.model_validate(
            {
                "jobDefinitionId": "job-1",
                "executionId": "exec-1",
                "symbolKey": "FPT",
            }
        )


def test_sync_symbols_job_message_parses_scheduler_payload():
    message = SyncSymbolsJobMessage.model_validate(
        {
            "jobDefinitionId": "job-1",
            "executionId": "exec-1",
            "parentExecutionId": "parent-1",
            "source": "VCI",
            "exchange": "hose",
            "timestamp": "2024-01-02T00:00:00Z",
            "metadata": {
                "symbolCount": 123,
                "includeSectorClassification": True,
            },
        }
    )

    assert message.exchange == "HOSE"
    assert message.expected_count == 123
    assert message.include_sector_classification is True
    assert message.status_payload["exchange"] == "HOSE"


def test_sync_symbols_job_message_defaults_optional_metadata_values():
    message = SyncSymbolsJobMessage.model_validate(
        {
            "jobDefinitionId": "job-1",
            "executionId": "exec-1",
            "exchange": "HNX",
        }
    )

    assert message.metadata == {}
    assert message.expected_count is None
    assert message.include_sector_classification is False


def test_sync_symbols_job_message_rejects_blank_exchange():
    with pytest.raises(ValidationError):
        SyncSymbolsJobMessage.model_validate(
            {
                "jobDefinitionId": "job-1",
                "executionId": "exec-1",
                "exchange": " ",
            }
        )
