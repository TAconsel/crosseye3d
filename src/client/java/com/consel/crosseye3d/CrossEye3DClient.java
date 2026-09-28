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

		ClientTickEvents.END_CLIENT_TICK.register(CrossEye3DClient::handleKeys);

		// Draw one crosshair per eye, each at the depth of whatever the player is looking at.
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (graphics, deltaTracker) -> {
			// The HUD is extracted before the frame renders, so check the setting rather than the current frame.
			if (!config.enabled || Minecraft.getInstance().level == null) {
				vanilla.extractRenderState(graphics, deltaTracker);
				return;
			}

			for (StereoRenderer.Eye eye : StereoRenderer.Eye.values()) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(StereoRenderer.crosshairOffset(eye, graphics.guiWidth()), 0.0F);
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
			: "3D off";
		minecraft.gui.hud.setOverlayMessage(Component.literal(text), false);
	}
}
