from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


class AdbError(RuntimeError):
    """Raised when an adb command fails."""


@dataclass(frozen=True)
class AdbDeviceConfig:
    adb_path: str = "adb"
    serial: str = ""
    timeout_sec: float = 8.0


class AdbDevice:
    def __init__(self, config: AdbDeviceConfig):
        self.config = config

    def _cmd(self, args: Sequence[str]) -> list[str]:
        cmd = [self.config.adb_path]
        if self.config.serial:
            cmd.extend(["-s", self.config.serial])
        cmd.extend(args)
        return cmd

    def run(
        self,
        args: Sequence[str],
        *,
        timeout: float | None = None,
        check: bool = True,
        binary: bool = False,
    ) -> subprocess.CompletedProcess:
        proc = subprocess.run(
            self._cmd(args),
            capture_output=True,
            timeout=timeout or self.config.timeout_sec,
            text=not binary,
        )
        if check and proc.returncode != 0:
            stderr = proc.stderr if not binary else proc.stderr.decode("utf-8", "ignore")
            raise AdbError(f"adb command failed: {' '.join(self._cmd(args))}\n{stderr}")
        return proc

    def shell(self, args: Sequence[str], *, timeout: float | None = None, check: bool = True) -> str:
        proc = self.run(["shell", *map(str, args)], timeout=timeout, check=check)
        return proc.stdout

    def screencap(self):
        import cv2
        import numpy as np

        proc = self.run(["exec-out", "screencap", "-p"], binary=True)
        data = np.frombuffer(proc.stdout, dtype=np.uint8)
        frame = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if frame is None:
            raise AdbError("failed to decode adb screencap output")
        return frame

    def save_screencap(self, path: str | Path) -> Path:
        import cv2

        frame = self.screencap()
        out = Path(path)
        out.parent.mkdir(parents=True, exist_ok=True)
        if not cv2.imwrite(str(out), frame):
            raise AdbError(f"failed to save screenshot: {out}")
        return out

    def tap(self, x: int, y: int) -> None:
        self.shell(["input", "tap", int(x), int(y)])

    def swipe(self, start: tuple[int, int], end: tuple[int, int], duration_ms: int) -> None:
        self.shell(
            [
                "input",
                "swipe",
                int(start[0]),
                int(start[1]),
                int(end[0]),
                int(end[1]),
                max(1, int(duration_ms)),
            ],
            timeout=max(self.config.timeout_sec, duration_ms / 1000 + 2),
        )

    def motionevent(self, action: str, x: int, y: int) -> None:
        self.shell(["input", "motionevent", action.upper(), int(x), int(y)])

    def motionevent_supported(self) -> bool:
        proc = self.run(["shell", "input", "--help"], check=False)
        text = f"{proc.stdout}\n{proc.stderr}"
        return "motionevent" in text.lower()

    def wm_size(self) -> tuple[int, int] | None:
        text = self.shell(["wm", "size"], check=False)
        match = re.search(r"Physical size:\s*(\d+)x(\d+)", text)
        if not match:
            return None
        return int(match.group(1)), int(match.group(2))


def find_connected_devices(adb_path: str = "adb") -> list[str]:
    proc = subprocess.run([adb_path, "devices"], capture_output=True, text=True, timeout=8)
    if proc.returncode != 0:
        raise AdbError(proc.stderr.strip() or "adb devices failed")
    devices: list[str] = []
    for line in proc.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices
