# ALARA Krea2 RunPod Serverless Worker

Queue-based RunPod Serverless worker for ALARA Krea2 BF16 ComfyUI jobs.

The Docker image contains ComfyUI, lightweight Krea2 helper nodes, and the handler. It does not contain model weights or LoRAs. Those are loaded from Alara Storage at:

```text
/workspace/ALARA_PROD/ComfyUI/models
```

Default endpoint target:

```text
GPU: NVIDIA GeForce RTX 5090 first, with RTX 6000 Ada / L40S / RTX PRO 6000 fallback options
Network volume: AlaraStorage / 1xwpz4c2a5
Volume mount: /workspace
MODEL_ROOT=/workspace/ALARA_PROD/ComfyUI/models
COMFY_OUTPUT_DIR=/workspace/ALARA_PROD/ComfyUI/output
```

This worker is content-neutral infrastructure. Prompts are supplied by the caller and are not stored in this repository.
