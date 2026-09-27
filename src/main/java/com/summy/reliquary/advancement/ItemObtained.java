package com.summy.reliquary.advancement;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * 「获得某件物品」的每秒兜底判定（1.5.6）。
 *
 * <p>与 {@code SinChallenges.tickObtained} 同套路：只要背包或饰品栏里持有目标物品就算「获得」，
 * 这样合成、拾取、指令给予都能覆盖到，不需要给每种来源单独挂钩。
 */
public final class ItemObtained {
	private ItemObtained() {
	}

	/** 服务端每秒调用一次 */
	public static void tick(ServerPlayer player) {
		if (has(player, SummyReliquary.SACRED_HEART.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.HEART_OBTAINED);
		}
		if (has(player, SummyReliquary.GODHEAD.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.GODHEAD_OBTAINED);
			// 1.6.10：创世纪的生存唯一途径 —— 获得神性时发 1 个（两条线各一次）
			com.summy.reliquary.item.GenesisItem.grantFrom(player, true);
		}
		// 1.6.10：天使线的两条新成就
		if (has(player, SummyReliquary.HOLY_LIGHT.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.LIGHT_OBTAINED);
		}
		if (has(player, SummyReliquary.HOLY_MANTLE.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.SHADE_OBTAINED);
		}
		// 1.6.9：恶魔线的「获取类」成就（合成 / 拾取 / 指令给都算）
		if (has(player, SummyReliquary.SATANIC_BIBLE.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.SATANIC_BIBLE_OBTAINED);
		}
		if (has(player, SummyReliquary.THE_MARK.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.THE_MARK_OBTAINED);
		}
		if (has(player, SummyReliquary.NIGHT_WRAITH.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.NIGHT_WRAITH_OBTAINED);
		}
		if (has(player, SummyReliquary.BRIMSTONE.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.BRIMSTONE_OBTAINED);
		}
		if (has(player, SummyReliquary.ABYSS_LORD.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.ABYSS_LORD_OBTAINED);
		}
		if (has(player, SummyReliquary.ABADDON.get())) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.ABADDON_OBTAINED);
			// 1.6.10：恶魔线的另一条线 —— 获得亚巴顿时也发 1 个创世纪
			com.summy.reliquary.item.GenesisItem.grantFrom(player, false);
		}
		// 1.7.10 收尾：「近乎完美」—— 除「无罪之人」外的全部本模组成就都已完成
		boolean nearlyPerfect = true;
		for (String id : ReliquaryAdvancements.NEARLY_PERFECT_REQUIRED) {
			if (!SinChallenges.advancementDone(player, id)) {
				nearlyPerfect = false;
				break;
			}
		}
		if (nearlyPerfect) {
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.NEARLY_PERFECT);
		}
	}

	/** 玩家身上（背包或饰品栏）是否有该物品 */
	public static boolean has(ServerPlayer player, Item item) {
		// 1.7.10 修订：按物品类型判定（忽略 NBT），并覆盖副手 / 盔甲 / 光标
		return com.summy.reliquary.util.HeldItems.holds(player, item);
	}
}
