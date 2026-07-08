# ALARA Krea2 RunPod Serverless Worker

Queue-based RunPod Serverless worker for ALARA Krea2 BF16 ComfyUI jobs.

The Docker image contains ComfyUI, lightweight Krea2 helper nodes, and the handler. It does not contain model weights or LoRAs. Those are loaded from Alara Storage at:

```text
/runpod-volume/ALARA_PROD/ComfyUI/models
```

Default endpoint target:

```text
GPU: RTX 5090
Network volume: AlaraStorage / 1xwpz4c2a5
Volume mount: /runpod-volume
MODEL_ROOT=/runpod-volume/ALARA_PROD/ComfyUI/models
COMFY_OUTPUT_DIR=/runpod-volume/ALARA_PROD/ComfyUI/output
```

This worker is content-neutral infrastructure. Prompts are supplied by the caller and are not stored in this repository.
