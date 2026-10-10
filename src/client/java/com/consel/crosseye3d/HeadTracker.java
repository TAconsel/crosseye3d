package com.consel.crosseye3d;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Receives head positions over UDP and turns them into a smoothed per-frame offset.
 * <p>
 * Packets use opentrack's "UDP over network" layout: six little-endian doubles (x, y, z, yaw, pitch, roll), with
 * positions in centimetres relative to the screen centre (x to the viewer's right, y up, z towards the viewer) and
 * angles in degrees. Only the position and roll are used.
 */
public final class HeadTracker {
	/** Packets older than this are treated as "no face" and the view eases back to neutral. */
	private static final long STALE_NANOS = 500_000_000L;
	/** Time constant for following the tracker, which hides the steps between camera frames at high frame rates. */
	private static final float FOLLOW_SECONDS = 0.02F;
	/** Time constant for fading tracking in and out when the face is found or lost. */
	private static final float FADE_SECONDS = 0.25F;
	/** Below this the tracker is assumed to send positions relative to a centre pose, not absolute distances. */
	private static final float MIN_ABSOLUTE_DISTANCE_CM = 20.0F;

	private static @Nullable DatagramSocket socket;
	private static volatile double @Nullable [] latest;
	private static volatile long latestNanos;

	private static final Vector3f position = new Vector3f();
	private static float roll;
	private static float weight;
	private static @Nullable Vector3f neutral;
	private static boolean recenterRequested;
	private static long lastFrameNanos;

	private HeadTracker() {
	}

	public static synchronized boolean start(final int port) {
		if (socket != null) {
			return true;
		}

		try {
			DatagramSocket newSocket = new DatagramSocket(port, InetAddress.getLoopbackAddress());
			socket = newSocket;
			Thread thread = new Thread(() -> receive(newSocket), "CrossEye3D head tracker");
			thread.setDaemon(true);
			thread.start();
			CrossEye3DClient.LOGGER.info("Listening for head tracking on 127.0.0.1:{}", port);
			return true;
		} catch (SocketException e) {
			CrossEye3DClient.LOGGER.warn("Could not listen for head tracking on port {}", port, e);
			return false;
		}
	}

	public static synchronized void stop() {
		if (socket != null) {
			socket.close();
			socket = null;
		}
		latest = null;
	}

	private static void receive(final DatagramSocket socket) {
		byte[] buffer = new byte[64];
		DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
		while (!socket.isClosed()) {
			try {
				socket.receive(packet);
			} catch (IOException e) {
				return;
			}
			if (packet.getLength() < 48) {
				continue;
			}

			double[] pose = new double[6];
			for (int i = 0; i < 6; i++) {
				pose[i] = data.getDouble(i * 8);
			}
			if (Double.isFinite(pose[0]) && Double.isFinite(pose[1]) && Double.isFinite(pose[2]) && Double.isFinite(pose[5])) {
				latest = pose;
				latestNanos = System.nanoTime();
			}
		}
	}

	/** True when a tracker has sent a pose recently. */
	public static boolean isReceiving() {
		return latest != null && System.nanoTime() - latestNanos < STALE_NANOS;
	}

	/** Use the current head position as the neutral (centred) position. */
	public static void recenter() {
		recenterRequested = true;
	}

	/** Advances smoothing; call once per rendered frame so both eyes see the same head pose. */
	public static void beginFrame() {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0.0F : Math.min((now - lastFrameNanos) / 1.0e9F, 0.25F);
		lastFrameNanos = now;

		double[] pose = latest;
		boolean fresh = CrossEye3DClient.config().headTracking && pose != null && now - latestNanos < STALE_NANOS;
		if (fresh) {
			Vector3f target = new Vector3f((float)pose[0], (float)pose[1], (float)pose[2]);
			if (neutral == null || recenterRequested) {
				neutral = new Vector3f(target);
				position.set(target);
				roll = (float)pose[5];
				recenterRequested = false;
			}
			float follow = dt <= 0.0F ? 1.0F : 1.0F - (float)Math.exp(-dt / FOLLOW_SECONDS);
			position.lerp(target, follow);
			roll += ((float)pose[5] - roll) * follow;
		}

		float fade = dt <= 0.0F ? 0.0F : 1.0F - (float)Math.exp(-dt / FADE_SECONDS);
		weight += ((fresh ? 1.0F : 0.0F) - weight) * fade;
		if (!fresh && weight < 0.001F) {
			weight = 0.0F;
		}
	}

	/** Whether head tracking should change the view this frame. */
	public static boolean isActive() {
		return CrossEye3DClient.config().headTracking && weight > 0.0F && neutral != null;
	}

	/** Head offset from the neutral position, in centimetres, scaled by the tracking strength and fade. */
	public static Vector3f offsetCm(final Vector3f dest) {
		if (neutral == null) {
			return dest.zero();
		}
		return position.sub(neutral, dest).mul(weight * CrossEye3DClient.config().trackingStrength);
	}

	/** Head roll in radians (positive when the right eye is higher), faded with tracking. */
	public static float rollRadians() {
		return (float)Math.toRadians(roll * weight);
	}

	/** Distance from the screen at the neutral position, in centimetres. */
	public static float neutralDistanceCm() {
		Vector3f n = neutral;
		if (n != null && n.z > MIN_ABSOLUTE_DISTANCE_CM) {
			return n.z;
		}
		return CrossEye3DClient.config().viewingDistanceCm;
	}
}
