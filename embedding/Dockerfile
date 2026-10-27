# BGE-M3 向量化服务镜像（方案 B，备选实现；默认方案为 TEI 官方镜像）
#
# 构建：docker build -t cr-embedding:1.0 embedding/
# 说明：torch 显式安装 CPU 版本，避免拉入 2GB+ 的 CUDA 依赖
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/data \
    TRANSFORMERS_CACHE=/data \
    EMBEDDING_MODEL=BAAI/bge-m3 \
    EMBEDDING_DIM=1024 \
    DEVICE=cpu

WORKDIR /app

# curl 用于容器健康检查
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# CPU 版 torch 单独装，体积约为 CUDA 版的 1/10
RUN pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

# 非 root 运行：容器逃逸时降低影响面
RUN useradd -m -u 1001 appuser && mkdir -p /data && chown -R appuser:appuser /data /app
USER appuser

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=300s --retries=3 \
    CMD curl -sf http://localhost:8000/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
