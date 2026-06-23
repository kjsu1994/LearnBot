import os
import gc
import logging
import threading
from typing import List

from fastapi import FastAPI
from pydantic import BaseModel


class RerankDocument(BaseModel):
    id: str
    title: str = ""
    text: str


class RerankRequest(BaseModel):
    query: str
    documents: List[RerankDocument]


class RerankResult(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    results: List[RerankResult]


app = FastAPI()
_model = None
_model_loading = False
_model_lock = threading.Lock()
_active_requests = 0
_active_requests_lock = threading.Lock()
logger = logging.getLogger("learnbot-reranker")


def load_model():
    global _model, _model_loading
    if _model is not None:
        return _model
    with _model_lock:
        if _model is not None:
            return _model
        _model_loading = True
        try:
            from FlagEmbedding import FlagReranker

            device = os.getenv("RERANKER_DEVICE", "auto")
            use_fp16 = device in ("cuda", "auto")
            _model = FlagReranker(os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3"), use_fp16=use_fp16)
            logger.info("reranker_model_ready")
        finally:
            _model_loading = False
    return _model


def model_status(status: str):
    device = os.getenv("RERANKER_DEVICE", "auto")
    response = {
        "status": status,
        "modelLoaded": _model is not None,
        "modelLoading": _model_loading,
        "activeRequests": active_request_count(),
        "modelName": os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3"),
        "device": device,
        "cudaAllocatedBytes": None,
        "cudaReservedBytes": None,
    }
    try:
        import torch

        if torch.cuda.is_available():
            response["cudaAllocatedBytes"] = int(torch.cuda.memory_allocated())
            response["cudaReservedBytes"] = int(torch.cuda.memory_reserved())
    except Exception:
        pass
    return response


def active_request_count():
    with _active_requests_lock:
        return _active_requests


def increment_active_requests():
    global _active_requests
    with _active_requests_lock:
        _active_requests += 1


def decrement_active_requests():
    global _active_requests
    with _active_requests_lock:
        _active_requests = max(0, _active_requests - 1)


def ensure_model_loading():
    global _model_loading
    if _model is not None or _model_loading:
        return
    _model_loading = True

    def load_background():
        try:
            load_model()
        except Exception as exc:
            logger.warning("reranker_model_load_failed reason=%s", exc.__class__.__name__)

    threading.Thread(target=load_background, name="reranker-warmup", daemon=True).start()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/ready")
def ready():
    if _model is not None:
        return model_status("ready")
    if _model_loading:
        return model_status("warming")
    return model_status("cold")


@app.post("/warmup")
def warmup():
    ensure_model_loading()
    return ready()


@app.post("/unload")
def unload():
    global _model
    if _model_loading:
        return model_status("loading")
    if active_request_count() > 0:
        return model_status("busy")
    with _model_lock:
        if _model is None:
            return model_status("cold")
        if active_request_count() > 0:
            return model_status("busy")
        _model = None
        gc.collect()
        try:
            import torch

            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass
        logger.info("reranker_model_unloaded")
        return model_status("unloaded")


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    if not request.query.strip() or not request.documents:
        return RerankResponse(results=[])
    usable = [doc for doc in request.documents if f"{doc.title}\n{doc.text}".strip()]
    if not usable:
        return RerankResponse(results=[])
    if _model is None:
        ensure_model_loading()
        return RerankResponse(results=[])
    increment_active_requests()
    try:
        pairs = [[request.query, f"{doc.title}\n{doc.text}".strip()] for doc in usable]
        scores = _model.compute_score(pairs, normalize=True)
        if not isinstance(scores, list):
            scores = [float(scores)]
        results = [
            RerankResult(id=doc.id, score=float(score))
            for doc, score in zip(usable, scores)
        ]
        results.sort(key=lambda item: item.score, reverse=True)
        return RerankResponse(results=results)
    except Exception as exc:
        logger.warning("rerank_failed reason=%s documents=%s", exc.__class__.__name__, len(usable))
        return RerankResponse(results=[])
    finally:
        decrement_active_requests()
