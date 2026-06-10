from __future__ import annotations

import argparse
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train YOLO detector")
    parser.add_argument("--data", default="data/yolo/data.yaml", help="YOLO data.yaml path")
    parser.add_argument("--base", default="yolov8n.pt", help="Base YOLO model")
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--imgsz", type=int, default=960)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--out", default="models", help="Project output directory")
    parser.add_argument("--name", default="rock-catcher", help="Run name")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    from ultralytics import YOLO

    Path(args.out).mkdir(parents=True, exist_ok=True)
    model = YOLO(args.base)
    result = model.train(
        data=args.data,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        project=args.out,
        name=args.name,
        exist_ok=True,
    )
    print(result)
    print(f"best model is usually under: {Path(args.out) / args.name / 'weights' / 'best.pt'}")


if __name__ == "__main__":
    main()
