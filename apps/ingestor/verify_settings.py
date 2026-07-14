import os
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
_PY_COMMON_SRC = _REPO_ROOT / "libs" / "py-common"
if str(_PY_COMMON_SRC) not in sys.path:
    sys.path.insert(0, str(_PY_COMMON_SRC))

from app.settings import settings


def test_settings():
    print("--- Testing Default Settings ---")
    print(f"Kafka Bootstrap: {settings.kafka_bootstrap}")
    print(f"Sync Job Status Topic: {settings.sync_job_status_topic}")
    print(f"MinIO Bucket: {settings.minio_bucket}")

    # Verify the specific bug fix
    assert (
        settings.sync_job_status_topic == "topic-sync-job-status"
    ), f"Expected 'topic-sync-job-status', got '{settings.sync_job_status_topic}'"
    print("SUCCESS: sync_job_status_topic is correct!")


def test_override():
    print("\n--- Testing Env Var Override ---")
    # We need to create a new Settings instance because the 'settings' object is already initialized
    from app.settings import Settings

    os.environ["TOPIC_SYNC_SYMBOLS"] = "test-topic-override"
    override_settings = Settings()
    print(f"Topic Sync Symbols (overridden): {override_settings.topic_sync_symbols}")

    assert (
        override_settings.topic_sync_symbols == "test-topic-override"
    ), f"Expected 'test-topic-override', got '{override_settings.topic_sync_symbols}'"
    print("SUCCESS: Env var override works!")


if __name__ == "__main__":
    try:
        test_settings()
        test_override()
        print("\nAll Python settings verifications passed!")
    except Exception as e:
        print(f"\nFAILURE: Verification failed: {e}")
        exit(1)
