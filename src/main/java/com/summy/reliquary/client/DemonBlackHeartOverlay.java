package com.summy.reliquary.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.effect.DemonPact;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 黑心 HUD（1.6.0）：在血条上方把「黑心池」画成原版风格的黑色心。
 *
 * <p>为什么必须自己画：原版血条只认 {@code MobEffects.WITHER}（而且会把**整条**血变成凋零心），
 * 吸收值则永远是黄心 —— 没有任何 API 能把"额外的心"画成黑心。这里用
 * {@code RegisterGuiOverlaysEvent.registerAbove(PLAYER_HEALTH)} 在血条之上自绘一层，
 * 贴图是我们自己的 9×9 黑心（{@code textures/gui/demon_black_heart/}），随时可替换。
 *
 * <p>位置：`x = 屏幕中心 - 91`（与原版血条左端对齐）；`y = 血条上方一行`，
 * 如果玩家有吸收值（黄血）就再往上挪一行，避免与黄心重叠。
 * <p>只在**佩戴契约**且黑心池 > 0 时绘制（与"黑心随契约生效"一致）。
 */
public final class DemonBlackHeartOverlay implements IGuiOverlay {
	/** 满心贴图 */
	private static final ResourceLocation FULL =
			new ResourceLocation(SummyReliquary.NAMESPACE, "textures/gui/demon_black_heart/full.png");
	/** 半心贴图 */
	private static final ResourceLocation HALF =
			new ResourceLocation(SummyReliquary.NAMESPACE, "textures/gui/demon_black_heart/half.png");
	/** 单颗心的贴图尺寸（9×9，与原版心一致） */
	private static final int HEART_SIZE = 9;
	/** 相邻两颗心的水平间距 */
	private static final int HEART_STEP = 8;
	/** 最多绘制多少颗（保险丝：配置被改大时也不至于糊满屏幕） */
	private static final int MAX_HEARTS = 20;

	@Override
	public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
			float partialTick, int width, int height) {
		Minecraft client = Minecraft.getInstance();
		// 创造与旁观模式不画（1.6.2）；F1 隐藏 GUI 时同样不画
		if (!shouldRender(client.player, client.options.hideGui)) {
			return;
		}
		// 黑心随契约生效：没戴契约就不画
		if (!DemonPact.active(client.player)) {
			return;
		}
		int points = DemonPact.blackHeartPoints(client.player);
		if (points <= 0) {
			return;
		}
		int full = Math.min(MAX_HEARTS, DemonPact.blackHeartFullCount(points));
		boolean half = DemonPact.blackHeartHasHalf(points) && full < MAX_HEARTS;

		int x = width / 2 - 91;
		// 1.6.5：黑心紧跟在魂心（蓝心）之后的**独立行**——蓝心在盔甲条上方、黑心再往上，避免压住黄心与盔甲图标
		// 1.6.6：行位改为按原版"红心 + 黄心"网格的真实行数 / 行距推算（多排红心时也不会重叠）
		// 1.8.5 补修：经典状态条环境改用它的实际条堆高度锚定（与魂心同一个函数）
		int y = blackHeartRowY(height, SoulHeartOverlay.maxHealthOf(client.player),
				client.player.getAbsorptionAmount(), client.player.getArmorValue(),
				SoulHeartOverlay.ourSoulHeartPoints(client.player), gui.rightHeight,
				com.summy.reliquary.config.ReliquaryConfig.soulHeartHudOffsetY());
		PoseStack pose = graphics.pose();
		pose.pushPose();
		for (int index = 0; index < full; index++) {
			graphics.blit(FULL, x + index * HEART_STEP, y, 0, 0, HEART_SIZE, HEART_SIZE,
					HEART_SIZE, HEART_SIZE);
		}
		if (half) {
			graphics.blit(HALF, x + full * HEART_STEP, y, 0, 0, HEART_SIZE, HEART_SIZE,
					HEART_SIZE, HEART_SIZE);
		}
		pose.popPose();
	}

	/** 黑心区域的基准行（Y）：魂心区域之上（魂心没点时直接占用魂心的基准行）；兼容入口，`rightHeight` 按 0 处理 */
	public static int blackHeartRowY(int screenHeight, float maxHealth, float absorptionPoints, int armorPoints,
			double soulPoints) {
		return SoulHeartOverlay.soulHeartRowY(screenHeight, maxHealth, absorptionPoints, armorPoints)
				- 10 * SoulHeartOverlay.soulHeartRows(soulPoints);
	}

	/**
	 * 1.8.5 补修：带「经典状态条」条堆高度的重载（`render` 用）。
	 *
	 * <p>魂心与黑心**共用同一套行位解析**，黑心永远画在魂心区域之上，因此 Classic Bar 环境里两者
	 * 会一起贴着条堆往上排（各自 10px 一行）。
	 */
	public static int blackHeartRowY(int screenHeight, float maxHealth, float absorptionPoints, int armorPoints,
			double soulPoints, int classicBarRightHeight, int offsetY) {
		return SoulHeartOverlay.soulHeartRowY(screenHeight, maxHealth, absorptionPoints, armorPoints,
				classicBarRightHeight, offsetY)
				- 10 * SoulHeartOverlay.soulHeartRows(soulPoints);
	}

	/** 是否应该绘制这层 HUD（1.6.2）：F1 隐藏 GUI、旁观、创造模式都不画 */
	public static boolean shouldRender(net.minecraft.world.entity.player.Player player, boolean hideGui) {
		return player != null && !hideGui && !player.isSpectator() && !player.isCreative();
	}
}
