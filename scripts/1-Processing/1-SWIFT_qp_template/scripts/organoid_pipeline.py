# "organoid_pipeline.py"
import argparse
import csv
import cv2
import numpy as np
from pathlib import Path
from ultralytics import YOLO, SAM
from ultralytics.models.sam import SAM2VideoPredictor

TRACK_VIDEO_FPS = 2.0  # arbitrary; only affects the temp video container, not results


def parse_args():
    parser = argparse.ArgumentParser(description="Detect organoids with YOLO and segment/track with SAM2")
    parser.add_argument("img_path", type=str,
                         help="Path to input image. In --track mode, this must be the reference "
                              "(first) frame, and it must live inside a folder containing all the "
                              "ordered timelapse frames (that folder is used as the frame sequence).")
    parser.add_argument("--mode", choices=["detect", "segment", "full"], default="full",
                         help="'detect' (YOLO only), 'segment' (SAM2 only, needs --boxes), or 'full' (both)")
    parser.add_argument("--yolo-weights", type=str, default=None, help="YOLO weights (detect/full)")
    parser.add_argument("--sam-weights", type=str, default="sam2_b.pt", help="SAM2 weights (segment/full)")
    parser.add_argument("--conf", type=float, default=0.25, help="YOLO/SAM2 confidence threshold")
    parser.add_argument("--boxes", type=str, default=None,
                         help="Boxes CSV path (output in detect/full, input in segment). In --track mode "
                              "these boxes apply to the reference (first) frame only.")
    parser.add_argument("--out", type=str, default=None,
                         help="Output path. Single-image segment: label TIF path. "
                              "--track segment: output directory for per-timepoint label TIFs.")
    parser.add_argument("--track", action="store_true",
                         help="Segment step uses SAM2 video tracking (memory/propagation) across all "
                              "frames found alongside img_path, instead of segmenting a single image.")
    return parser.parse_args()


def save_boxes_csv(path, boxes, scores, classes):
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["x1", "y1", "x2", "y2", "score", "class"])
        for box, score, cls in zip(boxes, scores, classes):
            writer.writerow([*box, score, cls])


def load_boxes_csv(path):
    boxes = []
    with open(path, "r", newline="") as f:
        reader = csv.reader(f)
        next(reader)  # header
        for row in reader:
            if not row:
                continue
            boxes.append([float(v) for v in row[:4]])
    return np.array(boxes, dtype=np.float32)


def resolve_boxes(args, boxes_xyxy=None):
    """Use boxes passed in-memory (from a prior detect step) or load them from --boxes CSV."""
    if boxes_xyxy is None:
        if not args.boxes:
            raise ValueError("--boxes is required when boxes are not produced by a prior detect step")
        boxes_xyxy = load_boxes_csv(args.boxes)
        print(f"Loaded {len(boxes_xyxy)} boxes from {args.boxes}")
    return boxes_xyxy


def masks_to_label_image(masks, shape):
    label_img = np.zeros(shape, dtype=np.uint16)
    for i, mask in enumerate(masks, start=1):
        label_img[mask.astype(bool)] = i
    return label_img


def run_detect(args, img_path):
    if not args.yolo_weights:
        raise ValueError("--yolo-weights is required for 'detect' mode")

    yolo_model = YOLO(args.yolo_weights)
    det_results = yolo_model.predict(str(img_path), conf=args.conf, verbose=False)
    r = det_results[0]

    boxes_xyxy = r.boxes.xyxy.cpu().numpy()
    scores = r.boxes.conf.cpu().numpy()
    classes = r.boxes.cls.cpu().numpy()

    print(f"Detected {len(boxes_xyxy)} organoids")

    boxes_path = Path(args.boxes) if args.boxes else img_path.with_name(f"{img_path.stem}_boxes.csv")
    save_boxes_csv(boxes_path, boxes_xyxy, scores, classes)
    print(f"Boxes saved to: {boxes_path}")
    return boxes_xyxy


def run_segment(args, img_path, boxes_xyxy=None):
    boxes_xyxy = resolve_boxes(args, boxes_xyxy)

    if len(boxes_xyxy) == 0:
        print("No boxes to segment.")
        return

    sam_model = SAM(args.sam_weights)
    sam_results = sam_model.predict(str(img_path), bboxes=boxes_xyxy.tolist())
    sam_r = sam_results[0]

    masks = sam_r.masks.data.cpu().numpy()
    print(f"Mask array shape: {masks.shape}")

    label_img = masks_to_label_image(masks, masks.shape[1:])

    out_path = Path(args.out) if args.out else img_path.with_name(f"{img_path.stem}_labels.tif")
    if not cv2.imwrite(str(out_path), label_img):
        raise IOError(f"Failed to write TIF to {out_path}")
    print(f"Label image saved to: {out_path}")


def _list_frames(frames_dir):
    exts = (".jpg", ".jpeg", ".png")
    frame_paths = sorted(p for p in frames_dir.iterdir() if p.suffix.lower() in exts)
    if not frame_paths:
        raise ValueError(f"No frame images found in {frames_dir}")
    return frame_paths


def run_track(args, img_path, boxes_xyxy=None):
    """SAM2 video tracking: propagate first-frame boxes through all frames in img_path.parent."""
    boxes_xyxy = resolve_boxes(args, boxes_xyxy)

    if len(boxes_xyxy) == 0:
        print("No boxes to track.")
        return

    frames_dir = img_path.parent
    frame_paths = _list_frames(frames_dir)
    print(f"Found {len(frame_paths)} frames in {frames_dir}")

    first = cv2.imread(str(frame_paths[0]))
    if first is None:
        raise IOError(f"Failed to read first frame: {frame_paths[0]}")
    h, w = first.shape[:2]

    # SAM2VideoPredictor needs an actual video source; assemble one from the frame sequence.
    tmp_video_path = frames_dir / "_sam2_track_tmp.mp4"
    writer = cv2.VideoWriter(str(tmp_video_path), cv2.VideoWriter_fourcc(*"mp4v"), TRACK_VIDEO_FPS, (w, h))
    try:
        for p in frame_paths:
            frame = cv2.imread(str(p))
            if frame is None:
                raise IOError(f"Failed to read frame: {p}")
            writer.write(frame)
    finally:
        writer.release()

    overrides = dict(conf=args.conf, task="segment", mode="predict", model=args.sam_weights)
    predictor = SAM2VideoPredictor(overrides=overrides)
    # Boxes are only supported as prompts on the first frame; SAM2 then propagates them via memory attention.
    results = predictor(source=str(tmp_video_path), bboxes=boxes_xyxy.tolist(), stream=True)

    out_dir = Path(args.out) if args.out else frames_dir / f"{frames_dir.name}_tracked_labels"
    out_dir.mkdir(parents=True, exist_ok=True)

    n_frames = 0
    for t, r in enumerate(results):
        if r.masks is not None:
            masks = r.masks.data.cpu().numpy()
        else:
            masks = np.zeros((0, h, w), dtype=bool)
        label_img = masks_to_label_image(masks, (h, w))
        out_path = out_dir / f"labels_t{t:04d}.tif"
        if not cv2.imwrite(str(out_path), label_img):
            raise IOError(f"Failed to write TIF to {out_path}")
        n_frames += 1

    tmp_video_path.unlink(missing_ok=True)
    print(f"Tracked {len(boxes_xyxy)} organoids across {n_frames} frames")
    print(f"Label images saved to: {out_dir}")


def main():
    args = parse_args()
    img_path = Path(args.img_path)

    if args.mode == "detect":
        run_detect(args, img_path)
    elif args.mode == "segment":
        if args.track:
            run_track(args, img_path)
        else:
            run_segment(args, img_path)
    elif args.mode == "full":
        boxes_xyxy = run_detect(args, img_path)
        if args.track:
            run_track(args, img_path, boxes_xyxy)
        else:
            run_segment(args, img_path, boxes_xyxy)


if __name__ == "__main__":
    main()