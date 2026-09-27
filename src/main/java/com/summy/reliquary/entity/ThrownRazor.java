package com.summy.reliquary.entity;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金刀片的投掷物（1.7.10）：一把**可穿透生物**的幻影刀片。
 *
 * <p>与长矛的幻影矛不同，它自己结算伤害：
 * <ul>
 *     <li>本体留在玩家手上（`Pickup.DISALLOWED`、`getPickupItem()` 空栈），所以**不会复制 / 不会被捡走**；</li>
 *     <li>走原版 {@link AbstractArrow} 的物理与碰撞，但**不调用** {@code super.onHitEntity} ——
 *     原版那条路径在"伤害为 0"时会把速度反弹回去（穿不透），所以这里自己写穿透记账
 *     （{@link #piercingIgnoreEntityIds} + 原版 {@code canHitEntity} 会自动跳过已命中的目标）；</li>
 *     <li>命中每个目标固定打 {@code [golden_razor] razor_damage}（默认 5）点物理伤害，
 *     伤害类型是专属的 {@code summy-reliquary:golden_razor} —— 不吃本模组任何加成，护甲 / 保护 / 抗性照常减免；</li>
 *     <li>命中方块后**插在原地停留** {@code razor_stuck_ticks}（默认 100 tick = 5 秒）再清除
 *     —— 原版 {@code inGround} 之后不再做实体碰撞，所以停留期间不会误伤路过的生物；
 *     另有 {@code razor_max_life_ticks}（默认 200）作为未命中时的寿命兜底。</li>
 * </ul>
 */
public class ThrownRazor extends AbstractArrow {
	/** 渲染用的物品堆栈（同步到客户端） */
	private static final EntityDataAccessor<ItemStack> DATA_ITEM =
			SynchedEntityData.defineId(ThrownRazor.class, EntityDataSerializers.ITEM_STACK);

	/** 存活计数（寿命兜底） */
	private int lifeTicks;
	/** 1.7.10 收尾：是否已经插在方块上（落地停留中） */
	private boolean stuck;
	/** 1.7.10 收尾：落地停留已经过去的 tick 数 */
	private int stuckTicks;
	/**
	 * 1.7.10：**自己的**穿透记账。
	 *
	 * <p>原版 {@code AbstractArrow.piercingIgnoreEntityIds} 是 private，而且它的 {@code onHitEntity}
	 * 在"伤害为 0"时会把速度反弹回去（穿不透），所以这里自己记一份、并在 {@link #canHitEntity} 里跳过已命中的目标。
	 */
	private final IntOpenHashSet hitEntities = new IntOpenHashSet(5);

	public ThrownRazor(EntityType<? extends ThrownRazor> type, Level level) {
		super(type, level);
	}

	public ThrownRazor(Level level, ServerPlayer shooter) {
		super(SummyReliquary.THROWN_RAZOR.get(), shooter, level);
		// 本体没被拿走 → 幻影一律不可拾取
		this.pickup = Pickup.DISALLOWED;
		// 伤害完全由我们自己结算（原版那条路径在 0 伤害时会把速度反弹，穿不透）
		setBaseDamage(0.0D);
		setCritArrow(false);
		setPierceLevel((byte) ReliquaryConfig.razorPierceLevel());
		setDisplayStack(new ItemStack(SummyReliquary.GOLDEN_RAZOR.get()));
	}

	// ==================== 同步数据 ====================

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(DATA_ITEM, ItemStack.EMPTY);
	}

	/** 渲染用的物品堆栈 */
	public ItemStack getDisplayStack() {
		return this.entityData.get(DATA_ITEM);
	}

	private void setDisplayStack(ItemStack stack) {
		this.entityData.set(DATA_ITEM, stack);
	}

	@Override
	protected ItemStack getPickupItem() {
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
		// 穿透记账：达到上限就消失，否则记下"已命中"（canHitEntity 会自动跳过它）
		if (this.hitEntities.size() >= this.getPierceLevel() + 1) {
			discard();
			return;
		}
		this.hitEntities.add(target.getId());
		if (target instanceof LivingEntity living && getOwner() instanceof ServerPlayer player) {
			float damage = (float) ReliquaryConfig.razorDamage();
			if (damage > 0.0F) {
				living.hurt(razorSource(player), damage);
			}
		}
		playSound(SoundEvents.TRIDENT_HIT, 1.0F, 1.6F);
	}

	/** 同一个生物只吃一次：已命中的目标不再参与碰撞检测 */
	@Override
	protected boolean canHitEntity(Entity target) {
		return super.canHitEntity(target) && !this.hitEntities.contains(target.getId());
	}

	@Override
	protected void onHitBlock(BlockHitResult result) {
		super.onHitBlock(result);
		// 1.7.10 收尾：命中方块后不再立刻消失，而是插在原地停留 5 秒（默认 100 tick）再清除。
		// super 已经置好 inGround / 法线贴墙，之后原版不再做实体碰撞 —— 停留期间不会误伤路过的生物。
		setDeltaMovement(Vec3.ZERO);
		setNoGravity(true);
		playSound(SoundEvents.TRIDENT_HIT_GROUND, 1.0F, 1.6F);
		this.stuck = true;
		this.stuckTicks = 0;
	}

	/** 专属伤害源（数据包缺失时退回原版投掷物伤害，避免抛异常） */
	private DamageSource razorSource(ServerPlayer attacker) {
		var registry = level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("golden_razor"));
		var holder = registry.getHolder(key).orElse(null);
		if (holder == null) {
			return level().damageSources().thrown(this, attacker);
		}
		return new DamageSource(holder, this, attacker);
	}

	// ==================== 每 tick ====================

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) {
			return;
		}
		// 1.7.10 收尾：落地后由"停留计时"接管（不再累加寿命，避免两个计时器打架）
		if (this.stuck) {
			advanceStuck();
		} else if (++lifeTicks > ReliquaryConfig.razorMaxLifeTicks()) {
			discard();
		}
	}

	/** 1.7.10 收尾：落地停留计时（自检与运行时共用同一套逻辑） */
	private void advanceStuck() {
		if (++this.stuckTicks >= ReliquaryConfig.razorStuckTicks()) {
			discard();
		}
	}

	// ==================== 存档 ====================

	// ==================== 自检接口 ====================

	/** 自检用：直接结算一次"命中实体"（走完整逻辑：穿透记账 + 固定伤害） */
	public void hitEntityForTest(LivingEntity target) {
		onHitEntity(new EntityHitResult(target));
	}

	/** 自检用：已经命中过多少个目标 */
	public int piercedCount() {
		return this.hitEntities.size();
	}

	/** 自检用：直接走一次"命中方块"（进入落地停留状态） */
	public void hitBlockForTest(Vec3 location) {
		onHitBlock(new BlockHitResult(location, Direction.UP, BlockPos.containing(location), false));
	}

	/** 自检用：是否已经进入"落地停留"状态 */
	public boolean isStuck() {
		return this.stuck;
	}

	/** 自检用：落地停留已经过去的 tick 数 */
	public int stuckTicks() {
		return this.stuckTicks;
	}

	/** 自检用：手动推进落地计时（与运行时走同一个 {@link #advanceStuck()}，保证不自检分叉） */
	public void stuckCountdownForTest(int ticks) {
		for (int i = 0; i < ticks; i++) {
			advanceStuck();
		}
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.put("RazorItem", getDisplayStack().save(new CompoundTag()));
		tag.putInt("LifeTicks", lifeTicks);
		tag.putBoolean("Stuck", stuck);
		tag.putInt("StuckTicks", stuckTicks);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("RazorItem")) {
			setDisplayStack(ItemStack.of(tag.getCompound("RazorItem")));
		}
		lifeTicks = tag.getInt("LifeTicks");
		// 1.7.10 收尾：区块重载后继续倒计时
		stuck = tag.getBoolean("Stuck");
		stuckTicks = tag.getInt("StuckTicks");
		// 重新加载后同样不可拾取
		this.pickup = Pickup.DISALLOWED;
	}
}
