from __future__ import annotations

import argparse
import time
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Capture Android screenshots for training")
    parser.add_argument("--out", default="data/raw", help="Output image directory")
    parser.add_argument("--count", type=int, default=100, help="Number of screenshots to capture")
    parser.add_argument("--interval", type=float, default=0.5, help="Seconds between screenshots")
    parser.add_argument("--adb", default="adb", help="adb executable path")
    parser.add_argument("--serial", default="", help="adb device serial")
    parser.add_argument("--prefix", default="cap", help="Filename prefix")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    import cv2

    from .adb import AdbDevice, AdbDeviceConfig

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    adb = AdbDevice(AdbDeviceConfig(adb_path=args.adb, serial=args.serial))
    for index in range(args.count):
        frame = adb.screencap()
        name = f"{args.prefix}-{time.strftime('%Y%m%d-%H%M%S')}-{index:04d}.png"
        path = out / name
        if not cv2.imwrite(str(path), frame):
            raise RuntimeError(f"failed to write {path}")
        print(path)
        time.sleep(max(0, args.interval))


if __name__ == "__main__":
    main()
