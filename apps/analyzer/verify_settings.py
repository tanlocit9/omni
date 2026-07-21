import os
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
_PY_COMMON_SRC = _REPO_ROOT / "libs" / "py-common"
if str(_PY_COMMON_SRC) not in sys.path:
    sys.path.insert(0, str(_PY_COMMON_SRC))

from app.settings import AppSettings, settings


def test_settings():
    print("--- Testing Default Analyzer Settings ---")
    print(f"Kafka Bootstrap: {settings.kafka.bootstrap_servers}")
    print(f"Indicator Topic: {settings.topic_sync_indicators}")
    print(f"MinIO Bucket: {settings.minio.bucket}")
    print(f"Indicator Kafka Enabled: {settings.indicator_kafka_enabled}")

    assert (
        settings.topic_sync_indicators == "topic-sync-indicators"
    ), f"Expected 'topic-sync-indicators', got '{settings.topic_sync_indicators}'"
    assert settings.indicator_kafka_enabled is True, (
        "Expected indicator_kafka_enabled to default to True, "
        f"got {settings.indicator_kafka_enabled}"
    )
    print("SUCCESS: Analyzer default settings are correct!")


def test_override():
    print("\n--- Testing Analyzer Env Var Override ---")
    os.environ["INDICATOR_KAFKA_ENABLED"] = "false"
    override_settings = AppSettings()
    print(
        "Indicator Kafka Enabled (overridden): "
        f"{override_settings.indicator_kafka_enabled}"
    )

    assert override_settings.indicator_kafka_enabled is False, (
        "Expected indicator_kafka_enabled override to be False, "
        f"got {override_settings.indicator_kafka_enabled}"
    )
    print("SUCCESS: Analyzer env var override works!")


if __name__ == "__main__":
    try:
        test_settings()
        test_override()
        print("\nAll Analyzer Python settings verifications passed!")
    except Exception as e:
        print(f"\nFAILURE: Verification failed: {e}")
        exit(1)
