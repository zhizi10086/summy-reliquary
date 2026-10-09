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
	/** 1.8.5：环境探测缓存（{@code null} = 还没探测过）。客户端渲染期间 mod 列表稳定，探测一次即可。 */
	private static Boolean mantleLoaded;
	private static Boolean classicBarLoaded;
	/** 1.8.5 补修：参与 HUD 行数计算的吸收值上限（50 颗心）—— 吸收值异常时蓝心不再被顶出屏幕 */
	private static final float MAX_LAYOUT_ABSORPTION = 100.0F;
	/** 钳制提示整局只打一条 */
	private static boolean absorptionClampLogged;
	/** 自检用：强制布局档（{@code null} = 按配置 + 环境解析） */
	private static Layout layoutForTest;

	/** 魂心 / 黑心 HUD 的定位方式（1.8.5 补修） */
	public enum Layout {
		/** 完全复刻原版 `Gui#renderPlayerHealth` 的心网格折行 */
		VANILLA,
		/** 血条恒为一排（Mantle）：红心恒按一排、盔甲避让保留、不为原版吸收值留行 */
		SINGLE_ROW,
		/** 按「经典状态条」的实际条位锚定：红心 / 黄心 / 盔甲已全部并进它的条堆 */
		CLASSIC_ROW
	}

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
		// 1.8.5 补修：经典状态条环境改用它的实际条堆高度（gui.rightHeight）锚定 —— 注册锚点已挪到
		// ITEM_NAME 之上，保证读到的偏移是累加完的（与 Enchantment Reforged 的额外饥饿行同一套做法）。
		int startY = soulHeartRowY(height, maxHealthOf(player), player.getAbsorptionAmount(),
				player.getArmorValue(), gui.rightHeight, ReliquaryConfig.soulHeartHudOffsetY());
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
		return soulHeartRowY(screenHeight, maxHealth, absorptionPoints, armorPoints, 0,
				ReliquaryConfig.soulHeartHudOffsetY());
	}

	/**
	 * 1.8.5 补修的主入口（{@code render} 用）：显式给出「经典状态条」的条堆高度 {@code gui.rightHeight}。
	 *
	 * <p>按 {@link #layout()} 解析出的档位分派：`CLASSIC_ROW` 且 {@code rightHeight > 0} 时按实际条位锚定；
	 * 其余（含 `CLASSIC_ROW` 但 {@code rightHeight <= 0} 的回退）走原版 / 单排公式。
	 */
	public static int soulHeartRowY(int screenHeight, float maxHealth, float absorptionPoints, int armorPoints,
			int classicBarRightHeight, int offsetY) {
		Layout kind = layout();
		if (kind == Layout.CLASSIC_ROW && classicBarRightHeight > 0) {
			return classicSoulHeartRowY(screenHeight, classicBarRightHeight, offsetY);
		}
		return soulHeartRowY(screenHeight, maxHealth, absorptionPoints, armorPoints,
				kind != Layout.VANILLA, offsetY);
	}

	/**
	 * 纯函数（自检直接断言）：按「经典状态条」的实际条堆高度取基准行。
	 *
	 * <p>与 Enchantment Reforged 的额外饥饿行同一套公式（{@code screenHeight − rightHeight − 10}）；
	 * 该档**不再叠加**吸收值与盔甲行 —— 红心 / 黄心 / 盔甲条都已经被 Classic Bar 并进了 `rightHeight`。
	 */
	public static int classicSoulHeartRowY(int screenHeight, int classicBarRightHeight, int offsetY) {
		return screenHeight - Math.max(0, classicBarRightHeight) - 10 + offsetY;
	}

	/**
	 * 1.8.5 新增的可配重载：显式给出「是否按单排血条适配」与额外像素偏移。
	 *
	 * <p>`singleRow = true` 时，原版"红心 + 黄心"网格里的**红心恒按一排算**（不再因为生命上限 275 而
	 * 折到 14 排、把蓝心顶到天上），**且不再为原版吸收值留行**（Mantle 的血条恒为一行、Classic Bar
	 * 更是把整排换掉了）；**盔甲条的避让仍然保留**（Mantle 环境里盔甲行没有被接管）。
	 */
	public static int soulHeartRowY(int screenHeight, float maxHealth, float absorptionPoints, int armorPoints,
			boolean singleRow, int offsetY) {
		float absorption = singleRow ? 0.0F : layoutAbsorption(absorptionPoints);
		float gridHealth = singleRow ? Math.min(maxHealth, 20.0F) : maxHealth;
		return vanillaHeartGridTopY(screenHeight, gridHealth, absorption)
				- 10 * (1 + (armorPoints > 0 ? 1 : 0))
				+ offsetY;
	}

	/**
	 * 1.8.5 补修：按配置（`[spirit_altar] soul_heart_hud_layout`）与环境解析实际布局档。
	 *
	 * <p>`auto`：先看 **经典状态条 Classic Bar**（它把红心 / 黄心 / 盔甲条都换成了自己的条堆，
	 * 必须按 `rightHeight` 锚定）→ 再看 **Mantle**（Tinkers 前置，血条恒为一行）→ 都没有就保持
	 * 原版折行口径。
	 */
	public static Layout layout() {
		if (layoutForTest != null) {
			return layoutForTest;
		}
		String mode = ReliquaryConfig.soulHeartHudLayout();
		return switch (mode) {
			case "vanilla" -> Layout.VANILLA;
			case "single_row" -> Layout.SINGLE_ROW;
			case "classic_row" -> Layout.CLASSIC_ROW;
			default -> classicBarLoaded() ? Layout.CLASSIC_ROW
					: (mantleLoaded() ? Layout.SINGLE_ROW : Layout.VANILLA);
		};
	}

	/** 是否处在"血条不是原版多排心网格"的环境（自检与日志用） */
	public static boolean usesSingleRowLayout() {
		return layout() != Layout.VANILLA;
	}

	/** 自检用：强制某个布局档（传 {@code null} 恢复自动解析） */
	public static void setLayoutForTest(Layout kind) {
		layoutForTest = kind;
	}

	/** 自检用：清掉环境探测缓存、强制档与钳制提示标记 */
	public static void resetLayoutProbeForTest() {
		mantleLoaded = null;
		classicBarLoaded = null;
		absorptionClampLogged = false;
		layoutForTest = null;
	}

	/** Mantle（Tinkers 前置）是否已加载 */
	private static boolean mantleLoaded() {
		if (mantleLoaded == null) {
			mantleLoaded = modLoaded("mantle");
		}
		return mantleLoaded;
	}

	/** 经典状态条 Classic Bar 是否已加载 */
	private static boolean classicBarLoaded() {
		if (classicBarLoaded == null) {
			classicBarLoaded = modLoaded("classicbar");
		}
		return classicBarLoaded;
	}

	/**
	 * 某个 modid 是否已加载。
	 *
	 * <p>两端**都问一遍、取或**：Fabric 侧（Kilt / Connector）走 {@code FabricLoader}，Forge 侧走
	 * {@code ModList}。Connector（信雅互联）环境里 `FabricLoader` 只认识 Fabric 模组，Forge 侧的
	 * Mantle 必须靠 `ModList` 才能问到 —— 以前"先问 Fabric 就 return"会漏检。
	 *
	 * <p>用反射而不是直接引用，保证在任何一端缺类时都不会把 HUD 拖崩。
	 */
	private static boolean modLoaded(String modId) {
		try {
			Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object loader = loaderClass.getMethod("getInstance").invoke(null);
			Object result = loaderClass.getMethod("isModLoaded", String.class).invoke(loader, modId);
			if (result instanceof Boolean loaded && loaded) {
				return true;
			}
		} catch (Throwable ignored) {
			// Fabric 侧不存在或方法签名不符 → 只信 Forge 侧
		}
		try {
			net.minecraftforge.fml.ModList list = net.minecraftforge.fml.ModList.get();
			return list != null && list.isLoaded(modId);
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * 参与行数计算的吸收值：单排两档直接按 0 处理；原版档照旧，但异常大的值会钳到
	 * {@link #MAX_LAYOUT_ABSORPTION}（以后再有"吸收值被叠到几千"的怪状也不会把蓝心顶出屏幕）。
	 */
	private static float layoutAbsorption(float absorptionPoints) {
		if (absorptionPoints > MAX_LAYOUT_ABSORPTION) {
			if (!absorptionClampLogged) {
				absorptionClampLogged = true;
				SummyReliquary.LOGGER.info(
						"[Summy Reliquary] 魂心 HUD：吸收值 {} 超过行数计算上限 {}，本次按上限绘制（蓝心不再被顶出屏幕）",
						absorptionPoints, MAX_LAYOUT_ABSORPTION);
			}
			return MAX_LAYOUT_ABSORPTION;
		}
		return Math.max(0.0F, absorptionPoints);
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
