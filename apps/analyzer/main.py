import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.controllers.v1.stock import router as stock_router
from app.core.database import Base, engine
from app.kafka_consumer import consume_loop

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    task = asyncio.create_task(consume_loop())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(lifespan=lifespan)

app.include_router(stock_router, prefix="/v1")
