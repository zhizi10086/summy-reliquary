package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「圣光」的命中判定与结算（1.5.4）。
 *
 * <p>规则：佩戴圣光的玩家**命中时**（近战或箭矢/投掷物）按几率召唤圣光，对目标造成
 * 「本次命中在各类倍率算完、减伤之前」的伤害 × 配置百分比。
 *
 * <p>多段处理（同一 tick + 同一攻击者 + 同一目标）：
 * <ul>
 *     <li>段的伤害类型与 directEntity 与当前这次攻击**相同** → 视为**新的一次攻击**（各自掷点）——
 *     对应「出其不意」那种同源再打一次；</li>
 *     <li>类型**不同** → **累加**进当前这次攻击的基准 —— 对应「魔剑」那种同一击的追加段。</li>
 * </ul>
 *
 * <p>时机：在 {@code LivingHurtEvent} 的 LOWEST 优先级记录（各类倍率之后、目标减伤之前），
 * 服务端 tick 结束时统一掷点结算；落地前清无敌帧，所以**无视无敌帧**。
 *
 * <p><b>两条独立通道</b>（1.7.9）：饰品位（圣光 / 圣光 + 神性 → 15% / 25%）与
 * **手持圣光短矛**的攻击（固定 {@code [holy_light] holy_light_chance_percent_spear}，默认 10%）
 * 各自掷骰、互不影响 —— 同一次命中可能同时触发两条光柱。
 */
public final class HolyLightEffect {
	/** 光柱重画时长（tick）：3 = 0.15 秒，短促有力 */
	private static final int COLUMN_LINGER_TICKS = 3;
	/** 光柱每点每 tick 的颗数 */
	private static final int COLUMN_PARTICLES_PER_POINT = 2;
	/** 光柱步距（格/点）：0.125 → 每格 8 个点 */
	private static final double COLUMN_STEP = 0.125D;
	/** 光柱起点相对碰撞箱顶部的高度差（格） */
	private static final double COLUMN_TOP_LIFT = 7.0D;
	/** 爆发环颗数（与救恩同款，另加 1 颗 FLASH） */
	private static final int BURST_RING = 8;

	/** 本模组的两种神性伤害（不参与记录，避免自我循环） */
	private static final String HOLY_LIGHT_ID = "summy-reliquary.holy_light";
	/** 1.7.9：炽天使之枪投掷的「落点圣光爆发」——独立真伤类型，不吃抗性 / 保护 / 倍率 */
	private static final String HOLY_LIGHT_BURST_ID = "summy-reliquary.holy_light_burst";
	/**
	 * 1.7.10：金刀片投掷的专属物理伤害。
	 *
	 * <p>它**不是**真伤（护甲 / 保护 / 抗性照常减免），但按需求"不享受任何加成"，
	 * 所以同样进下面两张排除表 —— 七罪 / 七德 / 圣心 / 神性 / 契约乘区、伯列恒、思想、王冠分档、
	 * 狱火叠层、圣光 proc、契约吸血全部不参与。
	 */
	private static final String GOLDEN_RAZOR_ID = "summy-reliquary.golden_razor";
	private static final String REVELATION_ID = "summy-reliquary.revelation_light";
	/** 撒旦圣经的黑心碎裂（1.6.1）：同样属于"本模组的特效伤害" */
	private static final String PACT_SHATTER_ID = "summy-reliquary.pact_shatter";
	/** 复仇之魂的狱火（1.6.2）：同属"本模组的特效伤害" */
	private static final String HELLFIRE_ID = "summy-reliquary.hellfire";

	/** 1.6.4：硫磺火的「恶魔之焰」（真伤）——同样属于本模组特效伤害 */
	private static final String DEMON_FLAME_ID = "summy-reliquary.demon_flame";

	/** 1.6.4：神性光环的独立真伤类型（死亡文本沿用圣光那条） */
	private static final String GODHEAD_AURA_ID = "summy-reliquary.godhead_aura";

	/** 1.6.8：亚巴顿「恶魔光环」的独立真伤类型（有自己的死亡文本） */
	private static final String ABYSS_AURA_ID = "summy-reliquary.abyss_aura";

	/** 1.6.4：契约献祭的真伤类型 */
	private static final String SACRIFICE_ID = "summy-reliquary.sacrifice";

	/** 还没结算的攻击（同一 tick 内累积） */
	private static final List<Attack> PENDING = new ArrayList<>();
	/** 还在重画的光柱 */
	private static final List<Column> COLUMNS = new ArrayList<>();

	/** 自检用：强制掷点结果（null = 正常随机） */
	private static Boolean forcedRoll = null;
	/** 自检用：命中次数 / 最近一次伤害 */
	private static int triggerCount;
	private static float lastDamage;

	private HolyLightEffect() {
	}

	/** 一次攻击（同一 tick + 同一攻击者 + 同一目标）的累积 */
	private static final class Attack {
		private final UUID attacker;
		private final UUID target;
		private final long tick;
		/** 首段的伤害类型 id 与直接来源（用来判断"同源重复"还是"不同类型追加"） */
		private final String msgId;
		private final Entity direct;
		private float baseline;
		/** 饰品位（圣光 / 圣光 + 神性）的触发通道 */
		private boolean ornament;
		/** 圣光短矛武器位的触发通道（1.7.9） */
		private boolean weapon;

		private Attack(UUID attacker, UUID target, long tick, String msgId, Entity direct, float baseline,
				boolean ornament, boolean weapon) {
			this.attacker = attacker;
			this.target = target;
			this.tick = tick;
			this.msgId = msgId;
			this.direct = direct;
			this.baseline = baseline;
			this.ornament = ornament;
			this.weapon = weapon;
		}
	}

	/** 一条还在重画的光柱 */
	private static final class Column {
		private final ServerLevel level;
		private final UUID target;
		private Vec3 from;
		private Vec3 to;
		private final long endTick;

		private Column(ServerLevel level, UUID target, Vec3 from, Vec3 to, long endTick) {
			this.level = level;
			this.target = target;
			this.from = from;
			this.to = to;
			this.endTick = endTick;
		}
	}

	/**
	 * 记录一次命中（{@code LivingHurtEvent}，LOWEST 优先级：各类倍率已算完、还没减伤）。
	 */
	public static void record(LivingHurtEvent event) {
		if (event.isCanceled() || !ReliquaryConfig.enableHolyLight()) {
			return;
		}
		DamageSource source = event.getSource();
		ServerPlayer attacker = attackerOf(source);
		if (attacker == null || attacker == event.getEntity() || isDivine(source)) {
			return;
		}
		// 两条独立通道：饰品位（圣光 / 圣光 + 神性）与"手持圣光短矛且达标"的武器位
		boolean ornament = CurioHelper.wears(attacker, SummyReliquary.HOLY_LIGHT.get());
		boolean weapon = WeaponGates.holySpearProc(attacker);
		if (!ornament && !weapon) {
			return;
		}
		float amount = event.getAmount();
		if (amount <= 0.0F) {
			return;
		}
		long tick = now(attacker.getServer());
		UUID target = event.getEntity().getUUID();
		Attack last = PENDING.isEmpty() ? null : PENDING.get(PENDING.size() - 1);
		if (last != null && last.attacker.equals(attacker.getUUID()) && last.target.equals(target)
				&& last.tick == tick) {
			if (last.msgId.equals(source.getMsgId()) && last.direct == source.getDirectEntity()) {
				// 同源重复（例如 ER「出其不意」的第二击）＝ 新的一次攻击，单独掷点
				PENDING.add(new Attack(attacker.getUUID(), target, tick, source.getMsgId(),
						source.getDirectEntity(), amount, ornament, weapon));
			} else {
				// 不同类型的追加段（例如 ER「魔剑」的 indirect_magic）＝ 累加进当前这次攻击
				last.baseline += amount;
				last.ornament |= ornament;
				last.weapon |= weapon;
			}
		} else {
			PENDING.add(new Attack(attacker.getUUID(), target, tick, source.getMsgId(),
					source.getDirectEntity(), amount, ornament, weapon));
		}
	}

	/** 服务端每 tick：先结算本 tick 记录的攻击，再重画还没过期的光柱 */
	public static void tickServer(MinecraftServer server) {
		long nowTick = now(server);
		settle(server, nowTick);
		redraw(nowTick);
	}

	/** 掷点并结算（每个攻击实例一次） */
	private static void settle(MinecraftServer server, long nowTick) {
		if (PENDING.isEmpty()) {
			return;
		}
		List<Attack> batch = new ArrayList<>(PENDING);
		PENDING.clear();
		for (Attack attack : batch) {
			ServerPlayer attacker = server.getPlayerList().getPlayer(attack.attacker);
			if (attacker == null) {
				continue;
			}
			ServerLevel level = attacker.serverLevel();
			Entity entity = level.getEntity(attack.target);
			if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
				continue;
			}
			float damage = attack.baseline * ReliquaryConfig.holyLightDamagePercent() / 100.0F;
			if (damage <= 0.0F) {
				continue;
			}
			// 两条通道各自独立掷骰：同一次命中可能出两条光柱
			if (attack.ornament && rollPercent(attacker, Synergies.holyLightChancePercent(attacker))) {
				applyHolyLight(level, attacker, target, damage, nowTick);
			}
			if (attack.weapon && rollPercent(attacker, ReliquaryConfig.holyLightChancePercentSpear())) {
				applyHolyLight(level, attacker, target, damage, nowTick);
			}
		}
	}

	/** 落地圣光伤害（先清无敌帧）并打出光柱与爆发 */
	private static void applyHolyLight(ServerLevel level, ServerPlayer attacker, LivingEntity target, float damage,
			long nowTick) {
		if (damage <= 0.0F) {
			return;
		}
		triggerCount++;
		lastDamage = damage;
		// 无视无敌帧：和启示之光 / 救恩同一套做法
		target.invulnerableTime = 0;
		target.hurt(holyLightSource(level, attacker), damage);
		spawnColumn(level, target, nowTick + COLUMN_LINGER_TICKS);
	}

	private static DamageSource holyLightSource(ServerLevel level, ServerPlayer attacker) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, SummyReliquary.id("holy_light"));
		// 兜底：数据包缺失（例如手动删掉了 damage_type 文件）时退回原版魔法伤害，避免抛异常
		var holder = registry.getHolder(key).orElse(null);
		if (holder == null) {
			return level.damageSources().magic();
		}
		return new DamageSource(holder, attacker, attacker);
	}

	/** 掷点：自检可用 {@link #setForcedRoll} 强制结果 */
	private static boolean rollPercent(ServerPlayer attacker, int chancePercent) {
		if (forcedRoll != null) {
			return forcedRoll;
		}
		// 1.6.10：神性 + 圣光同时佩戴时用联动几率（配置见 [holy_light]）
		return attacker.getRandom().nextFloat() * 100.0F < chancePercent;
	}

	// ==================== 光柱与爆发 ====================

	private static void spawnColumn(ServerLevel level, LivingEntity target, long endTick) {
		Vec3 from = columnTop(target);
		Vec3 to = columnBottom(target);
		drawColumn(level, from, to);
		// 爆发：与救恩完全相同的"8 颗环绕 + 1 颗 FLASH"，位置改到目标碰撞箱底部
		spawnBurst(level, to);
		COLUMNS.add(new Column(level, target.getUUID(), from, to, endTick));
	}

	/** 光柱起点：目标碰撞箱顶部 + 7 格 */
	private static Vec3 columnTop(LivingEntity target) {
		return new Vec3(target.getX(), target.getBoundingBox().maxY + COLUMN_TOP_LIFT, target.getZ());
	}

	/** 光柱终点：目标碰撞箱底部 */
	private static Vec3 columnBottom(LivingEntity target) {
		return new Vec3(target.getX(), target.getBoundingBox().minY, target.getZ());
	}

	private static void drawColumn(ServerLevel level, Vec3 from, Vec3 to) {
		int steps = (int) Math.max(1.0D, from.distanceTo(to) / COLUMN_STEP);
		for (int index = 0; index <= steps; index++) {
			Vec3 point = from.lerp(to, index / (double) steps);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z,
					COLUMN_PARTICLES_PER_POINT, 0.02D, 0.02D, 0.02D, 0.0D);
		}
	}

	private static void spawnBurst(ServerLevel level, Vec3 center) {
		for (int index = 0; index < BURST_RING; index++) {
			double angle = Math.PI * 2.0D * index / BURST_RING;
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x + Math.cos(angle) * 0.6D, center.y,
					center.z + Math.sin(angle) * 0.6D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		level.sendParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
	}

	/** 重画还没过期的光柱；目标还活着就按当前碰撞箱重算两端 */
	private static void redraw(long nowTick) {
		if (COLUMNS.isEmpty()) {
			return;
		}
		COLUMNS.removeIf(column -> {
			if (nowTick > column.endTick) {
				return true;
			}
			Entity entity = column.level.getEntity(column.target);
			if (entity instanceof LivingEntity living && living.isAlive()) {
				column.from = columnTop(living);
				column.to = columnBottom(living);
			}
			drawColumn(column.level, column.from, column.to);
			return false;
		});
	}

	// ==================== 工具 ====================

	/** 只认近战与箭矢/投掷物：返回"这次攻击的玩家"，否则 null */
	private static ServerPlayer attackerOf(DamageSource source) {
		Entity direct = source.getDirectEntity();
		if (direct instanceof ServerPlayer player) {
			return player;
		}
		if (direct instanceof AbstractArrow arrow && arrow.getOwner() instanceof ServerPlayer player) {
			return player;
		}
		return null;
	}

	/**
	 * 是否是本模组的**特效伤害**（圣光 / 启示之光 / 黑心碎裂 / 狱火 / 恶魔之焰）：不参与记录、不吃七罪七德与最终倍率、
	 * 也不触发契约的生命吸取，避免"自家特效被当成玩家近战命中"重复结算。
	 */
	public static boolean isDivine(DamageSource source) {
		String id = source.getMsgId();
		return HOLY_LIGHT_ID.equals(id) || HOLY_LIGHT_BURST_ID.equals(id) || GOLDEN_RAZOR_ID.equals(id)
				|| REVELATION_ID.equals(id) || PACT_SHATTER_ID.equals(id) || HELLFIRE_ID.equals(id)
				|| DEMON_FLAME_ID.equals(id) || ABYSS_AURA_ID.equals(id);
	}

	/**
	 * 1.6.4：**金额必须分毫不差**的本模组真伤（启示之光 / 神性光环 / 献祭 / 恶魔之焰）。
	 *
	 * <p>这些伤害的数值由配置直接给定（7 点 / 2 点 / 10000 点 / 6 点），所以不允许被
	 * 「伯列恒之星：造成伤害 +20%」这类**加伤**改写。1.6.3 之前是靠启示之光的"事后校正"顺带盖住的，
	 * 1.6.4 改成伤害标签后必须显式排除，否则光柱会变成 8.4 点/次。
	 */
	public static boolean isExactDamage(DamageSource source) {
		String id = source.getMsgId();
		return HOLY_LIGHT_BURST_ID.equals(id) || GOLDEN_RAZOR_ID.equals(id) || REVELATION_ID.equals(id)
				|| GODHEAD_AURA_ID.equals(id) || SACRIFICE_ID.equals(id) || DEMON_FLAME_ID.equals(id)
				|| ABYSS_AURA_ID.equals(id);
	}

	/**
	 * 1.6.7：是否是「狱火」伤害。
	 *
	 * <p>深渊领主给目标叠狱火时要用它排除"狱火每秒伤害自己"——否则每次每秒结算都会再叠一层，
	 * 目标会在几秒内被动升到满级。
	 */
	public static boolean isHellfire(DamageSource source) {
		return source != null && HELLFIRE_ID.equals(source.getMsgId());
	}

	private static long now(MinecraftServer server) {
		return server == null ? 0L : server.getTickCount();
	}

	// ==================== 自检接口 ====================

	/** 自检用：强制掷点结果（null = 恢复正常随机） */
	public static void setForcedRoll(Boolean value) {
		forcedRoll = value;
	}

	/** 自检用：命中次数 */
	public static int triggerCount() {
		return triggerCount;
	}

	/** 自检用：最近一次落地的圣光伤害 */
	public static float lastDamage() {
		return lastDamage;
	}

	/** 自检用：当前还没过期的光柱条数 */
	public static int activeColumns() {
		return COLUMNS.size();
	}

	/** 自检用：清掉记录、光柱与统计 */
	public static void reset() {
		PENDING.clear();
		COLUMNS.clear();
		triggerCount = 0;
		lastDamage = 0.0F;
		forcedRoll = null;
	}

	/** 自检用：只清掉还在重画的光柱（大量采样时避免堆积） */
	public static void clearColumns() {
		COLUMNS.clear();
	}

	/** 服务端停止时清掉残留 */
	public static void clear() {
		PENDING.clear();
		COLUMNS.clear();
	}

	// 常量访问（自检断言用）
	public static int columnLingerTicks() {
		return COLUMN_LINGER_TICKS;
	}

	public static int columnParticlesPerPoint() {
		return COLUMN_PARTICLES_PER_POINT;
	}

	public static int burstRing() {
		return BURST_RING;
	}
}
