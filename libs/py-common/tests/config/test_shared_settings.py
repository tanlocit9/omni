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
                "    topic-sync-signals: signals-topic",
                "    topic-evaluate-signals: evaluate-signals-topic",
                "    topic-signal-notifications: signal-notifications-topic",
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
                "    signals:",
                "      base: analytics/signals/",
                "      pattern: '{strategy}/{timeframe}/{exchange}.parquet'",
                "    signal-current:",
                "      base: analytics/signals/",
                "      pattern: '{strategy}/{timeframe}/{exchange}.parquet'",
            ]
        ),
        encoding="utf-8",
    )


def test_find_repo_root_from_nested_path(tmp_path: Path):
    _write_shared_config(tmp_path)
    nested = tmp_path / "apps" / "ingestor" / "app"
    nested.mkdir(parents=True)

    assert find_repo_root(nested) == tmp_path


def test_base_app_settings_loads_shared_config(tmp_path: Path, monkeypatch):
    monkeypatch.delenv("KAFKA_BOOTSTRAP_SERVERS", raising=False)
    monkeypatch.delenv("MINIO_ENDPOINT", raising=False)
    monkeypatch.delenv("MINIO_ACCESS_KEY", raising=False)
    monkeypatch.delenv("MINIO_SECRET_KEY", raising=False)
    monkeypatch.delenv("MINIO_BUCKET", raising=False)
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
    assert settings.topic_sync_signals == "signals-topic"
    assert settings.topic_evaluate_signals == "evaluate-signals-topic"
    assert settings.topic_signal_notifications == "signal-notifications-topic"
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
    assert (
        settings.stock_data_paths.signals("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "analytics/signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        settings.stock_data_paths.signal_history(
            "TREND_MOMENTUM_V1", "1d", "HOSE", "HPG"
        )
        == "analytics/signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        settings.stock_data_paths.signal_current(
            "TREND_MOMENTUM_V1", "1d", "HOSE", "HPG"
        )
        == "analytics/signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        settings.get_signals_path("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "analytics/signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        settings.get_signal_current_path("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "analytics/signals/trend_momentum_v1/1d/hose.parquet"
    )


def test_base_app_settings_uses_defaults_when_shared_files_are_absent(
    tmp_path: Path,
    monkeypatch,
):
    monkeypatch.delenv("MINIO_BUCKET", raising=False)

    settings = BaseAppSettings(shared_config_root=tmp_path)

    assert settings.kafka.bootstrap_servers == "localhost:9092"
    assert settings.topic_sync_stock_prices == "topic-sync-stock-prices"
    assert settings.topic_sync_indicators == "topic-sync-indicators"
    assert settings.topic_sync_signals == "topic-sync-signals"
    assert settings.topic_evaluate_signals == "topic-evaluate-signals"
    assert settings.topic_signal_notifications == "topic-signal-notifications"
    assert settings.scheduler.zone == "Asia/Ho_Chi_Minh"
    assert settings.minio.bucket == ""
    assert settings.stock_data_paths.eod("HOSE", "HPG") == "eod/hose/hpg.parquet"


def test_base_app_settings_supports_flat_environment_overrides(
    tmp_path: Path,
    monkeypatch,
):
    monkeypatch.setenv("KAFKA_BOOTSTRAP_SERVERS", "override-kafka:9092")
    monkeypatch.setenv("MINIO_ENDPOINT", "http://override-minio:9000")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "override-access")
    monkeypatch.setenv("MINIO_SECRET_KEY", "override-secret")
    monkeypatch.setenv("MINIO_BUCKET", "override-bucket")

    settings = BaseAppSettings(shared_config_root=tmp_path)

    assert settings.kafka.bootstrap_servers == "override-kafka:9092"
    assert settings.minio.endpoint == "http://override-minio:9000"
    assert settings.minio.access_key == "override-access"
    assert settings.minio.secret_key == "override-secret"
    assert settings.minio.bucket == "override-bucket"
