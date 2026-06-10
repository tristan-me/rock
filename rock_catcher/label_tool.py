from __future__ import annotations

import argparse
from pathlib import Path


IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}


class LabelSession:
    def __init__(self, image_paths: list[Path], label_dir: Path, classes: list[str]):
        self.image_paths = image_paths
        self.label_dir = label_dir
        self.classes = classes
        self.index = 0
        self.current_class = 0
        self.boxes: list[tuple[int, int, int, int, int]] = []
        self.drag_start: tuple[int, int] | None = None
        self.drag_current: tuple[int, int] | None = None
        self.image: np.ndarray | None = None

    def label_path(self, image_path: Path) -> Path:
        return self.label_dir / f"{image_path.stem}.txt"

    def load(self) -> None:
        image_path = self.image_paths[self.index]
        self.image = cv2.imread(str(image_path))
        if self.image is None:
            raise RuntimeError(f"failed to read image: {image_path}")
        self.boxes = []
        label_path = self.label_path(image_path)
        if not label_path.exists():
            return
        h, w = self.image.shape[:2]
        for line in label_path.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) != 5:
                continue
            cls_id = int(parts[0])
            cx, cy, bw, bh = map(float, parts[1:])
            x1 = int(round((cx - bw / 2) * w))
            y1 = int(round((cy - bh / 2) * h))
            x2 = int(round((cx + bw / 2) * w))
            y2 = int(round((cy + bh / 2) * h))
            self.boxes.append((cls_id, x1, y1, x2, y2))

    def save(self) -> None:
        if self.image is None:
            return
        self.label_dir.mkdir(parents=True, exist_ok=True)
        h, w = self.image.shape[:2]
        lines = []
        for cls_id, x1, y1, x2, y2 in self.boxes:
            left, right = sorted((max(0, x1), min(w - 1, x2)))
            top, bottom = sorted((max(0, y1), min(h - 1, y2)))
            if right - left < 2 or bottom - top < 2:
                continue
            cx = ((left + right) / 2) / w
            cy = ((top + bottom) / 2) / h
            bw = (right - left) / w
            bh = (bottom - top) / h
            lines.append(f"{cls_id} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
        self.label_path(self.image_paths[self.index]).write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")

    def draw(self) -> np.ndarray:
        assert self.image is not None
        canvas = self.image.copy()
        palette = [(0, 255, 255), (0, 220, 0), (255, 160, 0), (255, 0, 255), (255, 255, 0)]
        for cls_id, x1, y1, x2, y2 in self.boxes:
            color = palette[cls_id % len(palette)]
            cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)
            cv2.putText(canvas, self.classes[cls_id], (x1, max(20, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.65, color, 2)
        if self.drag_start and self.drag_current:
            color = palette[self.current_class % len(palette)]
            cv2.rectangle(canvas, self.drag_start, self.drag_current, color, 1)
        title = (
            f"{self.index + 1}/{len(self.image_paths)} "
            f"class={self.current_class}:{self.classes[self.current_class]} "
            "keys: 0-9 class, u undo, s save, n next, p prev, q quit"
        )
        cv2.putText(canvas, title, (16, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (255, 255, 255), 2)
        return canvas

    def mouse(self, event: int, x: int, y: int, flags: int, param: object) -> None:
        if event == cv2.EVENT_LBUTTONDOWN:
            self.drag_start = (x, y)
            self.drag_current = (x, y)
        elif event == cv2.EVENT_MOUSEMOVE and self.drag_start:
            self.drag_current = (x, y)
        elif event == cv2.EVENT_LBUTTONUP and self.drag_start:
            x1, y1 = self.drag_start
            x2, y2 = x, y
            if abs(x2 - x1) > 2 and abs(y2 - y1) > 2:
                self.boxes.append((self.current_class, x1, y1, x2, y2))
            self.drag_start = None
            self.drag_current = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Tiny YOLO label tool")
    parser.add_argument("images", help="Image directory")
    parser.add_argument("--labels", default="data/labels", help="Output label directory")
    parser.add_argument("--classes", nargs="+", default=["sprite", "reticle", "ball_button"], help="Class names")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    global cv2, np
    import cv2
    import numpy as np

    image_paths = sorted(p for p in Path(args.images).iterdir() if p.suffix.lower() in IMAGE_EXTS)
    if not image_paths:
        raise SystemExit(f"no images found in {args.images}")
    session = LabelSession(image_paths, Path(args.labels), args.classes)
    cv2.namedWindow("label-tool", cv2.WINDOW_NORMAL)
    cv2.setMouseCallback("label-tool", session.mouse)
    session.load()
    while True:
        cv2.imshow("label-tool", session.draw())
        key = cv2.waitKey(20) & 0xFF
        if key in (255,):
            continue
        if key in (27, ord("q")):
            session.save()
            break
        if ord("0") <= key <= ord("9"):
            cls_id = key - ord("0")
            if cls_id < len(session.classes):
                session.current_class = cls_id
        elif key == ord("u") and session.boxes:
            session.boxes.pop()
        elif key == ord("s"):
            session.save()
        elif key == ord("n"):
            session.save()
            session.index = min(len(session.image_paths) - 1, session.index + 1)
            session.load()
        elif key == ord("p"):
            session.save()
            session.index = max(0, session.index - 1)
            session.load()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
