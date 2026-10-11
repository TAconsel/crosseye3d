package com.consel.crosseye3d.mixin;

import com.consel.crosseye3d.StereoGui;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.Window;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lays the GUI out for one eye's image while the GUI is drawn per eye (see StereoGui). */
@Mixin(Window.class)
public abstract class WindowMixin {
	@ModifyExpressionValue(
		method = {"calculateScale", "setGuiScale"},
		at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferWidth:I", opcode = Opcodes.GETFIELD)
	)
	private int crosseye3d$eyeWidth(final int width) {
		return StereoGui.isActive() ? StereoGui.eyeWidth(width) : width;
	}

	@ModifyExpressionValue(
		method = {"calculateScale", "setGuiScale"},
		at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferHeight:I", opcode = Opcodes.GETFIELD)
	)
	private int crosseye3d$eyeHeight(final int height) {
		return StereoGui.isActive() ? StereoGui.eyeHeight(height) : height;
	}
}
