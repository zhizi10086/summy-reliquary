package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.attribute.ReliquaryAttributes;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本模组自己管理的属性修正。
 *
 * <p>为什么不用 Curios 的 {@code getAttributeModifiers}：套装的「+10 ↔ +20」与光环的「×0.5 / ×2」
 * 会随佩戴组合变化，而 Curios 只在被装卸的那一格上重算修正；这里改成自己每 20 tick 重算一次，
 * 保证任何组合变化都会在一秒内生效，也保证卸下时会立刻移除。
 * 魂心（+3）是固定值，走 Curios 自己的机制即可。
 */
public final class AttributeManager {
	/** 本模组使用的固定修饰符 UUID（同一属性上可用多条，靠 UUID 区分） */
	private static final UUID BODY_HEALTH = id(1);
	private static final UUID HALO_HEALTH = id(2);
	private static final UUID HALO_DAMAGE = id(3);
	private static final UUID HALO_ATTACK_SPEED = id(4);
	private static final UUID HALO_ARMOR = id(5);
	private static final UUID HALO_TOUGHNESS = id(6);
	private static final UUID HALO_MOVEMENT = id(7);
	private static final UUID STAR_ATTACK_SPEED = id(8);
	private static final UUID SOUL_HEARTS = id(9);
	/** 攻击速度上限用的负修正（动态校准） */
	private static final UUID ATTACK_SPEED_CAP = id(10);
	/** 圣心：生命 / 护甲 / 韧性 / 攻速 / 移速 */
	private static final UUID SACRED_HEART_HEALTH = id(11);
	private static final UUID SACRED_HEART_ARMOR = id(12);
	private static final UUID SACRED_HEART_TOUGHNESS = id(13);
	private static final UUID SACRED_HEART_ATTACK_SPEED = id(14);
	private static final UUID SACRED_HEART_MOVEMENT = id(15);
	/** 五芒星（1.5.8）：近战伤害 +1 */
	private static final UUID PENTAGRAM_DAMAGE = id(16);
	/** 恶魔契约（1.6.0）：攻速（16% + 邪恶度） */
	private static final UUID PACT_ATTACK_SPEED = id(17);
	/** 恶魔契约（1.6.0）：生命（每条诅咒 2%） */
	private static final UUID PACT_HEALTH = id(18);
	/** 恶魔契约（1.6.0）：移动速度（每条诅咒 2%） */
	private static final UUID PACT_SPEED = id(19);
	/** 仪式法袍：攻击力（加法，1.6.1） */
	private static final UUID ROBE_DAMAGE = id(20);
	/**
	 * 夜之幽魂：移动速度（百分比，1.6.3）。
	 *
	 * <p>**必须避开七罪 / 七德的号段**：`SinEffects` 用 21~24（含怠惰的移速）、`VirtuesEffects` 用 31~33。
	 * 早期版本这里误用了 `id(21)`，与怠惰的移速修饰符撞 UUID（同一属性、同一 UUID），
	 * 结果怠惰每 tick 的"0 值回收"会把夜之幽魂的 +20% 一并删掉 —— 移速看起来完全没生效。
	 */
	private static final UUID NIGHT_WRAITH_SPEED = id(41);
	/** 恶魔王冠（1.7.1）：移速 / 攻速 / 近战伤害 / 生命 / 护甲 / 韧性（号段 51~56，避开七罪 21~24 与七德 31~33） */
	private static final UUID DEVIL_CROWN_SPEED = id(51);
	private static final UUID DEVIL_CROWN_ATTACK_SPEED = id(52);
	private static final UUID DEVIL_CROWN_DAMAGE = id(53);
	private static final UUID DEVIL_CROWN_HEALTH = id(54);
	private static final UUID DEVIL_CROWN_ARMOR = id(55);
	private static final UUID DEVIL_CROWN_TOUGHNESS = id(56);
	/** 天使线长矛（1.7.9）：近战攻击距离（Forge 的 entity_reach，加法）—— 圣光短矛 +0.5 / 炽天使之枪 +1.0 */
	private static final UUID SPEAR_REACH = id(62);
	/** 上一次打印过的属性摘要：签名不变就不重复刷日志 */
	private static final Map<UUID, String> LAST_SUMMARY = new HashMap<>();
	/** 由我们授予过飞行的玩家（卸下终末天启时要收回） */
	private static final Set<UUID> GRANTED_FLIGHT = new HashSet<>();
	/**
	 * 创造飞行能力的「最近一次推送指纹」（1.8.5 第二轮补修）。
	 *
	 * <p>值 = {@code 维度#游戏模式#(游戏 tick / }{@link #FLIGHT_RESYNC_TICKS_PER_STAMP}{@code )}。
	 * 指纹变了就重推一次能力包。
	 *
	 * <p>为什么需要它（实机根因）：客户端在**换维度 / 重生**时会重建 LocalPlayer，飞行能力被重置成默认值；
	 * 而服务端这边的 {@code abilities.mayfly} 一直是 true —— 旧实现只在"服务端值变化"时才补包，
	 * 于是客户端永远拿不回飞行。实测：神性死亡拦截把玩家从**下界送回主世界**后创造飞行失效，
	 * 摘下神性再戴上（强制一次值变化）才恢复。
	 */
	private static final Map<UUID, String> FLIGHT_STAMP = new HashMap<>();
	/**
	 * 飞行能力的「维度 / 游戏模式」上下文（1.8.5 第三轮）：只在它**变化**时打一条 INFO，
	 * 用来在实机日志里直接确认"换维度后补推"生效（5 秒心跳那种周期性重推不打印，避免刷屏）。
	 */
	private static final Map<UUID, String> FLIGHT_CONTEXT = new HashMap<>();
	/** 指纹里的时间分量：每这么多 tick 变一次 = 5 秒一道心跳，兜住其它会重置客户端能力的场景 */
	private static final long FLIGHT_RESYNC_TICKS_PER_STAMP = 100L;
	/** 自检用：累计推给客户端的飞行能力包数量 */
	private static int flightSyncCount;

	private AttributeManager() {
	}

	private static UUID id(int index) {
		return UUID.fromString(String.format("6f5c1a2e-0000-4a0b-9d1e-%012d", index));
	}

	/** 服务端每秒调用一次 */
	public static void tickServer(MinecraftServer server) {
		// 每 10 tick 重算一次（属性变化 + 攻速上限校准）
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			apply(player);
		}
	}

	/** 重算某个玩家的所有本模组属性修正 */
	public static void apply(LivingEntity entity) {
		// 肉体：最大生命 +10（套装效果改成了减伤，由 SpiritAltarSet 处理，不再加生命）
		double bodyHealth = 0.0D;
		// 1.6.2：咒印继承灵台套装，所以戴它同样给这 +10 生命
		if (CurioHelper.wears(entity, SummyReliquary.THE_BODY.get())
				|| com.summy.reliquary.effect.SatanicMark.wears(entity)) {
			bodyHealth = ReliquaryConfig.bodyHealth();
		}
		apply(entity, Attributes.MAX_HEALTH, BODY_HEALTH, "spirit_altar_body", bodyHealth,
				AttributeModifier.Operation.ADDITION);

		// 光环：整套属性受七罪/美德倍率影响
		boolean haloWorn = CurioHelper.wears(entity, SummyReliquary.THE_HALO.get());
		double factor = haloWorn ? HaloState.multiplier(entity) : 0.0D;
		apply(entity, Attributes.MAX_HEALTH, HALO_HEALTH, "the_halo_health",
				ReliquaryConfig.haloMaxHealth() * factor, AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ATTACK_DAMAGE, HALO_DAMAGE, "the_halo_damage",
				ReliquaryConfig.haloAttackDamage() * factor, AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ATTACK_SPEED, HALO_ATTACK_SPEED, "the_halo_attack_speed",
				ReliquaryConfig.haloAttackSpeed() * factor, AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ARMOR, HALO_ARMOR, "the_halo_armor",
				ReliquaryConfig.haloArmor() * factor, AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ARMOR_TOUGHNESS, HALO_TOUGHNESS, "the_halo_toughness",
				ReliquaryConfig.haloArmorToughness() * factor, AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.MOVEMENT_SPEED, HALO_MOVEMENT, "the_halo_movement",
				ReliquaryConfig.haloMovementPercent() / 100.0D * factor,
				AttributeModifier.Operation.MULTIPLY_TOTAL);

		// 恶魔王冠（1.7.1）：需佩戴撒旦圣经才生效；固定值，不受光环那套倍率影响
		boolean crownActive = DevilCrown.active(entity);
		apply(entity, Attributes.MOVEMENT_SPEED, DEVIL_CROWN_SPEED, "devil_crown_movement",
				crownActive ? ReliquaryConfig.devilCrownMovementPercent() / 100.0D : 0.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);
		apply(entity, Attributes.ATTACK_SPEED, DEVIL_CROWN_ATTACK_SPEED, "devil_crown_attack_speed",
				crownActive ? ReliquaryConfig.devilCrownAttackSpeed() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ATTACK_DAMAGE, DEVIL_CROWN_DAMAGE, "devil_crown_damage",
				crownActive ? ReliquaryConfig.devilCrownMeleeDamage() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.MAX_HEALTH, DEVIL_CROWN_HEALTH, "devil_crown_health",
				crownActive ? ReliquaryConfig.devilCrownMaxHealth() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ARMOR, DEVIL_CROWN_ARMOR, "devil_crown_armor",
				crownActive ? ReliquaryConfig.devilCrownArmor() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ARMOR_TOUGHNESS, DEVIL_CROWN_TOUGHNESS, "devil_crown_toughness",
				crownActive ? ReliquaryConfig.devilCrownArmorToughness() : 0.0D,
				AttributeModifier.Operation.ADDITION);

		// 天使线长矛（1.7.9）：手持且达标才有"攻击距离 +0.5 / +1.0"（切走立刻失效）
		apply(entity, net.minecraftforge.common.ForgeMod.ENTITY_REACH.get(), SPEAR_REACH, "spear_reach",
				WeaponGates.reachBonus(entity), AttributeModifier.Operation.ADDITION);

		// 圣心：生命 / 护甲 / 韧性 / 攻速 / 移速（加护饰品，1.5.5）
		boolean sacredHeartWorn = CurioHelper.wears(entity, SummyReliquary.SACRED_HEART.get());
		apply(entity, Attributes.MAX_HEALTH, SACRED_HEART_HEALTH, "sacred_heart_health",
				sacredHeartWorn ? ReliquaryConfig.sacredHeartMaxHealth() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ARMOR, SACRED_HEART_ARMOR, "sacred_heart_armor",
				sacredHeartWorn ? ReliquaryConfig.sacredHeartArmor() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ARMOR_TOUGHNESS, SACRED_HEART_TOUGHNESS, "sacred_heart_toughness",
				sacredHeartWorn ? ReliquaryConfig.sacredHeartToughness() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.ATTACK_SPEED, SACRED_HEART_ATTACK_SPEED, "sacred_heart_attack_speed",
				sacredHeartWorn ? ReliquaryConfig.sacredHeartAttackSpeed() : 0.0D,
				AttributeModifier.Operation.ADDITION);
		apply(entity, Attributes.MOVEMENT_SPEED, SACRED_HEART_MOVEMENT, "sacred_heart_movement",
				sacredHeartWorn ? ReliquaryConfig.sacredHeartMovementPercent() / 100.0D : 0.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);

		// 五芒星（护符栏，1.5.8）：近战伤害 +1（卸下即回收）
		boolean pentagramWorn = CurioHelper.wears(entity, SummyReliquary.PENTAGRAM.get());
		apply(entity, Attributes.ATTACK_DAMAGE, PENTAGRAM_DAMAGE, "pentagram_attack_damage",
				pentagramWorn ? ReliquaryConfig.pentagramAttackDamage() : 0.0D,
				AttributeModifier.Operation.ADDITION);

		// 恶魔契约（1.6.0）：攻速 = 16% + 邪恶度加成；生命 / 移速 = 每条生效诅咒 2%
		boolean pactActive = DemonPact.active(entity);
		apply(entity, Attributes.ATTACK_SPEED, PACT_ATTACK_SPEED, "demon_pact_attack_speed",
				pactActive ? DemonPact.attackSpeedBonusPercent(entity) / 100.0D : 0.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);
		apply(entity, Attributes.MAX_HEALTH, PACT_HEALTH, "demon_pact_health",
				pactActive ? DemonPact.healthBonusPercent(entity) / 100.0D : 0.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);
		apply(entity, Attributes.MOVEMENT_SPEED, PACT_SPEED, "demon_pact_speed",
				pactActive ? DemonPact.speedBonusPercent(entity) / 100.0D : 0.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);

		// 仪式法袍（1.6.1）：固定 +2 攻击力（与五芒星同款加法），未佩戴时回收
		boolean robeWorn = ReliquaryConfig.enableRobe()
				&& CurioHelper.wears(entity, SummyReliquary.CEREMONIAL_ROBES.get());
		apply(entity, Attributes.ATTACK_DAMAGE, ROBE_DAMAGE, "ceremonial_robes_attack_damage",
				robeWorn ? ReliquaryConfig.robeAttackDamage() : 0.0D,
				AttributeModifier.Operation.ADDITION);

		// 夜之幽魂（1.6.3）/ 玄秘魔眼（1.6.5，继承夜之幽魂）：移动速度 +20%（百分比，卸下回收；同在也只加一次）
		boolean wraithWorn = CurioHelper.wears(entity, SummyReliquary.NIGHT_WRAITH.get())
				|| CurioHelper.wears(entity, SummyReliquary.OCCULT_EYE.get());
		apply(entity, Attributes.MOVEMENT_SPEED, NIGHT_WRAITH_SPEED, "night_wraith_movement",
				wraithWorn ? ReliquaryConfig.nightWraithMovementPercent() / 100.0D : 0.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);

		// 伯列恒之星 / 终末天启 / 神性：攻击速度 +20%
		double starSpeed = (CurioHelper.wears(entity, SummyReliquary.STAR_OF_BETHLEHEM.get())
				|| CurioHelper.wears(entity, SummyReliquary.FINAL_REVELATION.get())
				|| CurioHelper.wears(entity, SummyReliquary.GODHEAD.get()))
				? ReliquaryConfig.starAttackSpeedPercent() / 100.0D
				: 0.0D;
		apply(entity, Attributes.ATTACK_SPEED, STAR_ATTACK_SPEED, "star_of_bethlehem_attack_speed",
				starSpeed, AttributeModifier.Operation.MULTIPLY_TOTAL);

		// 魂心：灵魂 +3、终末天启/神性 +2（可叠加；独立池由 SoulShield 维护）；
		// 1.6.2：戴咒印时"灵魂的魂心"已经转化成黑心，所以不算这一份
		double soulHearts = 0.0D;
		if (CurioHelper.wears(entity, SummyReliquary.THE_SOUL.get())
				&& !com.summy.reliquary.effect.SatanicMark.wears(entity)) {
			soulHearts += ReliquaryConfig.soulHearts();
		}
		if (CurioHelper.wears(entity, SummyReliquary.FINAL_REVELATION.get())) {
			soulHearts += ReliquaryConfig.finalSoulHearts();
		}
		// 神性：继承终末天启的魂心
		if (CurioHelper.wears(entity, SummyReliquary.GODHEAD.get())) {
			soulHearts += ReliquaryConfig.finalSoulHearts();
		}
		apply(entity, ReliquaryAttributes.SOUL_HEARTS.get(), SOUL_HEARTS, "soul_hearts",
				soulHearts, AttributeModifier.Operation.ADDITION);

		// 七罪：怠惰的减速、色欲的护甲削弱
		com.summy.reliquary.sin.SinEffects.applyAttributes(entity);
		// 七德：勤勉的三类速度 + 慷慨的移速叠层
		com.summy.reliquary.effect.VirtuesEffects.applyAttributes(entity);

		// 攻击速度上限：把属性值整体压回配置上限（剑等低于上限的武器不受影响）
		applyAttackSpeedCap(entity);

		// 最大生命下降后立刻把当前血量夹回上限，避免"血条下降延迟"
		if (entity.getHealth() > entity.getMaxHealth()) {
			entity.setHealth(entity.getMaxHealth());
		}

		// 终末天启：创造模式飞行（速度减半）
		applyFlight(entity);

		// 进度：同时获得灵台三件套（"三位一体"）
		if (entity instanceof ServerPlayer player && SpiritAltarSet.isFullSet(player)) {
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
					com.summy.reliquary.advancement.ReliquaryAdvancements.SPIRIT_ALTAR_FULL_SET);
			// 三位一体是伯列恒之星唯一的获得途径（内部按 star_granted 标记只发一次）
			com.summy.reliquary.advancement.SinChallenges.grantStarOnTrinity(player);
		}

		// 数值变化时打一条 INFO，便于实机确认肉体 / 灵魂等是否真的生效
		logIfChanged(entity, bodyHealth, haloWorn, factor, soulHearts);
	}

	/**
	 * 供其它模块（七罪效果）复用的属性修饰符写入：值没变就不重复写。
	 *
	 * <p>用固定的 UUID 区分同一条属性上的多个来源，值设回 0 也不会残留。
	 */
	public static void applyModifier(LivingEntity entity, Attribute attribute, UUID uuid, String name,
			double value, AttributeModifier.Operation operation) {
		apply(entity, attribute, uuid, name, value, operation);
	}

	/**
	 * 终末天启 / 神性的创造飞行：佩戴期间允许飞行并把飞行速度设为配置倍率（默认 0.5 = 速度减半）；
	 * **神性**（1.5.5）是"不减速"的创造飞行 —— 即使生存模式也用原版 0.05。
	 * 卸下时若玩家本身不能飞（非创造 / 旁观）则收回飞行能力，并把速度恢复为原版 0.05。
	 */
	private static void applyFlight(LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		UUID id = player.getUUID();
		boolean worn = CurioHelper.wears(player, SummyReliquary.FINAL_REVELATION.get())
				|| CurioHelper.wears(player, SummyReliquary.GODHEAD.get())
				// 1.6.3：夜之幽魂同样授予创造飞行（半速），与天启共用这一套逻辑、不叠加
				|| CurioHelper.wears(player, SummyReliquary.NIGHT_WRAITH.get())
				// 1.6.5：玄秘魔眼继承夜之幽魂的飞行
				|| CurioHelper.wears(player, SummyReliquary.OCCULT_EYE.get())
				// 1.6.8：亚巴顿也授予飞行（不减速，见下面的 noSlowdown）
				|| CurioHelper.wears(player, SummyReliquary.ABADDON.get());
		var abilities = player.getAbilities();
		boolean nativeFlight = player.isCreative() || player.isSpectator();
		// 神性 / 亚巴顿：创造飞行且**不减速**（按需求，生存模式也是原版速度）
		boolean noSlowdown = com.summy.reliquary.effect.Godhead.active(player)
				|| com.summy.reliquary.effect.Abaddon.wears(player);
		float defaultSpeed = 0.05F;
		float desired = (float) (defaultSpeed * ReliquaryConfig.flightSpeedMultiplier());

		if (worn) {
			// 真正的创造 / 旁观、或佩戴神性时保持原版飞行速度（0.05）；半速只作用于"我们授予的飞行"
			float target = (nativeFlight || noSlowdown) ? defaultSpeed : desired;
			// 1.8.5 第二轮补修：除了"服务端值变化"，维度 / 游戏模式变化（客户端会重建 LocalPlayer，
			// 能力被重置）与 5 秒心跳也各算一次"需要重推"，否则客户端会永远卡在 mayfly=false。
			// 安全性：能力包里的 flying 取自服务端，而服务端在收到 ServerboundPlayerAbilitiesPacket 时
			// 就已经同步过客户端的 flying（`flying = 包里的 flying && mayfly`），所以补包不会把人从飞行中踢下来。
			String stamp = flightStamp(player);
			boolean stampChanged = !stamp.equals(FLIGHT_STAMP.get(id));
			FLIGHT_STAMP.put(id, stamp);
			// 维度 / 游戏模式变化 → 打一条 INFO（客户端这时会重建 LocalPlayer、能力被重置，
			// 正是最需要补推的时刻；首次授予不留旧上下文，所以不会误报）
			String context = flightContext(player);
			String previousContext = FLIGHT_CONTEXT.put(id, context);
			if (previousContext != null && !previousContext.equals(context)) {
				SummyReliquary.LOGGER.info(
						"[Summy Reliquary] 创造飞行补推：{} → {}（维度 / 游戏模式变化），已重推能力包",
						previousContext, context);
			}
			boolean changed = stampChanged || !abilities.mayfly
					|| Math.abs(abilities.getFlyingSpeed() - target) > 1.0E-4F;
			abilities.mayfly = true;
			abilities.setFlyingSpeed(target);
			GRANTED_FLIGHT.add(id);
			if (changed) {
				player.onUpdateAbilities();
				flightSyncCount++;
			}
			return;
		}
		if (GRANTED_FLIGHT.remove(id)) {
			if (!nativeFlight) {
				abilities.mayfly = false;
				abilities.flying = false;
			}
			abilities.setFlyingSpeed(defaultSpeed);
			player.onUpdateAbilities();
			flightSyncCount++;
			FLIGHT_STAMP.remove(id);
		}
	}

	/** 飞行能力的「维度 / 游戏模式」上下文（诊断日志与指纹共用） */
	private static String flightContext(LivingEntity entity) {
		String dimension = entity.level().dimension().location().toString();
		String mode = entity instanceof ServerPlayer player ? player.gameMode.getGameModeForPlayer().name() : "-";
		return dimension + "#" + mode;
	}

	/** 飞行能力的「推送指纹」：上下文 + 每 5 秒变一次的时间片 */
	private static String flightStamp(LivingEntity entity) {
		return flightContext(entity) + "#" + (entity.level().getGameTime() / FLIGHT_RESYNC_TICKS_PER_STAMP);
	}

	/** 自检用：累计推送过多少次飞行能力包 */
	public static int flightSyncCountForTest() {
		return flightSyncCount;
	}

	/**
	 * 自检用：把"最近一次推送指纹"清掉 —— 等价于"客户端重建了 LocalPlayer、我们的记号却还在"，
	 * 下一次 {@code apply} 必须重推一次能力包。
	 */
	public static void markFlightStampStaleForTest(LivingEntity entity) {
		if (entity != null) {
			FLIGHT_STAMP.remove(entity.getUUID());
		}
	}

	/** 玩家退出时清掉日志签名缓存 */
	public static void forget(LivingEntity entity) {
		LAST_SUMMARY.remove(entity.getUUID());
		GRANTED_FLIGHT.remove(entity.getUUID());
		FLIGHT_STAMP.remove(entity.getUUID());
		FLIGHT_CONTEXT.remove(entity.getUUID());
	}

	/**
	 * 该玩家此刻是否处于"**本模组授予的飞行**"状态（1.6.8）。
	 *
	 * <p>用于飞行免摔：只认我们授予的飞行（幽魂 / 魔眼 / 终末天启 / 亚巴顿 / 神性），
	 * 不去管创造模式或其它模组给的飞行。
	 */
	public static boolean hasGrantedFlight(LivingEntity entity) {
		return entity != null && GRANTED_FLIGHT.contains(entity.getUUID());
	}

	/**
	 * 只在属性摘要变化时打一条 INFO（每 10 tick 重算一次，签名不变就静默），
	 * 用来确认「肉体 +生命」「魂心 +黄血」「光环倍率」等是否生效。
	 */
	private static void logIfChanged(LivingEntity entity, double bodyHealth, boolean haloWorn,
			double factor, double soulHearts) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		double healthBonus = bodyHealth
				+ (haloWorn ? ReliquaryConfig.haloMaxHealth() * factor : 0.0D);
		// 1.8.5：变化判定只用**关键字段**，不再包含「原版吸收」——
		// 那个值会被 Enchantment Reforged 那类护盾 / 恢复机制每 tick 微调（7.0 → 7.5 → 8.0 …），
		// 带上它会让这条日志每 2~3 秒刷一条。吸收值仍然会打印，只是不再参与"值变了没有"的判断。
		String summary = String.format(
				"生命加成 +%.0f（上限 %.0f）、光环=%s（倍率 ×%.2f）、魂心=%.1f、魂心池=%.1f（上限 %.1f）、"
						+ "%s、套装=%s",
				healthBonus, player.getMaxHealth(),
				haloWorn ? "已佩戴" : "未佩戴", factor, soulHearts,
				SoulShield.points(player), SoulShield.capacityFor(player),
				blackHeartSummary(player),
				SpiritAltarSet.isFullSet(player) ? "激活" : "未激活");
		String previous = LAST_SUMMARY.put(player.getUUID(), summary);
		if (summary.equals(previous)) {
			return;
		}
		SummyReliquary.LOGGER.info("[Summy Reliquary] {} 属性更新：{}、原版吸收={}",
				player.getGameProfile().getName(), summary,
				String.format(java.util.Locale.ROOT, "%.1f", player.getAbsorptionAmount()));
	}

	/**
	 * 黑心池的日志片段（1.6.7）。
	 *
	 * <p>数值按**生效**口径夹取：没戴契约时黑心本来就不生效，所以显示 {@code 0.0（上限 0.0）}，
	 * 不会出现"上限 4 但其实一点都没用"这种误导。
	 */
	public static String blackHeartSummary(LivingEntity entity) {
		double max = DemonPact.active(entity) ? DemonPact.blackHeartMaxPoints(entity) : 0.0D;
		double points = Math.min(PlayerFlags.blackHeartPoints(entity), max);
		return String.format("黑心池=%.1f（上限 %.1f）", points, max);
	}

	/** 攻速上限：用一个固定 UUID 的负修正把 ATTACK_SPEED 夹到配置值 */
	private static void applyAttackSpeedCap(LivingEntity entity) {
		AttributeInstance instance = entity.getAttribute(Attributes.ATTACK_SPEED);
		if (instance == null) {
			return;
		}
		AttributeModifier existing = instance.getModifier(ATTACK_SPEED_CAP);
		if (existing != null) {
			instance.removeModifier(ATTACK_SPEED_CAP);
		}
		double cap = ReliquaryConfig.maxAttackSpeed();
		double current = instance.getValue();
		if (cap > 0.0D && current > cap) {
			instance.addTransientModifier(new AttributeModifier(ATTACK_SPEED_CAP, "attack_speed_cap",
					cap - current, AttributeModifier.Operation.ADDITION));
		}
	}

	/** 移除后按需重新添加（值为 0 即视为不生效） */
	private static void apply(LivingEntity entity, Attribute attribute, UUID uuid, String name,
			double value, AttributeModifier.Operation operation) {
		AttributeInstance instance = entity.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		AttributeModifier existing = instance.getModifier(uuid);
		if (existing != null) {
			instance.removeModifier(uuid);
		}
		if (Math.abs(value) > 1.0E-6D) {
			instance.addTransientModifier(new AttributeModifier(uuid, name, value, operation));
		}
	}
}
