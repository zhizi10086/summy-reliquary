package com.summy.reliquary.advancement;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.effect.RevelationTracker;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * 1.4.1 新增的成就逻辑：有罪之人的判据、两个末影龙挑战、天使标记与伯列恒星发放。
 *
 * <p>要点：
 * 有罪之人 = 获得七罪之源（首次发放时立即判定，之后每秒兜底检查）；
 * 无罪之人 = 击杀末影龙时没佩戴七罪之源且已完成有罪之人，之后无法再佩戴七罪之源；
 * 纯洁无瑕 = 击杀末影龙时佩戴七罪之源且七罪全部未激活，七罪之源就地变美德并自动完成纯洁之人；
 * 纯洁之人 = 完成时获得天使标记（灵台三件套 / 伯列恒之星 / 终末天启的门槛）。
 *
 * <p>末影龙的判定与「解放末地」同口径：击杀者是该玩家。为了兼容"早已完成解放末地"的存档，
 * 既监听原版 {@code minecraft:end/kill_dragon} 的达成事件，也在末影龙死亡时按击杀者判定一次。
 */
public final class SinChallenges {
	/** 原版「解放末地」 */
	public static final ResourceLocation VANILLA_KILL_DRAGON =
			new ResourceLocation("minecraft", "end/kill_dragon");

	/** 裁决：还没判过 */
	public static final int VERDICT_NONE = 0;
	/** 裁决：无罪之人 */
	public static final int VERDICT_SINLESS = 1;
	/** 裁决：纯洁无瑕 */
	public static final int VERDICT_FLAWLESS = 2;

	private SinChallenges() {
	}

	// ==================== 有罪之人：获得即达成 ====================

	/** 每秒兜底：只要背包 / 饰品栏里有七罪之源就算「获得」 */
	public static void tickObtained(ServerPlayer player) {
		if (!hasSourceOfSins(player)) {
			return;
		}
		ReliquaryAdvancements.fire(player, ReliquaryAdvancements.SINS_OBTAINED);
	}

	/** 玩家身上（背包或饰品栏）是否有七罪之源 */
	public static boolean hasSourceOfSins(ServerPlayer player) {
		// 1.7.10 修订：按物品类型判定（忽略 NBT），并覆盖副手 / 盔甲 / 光标
		return com.summy.reliquary.util.HeldItems.holds(player, SummyReliquary.SOURCE_OF_SINS.get());
	}

	// ==================== 末影龙挑战 ====================

	/** 末影龙被该玩家击杀时调用（与解放末地同口径） */
	public static void onDragonSlainBy(ServerPlayer player) {
		evaluateDragon(player);
	}

	/**
	 * 判定两条末影龙挑战。
	 *
	 * <p>两条互斥：佩戴与否。纯洁无瑕会顺带把七罪之源转化为美德并触发纯洁之人。
	 */
	public static void evaluateDragon(ServerPlayer player) {
		// 1.7.1：**恶魔线排斥** —— 当前持恶魔标记、或曾签过契约的玩家不参与这两条挑战：
		// 签约会把七罪重置成"未激活"，若不拦截，恶魔一杀龙就会把七罪之源换成美德（永远做不出撒旦圣经）；
		// 同时避免"无罪之人"的 `sin_renounced` 把七罪之源锁死。清掉 demon_sealed 的唯一途径是创世纪。
		if (PlayerFlags.isDemon(player) || PlayerFlags.isDemonSealed(player)) {
			return;
		}
		// 一次性裁决：击杀末影龙会先后触发「我们的死亡事件」与「原版解放末地达成」两次判定，
		// 只认第一次；否则第二次会看到"已经不佩戴七罪之源"，错误地再发一个无罪之人。
		if (PlayerFlags.dragonVerdict(player) != VERDICT_NONE) {
			return;
		}
		boolean wearing = CurioHelper.wears(player, SummyReliquary.SOURCE_OF_SINS.get());
		if (!wearing) {
			// 无罪之人：要求已经获得过七罪之源（即已完成有罪之人）
			if (advancementDone(player, "sinner")) {
				PlayerFlags.setDragonVerdict(player, VERDICT_SINLESS);
				ReliquaryAdvancements.fire(player, ReliquaryAdvancements.KILLED_DRAGON_SINLESS);
			}
			return;
		}
		// 纯洁无瑕：佩戴且七罪全部未激活
		if (SinManager.mask(player) != 0 || SinManager.redeemedMask(player) != 0) {
			return;
		}
		// 1.7.10 收尾：佩戴率判据（参考神秘遗物的 IPlaytimeCounter —— 分母 = 戴着 + 没戴）
		// 不通过时同样"不写裁决"，本次击杀不算数、以后可以重新召唤末影龙再试。
		int wornSeconds = PlayerFlags.sinWornSeconds(player);
		int unwornSeconds = PlayerFlags.sinUnwornSeconds(player);
		int trackedSeconds = wornSeconds + unwornSeconds;
		if (trackedSeconds > 0
				&& (long) wornSeconds * 100 < (long) trackedSeconds * ReliquaryConfig.flawlessMinWearingPercent()) {
			return;
		}
		PlayerFlags.setDragonVerdict(player, VERDICT_FLAWLESS);
		ReliquaryAdvancements.fire(player, ReliquaryAdvancements.KILLED_DRAGON_FLAWLESS);
		// 无瑕：七罪全部视为已赎罪（这样美德才能真正继承"赎罪后"的增益）
		markAllRedeemed(player);
		// 七罪之源就地变成美德（只有还在魂印栏里才转换，避免重复触发时重复发放）
		if (convertSourceToVirtues(player)) {
			player.displayClientMessage(Component.translatable("message.summy-reliquary.sin.flawless.converted"), true);
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.REDEEMED_TO_VIRTUES);
		}
	}

	/** 魂印栏里的七罪之源（栏位上下文 + 该玩家的饰品处理器）；找不到时为空 */
	private record SealSource(ICuriosItemHandler handler, SlotContext context) {
	}

	/**
	 * 1.7.4：找出"魂印栏里的七罪之源"。
	 *
	 * <p><b>纯读、无副作用</b> —— 自愈链要在 {@link #markAllRedeemed} **之前**用它把门：
	 * 否则"戴撒旦圣经（魂印栏不是七罪之源）"的玩家会先被写成"七罪全赎罪"，
	 * 紧接着转化失败，留下"圣经 + 全赎罪"的残留态（那样圣经就会按赎罪后档结算）。
	 * {@link #convertSourceToVirtues} 复用同一份判定，保证两处口径不会漂移。
	 */
	private static SealSource findSealSource(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return null;
		}
		var found = handler.findFirstCurio(SummyReliquary.SOURCE_OF_SINS.get());
		if (found.isEmpty()) {
			return null;
		}
		SlotContext context = found.get().slotContext();
		if (!ReliquarySlots.SOUL_SEAL.equals(context.identifier())) {
			return null;
		}
		return new SealSource(handler, context);
	}

	/** 把魂印栏里的七罪之源就地替换为美德；返回是否真的转换了 */
	private static boolean convertSourceToVirtues(ServerPlayer player) {
		SealSource seal = findSealSource(player);
		if (seal == null) {
			return false;
		}
		SlotContext context = seal.context();
		seal.handler().setEquippedCurio(context.identifier(), context.index(),
				new ItemStack(SummyReliquary.VIRTUES.get()));
		// 1.6.10：天使标记由**这个动作**驱动（不再看"纯洁之人"成就是否已完成）
		grantAngelForConversion(player);
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
		return true;
	}

	/**
	 * 完成"七罪之源 → 美德"转化时授予天使标记（1.6.10 起**动作驱动**，与成就无关）。
	 *
	 * <p>两个转化入口都调它：右击「赎罪」({@code SinRedemption}) 与「纯洁无瑕」的自动转化
	 * （{@link #evaluateDragon}）。守则与旧版一致：已有标记不动；**曾签过契约 / 当前持恶魔标记**
	 * 的不自动给（想回天使只能走「痛悔短祷」）。
	 */
	public static void grantAngelForConversion(ServerPlayer player) {
		if (player == null) {
			return;
		}
		// 1.7.9：转化成功（"七罪之源 → 美德"）就把圣光短矛交给玩家 —— 已有任意一把长矛则跳过，
		// 但"获得过"的标记照旧置位（它只用于防丢失配方的前置，且**不随创世纪清除**）。
		grantHolySpear(player);
		if (PlayerFlags.hasAngel(player)
				|| PlayerFlags.isDemon(player) || PlayerFlags.isDemonSealed(player)) {
			return;
		}
		PlayerFlags.setAngel(player, true);
		RevelationTracker.sync(player);
		player.displayClientMessage(Component.translatable("message.summy-reliquary.angel.gained"), true);
	}

	/**
	 * 1.7.9：发放圣光短矛（"七罪之源 → 美德"的获取途径）。
	 *
	 * <p>身上（背包 + 饰品栏）已经有任意一把长矛时只置位"获得过"、不重复发；背包满则掉在脚下
	 * 并加上掉落保护（和创世纪 / 匕首同款，防仙人掌与自然消失）。
	 */
	private static void grantHolySpear(ServerPlayer player) {
		PlayerFlags.setSpearObtained(player, true);
		if (com.summy.reliquary.effect.SpearRecovery.holdsSpear(player)) {
			return;
		}
		ItemStack spear = new ItemStack(SummyReliquary.HOLY_SPEAR.get());
		if (!player.getInventory().add(spear)) {
			com.summy.reliquary.item.GenesisItem.protectDrop(player.drop(spear, false));
		}
	}

	// ==================== 成就完成时的标记 ====================

	/**
	 * 每秒 / 登录时自愈：
	 * <ul>
	 *     <li>「纯洁之人」已完成却没有天使标记 → **只给老存档补一次**（1.6.10 起改成由
	 *     "七罪之源 → 美德"这个动作驱动，见 {@link #grantAngelForConversion}）；</li>
	 *     <li>「无罪之人」已完成却没有「放弃一切」标记 → 补标记；</li>
	 *     <li>裁决是「纯洁无瑕」但七罪之源还没被换掉 → 补做转化并触发纯洁之人。</li>
	 * </ul>
	 */
	public static void selfHeal(ServerPlayer player) {
		// 1.7.10 收尾：七罪之源的佩戴计时（「纯洁无瑕」判据）
		trackSinWear(player);
		// 1.6.10：天使标记改成**动作驱动**（"七罪之源 → 美德"成功那一刻，见 grantAngelForConversion），
		// 这里只保留"老存档一次性迁移"：1.6.10 之前的老档可能已完成纯洁之人却没有标记，补一次即可。
		// 迁移位一旦置位就永不再补 —— 于是**创世纪重置清掉天使标记后不会被自愈补回**（与一周目一致）。
		if (!PlayerFlags.isAngelMigrated(player)) {
			PlayerFlags.setAngelMigrated(player, true);
			// 1.5.10：**曾签过恶魔契约就不再自动补天使标记**（想回天使只能走「痛悔短祷」或走一遍转化）。
			if (advancementDone(player, "pure") && !PlayerFlags.hasAngel(player)
					&& !PlayerFlags.isDemonSealed(player)) {
				grantAngelForConversion(player);
			}
		}
		if (advancementDone(player, "sinless") && !PlayerFlags.isSinRenounced(player)) {
			PlayerFlags.setSinRenounced(player, true);
			RevelationTracker.sync(player);
		}
		if (PlayerFlags.dragonVerdict(player) == VERDICT_FLAWLESS
				&& SinManager.mask(player) == 0 && SinManager.redeemedMask(player) == 0
				// 1.7.4：先"纯读"确认魂印栏里确实是七罪之源，再动手改七罪状态。
				// 少了这道门，"戴撒旦圣经 + 七罪全未激活"会被写成全赎罪后再转化失败（仅 OP 可达的残留态）。
				&& findSealSource(player) != null
				&& markAllRedeemed(player) && convertSourceToVirtues(player)) {
			// 幂等：上面的 findSealSource 已经保证"魂印栏里确实还是七罪之源"
			ReliquaryAdvancements.fire(player, ReliquaryAdvancements.REDEEMED_TO_VIRTUES);
		}
	}

	/** 把七罪全部置为已赎罪；返回是否真的发生了变化（幂等，便于每秒自愈里调用） */
	private static boolean markAllRedeemed(ServerPlayer player) {
		int all = (1 << com.summy.reliquary.sin.Sin.values().length) - 1;
		if ((SinManager.redeemedMask(player) & all) == all) {
			return false;
		}
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.REDEEMED);
		}
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
		return true;
	}

	/** 清空末影龙裁决与「放弃一切」标记（管理 / 测试用；进度本身要用 /advancement revoke） */
	public static void resetVerdict(ServerPlayer player) {
		PlayerFlags.setDragonVerdict(player, VERDICT_NONE);
		PlayerFlags.setSinRenounced(player, false);
		RevelationTracker.sync(player);
	}

	/** 进度达成时调用（Forge 的 AdvancementEvent.AdvancementEarnEvent） */
	public static void onAdvancementEarned(ServerPlayer player, Advancement advancement) {
		ResourceLocation id = advancement.getId();
		if (VANILLA_KILL_DRAGON.equals(id)) {
			evaluateDragon(player);
			return;
		}
		if (!SummyReliquary.NAMESPACE.equals(id.getNamespace())) {
			return;
		}
		switch (id.getPath()) {
			// 1.6.10：「纯洁之人 → 天使标记」不再挂在进度上（改成"七罪之源 → 美德"这个动作驱动，
			// 见 grantAngelForConversion）；「罪无可赦 → 五芒星」也不再挂在进度上（改成"七罪全触发"条件驱动，
			// 见 SinManager.setState 里的 Pentagram.grantIfEarned）。
			// 保留「无罪之人 → 放弃一切」：本轮按要求不动。
			case "sinless" -> {
				// 无罪之人：之后无法再佩戴七罪之源
				PlayerFlags.setSinRenounced(player, true);
				RevelationTracker.sync(player);
			}
			default -> {
			}
		}
	}

	// ==================== 伯列恒之星：三位一体自动发放 ====================

	/** 完成三位一体时发放一个伯列恒之星（只发一次） */
	public static void grantStarOnTrinity(ServerPlayer player) {
		if (PlayerFlags.isStarGranted(player)) {
			return;
		}
		PlayerFlags.setStarGranted(player, true);
		ItemStack star = new ItemStack(SummyReliquary.STAR_OF_BETHLEHEM.get());
		if (!player.getInventory().add(star)) {
			player.drop(star, false);
		}
		player.displayClientMessage(Component.translatable("message.summy-reliquary.star.granted"), true);
	}

	/**
	 * 每秒兜底（1.6.10）：**真三件套同时佩戴**（与「三位一体」同一条件）且还没发过 → 补发伯列恒之星。
	 *
	 * <p>与 {@link #grantStarOnTrinity} 用的是同一个判定（{@code SpiritAltarSet.isFullSet}）与同一个
	 * 去重标记，所以"重置后不会提前发、重新戴齐三件套时会补发"。咒印不算三件套（恶魔线不该拿天使线奖励）。
	 */
	public static void tickStar(ServerPlayer player) {
		if (PlayerFlags.isStarGranted(player)) {
			return;
		}
		if (com.summy.reliquary.effect.SpiritAltarSet.isFullSet(player)) {
			grantStarOnTrinity(player);
		}
	}

	// ==================== 工具 ====================

	/** 玩家个人游戏时长（秒）——用 {@code Stats.PLAY_TIME}（tick）换算，离线不累计 */
	private static int playSeconds(ServerPlayer player) {
		// PLAY_TIME 是 ResourceLocation，要先经 Stats.CUSTOM 转成 Stat<?> 才能取计数
		return player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME)) / 20;
	}

	/**
	 * 每秒一次：统计七罪之源的佩戴 / 未佩戴秒数（「纯洁无瑕」的佩戴率判据）。
	 *
	 * <p>参考神秘遗物的 {@code IPlaytimeCounter}：分母＝戴着 + 没戴，两个计数器二选一自增。
	 * 这里的三条差别：① 只在"已完成有罪之人"（= 已获得过七罪之源）之后才开始计；
	 * ② 开局宽限期（默认 120 秒）内未佩戴不计、佩戴照常计；③ 首次建立记录时以当前时刻为锚点，
	 * 老存档不会被模组安装前的时间惩罚。创世纪重置会清空这三个键。
	 */
	private static void trackSinWear(ServerPlayer player) {
		if (!advancementDone(player, "sinner")) {
			return;
		}
		int now = playSeconds(player);
		int start = PlayerFlags.sinTrackingStartSeconds(player);
		if (start <= 0) {
			// 存 now + 1（0 = 还没建立记录），避免开局 now = 0 时被反复当成"未建立"
			start = now + 1;
			PlayerFlags.setSinTrackingStartSeconds(player, start);
		}
		if (CurioHelper.wears(player, SummyReliquary.SOURCE_OF_SINS.get())) {
			PlayerFlags.setSinWornSeconds(player, PlayerFlags.sinWornSeconds(player) + 1);
		} else if (now - (start - 1) >= ReliquaryConfig.flawlessUnwornGraceSeconds()) {
			PlayerFlags.setSinUnwornSeconds(player, PlayerFlags.sinUnwornSeconds(player) + 1);
		}
	}

	/** 该玩家是否已经完成某个本模组进度 */
	public static boolean advancementDone(ServerPlayer player, String path) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return false;
		}
		Advancement advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(path));
		return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
	}
}
