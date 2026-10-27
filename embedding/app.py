"""
BGE-M3 向量化服务（方案 B：自建 FastAPI 版）。

为什么提供这个服务而不只是用云 API：
  1. 代码是敏感资产，送入第三方向量接口意味着源码外泄
  2. 本地部署后断网可用，答辩现场不受网络波动影响
  3. 向量维度固定 1024，与数据库 vector(1024) 严格对应

接口契约：
  GET  /health  -> {status, model, dim, device, loaded}
  POST /embed   -> {dim, count, embeddings: [[float x 1024], ...]}

设计约束：
  * 输出一律做 L2 归一化，这样 pgvector 用 cosine 距离时等价于内积，检索更快
  * 单次请求文本数上限 32、单条字符数上限 8000，防止超长输入打爆显存/内存
  * 模型编码是 CPU 密集操作，用线程池 + 信号量限制并发，避免请求堆积导致 OOM
"""

from __future__ import annotations

import logging
import os
import threading
from contextlib import asynccontextmanager
from typing import List

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

MODEL_NAME = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")
EMBEDDING_DIM = int(os.getenv("EMBEDDING_DIM", "1024"))
DEVICE = os.getenv("DEVICE", "cpu")
MAX_BATCH_SIZE = int(os.getenv("MAX_BATCH_SIZE", "32"))
MAX_CHARS_PER_TEXT = int(os.getenv("MAX_CHARS_PER_TEXT", "8000"))
MAX_CONCURRENCY = int(os.getenv("MAX_CONCURRENCY", "2"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("embedding")

_model = None
_model_lock = threading.Lock()
_semaphore = threading.BoundedSemaphore(MAX_CONCURRENCY)


class EmbedRequest(BaseModel):
    texts: List[str] = Field(..., min_length=1, max_length=MAX_BATCH_SIZE,
                             description=f"待向量化文本，单次最多 {MAX_BATCH_SIZE} 条")
    normalize: bool = Field(True, description="是否做 L2 归一化，检索场景应保持 True")


class EmbedResponse(BaseModel):
    dim: int
    count: int
    embeddings: List[List[float]]


class HealthResponse(BaseModel):
    status: str
    model: str
    dim: int
    device: str
    loaded: bool


def load_model():
    """懒加载 + 双检锁，避免并发首请求把模型加载两次（会瞬间吃掉 4GB 内存）。"""
    global _model
    if _model is not None:
        return _model
    with _model_lock:
        if _model is None:
            from sentence_transformers import SentenceTransformer

            logger.info("开始加载模型 %s (device=%s)…", MODEL_NAME, DEVICE)
            model = SentenceTransformer(MODEL_NAME, device=DEVICE)
            actual_dim = model.get_sentence_embedding_dimension()
            if actual_dim != EMBEDDING_DIM:
                # 维度不一致必须立刻失败：否则写入数据库时才报错，排查成本极高
                raise RuntimeError(
                    f"模型维度 {actual_dim} 与配置 EMBEDDING_DIM={EMBEDDING_DIM} 不一致，"
                    f"请统一配置与数据库 vector 列维度"
                )
            _model = model
            logger.info("模型加载完成，维度=%s", actual_dim)
    return _model


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动即预热：答辩现场第一次请求等待 30 秒以上会非常尴尬
    try:
        await run_in_threadpool(load_model)
    except Exception as exc:  # noqa: BLE001 - 启动阶段需要明确暴露失败原因
        logger.error("模型预热失败，服务将以未就绪状态启动: %s", exc)
    yield


app = FastAPI(
    title="BGE-M3 Embedding Service",
    version="1.0.0",
    description="本地向量化服务，输出 1024 维 L2 归一化向量",
    lifespan=lifespan,
)


def _encode(texts: List[str], normalize: bool) -> List[List[float]]:
    model = load_model()
    # truncate 保证超长文本不会触发模型内部静默截断带来的语义偏差
    vectors = model.encode(
        texts,
        batch_size=min(len(texts), MAX_BATCH_SIZE),
        normalize_embeddings=normalize,
        convert_to_numpy=True,
        show_progress_bar=False,
        truncate_dim=None,
    )
    vectors = np.asarray(vectors, dtype=np.float32)
    if vectors.ndim == 1:
        vectors = vectors.reshape(1, -1)
    return vectors.tolist()


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(
        status="UP" if _model is not None else "LOADING",
        model=MODEL_NAME,
        dim=EMBEDDING_DIM,
        device=DEVICE,
        loaded=_model is not None,
    )


@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest) -> EmbedResponse:
    texts = [text if text is not None else "" for text in request.texts]
    if any(len(text) > MAX_CHARS_PER_TEXT for text in texts):
        raise HTTPException(
            status_code=400,
            detail=f"单条文本长度不能超过 {MAX_CHARS_PER_TEXT} 字符，请先切片",
        )

    acquired = _semaphore.acquire(blocking=False)
    if not acquired:
        # 明确返回 429，让调用方退避重试，而不是无限排队
        raise HTTPException(status_code=429, detail="向量化服务繁忙，请稍后重试")
    try:
        embeddings = await run_in_threadpool(_encode, texts, request.normalize)
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        logger.exception("向量化失败")
        raise HTTPException(status_code=500, detail=f"向量化失败: {exc}") from exc
    finally:
        _semaphore.release()

    return EmbedResponse(dim=len(embeddings[0]), count=len(embeddings), embeddings=embeddings)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
