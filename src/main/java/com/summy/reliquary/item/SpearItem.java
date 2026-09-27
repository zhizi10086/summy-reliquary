package com.summy.reliquary.item;

import com.summy.reliquary.entity.ThrownSpear;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.WeaponGates;
import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 天使线长矛的通用实现（1.7.8 起）：圣光短矛与炽天使之枪共用。
 *
 * <p>它是**剑类**（可附锋利、可横扫）。两把矛的差别在 1.7.9 之后是：
 * <ul>
 *     <li><b>圣光短矛</b>（{@code throwable = false}）：不能投掷，改为"攻击距离 +0.5 格"
 *     + "攻击时 10% 概率召唤圣光"（与饰品位的 15% / 25% 各自独立掷骰）；</li>
 *     <li><b>炽天使之枪</b>（{@code throwable = true}）：攻击距离 +1 格，长按右键蓄力
 *     {@link #MIN_CHARGE_TICKS} tick 后松手，投出一枚 {@link ThrownSpear} 幻影 ——
 *     **本体留在手上不消耗**（只扣 1 点耐久），架构与 ER 的「忠诚改版」一致；幻影命中后
 *     结算面板近战伤害 + 落点 4 格的圣光爆发，停留 20 tick 后消失（不再飞回来）。</li>
 * </ul>
 *
 * <p>冷却圈（原版物品冷却）会一直挂到幻影消失为止（由 {@code ThrownSpear#remove} 撤销），
 * 万一幻影卡在未加载区块不 tick，冷却本身也有 {@code ThrownSpear#THROW_BLOCK_TICKS} 的到期兜底，
 * 绝不会把玩家卡死。
 *
 * <p><b>门槛</b>（1.7.9）：两把矛都需要天使标记，未达标时右键直接拒绝，并且攻击距离 / 圣光
 * 召唤 / 左键伤害全部失效（见 {@link WeaponGates}）。
 */
public class SpearItem extends SwordItem {
	/** 投出所需的最短蓄力（tick，10 = 0.5 秒，与三叉戟一致） */
	private static final int MIN_CHARGE_TICKS = 10;
	/** 可以一直举着（与三叉戟一样不设上限） */
	private static final int MAX_USE_TICKS = 72000;
	/** 出手速度 */
	private static final float SHOOT_VELOCITY = 2.5F;

	/** 物品名的语言键 */
	private final String nameKey;
	/** 是否可投掷（圣光短矛 false / 炽天使之枪 true） */
	private final boolean throwable;

	public SpearItem(Tier tier, int attackDamageModifier, float attackSpeedModifier, Properties properties,
			String nameKey, boolean throwable) {
		super(tier, attackDamageModifier, attackSpeedModifier, properties);
		this.nameKey = nameKey;
		this.throwable = throwable;
	}

	/** 是否可投掷（自检用） */
	public boolean isThrowable() {
		return throwable;
	}

	/** 物品名按天使线配色（白 → 金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName(nameKey, ReliquaryFaction.ANGEL);
	}

	/** 举矛蓄力姿势（原版三叉戟同款） */
	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.SPEAR;
	}

	@Override
	public int getUseDuration(ItemStack stack) {
		return MAX_USE_TICKS;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		// 圣光短矛没有投掷能力：右键无事发生（也就没有蓄力动作）
		if (!throwable) {
			return InteractionResultHolder.pass(stack);
		}
		// 未达标（没有天使标记）→ 拒绝并提示，完全禁用
		if (!WeaponGates.qualified(player, this)) {
			if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
				serverPlayer.displayClientMessage(
						Component.translatable(WeaponGates.hintKey(player, this)), true);
			}
			return InteractionResultHolder.fail(stack);
		}
		if (player.getCooldowns().isOnCooldown(this)) {
			// 幻影还在飞（冷却圈未撤）→ 不能投
			return InteractionResultHolder.fail(stack);
		}
		player.startUsingItem(hand);
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (!(entity instanceof Player player)) {
			return;
		}
		if (!throwable || !WeaponGates.qualified(player, this)) {
			return;
		}
		int charge = getUseDuration(stack) - timeLeft;
		if (charge < MIN_CHARGE_TICKS || level.isClientSide || player.getCooldowns().isOnCooldown(this)) {
			return;
		}
		// 投掷瞬间锁定"面板攻击力"；命中时再按目标类型补上附魔增伤（见 ThrownSpear#onHitEntity）
		float panel = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		ThrownSpear spear = new ThrownSpear(level, player, stack, panel);
		// 1.7.10：佩戴圣心时投掷初速小幅加快（配置项；只作用于两把长矛）
		float velocity = SHOOT_VELOCITY * com.summy.reliquary.effect.Synergies
				.spearThrowVelocityMultiplier(player);
		spear.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, velocity, 1.0F);
		level.addFreshEntity(spear);
		// 本体不消耗，只扣耐久
		stack.hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(player.getUsedItemHand()));
		// 冷却上限：幻影回收时会被提前撤掉
		player.getCooldowns().addCooldown(this, ThrownSpear.THROW_BLOCK_TICKS);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	/**
	 * 提示（1.7.9）：风味一行常驻；Shift 展开各自的三行说明（天使线配色）。
	 *
	 * <p>圣光短矛的"10%"读配置（{@code [holy_light] holy_light_chance_percent_spear}），
	 * 免得改了配置之后提示与实际不符。
	 */
	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
			TooltipFlag flag) {
		ReliquaryTooltips.angelFlavorLine(tooltip, nameKey + ".tagline.1");
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.1");
			if (throwable) {
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.2");
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.3");
			} else {
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.2",
						ReliquaryConfig.holyLightChancePercentSpear());
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.3");
			}
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}
}
