import os
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


def model():
    global _model
    if _model is None:
        from FlagEmbedding import FlagReranker

        device = os.getenv("RERANKER_DEVICE", "auto")
        use_fp16 = device in ("cuda", "auto")
        _model = FlagReranker(os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3"), use_fp16=use_fp16)
    return _model


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    if not request.documents:
        return RerankResponse(results=[])
    pairs = [[request.query, f"{doc.title}\n{doc.text}".strip()] for doc in request.documents]
    scores = model().compute_score(pairs, normalize=True)
    if not isinstance(scores, list):
        scores = [float(scores)]
    results = [
        RerankResult(id=doc.id, score=float(score))
        for doc, score in zip(request.documents, scores)
    ]
    results.sort(key=lambda item: item.score, reverse=True)
    return RerankResponse(results=results)
