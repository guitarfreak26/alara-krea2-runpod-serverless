from __future__ import annotations

import base64
import copy
import json
import os
import random
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import requests
import runpod

APP_DIR = Path(__file__).resolve().parent
COMFY_DIR = Path(os.getenv("COMFY_DIR", "/opt/ComfyUI"))
CONFIGURED_MODEL_ROOT = Path(os.getenv("MODEL_ROOT", "/workspace/ALARA_PROD/ComfyUI/models"))
CONFIGURED_COMFY_OUTPUT_DIR = Path(os.getenv("COMFY_OUTPUT_DIR", "/workspace/ALARA_PROD/ComfyUI/output"))
COMFY_HOST = os.getenv("COMFY_HOST", "127.0.0.1")
COMFY_PORT = int(os.getenv("COMFY_PORT", "8188"))
COMFY_URL = os.getenv("COMFY_URL", f"http://{COMFY_HOST}:{COMFY_PORT}")
REGISTRY_PATH = Path(os.getenv("WORKFLOW_REGISTRY", APP_DIR / "workflow_registry.json"))
COMFY_START_TIMEOUT = int(os.getenv("COMFY_START_TIMEOUT", "420"))
PROMPT_TIMEOUT = int(os.getenv("PROMPT_TIMEOUT", "1800"))
POLL_INTERVAL = float(os.getenv("POLL_INTERVAL", "1.0"))
MAX_COUNT = int(os.getenv("MAX_COUNT", "8"))

_comfy_process: subprocess.Popen[str] | None = None
_comfy_lock = threading.Lock()
_registry_cache: dict[str, Any] | None = None
_model_root_cache: Path | None = None
_output_dir_cache: Path | None = None


class WorkerError(RuntimeError):
    pass


def _load_registry() -> dict[str, Any]:
    global _registry_cache
    if _registry_cache is None:
        _registry_cache = json.loads(REGISTRY_PATH.read_text())
    return _registry_cache


def _get_workflow_config(workflow_id: str) -> dict[str, Any]:
    workflows = (_load_registry().get("workflows") or {})
    if workflow_id not in workflows:
        raise WorkerError(f"Unknown workflow_id: {workflow_id}")
    return workflows[workflow_id]


def _character_config(name: str) -> tuple[str, dict[str, Any]]:
    raw = str(name or "seoyeon").strip().lower()
    characters = _load_registry().get("characters") or {}
    for key, config in characters.items():
        aliases = [key, config.get("display_name", ""), *(config.get("aliases") or [])]
        if raw in {str(alias).lower() for alias in aliases if alias}:
            return key, config
    raise WorkerError(f"Unknown character: {name}")


def _model_candidates() -> list[Path]:
    candidates = [
        CONFIGURED_MODEL_ROOT,
        Path("/runpod-volume/ALARA_PROD/ComfyUI/models"),
        Path("/runpod-volume/ComfyUI/models"),
        Path("/runpod-volume/models"),
        Path("/workspace/ALARA_PROD/ComfyUI/models"),
    ]
    unique: list[Path] = []
    seen: set[str] = set()
    for candidate in candidates:
        key = str(candidate)
        if key not in seen:
            unique.append(candidate)
            seen.add(key)
    return unique


def _missing_models(model_root: Path, workflow_config: dict[str, Any]) -> list[str]:
    missing: list[str] = []
    for folder, filenames in (workflow_config.get("models") or {}).items():
        for filename in filenames or []:
            candidate = model_root / folder / filename
            if not candidate.exists():
                missing.append(str(candidate))
    return missing


def _resolve_model_root(workflow_config: dict[str, Any]) -> Path:
    global _model_root_cache
    if _model_root_cache is not None:
        return _model_root_cache

    print(f"[handler] configured MODEL_ROOT={CONFIGURED_MODEL_ROOT}", flush=True)
    for candidate in _model_candidates():
        missing = _missing_models(candidate, workflow_config)
        if not missing:
            _model_root_cache = candidate
            print(f"[handler] using MODEL_ROOT={candidate}", flush=True)
            return candidate
        print(f"[handler] model root candidate {candidate} missing {len(missing)} required files", flush=True)

    _model_root_cache = CONFIGURED_MODEL_ROOT
    return _model_root_cache


def _resolve_output_dir(workflow_config: dict[str, Any]) -> Path:
    global _output_dir_cache
    if _output_dir_cache is not None:
        return _output_dir_cache

    model_root = _resolve_model_root(workflow_config)
    if model_root.name == "models":
        _output_dir_cache = model_root.parent / "output"
    else:
        _output_dir_cache = CONFIGURED_COMFY_OUTPUT_DIR

    if _output_dir_cache != CONFIGURED_COMFY_OUTPUT_DIR:
        print(
            f"[handler] using COMFY_OUTPUT_DIR={_output_dir_cache} "
            f"(configured {CONFIGURED_COMFY_OUTPUT_DIR})",
            flush=True,
        )
    else:
        print(f"[handler] using COMFY_OUTPUT_DIR={_output_dir_cache}", flush=True)

    return _output_dir_cache


def _set_path(obj: dict[str, Any], dotted_path: str, value: Any) -> None:
    current: Any = obj
    parts = dotted_path.split(".")
    for part in parts[:-1]:
        current = current[part]
    current[parts[-1]] = value


def _set_patch_point(obj: dict[str, Any], patch_point: Any, value: Any) -> None:
    if not patch_point:
        return
    if isinstance(patch_point, list):
        for item in patch_point:
            _set_path(obj, str(item), value)
        return
    _set_path(obj, str(patch_point), value)


def _wait_for_comfy(timeout: int = COMFY_START_TIMEOUT) -> None:
    deadline = time.time() + timeout
    last_error = ""
    while time.time() < deadline:
        try:
            res = requests.get(f"{COMFY_URL}/system_stats", timeout=5)
            if res.ok:
                return
            last_error = f"HTTP {res.status_code}: {res.text[:200]}"
        except requests.RequestException as exc:
            last_error = str(exc)
        time.sleep(2)
    raise WorkerError(f"ComfyUI did not become ready within {timeout}s: {last_error}")


def _ensure_comfy_running(workflow_config: dict[str, Any]) -> None:
    global _comfy_process
    with _comfy_lock:
        if _comfy_process and _comfy_process.poll() is None:
            return

        model_root = _resolve_model_root(workflow_config)
        output_dir = _resolve_output_dir(workflow_config)
        output_dir.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env.update(
            {
                "COMFY_DIR": str(COMFY_DIR),
                "MODEL_ROOT": str(model_root),
                "COMFY_OUTPUT_DIR": str(output_dir),
                "COMFY_HOST": COMFY_HOST,
                "COMFY_PORT": str(COMFY_PORT),
            }
        )
        start_script = APP_DIR / "scripts" / "start_comfy.sh"
        print(f"[handler] starting ComfyUI via {start_script}", flush=True)
        _comfy_process = subprocess.Popen([str(start_script)], cwd=str(APP_DIR), env=env, text=True)

    _wait_for_comfy()


def _validate_models(workflow_id: str, workflow_config: dict[str, Any]) -> None:
    model_root = _resolve_model_root(workflow_config)
    missing = _missing_models(model_root, workflow_config)
    if missing:
        joined = "\n".join(f"  - {item}" for item in missing)
        raise WorkerError(f"Missing model files for {workflow_id}:\n{joined}")


def _load_workflow(workflow_config: dict[str, Any]) -> dict[str, Any]:
    workflow_path = APP_DIR / workflow_config["file"]
    return json.loads(workflow_path.read_text())


def _resolve_size(workflow_config: dict[str, Any], payload: dict[str, Any]) -> tuple[int, int]:
    aspect_ratio = payload.get("aspect_ratio") or "landscape"
    ratios = workflow_config.get("aspect_ratios") or {}
    preset = ratios.get(aspect_ratio, {})
    width = int(payload.get("width") or preset.get("width") or workflow_config.get("default_width"))
    height = int(payload.get("height") or preset.get("height") or workflow_config.get("default_height"))
    return width, height


def _normalize_seed(seed: Any) -> int:
    if seed is None or int(seed) < 0:
        return random.randint(0, 2**48 - 1)
    return int(seed)


def _patch_workflow(
    workflow: dict[str, Any],
    workflow_config: dict[str, Any],
    payload: dict[str, Any],
    job_id: str,
) -> tuple[dict[str, Any], int, str]:
    patched = copy.deepcopy(workflow)
    patch_points = workflow_config.get("patch_points") or {}

    prompt = str(payload.get("prompt") or "").strip()
    if not prompt:
        raise WorkerError("input.prompt is required")

    character_key, character = _character_config(str(payload.get("character") or "seoyeon"))
    identity_lora = str(payload.get("identity_lora") or character.get("identity_lora") or "").strip()
    if not identity_lora:
        raise WorkerError(f"No identity LoRA configured for character {character_key}")

    seed = _normalize_seed(payload.get("seed", -1))
    count = int(payload.get("count", 1))
    if count < 1 or count > MAX_COUNT:
        raise WorkerError(f"count must be between 1 and {MAX_COUNT}")

    width, height = _resolve_size(workflow_config, payload)
    prefix = str(payload.get("filename_prefix") or f"krea2/{character_key}/{job_id}")

    _set_patch_point(patched, patch_points["prompt"], prompt)
    _set_patch_point(patched, patch_points.get("negative_prompt"), str(payload.get("negative_prompt", "")))
    _set_patch_point(patched, patch_points.get("seed"), seed)
    _set_patch_point(patched, patch_points.get("width"), width)
    _set_patch_point(patched, patch_points.get("height"), height)
    _set_patch_point(patched, patch_points.get("count"), count)
    _set_patch_point(patched, patch_points.get("filename_prefix"), prefix)
    _set_patch_point(patched, patch_points.get("identity_lora"), identity_lora)

    if "identity_strength" in payload:
        strength = float(payload["identity_strength"])
        _set_patch_point(patched, patch_points.get("identity_strength_model"), strength)
        _set_patch_point(patched, patch_points.get("identity_strength_clip"), strength)
    if "realism_strength" in payload:
        strength = float(payload["realism_strength"])
        _set_patch_point(patched, patch_points.get("realism_strength_model"), strength)
        _set_patch_point(patched, patch_points.get("realism_strength_clip"), strength)
    if "detail_strength" in payload:
        strength = float(payload["detail_strength"])
        _set_patch_point(patched, patch_points.get("detail_strength_model"), strength)
        _set_patch_point(patched, patch_points.get("detail_strength_clip"), strength)

    return patched, seed, character_key


def _queue_prompt(workflow: dict[str, Any], client_id: str) -> str:
    res = requests.post(f"{COMFY_URL}/prompt", json={"prompt": workflow, "client_id": client_id}, timeout=30)
    if not res.ok:
        raise WorkerError(f"ComfyUI /prompt failed: HTTP {res.status_code}: {res.text[:2000]}")
    data = res.json()
    prompt_id = data.get("prompt_id")
    if not prompt_id:
        raise WorkerError(f"ComfyUI /prompt returned no prompt_id: {data}")
    return str(prompt_id)


def _poll_history(prompt_id: str) -> dict[str, Any]:
    deadline = time.time() + PROMPT_TIMEOUT
    last_history: dict[str, Any] = {}
    while time.time() < deadline:
        res = requests.get(f"{COMFY_URL}/history/{prompt_id}", timeout=30)
        if res.ok:
            history = res.json()
            last_history = history
            if prompt_id in history:
                item = history[prompt_id]
                status = item.get("status", {})
                if status.get("completed") is True:
                    return item
                messages = status.get("messages") or []
                if any(msg[0] == "execution_error" for msg in messages if isinstance(msg, list) and msg):
                    raise WorkerError(f"ComfyUI execution error: {json.dumps(status)[:4000]}")
        time.sleep(POLL_INTERVAL)
    raise WorkerError(f"Timed out waiting for prompt {prompt_id}. Last history: {json.dumps(last_history)[:2000]}")


def _download_view_image(image: dict[str, Any]) -> bytes:
    params = {
        "filename": image["filename"],
        "subfolder": image.get("subfolder", ""),
        "type": image.get("type", "output"),
    }
    res = requests.get(f"{COMFY_URL}/view?{urlencode(params)}", timeout=60)
    if not res.ok:
        raise WorkerError(f"Failed to fetch output image {image}: HTTP {res.status_code}")
    return res.content


def _collect_outputs(history_item: dict[str, Any], workflow_config: dict[str, Any], include_base64: bool) -> list[dict[str, Any]]:
    output_nodes = set(str(node) for node in workflow_config.get("output_nodes", []))
    outputs: list[dict[str, Any]] = []
    history_outputs = history_item.get("outputs") or {}
    output_dir = _resolve_output_dir(workflow_config)

    for node_id, node_output in history_outputs.items():
        if output_nodes and str(node_id) not in output_nodes:
            continue
        for image in node_output.get("images", []) or []:
            subfolder = image.get("subfolder", "")
            filename = image.get("filename", "")
            output_path = output_dir / subfolder / filename
            image_bytes: bytes | None = None
            if not output_path.exists():
                image_bytes = _download_view_image(image)
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_bytes(image_bytes)
                print(f"[handler] copied Comfy output to {output_path}", flush=True)
            size_bytes = output_path.stat().st_size
            item = {
                "type": "image",
                "node_id": str(node_id),
                "filename": filename,
                "subfolder": subfolder,
                "path_type": image.get("type", "output"),
                "path": str(output_path),
                "size_bytes": size_bytes,
            }
            if include_base64:
                if image_bytes is None:
                    image_bytes = output_path.read_bytes()
                item["base64"] = base64.b64encode(image_bytes).decode("ascii")
            outputs.append(item)

    return outputs


def handler(job: dict[str, Any]) -> dict[str, Any]:
    started = time.time()
    job_input = job.get("input") or {}
    job_id = str(job.get("id") or uuid.uuid4())
    workflow_id = str(job_input.get("workflow_id") or "krea2-bf16")
    include_base64 = bool(job_input.get("return_base64", False))

    try:
        workflow_config = _get_workflow_config(workflow_id)
        _validate_models(workflow_id, workflow_config)
        _ensure_comfy_running(workflow_config)

        workflow = _load_workflow(workflow_config)
        patched_workflow, seed, character_key = _patch_workflow(workflow, workflow_config, job_input, job_id)
        prompt_id = _queue_prompt(patched_workflow, client_id=job_id)
        history_item = _poll_history(prompt_id)
        outputs = _collect_outputs(history_item, workflow_config, include_base64)

        if not outputs:
            raise WorkerError(f"No outputs collected from output nodes {workflow_config.get('output_nodes')}")

        return {
            "status": "success",
            "workflow_id": workflow_id,
            "character": character_key,
            "prompt_id": prompt_id,
            "seed": seed,
            "outputs": outputs,
            "duration_seconds": round(time.time() - started, 2),
        }
    except Exception as exc:
        print(f"[handler] error: {exc}", flush=True)
        return {
            "status": "error",
            "workflow_id": workflow_id,
            "error": str(exc),
            "duration_seconds": round(time.time() - started, 2),
        }


if __name__ == "__main__":
    runpod.serverless.start({"handler": handler})
