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

## How it works

- **Eye offset in the projection matrix.** Each eye's offset (a sideways shift plus an off-axis shear for convergence) goes into the projection matrix. The camera itself doesn't move, so culling, entity extraction and the camera position stay the same. Shader packs that rebuild world positions from `gbufferProjectionInverse` get correct lighting and shadows for each eye.
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
