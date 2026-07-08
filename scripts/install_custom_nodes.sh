#!/usr/bin/env bash
set -euo pipefail

COMFY_DIR="${COMFY_DIR:-/opt/ComfyUI}"
CUSTOM_NODES_DIR="${COMFY_DIR}/custom_nodes"

mkdir -p "${CUSTOM_NODES_DIR}"
cd "${CUSTOM_NODES_DIR}"

clone_or_update() {
  local name="$1"
  local repo="$2"

  if [ -d "${name}/.git" ]; then
    echo "[custom-nodes] ${name} already exists"
    return 0
  fi

  echo "[custom-nodes] cloning ${name} from ${repo}"
  git clone --depth 1 "${repo}" "${name}"
}

clone_or_update "ComfyUI-Manager" "https://github.com/ltdrdata/ComfyUI-Manager.git"
clone_or_update "rgthree-comfy" "https://github.com/rgthree/rgthree-comfy.git"
clone_or_update "ComfyUI-Custom-Scripts" "https://github.com/pythongosssss/ComfyUI-Custom-Scripts.git"
clone_or_update "ComfyUI-KJNodes" "https://github.com/kijai/ComfyUI-KJNodes.git"
clone_or_update "ComfyUI-ConditioningKrea2Rebalance" "https://github.com/nova452/ComfyUI-ConditioningKrea2Rebalance.git"

for req in "${CUSTOM_NODES_DIR}"/*/requirements.txt; do
  [ -f "${req}" ] || continue
  echo "[custom-nodes] installing requirements from ${req}"
  python -m pip install --no-cache-dir -r "${req}"
done

echo "[custom-nodes] complete"
