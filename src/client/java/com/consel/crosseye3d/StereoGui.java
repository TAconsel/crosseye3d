package com.consel.crosseye3d;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Draws the HUD and menus once per eye, so each eye sees the whole GUI (needed for AR glasses and other SBS displays,
 * where each eye only ever sees its own half of the screen).
 * <p>
 * While 3D is on in a world, the GUI is laid out for one eye's image instead of the whole window: the window reports
 * an eye-sized framebuffer to the GUI scale calculation, so the GUI scale, the GUI-scaled size and every screen layout
 * follow from that. The finished draw list is then replayed into each eye's region with its own projection and
 * scissor. The mouse needs no remapping, since Minecraft scales window coordinates by GUI size over window size: moving
 * across the whole window sweeps the whole eye-sized GUI. The system cursor no longer matches what either eye sees, so
 * it is hidden and a cursor is drawn into the GUI instead.
 */
public final class StereoGui {
	/** Arrow cursor, one character per GUI pixel: '#' outline, '.' fill. */
	private static final String[] CURSOR = {
		"#",
		"##",
		"#.#",
		"#..#",
		"#...#",
		"#....#",
		"#.....#",
		"#......#",
		"#.......#",
		"#....#####",
		"#..#..#",
		"#.# #..#",
		"##  #..#",
		"     ##",
	};

	private static boolean active;
	private static StereoConfig.@Nullable Layout activeLayout;

	private static final Projection eyeProjection = new Projection();
	private static final ProjectionMatrixBuffer[] eyeProjectionBuffers = new ProjectionMatrixBuffer[2];

	private static StereoRenderer.@Nullable Eye passEye;
	private static @Nullable GpuBufferSlice passTransforms;
	private static @Nullable GpuBufferSlice passCrosshairTransforms;
	private static boolean crosshairTransformsBound;

	private StereoGui() {
	}

	/** Whether the GUI is laid out and drawn per eye. Fixed for the whole frame. */
	public static boolean isActive() {
		return active;
	}

	/**
	 * Called at the start of each frame's GUI extraction. Re-lays out the GUI when 3D is turned on or off, a world is
	 * entered or left, or the layout changes, and shows or hides the system cursor to match.
	 */
	public static void beginFrame(final Minecraft minecraft) {
		StereoConfig config = CrossEye3DClient.config();
		boolean wanted = config.enabled && minecraft.level != null;
		StereoConfig.Layout layout = wanted ? config.layout : null;
		if (wanted != active || layout != activeLayout) {
			active = wanted;
			activeLayout = layout;
			minecraft.resizeGui();
		}

		updateSystemCursor(minecraft);
	}

	private static void updateSystemCursor(final Minecraft minecraft) {
		if (minecraft.mouseHandler.isMouseGrabbed()) {
			return;
		}

		long handle = minecraft.getWindow().handle();
		int mode = GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR);
		int wanted = active ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL;
		// Only switch between normal and hidden; leave a disabled (grabbed) cursor alone.
		if (mode != wanted && (mode == GLFW.GLFW_CURSOR_NORMAL || mode == GLFW.GLFW_CURSOR_HIDDEN)) {
			GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, wanted);
		}
	}

	/** Size in pixels of the region one eye's image covers; the GUI is laid out for this instead of the window. */
	public static int eyeWidth(final int windowWidth) {
		return Math.max(windowWidth / 2, 1);
	}

	public static int eyeHeight(final int windowHeight) {
		return activeLayout == StereoConfig.Layout.LETTERBOX ? Math.max(windowHeight / 2, 1) : windowHeight;
	}

	/** Draws the mouse cursor into the GUI, so it shows up in both eyes. */
	public static void extractCursor(final Minecraft minecraft, final GuiGraphicsExtractor graphics) {
		if (!active || minecraft.mouseHandler.isMouseGrabbed() || minecraft.gui.screen() == null || minecraft.gui.overlay() != null) {
			return;
		}

		Window window = minecraft.getWindow();
		graphics.nextStratum();
		graphics.pose().pushMatrix();
		graphics.pose().translate((float)minecraft.mouseHandler.getScaledXPos(window), (float)minecraft.mouseHandler.getScaledYPos(window));
		// Half a GUI pixel per cursor pixel keeps the cursor about the size of the system one.
		graphics.pose().scale(0.5F, 0.5F);
		for (int y = 0; y < CURSOR.length; y++) {
			String row = CURSOR[y];
			for (int x = 0; x < row.length(); x++) {
				char c = row.charAt(x);
				if (c != ' ') {
					graphics.fill(x, y, x + 1, y + 1, c == '#' ? 0xFF000000 : 0xFFFFFFFF);
				}
			}
		}
		graphics.pose().popMatrix();
	}

	/**
	 * Prepares to replay the GUI draw list into {@code eye}'s region: sets that region's projection and works out where
	 * the crosshair goes in that eye.
	 */
	public static void beginEyePass(final StereoRenderer.Eye eye, final GpuBufferSlice transforms) {
		WindowRenderState window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
		int eyeWidth = eyeWidth(window.width);
		int eyeHeight = eyeHeight(window.height);
		float guiWidth = (float)eyeWidth / window.guiScale;
		float guiHeight = (float)eyeHeight / window.guiScale;

		// Same orthographic projection as vanilla, for the eye-sized GUI, then squeezed into the eye's region in NDC.
		eyeProjection.setupOrtho(1000.0F, 11000.0F, guiWidth, guiHeight, true);
		boolean onLeftHalf = StereoRenderer.isOnLeftHalf(eye);
		Matrix4f matrix = new Matrix4f()
			.translation(onLeftHalf ? -0.5F : 0.5F, 0.0F, 0.0F)
			.scale(0.5F, (float)eyeHeight / window.height, 1.0F)
			.mul(eyeProjection.getMatrix(new Matrix4f()));
		int index = eye.ordinal();
		if (eyeProjectionBuffers[index] == null) {
			eyeProjectionBuffers[index] = new ProjectionMatrixBuffer("CrossEye3D gui " + eye);
		}
		RenderSystem.setProjectionMatrix(eyeProjectionBuffers[index].getBuffer(matrix), ProjectionType.ORTHOGRAPHIC);

		// The crosshair sits where the aimed-at point lands in this eye's image, so it appears at the target's depth.
		Vector2f offset = StereoRenderer.crosshairOffset(eye, guiWidth, guiHeight);
		passCrosshairTransforms = RenderSystem.getDynamicUniforms()
			.writeTransform(new Matrix4f().setTranslation(offset.x, offset.y, -11000.0F));
		passTransforms = transforms;
		passEye = eye;
		crosshairTransformsBound = false;
	}

	public static void endEyePass() {
		passEye = null;
		passTransforms = null;
		passCrosshairTransforms = null;
	}

	/** Called after each GUI draw sets its pipeline: moves the crosshair (and attack indicator) for the current eye. */
	public static void onSetPipeline(final RenderPass pass, final RenderPipeline pipeline) {
		if (passEye == null) {
			return;
		}

		boolean crosshair = pipeline == RenderPipelines.CROSSHAIR;
		if (crosshair != crosshairTransformsBound) {
			pass.setUniform("DynamicTransforms", crosshair ? passCrosshairTransforms : passTransforms);
			crosshairTransformsBound = crosshair;
		}
	}

	/**
	 * Scissor for the current eye pass, in window pixels (origin bottom left).
	 *
	 * @return false if no eye pass is running and vanilla should handle it
	 */
	public static boolean enableScissor(final ScreenRectangle rectangle, final RenderPass pass) {
		StereoRenderer.Eye eye = passEye;
		if (eye == null) {
			return false;
		}

		WindowRenderState window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
		int eyeWidth = eyeWidth(window.width);
		int eyeHeight = eyeHeight(window.height);
		int regionX = StereoRenderer.isOnLeftHalf(eye) ? 0 : window.width - eyeWidth;
		int regionY = (window.height - eyeHeight) / 2;

		int guiScale = window.guiScale;
		int left = Math.min(rectangle.left() * guiScale, eyeWidth);
		int top = Math.min(rectangle.top() * guiScale, eyeHeight);
		int right = Math.min(rectangle.right() * guiScale, eyeWidth);
		int bottom = Math.min(rectangle.bottom() * guiScale, eyeHeight);
		pass.enableScissor(regionX + left, regionY + eyeHeight - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
		return true;
	}
}
