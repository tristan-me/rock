from __future__ import annotations

import argparse
import random
import shutil
from pathlib import Path


IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}


def write_data_yaml(out: Path, classes: list[str]) -> None:
    names = ", ".join(repr(name) for name in classes)
    text = (
        f"path: {out.resolve().as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        f"names: [{names}]\n"
    )
    (out / "data.yaml").write_text(text, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create YOLO dataset split")
    parser.add_argument("--images", default="data/raw", help="Raw image directory")
    parser.add_argument("--labels", default="data/labels", help="YOLO txt label directory")
    parser.add_argument("--out", default="data/yolo", help="Output YOLO dataset directory")
    parser.add_argument("--classes", nargs="+", default=["sprite", "reticle", "ball_button"], help="Class names")
    parser.add_argument("--val-ratio", type=float, default=0.2, help="Validation split ratio")
    parser.add_argument("--seed", type=int, default=7, help="Random seed")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    image_dir = Path(args.images)
    label_dir = Path(args.labels)
    out = Path(args.out)
    images = sorted(p for p in image_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)
    if not images:
        raise SystemExit(f"no images found: {image_dir}")

    random.seed(args.seed)
    random.shuffle(images)
    val_count = max(1, int(len(images) * args.val_ratio)) if len(images) > 1 else 0
    val_set = set(images[:val_count])

    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)

    copied = {"train": 0, "val": 0}
    for image in images:
        split = "val" if image in val_set else "train"
        shutil.copy2(image, out / "images" / split / image.name)
        label = label_dir / f"{image.stem}.txt"
        target_label = out / "labels" / split / f"{image.stem}.txt"
        if label.exists():
            shutil.copy2(label, target_label)
        else:
            target_label.write_text("", encoding="utf-8")
        copied[split] += 1

    write_data_yaml(out, args.classes)
    print(f"created {out} train={copied['train']} val={copied['val']}")
    print(out / "data.yaml")


if __name__ == "__main__":
    main()
