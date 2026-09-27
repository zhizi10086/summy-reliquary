package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「玄秘魔眼」的恐惧逻辑（1.6.5）。
 *
 * <p>注视判定与「思想：看向的目标也发光」**共用同一套实现**（{@link GazeLook}）：
 * 眼睛出发的射线、最远 {@code [occult_eye] fear_radius} 格、不穿墙、取射线击中的最近一具生物。
 * 每秒一次：给这一具施加/刷新 {@code fear_seconds} 秒的「恐惧」（发光由 {@code SpiritAltarSet} 维护）；
 * 命中瞬间的 ×{@code fear_damage_multiplier} 加成在 {@code SpiritAltarSet#onLivingHurt} 里判定。
 */
public final class OccultEye {
	/** 「移速归零」修饰符的固定 UUID（亚巴顿联动；等级变化时按它移除/重加） */
	private static final UUID FREEZE_UUID = UUID.fromString("3b7d5c91-2f64-4a8e-9d10-5c6b7a8f9e01");
	/**
	 * 「移速归零」用的 MULTIPLY_TOTAL 值：-1000%。
	 *
	 * <p>取这么大是为了**真正归零** —— 原版速度属性下限是 0，只要总和 ≤ 0 就会被夹到 0；
	 * 即便目标身上有「速度提升」之类的正加成，也仍然会被压到 0（与"无法移动"的语义一致）。
	 */
	private static final double FREEZE_VALUE = -10.0D;

	/** 被"恐惧归零"的目标 → 冻结到期时刻（gameTime） */
	private static final Map<UUID, Long> FREEZE_UNTIL = new HashMap<>();
	/** 自检用：累计施加恐惧的次数 */
	private static int fearCount = 0;

	private OccultEye() {
	}

	/** 是否佩戴着玄秘魔眼（且功能已启用） */
	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableOccultEye()
				&& CurioHelper.wears(entity, SummyReliquary.OCCULT_EYE.get());
	}

	/** 服务端每秒：给"此刻注视到的那一具"施加/刷新恐惧 */
	public static void tickPlayer(ServerPlayer player) {
		if (!wears(player)) {
			return;
		}
		LivingEntity target = GazeLook.lookedAt(player, ReliquaryConfig.fearRadius());
		if (target == null || !canFear(target)) {
			return;
		}
		target.addEffect(new MobEffectInstance(SummyReliquary.FEAR.get(), ReliquaryConfig.fearTicks(),
				0, false, true, true));
		// 1.6.10 联动：亚巴顿 + 玄秘魔眼 → 恐惧期间额外"移速归零"
		if (Synergies.fearFreezes(player)) {
			applyFreeze(target);
			// 与恐惧同寿命：恐惧到期 / 被清掉时由 tickServer 移除
			FREEZE_UNTIL.put(target.getUUID(), target.level().getGameTime() + ReliquaryConfig.fearTicks());
		}
		fearCount++;
	}

	/** 服务端每 tick：清理过期的"恐惧归零"（目标不再带恐惧、时间到、或目标已不存在） */
	public static void tickServer(MinecraftServer server) {
		if (FREEZE_UNTIL.isEmpty()) {
			return;
		}
		var iterator = FREEZE_UNTIL.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			LivingEntity target = findEntity(server, entry.getKey());
			if (target == null) {
				// 实体已经不在（死亡 / 卸载）：临时修饰符会随实体一起消失，这里只需清记录
				iterator.remove();
				continue;
			}
			boolean expired = target.level().getGameTime() > entry.getValue();
			if (expired || !target.hasEffect(SummyReliquary.FEAR.get())) {
				clearFreeze(target);
				iterator.remove();
			}
		}
	}

	/** 给目标挂上"移速归零"（幂等：先移除同 UUID 再重加） */
	private static void applyFreeze(LivingEntity target) {
		AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(FREEZE_UUID);
		speed.addTransientModifier(new AttributeModifier(FREEZE_UUID, "summy_reliquary_fear_freeze",
				FREEZE_VALUE, AttributeModifier.Operation.MULTIPLY_TOTAL));
	}

	/** 移除目标的"移速归零" */
	private static void clearFreeze(LivingEntity target) {
		AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(FREEZE_UUID);
		}
	}

	/** 自检用：该实体当前是否挂着"移速归零"修饰符 */
	public static boolean isFrozen(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		return speed != null && speed.getModifier(FREEZE_UUID) != null;
	}

	/** 按 UUID 在全部维度里找实体（找不到返回 null） */
	private static LivingEntity findEntity(MinecraftServer server, UUID id) {
		for (var level : server.getAllLevels()) {
			var entity = level.getEntity(id);
			if (entity instanceof LivingEntity living) {
				return living;
			}
		}
		return null;
	}

	/**
	 * 恐惧能否作用于该目标：非玩家一律可以；玩家看配置，且**佩戴圣心或神性的玩家免疫恐惧**（1.6.7 起神性同步）。
	 *
	 * <p>注意：这里只影响"恐惧减益"。发光与注视增伤都不受影响（口径与 1.6.5 一致）。
	 */
	public static boolean canFear(LivingEntity target) {
		if (target == null || !(target instanceof Player)) {
			return target != null;
		}
		if (!ReliquaryConfig.fearAffectsPlayers()) {
			return false;
		}
		return !DivineImmunity.immune(target);
	}

	/** 自检用：累计施加次数 */
	public static int fearCount() {
		return fearCount;
	}

	/** 自检用：清空计数 */
	public static void reset() {
		fearCount = 0;
	}

	/** 自检用：把某个目标身上的"移速归零"撤掉（并清掉它的到期记录） */
	public static void clearFreezeForTest(LivingEntity target) {
		if (target == null) {
			return;
		}
		clearFreeze(target);
		FREEZE_UNTIL.remove(target.getUUID());
	}
}
