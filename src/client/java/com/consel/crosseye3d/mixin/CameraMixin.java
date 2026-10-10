package com.consel.crosseye3d.mixin;

import com.consel.crosseye3d.HeadTracker;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class CameraMixin {
	/**
	 * Head tracking moves the eyes away from the camera and skews the frustum, so things just outside the normal view
	 * can become visible. Widen the culling frustum so they are not culled.
	 */
	@ModifyReturnValue(method = "createProjectionMatrixForCulling", at = @At("RETURN"))
	private Matrix4f crosseye3d$widenCullingForHeadTracking(final Matrix4f projection) {
		if (HeadTracker.isActive()) {
			projection.m00(projection.m00() * 0.5F);
			projection.m11(projection.m11() * 0.5F);
			projection.determineProperties();
		}
		return projection;
	}
}
