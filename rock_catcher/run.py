from __future__ import annotations

import argparse
import time
from pathlib import Path


def build_detector(config: dict) -> DetectorPipeline:
    from .detectors import DetectorPipeline, TemplateDetector, YoloDetector

    model_cfg = config["model"]
    yolo = YoloDetector(
        model_path=model_cfg["path"],
        class_config=model_cfg["classes"],
        conf=float(model_cfg["conf"]),
        iou=float(model_cfg["iou"]),
    )
    template_cfg = config["templates"]
    templates = TemplateDetector(
        templates={"reticle": template_cfg.get("reticle", ""), "ball_button": template_cfg.get("ball_button", "")},
        threshold=float(template_cfg.get("threshold", 0.82)),
    )
    return DetectorPipeline(yolo, templates)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Android YOLO aiming prototype")
    parser.add_argument("--config", default="config.local.yaml", help="Path to yaml config")
    parser.add_argument("--serial", default="", help="Override adb device serial")
    parser.add_argument("--model", default="", help="Override YOLO model path")
    parser.add_argument("--dry-run", action="store_true", help="Recognize only, do not touch the device")
    parser.add_argument("--arm", action="store_true", help="Actually send touch gestures")
    parser.add_argument("--preview", action="store_true", help="Show OpenCV preview window")
    parser.add_argument("--once", action="store_true", help="Run one attempt and exit")
    parser.add_argument("--list-devices", action="store_true", help="Print connected adb devices and exit")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.list_devices:
        from .adb import find_connected_devices

        for serial in find_connected_devices("adb"):
            print(serial)
        return

    from .adb import AdbDevice, AdbDeviceConfig
    from .aim import Catcher
    from .config import load_config
    from .controllers import make_controller

    cfg_path = Path(args.config)
    config = load_config(cfg_path if cfg_path.exists() else None)

    if args.serial:
        config["device"]["serial"] = args.serial
    if args.model:
        config["model"]["path"] = args.model
    if args.preview:
        config["runtime"]["preview"] = True
    if args.once:
        config["runtime"]["loop"] = False

    dry_run = True
    if args.arm:
        dry_run = False
    elif args.dry_run or config["runtime"].get("dry_run", True):
        dry_run = True

    adb = AdbDevice(
        AdbDeviceConfig(
            adb_path=config["device"]["adb_path"],
            serial=config["device"].get("serial", ""),
            timeout_sec=float(config["capture"].get("timeout_sec", 8)),
        )
    )
    detector = build_detector(config)
    controller = make_controller(config["device"].get("controller", "auto"), adb, config["device"].get("serial", ""))
    catcher = Catcher(
        adb=adb,
        detector=detector,
        controller=controller,
        config=config,
        dry_run=dry_run,
        preview=bool(config["runtime"].get("preview", False)),
    )

    print(f"controller={controller.__class__.__name__} dry_run={dry_run}")
    print(f"model={config['model']['path']}")

    try:
        while True:
            status = catcher.run_once()
            print(status)
            if not config["runtime"].get("loop", True):
                break
            time.sleep(float(config["aim"]["retry_delay_ms"]) / 1000)
    except KeyboardInterrupt:
        print("stopped")
    finally:
        import cv2

        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
