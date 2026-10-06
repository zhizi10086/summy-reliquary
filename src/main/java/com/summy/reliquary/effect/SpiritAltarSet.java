package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「灵台」套装效果。
 *
 * <ul>
 *     <li>肉体：最大生命 +10 / 套装 +20（属性修正由物品类提供）；</li>
 *     <li>思想：24 格内敌对生物与玩家发光（视线正对的那一具同样点亮）；套装时对发光目标 +10% 伤害；</li>
 *     <li>灵魂：+3 魂心（每点 2 点吸收，黄血由 {@link SoulShield} 维护）；套装时 20% 几率免死（见 {@link DeathImmunity}）。</li>
 * </ul>
 *
 * <p>发光用「任意佩戴者覆盖即发光」的并集判定：多名玩家同时佩戴时不会互相把标记清掉。
 */
public final class SpiritAltarSet {
	/** 当前被本模组点亮的目标实体（UUID → 实体，便于清除标记） */
	private static final Map<UUID, Entity> GLOWING = new HashMap<>();

	private SpiritAltarSet() {
	}

	/** 三件套判定 */
	public static boolean isFullSet(LivingEntity entity) {
		return CurioHelper.wearsAll(entity,
				SummyReliquary.THE_BODY.get(),
				SummyReliquary.THE_MIND.get(),
				SummyReliquary.THE_SOUL.get());
	}

	/**
	 * 「套装效果是否生效」（1.6.2）：三件套齐 **或** 佩戴咒印。
	 *
	 * <p>咒印继承天使线灵台套装的全部属性，所以减伤 / 对发光加伤 / 免死都走这个判定；
	 * 但**发放伯列恒之星与「三位一体」进度仍然只认真正的三件套**（见 {@link #isFullSet}），
	 * 恶魔线不该因此拿到天使线的奖励。
	 */
	public static boolean hasSetEffects(LivingEntity entity) {
		return isFullSet(entity) || com.summy.reliquary.effect.SatanicMark.wears(entity);
	}

	/** 服务端每 tick 调用 */
	public static void tickServer(MinecraftServer server) {
		boolean secondPassed = server.getTickCount() % 20 == 0;
		Set<UUID> desired = secondPassed ? new HashSet<>() : null;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// 发光：每秒维护一次
			if (desired != null && (CurioHelper.wears(player, SummyReliquary.THE_MIND.get())
					|| com.summy.reliquary.effect.SatanicMark.wears(player))) {
				double radius = ReliquaryConfig.glowRadius();
				AABB box = player.getBoundingBox().inflate(radius);
				for (Entity entity : player.serverLevel().getEntities(player, box, SpiritAltarSet::isGlowTarget)) {
					desired.add(entity.getUUID());
					GLOWING.put(entity.getUUID(), entity);
				}
				// 额外：视线看向的生物也发光（距离同生效半径，被方块挡住则不算）
				LivingEntity lookedAt = findLookedAt(player, radius);
				if (lookedAt != null) {
					desired.add(lookedAt.getUUID());
					GLOWING.put(lookedAt.getUUID(), lookedAt);
				}
			}
			// 1.6.5：玄秘魔眼——被它注视的那一具也发光（对玩家也生效，可配置）。
			// 与"思想"共用同一份 desired 集合，避免两边互相清除发光标记。
			if (desired != null && ReliquaryConfig.enableOccultEye()
					&& CurioHelper.wears(player, SummyReliquary.OCCULT_EYE.get())
					&& ReliquaryConfig.fearGlow()) {
				LivingEntity eyed = GazeLook.lookedAt(player, ReliquaryConfig.fearRadius());
				if (eyed != null
						&& (ReliquaryConfig.fearGlowAffectsPlayers() || !(eyed instanceof Player))) {
					desired.add(eyed.getUUID());
					GLOWING.put(eyed.getUUID(), eyed);
				}
			}
		}

		if (desired == null) {
			return;
		}

		// 不再需要发光的目标：清除标记
		Iterator<Map.Entry<UUID, Entity>> iterator = GLOWING.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Entity> entry = iterator.next();
			Entity entity = entry.getValue();
			if (!entity.isAlive()) {
				iterator.remove();
				continue;
			}
			if (!desired.contains(entry.getKey())) {
				entity.setGlowingTag(false);
				iterator.remove();
			}
		}
		// 需要发光的目标：打标记
		for (UUID id : desired) {
			Entity entity = GLOWING.get(id);
			if (entity != null && entity.isAlive() && !entity.hasGlowingTag()) {
				entity.setGlowingTag(true);
			}
		}
	}

	/** 发光目标：敌对生物与玩家（不含佩戴者自己） */
	private static boolean isGlowTarget(Entity entity) {
		return entity.isAlive() && (entity instanceof Enemy || entity instanceof Player);
	}

	/**
	 * 视线看向的生物（1.6.5 起改为调用共用的 {@link GazeLook}，与玄秘魔眼同一套判定）：
	 * 最远 distance 格、被方块挡住就不算、对任意生物生效。
	 */
	private static LivingEntity findLookedAt(ServerPlayer player, double distance) {
		return GazeLook.lookedAt(player, distance);
	}

	/**
	 * 战斗结算：套装伤害加成（思想对发光目标 / 玄秘魔眼注视 / 恶魔王冠分档）与肉体减伤。
	 *
	 * <p>1.8.3：伯列恒之星 / 终末天启的「造成伤害 +20%」已并入
	 * {@code ReliquaryEvents.finalDamageMultiplier}（与圣心 / 神性同乘区相加）；
	 * 本层不再处理它，并统一排除**本模组的全部伤害类型**与自伤。
	 */
	public static void onLivingHurt(LivingHurtEvent event) {
		LivingEntity victim = event.getEntity();
		// 1.8.3：自伤（献祭匕首的自损）**完全不吃这一层** —— 既不参与加伤，也不被「肉体」套装减伤缩水，
		// 保证"自损恒为配置值"（否则三件套齐 / 戴咒印时 4 点会先被 ×0.8 变成 3.2）
		if (event.getSource().getEntity() != null && event.getSource().getEntity() == victim) {
			return;
		}

		// 肉体 + 套装：受到的伤害减免
		if (victim instanceof ServerPlayer player && hasSetEffects(player)) {
			double reduction = ReliquaryConfig.bodySetDamageReductionPercent() / 100.0D;
			if (reduction > 0.0D) {
				event.setAmount((float) (event.getAmount() * (1.0D - reduction)));
			}
		}

		Entity attacker = event.getSource().getEntity();
		if (!(attacker instanceof ServerPlayer player)) {
			return;
		}
		// 1.8.3：本模组自己的全部伤害类型都不吃这一层加成。这里必须用**并集**：
		// isDivine 与 isExactDamage 互不包含 —— isDivine 独有 holy_light（圣光）/ pact_shatter（黑心碎裂）/ hellfire（狱火），
		// isExactDamage 独有 godhead_aura（神性光环）/ sacrifice（契约献祭），只用其中一个都会漏掉另一边。
		// 于是圣光只在"主击基准"里继承过一次加伤，不会在本层被重复放大。
		if (com.summy.reliquary.effect.HolyLightEffect.isDivine(event.getSource())
				|| com.summy.reliquary.effect.HolyLightEffect.isExactDamage(event.getSource())) {
			return;
		}

		float amount = event.getAmount();

		// 思想 + 套装：对发光目标额外伤害
		// 1.8.3：改用 isCurrentlyGlowing() —— 服务端等价于「本模组打的标记 ∨ 原版发光效果」，
		// 被光谱箭 / 发光药水照亮的目标也算（不受 24 格限制）
		if (hasSetEffects(player) && victim.isCurrentlyGlowing()) {
			amount *= 1.0F + ReliquaryConfig.mindBonusPercent() / 100.0F;
		}

		// 玄秘魔眼（1.6.5）：对"此刻正被自己注视的那一具"造成伤害 ×1.3
		// —— 与目标身上有没有"恐惧"无关，所以戴圣心的目标只免疫减益、照样吃这个加成
		if (ReliquaryConfig.enableOccultEye()
				&& CurioHelper.wears(player, SummyReliquary.OCCULT_EYE.get())
				&& GazeLook.lookedAt(player, ReliquaryConfig.fearRadius()) == victim) {
			amount *= (float) ReliquaryConfig.fearDamageMultiplier();
		}

		// 恶魔王冠（1.7.1）：邪恶 666 后，按"已损失生命"每 10% 一档 +3%（上限 +15%）
		// —— 与上面几条同层（事件金额、减伤之前）；定值真伤已在上面 return 掉，不会吃这个加成
		double crownBonus = DevilCrown.damageBonusPercent(player);
		if (crownBonus > 0.0D) {
			amount *= 1.0F + (float) (crownBonus / 100.0D);
		}

		event.setAmount(amount);
	}
}
