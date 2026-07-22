from pathlib import Path

from py_common.config import BaseAppSettings, find_repo_root


def _write_shared_config(root: Path) -> None:
    shared_dir = root / "configs" / "shared"
    shared_dir.mkdir(parents=True)
    (shared_dir / "topics.yaml").write_text(
        "\n".join(
            [
                "kafka:",
                "  bootstrap-servers: kafka:29092",
                "  topics:",
                "    topic-sync-stock-prices: prices-topic",
                "    topic-sync-symbols: symbols-topic",
                "    topic-upsert-symbols: upsert-topic",
                "    topic-sync-indicators: indicators-topic",
                "    topic-sync-job-status: status-topic",
                "app:",
                "  scheduler:",
                "    zone: Asia/Bangkok",
                "min-io:",
                "  endpoint: minio:9000",
                "  access-key: access",
                "  secret-key: secret",
                "  bucket: topic-bucket",
            ]
        ),
        encoding="utf-8",
    )
    (shared_dir / "s3-paths.yaml").write_text(
        "\n".join(
            [
                "stock-data:",
                "  bucket: stock-bucket",
                "  paths:",
                "    symbols:",
                "      base: metadata/symbols/",
                "      pattern: '{exchange}.parquet'",
                "    eod:",
                "      base: prices/eod/",
                "      pattern: '{exchange}/{code}.parquet'",
                "    indicators:",
                "      base: analytics/indicators/",
                "      pattern: '{source}/{timeframe}/{exchange}/{code}.parquet'",
            ]
        ),
        encoding="utf-8",
    )


def test_find_repo_root_from_nested_path(tmp_path: Path):
    _write_shared_config(tmp_path)
    nested = tmp_path / "apps" / "ingestor" / "app"
    nested.mkdir(parents=True)

    assert find_repo_root(nested) == tmp_path


def test_base_app_settings_loads_shared_config(tmp_path: Path):
    _write_shared_config(tmp_path)

    settings = BaseAppSettings(shared_config_root=tmp_path)

    assert settings.kafka.bootstrap_servers == "kafka:29092"
    assert settings.minio.endpoint == "minio:9000"
    assert settings.minio.access_key == "access"
    assert settings.minio.secret_key == "secret"
    assert settings.minio.bucket == "stock-bucket"
    assert settings.topic_sync_stock_prices == "prices-topic"
    assert settings.topic_sync_symbols == "symbols-topic"
    assert settings.topic_upsert_symbols == "upsert-topic"
    assert settings.topic_sync_indicators == "indicators-topic"
    assert settings.sync_job_status_topic == "status-topic"
    assert settings.scheduler.zone == "Asia/Bangkok"
    assert settings.stock_data_paths.symbols("HOSE") == "metadata/symbols/hose.parquet"
    assert settings.get_symbols_path("HOSE") == "metadata/symbols/hose.parquet"
    assert settings.stock_data_paths.eod("HOSE", "HPG") == "prices/eod/hose/hpg.parquet"
    assert settings.get_eod_path("HOSE", "HPG") == "prices/eod/hose/hpg.parquet"
    assert (
        settings.stock_data_paths.indicators("close", "1d", "HOSE", "HPG")
        == "analytics/indicators/close/1d/hose/hpg.parquet"
    )
    assert (
        settings.get_indicators_path("close", "1d", "HOSE", "HPG")
        == "analytics/indicators/close/1d/hose/hpg.parquet"
    )


def test_base_app_settings_uses_defaults_when_shared_files_are_absent(tmp_path: Path):
    settings = BaseAppSettings(shared_config_root=tmp_path)

    assert settings.kafka.bootstrap_servers == "localhost:9092"
    assert settings.topic_sync_stock_prices == "topic-sync-stock-prices"
    assert settings.topic_sync_indicators == "topic-sync-indicators"
    assert settings.scheduler.zone == "Asia/Ho_Chi_Minh"
    assert settings.minio.bucket == ""
    assert settings.stock_data_paths.eod("HOSE", "HPG") == "eod/hose/hpg.parquet"
