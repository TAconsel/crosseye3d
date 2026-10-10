package com.consel.crosseye3d;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CrossEye3DClient implements ClientModInitializer {
	public static final String MOD_ID = "crosseye3d";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static StereoConfig config = new StereoConfig();

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	private static KeyMapping toggleKey;
	private static KeyMapping swapKey;
	private static KeyMapping layoutKey;
	private static KeyMapping separationUpKey;
	private static KeyMapping separationDownKey;
	private static KeyMapping convergenceUpKey;
	private static KeyMapping convergenceDownKey;
	private static KeyMapping headTrackingKey;
	private static KeyMapping recenterKey;

	public static StereoConfig config() {
		return config;
	}

	@Override
	public void onInitializeClient() {
		config = StereoConfig.load();
		LOGGER.debug("Registered pipeline {}", StereoRenderer.SIDE_BY_SIDE.getLocation());

		toggleKey = register("toggle", GLFW.GLFW_KEY_F7);
		swapKey = register("swap", GLFW.GLFW_KEY_F6);
		layoutKey = register("layout", GLFW.GLFW_KEY_F10);
		separationUpKey = register("separation_up", GLFW.GLFW_KEY_RIGHT_BRACKET);
		separationDownKey = register("separation_down", GLFW.GLFW_KEY_LEFT_BRACKET);
		convergenceUpKey = register("convergence_up", GLFW.GLFW_KEY_EQUAL);
		convergenceDownKey = register("convergence_down", GLFW.GLFW_KEY_MINUS);
		headTrackingKey = register("head_tracking", GLFW.GLFW_KEY_F8);
		recenterKey = register("recenter", GLFW.GLFW_KEY_F9);

		if (config.headTracking) {
			HeadTracker.start(config.trackerPort);
		}

		ClientTickEvents.END_CLIENT_TICK.register(CrossEye3DClient::handleKeys);

		// Draw the crosshair where the aimed-at point lands in each eye's image (or in the head-tracked view), so it
		// sits on the target in 3D and stays on it while the head moves.
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (graphics, deltaTracker) -> {
			// The HUD is extracted before the frame renders, so check the settings rather than the current frame.
			boolean stereo = config.enabled && Minecraft.getInstance().level != null;
			if (!stereo && !HeadTracker.isActive()) {
				vanilla.extractRenderState(graphics, deltaTracker);
				return;
			}

			for (StereoRenderer.Eye eye : stereo ? StereoRenderer.Eye.values() : new StereoRenderer.Eye[] {null}) {
				Vector2f offset = StereoRenderer.crosshairOffset(eye, graphics.guiWidth(), graphics.guiHeight());
				graphics.pose().pushMatrix();
				graphics.pose().translate(offset.x, offset.y);
				vanilla.extractRenderState(graphics, deltaTracker);
				graphics.pose().popMatrix();
			}
		});
	}

	private static KeyMapping register(final String name, final int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + "." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
	}

	private static void handleKeys(final Minecraft minecraft) {
		boolean changed = false;
		while (toggleKey.consumeClick()) {
			config.enabled = !config.enabled;
			changed = true;
		}
		while (swapKey.consumeClick()) {
			config.crossEye = !config.crossEye;
			changed = true;
		}
		while (layoutKey.consumeClick()) {
			config.layout = config.layout == StereoConfig.Layout.FULL ? StereoConfig.Layout.LETTERBOX : StereoConfig.Layout.FULL;
			changed = true;
		}
		while (separationUpKey.consumeClick()) {
			config.eyeSeparation *= 1.15F;
			changed = true;
		}
		while (separationDownKey.consumeClick()) {
			config.eyeSeparation /= 1.15F;
			changed = true;
		}
		while (convergenceUpKey.consumeClick()) {
			config.convergence *= 1.15F;
			changed = true;
		}
		while (convergenceDownKey.consumeClick()) {
			config.convergence /= 1.15F;
			changed = true;
		}
		while (headTrackingKey.consumeClick()) {
			config.headTracking = !config.headTracking;
			if (config.headTracking) {
				if (!HeadTracker.start(config.trackerPort)) {
					config.headTracking = false;
					minecraft.gui.hud.setOverlayMessage(
						Component.literal("Head tracking: port " + config.trackerPort + " is in use"), false);
					continue;
				}
			} else {
				HeadTracker.stop();
			}
			changed = true;
		}
		while (recenterKey.consumeClick()) {
			HeadTracker.recenter();
			minecraft.gui.hud.setOverlayMessage(Component.literal("Head tracking recentred"), false);
		}

		if (changed) {
			config.clamp();
			config.save();
			showStatus(minecraft);
		}
	}

	private static void showStatus(final Minecraft minecraft) {
		String text = config.enabled
			? String.format(
				Locale.ROOT,
				"3D %s | %s | depth %.2f | screen plane %.1f blocks",
				config.crossEye ? "cross-eye" : "parallel",
				config.layout == StereoConfig.Layout.FULL ? "full" : "letterbox",
				config.eyeSeparation,
				config.convergence
			)
			: String.format(Locale.ROOT, "3D off | screen plane %.1f blocks", config.convergence);
		if (config.headTracking) {
			text += HeadTracker.isReceiving() ? " | head tracking on" : " | head tracking on (waiting for tracker)";
		}
		minecraft.gui.hud.setOverlayMessage(Component.literal(text), false);
	}
}
