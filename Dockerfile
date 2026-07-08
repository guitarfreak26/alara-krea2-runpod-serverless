# syntax=docker/dockerfile:1
ARG BASE_IMAGE=pytorch/pytorch:2.11.0-cuda12.8-cudnn9-runtime
FROM ${BASE_IMAGE}

ARG COMFY_REPO=https://github.com/comfyanonymous/ComfyUI.git
ARG COMFY_REF=master

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    HF_HUB_DISABLE_TELEMETRY=1 \
    PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True \
    COMFY_DIR=/opt/ComfyUI \
    MODEL_ROOT=/workspace/ALARA_PROD/ComfyUI/models \
    COMFY_OUTPUT_DIR=/workspace/ALARA_PROD/ComfyUI/output \
    COMFY_HOST=127.0.0.1 \
    COMFY_PORT=8188 \
    MAX_COUNT=8

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      ffmpeg \
      git \
      git-lfs \
      build-essential \
      libgl1 \
      libglib2.0-0 \
      libsm6 \
      libxext6 \
      libxrender1 \
      procps \
      rsync \
      tini \
      wget \
    && git lfs install --skip-repo || true \
    && rm -rf /var/lib/apt/lists/*

RUN python -m pip install --upgrade pip wheel setuptools \
    && python -m pip install --no-cache-dir \
      aiohttp \
      einops \
      huggingface_hub \
      protobuf \
      psutil \
      requests \
      runpod \
      safetensors \
      sentencepiece \
      timm \
      tokenizers \
      tqdm \
      transformers \
      websocket-client

RUN git clone --depth 1 --branch ${COMFY_REF} ${COMFY_REPO} ${COMFY_DIR} \
    && python -m pip install --no-cache-dir -r ${COMFY_DIR}/requirements.txt

WORKDIR /opt/alara-krea2
COPY scripts/ ./scripts/
COPY workflows/ ./workflows/
COPY handler.py workflow_registry.json ./

RUN chmod +x ./scripts/*.sh ./scripts/*.py \
    && ./scripts/install_custom_nodes.sh \
    && ./scripts/validate_registry.py

WORKDIR /opt/alara-krea2
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python", "-u", "handler.py"]
