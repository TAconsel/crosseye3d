#!/usr/bin/env python3
"""Head tracker for CrossEye 3D.

Finds your eyes in a camera image, estimates where your head is relative to the centre of the screen, and sends
the position to Minecraft over UDP. The packet layout is the same as opentrack's "UDP over network" output:
six little-endian doubles (x, y, z, yaw, pitch, roll).

Coordinates sent (centimetres / degrees), as seen by you sitting in front of the screen:
    x   to your right
    y   up
    z   distance from the screen plane towards you
    roll  positive when your right eye is higher than your left eye

Tracking runs at the camera's full frame rate. A face detector (YuNet) gives absolute but jittery eye positions;
optical flow on dozens of points on the face gives very steady frame-to-frame motion that slowly drifts. The two
are fused: optical flow carries the motion and the detector gently pulls it back, which removes most of the jitter.

Works with any webcam (distance estimated from the size of your face). With an Intel RealSense R200 the depth
stream is used to calibrate that distance estimate automatically.
"""

import argparse
import math
import signal
import socket
import struct
import threading
import os
import time
from pathlib import Path

# OpenCV's V4L2 backend ignores CAP_PROP_READ_TIMEOUT_MSEC and waits 10 s for a frame by default. Wait 1 s, so a
# stalled camera is noticed and reopened quickly. Must be set before cv2 is imported.
os.environ.setdefault("OPENCV_VIDEOIO_V4L_SELECT_TIMEOUT", "1")

import cv2
import numpy as np

MODEL = Path(__file__).resolve().parent / "models" / "face_detection_yunet_2023mar.onnx"


class OneEuroFilter:
    """Adaptive low-pass filter: smooth when still, responsive when moving (Casiez et al. 2012)."""

    def __init__(self, min_cutoff=1.0, beta=0.05, d_cutoff=1.0):
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
        self.x = None
        self.dx = 0.0
        self.t = None

    @staticmethod
    def _alpha(cutoff, dt):
        tau = 1.0 / (2.0 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt)

    def __call__(self, value, t):
        if self.x is None:
            self.x, self.t = value, t
            return value
        dt = max(t - self.t, 1e-3)
        self.t = t
        dx = (value - self.x) / dt
        self.dx += self._alpha(self.d_cutoff, dt) * (dx - self.dx)
        cutoff = self.min_cutoff + self.beta * abs(self.dx)
        self.x += self._alpha(cutoff, dt) * (value - self.x)
        return self.x

    def reset(self):
        self.x = None


class FrameGrabber(threading.Thread):
    """Reads frames as fast as the camera delivers them and keeps only the newest, so processing never lags.

    If the camera stops delivering frames (the RealSense R200 sometimes stalls), the device is closed and reopened.
    """

    STALL_SECONDS = 2.0

    def __init__(self, open_camera):
        super().__init__(daemon=True)
        self.open_camera = open_camera
        self.cap = open_camera()
        self.cond = threading.Condition()
        self.frame = None
        self.stamp = 0.0
        self.count = 0
        self.last_frame_time = time.time()
        self.reopens = 0
        self.running = True

    def run(self):
        while self.running:
            ok, frame = self.cap.read()
            now = time.time()
            if not ok:
                if now - self.last_frame_time > self.STALL_SECONDS and self.running:
                    self.reopens += 1
                    self.cap.release()
                    time.sleep(0.5)
                    self.cap = self.open_camera()
                    self.last_frame_time = time.time()
                else:
                    time.sleep(0.005)
                continue
            with self.cond:
                self.frame, self.stamp = frame, now
                self.last_frame_time = now
                self.count += 1
                self.cond.notify_all()

    def stop(self):
        self.running = False
        self.join(timeout=2.0)
        self.cap.release()

    def stalled_for(self):
        return time.time() - self.last_frame_time

    def next(self, last_count, timeout=1.0):
        with self.cond:
            self.cond.wait_for(lambda: self.count != last_count, timeout)
            return self.frame, self.stamp, self.count


class DepthReader(threading.Thread):
    """Keeps the latest frame of a V4L2 Z16 depth stream (RealSense R200: /dev/video2, 628x469)."""

    def __init__(self, device, width, height):
        super().__init__(daemon=True)
        self.width, self.height = width, height
        self.cap = cv2.VideoCapture(device, cv2.CAP_V4L2)
        self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"Z16 "))
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        self.cap.set(cv2.CAP_PROP_CONVERT_RGB, 0)
        self.frame = None
        self.ok = self.cap.isOpened()

    def run(self):
        expected = self.width * self.height
        while self.ok:
            ok, raw = self.cap.read()
            if not ok:
                time.sleep(0.01)
                continue
            data = raw.reshape(-1).view(np.uint16)
            if data.size >= expected:
                self.frame = data[:expected].reshape(self.height, self.width)

    def stop(self):
        self.ok = False
        self.join(timeout=2.0)
        self.cap.release()


class FaceTracker:
    """Fuses a face detector with optical flow.

    State (image pixels): eye midpoint, face size (detector box width) and roll. Every frame the state is moved by
    the similarity transform fitted to optical-flow points on the face, then pulled towards the detector's
    measurement by a small gain, which cancels flow drift while filtering out most of the detector's jitter.
    """

    def __init__(self, model, size, correction=0.04):
        self.detector = cv2.FaceDetectorYN.create(str(model), "", size, 0.6, 0.3, 50)
        self.correction = correction
        self.prev_gray = None
        self.points = None
        self.state = None  # [u, v, size, roll_degrees]
        self.ipd_ratio = None  # eye spacing / face box width, learned slowly
        self.box = None
        self.eyes = None

    def reset(self):
        self.prev_gray = self.points = self.state = None

    def _seed(self, gray, box):
        x, y, w, h = (int(v) for v in box)
        mask = np.zeros_like(gray)
        mask[max(0, y + h // 8): y + h * 7 // 8, max(0, x + w // 6): x + w * 5 // 6] = 255
        return cv2.goodFeaturesToTrack(gray, 100, 0.005, 3, mask=mask, blockSize=7)

    def update(self, frame):
        gray = cv2.GaussianBlur(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), (5, 5), 0)
        _, faces = self.detector.detect(frame)
        measurement = None
        if faces is not None and len(faces):
            face = max(faces, key=lambda f: f[2] * f[3])
            (u1, v1), (u2, v2) = face[4:6], face[6:8]
            ipd = math.hypot(u2 - u1, v2 - v1)
            # Your right eye appears on the image's left.
            (ru, rv), (lu, lv) = sorted([(u1, v1), (u2, v2)])
            roll = math.degrees(math.atan2(lv - rv, lu - ru))
            measurement = np.array([(u1 + u2) / 2, (v1 + v2) / 2, face[2], roll])
            ratio = ipd / face[2]
            self.ipd_ratio = ratio if self.ipd_ratio is None else self.ipd_ratio + 0.01 * (ratio - self.ipd_ratio)
            self.box, self.eyes = face[:4], ((u1, v1), (u2, v2))

        if self.state is None or self.prev_gray is None or self.points is None or len(self.points) < 8:
            if measurement is None:
                self.reset()
                return None
            self.state = measurement.copy()
        else:
            nxt, st, _ = cv2.calcOpticalFlowPyrLK(self.prev_gray, gray, self.points, None, winSize=(31, 31), maxLevel=3)
            back, st2, _ = cv2.calcOpticalFlowPyrLK(gray, self.prev_gray, nxt, None, winSize=(31, 31), maxLevel=3)
            good = (st.reshape(-1) == 1) & (st2.reshape(-1) == 1) \
                & (np.linalg.norm((back - self.points).reshape(-1, 2), axis=1) < 1.5)
            if good.sum() >= 8:
                a, b = self.points.reshape(-1, 2)[good], nxt.reshape(-1, 2)[good]
                m, _ = cv2.estimateAffinePartial2D(a, b, method=cv2.LMEDS)
                if m is not None:
                    u, v = self.state[:2]
                    self.state[0] = m[0, 0] * u + m[0, 1] * v + m[0, 2]
                    self.state[1] = m[1, 0] * u + m[1, 1] * v + m[1, 2]
                    self.state[2] *= math.hypot(m[0, 0], m[1, 0])
                    # Same convention as the detector's roll: image y points down, so this is clockwise on screen.
                    self.state[3] += math.degrees(math.atan2(m[1, 0], m[0, 0]))
                self.points = nxt[good].reshape(-1, 1, 2)
            else:
                self.points = None
            if measurement is not None:
                self.state += self.correction * (measurement - self.state)
            elif self.points is None:
                self.reset()
                return None

        if self.box is not None and (self.points is None or len(self.points) < 40):
            self.points = self._seed(gray, self.box)
        self.prev_gray = gray
        return self.state.copy()


def open_color(device, width, height, fps):
    cap = cv2.VideoCapture(device, cv2.CAP_V4L2)
    cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"YUYV"))
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    cap.set(cv2.CAP_PROP_FPS, fps)
    return cap


def find_r200_node(interface):
    """Device path of an R200 stream by USB interface (1.2 = depth, 1.4 = colour), stable across replugging."""
    sysfs = Path("/sys/class/video4linux")
    for node in sorted(sysfs.glob("video*"), key=lambda p: int(p.name[5:])):
        try:
            if "R200" not in (node / "name").read_text() or (node / "index").read_text().strip() != "0":
                continue
            if (node / "device").resolve().name.endswith(":" + interface):
                return f"/dev/{node.name}"
        except OSError:
            continue
    return None


def focal_length(pixels, fov_degrees):
    return (pixels / 2.0) / math.tan(math.radians(fov_degrees) / 2.0)


def face_depth_cm(depth, face_box, color_size, color_fov, depth_fov):
    """Median depth (cm) inside the face box, mapping colour pixels to depth pixels through viewing angles."""
    if depth is None:
        return None
    cw, ch = color_size
    dh, dw = depth.shape
    fxc, fyc = focal_length(cw, color_fov[0]), focal_length(ch, color_fov[1])
    fxd, fyd = focal_length(dw, depth_fov[0]), focal_length(dh, depth_fov[1])
    x, y, w, h = face_box
    # Inner part of the face only, to avoid background around the head.
    corners = [(x + 0.25 * w, y + 0.2 * h), (x + 0.75 * w, y + 0.8 * h)]
    mapped = [(dw / 2 + fxd * (u - cw / 2) / fxc, dh / 2 + fyd * (v - ch / 2) / fyc) for u, v in corners]
    x0, y0 = (int(max(0, min(dw - 1, c))) for c in mapped[0])
    x1, y1 = (int(max(0, min(dw, c))) for c in mapped[1])
    if x1 - x0 < 4 or y1 - y0 < 4:
        return None
    patch = depth[y0:y1, x0:x1]
    valid = patch[(patch > 200) & (patch < 3000)]  # millimetres
    if valid.size < 25:
        return None
    return float(np.median(valid)) / 10.0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--camera", default="auto",
                        help="colour camera device, e.g. /dev/video0; 'auto' finds a RealSense R200, else /dev/video0")
    parser.add_argument("--depth", default="auto", help="R200 depth device, 'auto', or 'none' for a plain webcam")
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--fps", type=int, default=60, help="requested camera frame rate (R200 colour: 60 at 640x480)")
    parser.add_argument("--vfov", type=float, default=43.0,
                        help="colour camera vertical field of view in degrees (R200: 43; most webcams: 40-45)")
    parser.add_argument("--depth-fov", type=float, nargs=2, default=(59.0, 46.0), metavar=("H", "V"))
    parser.add_argument("--screen-height-cm", type=float, default=30.0,
                        help="visible screen height; the camera is assumed to sit on the top edge, centred")
    parser.add_argument("--camera-offset-cm", type=float, nargs=3, default=None, metavar=("X", "Y", "Z"),
                        help="camera lens position relative to the screen centre (overrides --screen-height-cm)")
    parser.add_argument("--ipd-cm", type=float, default=6.3, help="your interpupillary distance (auto-calibrated with depth)")
    parser.add_argument("--smoothing", type=float, default=1.0,
                        help="extra smoothing strength; higher is steadier but slower to follow (0.3 to 3)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=4242)
    parser.add_argument("--preview", action="store_true", help="show the camera image with tracking overlay")
    parser.add_argument("--verbose", action="store_true", help="print the head position a few times per second")
    args = parser.parse_args()

    camera_offset = args.camera_offset_cm or (0.0, args.screen_height_cm / 2.0 + 1.5, 0.0)
    if args.camera == "auto":
        args.camera = find_r200_node("1.4") or "/dev/video0"
    if args.depth == "auto":
        args.depth = find_r200_node("1.2") or "none"

    grabber = FrameGrabber(lambda: open_color(args.camera, args.width, args.height, args.fps))
    if not grabber.cap.isOpened():
        raise SystemExit(f"Could not open camera {args.camera}. Is another program (or another tracker) using it?")
    width = int(grabber.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(grabber.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    # The vertical field of view stays the same across modes; 4:3 modes are narrower than 16:9 ones.
    vfov = args.vfov
    hfov = math.degrees(2 * math.atan(math.tan(math.radians(vfov) / 2) * width / height))
    fx, fy = focal_length(width, hfov), focal_length(height, vfov)
    grabber.start()

    depth_reader = None
    if args.depth.lower() != "none":
        depth_reader = DepthReader(args.depth, 628, 469)
        if depth_reader.ok:
            depth_reader.start()
        else:
            print(f"Depth device {args.depth} not available; using face size only")
            depth_reader = None

    tracker = FaceTracker(MODEL, (width, height))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # The fused tracker is already steady; these only take off what is left. Depth (z) is the noisiest axis.
    k = 1.0 / max(args.smoothing, 0.05)
    filters = {
        "x": OneEuroFilter(min_cutoff=2.0 * k, beta=0.3),
        "y": OneEuroFilter(min_cutoff=2.0 * k, beta=0.3),
        "z": OneEuroFilter(min_cutoff=0.8 * k, beta=0.2),
        "roll": OneEuroFilter(min_cutoff=1.0 * k, beta=0.05),
    }
    ipd_cm = args.ipd_cm
    last_print = 0.0
    last_status = 0.0
    last_count = 0
    status = "starting"
    frames = 0
    fps_t0 = time.time()
    tracking_fps = 0.0

    def on_terminate(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, on_terminate)

    print(f"Tracking with {args.camera} at {width}x{height} up to {args.fps} fps, sending to {args.host}:{args.port}."
          " Ctrl+C to stop.", flush=True)
    try:
        while True:
            frame, stamp, count = grabber.next(last_count)
            now = time.time()
            if now - last_status > 2.0 and not args.verbose:
                last_status = now
                print(status_line(grabber, tracking_fps, status), flush=True)
            if frame is None or count == last_count:
                if grabber.stalled_for() > 1.0:
                    tracking_fps = 0.0
                    status = "no camera frames"
                continue
            last_count = count
            frames += 1
            if stamp - fps_t0 >= 1.0:
                tracking_fps = frames / (stamp - fps_t0)
                frames, fps_t0 = 0, stamp

            state = tracker.update(frame)
            status = "no face"
            if state is not None and tracker.ipd_ratio:
                u, v, face_width, roll = state
                ipd_px = face_width * tracker.ipd_ratio
                if depth_reader is not None and tracker.box is not None:
                    z_depth = face_depth_cm(depth_reader.frame, tracker.box, (width, height), (hfov, vfov),
                                            args.depth_fov)
                    if z_depth is not None:
                        # Slowly learn the eye spacing that makes the face-size distance match the depth camera.
                        measured_ipd = z_depth * ipd_px / fx
                        if 4.5 < measured_ipd < 8.0:
                            ipd_cm += 0.01 * (measured_ipd - ipd_cm)
                z_cam = fx * ipd_cm / ipd_px
                cam_x = (u - width / 2.0) / fx * z_cam
                cam_y = (v - height / 2.0) / fy * z_cam
                # Camera faces you: image right is your left, image down is down.
                x = filters["x"](-cam_x + camera_offset[0], stamp)
                y = filters["y"](-cam_y + camera_offset[1], stamp)
                z = filters["z"](z_cam + camera_offset[2], stamp)
                r = filters["roll"](roll, stamp)
                sock.sendto(struct.pack("<6d", x, y, z, 0.0, 0.0, r), (args.host, args.port))
                status = f"x {x:+6.1f}  y {y:+6.1f}  z {z:5.1f} cm  roll {r:+5.1f}  ipd {ipd_cm:.2f}"
            else:
                for f in filters.values():
                    f.reset()

            if args.verbose and stamp - last_print > 0.25:
                print(f"{tracking_fps:4.1f} fps  {status}", flush=True)
                last_print = stamp
            if args.preview:
                view = frame.copy()
                if state is not None and tracker.box is not None:
                    x0, y0, w, h = (int(c) for c in tracker.box)
                    cv2.rectangle(view, (x0, y0), (x0 + w, y0 + h), (0, 255, 0), 1)
                    if tracker.points is not None:
                        for p in tracker.points.reshape(-1, 2):
                            cv2.circle(view, (int(p[0]), int(p[1])), 1, (255, 200, 0), -1)
                    cv2.circle(view, (int(state[0]), int(state[1])), 4, (0, 0, 255), -1)
                cv2.putText(view, f"{tracking_fps:.0f} fps  {status}", (10, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                            (0, 255, 255), 1)
                cv2.imshow("CrossEye 3D tracker (q to quit)", view)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break
    except KeyboardInterrupt:
        pass
    finally:
        # Stop streaming cleanly; leaving the camera mid-stream can make the R200 stall until it is replugged.
        grabber.stop()
        if depth_reader is not None:
            depth_reader.stop()
        if args.preview:
            cv2.destroyAllWindows()
        print("Tracker stopped.", flush=True)


def status_line(grabber, fps, status):
    if status == "no camera frames" or grabber.stalled_for() > 1.0:
        hint = " If this continues, unplug the camera and plug it back in." if grabber.reopens >= 2 else ""
        return f"Camera is not sending frames (reopened {grabber.reopens}x).{hint}"
    if status == "no face":
        return f"{fps:4.1f} fps  no face found - sit in front of the camera with your face lit"
    if status.startswith("x "):
        return f"{fps:4.1f} fps  tracking  {status}"
    return f"{fps:4.1f} fps  {status}"


if __name__ == "__main__":
    main()
