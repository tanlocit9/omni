import io

import pandas as pd
from minio import Minio

from app.settings import settings


def read_existing_parquet(
    client: Minio, object_name: str, bucket: str | None = None
) -> pd.DataFrame:
    target_bucket = bucket or settings.minio_bucket
    try:
        response = client.get_object(target_bucket, object_name)
        return pd.read_parquet(response)
    except Exception:
        return pd.DataFrame()


def write_parquet_to_minio(
    client: Minio, df: pd.DataFrame, object_name: str, bucket: str | None = None
) -> None:
    target_bucket = bucket or settings.minio_bucket
    buffer = io.BytesIO()
    df.to_parquet(buffer, index=False)
    buffer.seek(0)
    client.put_object(
        target_bucket,
        object_name,
        data=buffer,
        length=buffer.getbuffer().nbytes,
        content_type="application/octet-stream",
    )
