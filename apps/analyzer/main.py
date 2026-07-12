from fastapi import FastAPI

from app.controllers.v1.stock import router as stock_router

app = FastAPI()

app.include_router(stock_router, prefix="/v1")