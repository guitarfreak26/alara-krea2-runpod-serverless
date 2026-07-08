#!/usr/bin/env bash
set -euo pipefail

COMFY_DIR="${COMFY_DIR:-/opt/ComfyUI}"
MODEL_ROOT="${MODEL_ROOT:-/runpod-volume/ALARA_PROD/ComfyUI/models}"
COMFY_OUTPUT_DIR="${COMFY_OUTPUT_DIR:-/runpod-volume/ALARA_PROD/ComfyUI/output}"
COMFY_HOST="${COMFY_HOST:-127.0.0.1}"
COMFY_PORT="${COMFY_PORT:-8188}"

cd "${COMFY_DIR}"

echo "[start] model root: ${MODEL_ROOT}"
echo "[start] output dir: ${COMFY_OUTPUT_DIR}"
mkdir -p models input "${COMFY_OUTPUT_DIR}"

if [ ! -d "${MODEL_ROOT}" ]; then
  echo "[start] warning: model root does not exist: ${MODEL_ROOT}"
  echo "[start] volume candidates:"
  find /runpod-volume /workspace -maxdepth 4 -type d 2>/dev/null | sort | head -200 || true
fi

link_model_dir() {
  local name="$1"
  local src="${MODEL_ROOT}/${name}"
  local dst="${COMFY_DIR}/models/${name}"

  if [ ! -e "${src}" ]; then
    echo "[start] warning: missing model dir ${src}"
    return 0
  fi

  rm -rf "${dst}"
  ln -s "${src}" "${dst}"
  echo "[start] linked models/${name} -> ${src}"
}

link_model_dir "checkpoints"
link_model_dir "diffusion_models"
link_model_dir "loras"
link_model_dir "text_encoders"
link_model_dir "unet"
link_model_dir "vae"

exec python main.py \
  --listen "${COMFY_HOST}" \
  --port "${COMFY_PORT}" \
  --disable-auto-launch \
  --output-directory "${COMFY_OUTPUT_DIR}"
