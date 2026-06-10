from __future__ import annotations

import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .adb import AdbDevice
from .config import point_from_config
from .controllers import BaseController, GesturePoint
from .detectors import Detection, DetectorPipeline, pick_best


@dataclass
class AimState:
    sprite: Detection | None
    reticle: Detection | None
    ball_button: Detection | None
    reticle_point: tuple[int, int] | None
    ball_point: tuple[int, int] | None
    detections: list[Detection]

    @property
    def ready(self) -> bool:
        return self.sprite is not None and self.reticle_point is not None and self.ball_point is not None


class Catcher:
    def __init__(
        self,
        *,
        adb: AdbDevice,
        detector: DetectorPipeline,
        controller: BaseController,
        config: dict[str, Any],
        dry_run: bool = True,
        preview: bool = False,
    ):
        self.adb = adb
        self.detector = detector
        self.controller = controller
        self.config = config
        self.dry_run = dry_run
        self.preview = preview
        self.last_overlay: np.ndarray | None = None

    def locate(self, frame: np.ndarray) -> AimState:
        detections = self.detector.detect(frame)
        aim_cfg = self.config["aim"]
        sprite = pick_best(detections, "sprite", min_conf=float(aim_cfg["min_confidence_sprite"]))
        reticle = pick_best(detections, "reticle")
        ball_button = pick_best(detections, "ball_button")

        reticle_point = reticle.center if reticle else point_from_config(self.config["fallback"].get("reticle"), name="fallback.reticle")
        ball_point = (
            ball_button.center
            if ball_button
            else point_from_config(self.config["fallback"].get("ball_button"), name="fallback.ball_button")
        )
        return AimState(
            sprite=sprite,
            reticle=reticle,
            ball_button=ball_button,
            reticle_point=reticle_point,
            ball_point=ball_point,
            detections=detections,
        )

    def compute_step(self, sprite_point: tuple[int, int], reticle_point: tuple[int, int]) -> tuple[int, int, float]:
        aim_cfg = self.config["aim"]
        error_x = sprite_point[0] - reticle_point[0]
        error_y = sprite_point[1] - reticle_point[1]
        distance = math.hypot(error_x, error_y)
        step_x = error_x * float(aim_cfg["gain_x"]) * int(aim_cfg["direction_x"])
        step_y = error_y * float(aim_cfg["gain_y"]) * int(aim_cfg["direction_y"])
        max_step = float(aim_cfg["max_step_px"])
        step_len = math.hypot(step_x, step_y)
        if step_len > max_step > 0:
            scale = max_step / step_len
            step_x *= scale
            step_y *= scale
        return int(round(step_x)), int(round(step_y)), distance

    def clamp_point(self, point: tuple[int, int], frame: np.ndarray) -> tuple[int, int]:
        h, w = frame.shape[:2]
        return max(0, min(w - 1, point[0])), max(0, min(h - 1, point[1]))

    def draw_overlay(self, frame: np.ndarray, state: AimState, status: str = "") -> np.ndarray:
        overlay = frame.copy()
        colors = {
            "sprite": (0, 255, 255),
            "reticle": (0, 200, 0),
            "ball_button": (255, 160, 0),
        }
        for det in state.detections:
            color = colors.get(det.label, (255, 255, 255))
            x1, y1, x2, y2 = map(int, det.xyxy)
            cv2.rectangle(overlay, (x1, y1), (x2, y2), color, 2)
            cv2.putText(
                overlay,
                f"{det.label} {det.confidence:.2f}",
                (x1, max(20, y1 - 8)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                color,
                2,
                cv2.LINE_AA,
            )
        if state.sprite and state.reticle_point:
            cv2.circle(overlay, state.sprite.center, 8, (0, 255, 255), -1)
            cv2.circle(overlay, state.reticle_point, 10, (0, 255, 0), 2)
            cv2.line(overlay, state.reticle_point, state.sprite.center, (0, 255, 255), 2)
        if state.ball_point:
            cv2.circle(overlay, state.ball_point, 10, (255, 160, 0), 2)
        cv2.putText(
            overlay,
            status,
            (20, 40),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.9,
            (255, 255, 255),
            2,
            cv2.LINE_AA,
        )
        return overlay

    def show_or_save_overlay(self, overlay: np.ndarray, status: str) -> bool:
        self.last_overlay = overlay
        if self.config["runtime"].get("save_debug"):
            debug_dir = Path(self.config["runtime"].get("debug_dir", "runs/debug"))
            debug_dir.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d-%H%M%S")
            cv2.imwrite(str(debug_dir / f"{stamp}-{status}.png"), overlay)
        if not self.preview:
            return True
        cv2.imshow("rock-catcher", overlay)
        key = cv2.waitKey(1) & 0xFF
        return key not in (27, ord("q"))

    def run_once(self) -> str:
        frame = self.adb.screencap()
        state = self.locate(frame)
        if not state.ready:
            missing = []
            if not state.sprite:
                missing.append("sprite")
            if not state.reticle_point:
                missing.append("reticle")
            if not state.ball_point:
                missing.append("ball_button")
            status = "missing:" + ",".join(missing)
            self.show_or_save_overlay(self.draw_overlay(frame, state, status), status)
            return status

        sprite_point = state.sprite.center  # type: ignore[union-attr]
        step_x, step_y, distance = self.compute_step(sprite_point, state.reticle_point)  # type: ignore[arg-type]
        status = f"distance={distance:.1f}px step=({step_x},{step_y})"
        if self.dry_run:
            self.show_or_save_overlay(self.draw_overlay(frame, state, "dry-run " + status), "dry-run")
            return "dry-run"

        start = GesturePoint(*state.ball_point)  # type: ignore[arg-type]
        if self.controller.supports_streaming:
            return self._run_streaming(start)

        end_tuple = self.clamp_point((start.x + step_x, start.y + step_y), frame)
        end = GesturePoint(*end_tuple)
        duration = int(self.config["aim"]["max_hold_ms"])
        self.controller.drag_once(start, end, duration)
        self.show_or_save_overlay(self.draw_overlay(frame, state, "swipe " + status), "swipe")
        return "swipe"

    def _run_streaming(self, start: GesturePoint) -> str:
        aim_cfg = self.config["aim"]
        release_radius = float(aim_cfg["release_radius_px"])
        max_hold_s = float(aim_cfg["max_hold_ms"]) / 1000
        move_interval_s = float(aim_cfg["move_interval_ms"]) / 1000
        hold_before_s = float(aim_cfg["hold_before_ms"]) / 1000

        finger = start
        status = "stream"
        self.controller.down(finger)
        try:
            time.sleep(hold_before_s)
            started = time.perf_counter()
            while time.perf_counter() - started <= max_hold_s:
                frame = self.adb.screencap()
                state = self.locate(frame)
                if not state.ready:
                    status = "stream-missing"
                    self.show_or_save_overlay(self.draw_overlay(frame, state, status), status)
                    break
                step_x, step_y, distance = self.compute_step(state.sprite.center, state.reticle_point)  # type: ignore[union-attr,arg-type]
                status = f"stream distance={distance:.1f}px step=({step_x},{step_y})"
                self.show_or_save_overlay(self.draw_overlay(frame, state, status), "stream")
                if distance <= release_radius:
                    status = "throw"
                    break
                next_point = self.clamp_point((finger.x + step_x, finger.y + step_y), frame)
                finger = GesturePoint(*next_point)
                self.controller.move(finger)
                time.sleep(move_interval_s)
        finally:
            self.controller.up(finger)
        return status
