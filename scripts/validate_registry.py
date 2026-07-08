#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def get_path(obj: dict, dotted: str):
    current = obj
    for part in dotted.split("."):
        current = current[part]
    return current


def main() -> int:
    registry_path = ROOT / "workflow_registry.json"
    registry = json.loads(registry_path.read_text())
    errors: list[str] = []

    for workflow_id, config in (registry.get("workflows") or {}).items():
        workflow_path = ROOT / config["file"]
        if not workflow_path.exists():
            errors.append(f"{workflow_id}: missing workflow file {workflow_path}")
            continue
        try:
            workflow = json.loads(workflow_path.read_text())
        except Exception as exc:
            errors.append(f"{workflow_id}: invalid JSON: {exc}")
            continue
        for label, patch_point in (config.get("patch_points") or {}).items():
            patch_points = patch_point if isinstance(patch_point, list) else [patch_point]
            for item in patch_points:
                try:
                    get_path(workflow, str(item))
                except Exception as exc:
                    errors.append(f"{workflow_id}: bad patch point {label}={item}: {exc}")
        for node_id in config.get("output_nodes") or []:
            if str(node_id) not in workflow:
                errors.append(f"{workflow_id}: missing output node {node_id}")

    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"Validated {len(registry.get('workflows') or {})} workflow(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
