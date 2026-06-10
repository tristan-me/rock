from __future__ import annotations

from copy import deepcopy
from pathlib import Path
from typing import Any

import yaml


DEFAULT_CONFIG: dict[str, Any] = {
    "device": {"adb_path": "adb", "serial": "", "controller": "auto"},
    "capture": {"source": "adb", "timeout_sec": 8},
    "model": {
        "path": "models/best.pt",
        "conf": 0.45,
        "iou": 0.50,
        "classes": {
            "sprite": ["sprite", "pet", "monster"],
            "reticle": ["reticle", "crosshair", "aim"],
            "ball_button": ["ball_button", "throw_button", "ball"],
        },
    },
    "templates": {"threshold": 0.82, "reticle": "", "ball_button": ""},
    "fallback": {"reticle": [960, 540], "ball_button": [1720, 860]},
    "aim": {
        "gain_x": 0.65,
        "gain_y": 0.65,
        "direction_x": 1,
        "direction_y": 1,
        "max_step_px": 120,
        "release_radius_px": 28,
        "hold_before_ms": 180,
        "move_interval_ms": 90,
        "max_hold_ms": 2400,
        "retry_delay_ms": 900,
        "min_confidence_sprite": 0.35,
    },
    "runtime": {
        "dry_run": True,
        "preview": True,
        "loop": True,
        "save_debug": False,
        "debug_dir": "runs/debug",
    },
}


def deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    result = deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    if not path:
        return deepcopy(DEFAULT_CONFIG)
    cfg_path = Path(path)
    if not cfg_path.exists():
        raise FileNotFoundError(f"config file not found: {cfg_path}")
    with cfg_path.open("r", encoding="utf-8") as f:
        user_config = yaml.safe_load(f) or {}
    return deep_merge(DEFAULT_CONFIG, user_config)


def point_from_config(value: Any, *, name: str) -> tuple[int, int] | None:
    if value in ("", None):
        return None
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        raise ValueError(f"{name} must be [x, y]")
    return int(value[0]), int(value[1])
