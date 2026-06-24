import asyncio
import logging

from app.kafka_consumer import consume_loop

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)


async def main():
    await consume_loop()


if __name__ == "__main__":
    asyncio.run(main())