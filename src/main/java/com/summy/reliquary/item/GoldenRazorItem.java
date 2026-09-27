package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.entity.ThrownRazor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 金刀片（1.7.10）：**无基础属性**的中立武器，右键**立即投掷**一把可穿透生物的金刀片。
 *
 * <p>口径：
 * <ul>
 *     <li>**无属性** —— 不挂任何攻击力 / 攻速修饰符，也不进 {@code #swords}，左键只有原版空手伤害；</li>
 *     <li>**投掷不消耗本体**，只进 {@code [golden_razor] razor_cooldown_ticks}（默认 10 tick = 0.5 秒）冷却；</li>
 *     <li>投出去的 {@link ThrownRazor} 可穿透生物，命中每个目标固定 {@code razor_damage}（默认 5）点物理伤害，
 *     且**不吃本模组的任何加成**（伤害类型 {@code summy-reliquary:golden_razor} 在两处排除表里）；</li>
 *     <li>出手速度 {@code razor_velocity}（1.7.10 收尾：默认 1.8）；投掷物**命中方块后插在原地停留**
 *     {@code razor_stuck_ticks}（默认 100 tick = 5 秒）再清除；</li>
 *     <li>**无派系限制**：没有天使 / 恶魔门槛，也不在任何门禁或没收清单里。</li>
 * </ul>
 */
public class GoldenRazorItem extends Item {
	public GoldenRazorItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!ReliquaryConfig.enableGoldenRazor()) {
			return InteractionResultHolder.pass(stack);
		}
		// 0.5 秒（默认 10 tick）投掷间隔：冷却中直接拒绝
		if (player.getCooldowns().isOnCooldown(this)) {
			return InteractionResultHolder.fail(stack);
		}
		if (level.isClientSide()) {
			return InteractionResultHolder.sidedSuccess(stack, true);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		ThrownRazor razor = new ThrownRazor(level, serverPlayer);
		razor.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
				(float) ReliquaryConfig.razorVelocity(), 0.0F);
		level.addFreshEntity(razor);
		// 投掷不消耗本体，只走冷却
		serverPlayer.getCooldowns().addCooldown(this, ReliquaryConfig.razorCooldownTicks());
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.4F);
		return InteractionResultHolder.success(stack);
	}

	/**
	 * 提示：风味一行常驻；Shift 展开一行说明。
	 *
	 * <p>它是**中立物品**，所以风味行用中立灰白（{@code #AAAAAA}），Shift 行用灰色叙述行。
	 */
	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
			TooltipFlag flag) {
		ReliquaryTooltips.neutralFlavorLine(tooltip, "item.summy-reliquary.golden_razor.tagline.1");
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.golden_razor.shift.1");
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}
}
