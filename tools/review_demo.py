#!/usr/bin/env python3
"""Unpack and review a Quest Lens session locally; optionally locate visible touch dots."""

import argparse
import json
import math
import shutil
import sys
import zipfile
from pathlib import Path

import cv2
import numpy as np


def unpack(source, folder):
    with zipfile.ZipFile(source) as archive:
        if sorted(archive.namelist()) != ["recording.mp4", "session.json"]:
            raise ValueError("Expected recording.mp4 and session.json in a Quest Lens ZIP")
        video = archive.getinfo("recording.mp4")
        manifest = archive.getinfo("session.json")
        if not 0 < video.file_size <= 100 * 1024 * 1024 or manifest.file_size > 1024 * 1024:
            raise ValueError("Session exceeds the expected size")
        session = json.loads(archive.read(manifest))
        if not isinstance(session, dict) or session.get("schema_version") != 1:
            raise ValueError("Unsupported session schema")
        with archive.open(video) as data, (folder / video.filename).open("wb") as local:
            shutil.copyfileobj(data, local)
    return session, folder / video.filename


def frame_at(video, timestamp_ms):
    video.set(cv2.CAP_PROP_POS_MSEC, max(0, timestamp_ms))
    success, frame = video.read()
    if not success:
        raise ValueError(f"Could not read video near {timestamp_ms / 1000:.3f}s")
    return frame


def template_for(video, sample, radius, scale):
    seconds, sample_x, sample_y = sample
    frame = frame_at(video, round(seconds * 1000))
    x, y = round(sample_x), round(sample_y)
    if not (radius <= x < frame.shape[1] - radius and radius <= y < frame.shape[0] - radius):
        raise ValueError("Calibration point is too close to the edge")
    cropped = frame[y - radius:y + radius + 1, x - radius:x + radius + 1]
    gray = cv2.cvtColor(cropped, cv2.COLOR_BGR2GRAY)
    size = max(7, round((2 * radius + 1) * scale)) | 1
    template = cv2.resize(gray, (size, size), interpolation=cv2.INTER_AREA)
    mask = np.zeros((size, size), dtype=np.uint8)
    cv2.circle(mask, (size // 2, size // 2), size // 2, 255, -1)
    if cv2.meanStdDev(template, mask=mask)[1][0, 0] < 4:
        raise ValueError("Calibration has low contrast; use a visible dot on a flat, darker area")
    return template, mask


def find_candidates(video, sample, radius, threshold, fps, width, height):
    # A matched dot must show a local change to start a track. Static circular
    # UI icons can otherwise match a template on every frame.
    scale = min(1.0, 800 / max(width, height))
    template, mask = template_for(video, sample, radius, scale)
    half = template.shape[0] // 2
    frame_size = (round(width * scale), round(height * scale))
    video.set(cv2.CAP_PROP_POS_FRAMES, 0)
    frame_number = 0
    previous = None
    current = None
    tracks = []

    while True:
        success, frame = video.read()
        if not success:
            break
        decoder_time = video.get(cv2.CAP_PROP_POS_MSEC)
        time_ms = round(decoder_time if decoder_time > 0 else frame_number * 1000 / fps)
        frame_number += 1
        gray = cv2.cvtColor(cv2.resize(frame, frame_size, interpolation=cv2.INTER_AREA),
                            cv2.COLOR_BGR2GRAY)
        scores = cv2.matchTemplate(gray, template, cv2.TM_CCOEFF_NORMED, mask=mask)
        scores[~np.isfinite(scores)] = -1
        scores[np.abs(scores) > 1.01] = -1

        if current and time_ms - current["end_ms"] > 120:
            tracks.append(current)
            current = None
        if current:
            cx, cy = current["last_small"]
            search_radius = max(half * 3, 12)
            left, top = max(0, cx - half - search_radius), max(0, cy - half - search_radius)
            right = min(scores.shape[1], cx - half + search_radius + 1)
            bottom = min(scores.shape[0], cy - half + search_radius + 1)
            region = scores[top:bottom, left:right]
        else:
            left, top, region = 0, 0, scores
            if previous is not None:
                change = cv2.absdiff(gray, previous)
                change = cv2.boxFilter(change, cv2.CV_32F, template.shape)
                center_change = change[half:half + scores.shape[0], half:half + scores.shape[1]]
                region = np.where(center_change >= 3.0, scores, -1).astype(np.float32)

        _, match, _, point = cv2.minMaxLoc(region)
        if match >= threshold and (previous is not None or current is not None):
            x, y = left + point[0] + half, top + point[1] + half
            item = {"at_ms": time_ms, "x_px": min(width - 1, round(x / scale)),
                    "y_px": min(height - 1, round(y / scale))}
            if current is None:
                current = {"start_ms": time_ms, "end_ms": time_ms, "score": 0.0, "points": []}
            if not current["points"] or time_ms > current["points"][-1]["at_ms"]:
                current["points"].append(item)
            current["end_ms"] = time_ms
            current["score"] = max(current["score"], round(match, 3))
            current["last_small"] = (x, y)
        previous = gray

    if current:
        tracks.append(current)

    for track in tracks:
        track.pop("last_small")
        points = track["points"]
        first = points[0]
        travel = max(math.hypot(p["x_px"] - first["x_px"], p["y_px"] - first["y_px"]) for p in points)
        track["kind"] = "swipe_candidate" if travel > 2 * radius else "tap_candidate"
        for point in points:
            point["x_normalized"] = round(point["x_px"] / width, 6)
            point["y_normalized"] = round(point["y_px"] / height, 6)
    return tracks


def write_frame(video, destination, timestamp):
    frame = frame_at(video, timestamp)
    success, image = cv2.imencode(".png", frame)
    if not success:
        raise OSError(f"Could not encode {destination}")
    destination.write_bytes(image.tobytes())


def review(args):
    args.output.mkdir(parents=True, exist_ok=False)
    session, source = unpack(args.zip, args.output)
    video = cv2.VideoCapture(str(source))
    try:
        if not video.isOpened():
            raise ValueError("Could not open recording.mp4")
        fps = video.get(cv2.CAP_PROP_FPS)
        width = round(video.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = round(video.get(cv2.CAP_PROP_FRAME_HEIGHT))
        count = round(video.get(cv2.CAP_PROP_FRAME_COUNT))
        if not 1 <= fps <= 120 or not 16 <= min(width, height) or count < 1:
            raise ValueError("Invalid video dimensions or frame rate")

        bookmarks = session.get("bookmarks", [])
        last_ms = round((count - 1) * 1000 / fps)
        for index, bookmark in enumerate(bookmarks, 1):
            milliseconds = min(last_ms, int(bookmark["suggested_review_ms"]))
            write_frame(video, args.output / f"quest-page-{index:02d}.png", milliseconds)
        for index, seconds in enumerate(args.frame, 1):
            write_frame(video, args.output / f"frame-{index:02d}.png", round(seconds * 1000))

        candidates = None
        if args.sample:
            candidates = find_candidates(video, args.sample, args.radius, args.threshold, fps, width, height)
        report = {
            "session": session,
            "touch_analysis": "visual_candidates_only" if candidates is not None else "not_run_without_calibration",
            "touch_candidates": candidates,
            "video_coordinates": {"width": width, "height": height},
        }
        (args.output / "timeline.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    finally:
        video.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", type=Path)
    parser.add_argument("output", type=Path, help="New review directory")
    parser.add_argument("--frame", type=float, action="append", default=[], help="Export a frame at this many seconds")
    parser.add_argument("--sample", type=float, nargs=3, metavar=("SECONDS", "X", "Y"),
                        help="Calibrate on a visible Show taps dot in this recording (video pixel coordinates)")
    parser.add_argument("--radius", type=int, default=13, help="Template radius in video pixels")
    parser.add_argument("--threshold", type=float, default=0.86, help="Template match threshold")
    args = parser.parse_args()
    if not 6 <= args.radius <= 64 or not 0.5 <= args.threshold <= 0.99:
        parser.error("Radius must be 6–64 and threshold 0.50–0.99")
    try:
        review(args)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile, cv2.error) as error:
        sys.exit(f"Review failed: {error}")


if __name__ == "__main__":
    main()
