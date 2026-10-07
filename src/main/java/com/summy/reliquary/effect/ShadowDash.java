package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「遁入暗影」（1.7.5）：献祭匕首 / 暗仪刺刀的右键技能。
 *
 * <p>流程（口径见进度文档）：
 * <ol>
 *     <li><b>ACTIVE</b>（献祭 1 秒 / 暗仪 2 秒）：按下右键那一刻开始无敌与 +100% 移速；
 *     每 tick 扫描玩家 {@code contact_radius}（默认 2 格）内、与神性光环同口径的敌人，
 *     按**首次接触顺序**打标记（同一目标只标一次，用 {@link Set} 去重）；</li>
 *     <li>判定时长一到就进入 <b>RESOLVING</b>：**同时关闭加速**（加速时长＝判定时长），
 *     并一次性锁定每个标记目标的「基础斩击」理论值与它们的总和；</li>
 *     <li>每 {@code resolve_interval_ticks}（默认 2 tick）按标记顺序结算一个目标；
 *     标记表排空后，对 {@code heavy_slash_radius}（默认 3 格）内**所有**敌人释放一次
 *     「强力斩击」，伤害＝锁定的理论总和（不因目标血量不足或实际减免而缩水）；</li>
 *     <li>强力斩击结束才**解除无敌**，并给触发时那把武器加原版冷却（默认 120 tick = 6 秒）。</li>
 * </ol>
 *
 * <p><b>伤害口径</b>：基础斩击＝（玩家 {@code ATTACK_DAMAGE} 属性值〔已含力量/虚弱等药水修饰符〕
 * ＋ 触发时锁定武器的附魔增伤）× 倍率，走 {@code playerAttack} 伤害源 —— 完整享受七罪 / 七德 /
 * 圣心 / 神性 / 契约 / 伯列恒 / 思想 / 王冠 等增伤，也照常吃护甲 / 保护 / 抗性。
 * 强力斩击的数值已经是一次「基础斩击」的汇总，**不再走一遍增伤乘区**（只吃护甲 / 保护 / 抗性）。
 */
public final class ShadowDash {
	/** 技能期间的移速修饰符（避开七罪 21~24 / 七德 31~33 / 幽魂 41 / 王冠 51~56） */
	private static final UUID SPEED_MODIFIER =
			UUID.fromString("6f5c1a2e-0000-4a0b-9d1e-000000000061");

	/** 技能阶段 */
	private enum Phase {
		/** 判定期：无敌 + 加速 + 打标记 */
		ACTIVE,
		/** 结算期：加速已关闭，逐个斩击 */
		RESOLVING
	}

	/** 一次技能的全部状态 */
	private static final class State {
		private final boolean darkArts;
		private final ItemStack weapon;
		/** 1.8.4：技能启动瞬间的玩家坐标（Y 取 +1.0 的胸口高度）—— 判定期玩家高速移动，起点必须是快照 */
		private final Vec3 origin;
		/** 1.8.4：技能启动时的维度（换维度时连线直接跳过，只做安全兜底） */
		private final ResourceKey<Level> dimension;
		/** 1.8.4：斩击轨迹连线的游标 —— 始终指向"上一段线的终点"，初值 = 起点 */
		private Vec3 cursor;
		/** 判定期剩余 tick（加速与无敌都跟它走） */
		private int remaining;
		private Phase phase = Phase.ACTIVE;
		/** 距下一次结算还要等几 tick */
		private int resolveDelay;
		/** 按"首次接触"顺序记录的标记目标 */
		private final List<UUID> order = new ArrayList<>();
		/** 已标记过（不可重复施加） */
		private final Set<UUID> marked = new HashSet<>();
		/** 1.7.10：本次技能里"狱火是标记时自己加的"目标（斩击结算后只退自己那 1 级） */
		private final Set<UUID> hellfireAdded = new HashSet<>();
		/** 每个标记目标锁定的基础斩击理论值（进入结算时一次性算好） */
		private final Map<UUID, Float> values = new LinkedHashMap<>();
		/** 强力斩击的伤害＝上述理论值之和 */
		private float heavyDamage;

		private State(boolean darkArts, ItemStack weapon, int durationTicks, Vec3 origin,
				ResourceKey<Level> dimension) {
			this.darkArts = darkArts;
			this.weapon = weapon;
			this.remaining = Math.max(1, durationTicks);
			this.origin = origin;
			this.dimension = dimension;
			this.cursor = origin;
		}
	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();
	/** 强力斩击期间 > 0：跳过所有增伤乘区（服务端同线程同步调用，用计数器即可） */
	private static int heavyDepth;

	private ShadowDash() {
	}

	// ==================== 触发 ====================

	/**
	 * 按下右键触发技能。
	 *
	 * @param darkArts true = 暗仪刺刀（2 秒 / 2 倍），false = 献祭匕首（1 秒 / 1 倍）
	 * @return true 表示这次真的开始了技能
	 */
	public static boolean tryStart(ServerPlayer player, ItemStack weapon, boolean darkArts) {
		if (player == null || !ReliquaryConfig.enableShadowDash()) {
			return false;
		}
		// 1.7.9：门槛统一（献祭匕首 = 恶魔标记，暗仪刺刀 = 恶魔标记 + 邪恶 700）——
		// 未达标时整件武器完全禁用，技能连起手都不允许。
		if (weapon != null && !WeaponGates.qualified(player, weapon.getItem())) {
			return false;
		}
		if (ACTIVE.containsKey(player.getUUID())) {
			return false;
		}
		if (player.getCooldowns().isOnCooldown(weapon.getItem())) {
			return false;
		}
		ACTIVE.put(player.getUUID(), new State(darkArts, weapon.copy(),
				// 1.7.10：佩戴亚巴顿时技能时长 +1 秒（配置项）
				Synergies.shadowDashDurationTicks(player, darkArts),
				// 1.8.4：起点快照 = 按下右键那一刻的玩家坐标（胸口高度）
				new Vec3(player.getX(), player.getY() + 1.0D, player.getZ()),
				player.level().dimension()));
		// 技能期间禁用 V 键射线：开始时就打断可能在进行的蓄力
		RevelationBeam.setCharging(player, false);
		AttributeManager.applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER,
				"shadow_dash_movement", ReliquaryConfig.shadowDashMovementPercent() / 100.0D,
				AttributeModifier.Operation.MULTIPLY_TOTAL);
		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 0.2D, player.getZ(),
				24, 0.4D, 0.1D, 0.4D, 0.02D);
		return true;
	}

	/** 技能进行中（= 无敌期） */
	public static boolean isInvulnerable(LivingEntity entity) {
		return entity != null && ACTIVE.containsKey(entity.getUUID());
	}

	/** 强力斩击期间：让伤害跳过所有增伤乘区 */
	public static boolean suppressOutgoingBonus() {
		return heavyDepth > 0;
	}

	// ==================== 每 tick 推进 ====================

	/** 服务端每 tick：推进所有技能 */
	public static void tickServer(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, State>> iterator = ACTIVE.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, State> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}
			State state = entry.getValue();
			// 技能途中死亡：直接收尾（照常写冷却，避免用死亡重置技能）
			if (!player.isAlive()) {
				iterator.remove();
				finish(player, state);
				continue;
			}
			// 无敌期间（判定期 + 结算期）持续散发黑色粒子
			spawnAuraParticles(player);
			if (state.phase == Phase.ACTIVE) {
				collectMarks(player, state);
				if (--state.remaining <= 0) {
					beginResolving(player, state);
				}
				continue;
			}
			if (state.resolveDelay > 0) {
				state.resolveDelay--;
				continue;
			}
			if (!state.order.isEmpty()) {
				UUID targetId = state.order.remove(0);
				LivingEntity target = findTarget(player, targetId);
				if (target != null && target.isAlive()) {
					strike(player, target, state.values.getOrDefault(targetId, state.heavyDamage));
					// 1.8.4：按斩击顺序把上一节点连到本目标（墨汁连线，纯表现）
					connectLine(player.serverLevel(), state, targetChest(target));
				}
				// 1.7.10：斩击结算后，只退掉"标记时自己加的那一层"狱火
				if (state.hellfireAdded.remove(targetId) && target != null) {
					AbyssLord.removeOneStack(target);
				}
				state.resolveDelay = Math.max(0, ReliquaryConfig.shadowDashResolveIntervalTicks() - 1);
				continue;
			}
			heavySlash(player, state);
			iterator.remove();
			finish(player, state);
		}
	}

	/** 判定期：把接触到的敌人按顺序打标记 */
	private static void collectMarks(ServerPlayer player, State state) {
		ServerLevel level = player.serverLevel();
		// 1.7.10：佩戴亚巴顿时接触半径 +1（配置项）
		double radius = Synergies.shadowDashContactRadius(player);
		// 1.7.10：佩戴深渊领主时，接触标记会给目标挂 1 层狱火（斩击后只退自己那 1 层）
		boolean hellfireOnMark = AbyssLord.wears(player);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius), entity -> Godhead.isAuraTarget(player, entity))) {
			if (target.distanceTo(player) > radius) {
				continue;
			}
			// 不可重复施加：同一个目标只记一次
			if (state.marked.add(target.getUUID())) {
				state.order.add(target.getUUID());
				if (hellfireOnMark) {
					AbyssLord.applyStack(target, player);
					state.hellfireAdded.add(target.getUUID());
				}
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
						target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(),
						8, 0.2D, 0.3D, 0.2D, 0.01D);
			}
		}
	}

	/** 判定期结束：关闭加速、锁定每个标记目标的基础斩击理论值与总和 */
	private static void beginResolving(ServerPlayer player, State state) {
		state.phase = Phase.RESOLVING;
		state.resolveDelay = 0;
		// 加速时长与技能判定时长相同 —— 开始结算就关闭加速
		AttributeManager.applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER,
				"shadow_dash_movement", 0.0D, AttributeModifier.Operation.MULTIPLY_TOTAL);
		float total = 0.0F;
		for (UUID id : state.order) {
			float value = slashValue(player, state, findTarget(player, id));
			state.values.put(id, value);
			total += value;
		}
		state.heavyDamage = total;
	}

	/**
	 * 一次「基础斩击」的理论值＝（玩家攻击力属性 ＋ 触发时武器对该目标类型的附魔增伤）× 倍率。
	 *
	 * <p>1.20.1 的力量 / 虚弱是属性修饰符，已经包含在 {@code ATTACK_DAMAGE} 里；饰品 / 契约等增伤
	 * 由 {@code LivingHurtEvent} 在真正结算时叠加 —— 所以这里算出的就是"该打多少"的理论值。
	 */
	private static float slashValue(ServerPlayer player, State state, LivingEntity target) {
		double base = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		if (target != null && state.weapon != null) {
			base += EnchantmentHelper.getDamageBonus(state.weapon, target.getMobType());
		}
		return (float) Math.max(0.0D, base * ReliquaryConfig.shadowDashMultiplier(state.darkArts));
	}

	/** 对单个标记目标结算一次基础斩击（完整近战伤害：吃增伤，也吃护甲 / 保护 / 抗性） */
	private static void strike(ServerPlayer player, LivingEntity target, float damage) {
		if (damage <= 0.0F) {
			return;
		}
		target.hurt(player.damageSources().playerAttack(player), damage);
		ServerLevel level = player.serverLevel();
		level.sendParticles(ParticleTypes.SWEEP_ATTACK,
				target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		level.playSound(null, target.getX(), target.getY(), target.getZ(),
				SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	/** 强力斩击：半径内所有敌人吃一次"理论总和"，但不再走增伤乘区 */
	private static void heavySlash(ServerPlayer player, State state) {
		ServerLevel level = player.serverLevel();
		// 1.7.10：佩戴亚巴顿时强力斩击半径 +2（配置项）
		double radius = Synergies.shadowDashHeavyRadius(player);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius), entity -> Godhead.isAuraTarget(player, entity))) {
			if (target.distanceTo(player) > radius) {
				continue;
			}
			if (state.heavyDamage > 0.0F) {
				heavyDepth++;
				try {
					target.hurt(player.damageSources().playerAttack(player), state.heavyDamage);
				} finally {
					heavyDepth--;
				}
			}
			level.sendParticles(ParticleTypes.SWEEP_ATTACK,
					target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.8F);
		// 1.7.6：暗影爆发（纯表现）—— 施法音 + 两圈灵魂火 + 黑烟，刻意不加白色爆炸
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.7F);
		soulRing(level, player, radius, 32);
		soulRing(level, player, Math.max(1.0D, radius - 0.8D), 24);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 0.3D, player.getZ(),
				36, radius * 0.8D, 0.3D, radius * 0.8D, 0.05D);
		// 1.8.4：斩击轨迹收口 —— 从上一节点连到玩家（强力斩击释放点）
		// 未标记任何目标时游标仍停在起点，于是自然退化为「起点 → 玩家」的一段直线
		connectLine(level, state, new Vec3(player.getX(), player.getY() + 1.0D, player.getZ()));
		// 1.7.10：强化斩击触发「咒印的黑心爆发伤害」（门槛＝佩戴咒印；不动黑心池与补满计时）
		DemonPact.triggerShatterDamage(player);
	}

	// ==================== 1.8.4 斩击轨迹连线（纯表现） ====================

	/** 目标的胸口坐标（与既有斩击特效同高：脚下 + 0.5 × 身高） */
	private static Vec3 targetChest(LivingEntity target) {
		return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
	}

	/**
	 * 把连线游标推进到新节点，并沿途铺一串墨汁粒子。
	 *
	 * <p>逐段随斩击推进：每次基础斩击画「上一点 → 该目标」，强力斩击时补最后一段连到玩家。
	 * 每段只发一遍粒子、随后自然消散，**不做每 tick 重绘**。
	 *
	 * <p>与技能启动不同的维度直接跳过（技能途中换维度属极端情形，只做安全兜底）；
	 * 游标照常推进，避免后续段落跨维度跳变。
	 */
	private static void connectLine(ServerLevel level, State state, Vec3 to) {
		Vec3 from = state.cursor;
		state.cursor = to;
		if (level == null || !level.dimension().equals(state.dimension)) {
			return;
		}
		drawInkLine(level, from, to);
	}

	/**
	 * 沿两点之间等距铺「墨汁」粒子。
	 *
	 * <p>步长 0.4 格、单段点数钳制在 2~64 之间：超长距离自动稀释，避免粒子风暴。
	 */
	private static void drawInkLine(ServerLevel level, Vec3 from, Vec3 to) {
		double distance = from.distanceTo(to);
		int points = Math.max(2, Math.min(64, (int) Math.round(distance / 0.4D) + 1));
		for (int index = 0; index < points; index++) {
			double ratio = (double) index / (double) (points - 1);
			Vec3 point = from.lerp(to, ratio);
			level.sendParticles(ParticleTypes.SQUID_INK, point.x, point.y, point.z,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/** 在玩家周围沿一个圆环逐点发灵魂火（像爆发一样贴在半径上） */
	private static void soulRing(ServerLevel level, ServerPlayer player, double radius, int points) {
		for (int index = 0; index < points; index++) {
			double angle = Math.PI * 2.0D * index / points;
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
					player.getX() + Math.cos(angle) * radius,
					player.getY() + 0.25D,
					player.getZ() + Math.sin(angle) * radius,
					1, 0.0D, 0.0D, 0.0D, 0.01D);
		}
	}

	/** 收尾：关加速、解除无敌（从 ACTIVE 移除由调用方做）、写冷却 */
	private static void finish(ServerPlayer player, State state) {
		AttributeManager.applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER,
				"shadow_dash_movement", 0.0D, AttributeModifier.Operation.MULTIPLY_TOTAL);
		if (state.weapon != null) {
			player.getCooldowns().addCooldown(state.weapon.getItem(),
					ReliquaryConfig.shadowDashCooldownTicks());
		}
	}

	// ==================== 辅助 ====================

	private static LivingEntity findTarget(ServerPlayer player, UUID id) {
		var entity = player.serverLevel().getEntity(id);
		return entity instanceof LivingEntity living ? living : null;
	}

	/** 判定期每 tick 的"暗影"表现 */
	private static void spawnAuraParticles(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0D, player.getZ(),
				3, 0.35D, 0.5D, 0.35D, 0.01D);
	}

	/** 登出 / 清理：撤掉技能与该玩家的一切痕迹 */
	public static void forget(ServerPlayer player) {
		if (player == null) {
			return;
		}
		State state = ACTIVE.remove(player.getUUID());
		if (state != null) {
			AttributeManager.applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER,
					"shadow_dash_movement", 0.0D, AttributeModifier.Operation.MULTIPLY_TOTAL);
		}
	}

	/** 服务器停止：清空全部状态 */
	public static void clear() {
		ACTIVE.clear();
		heavyDepth = 0;
	}

	// ==================== 自检接口 ====================

	/** 技能是否进行中 */
	public static boolean isActive(ServerPlayer player) {
		return player != null && ACTIVE.containsKey(player.getUUID());
	}

	/** 是否已经进入结算期 */
	public static boolean isResolving(ServerPlayer player) {
		State state = player == null ? null : ACTIVE.get(player.getUUID());
		return state != null && state.phase == Phase.RESOLVING;
	}

	/** 当前标记数量 */
	public static int markCount(ServerPlayer player) {
		State state = player == null ? null : ACTIVE.get(player.getUUID());
		return state == null ? 0 : state.marked.size();
	}

	/** 锁定的强力斩击伤害（0 = 还没进入结算） */
	public static float heavyDamage(ServerPlayer player) {
		State state = player == null ? null : ACTIVE.get(player.getUUID());
		return state == null ? 0.0F : state.heavyDamage;
	}

	/** 自检用：当前的移速加成百分比（读属性修饰符） */
	public static double movementBonus(ServerPlayer player) {
		var instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (instance == null) {
			return 0.0D;
		}
		var modifier = instance.getModifier(SPEED_MODIFIER);
		return modifier == null ? 0.0D : modifier.getAmount() * 100.0D;
	}

}
