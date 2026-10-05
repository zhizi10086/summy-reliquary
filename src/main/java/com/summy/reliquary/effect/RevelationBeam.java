package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.net.ReliquaryNetworking;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「启示之光」：终末天启的长按 V 技能（服务端权威结算）。
 *
 * <p>发射后光束固定在起点与方向上，持续 {@code beam_duration_seconds} 秒（默认 1.5 秒）；期间每
 * {@code beam_damage_interval_seconds} 秒（默认 0.1 秒）对"此刻处在圆柱体内"的生物各结算一次
 * {@code beam_damage_per_tick} 点（默认 7 点）——中途进入就吃、离开就停，站在里面不动最多 15 × 7 = 105 点。
 *
 * <p>伤害类型是自定义的 {@code summy-reliquary:revelation_light}（死亡文案自定义）；
 * "无视护甲/附魔保护/抗性、但仍先扣黄血"由**伤害标签**实现（1.6.4 起：该类型写进原版
 * {@code bypasses_armor / bypasses_effects / bypasses_enchantments / bypasses_resistance} 四个标签），
 * 不再依赖 {@code LivingDamageEvent} 的金额校正 —— Kilt 上那次写回是无效的。
 * 每次结算前都会把目标的无敌帧清零，所以 0.1 秒的节奏不会被原版 0.5 秒无敌帧挡住。
 *
 * <p>光柱穿透方块、不伤害施法者，冷却 {@code beam_cooldown_seconds} 秒。
 */
public final class RevelationBeam {
	/**
	 * 光束种类（1.6.4）。
	 *
	 * <p>启示之光与硫磺火的「恶魔之焰」共用这一套引擎（发射、蓄力、照射、结算、渲染），
	 * 只是参数、伤害类型、粒子与提示文案不同。
	 */
	public enum BeamKind {
		/** 启示之光（终末天启 / 神性，天使侧） */
		HOLY("message.summy-reliquary.beam"),
		/** 恶魔之焰（硫磺火，恶魔侧） */
		DEMON_FLAME("message.summy-reliquary.demon_flame");

		private final String messagePrefix;

		BeamKind(String messagePrefix) {
			this.messagePrefix = messagePrefix;
		}

		/** 行动栏提示键（charging / fired / cooldown） */
		public String messageKey(String suffix) {
			return messagePrefix + "." + suffix;
		}

		/** 网络包里的序号 → 种类（越界时退回启示之光，避免旧包炸客户端） */
		public static BeamKind byOrdinal(int ordinal) {
			BeamKind[] all = values();
			return ordinal >= 0 && ordinal < all.length ? all[ordinal] : HOLY;
		}
	}

	/** 每种光束、每个玩家的冷却结束时刻（游戏 tick） */
	private static final Map<BeamKind, Map<UUID, Long>> READY_AT = new EnumMap<>(BeamKind.class);
	/** 正在进行照射的光束 */
	private static final List<ActiveBeam> ACTIVE = new ArrayList<>();
	/** 正在蓄力的玩家 → 蓄力状态（开始 tick + 超时 tick） */
	private static final Map<UUID, Charge> CHARGING = new HashMap<>();

	// ===== 蓄力粒子的纯表现常量（唯一调参入口；按约定不进配置文件） =====
	/** 刚开始蓄力时的环绕半径（格） */
	private static final double CHARGE_RING_START = 1.9D;
	/** 蓄满时的环绕半径（格）：半径一路收缩，形成"向中心聚拢"的观感 */
	private static final double CHARGE_RING_END = 0.4D;
	/** 圆心高度 = 脚底 + 身高 × 该系数（0.55 ≈ 胸口） */
	private static final double CHARGE_CENTER_HEIGHT_FACTOR = 0.55D;
	/** 圆心整体再下移的格数（把粒子压到视线下方，避免挡视野） */
	private static final double CHARGE_CENTER_DROP = 0.35D;
	/** 每隔多少 tick 生成一次粒子（2 = 密度减半） */
	private static final int CHARGE_PARTICLE_INTERVAL = 2;
	/** 每次生成多少个粒子 */
	private static final int CHARGE_PARTICLES_PER_BURST = 2;
	/** 允许生成粒子的角度弧上取几个等分点 */
	private static final int CHARGE_RING_STEPS = 8;
	/** 以视线方向为中心、多少度以内不生成粒子（避免糊在视野中央） */
	private static final double CHARGE_FRONT_EXCLUDE_DEGREES = 20.0D;

	/** 一次蓄力的状态：种类 + 开始 tick + 失效 tick */
	private record Charge(BeamKind kind, long startTick, long expireTick) {
	}

	private RevelationBeam() {
	}

	/** 玩家退出时清掉冷却记录 */
	public static void forget(ServerPlayer player) {
		for (Map<UUID, Long> per : READY_AT.values()) {
			per.remove(player.getUUID());
		}
		// 1.7.3：蓄力状态也一并清掉（创世纪重置后不该还留着"蓄到一半"的状态）
		CHARGING.remove(player.getUUID());
	}

	/** 自检用：人为制造"冷却中 + 正在蓄力"（1.7.3 用来验证创世纪会把两者都清掉） */
	public static void markBusyForTest(ServerPlayer player, int cooldownTicks) {
		long until = player.level().getGameTime() + cooldownTicks;
		// 注意：READY_AT 是按需创建的空表 —— 必须用 readyMap 逐个"先建出来再写"，
		// 否则冷启动时一个都写不进去，冷却断言会假失败（1.7.3 实测踩过）
		for (BeamKind kind : BeamKind.values()) {
			readyMap(kind).put(player.getUUID(), until);
		}
		CHARGING.put(player.getUUID(), new Charge(BeamKind.HOLY, player.level().getGameTime(), until));
	}

	/** 自检用：当前剩余冷却 tick（取所有光束类型的最大值） */
	public static int cooldownRemainingForTest(ServerPlayer player) {
		long now = player.level().getGameTime();
		long best = 0L;
		for (Map<UUID, Long> per : READY_AT.values()) {
			best = Math.max(best, per.getOrDefault(player.getUUID(), 0L));
		}
		return (int) Math.max(0L, best - now);
	}

	/** 服务端停止时清空活动光束与登记，避免残留到下一个存档 */
	public static void clear() {
		ACTIVE.clear();
		CHARGING.clear();
		READY_AT.clear();
	}

	// ==================== 种类与参数（1.6.4） ====================

	/** 是否佩戴着硫磺火（恶魔之焰） */
	public static boolean wearsBrimstone(net.minecraft.world.entity.LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableBrimstone()
				&& com.summy.reliquary.util.CurioHelper.wears(entity, SummyReliquary.BRIMSTONE.get());
	}

	/**
	 * 是否佩戴着亚巴顿（1.6.8：继承并**强化**恶魔之焰 —— 伤害 9 / 射程 32 / 半径 3）。
	 *
	 * <p>它与硫磺火同属「启示之座」栏、天然互斥；这里一起判定只是为了防作弊 / 创造模式兜底。
	 */
	public static boolean wearsAbaddon(net.minecraft.world.entity.LivingEntity entity) {
		return com.summy.reliquary.effect.Abaddon.wears(entity);
	}

	/**
	 * V 键当前该放哪一种光束：**恶魔之焰优先**（硫磺火），否则启示之光（终末天启 / 神性），都没有返回 null。
	 *
	 * <p>实际上 666 会永久锁定天使线，两者不会共存；这条优先级只是给指令 / 创造模式兜底。
	 */
	public static BeamKind kindFor(net.minecraft.world.entity.LivingEntity entity) {
		if (wearsBrimstone(entity) || wearsAbaddon(entity)) {
			return BeamKind.DEMON_FLAME;
		}
		return Godhead.hasRevelation(entity) ? BeamKind.HOLY : null;
	}

	/** 这一发恶魔之焰是否走"亚巴顿强化档"（9 / 32 / 3） */
	private static boolean abyssal(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return kind == BeamKind.DEMON_FLAME && wearsAbaddon(entity);
	}

	/**
	 * 蓄力 tick 数（1.8.2：恶魔之焰按「硫磺火 / 亚巴顿」两档取，启示之光走神性覆盖）。
	 */
	public static int chargeTicks(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		if (kind == BeamKind.DEMON_FLAME) {
			return abyssal(entity, kind) ? ReliquaryConfig.abaddonBeamChargeTicks()
					: ReliquaryConfig.brimstoneChargeTicks();
		}
		return Godhead.beamChargeTicks(entity);
	}

	/** 冷却 tick 数（启示之光按神性 / 天启的覆盖值，恶魔之焰走自己的配置） */
	public static int cooldownTicks(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return kind == BeamKind.DEMON_FLAME ? ReliquaryConfig.brimstoneCooldownTicks()
				: Godhead.beamCooldownTicks(entity);
	}

	private static int length(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		if (kind == BeamKind.DEMON_FLAME) {
			return abyssal(entity, kind) ? ReliquaryConfig.abaddonBeamLength()
					: ReliquaryConfig.brimstoneBeamLength();
		}
		return Godhead.beamLength(entity);
	}

	private static int radius(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		if (kind == BeamKind.DEMON_FLAME) {
			return abyssal(entity, kind) ? ReliquaryConfig.abaddonBeamRadius()
					: ReliquaryConfig.brimstoneBeamRadius();
		}
		return Godhead.beamRadius(entity);
	}

	private static float damagePerTick(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		if (kind == BeamKind.DEMON_FLAME) {
			return abyssal(entity, kind) ? ReliquaryConfig.abaddonBeamDamagePerTick()
					: ReliquaryConfig.brimstoneDamagePerTick();
		}
		return ReliquaryConfig.beamDamagePerTick();
	}

	private static int durationTicks(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return kind == BeamKind.DEMON_FLAME ? ReliquaryConfig.brimstoneDurationTicks()
				: ReliquaryConfig.beamDurationTicks();
	}

	private static int intervalTicks(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return kind == BeamKind.DEMON_FLAME ? ReliquaryConfig.brimstoneDamageIntervalTicks()
				: ReliquaryConfig.beamDamageIntervalTicks();
	}

	/** 该种类使用的伤害类型路径（都在本模组命名空间下） */
	private static String damageTypePath(BeamKind kind) {
		return kind == BeamKind.DEMON_FLAME ? "demon_flame" : "revelation_light";
	}

	/** 某个种类、某个玩家的冷却结束时刻 */
	private static long readyAt(BeamKind kind, UUID id) {
		Map<UUID, Long> per = READY_AT.get(kind);
		return per == null ? 0L : per.getOrDefault(id, 0L);
	}

	// ==================== 自检接口（1.6.8：用于断言"亚巴顿强化档"的分流） ====================

	public static int beamLengthForTest(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return length(entity, kind);
	}

	public static int beamRadiusForTest(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return radius(entity, kind);
	}

	public static float beamDamageForTest(net.minecraft.world.entity.LivingEntity entity, BeamKind kind) {
		return damagePerTick(entity, kind);
	}

	/** 某个种类的冷却表（按需创建） */
	private static Map<UUID, Long> readyMap(BeamKind kind) {
		return READY_AT.computeIfAbsent(kind, key -> new HashMap<>());
	}

	/**
	 * 客户端通知蓄力状态变化。
	 *
	 * <p>开始蓄力时校验"佩戴终末天启 + 不在冷却"，并记录最长蓄力时长（配置值 + 少量宽限）；
	 * 结束/中断直接清除。期间由 {@link #tickServer} 生成附近可见的白色粒子。
	 */
	public static void setCharging(ServerPlayer player, boolean charging) {
		UUID id = player.getUUID();
		if (!charging) {
			CHARGING.remove(id);
			return;
		}
		// 1.6.4：V 键现在可能放两种光束（恶魔之焰优先），两者共用一个蓄力登记
		BeamKind kind = kindFor(player);
		if (kind == null) {
			return;
		}
		long now = player.serverLevel().getGameTime();
		if (now < readyAt(kind, id)) {
			return;
		}
		CHARGING.put(id, new Charge(kind, now, now + chargeTicks(player, kind) + 5L));
	}

	/** 该玩家是否正在蓄力（自检用） */
	public static boolean isCharging(ServerPlayer player) {
		return CHARGING.containsKey(player.getUUID());
	}

	/** 剩余冷却秒数（向上取整；只用于提示） */
	public static int cooldownSecondsLeft(ServerPlayer player, BeamKind kind) {
		long remaining = readyAt(kind, player.getUUID()) - player.serverLevel().getGameTime();
		return (int) Math.max(0L, (remaining + 19L) / 20L);
	}

	/**
	 * 发射一次启示之光：登记一道持续照射的光束。
	 *
	 * @return true 表示确实发射了（通过装备与冷却校验）
	 */
	public static boolean fire(ServerPlayer player) {
		// 1.6.4：V 键两种光束（硫磺火的恶魔之焰优先）
		BeamKind kind = kindFor(player);
		if (kind == null) {
			return false;
		}
		ServerLevel level = player.serverLevel();
		long now = level.getGameTime();
		if (now < readyAt(kind, player.getUUID())) {
			player.displayClientMessage(Component.translatable(kind.messageKey("cooldown"),
					cooldownSecondsLeft(player, kind)), true);
			return false;
		}

		Vec3 look = player.getLookAngle();
		if (look.lengthSqr() < 1.0E-6D) {
			return false;
		}
		Vec3 direction = look.normalize();
		Vec3 origin = player.getEyePosition();
		ActiveBeam beam = new ActiveBeam(kind, level, damageSource(level, player, damageTypePath(kind)),
				origin, direction,
				// 启示之光：佩戴神性时用它的覆盖值（射程 35 / 半径 3）；恶魔之焰：走 [brimstone]
				length(player, kind), radius(player, kind),
				damagePerTick(player, kind), durationTicks(player, kind), intervalTicks(player, kind), now);
		ACTIVE.add(beam);

		readyMap(kind).put(player.getUUID(), now + cooldownTicks(player, kind));
		if (kind == BeamKind.DEMON_FLAME) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0F, 0.7F);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 0.8F, 0.6F);
		} else {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 1.4F);
		}
		// 发射瞬间先铺一层粒子 + 枪口闪光；之后的每 tick 由 tickServer 继续补粒子
		spawnBeamParticles(level, origin, direction, beam.length, beam.radius, kind);
		level.sendParticles(ParticleTypes.FLASH, origin.x, origin.y, origin.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		broadcast(level, origin, direction, beam.length, beam.radius, beam.durationTicks, kind);
		player.displayClientMessage(Component.translatable(kind.messageKey("fired")), true);
		SummyReliquary.LOGGER.info("[Summy Reliquary] {} 发射{}：持续 {} 秒、每 {} tick 结算 {} 点",
				player.getName().getString(),
				kind == BeamKind.DEMON_FLAME ? "恶魔之焰" : "启示之光",
				String.format("%.1f", beam.durationTicks / 20.0D), beam.intervalTicks, (int) beam.damagePerTick);
		return true;
	}

	/** 服务端每 tick 驱动（在 {@code ServerTickEvent} 的 END 阶段调用） */
	public static void tickServer(MinecraftServer server) {
		tickCharging(server);
		if (ACTIVE.isEmpty()) {
			return;
		}
		Iterator<ActiveBeam> iterator = ACTIVE.iterator();
		while (iterator.hasNext()) {
			ActiveBeam beam = iterator.next();
			long now = beam.level.getGameTime();
			// 结算：按固定节奏补齐（服务器卡顿时也不会漏掉次数）
			while (now >= beam.nextDamageAt && beam.nextDamageAt < beam.endAt) {
				applyDamageTick(beam, now);
				beam.nextDamageAt += beam.intervalTicks;
			}
			// 粒子：整段照射期间每 tick 都补
			spawnBeamParticles(beam.level, beam.origin, beam.direction, beam.length, beam.radius, beam.kind);
			if (now >= beam.endAt) {
				iterator.remove();
				SummyReliquary.LOGGER.info("[Summy Reliquary] {}结束：结算 {} 次、命中 {} 个目标",
						beam.kind == BeamKind.DEMON_FLAME ? "恶魔之焰" : "启示之光",
						beam.ticksApplied, beam.hitTargets.size());
			}
		}
	}

	/** 蓄力期间：在身前生成白色粒子（附近玩家都能看到），并及时清理失效状态 */
	private static void tickCharging(MinecraftServer server) {
		if (CHARGING.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Charge>> iterator = CHARGING.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Charge> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			// 1.6.4：卸下对应的饰品（硫磺火 / 天启·神性）即中断充能
			if (player == null || !player.isAlive() || kindFor(player) != entry.getValue().kind()
					|| player.serverLevel().getGameTime() > entry.getValue().expireTick()) {
				iterator.remove();
				continue;
			}
			spawnChargeParticles(player, entry.getValue());
		}
	}

	/**
	 * 蓄力粒子的半径随进度收缩：从 {@value #CHARGE_RING_START} 格收到 {@value #CHARGE_RING_END} 格。
	 *
	 * <p>几何上就是"周围生成、越蓄越向中心聚拢"；纯表现常量，不进配置。
	 */
	public static double chargeRingRadius(double progress) {
		double clamped = Math.max(0.0D, Math.min(1.0D, progress));
		return CHARGE_RING_START + (CHARGE_RING_END - CHARGE_RING_START) * clamped;
	}

	/** 自检用：蓄力粒子的圆心（胸口再下移一点，保证不挡视线） */
	public static Vec3 chargeCenter(ServerPlayer player) {
		return player.position().add(0.0D,
				player.getBbHeight() * CHARGE_CENTER_HEIGHT_FACTOR - CHARGE_CENTER_DROP, 0.0D);
	}

	/** 蓄力粒子：身体侧下方环绕一圈的白色 END_ROD，半径随进度收缩（密度约为旧版的 1/3） */
	private static void spawnChargeParticles(ServerPlayer player, Charge charge) {
		ServerLevel level = player.serverLevel();
		long now = level.getGameTime();
		if (now % CHARGE_PARTICLE_INTERVAL != 0L) {
			return;
		}
		int needed = Math.max(1, chargeTicks(player, charge.kind()));
		double progress = (now - charge.startTick()) / (double) needed;
		double radius = chargeRingRadius(progress);

		Vec3 center = chargeCenter(player);
		// 以视线方向为 0°：排除正前方 ±20° 的扇形，只在其余弧段上取点
		double front = Math.toRadians(CHARGE_FRONT_EXCLUDE_DEGREES);
		double arcSpan = Math.PI * 2.0D - front * 2.0D;
		double yawRadians = Math.toRadians(player.getYRot());
		long step = now / CHARGE_PARTICLE_INTERVAL;
		for (int index = 0; index < CHARGE_PARTICLES_PER_BURST; index++) {
			double ratio = ((step + index * 2L) % CHARGE_RING_STEPS) / (double) CHARGE_RING_STEPS;
			double angle = yawRadians + front + ratio * arcSpan;
			double x = center.x - Math.sin(angle) * radius;
			double z = center.z + Math.cos(angle) * radius;
			level.sendParticles(charge.kind() == BeamKind.DEMON_FLAME
					? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.END_ROD,
					x, center.y, z, 1,
					0.05D, 0.05D, 0.05D, 0.0D);
		}
	}

	/** 一次结算：对"此刻在圆柱体内"的生物逐个造成伤害（无视无敌帧） */
	private static void applyDamageTick(ActiveBeam beam, long now) {
		beam.ticksApplied++;
		for (LivingEntity target : collectTargets(beam)) {
			// 无视无敌帧，保证 0.1 秒的节奏每一下都生效
			target.invulnerableTime = 0;
			if (target.hurt(beam.source, beam.damagePerTick)) {
				beam.hitTargets.add(target.getUUID());
			}
		}
		// 1.8.2：末地水晶不是生物，单独结算 —— 打中即按原版规则引爆
		for (net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal : collectCrystals(beam)) {
			if (crystal.hurt(beam.source, beam.damagePerTick)) {
				beam.hitTargets.add(crystal.getUUID());
			}
		}
	}

	/** 取此刻处在圆柱体内的生物（不含施法者与已死亡目标） */
	private static List<LivingEntity> collectTargets(ActiveBeam beam) {
		List<LivingEntity> targets = new ArrayList<>();
		for (LivingEntity target : beam.level.getEntitiesOfClass(LivingEntity.class, beamBox(beam),
				candidate -> candidate.isAlive() && !candidate.getUUID().equals(beam.casterId))) {
			if (inBeam(beam, target)) {
				targets.add(target);
			}
		}
		return targets;
	}

	/**
	 * 1.8.2：取此刻处在圆柱体内的**末地水晶**。
	 *
	 * <p>水晶不是 {@link LivingEntity}，原版的光柱目标收集拿不到它，所以单列一路；
	 * 对它造成伤害会按原版规则直接引爆（可能波及地形与周围生物，与用箭矢打水晶同款）。
	 */
	private static List<net.minecraft.world.entity.boss.enderdragon.EndCrystal> collectCrystals(
			ActiveBeam beam) {
		List<net.minecraft.world.entity.boss.enderdragon.EndCrystal> crystals = new ArrayList<>();
		for (net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal
				: beam.level.getEntitiesOfClass(
						net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, beamBox(beam),
						candidate -> !candidate.isRemoved()
								&& !candidate.getUUID().equals(beam.casterId))) {
			if (inBeam(beam, crystal)) {
				crystals.add(crystal);
			}
		}
		return crystals;
	}

	/** 光柱判定用的外扩包围盒 */
	private static AABB beamBox(ActiveBeam beam) {
		return new AABB(beam.origin, beam.origin.add(beam.direction.scale(beam.length)))
				.inflate(beam.radius + 1.0D);
	}

	/** 目标中心是否落在光柱圆柱体内（沿轴 0~length、径向 ≤ radius + 半宽） */
	private static boolean inBeam(ActiveBeam beam, net.minecraft.world.entity.Entity entity) {
		Vec3 relative = entity.getBoundingBox().getCenter().subtract(beam.origin);
		double along = relative.dot(beam.direction);
		if (along < 0.0D || along > beam.length) {
			return false;
		}
		double perpendicular = relative.subtract(beam.direction.scale(along)).length();
		return perpendicular <= beam.radius + entity.getBbWidth() * 0.5D;
	}

	private static DamageSource damageSource(ServerLevel level, ServerPlayer caster, String path) {
		Registry<DamageType> registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id(path));
		// 兜底：数据包缺失（例如手动删掉了 damage_type 文件）时退回原版声波，避免抛异常
		Holder<DamageType> holder = registry.getHolder(key)
				.<Holder<DamageType>>map(value -> value)
				.orElseGet(() -> registry.getHolderOrThrow(DamageTypes.SONIC_BOOM));
		return new DamageSource(holder, caster, caster);
	}

	/**
	 * 「启示伤害」的便捷入口：类型固定为 {@code summy-reliquary:revelation_light}，
	 * 并且在命中后给受击者挂/刷新「启示之光」buff（光柱与救恩领域都走这里）。
	 */
	public static boolean applyRevelationDamage(ServerLevel level, ServerPlayer caster, LivingEntity target,
			float damage) {
		return applyTrueDamage(level, caster, target, damage, "revelation_light", true);
	}

	/**
	 * 「真实伤害」的通用落地（1.6.4：口径完全靠**伤害标签**，不再做事后校正）。
	 *
	 * <p>调用前会把目标的无敌帧清零，所以 0.1 秒的节奏不会被原版 0.5 秒无敌帧挡住；
	 * 具体减免由类型的 {@code bypasses_*} 标签决定（见各 {@code damage_type} 与标签覆盖文件）。
	 *
	 * @param path              伤害类型路径（本模组命名空间下），如 {@code revelation_light} / {@code holy_light}
	 * @param applyLightBuff    命中后是否挂「启示之光」（只有启示伤害挂）
	 */
	public static boolean applyTrueDamage(ServerLevel level, ServerPlayer caster, LivingEntity target,
			float damage, String path, boolean applyLightBuff) {
		target.invulnerableTime = 0;
		boolean hurt = target.hurt(testedDamageSource(level, caster, path), damage);
		if (!hurt) {
			return false;
		}
		if (applyLightBuff && com.summy.reliquary.config.ReliquaryConfig.enableRevelationLightBuff()) {
			// 1.5.6：启示伤害会给受击者挂/刷新「启示之光」（纯标记，7 秒可配置）
			target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					com.summy.reliquary.SummyReliquary.REVELATION_LIGHT.get(),
					com.summy.reliquary.config.ReliquaryConfig.revelationLightBuffTicks(),
					0, false, true, true));
		}
		// 1.5.7：训练人偶只吃伤害数字，不允许被我们打坏（结算后回满血）
		DummySupport.protectDummy(target);
		return true;
	}

	/** 指定类型的伤害源（数据包缺失时退回原版魔法伤害） */
	private static DamageSource testedDamageSource(ServerLevel level, ServerPlayer caster, String path) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		var holder = registry.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE,
				com.summy.reliquary.SummyReliquary.id(path))).orElse(null);
		if (holder == null) {
			return level.damageSources().magic();
		}
		return new DamageSource(holder, caster, caster);
	}

	/** 粒子：启示之光 = 白色 END_ROD + 金色 WAX_ON；恶魔之焰 = 火焰 + 灵魂火（1.6.4） */
	private static void spawnBeamParticles(ServerLevel level, Vec3 origin, Vec3 direction, double length,
			double radius, BeamKind kind) {
		int index = 0;
		for (double distance = 0.5D; distance < length; distance += 1.0D, index++) {
			Vec3 point = origin.add(direction.scale(distance));
			ParticleOptions particle;
			if (kind == BeamKind.DEMON_FLAME) {
				particle = index % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
			} else {
				particle = index % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.WAX_ON;
			}
			level.sendParticles(particle, point.x, point.y, point.z, 2,
					radius * 0.3D, radius * 0.3D, radius * 0.3D, 0.0D);
		}
	}

	/** 把光柱广播给附近客户端，用于渲染（含持续时长与种类） */
	private static void broadcast(ServerLevel level, Vec3 origin, Vec3 direction, double length, double radius,
			int durationTicks, BeamKind kind) {
		for (ServerPlayer viewer : level.players()) {
			double broadcastRadius = ReliquaryConfig.beamBroadcastRadius();
			if (viewer.distanceToSqr(origin) > broadcastRadius * broadcastRadius) {
				continue;
			}
			ReliquaryNetworking.sendBeam(viewer, origin.x, origin.y, origin.z,
					(float) direction.x, (float) direction.y, (float) direction.z,
					(float) length, (float) radius, durationTicks, kind.ordinal());
		}
	}

	/** 一道正在照射的光束（固定在发射点与方向上） */
	private static final class ActiveBeam {
		private final BeamKind kind;
		private final ServerLevel level;
		private final DamageSource source;
		private final UUID casterId;
		private final Vec3 origin;
		private final Vec3 direction;
		private final double length;
		private final double radius;
		private final float damagePerTick;
		private final int durationTicks;
		private final long intervalTicks;
		private final long endAt;
		private final Set<UUID> hitTargets = new HashSet<>();
		private long nextDamageAt;
		private int ticksApplied;

		private ActiveBeam(BeamKind kind, ServerLevel level, DamageSource source, Vec3 origin, Vec3 direction,
				double length, double radius, float damagePerTick, int durationTicks, long intervalTicks,
				long now) {
			this.kind = kind;
			this.level = level;
			this.source = source;
			Entity causing = source.getEntity();
			this.casterId = causing == null ? new UUID(0L, 0L) : causing.getUUID();
			this.origin = origin;
			this.direction = direction;
			this.length = length;
			this.radius = radius;
			this.damagePerTick = damagePerTick;
			this.durationTicks = Math.max(1, durationTicks);
			this.intervalTicks = Math.max(1L, intervalTicks);
			this.endAt = now + this.durationTicks;
			// 发射瞬间先结算一次，之后每 intervalTicks 一次
			this.nextDamageAt = now;
		}
	}
}
