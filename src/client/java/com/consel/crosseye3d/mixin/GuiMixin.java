package com.consel.crosseye3d.mixin;

import com.consel.crosseye3d.StereoGui;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	@Shadow @Final private Minecraft minecraft;

	/** Before anything reads the GUI size or mouse position this frame. */
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void crosseye3d$beginFrame(final DeltaTracker deltaTracker, final boolean shouldRenderLevel, final boolean resourcesLoaded, final CallbackInfo ci) {
		StereoGui.beginFrame(this.minecraft);
	}

	@Inject(
		method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;applyCursor(Lcom/mojang/blaze3d/platform/Window;)V")
	)
	private void crosseye3d$extractCursor(
		final DeltaTracker deltaTracker, final boolean shouldRenderLevel, final boolean resourcesLoaded, final CallbackInfo ci,
		@Local final GuiGraphicsExtractor graphics
	) {
		StereoGui.extractCursor(this.minecraft, graphics);
	}
}
