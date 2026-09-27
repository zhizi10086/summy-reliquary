package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「救恩」的领域审判（1.5.0）。
 *
 * <p>规则：以佩戴者**脚底**为圆心、半径 2 格（同时佩戴**终末天启**时 3 格）；
 * 领域内的**敌对生物**累计停留满 1 秒 → 结算 7 点启示伤害（先扣黄血、无视护甲/附魔/抗性，结算前清无敌帧）；
 * 每名敌人结算后有 0.5 秒冷却再开始下一轮锁定，**各敌人独立判定**。
 *
 * <p>锁定表现：开始时在目标**碰撞箱顶部 + 3 格**与身体周围生成粒子、密度随进度增加；
 * 完成瞬间从该**蓄力点**射向目标身体中部生成一条粒子束（1.5.1 起）。
 *
 * <p>玩家默认不受伤；OP 用 {@code /summyreliquary salvation <玩家> <on|off|query>} 把玩家加入"可被审判"名单
 * （名单存在该玩家的持久化数据里）。
 */
public final class SalvationDomain {
	/** 领域半径（格） */
	// 数值全部来自 [blessing] 配置段（ReliquaryConfig.salvation*）

	/** 玩家名单标记键（存在该玩家的持久化数据里） */
	private static final String TARGETABLE = "salvation_targetable";

	// ===== 边界粒子的观感常量（唯一调参入口；不进配置） =====
	/** 每隔多少 tick 生成一批边界粒子 */
	private static final int BOUNDARY_INTERVAL = 1;
	/** 每批生成几个点（每 45° 一个 → 一圈 8 点） */
	private static final int BOUNDARY_POINTS = 8;
	// 相位角每 tick 前进多少度：1.5°/tick = 30°/秒（一圈 12 秒）。
	// 1.5.2 起改成「每 tick 一批、每批只转 1.5°」：电火花寿命只有 2~3 tick，
	// 相邻两三批叠在一起才看得出是连续旋转，而不是每批跳一大格。
	private static final double BOUNDARY_DEGREES_PER_TICK = 1.5D;
	/** 圆心相对脚底的上抬高度（避免贴地） */
	private static final double BOUNDARY_LIFT = 0.15D;

	// 锁定粒子（头顶 + 身体）：1.5.3 起改用 ELECTRIC_SPARK（青蓝电火花，寿命只有 2~3 tick），
	// 靠「每 tick 各生成 LOCK_DENSITY 颗」重叠出约 0.5 秒的持续存在感。
	private static final net.minecraft.core.particles.SimpleParticleType LOCK_PARTICLE =
			net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK;
	/** 锁定粒子每 tick 在每个点生成的颗数（固定密度，不再随进度加密） */
	private static final int LOCK_DENSITY = 3;
	/** 打击粒子束：同样是电火花，靠逐 tick 重画补时长 */
	private static final net.minecraft.core.particles.SimpleParticleType BEAM_PARTICLE =
			net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK;
	/** 粒子束每点每 tick 的颗数 */
	private static final int BEAM_PARTICLES_PER_POINT = 1;
	/** 粒子束重画时长（tick）：10 = 0.5 秒，正好在下一轮打击（0.5 秒冷却）前后散尽 */
	private static final int BEAM_LINGER_TICKS = 10;
	/** 粒子束的步距（格/点）：0.125 → 每格 8 个点 */
	private static final double BEAM_STEP = 0.125D;
	/** 打击瞬间末端环绕爆发的颗数（只在第一 tick 出，不参与重画） */
	private static final int BEAM_END_RING = 8;
	/** 蓄力点相对**碰撞箱顶部**的高度差（锁定粒子与粒子束的起点，1.5.1 起按碰撞箱算，矮生物不再错位） */
	private static final double CHARGE_LIFT = 3.0D;

	/** 自检用：累计生成的边界粒子批次数（正式游戏里只累加，不参与任何逻辑） */
	private static int boundaryBatches;

	/** 施法者 → (目标 UUID → 已累计锁定 tick) */
	private static final Map<UUID, Map<UUID, Integer>> LOCKS = new HashMap<>();
	/** 目标 UUID → 冷却结束的游戏 tick */
	private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
	/** 打击后还没过期的粒子束（每 tick 重画一次，共 BEAM_LINGER_TICKS tick） */
	private static final java.util.List<ActiveBeam> BEAMS = new java.util.ArrayList<>();

	/** 一条正在重画的粒子束 */
	private static final class ActiveBeam {
		/** 施法者 UUID */
		private final UUID caster;
		/** 目标 UUID（目标还在就跟走；不在就沿用冻结的两端） */
		private final UUID target;
		private Vec3 from;
		private Vec3 to;
		/** 到期时的服务端 tick */
		private final long endTick;

		private ActiveBeam(UUID caster, UUID target, Vec3 from, Vec3 to, long endTick) {
			this.caster = caster;
			this.target = target;
			this.from = from;
			this.to = to;
			this.endTick = endTick;
		}
	}

	private SalvationDomain() {
	}

	/** 服务端每 tick 调用 */
	public static void tickServer(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!CurioHelper.wears(player, SummyReliquary.SALVATION.get())) {
				LOCKS.remove(player.getUUID());
				// 卸下救恩就不再重画他的粒子束
				BEAMS.removeIf(beam -> beam.caster.equals(player.getUUID()));
				continue;
			}
			ServerLevel level = player.serverLevel();
			Vec3 center = player.position();
			double radius = radiusFor(player);

			// ① 边界：一圈**绕玩家旋转**的青蓝电火花（每 BOUNDARY_INTERVAL tick 生成 BOUNDARY_POINTS 个点）
			if (level.getGameTime() % BOUNDARY_INTERVAL == 0L) {
				double phase = level.getGameTime() * BOUNDARY_DEGREES_PER_TICK * Math.PI / 180.0D;
				for (int index = 0; index < BOUNDARY_POINTS; index++) {
					double angle = phase + Math.PI * 2.0D * index / BOUNDARY_POINTS;
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
							center.x + Math.cos(angle) * radius,
							center.y + BOUNDARY_LIFT,
							center.z + Math.sin(angle) * radius,
							1, 0.0D, 0.0D, 0.0D, 0.0D);
				}
				boundaryBatches++;
			}

			// ② 领域内的目标
			Map<UUID, Integer> locks = LOCKS.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
			Set<UUID> seen = new HashSet<>();
			long now = level.getGameTime();
			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(radius + 1.0D), entity -> canJudge(player, entity))) {
				if (target.distanceTo(player) > radius) {
					continue;
				}
				seen.add(target.getUUID());
				if (now < COOLDOWN.getOrDefault(target.getUUID(), 0L)) {
					continue;
				}
				int progress = locks.getOrDefault(target.getUUID(), 0) + 1;
				// ③ 锁定粒子：头顶（碰撞箱顶部 + 3）与身体中部各 LOCK_DENSITY 颗电火花；
				// 电火花寿命只有 2~3 tick，所以必须逐 tick 生成才能看起来"一直存在"
				Vec3 charge = chargePoint(target);
				Vec3 body = bodyCenter(target);
				level.sendParticles(LOCK_PARTICLE, charge.x, charge.y, charge.z,
						LOCK_DENSITY, 0.35D, 0.05D, 0.35D, 0.0D);
				level.sendParticles(LOCK_PARTICLE, body.x, body.y, body.z,
						LOCK_DENSITY, 0.35D, 0.35D, 0.35D, 0.0D);

				if (progress >= Math.max(1, (int) Math.round(
						com.summy.reliquary.config.ReliquaryConfig.salvationLockSeconds() * 20.0D))) {
					locks.remove(target.getUUID());
					COOLDOWN.put(target.getUUID(), now + Math.max(1, (int) Math.round(
							com.summy.reliquary.config.ReliquaryConfig.salvationCooldownSeconds() * 20.0D)));
					judge(player, target);
				} else {
					locks.put(target.getUUID(), progress);
				}
			}
			locks.keySet().removeIf(id -> !seen.contains(id));
		}
		// ④ 粒子束：打击后继续逐 tick 重画 0.5 秒（不依赖施法者此刻是否还在领域内）
		redrawBeams(server, server.getTickCount());
	}

	/** 重画所有还没过期的粒子束；目标还在就跟着目标走，否则沿用冻结的两端把剩余时长放完 */
	private static void redrawBeams(MinecraftServer server, long nowTick) {
		if (BEAMS.isEmpty()) {
			return;
		}
		BEAMS.removeIf(beam -> {
			if (nowTick > beam.endTick) {
				return true;
			}
			ServerPlayer caster = server.getPlayerList().getPlayer(beam.caster);
			if (caster == null) {
				return true;
			}
			var target = caster.serverLevel().getEntity(beam.target);
			if (target instanceof LivingEntity living && living.isAlive()) {
				beam.from = chargePoint(living);
				beam.to = bodyCenter(living);
			}
			drawBeamLine(caster.serverLevel(), beam.from, beam.to);
			return false;
		});
	}

	/** 沿光路画一条电火花线（每点 BEAM_PARTICLES_PER_POINT 颗） */
	private static void drawBeamLine(ServerLevel level, Vec3 from, Vec3 to) {
		int steps = (int) Math.max(1.0D, from.distanceTo(to) / BEAM_STEP);
		for (int index = 0; index <= steps; index++) {
			Vec3 point = from.lerp(to, index / (double) steps);
			level.sendParticles(BEAM_PARTICLE, point.x, point.y, point.z, BEAM_PARTICLES_PER_POINT,
					0.02D, 0.02D, 0.02D, 0.0D);
		}
	}

	/** 是否可以审判：敌对生物；玩家只有在名单里才可被审判 */
	private static boolean canJudge(ServerPlayer caster, LivingEntity target) {
		if (target == caster || !target.isAlive()) {
			return false;
		}
		if (target instanceof Player) {
			return isTargetable(target);
		}
		// 训练人偶（dummmmmmy:target_dummy）：默认也吃领域，可用开关关掉
		if (DummySupport.isTargetDummy(target)) {
			return true;
		}
		return target instanceof Enemy;
	}

	/**
	 * 该玩家的领域半径：优先级 神性（默认 5）&gt; 终末天启（默认 4）&gt; 基础（默认 3）。
	 *
	 * <p>自检也用它，保证"看到的边界 = 实际判定范围"。
	 */
	public static double radiusFor(ServerPlayer player) {
		if (Godhead.active(player)) {
			return Godhead.salvationRadius(player);
		}
		if (CurioHelper.wears(player, SummyReliquary.FINAL_REVELATION.get())) {
			return com.summy.reliquary.config.ReliquaryConfig.salvationExtendedRadius();
		}
		return com.summy.reliquary.config.ReliquaryConfig.salvationRadius();
	}

	/** 结算一次领域伤害：用自定义的启示伤害类型，先扣黄血、无视护甲/附魔/抗性 */
	private static void judge(ServerPlayer caster, LivingEntity target) {
		ServerLevel level = caster.serverLevel();
		// 粒子束：从**目标头顶的蓄力点**射向目标身体中部（1.5.1 起与锁定粒子同源）；
		// 1.5.3 起换成电火花，并在打击后逐 tick 重画 0.5 秒 —— 所以这里只出"第一帧"
		Vec3 from = chargePoint(target);
		Vec3 to = bodyCenter(target);
		drawBeamLine(level, from, to);
		// 末端环绕爆发 + 闪光：只出这一次，不参与重画
		for (int index = 0; index < BEAM_END_RING; index++) {
			double angle = Math.PI * 2.0D * index / BEAM_END_RING;
			level.sendParticles(BEAM_PARTICLE, to.x + Math.cos(angle) * 0.6D, to.y,
					to.z + Math.sin(angle) * 0.6D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		level.sendParticles(ParticleTypes.FLASH, to.x, to.y, to.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		// 登记活跃光束：接下来 BEAM_LINGER_TICKS tick 每 tick 重画一次
		if (caster.getServer() != null) {
			BEAMS.add(new ActiveBeam(caster.getUUID(), target.getUUID(), from, to,
					caster.getServer().getTickCount() + BEAM_LINGER_TICKS));
		}
		// 先扣黄血：自己按固定值扣，避免原版"事件前后各扣一次吸收"导致总额翻倍；
		// 剩下的血量伤害再走启示之光的"真实伤害"校正（无视护甲 / 附魔保护 / 抗性）
		float damage = com.summy.reliquary.config.ReliquaryConfig.salvationDamage();
		float pool = target.getAbsorptionAmount();
		float absorbed = Math.min(pool, damage);
		if (absorbed > 0.0F) {
			target.setAbsorptionAmount(pool - absorbed);
		}
		float remaining = damage - absorbed;
		if (remaining > 0.0F) {
			RevelationBeam.applyRevelationDamage(level, caster, target, remaining);
		}
	}

	/** 蓄力点（锁定粒子与粒子束的起点）：目标**碰撞箱顶部**再上抬 3 格 */
	public static Vec3 chargePoint(LivingEntity target) {
		return new Vec3(target.getX(), target.getBoundingBox().maxY + CHARGE_LIFT, target.getZ());
	}

	/** 目标身体中部（锁定粒子与粒子束的终点）：碰撞箱的垂直中心 */
	public static Vec3 bodyCenter(LivingEntity target) {
		return new Vec3(target.getX(),
				(target.getBoundingBox().minY + target.getBoundingBox().maxY) / 2.0D, target.getZ());
	}

	/** 自检用：读取已生成的边界粒子批次数 */
	public static int boundaryBatches() {
		return boundaryBatches;
	}

	/** 自检用：复位边界粒子批次计数 */
	public static void resetBoundaryBatches() {
		boundaryBatches = 0;
	}

	/** 自检用：当前锁定粒子类型（应恒为短寿命的 ELECTRIC_SPARK） */
	public static net.minecraft.core.particles.SimpleParticleType lockParticle() {
		return LOCK_PARTICLE;
	}

	/** 自检用：当前打击粒子束类型（应恒为短寿命的 ELECTRIC_SPARK） */
	public static net.minecraft.core.particles.SimpleParticleType beamParticle() {
		return BEAM_PARTICLE;
	}

	/** 自检用：锁定粒子每 tick 每点的颗数 */
	public static int lockDensity() {
		return LOCK_DENSITY;
	}

	/** 自检用：粒子束重画时长（tick） */
	public static int beamLingerTicks() {
		return BEAM_LINGER_TICKS;
	}

	/** 自检用：当前还没过期的粒子束条数 */
	public static int activeBeams() {
		return BEAMS.size();
	}

	/** 玩家登出时清掉他的锁定进度与粒子束 */
	public static void forget(ServerPlayer player) {
		LOCKS.remove(player.getUUID());
		BEAMS.removeIf(beam -> beam.caster.equals(player.getUUID()));
	}

	/** 该玩家是否在"可被审判"名单里 */
	public static boolean isTargetable(LivingEntity entity) {
		return entity.getPersistentData().getCompound(SinManager.ROOT).getBoolean(TARGETABLE);
	}

	/** 设置名单（OP 命令 / 自检用） */
	public static void setTargetable(ServerPlayer player, boolean value) {
		// 必须用「挂回父标签」的写法：直接 getCompound(...).putBoolean(...) 在根标签不存在时会写进游离标签而丢失
		SinManager.mutableRoot(player).putBoolean(TARGETABLE, value);
	}
}
