package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 神圣裁决单次伤害事务诊断。
 *
 * <p>本类只读取事件和生命值，不取消事件、不改伤害金额。每个 R 目标与每次被动附伤各建立一个事务，
 * 用来区分“顶层调用一次但内部重复 hurt 事件”和“破限伤补差再次 setHealth”两种情况。
 */
public final class SacredVerdictTrace {
	private static final ThreadLocal<Deque<Trace>> ACTIVE =
			ThreadLocal.withInitial(ArrayDeque::new);

	private SacredVerdictTrace() {
	}

	public static void begin(String kind, long traceId, ServerPlayer attacker,
			LivingEntity target, String damageType, float baseDamage) {
		ACTIVE.get().push(new Trace(kind, traceId, attacker, target, damageType, baseDamage));
	}

	public static void onHurt(LivingIncomingDamageEvent event) {
		Trace trace = currentFor(event.getEntity());
		if (trace != null) {
			trace.hurt(event);
		}
	}

	public static void onDamage(LivingDamageEvent.Pre event) {
		Trace trace = currentFor(event.getEntity());
		if (trace != null) {
			trace.damage(event);
		}
	}

	public static void recordHurtResult(LivingEntity target, boolean landed, float lostAfterHurt) {
		Trace trace = current();
		if (trace != null) {
			trace.landed = landed;
			trace.lostAfterHurt = lostAfterHurt;
			trace.healthAfterHurt = target.getHealth();
			trace.absorptionAfterHurt = target.getAbsorptionAmount();
		}
	}

	public static void recordIntended(float intended, float lostBeforeTopUp) {
		Trace trace = current();
		if (trace != null) {
			trace.intended = intended;
			trace.lostBeforeTopUp = lostBeforeTopUp;
		}
	}

	public static void recordTopUpStart(LivingEntity target) {
		Trace trace = current();
		if (trace != null) {
			trace.topUpRan = true;
			trace.topUpHealthBefore = target.getHealth();
			trace.topUpAbsorptionBefore = target.getAbsorptionAmount();
		}
	}

	public static void recordTopUpEnd(LivingEntity target, float supplemented) {
		Trace trace = current();
		if (trace != null) {
			trace.topUpHealthAfter = target.getHealth();
			trace.topUpAbsorptionAfter = target.getAbsorptionAmount();
			trace.supplemented = supplemented;
		}
	}

	public static void finish() {
		Deque<Trace> traces = ACTIVE.get();
		Trace trace = traces.poll();
		if (traces.isEmpty()) {
			ACTIVE.remove();
		}
		if (trace != null) {
			trace.logSummary();
		}
	}

	private static Trace current() {
		Deque<Trace> traces = ACTIVE.get();
		return traces.isEmpty() ? null : traces.peek();
	}

	private static Trace currentFor(LivingEntity target) {
		Deque<Trace> traces = ACTIVE.get();
		for (Trace trace : traces) {
			if (trace.targetUuid.equals(target.getUUID())) {
				return trace;
			}
		}
		return null;
	}

	private static String sourceType(DamageSource source) {
		String id = HolyLightEffect.typeId(source);
		return id.isEmpty() ? source.getMsgId() : id;
	}

	private static String entityName(Entity entity) {
		return entity == null ? "无"
				: entity.getType() + "#" + entity.getUUID();
	}

	private static final class Trace {
		private final String kind;
		private final long id;
		private final String attacker;
		private final UUID targetUuid;
		private final String targetName;
		private final String targetClass;
		private final String damageType;
		private final float maxHealth;
		private final float healthBefore;
		private final float absorptionBefore;
		private final long tick;
		private final List<String> hurtEvents = new ArrayList<>();
		private final List<String> damageEvents = new ArrayList<>();
		private boolean landed;
		private float lostAfterHurt;
		private float healthAfterHurt;
		private float absorptionAfterHurt;
		private float intended;
		private float lostBeforeTopUp;
		private float topUpHealthBefore = Float.NaN;
		private float topUpAbsorptionBefore = Float.NaN;
		private float topUpHealthAfter = Float.NaN;
		private float topUpAbsorptionAfter = Float.NaN;
		private float supplemented;
		private boolean topUpRan;

		private Trace(String kind, long id, ServerPlayer attacker, LivingEntity target,
				String damageType, float baseDamage) {
			this.kind = kind;
			this.id = id;
			this.attacker = attacker.getName().getString();
			this.targetUuid = target.getUUID();
			this.targetName = target.getName().getString();
			this.targetClass = target.getClass().getName();
			this.damageType = damageType;
			this.maxHealth = target.getMaxHealth();
			this.healthBefore = target.getHealth();
			this.absorptionBefore = target.getAbsorptionAmount();
			this.healthAfterHurt = this.healthBefore;
			this.absorptionAfterHurt = this.absorptionBefore;
			this.tick = target.level().getGameTime();
			this.intended = baseDamage;
		}

		private void hurt(LivingIncomingDamageEvent event) {
			String line = String.format("#%d %s=%.4f 直接来源=%s 取消=%s",
					hurtEvents.size() + 1, sourceType(event.getSource()), event.getAmount(),
					entityName(event.getSource().getDirectEntity()), event.isCanceled());
			hurtEvents.add(line);
			SummyReliquary.LOGGER.info(
					"神圣裁决伤害探针：事务={}#{}、目标={}、阶段=LivingIncomingDamageEvent{}、"
							+ "类型={}、金额={}、直接来源={}、取消={}、tick={}",
					kind, id, targetName, line, sourceType(event.getSource()), event.getAmount(),
					entityName(event.getSource().getDirectEntity()), event.isCanceled(),
					tick);
		}

		private void damage(LivingDamageEvent.Pre event) {
			String line = String.format("#%d %s=%.4f 直接来源=%s 取消=%s",
					damageEvents.size() + 1, sourceType(event.getSource()), event.getNewDamage(),
					entityName(event.getSource().getDirectEntity()), false);
			damageEvents.add(line);
			SummyReliquary.LOGGER.info(
					"神圣裁决伤害探针：事务={}#{}、目标={}、阶段=LivingDamageEvent{}、"
							+ "类型={}、金额={}、直接来源={}、取消={}、tick={}",
					kind, id, targetName, line, sourceType(event.getSource()), event.getNewDamage(),
					entityName(event.getSource().getDirectEntity()), false,
					tick);
		}

		private void logSummary() {
			float finalHealth = topUpRan ? topUpHealthAfter : healthAfterHurt;
			float finalAbsorption = topUpRan ? topUpAbsorptionAfter : absorptionAfterHurt;
			float finalLost = Math.max(0.0F, healthBefore - finalHealth)
					+ Math.max(0.0F, absorptionBefore - finalAbsorption);
			SummyReliquary.LOGGER.info(
					"神圣裁决伤害事务汇总：事务={}#{}、攻击者={}、目标={}、类={}、"
							+ "类型={}、最大生命={}、初始生命={}、初始吸收={}、"
							+ "Hurt事件数={}{}、Damage事件数={}{}、hurt后损失={}、"
							+ "乘区后应得={}、补差前损失={}、补差生命 {}→{}、补差吸收 {}→{}、"
							+ "补差执行={}、补差量={}、最终损失={}、落地={}、tick={}",
					kind, id, attacker, targetName, targetClass, damageType,
					maxHealth, healthBefore, absorptionBefore,
					hurtEvents.size(), hurtEvents,
					damageEvents.size(), damageEvents,
					lostAfterHurt, intended, lostBeforeTopUp,
					topUpHealthBefore, topUpHealthAfter,
					topUpAbsorptionBefore, topUpAbsorptionAfter,
					topUpRan, supplemented, finalLost, landed, tick);
		}
	}
}
