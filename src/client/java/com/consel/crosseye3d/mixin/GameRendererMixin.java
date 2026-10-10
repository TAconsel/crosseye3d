package com.consel.crosseye3d.mixin;

import com.consel.crosseye3d.CrossEye3DClient;
import com.consel.crosseye3d.HeadTracker;
import com.consel.crosseye3d.StereoRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private RenderTarget mainRenderTarget;
	@Shadow @Final private GameRenderState gameRenderState;

	@Unique
	private final Matrix4f crosseye3d$savedProjection = new Matrix4f();
	@Unique
	private final Matrix4f crosseye3d$savedView = new Matrix4f();
	@Unique
	private boolean crosseye3d$projectionModified;

	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
	)
	private void crosseye3d$renderLevelPerEye(final GameRenderer self, final DeltaTracker deltaTracker, final Operation<Void> original) {
		if (!CrossEye3DClient.config().enabled) {
			original.call(self, deltaTracker);
			return;
		}

		StereoRenderer.renderStereo(this.mainRenderTarget, this.gameRenderState.guiRenderState.clearColorOverride, () -> {
			original.call(self, deltaTracker);
			// Glowing-entity outlines are blitted onto the main target, so they have to happen once per eye.
			this.minecraft.levelRenderer.doEntityOutline();
		});
	}

	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V")
	)
	private void crosseye3d$skipSharedEntityOutline(final LevelRenderer levelRenderer, final Operation<Void> original) {
		if (!StereoRenderer.isStereoFrame()) {
			original.call(levelRenderer);
		}
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void crosseye3d$beginFrame(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
		HeadTracker.beginFrame();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void crosseye3d$endFrame(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
		StereoRenderer.endFrame();
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void crosseye3d$applyEyeProjection(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		Matrix4f projection = this.gameRenderState.levelRenderState.cameraRenderState.projectionMatrix;
		StereoRenderer.rememberProjection(projection);
		this.crosseye3d$projectionModified = StereoRenderer.modifiesLevelProjection();
		if (this.crosseye3d$projectionModified) {
			Matrix4f view = this.gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix;
			this.crosseye3d$savedProjection.set(projection);
			this.crosseye3d$savedView.set(view);
			StereoRenderer.applyLevelView(projection, view);
		}
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void crosseye3d$restoreProjection(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		if (this.crosseye3d$projectionModified) {
			this.gameRenderState.levelRenderState.cameraRenderState.projectionMatrix.set(this.crosseye3d$savedProjection);
			this.gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix.set(this.crosseye3d$savedView);
			this.crosseye3d$projectionModified = false;
		}
	}

	/** The first-person hand uses its own projection; give it the same eye offset. */
	@WrapOperation(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
		)
	)
	private GpuBufferSlice crosseye3d$stereoHandProjection(
		final ProjectionMatrixBuffer buffer, final Projection projection, final Operation<GpuBufferSlice> original
	) {
		if (!StereoRenderer.isRenderingEye()) {
			return original.call(buffer, projection);
		}

		Matrix4f matrix = projection.getMatrix(new Matrix4f());
		StereoRenderer.applyHandEye(matrix);
		return buffer.getBuffer(matrix);
	}
}
