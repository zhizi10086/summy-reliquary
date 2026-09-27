package com.summy.reliquary.item;

import com.summy.reliquary.client.TooltipClientHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.List;
import java.util.function.Supplier;

/**
 * 饰品共用的提示格式：
 * <ol>
 *     <li>栏位行由 **Curios 自带**（{@code curios.tooltip.slot} + {@code curios.identifier.*}），本模组不再重复输出；</li>
 *     <li>风味文本（灰）</li>
 *     <li>按住 Shift 时显示功能描述，否则显示「按住 Shift 查看功能」提示（深灰）。</li>
 * </ol>
 *
 * <p>提示最终顺序：物品名 → Curios 栏位行 → 风味文本 → Shift 提示。
 */
public final class ReliquaryTooltips {
	/**
	 * 恶魔线风味行的颜色（1.6.9）：与恶魔线物品名 / 亚巴顿 tagline 同色（亮一档的深红）。
	 *
	 * <p>只上色、**不改文案**；引号句、{@code （……）} 括注行、{@code 属性名|数值} 属性行都不走这里。
	 */
	public static final int DEMON_FLAVOR_COLOR = 0xC03030;

	private ReliquaryTooltips() {
	}

	/** 恶魔线风味行（深红正体）；引号句 / 括注行 / 属性行不适用 */
	public static MutableComponent demonFlavor(String key, Object... args) {
		return Component.translatable(key, args)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(DEMON_FLAVOR_COLOR)));
	}

	/** 同上，直接追加到提示列表 */
	public static void demonFlavorLine(List<Component> tooltip, String key, Object... args) {
		tooltip.add(demonFlavor(key, args));
	}

	/**
	 * 天使线风味行（1.7.9）：与 {@link #demonFlavor(String, Object...)} 对称，用天使线的"淡金"
	 * （{@code #FFE4B5}，与派系属性名同色）。只给两把长矛的风味句用；Shift 叙述行仍是灰色。
	 */
	public static MutableComponent angelFlavor(String key, Object... args) {
		return Component.translatable(key, args)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(
						com.summy.reliquary.text.ReliquaryFaction.ANGEL.nameColor())));
	}

	/** 同上，直接追加到提示列表 */
	public static void angelFlavorLine(List<Component> tooltip, String key, Object... args) {
		tooltip.add(angelFlavor(key, args));
	}

	/**
	 * 中立物品风味行（1.7.10）：用中立派系的"暗色"（{@code #AAAAAA}）。
	 *
	 * <p>给金刀片这类没有派系的物品用；Shift 叙述行仍是灰色（{@link #narrativeLine}）。
	 */
	public static MutableComponent neutralFlavor(String key, Object... args) {
		return Component.translatable(key, args)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(
						com.summy.reliquary.text.ReliquaryFaction.NEUTRAL.nameColor())));
	}

	/** 同上，直接追加到提示列表 */
	public static void neutralFlavorLine(List<Component> tooltip, String key, Object... args) {
		tooltip.add(neutralFlavor(key, args));
	}

	/**
	 * 按统一格式追加提示行。
	 *
	 * @param taglineKey 风味文本的语言键
	 * @param descKey    按住 Shift 时显示的功能描述语言键
	 */
	public static void append(List<Component> tooltip, String taglineKey, String descKey) {
		tooltip.add(Component.translatable(taglineKey).withStyle(ChatFormatting.GRAY));
		appendShiftLine(tooltip, descKey);
	}

	/** 只追加「Shift 功能描述 / 按住 Shift 查看功能」这一行（供需要额外插入套装块的饰品复用） */
	public static void appendShiftLine(List<Component> tooltip, String descKey) {
		appendShiftLine(tooltip, descKey, new Object[0]);
	}

	/** 同上，但功能描述里可以带参数（例如把配置里的数值填进提示） */
	public static void appendShiftLine(List<Component> tooltip, String descKey, Object... args) {
		if (shiftDown()) {
			// 功能描述行用浅灰，比提示行更显眼
			tooltip.add(Component.translatable(descKey, args).withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(shiftHint());
		}
	}

	/** 「按住 <Shift> 查看功能」提示行 */
	public static MutableComponent shiftHint() {
		return Component.translatable("item.summy-reliquary.shift_hint").withStyle(ChatFormatting.DARK_GRAY);
	}

	/**
	 * 「属性名 + 数值」两段配色的提示行（1.6.3）。
	 *
	 * <p>约定语言文件里写成 {@code 属性名|数值}（例：{@code 移动速度|+20%}）：属性名用派系的"暗色"、
	 * 数值用派系的"亮色"，中间留一个空格。**不含 {@code |} 的行**视为叙述句，按普通灰色输出。
	 */
	public static void statLine(List<Component> tooltip,
			com.summy.reliquary.text.ReliquaryFaction faction, String key, Object... args) {
		tooltip.add(statComponent(faction, key, args));
	}

	/**
	 * 同上，但直接返回组件（供需要在纯函数里拼装提示行的场景复用，例如五芒星的 {@code shiftLines}）。
	 */
	public static MutableComponent statComponent(com.summy.reliquary.text.ReliquaryFaction faction,
			String key, Object... args) {
		String raw = Component.translatable(key, args).getString();
		int separator = raw.indexOf('|');
		if (separator < 0) {
			return Component.literal(raw).withStyle(ChatFormatting.GRAY);
		}
		String name = raw.substring(0, separator);
		String value = raw.substring(separator + 1);
		MutableComponent line = Component.literal(name)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(faction.nameColor())));
		line.append(Component.literal(" " + value)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(faction.valueColor()))));
		return line;
	}

	/** 叙述句行（不含数值）：统一灰色，供"属性行 + 说明行"拆分后的说明部分复用 */
	public static void narrativeLine(List<Component> tooltip, String key, Object... args) {
		tooltip.add(Component.translatable(key, args).withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 当前玩家是否因为缺少「天使」标记而处于锁定状态。
	 *
	 * <p>灵台三件套 / 伯列恒之星 / 终末天启都有这条门槛：没拿到天使标记时既不能佩戴，
	 * 也**查不到功能描述**。玩家只在客户端存在，服务端返回 false（不额外加行）。
	 */
	public static boolean angelLocked() {
		Object local = localPlayer();
		return local instanceof LivingEntity entity
				&& !com.summy.reliquary.effect.PlayerFlags.hasAngel(entity);
	}

	/** 锁定时追加的浅灰提示行 */
	public static void appendAngelHint(List<Component> tooltip) {
		tooltip.add(Component.translatable("item.summy-reliquary.angel.required")
				.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 当前玩家是否因为"还没跟恶魔做过交易"而处于锁定状态（1.6.1，仪式法袍用）。
	 *
	 * <p>判据与 {@code CeremonialRobesItem#canEquip} 完全一致：**曾签约**或**当前仍是恶魔**。
	 * 服务端返回 false（不额外加行）。
	 */
	public static boolean demonLocked() {
		Object local = localPlayer();
		if (!(local instanceof LivingEntity entity)) {
			return false;
		}
		return !(com.summy.reliquary.effect.PlayerFlags.isDemonSealed(entity)
				|| com.summy.reliquary.effect.PlayerFlags.isDemon(entity));
	}

	/** 恶魔门槛锁定时追加的浅灰提示行 */
	public static void appendDemonHint(List<Component> tooltip) {
		tooltip.add(Component.translatable("item.summy-reliquary.demon.required")
				.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 「恶魔线饰品」的锁定判定（1.6.2）：需要**恶魔标记**且该里程碑**已解锁**。
	 *
	 * <p>复仇之魂 / 咒印用它：没拿到标记时不能佩戴、也查不到功能描述。服务端返回 false。
	 */
	public static boolean demonUnlockLocked(com.summy.reliquary.effect.EvilUnlock unlock) {
		LivingEntity entity = localPlayer();
		if (entity == null) {
			return false;
		}
		// 1.6.3：判据 = 恶魔标记 +（解锁位图 或 当前邪恶度达标），避免位图没同步时误报"邪恶不足"
		return !com.summy.reliquary.effect.EvilUnlock.usable(entity, unlock);
	}

	/** 恶魔线饰品锁定时追加的提示行（区分"没签过约"与"邪恶度还不够"） */
	public static void appendDemonUnlockHint(List<Component> tooltip) {
		Object local = localPlayer();
		boolean signed = local instanceof LivingEntity entity
				&& (com.summy.reliquary.effect.PlayerFlags.isDemon(entity)
						|| com.summy.reliquary.effect.PlayerFlags.isDemonSealed(entity));
		tooltip.add(Component.translatable(signed
						? "item.summy-reliquary.demon.not_ready"
						: "item.summy-reliquary.demon.required")
				.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 是否按住 Shift（服务端一律 false）。
	 *
	 * <p>键状态只在客户端存在，且通用类不能引用 {@code Screen}（专用服务端会因 dist 校验失败而崩），
	 * 所以这里用 {@link DistExecutor} 把真正的客户端调用延后到客户端才加载的
	 * {@link TooltipClientHooks}。
	 */
	public static boolean shiftDown() {
		return FMLEnvironment.dist.isClient()
				&& Boolean.TRUE.equals(
						DistExecutor.<Boolean>unsafeCallWhenOn(Dist.CLIENT,
								() -> () -> TooltipClientHooks.isShiftDown()));
	}

	/** 是否按住 Alt（服务端一律 false） */
	public static boolean altDown() {
		return FMLEnvironment.dist.isClient()
				&& Boolean.TRUE.equals(
						DistExecutor.<Boolean>unsafeCallWhenOn(Dist.CLIENT,
								() -> () -> TooltipClientHooks.isAltDown()));
	}

	/** 本地玩家（服务端返回 null，不会加载任何客户端类） */
	public static LivingEntity localPlayer() {
		return DistExecutor.<LivingEntity>unsafeCallWhenOn(Dist.CLIENT,
				() -> () -> TooltipClientHooks.localPlayer());
	}

	/**
	 * 把一行提示按提示框宽度折成多行（原版提示不会自动折行，长句会顶出屏幕）。
	 * 服务端没有字体，原样返回单行。
	 */
	public static List<Component> wrap(Component line) {
		if (!FMLEnvironment.dist.isClient()) {
			return List.of(line);
		}
		List<Component> wrapped = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
				() -> () -> TooltipClientHooks.wrap(line));
		return wrapped == null || wrapped.isEmpty() ? List.of(line) : wrapped;
	}
}
