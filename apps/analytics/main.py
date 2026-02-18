"""Sample Hello World application."""
# from vnstock import register_user
# register_user(api_key='vnstock_RANDOM_KEY')
from fastapi import FastAPI

app = FastAPI()


@app.get("/hello")
def hello():
    return {"msg": "Hello API!"}
