from fastapi import FastAPI
from cloud_server.api.routes import router as offload_router

app = FastAPI(title="Cloud Offloading Server", version="0.1.0")

app.include_router(offload_router, prefix="/api/v1")


@app.get("/health")
def health():
    return {"status": "ok", "node": "cloud"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8002, reload=True)
