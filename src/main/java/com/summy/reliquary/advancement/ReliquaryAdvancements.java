package com.summy.reliquary.advancement;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.server.level.ServerPlayer;

/**
 * 进度事件常量与统一触发入口。
 *
 * <p>对应的成就 JSON 在 {@code data/summy-reliquary/advancements/} 下，判据用
 * {@code summy-reliquary:event} 触发器 + 这里的字符串。
 */
public final class ReliquaryAdvancements {
	/** 获得七罪之源（1.4.1 起「有罪之人」的判据） */
	public static final String SINS_OBTAINED = "sins_obtained";
	/**
	 * 七罪**全部已激活或已赎罪**（`SinManager.allSinsTriggered`）。
	 *
	 * <p>1.7.10 加注：事件 id 里的 "active" 容易让人误读成"必须全部处于已激活" —— 实际上
	 * `REDEEMED` 也会写激活位，所以"开一罪赎一罪"到第 7 项同样算达成；**事件 id 不能改**
	 * （它写进玩家存档的进度判据里，改名会让老存档拿不到这条成就）。
	 */
	public static final String ALL_SINS_ACTIVE = "all_sins_active";
	/** 用赎罪把七罪之源转化为美德 */
	public static final String REDEEMED_TO_VIRTUES = "redeemed_to_virtues";
	/** 同时获得灵台三件套 */
	public static final String SPIRIT_ALTAR_FULL_SET = "spirit_altar_full_set";
	/** 把伯列恒之星转化为终末天启 */
	public static final String REVELATION_ASCENDED = "revelation_ascended";
	/** 不佩戴七罪之源时击杀末影龙（无罪之人） */
	public static final String KILLED_DRAGON_SINLESS = "killed_dragon_sinless";
	/** 佩戴七罪之源且七罪全未激活时击杀末影龙（纯洁无瑕） */
	public static final String KILLED_DRAGON_FLAWLESS = "killed_dragon_flawless";
	/** 合成救恩（五饼二鱼，1.5.6） */
	public static final String SALVATION_CRAFTED = "salvation_crafted";
	/** 获得圣心（心，1.5.6） */
	public static final String HEART_OBTAINED = "heart_obtained";
	/** 获得神性（神，1.5.6） */
	public static final String GODHEAD_OBTAINED = "godhead_obtained";

	/* ===== 1.6.9：恶魔线成就（9 条） ===== */

	/** 真正的契约签署（{@code DemonPact.grant(player, true)}；自愈补发不算） */
	public static final String PACT_SIGNED = "pact_signed";
	/** 完成村民献祭 */
	public static final String SACRIFICE_DONE = "sacrifice_done";
	/** 获得撒旦圣经 */
	public static final String SATANIC_BIBLE_OBTAINED = "satanic_bible_obtained";
	/** 获得咒印 */
	public static final String THE_MARK_OBTAINED = "the_mark_obtained";
	/** 获得夜之幽魂 */
	public static final String NIGHT_WRAITH_OBTAINED = "night_wraith_obtained";
	/** 获得硫磺火 */
	public static final String BRIMSTONE_OBTAINED = "brimstone_obtained";
	/** 获得深渊领主 */
	public static final String ABYSS_LORD_OBTAINED = "abyss_lord_obtained";
	/** 获得亚巴顿 */
	public static final String ABADDON_OBTAINED = "abaddon_obtained";
	/**
	 * 把夜之幽魂升级成玄秘魔眼（合成且**通过 700 门禁**）。
	 *
	 * <p>刻意不用"持有物兜底"：指令直接给 {@code occult_eye} 不算，只有真正走门禁放行的合成才算。
	 */
	public static final String OCCULT_EYE_UPGRADED = "occult_eye_upgraded";

	/* ===== 1.6.10：天使线两条 + 创世纪 ===== */

	/** 获得圣光（「光」） */
	public static final String LIGHT_OBTAINED = "light_obtained";
	/** 获得神圣斗篷（「荫蔽」） */
	public static final String SHADE_OBTAINED = "shade_obtained";
	/**
	 * 使用创世纪（「亘古之初」）。
	 *
	 * <p>注意：这个字符串是**进度事件 id**，与 {@code GenesisItem.USED_KEY} 那个 NBT 键
	 * {@code genesis_used} 虽然同名，但分属两套系统、互不影响。
	 */
	public static final String GENESIS_USED = "genesis_used";

	/** 「近乎完美」（1.7.10 收尾）：完成除「无罪之人」外的全部本模组成就 */
	public static final String NEARLY_PERFECT = "nearly_perfect";

	/**
	 * 「近乎完美」需要完成的进度 id 列表：本模组全部成就，**去掉「无罪之人」与它自己**。
	 *
	 * <p>这里是**显式列表**（不用反射遍历）：新增本模组成就时必须同步往这里加一条，
	 * 自检会断言"列表长度 = 成就 JSON 文件数 − 2"，漏加会被抓出来。
	 */
	public static final java.util.List<String> NEARLY_PERFECT_REQUIRED = java.util.List.of(
			"reliquary", "sinner", "unforgivable", "pure", "flawless",
			"trinity", "revelation", "bread_and_fish", "heart", "god",
			"light", "shade", "genesis",
			"deal", "fresh_soul", "dark_tome", "beast_mark", "nightmare",
			"demon_flame", "evil_eye", "demon_king", "finale");

	private ReliquaryAdvancements() {
	}

	/** 触发一个进度事件（触发器还没注册好时静默跳过） */
	public static void fire(ServerPlayer player, String event) {
		if (player == null) {
			return;
		}
		SummyReliquary.EVENT_TRIGGER.trigger(player, event);
	}
}
