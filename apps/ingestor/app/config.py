import os

from dotenv import load_dotenv

load_dotenv()

KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")
KAFKA_CONSUMER_GROUP_ID = os.getenv("KAFKA_CONSUMER_GROUP_ID", "analyzer-group")
KAFKA_RETRY_INTERVAL_SECONDS = int(os.getenv("KAFKA_RETRY_INTERVAL_SECONDS", "3"))

TOPIC_SYNC_STOCK_PRICES = os.getenv(
    "TOPIC_SYNC_STOCK_PRICES", "topic-sync-stock-prices"
)
TOPIC_SYNC_SYMBOLS = os.getenv("TOPIC_SYNC_SYMBOLS", "topic-sync-symbols")
SYNC_JOB_STATUS_TOPIC = os.getenv("SYNC_JOB_STATUS_TOPIC", "stock-sync-status")
TOPIC_UPSERT_SYMBOLS = os.getenv("TOPIC_UPSERT_SYMBOLS", "topic-upsert-symbols")

MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("MINIO_BUCKET", "stock-data")
EOD_PREFIX = os.getenv("EOD_PREFIX", "EOD/")
SYMBOLS_PREFIX = os.getenv("SYMBOLS_PREFIX", "SYMBOLS/")

DEFAULT_STOCK_SOURCE = os.getenv("DEFAULT_STOCK_SOURCE", "VND")
