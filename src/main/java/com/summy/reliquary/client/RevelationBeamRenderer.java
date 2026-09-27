package com.summy.reliquary.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.summy.reliquary.SummyReliquary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 「启示之光」光柱的客户端渲染：沿射线画两片互相垂直的四边形（信标光束贴图、自发光），
 * 颜色沿长度由白渐变为金，按服务端下发的持续时间淡入/淡出（默认 1.5 秒）。
 * 粒子与其它特效由服务端下发，所以这里只管光柱本体。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RevelationBeamRenderer {
	/** 信标光束贴图：自带上下渐变的透明度，最像"光柱" */
	private static final ResourceLocation BEAM_TEXTURE = new ResourceLocation("textures/entity/beacon_beam.png");
	/** 光柱整体不透明度（贴图本身已有渐变） */
	private static final float BASE_ALPHA = 0.75F;
	/** 淡入 tick 数 */
	private static final int FADE_IN_TICKS = 2;
	/** 淡出 tick 数 */
	private static final int FADE_OUT_TICKS = 8;
	/** 末端颜色（金色）：起点端保持纯白 */
	private static final float GOLD_RED = 1.0F;
	private static final float GOLD_GREEN = 0.84F;
	private static final float GOLD_BLUE = 0.0F;
	/** 恶魔之焰（1.6.4）：起点端暗红、末端橙黄 */
	private static final float DEMON_NEAR_RED = 0.45F;
	private static final float DEMON_NEAR_GREEN = 0.03F;
	private static final float DEMON_NEAR_BLUE = 0.02F;
	private static final float DEMON_FAR_RED = 1.0F;
	private static final float DEMON_FAR_GREEN = 0.45F;
	private static final float DEMON_FAR_BLUE = 0.05F;

	private static final List<Beam> BEAMS = new ArrayList<>();

	private RevelationBeamRenderer() {
	}

	/** 收到服务端广播：登记一根按指定时长淡入淡出的光柱 */
	public static void spawn(double x, double y, double z, float dirX, float dirY, float dirZ,
			float length, float radius, int durationTicks, int kind) {
		Vec3 direction = new Vec3(dirX, dirY, dirZ);
		if (direction.lengthSqr() < 1.0E-6D) {
			return;
		}
		BEAMS.add(new Beam(new Vec3(x, y, z), direction.normalize(), length, radius,
				Math.max(1, durationTicks), kind));
	}

	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
			return;
		}
		Iterator<Beam> iterator = BEAMS.iterator();
		while (iterator.hasNext()) {
			Beam beam = iterator.next();
			beam.age++;
			if (beam.age > beam.lifeTicks) {
				iterator.remove();
			}
		}
		if (BEAMS.isEmpty()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		Vec3 camera = event.getCamera().getPosition();
		PoseStack poseStack = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
		VertexConsumer consumer = buffers.getBuffer(RenderType.beaconBeam(BEAM_TEXTURE, true));
		for (Beam beam : BEAMS) {
			renderBeam(poseStack, consumer, beam, camera, alphaFor(beam));
		}
		buffers.endBatch(RenderType.beaconBeam(BEAM_TEXTURE, true));
	}

	/** 淡入 → 保持 → 淡出 */
	private static float alphaFor(Beam beam) {
		if (beam.age <= FADE_IN_TICKS) {
			return BASE_ALPHA * beam.age / (float) FADE_IN_TICKS;
		}
		int fadeOutStart = beam.lifeTicks - FADE_OUT_TICKS;
		if (beam.age > fadeOutStart) {
			return Math.max(0.0F, BASE_ALPHA * (beam.lifeTicks - beam.age) / (float) FADE_OUT_TICKS);
		}
		return BASE_ALPHA;
	}

	private static void renderBeam(PoseStack poseStack, VertexConsumer consumer, Beam beam, Vec3 camera, float alpha) {
		poseStack.pushPose();
		// 世界坐标 → 相机空间（关卡渲染的常规写法）
		poseStack.translate(beam.origin.x - camera.x, beam.origin.y - camera.y, beam.origin.z - camera.z);
		Vector3f from = new Vector3f(1.0F, 0.0F, 0.0F);
		Vector3f to = new Vector3f((float) beam.direction.x, (float) beam.direction.y, (float) beam.direction.z);
		poseStack.mulPose(new Quaternionf().rotationTo(from, to));
		Matrix4f matrix = poseStack.last().pose();

		float radius = beam.radius;
		float length = beam.length;
		// 两端颜色随种类变化：启示之光 = 白 → 金；恶魔之焰 = 暗红 → 橙黄（1.6.4）
		boolean demon = beam.kind == com.summy.reliquary.effect.RevelationBeam.BeamKind.DEMON_FLAME.ordinal();
		float nearRed = demon ? DEMON_NEAR_RED : 1.0F;
		float nearGreen = demon ? DEMON_NEAR_GREEN : 1.0F;
		float nearBlue = demon ? DEMON_NEAR_BLUE : 1.0F;
		float farRed = demon ? DEMON_FAR_RED : GOLD_RED;
		float farGreen = demon ? DEMON_FAR_GREEN : GOLD_GREEN;
		float farBlue = demon ? DEMON_FAR_BLUE : GOLD_BLUE;
		// XY 平面的一片：起点端 → 末端
		vertex(consumer, matrix, 0.0F, -radius, 0.0F, 0.0F, 1.0F, alpha, nearRed, nearGreen, nearBlue);
		vertex(consumer, matrix, length, -radius, 0.0F, 1.0F, 1.0F, alpha, farRed, farGreen, farBlue);
		vertex(consumer, matrix, length, radius, 0.0F, 1.0F, 0.0F, alpha, farRed, farGreen, farBlue);
		vertex(consumer, matrix, 0.0F, radius, 0.0F, 0.0F, 0.0F, alpha, nearRed, nearGreen, nearBlue);
		// XZ 平面的一片（与上一片垂直）
		vertex(consumer, matrix, 0.0F, 0.0F, -radius, 0.0F, 1.0F, alpha, nearRed, nearGreen, nearBlue);
		vertex(consumer, matrix, length, 0.0F, -radius, 1.0F, 1.0F, alpha, farRed, farGreen, farBlue);
		vertex(consumer, matrix, length, 0.0F, radius, 1.0F, 0.0F, alpha, farRed, farGreen, farBlue);
		vertex(consumer, matrix, 0.0F, 0.0F, radius, 0.0F, 0.0F, alpha, nearRed, nearGreen, nearBlue);
		poseStack.popPose();
	}

	/** 顶点顺序必须与 BLOCK 顶点格式一致：位置 → 颜色 → uv → 光照 → 法线 */
	private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z,
			float u, float v, float alpha, float red, float green, float blue) {
		consumer.vertex(matrix, x, y, z)
				.color(red, green, blue, alpha)
				.uv(u, v)
				.uv2(LightTexture.FULL_BRIGHT)
				.normal(0.0F, 1.0F, 0.0F)
				.endVertex();
	}

	/** 一根按服务端时长淡入淡出的光柱 */
	private static final class Beam {
		private final Vec3 origin;
		private final Vec3 direction;
		private final float length;
		private final float radius;
		private final int lifeTicks;
		private final int kind;
		private int age;

		private Beam(Vec3 origin, Vec3 direction, float length, float radius, int lifeTicks, int kind) {
			this.origin = origin;
			this.direction = direction;
			this.length = length;
			this.radius = radius;
			this.lifeTicks = lifeTicks;
			this.kind = kind;
		}
	}
}
