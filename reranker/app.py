import os
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
        return {"status": "ready"}
    if _model_loading:
        return {"status": "warming"}
    return {"status": "cold"}


@app.post("/warmup")
def warmup():
    ensure_model_loading()
    return ready()


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
