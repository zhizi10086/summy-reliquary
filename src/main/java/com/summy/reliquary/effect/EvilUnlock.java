package com.summy.reliquary.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邪恶度里程碑（1.6.0）：达到阈值只**解锁对应配方**（不发放物品）。
 *
 * <p>解锁状态记在玩家 NBT {@code evil_unlocks} 的位图里，**永久生效**（邪恶度日后衰减也不会收回）；
 * 客户端把它同步过去，用来在 JEI 里隐藏未解锁的配方；服务端由 {@code EvilRecipeGate} 兜底拦合成。
 *
 * <p>入库进度（1.7.1）：**7 项全部转正，并且都有正式合成表**（1.7.0 补齐）——
 * 咒印（3×「6」无序）、复仇之魂 / 夜之幽魂 / 硫磺火 / 玄秘魔眼 / 深渊领主 / 亚巴顿（各自的 3×3）；
 * 它们的配方都在这里登记，于是**未解锁时 JEI 隐藏 + 服务端收走产物并退料**（见 {@code EvilRecipeGate}）。
 */
public enum EvilUnlock {
	/** 100：复仇之魂（1.6.2 转正；1.7.0 起有 3×3 配方） */
	VENGEFUL_SPIRIT("vengeful_spirit", 100, 2),
	/** 300：咒印（有 3×「6」无序配方 → 立即受合成门禁管辖） */
	THE_MARK("the_mark", 300, 2),
	/** 500：夜之幽魂（1.6.3 转正：加护栏，移速 +20% + 创造飞行半速） */
	NIGHT_WRAITH("night_wraith", 500, 3),
	/** 666：硫磺火（1.6.4 转正，1.7.0 起有配方）+ 永久锁定天使线 */
	BRIMSTONE("brimstone", 666, 3),
	/** 700：玄秘魔眼（1.6.5 转正，1.7.0 起用 3×3 升级表；替代旧的"夜之幽魂 + 3×「6」"） */
	OCCULT_EYE("occult_eye", 700, 2, "message.summy-reliquary.pact.unlock.700.3"),
	/** 900：深渊领主（1.6.7 转正，1.7.0 起有配方） */
	ABYSS_LORD("abyss_lord", 900, 2),
	/** 1000：亚巴顿（1.6.8 转正，1.7.0 起有配方） */
	ABADDON("abaddon", 1000, 3);

	/** 配方 id → 解锁条目（只有登记在案的配方会被 JEI 隐藏 / 服务端拦合成） */
	private static final Map<ResourceLocation, EvilUnlock> GATED_RECIPES = new LinkedHashMap<>();

	static {
		// 1.7.0：恶魔线配方的门禁清单（JEI 按解锁位图隐藏 + 服务端取产物时收走并退料）
		//   咒印：3×「6」无序
		//   玄秘魔眼 / 复仇之魂 / 夜之幽魂 / 硫磺火 / 深渊领主 / 亚巴顿：各自的 3×3 合成表
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "the_mark"), THE_MARK);
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "occult_eye"), OCCULT_EYE);
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "vengeful_spirit"), VENGEFUL_SPIRIT);
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "night_wraith"), NIGHT_WRAITH);
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "brimstone"), BRIMSTONE);
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "abyss_lord"), ABYSS_LORD);
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "abaddon"), ABADDON);
		// 1.7.6：暗仪刺刀借 700 档（与玄秘魔眼同一解锁位）
		GATED_RECIPES.put(new ResourceLocation("summy-reliquary", "dark_arts"), OCCULT_EYE);
	}

	private final String itemId;
	private final int threshold;
	private final int lineCount;

	/**
	 * 1.7.10：附加在「（你解锁了 xxx）」**之后**的尾部语言键（灰色正体，与那一行同款样式）。
	 *
	 * <p>目前只有 700 档用（「（你腰间的匕首震动了一下）」）—— 按需求"先说解锁了什么，再说匕首的事"；
	 * 其余档位为 {@code null}，行数与顺序完全不变。
	 */
	private final String tailKey;

	EvilUnlock(String itemId, int threshold, int lineCount) {
		this(itemId, threshold, lineCount, null);
	}

	EvilUnlock(String itemId, int threshold, int lineCount, String tailKey) {
		this.itemId = itemId;
		this.threshold = threshold;
		this.lineCount = lineCount;
		this.tailKey = tailKey;
	}

	/** 物品 id（语言键 {@code item.summy-reliquary.<itemId>} 即中文名） */
	public String itemId() {
		return itemId;
	}

	/** 解锁需要的邪恶度 */
	public int threshold() {
		return threshold;
	}

	/** 台词行数（服务端拿不到译文，所以行数必须写在代码里） */
	public int lineCount() {
		return lineCount;
	}

	/** 1.7.10：尾部行的语言键（没有则 null；打印在「（你解锁了 xxx）」之后、同款灰色正体） */
	public String tailKey() {
		return tailKey;
	}

	/** 位图里的那一位（按 ordinal） */
	public int bit() {
		return 1 << ordinal();
	}

	/** 解锁提示文本的语言键前缀（{@code ...unlock.<阈值>.1} ……） */
	public String lineKey(int index) {
		return "message.summy-reliquary.pact.unlock." + threshold + "." + index;
	}

	/** 该位图是否已经解锁本项目 */
	public boolean unlocked(int unlocks) {
		return (unlocks & bit()) != 0;
	}

	/**
	 * 该玩家现在是否已经解锁这一项（1.6.3）。
	 *
	 * <p>判据 = **解锁位图已置位** __或__ **当前邪恶度 ≥ 阈值**。位图是永久凭据（邪恶度日后衰减也不收回），
	 * "当前邪恶度达标"只是容错：即使位图还没同步到客户端、或解锁那一下错过了，也不会误报"邪恶不足"。
	 */
	public boolean unlockedFor(net.minecraft.world.entity.LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		return unlocked(PlayerFlags.evilUnlocks(entity))
				|| DemonPact.evilValue(entity) >= threshold;
	}

	/** 恶魔线物品的完整使用门槛：恶魔标记 + 已解锁（位图或当前邪恶度达标） */
	public static boolean usable(net.minecraft.world.entity.LivingEntity entity, EvilUnlock unlock) {
		return unlock != null && PlayerFlags.isDemon(entity) && unlock.unlockedFor(entity);
	}

	/** 全部受管配方：配方 id → 解锁条目 */
	public static Map<ResourceLocation, EvilUnlock> gatedRecipes() {
		return GATED_RECIPES;
	}

	/** 按物品 id 找解锁条目（找不到返回 null） */
	public static EvilUnlock byItemId(String itemId) {
		// 1.7.6：先看"受管配方"清单 —— 暗仪刺刀借 700 档，但它本身不是枚举条目
		for (Map.Entry<ResourceLocation, EvilUnlock> entry : GATED_RECIPES.entrySet()) {
			if (entry.getKey().getPath().equals(itemId)) {
				return entry.getValue();
			}
		}
		for (EvilUnlock unlock : values()) {
			if (unlock.itemId.equals(itemId)) {
				return unlock;
			}
		}
		return null;
	}

	/** 全部里程碑（按阈值升序） */
	public static List<EvilUnlock> ordered() {
		return List.of(values());
	}
}
