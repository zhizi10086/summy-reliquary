package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「复仇之魂」（1.6.2）：加护栏的恶魔侧饰品。
 *
 * <p>佩戴时**每秒**对半径内与神性光环同口径的敌人造成固定点数的**狱火伤害**。
 * 伤害类型 {@code summy-reliquary:hellfire} 写进原版 {@code bypasses_armor} 标签 =
 * 无视护甲、保护与抗性照常生效、清无敌帧；并列入"本模组特效伤害"名单，
 * 所以不吃七罪/七德/最终倍率，也不会触发契约的生命吸取。
 */
public final class VengefulSpirit {
	/** 范围圈每 tick 生成的点数（与救恩一致） */
	public static final int RING_POINTS = 8;
	/** 范围圈的高度偏移 */
	public static final double RING_LIFT = 0.15D;

	private VengefulSpirit() {
	}

	/** 是否佩戴着复仇之魂 */
	public static boolean wears(LivingEntity entity) {
		return entity != null && CurioHelper.wears(entity, SummyReliquary.VENGEFUL_SPIRIT.get());
	}

	/**
	 * 生效半径（格）：1.6.7 起同时佩戴「硫磺火」时用配置里的更大半径（默认 3 → 4）。
	 *
	 * <p>伤害判定与粒子环共用同一个值，避免"伤害打得到、圈却画小了"。
	 */
	public static double effectiveRadius(LivingEntity entity) {
		// 1.6.8：佩戴亚巴顿时优先取更高的一档
		if (entity != null && Abaddon.wears(entity)) {
			return ReliquaryConfig.vengefulHellfireRadiusAbaddon();
		}
		if (entity != null && CurioHelper.wears(entity, SummyReliquary.BRIMSTONE.get())) {
			return ReliquaryConfig.vengefulHellfireRadiusBrimstone();
		}
		return ReliquaryConfig.vengefulHellfireRadius();
	}

	/** 服务端每秒：对半径内的敌人结算一次狱火伤害 */
	public static void tick(ServerPlayer player) {
		if (!wears(player)) {
			return;
		}
		double damage = ReliquaryConfig.vengefulHellfireDamage();
		if (damage <= 0.0D) {
			return;
		}
		ServerLevel level = player.serverLevel();
		double radius = effectiveRadius(player);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius),
				entity -> Godhead.isAuraTarget(player, entity))) {
			if (target.distanceTo(player) > radius) {
				continue;
			}
			applyHellfire(level, player, target, (float) damage);
		}
	}

	/**
	 * 服务端**每 tick**：在半径圈上生成一批火焰粒子（1.6.3 新增）。
	 *
	 * <p>与救恩的领域边界同款做法：每 tick 一批、每批只转 1.5°，相邻几批叠在一起才看得出连续旋转；
	 * 火焰粒子的寿命只有十几 tick，也必须逐 tick 生成才像"一直存在"。
	 * 相位取负号 → **逆时针**（与救恩的顺时针反相）。
	 */
	public static void tickRing(ServerPlayer player) {
		if (!wears(player)) {
			return;
		}
		spawnRing(player);
	}

	/** 半径圈上的火焰粒子（逆时针；与救恩的旋转方向相反） */
	public static void spawnRing(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		double radius = effectiveRadius(player);
		double phase = ringPhase(level.getGameTime());
		// 1.7.3：粒子注册改成"不依赖 Forge 事件"的路径（见 ReliquaryParticles），
		// Forge 与 Kilt 都能真正渲染我们的短寿命火焰，所以不再需要在服务端回退。
		net.minecraft.core.particles.SimpleParticleType particle = ringParticleType();
		for (int index = 0; index < RING_POINTS; index++) {
			double angle = phase + Math.PI * 2.0D * index / RING_POINTS;
			// Forge：短寿命火焰（贴图仍是原版火焰，只是寿命 4~8 tick）——走动时不再拖尾
			// Kilt：退回原版火焰（见 ringParticleType 的说明）
			level.sendParticles(particle,
				player.getX() + Math.cos(angle) * radius,
				player.getY() + RING_LIFT,
				player.getZ() + Math.sin(angle) * radius,
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		ringBatches++;
	}

	/**
	 * 火焰环使用的粒子类型：始终是我们的**短寿命火焰**（1.7.3 起 Kilt 也能注册成功）。
	 */
	public static net.minecraft.core.particles.SimpleParticleType ringParticleType() {
		return SummyReliquary.SHORT_FLAME.get();
	}

	/** 自检用：某一 tick 的相位角（弧度）。负号 = 逆时针，与救恩的 +1.5°/tick 刚好反相。 */
	public static double ringPhase(long gameTime) {
		return -gameTime * 1.5D * Math.PI / 180.0D;
	}

	/** 自检用：累计生成批次数与单批点数、高度 */
	private static int ringBatches;

	public static int ringBatches() {
		return ringBatches;
	}

	public static void resetRingBatches() {
		ringBatches = 0;
	}

	public static net.minecraft.core.particles.SimpleParticleType ringParticle() {
		return ringParticleType();
	}

	/** 落地一次狱火伤害（清无敌帧、无视护甲） */
	public static void applyHellfire(ServerLevel level, ServerPlayer attacker, LivingEntity target, float damage) {
		if (damage <= 0.0F) {
			return;
		}
		target.invulnerableTime = 0;
		target.hurt(hellfireSource(level, attacker), damage);
	}

	/** 狱火伤害源（数据包缺失时退回原版魔法伤害，避免抛异常） */
	private static DamageSource hellfireSource(ServerLevel level, ServerPlayer attacker) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("hellfire"));
		var holder = registry.getHolder(key).orElse(null);
		if (holder == null) {
			return level.damageSources().magic();
		}
		return new DamageSource(holder, attacker, attacker);
	}
}
