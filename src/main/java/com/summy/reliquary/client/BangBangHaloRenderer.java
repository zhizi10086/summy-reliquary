package com.summy.reliquary.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.summy.reliquary.SummyReliquary;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * 光环渲染器（Curios 版）。
 *
 * <p>把贴图画成一块平面，固定在玩家头部正后方偏上的位置：平面平行于头部后平面，
 * 并**完全跟随头部**（左右转向 + 抬头低头的俯仰），因此无论转头还是点头，光环都贴在脑后。
 *
 * <p>坐标系约定（Minecraft 模型骨骼空间）：进入头部骨骼后，**−Y 向上、+Z 朝玩家身后、−Z 是正前方**。
 * 所以"往上抬"要写 {@code translate(0, -HEIGHT_OFFSET, ...)}。
 */
public class BangBangHaloRenderer implements ICurioRenderer {
	private static final ResourceLocation TEXTURE = SummyReliquary.id("textures/item/bangbang_halo.png");

	// ================== 实机观感调节区：只改这三个常量即可挪动 / 缩放光环 ==================
	/**
	 * 从头部骨骼基准点（脖子处）沿头顶方向上移的距离（单位：格）。
	 * 0.50 = 正好对齐头顶：光环中线与头顶平齐，上下各露一半。
	 */
	private static final float HEIGHT_OFFSET = 0.50F;
	/** 从头部骨骼基准点沿头部朝向往后移的距离（单位：格），0.55 时贴在脑后 */
	private static final float BACK_OFFSET = 0.55F;
	/** 光环半径（单位：格，最终边长约为它的两倍） */
	private static final float HALF_SIZE = 0.32F;
	// =====================================================================================

	@Override
	public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext slotContext,
			PoseStack poseStack, RenderLayerParent<T, M> renderLayerParent, MultiBufferSource buffer, int light,
			float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
			float netHeadYaw, float headPitch) {
		// 只有人形模型（玩家、僵尸这类）才有头部骨骼
		if (!(renderLayerParent.getModel() instanceof HumanoidModel<?> humanoid)) {
			return;
		}

		poseStack.pushPose();

		// Curios 的渲染回调里矩阵已经在实体坐标系中，先把矩阵挪到头部骨骼上（跟随左右转向 + 抬头低头）
		humanoid.head.translateAndRotate(poseStack);
		// 需求：完全跟随头部俯仰。若哪天想改回"只跟随转头、俯仰保持竖直"，
		// 在这里加回下面这行即可：
		// poseStack.mulPose(Axis.XP.rotation(-humanoid.head.xRot));

		// 该坐标系中 −Y 向上、+Z 向玩家身后（−Z 是正前方）：先抬到头顶之上，再退到脑后
		poseStack.translate(0.0D, -HEIGHT_OFFSET, BACK_OFFSET);
		poseStack.scale(HALF_SIZE, HALF_SIZE, HALF_SIZE);

		// 按需求把贴图在平面内旋转 180°（顺时针/逆时针 180° 效果相同）
		poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));

		// 在本地 XY 平面画四边形：该平面平行于头部后平面且竖直于地面
		Matrix4f matrix = poseStack.last().pose();
		VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
		drawQuad(consumer, matrix, light);

		poseStack.popPose();
	}

	/** 画一个竖直四边形（位于本地 XY 平面），贴图 UV 铺满 (0,0)-(1,1) */
	private static void drawQuad(VertexConsumer consumer, Matrix4f matrix, int light) {
		vertex(consumer, matrix, -1.0F, 1.0F, 0.0F, 0.0F, 0.0F, light);
		vertex(consumer, matrix, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, light);
		vertex(consumer, matrix, 1.0F, -1.0F, 0.0F, 1.0F, 1.0F, light);
		vertex(consumer, matrix, -1.0F, -1.0F, 0.0F, 0.0F, 1.0F, light);
	}

	private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z,
			float u, float v, int light) {
		consumer.vertex(matrix, x, y, z)
				.color(255, 255, 255, 255)
				.uv(u, v)
				.overlayCoords(OverlayTexture.NO_OVERLAY)
				.uv2(light)
				// 本地 +Z 指向玩家身后，法线朝后朝向观察者
				.normal(0.0F, 0.0F, 1.0F)
				.endVertex();
	}
}
