from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.controllers.v1.stock import router as stock_router
from app.core.database import Base, engine


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield


app = FastAPI(lifespan=lifespan)

app.include_router(stock_router, prefix="/v1")
