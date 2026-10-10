# CrossEye 3D

A client-side Fabric mod that renders Minecraft in **side-by-side stereoscopic 3D** you can view **without glasses**, using the cross-eye (or parallel / wall-eye) free-viewing method. It works with **Iris shader packs** such as Complementary and BSL.

The world is rendered once per eye and the two images are placed side by side. For cross-eye viewing the right-eye image goes on the left half and the left-eye image on the right half.

## Requirements

- Minecraft **26.2**
- Fabric Loader **0.19+**
- Fabric API
- Java 25
- Optional: Sodium 0.9 and Iris 1.11. These are the versions it was tested with, and shader packs work.

## Installation

Download the jar from [Releases](https://github.com/TAconsel/crosseye3d/releases) and put it in your `.minecraft/mods` folder. 3D is on by default.

## Controls

All keys can be rebound under Controls → Key Binds → **CrossEye 3D**.

| Key | Action |
|---|---|
| F7 | Toggle 3D on or off |
| F6 | Switch between cross-eye and parallel viewing |
| F10 | Cycle layout: **Full** (each eye fills half the screen) or **Letterbox** (smaller, normal aspect, easier to fuse) |
| `[` / `]` | Decrease / increase depth (eye separation) |
| `-` / `=` | Pull the screen plane closer / push it farther |

Settings are saved in `config/crosseye3d.properties`.

## How to view (cross-eye)

1. Sit at a normal distance from the screen. Letterbox layout (F10) is easier at first.
2. Cross your eyes slowly, for example by looking at a fingertip held between you and the screen, until the two images merge into a third one in the middle.
3. Hold that and let the middle image come into focus. If the depth looks inside-out, press F6.
4. If it's hard to fuse or tiring, lower the depth with `[` or pull the screen plane closer with `-`.

## Head tracking (head-coupled perspective)

With a camera pointed at you, the screen acts like a window into the world. When you move your head, the view shifts to match, so nearby things move against distant ones. This gives depth from motion parallax even with 3D turned off, and it combines with cross-eye 3D.

The `tracker/` folder has a small tracker program. It sends your head position to the game over UDP (`127.0.0.1:4242`), using the same packet layout as opentrack's UDP output. It runs at the camera's full frame rate (60 fps on the R200's color camera at 640×480). It combines OpenCV's YuNet face detector with optical flow: the flow follows many points on your face with sub-pixel precision, and the detector slowly corrects it so it can't drift. That makes it far steadier than the detector alone. It works with any webcam. With an **Intel RealSense R200**, the depth stream helps calibrate the distance estimate.

```sh
cd tracker
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
# Download the YuNet face model (from OpenCV's model zoo) into tracker/models/
mkdir -p models && curl -L -o models/face_detection_yunet_2023mar.onnx \
  https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
.venv/bin/python crosseye_tracker.py --preview          # RealSense R200 (found automatically)
.venv/bin/python crosseye_tracker.py --camera /dev/video0 --depth none --fps 30   # plain webcam
```

In game, press **F8** to turn head tracking on. Sit how you normally do and press **F9** to recenter. The view then follows your head from that position.

| Key | Action |
|---|---|
| F8 | Toggle head tracking |
| F9 | Recenter (use your current head position as neutral) |
| `-` / `=` | Screen plane distance: objects at this depth stay fixed on the screen while you move |

Settings in `config/crosseye3d.properties`:
- `trackingStrength` (default 1): how strongly the view follows your head. At 1, your head's angle to the screen center matches the real angle.
- `trackerPort`: the UDP port the mod listens on.
- `viewingDistanceCm`: only used with trackers that send positions relative to a center pose.

Tracker options:
- `--screen-height-cm`: the tracker assumes the camera sits centered on top of the screen, so give the screen height.
- `--camera-offset-cm X Y Z`: use instead if the camera is mounted somewhere else.
- `--ipd-cm`: your eye spacing (pupil to pupil), for webcams without depth.
- The tracker prints a status line every 2 seconds: frame rate, "no face found", or "camera is not sending frames". The R200 sometimes stalls; the tracker reopens it automatically, and if that doesn't help, unplug the camera and plug it back in.
- `--smoothing`: default 1. Higher is steadier but follows more slowly; lower is snappier.
- `--width`, `--height`, `--fps`, `--vfov`: camera mode and vertical field of view. The defaults suit the R200; most webcams have a vertical field of view of 40–45°.

## How it works

- **Screen as a window.** The screen is modelled as a window at the screen-plane distance. Each eye, or your tracked head, gets an off-axis frustum through that window. The eye's offset goes into the view matrix rather than the projection, because shader packs often rebuild view positions from only part of `gbufferProjectionInverse`. Culling and entity extraction still use the normal camera; the culling frustum is widened while head tracking is on.
- **Per-frame caches are reset between eyes.** Sodium uploads its terrain uniforms once per frame, and Iris refreshes its per-frame uniforms only when its frame counter changes. Both are reset between the two eyes, by reflection, so the mod still works without them. Minecraft's per-frame entity and block-entity render lists are also kept until the second eye has been drawn.
- **Composite pass.** Each eye image is copied out, and a small custom pipeline draws both into their screen regions.
- **Crosshairs.** A crosshair is drawn for each eye at the depth of whatever you're looking at.

## Known limitations

- **FPS roughly halves**, because the world (including the shader shadow pass) is rendered twice per frame.
- **HUD is flat.** The hotbar, chat and menus are drawn once across the screen, not in stereo.
- **Temporal effects** (TAA, motion blur) can show small artifacts where one eye sees something the other can't. Turn them off in the shader settings if they bother you.
- **Post effects** (like the view when spectating a creeper) are applied over the combined side-by-side image.
- **Graphics backend.** Only tested with the OpenGL backend.

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`. Building needs JDK 25.

## License

MIT, see [LICENSE](LICENSE).
