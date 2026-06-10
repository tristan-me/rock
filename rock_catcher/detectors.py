from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np


@dataclass(frozen=True)
class Detection:
    label: str
    confidence: float
    xyxy: tuple[float, float, float, float]
    source: str = "model"

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.xyxy
        return int(round((x1 + x2) / 2)), int(round((y1 + y2) / 2))

    @property
    def area(self) -> float:
        x1, y1, x2, y2 = self.xyxy
        return max(0.0, x2 - x1) * max(0.0, y2 - y1)


def _alias_lookup(class_config: dict[str, list[str]]) -> dict[str, str]:
    lookup: dict[str, str] = {}
    for canonical, aliases in class_config.items():
        lookup[canonical.lower()] = canonical
        for alias in aliases:
            lookup[str(alias).lower()] = canonical
    return lookup


class YoloDetector:
    def __init__(self, model_path: str | Path, class_config: dict[str, list[str]], conf: float, iou: float):
        self.model_path = Path(model_path)
        self.class_config = class_config
        self.conf = float(conf)
        self.iou = float(iou)
        self._model: Any | None = None
        self._aliases = _alias_lookup(class_config)

    @property
    def available(self) -> bool:
        return self.model_path.exists()

    def _load(self) -> Any:
        if self._model is None:
            if not self.available:
                raise FileNotFoundError(f"YOLO model not found: {self.model_path}")
            from ultralytics import YOLO

            self._model = YOLO(str(self.model_path))
        return self._model

    def detect(self, frame: np.ndarray) -> list[Detection]:
        model = self._load()
        results = model.predict(frame, conf=self.conf, iou=self.iou, verbose=False)
        if not results:
            return []

        result = results[0]
        names = getattr(result, "names", None) or getattr(model, "names", {})
        detections: list[Detection] = []
        if result.boxes is None:
            return detections

        for box in result.boxes:
            cls_id = int(box.cls.item())
            raw_name = str(names.get(cls_id, cls_id)).lower()
            label = self._aliases.get(raw_name)
            if not label:
                continue
            conf = float(box.conf.item())
            xyxy = tuple(float(v) for v in box.xyxy[0].tolist())
            detections.append(Detection(label=label, confidence=conf, xyxy=xyxy, source="yolo"))
        return detections


class TemplateDetector:
    def __init__(self, templates: dict[str, str], threshold: float = 0.82):
        self.threshold = float(threshold)
        self.templates: dict[str, np.ndarray] = {}
        for label, path in templates.items():
            if not path:
                continue
            template_path = Path(path)
            if not template_path.exists():
                continue
            template = cv2.imread(str(template_path), cv2.IMREAD_GRAYSCALE)
            if template is not None:
                self.templates[label] = template

    def detect(self, frame: np.ndarray) -> list[Detection]:
        if not self.templates:
            return []
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        detections: list[Detection] = []
        for label, template in self.templates.items():
            if gray.shape[0] < template.shape[0] or gray.shape[1] < template.shape[1]:
                continue
            result = cv2.matchTemplate(gray, template, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(result)
            if max_val < self.threshold:
                continue
            h, w = template.shape[:2]
            x1, y1 = max_loc
            detections.append(
                Detection(
                    label=label,
                    confidence=float(max_val),
                    xyxy=(float(x1), float(y1), float(x1 + w), float(y1 + h)),
                    source="template",
                )
            )
        return detections


class DetectorPipeline:
    def __init__(self, yolo: YoloDetector | None, templates: TemplateDetector | None):
        self.yolo = yolo
        self.templates = templates

    def detect(self, frame: np.ndarray) -> list[Detection]:
        detections: list[Detection] = []
        if self.yolo and self.yolo.available:
            detections.extend(self.yolo.detect(frame))
        if self.templates:
            existing = {d.label for d in detections}
            for detection in self.templates.detect(frame):
                if detection.label not in existing:
                    detections.append(detection)
        return detections


def pick_best(detections: list[Detection], label: str, *, min_conf: float = 0.0) -> Detection | None:
    candidates = [d for d in detections if d.label == label and d.confidence >= min_conf]
    if not candidates:
        return None
    return max(candidates, key=lambda d: (d.confidence, d.area))
