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
		add(tooltip, demonFlavor(key, args));
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
		add(tooltip, angelFlavor(key, args));
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
		add(tooltip, neutralFlavor(key, args));
	}

	/**
	 * 统一提示输出入口（1.8.2）：把一行文本按提示框宽度折成多行再追加。
	 *
	 * <p>折行口径与原版悬停文本一致（{@code max(窗口 GUI 宽 / 2, 200)}），配色按 style 逐段保留；
	 * 短行折完仍是 1 行，所以调用方不必自己判断宽度。专用服务端没有字体，原样输出单行。
	 */
	public static void add(List<Component> tooltip, Component line) {
		tooltip.addAll(wrap(line));
	}

	/**
	 * 按统一格式追加提示行。
	 *
	 * @param taglineKey 风味文本的语言键
	 * @param descKey    按住 Shift 时显示的功能描述语言键
	 */
	public static void append(List<Component> tooltip, String taglineKey, String descKey) {
		add(tooltip, Component.translatable(taglineKey).withStyle(ChatFormatting.GRAY));
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
			add(tooltip, Component.translatable(descKey, args).withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(shiftHint());
		}
	}

	/** 「按住 <Shift> 查看功能」提示行 */
	public static MutableComponent shiftHint() {
		return Component.translatable("item.summy-reliquary.shift_hint").withStyle(ChatFormatting.DARK_GRAY);
	}

	/** 「按住 <Alt> 查看介绍」提示行 */
	public static MutableComponent altHint() {
		return Component.translatable("item.summy-reliquary.alt_hint").withStyle(ChatFormatting.DARK_GRAY);
	}

	/**
	 * 「属性名 + 数值」两段配色的提示行（1.6.3）。
	 *
	 * <p>约定语言文件里写成 {@code 属性名|数值}（例：{@code 移动速度|+20%}）：属性名用派系的"暗色"、
	 * 数值用派系的"亮色"，中间留一个空格。**不含 {@code |} 的行**视为叙述句，按普通灰色输出。
	 */
	public static void statLine(List<Component> tooltip,
			com.summy.reliquary.text.ReliquaryFaction faction, String key, Object... args) {
		add(tooltip, statComponent(faction, key, args));
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
		add(tooltip, Component.translatable(key, args).withStyle(ChatFormatting.GRAY));
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
	 * 四把仪式武器是否处于「未进对应路线」的锁定态（1.8.2）。
	 *
	 * <p>判据与右键技能 / 左键伤害完全同源（{@code WeaponGates#qualified}）：
	 * 献祭匕首＝当前持恶魔标记、暗仪刺刀＝恶魔标记 + 邪恶 700、两把长矛＝天使标记。
	 * 服务端没有本地玩家 → 返回 false（不额外加行）。
	 */
	public static boolean weaponLocked(net.minecraft.world.item.Item item) {
		return weaponLocked(localPlayer(), item);
	}

	/** 同上，但显式传入实体（自检 / 纯函数复用） */
	public static boolean weaponLocked(LivingEntity entity, net.minecraft.world.item.Item item) {
		return entity != null && !com.summy.reliquary.effect.WeaponGates.qualified(entity, item);
	}

	/** 武器未达标时追加的门槛提示行（匕首两态 / 长矛一态，文案复用 WeaponGates 的判据） */
	public static void appendWeaponHint(List<Component> tooltip, net.minecraft.world.item.Item item) {
		LivingEntity entity = localPlayer();
		add(tooltip, Component.translatable(
						com.summy.reliquary.effect.WeaponGates.hintKey(entity, item))
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
	 * 提示行里的数值格式化（1.8.4）：去掉多余的小数点 —— {@code 8.0 → 8}、{@code 1.5 → 1.5}。
	 *
	 * <p>联动数值改成"按当前佩戴状态显示"之后，同一条属性行会拿到整数值（基础档）
	 * 或半整数值（联动档），统一在这里收口，避免每件饰品各写一份 format。
	 */
	public static String number(double value) {
		return Math.abs(value - Math.rint(value)) < 1.0E-6D
				? String.valueOf((long) Math.rint(value))
				: String.valueOf(Math.round(value * 10.0D) / 10.0D);
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
