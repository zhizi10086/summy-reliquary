package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.attribute.ReliquaryAttributes;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 魂心 HUD（1.6.1 起；1.6.2 改为读**独立魂心池**的同步点数）。
 *
 * <p>魂心在 1.6.2 之后是一个**独立池**（顺序：吸收/护盾 → 魂心 → 黑心 → 真血），不再占用原版吸收值，
 * 所以这里画的是"池子里还剩多少"，画在**生命心之后的吸收位**（与之前的观感一致）。
 * 原版黄心照常显示、不隐藏也不改写。
 */
public final class SoulHeartOverlay implements IGuiOverlay {
	/** 满心贴图 */
	private static final ResourceLocation FULL =
			new ResourceLocation(SummyReliquary.NAMESPACE, "textures/gui/soul_heart/full.png");
	/** 半心贴图 */
	private static final ResourceLocation HALF =
			new ResourceLocation(SummyReliquary.NAMESPACE, "textures/gui/soul_heart/half.png");
	/** 单颗心贴图尺寸（9×9，与原版心一致） */
	private static final int HEART_SIZE = 9;
	/** 相邻两颗心的水平间距（与原版一致） */
	private static final int HEART_STEP = 8;
	/** 每行的最大心数（与原版一致） */
	private static final int HEARTS_PER_ROW = 10;
	/** 每多一行的垂直偏移 */
	private static final int ROW_STEP = 10;

	@Override
	public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
			float partialTick, int width, int height) {
		if (!ReliquaryConfig.enableSoulHeartHud()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		// 创造与旁观模式不画（1.6.2）；F1 隐藏 GUI 时同样不画
		if (!shouldRender(player, client.options.hideGui)) {
			return;
		}
		double ourPoints = ourSoulHeartPoints(player);
		if (ourPoints <= 0.0D) {
			return;
		}
		int ourHearts = Mth.ceil(ourPoints / 2.0D);
		if (ourHearts <= 0) {
			return;
		}
		boolean half = ourPoints % 2.0D != 0.0D;
		int startX = width / 2 - 91;
		// 1.6.5 起不再与"生命心之后的吸收位"抢位置（那会和原版黄心重叠），改成**独立占行**：
		// 基准行 = 原版"红心 + 黄心"这张心网格的上方一排；玩家有盔甲时原版盔甲占用网格再上一排，所以再避让一排。
		// 1.6.6 修正：以前只按"一排红心"估算，玩家生命心超过一排（例如灵魂 +18 生命 = 19 颗心）时
		// 会正好压住第二排红心。现在完全复刻原版 Gui#renderPlayerHealth 的网格行数与行距。
		int startY = soulHeartRowY(height, maxHealthOf(player), player.getAbsorptionAmount(),
				player.getArmorValue());
		for (int index = 0; index < ourHearts; index++) {
			int x = startX + (index % HEARTS_PER_ROW) * HEART_STEP;
			int y = startY - (index / HEARTS_PER_ROW) * ROW_STEP;
			boolean drawHalf = half && index == ourHearts - 1;
			graphics.blit(drawHalf ? HALF : FULL, x, y, 0, 0, HEART_SIZE, HEART_SIZE,
					HEART_SIZE, HEART_SIZE);
		}
	}

	/**
	 * 魂心（蓝心）区域的基准行（Y）：**原版心网格顶行**再往上 10px；玩家有盔甲时原版盔甲占用网格上面那一排，
	 * 所以再上移 10px。
	 *
	 * <p>多出来的行继续往上排（与原版吸收心同样的折行规则），因此蓝心永远不会压住红心、黄心或盔甲图标。
	 *
	 * @param maxHealth        玩家当前生命上限（取 max(属性上限, 当前生命)，与原版 Gui 的口径一致）
	 * @param absorptionPoints 玩家当前的原版吸收值（黄心 / 其它模组护盾），它们和红心共用同一张网格
	 */
	public static int soulHeartRowY(int screenHeight, float maxHealth, float absorptionPoints, int armorPoints) {
		return vanillaHeartGridTopY(screenHeight, maxHealth, absorptionPoints)
				- 10 * (1 + (armorPoints > 0 ? 1 : 0));
	}

	/**
	 * 原版"红心 + 黄心"网格共有几排（复刻 {@code Gui#renderPlayerHealth}：
	 * {@code ceil((maxHealth + ceil(absorption)) / 2 / 10)}）。
	 */
	public static int vanillaHeartGridRows(float maxHealth, float absorptionPoints) {
		float absorption = Mth.ceil(absorptionPoints);
		return Math.max(1, Mth.ceil((maxHealth + absorption) / 2.0F / 10.0F));
	}

	/** 原版网格的行距：原版会在"心数变多"时压缩行距（{@code max(10 - (rows - 2), 3)}） */
	public static int vanillaHeartGridRowSpacing(float maxHealth, float absorptionPoints) {
		return Math.max(10 - (vanillaHeartGridRows(maxHealth, absorptionPoints) - 2), 3);
	}

	/** 原版心网格**最上面一排**的 Y（红心从 {@code screenHeight - 39} 往上排） */
	public static int vanillaHeartGridTopY(int screenHeight, float maxHealth, float absorptionPoints) {
		int rows = vanillaHeartGridRows(maxHealth, absorptionPoints);
		return screenHeight - 39 - (rows - 1) * vanillaHeartGridRowSpacing(maxHealth, absorptionPoints);
	}

	/** 客户端玩家的"生命上限"口径：与原版 Gui 一样取 max(属性上限, 当前生命) */
	public static float maxHealthOf(Player player) {
		return player == null ? 20.0F : Math.max(player.getMaxHealth(), player.getHealth());
	}

	/** 魂心占多少行（每行 10 颗；0 点 = 0 行） */
	public static int soulHeartRows(double points) {
		int hearts = heartCount(points);
		return hearts <= 0 ? 0 : (hearts + HEARTS_PER_ROW - 1) / HEARTS_PER_ROW;
	}

	/**
	 * 是否应该绘制这层 HUD（1.6.2）：F1 隐藏 GUI、旁观、创造模式都不画；自检直接断言这个纯函数。
	 */
	public static boolean shouldRender(Player player, boolean hideGui) {
		return player != null && !hideGui && !player.isSpectator() && !player.isCreative();
	}

	/**
	 * 本模组的魂心池剩余点数（1.6.2）。
	 *
	 * <p>读服务端同步过来的池子点数；没有收到状态包时（例如刚进游戏）退回"按属性估算"的旧口径，
	 * 保证 HUD 不会因为同步延迟而闪一下 0。
	 */
	public static double ourSoulHeartPoints(Player player) {
		int synced = ReliquaryClientState.soulHeartPoints();
		if (ReliquaryClientState.hasSynced()) {
			return Math.max(0, synced);
		}
		double hearts = player.getAttributeValue(ReliquaryAttributes.SOUL_HEARTS.get());
		return Math.max(0.0D, hearts) * ReliquaryConfig.absorptionPerSoulHeart();
	}

	/** 需要画的完整蓝心数（4 点 = 2 颗；3 点 = 1 颗 + 1 颗半心）；自检也复用 */
	public static int heartCount(double points) {
		return Math.max(0, Mth.ceil(points / 2.0D));
	}

	/** 最后一颗是否需要画半心（点数为奇数时） */
	public static boolean hasHalf(double points) {
		return points > 0.0D && points % 2.0D != 0.0D;
	}
}
