from py_common.runtime import create_worker_app, run_async_worker

from app.messaging.consumer import IngestorKafkaRoutingService

service = IngestorKafkaRoutingService()


async def main() -> None:
    await service.run()


app = create_worker_app(
    main,
    title="Omni Ingestor",
    description="Stock sync ingestor worker service.",
    version="0.1.0",
    readiness=lambda: service.is_running,
)


if __name__ == "__main__":
    run_async_worker(main)
