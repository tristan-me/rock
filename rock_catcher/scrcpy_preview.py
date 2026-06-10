from __future__ import annotations

import argparse
import subprocess


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Start scrcpy preview")
    parser.add_argument("--scrcpy", default="scrcpy", help="scrcpy executable path")
    parser.add_argument("--serial", default="", help="adb serial")
    parser.add_argument("--stay-awake", action="store_true", help="Keep device awake")
    parser.add_argument("--max-size", type=int, default=0, help="scrcpy --max-size value")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    cmd = [args.scrcpy]
    if args.serial:
        cmd.extend(["--serial", args.serial])
    if args.stay_awake:
        cmd.append("--stay-awake")
    if args.max_size:
        cmd.extend(["--max-size", str(args.max_size)])
    print(" ".join(cmd))
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
