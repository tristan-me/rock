from __future__ import annotations

import time
from dataclasses import dataclass

from .adb import AdbDevice, AdbError


class ControllerError(RuntimeError):
    pass


@dataclass(frozen=True)
class GesturePoint:
    x: int
    y: int

    def as_tuple(self) -> tuple[int, int]:
        return self.x, self.y


class BaseController:
    supports_streaming = False

    def down(self, point: GesturePoint) -> None:
        raise NotImplementedError

    def move(self, point: GesturePoint) -> None:
        raise NotImplementedError

    def up(self, point: GesturePoint) -> None:
        raise NotImplementedError

    def drag_once(self, start: GesturePoint, end: GesturePoint, duration_ms: int) -> None:
        raise NotImplementedError


class AdbMotionEventController(BaseController):
    supports_streaming = True

    def __init__(self, device: AdbDevice):
        self.device = device

    def down(self, point: GesturePoint) -> None:
        self.device.motionevent("DOWN", point.x, point.y)

    def move(self, point: GesturePoint) -> None:
        self.device.motionevent("MOVE", point.x, point.y)

    def up(self, point: GesturePoint) -> None:
        self.device.motionevent("UP", point.x, point.y)

    def drag_once(self, start: GesturePoint, end: GesturePoint, duration_ms: int) -> None:
        self.down(start)
        time.sleep(max(0, duration_ms) / 1000)
        self.move(end)
        self.up(end)


class AdbSwipeController(BaseController):
    supports_streaming = False

    def __init__(self, device: AdbDevice):
        self.device = device

    def drag_once(self, start: GesturePoint, end: GesturePoint, duration_ms: int) -> None:
        self.device.swipe(start.as_tuple(), end.as_tuple(), duration_ms)


class AirtestSwipeController(BaseController):
    supports_streaming = False

    def __init__(self, serial: str = ""):
        try:
            from airtest.core.api import connect_device
        except Exception as exc:  # pragma: no cover - optional dependency
            raise ControllerError("Airtest is not installed or cannot be imported") from exc

        uri = f"Android:///{serial}" if serial else "Android:///"
        self.device = connect_device(uri)

    def drag_once(self, start: GesturePoint, end: GesturePoint, duration_ms: int) -> None:
        duration = max(0.05, duration_ms / 1000)
        self.device.swipe(start.as_tuple(), end.as_tuple(), duration=duration)


def make_controller(kind: str, device: AdbDevice, serial: str = "") -> BaseController:
    normalized = (kind or "auto").lower()
    if normalized == "auto":
        try:
            if device.motionevent_supported():
                return AdbMotionEventController(device)
        except AdbError:
            pass
        return AdbSwipeController(device)
    if normalized == "adb_motionevent":
        return AdbMotionEventController(device)
    if normalized == "adb_swipe":
        return AdbSwipeController(device)
    if normalized == "airtest":
        return AirtestSwipeController(serial)
    raise ValueError(f"unknown controller: {kind}")
