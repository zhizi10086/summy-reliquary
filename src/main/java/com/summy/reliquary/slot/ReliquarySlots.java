package com.summy.reliquary.slot;

import com.summy.reliquary.SummyReliquary;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 本模组的三个 Curios 栏位。
 *
 * <p>栏位本体是数据驱动的（见 {@code data/summy-reliquary/curios/slots/*.json}），
 * 这里只负责注册「哪个物品能进哪个栏位」的校验谓词，谓词 id 与 json 里的 validators 对应。
 */
public final class ReliquarySlots {
	/** 光环栏位 */
	public static final String HALO = "halo";
	/** 胃袋栏位 */
	public static final String STOMACH = "stomach";
	/** 魂印栏位 */
	public static final String SOUL_SEAL = "soul_seal";
	/** 灵台栏位（3 格：肉体 / 思想 / 灵魂） */
	public static final String SPIRIT_ALTAR = "spirit_altar";
	/** 启示栏位 */
	public static final String REVELATION = "revelation";
	/** 加护栏位（1.5.0 新增：救恩） */
	public static final String BLESSING = "blessing";
	/**
	 * Curios **自带**的「护符」栏位（1.5.8 起「五芒星」用它）。
	 *
	 * <p>这个栏位不在本模组的数据包里定义，而是 Curios 自己提供的
	 * （{@code data/curios/curios/slots/charm.json}，校验器是 {@code curios:tag}），
	 * 因此五芒星只要加进物品标签 {@code #curios:charm} 就能装进去。
	 */
	public static final String CHARM = "charm";
	/**
	 * 「恶魔契约」栏位（1.6.0）：**动态**的——签约时 +1 格、忏悔时压回 0 格。
	 */
	public static final String DEMON_PACT = "demon_pact";
	/**
	 * Curios **自带**的「背饰」栏位（1.6.1 起「仪式法袍」用它）。
	 *
	 * <p>与 {@link #CHARM} 同理：栏位本体由 Curios 提供（{@code data/curios/curios/slots/back.json}，
	 * 校验器 {@code curios:tag}），所以要给仪式法袍加物品标签 {@code #curios:back}，
	 * 并把 {@code back} 写进本模组玩家栏位表。
	 */
	public static final String BACK = "back";

	private static final Set<String> CUSTOM =
			Set.of(HALO, STOMACH, SOUL_SEAL, SPIRIT_ALTAR, REVELATION, BLESSING, DEMON_PACT);

	private ReliquarySlots() {
	}

	/** 在模组构造阶段注册三个校验谓词 */
	public static void registerValidators() {
		// 光环栏位：邦邦咔邦光环与新饰品「光环」共用（一次只能戴一个）
	register("halo_only", result -> result.stack().is(SummyReliquary.BANG_BANG_HALO.get())
			|| result.stack().is(SummyReliquary.THE_HALO.get())
			// 1.7.1：恶魔王冠也进「光环」栏（三者互斥）
			|| result.stack().is(SummyReliquary.DEVIL_CROWN.get()));
		register("stomach_only", result -> result.stack().is(SummyReliquary.FREELOADERS_RICE.get()));
		register("soul_seal_only", result -> result.stack().is(SummyReliquary.SOURCE_OF_SINS.get())
				|| result.stack().is(SummyReliquary.VIRTUES.get())
				|| result.stack().is(SummyReliquary.SATANIC_BIBLE.get()));
		register("spirit_altar_only", result -> result.stack().is(SummyReliquary.THE_BODY.get())
				|| result.stack().is(SummyReliquary.THE_MIND.get())
				|| result.stack().is(SummyReliquary.THE_SOUL.get())
				|| result.stack().is(SummyReliquary.THE_MARK.get()));
		// 启示之座（1 格）：伯列恒之星 / 终末天启 / 神性（1.5.5 起）互斥
		// 1.6.4：恶魔侧「硫磺火」也进这一格
		register("revelation_only", result -> result.stack().is(SummyReliquary.STAR_OF_BETHLEHEM.get())
				|| result.stack().is(SummyReliquary.FINAL_REVELATION.get())
				|| result.stack().is(SummyReliquary.GODHEAD.get())
				|| result.stack().is(SummyReliquary.BRIMSTONE.get())
				// 1.6.8：恶魔侧「亚巴顿」
				|| result.stack().is(SummyReliquary.ABADDON.get()));
		// 加护栏位（1.5.4 起 2 格）：救恩 / 圣光 / 神圣斗篷 / 圣心（1.5.5 起）
		// 1.6.4：补上恶魔侧「夜之幽魂」（1.6.3 转正时漏登记，导致 Curios 拒收、提示里也没有「栏位：加护」）
		register("blessing_only", result -> result.stack().is(SummyReliquary.SALVATION.get())
				|| result.stack().is(SummyReliquary.HOLY_LIGHT.get())
				|| result.stack().is(SummyReliquary.HOLY_MANTLE.get())
				|| result.stack().is(SummyReliquary.SACRED_HEART.get())
				|| result.stack().is(SummyReliquary.VENGEFUL_SPIRIT.get())
				|| result.stack().is(SummyReliquary.NIGHT_WRAITH.get())
				// 1.6.5：恶魔侧「玄秘魔眼」（由夜之幽魂升级而来）
				|| result.stack().is(SummyReliquary.OCCULT_EYE.get())
				// 1.6.7：恶魔侧「深渊领主」（邪恶 900 解锁）
				|| result.stack().is(SummyReliquary.ABYSS_LORD.get()));
		// 恶魔契约栏位（1.6.0，动态 0~1 格）：只有契约能进
		register("demon_pact_only", result -> result.stack().is(SummyReliquary.THE_PACT.get()));
	}

	private static void register(String path, Predicate<SlotResult> predicate) {
		CuriosApi.registerCurioPredicate(SummyReliquary.id(path), predicate);
	}

	/** 是否是本模组定义的栏位 */
	public static boolean isCustom(String identifier) {
		return CUSTOM.contains(identifier);
	}

	/** 栏位名的语言键（Curios 约定：curios.identifier.<栏位 id>） */
	public static String nameKey(String identifier) {
		return "curios.identifier." + identifier;
	}
}
