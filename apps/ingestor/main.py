from py_common.runtime import create_worker_app, run_async_worker

from app.kafka_consumer import consume_loop


async def main() -> None:
    await consume_loop()


app = create_worker_app(
    main,
    title="Omni Ingestor",
    description="Stock sync ingestor worker service.",
    version="0.1.0",
)


if __name__ == "__main__":
    run_async_worker(main)