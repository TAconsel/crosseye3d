package com.consel.crosseye3d.mixin;

import com.consel.crosseye3d.StereoGui;
import com.consel.crosseye3d.StereoRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.function.Supplier;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replays the GUI draw list once into each eye's region (see StereoGui). */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@WrapOperation(
		method = "draw",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;II)V"
		)
	)
	private void crosseye3d$drawRangePerEye(
		final GuiRenderer self, final Supplier<String> label, final RenderTarget target, final GpuBufferSlice transforms,
		final int startIndex, final int endIndex, final Operation<Void> original
	) {
		if (!StereoGui.isActive()) {
			original.call(self, label, target, transforms, startIndex, endIndex);
			return;
		}

		for (StereoRenderer.Eye eye : StereoRenderer.Eye.values()) {
			StereoGui.beginEyePass(eye, transforms);
			try {
				original.call(self, label, target, transforms, startIndex, endIndex);
			} finally {
				StereoGui.endEyePass();
			}
		}
	}

	@WrapOperation(
		method = "executeDraw",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void crosseye3d$moveCrosshair(final RenderPass pass, final RenderPipeline pipeline, final Operation<Void> original) {
		original.call(pass, pipeline);
		StereoGui.onSetPipeline(pass, pipeline);
	}

	@Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
	private void crosseye3d$eyeScissor(final ScreenRectangle rectangle, final RenderPass pass, final CallbackInfo ci) {
		if (StereoGui.enableScissor(rectangle, pass)) {
			ci.cancel();
		}
	}
}
