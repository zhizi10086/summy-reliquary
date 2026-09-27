package com.summy.reliquary.entity;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.Godhead;
import com.summy.reliquary.effect.RevelationBeam;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 天使线长矛的"幻影矛"（1.7.8）。
 *
 * <p>本体始终留在玩家物品栏里（投掷只扣耐久），飞出去的是这个实体 —— 架构照搬
 * ER（enchantment-reforged）的「忠诚改版」：
 * <ul>
 *     <li>拾取权限恒为 {@code DISALLOWED}、{@code getPickupItem()} 返回空栈 →
 *     主人死亡 / 卡进方块 / 区块重载这些分支只会让它消失，**不会复制出一把真矛**；</li>
 *     <li>命中或落地后**原地停留 {@link #STUCK_TICKS}（1 秒）再删除**，全程不往背包塞任何东西
 *     （1.7.9 起不再飞回主人：命中即开始下一轮投掷的倒计时）；</li>
 *     <li>自管理飞行计数，超过 {@link #MAX_LIFE_TICKS} 强制清除，避免孤儿实体永久堆积。</li>
 * </ul>
 *
 * <p><b>伤害口径</b>：命中用 {@code owner.damageSources().playerAttack(owner)} —— 与左键近战**完全同一个伤害源**，
 * 所以本模组判定近战的唯一条件（{@code source.getDirectEntity() == attacker}）成立：
 * 傲慢 / 贪婪等近战专属增伤、五芒星·法袍·王冠的近战属性、以及所有不区分近战远程的乘区全部照常生效。
 *
 * <p><b>1.7.9 的落点圣光爆发</b>：命中目标结算完面板近战伤害后，以落点为中心
 * {@code [holy_light] holy_light_burst_radius}（默认 4 格）内、与神性光环同口径的每个敌人
 * 各吃 {@code holy_light_burst_damage}（默认 14 点）**圣光真伤**：不吃任何倍率、无视无敌帧，
 * 也不吃护甲 / 保护 / 抗性（走独立的 {@code holy_light_burst} 伤害标签，
 * 因此既定的"圣光 proc 仍会被抗性削减"口径不受影响）。命中方块（落地）同样触发。
 */
public class ThrownSpear extends AbstractArrow {
	/** 命中 / 落地后停留多久（tick），到点直接删除 */
	private static final int STUCK_TICKS = 20;
	/** 投掷冷却与"拦截再次投掷"的时间上限（30 秒，照抄 ER 的 PHANTOM_BLOCK_TICKS） */
	public static final int THROW_BLOCK_TICKS = 600;
	/** 幻影总寿命（60 秒，照抄 ER 的 PHANTOM_CLEANUP_TICKS） */
	public static final int MAX_LIFE_TICKS = 1200;

	/** 渲染用的物品堆栈（同步到客户端） */
	private static final EntityDataAccessor<ItemStack> DATA_ITEM =
			SynchedEntityData.defineId(ThrownSpear.class, EntityDataSerializers.ITEM_STACK);

	/** 投掷瞬间锁定的"面板攻击力"（不含按目标类型计算的附魔，那部分命中时再补） */
	private float lockedAttackDamage;
	/** 飞行 / 存活计数 */
	private int flightTicks;
	/** 已经命中（实体或方块） */
	private boolean landed;
	/** 停留计数 */
	private int stuckTicks;
	/** 落点圣光爆发是否已经放过了（避免实体 / 方块双触发） */
	private boolean burstDone;

	public ThrownSpear(EntityType<? extends ThrownSpear> type, Level level) {
		super(type, level);
	}

	public ThrownSpear(Level level, LivingEntity shooter, ItemStack stack, float attackDamage) {
		super(SummyReliquary.THROWN_SPEAR.get(), shooter, level);
		this.lockedAttackDamage = attackDamage;
		setDisplayStack(stack.copy());
		// 本体没被拿走 → 幻影一律不可拾取（避免任何"复制一把"的分支）
		this.pickup = Pickup.DISALLOWED;
	}

	// ==================== 同步数据 ====================

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(DATA_ITEM, ItemStack.EMPTY);
	}

	/** 渲染与附魔计算用的物品堆栈 */
	public ItemStack getDisplayStack() {
		return this.entityData.get(DATA_ITEM);
	}

	private void setDisplayStack(ItemStack stack) {
		this.entityData.set(DATA_ITEM, stack);
	}

	/** 调整过的面板攻击力（投掷时锁定） */
	public float getLockedAttackDamage() {
		return lockedAttackDamage;
	}

	@Override
	protected ItemStack getPickupItem() {
		// 不可拾取（本体还在玩家身上）
		return ItemStack.EMPTY;
	}

	/** 幻影是否可被拾取 —— 恒为 false（自检与外部判断用） */
	public boolean isPickupAllowed() {
		return this.pickup != Pickup.DISALLOWED && !getPickupItem().isEmpty();
	}

	// ==================== 命中 ====================

	@Override
	protected void onHitEntity(EntityHitResult result) {
		Entity target = result.getEntity();
		Entity owner = getOwner();
		DamageSource source = null;
		if (owner instanceof Player player) {
			// 与左键近战**同一个伤害源**：本模组判定近战的唯一条件（directEntity == attacker）成立
			source = player.damageSources().playerAttack(player);
		} else if (owner instanceof LivingEntity living) {
			// 理论上不会走到这里（幻影只由玩家投出），保险起见退回普通投掷物源
			source = living.damageSources().thrown(this, living);
		}
		if (source != null) {
			float damage = lockedAttackDamage
					+ EnchantmentHelper.getDamageBonus(getDisplayStack(), target instanceof LivingEntity livingTarget
							? livingTarget.getMobType() : net.minecraft.world.entity.MobType.UNDEFINED);
			if (damage > 0.0F) {
				target.hurt(source, damage);
			}
		}
		markLanded();
		playSound(SoundEvents.TRIDENT_HIT, 1.0F, 1.0F);
		holyBurst(result.getLocation());
	}

	@Override
	protected void onHitBlock(BlockHitResult result) {
		super.onHitBlock(result);
		markLanded();
		playSound(SoundEvents.TRIDENT_HIT_GROUND, 1.0F, 1.0F);
		holyBurst(result.getLocation());
	}

	private void markLanded() {
		this.landed = true;
		this.setDeltaMovement(Vec3.ZERO);
		this.setNoGravity(true);
	}

	// ==================== 每 tick ====================

	@Override
	public void tick() {
		if (landed) {
			// 命中 / 落地后原地停留 1 秒：位置已经冻住，不再走原版的箭矢逻辑
			setDeltaMovement(Vec3.ZERO);
			if (++stuckTicks >= STUCK_TICKS) {
				discard();
			}
			return;
		}
		super.tick();
		if (++flightTicks > MAX_LIFE_TICKS) {
			discard();
		}
	}

	/**
	 * 落点圣光爆发（1.7.9）：白色爆发粒子 + 中心光点 + 爆炸音效，
	 * 并对半径内与神性光环同口径的敌人各打一次圣光真伤。
	 */
	private void holyBurst(Vec3 center) {
		if (burstDone || center == null) {
			return;
		}
		burstDone = true;
		if (!(level() instanceof ServerLevel serverLevel) || !(getOwner() instanceof ServerPlayer caster)) {
			return;
		}
		// 表现：白色爆发（EXPLOSION）+ 中心光点（END_ROD）+ 爆炸音效
		serverLevel.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.2D, center.z, 1,
				0.0D, 0.0D, 0.0D, 0.0D);
		serverLevel.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.5D, center.z, 24,
				0.35D, 0.35D, 0.35D, 0.05D);
		serverLevel.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE,
				SoundSource.PLAYERS, 1.0F, 1.2F);
		// 1.7.10 联动：佩戴神性时 伤害 +2、半径 +1（取值统一走 Synergies）
		float damage = (float) com.summy.reliquary.effect.Synergies.holyBurstDamage(caster);
		if (damage <= 0.0F) {
			return;
		}
		double radius = com.summy.reliquary.effect.Synergies.holyBurstRadius(caster);
		AABB area = new AABB(center, center).inflate(radius);
		for (LivingEntity target : serverLevel.getEntitiesOfClass(LivingEntity.class, area,
				entity -> Godhead.isAuraTarget(caster, entity))) {
			if (target.getBoundingBox().distanceToSqr(center) > radius * radius) {
				continue;
			}
			// 圣光真伤：不吃倍率、无视无敌帧（applyTrueDamage 会先把 invulnerableTime 清零）
			// 独立的真伤类型：bypasses_armor / effects / enchantments / resistance 全开
			RevelationBeam.applyTrueDamage(serverLevel, caster, target, damage, "holy_light_burst", true);
		}
	}

	// ==================== 自检接口 ====================

	/** 自检用：把幻影标记成"已落地"（此后每 tick 计数，满 20 tick 自动删除） */
	public void markLandedForTest() {
		markLanded();
	}

	/** 自检用：直接触发一次落点圣光爆发 */
	public void burstForTest(Vec3 center) {
		holyBurst(center);
	}

	/** 自检用：是否已经落地 */
	public boolean isLanded() {
		return landed;
	}

	/** 自检用：落点爆发是否已经放过 */
	public boolean isBurstDone() {
		return burstDone;
	}

	/** 自检用：落地后的停留计数 */
	public int stuckTicks() {
		return stuckTicks;
	}

	/**
	 * 幻影消失时，如果主人身边已经没有任何在飞的矛，就把投掷冷却撤掉 ——
	 * 于是"快捷栏冷却圈"的持续时间正好等于"矛在飞的时间"（ER 同款做法）。
	 * 万一幻影因为区块不加载一直不 tick，冷却本身也有 {@link #THROW_BLOCK_TICKS} 的到期兜底。
	 */
	@Override
	public void remove(RemovalReason reason) {
		if (!level().isClientSide && getOwner() instanceof ServerPlayer owner && !getDisplayStack().isEmpty()) {
			boolean others = !level().getEntitiesOfClass(ThrownSpear.class,
					owner.getBoundingBox().inflate(64.0D),
					other -> other != this && !other.isRemoved() && owner.equals(other.getOwner())).isEmpty();
			if (!others) {
				owner.getCooldowns().removeCooldown(getDisplayStack().getItem());
			}
		}
		super.remove(reason);
	}

	// ==================== 存档 ====================

	@Override
	public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.put("SpearItem", getDisplayStack().save(new net.minecraft.nbt.CompoundTag()));
		tag.putFloat("LockedDamage", lockedAttackDamage);
		tag.putInt("FlightTicks", flightTicks);
		tag.putBoolean("Landed", landed);
		tag.putBoolean("BurstDone", burstDone);
	}

	@Override
	public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("SpearItem")) {
			setDisplayStack(ItemStack.of(tag.getCompound("SpearItem")));
		}
		lockedAttackDamage = tag.getFloat("LockedDamage");
		flightTicks = tag.getInt("FlightTicks");
		landed = tag.getBoolean("Landed");
		burstDone = tag.getBoolean("BurstDone");
		// 重新加载后同样不可拾取，避免掉落复制品
		this.pickup = Pickup.DISALLOWED;
	}

	/** 是否由该玩家投出（自检与冷却回收用） */
	public boolean isOwnedBy(Player player) {
		return player != null && player.equals(getOwner());
	}
}
