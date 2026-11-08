package com.summy.reliquary.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 心之碎片与碎心共用的目标筛选、概率和不可摧毁掉落生成。 */
public final class RareMaterialDropSupport {
	private RareMaterialDropSupport() {
	}

	public static boolean qualifies(LivingEntity victim) {
		if (victim == null || victim instanceof Player
				|| DummySupport.isTargetDummyEntity(victim)) {
			return false;
		}
		return victim instanceof Enemy
				|| (victim instanceof Mob mob && mob.getTarget() != null);
	}

	public static double chanceFor(LivingEntity victim,
			double normalPercent, double bossPercent, double dragonPercent) {
		if (victim instanceof EnderDragon) {
			return dragonPercent;
		}
		if (victim instanceof WitherBoss || victim instanceof Warden) {
			return bossPercent;
		}
		return normalPercent;
	}

	/** 在死亡位置生成受保护的稀有材料；数量大于 1 时合并为一个掉落堆。 */
	public static ItemEntity spawnProtected(LivingEntity victim, Item item, int count) {
		if (!(victim.level() instanceof ServerLevel level) || count <= 0) {
			return null;
		}
		ItemEntity entity = new ItemEntity(level, victim.getX(), victim.getY() + 0.5D, victim.getZ(),
				new ItemStack(item, count));
		entity.setInvulnerable(true);
		level.addFreshEntity(entity);
		return entity;
	}
}
