package com.summy.reliquary.item;

import com.google.common.collect.Multimap;
import com.summy.reliquary.client.ReliquaryClientState;
import com.summy.reliquary.effect.RevelationTracker;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;
import java.util.UUID;

/**
 * 伯列恒之星：启示栏位饰品。
 *
 * <p>攻击速度 +20%（{@code AttributeManager}）、造成伤害 +20%（{@code SpiritAltarSet}）；
 * 累计佩戴满 600 秒后由 {@code RevelationTracker} 揭示一个主世界坐标，
 * 提示里的状态行在「等待祂的启示」与「启示之星于 [X, Z] 处闪耀」之间切换。
 */
public class StarOfBethlehemItem extends Item implements ICurioItem {
	public StarOfBethlehemItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.REVELATION, true);
	}

	@Override
	public List<Component> getSlotsTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public List<Component> getAttributesTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public Multimap<Attribute, AttributeModifier> getAttributeModifiers(SlotContext slotContext, UUID uuid,
			ItemStack stack) {
		return CurioItemSupport.noAttributes();
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.summy-reliquary.star_of_bethlehem.tagline")
				.withStyle(ChatFormatting.GRAY));
		// 没有天使标记：只能看到风味行 + 一行提示，功能描述不可查阅
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}
		// 状态行（1.4.4 起三态，悬停即可见）：
		// 未揭示 = 剩余秒数倒计时；已揭示 = 亮蓝坐标指引；已完成「启示」= 已经降临
		if (ReliquaryClientState.isRevelationAscended()) {
			tooltip.add(Component.translatable("item.summy-reliquary.star_of_bethlehem.ascended")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))));
		} else if (ReliquaryClientState.isRevealed()) {
			// 1.8.5：坐标后面的括注改为显示**维度名**（原先把「维度」误写成了「纬度」）。
			// 启示坐标固定派生自主世界，所以这里恒为「主世界」；日后若坐标改为跟随维度，
			// 只需把第二个参数换成对应维度的语言键即可。
			tooltip.add(Component.translatable("item.summy-reliquary.star_of_bethlehem.guided",
					RevelationTracker.coordinateText(
							ReliquaryClientState.revealX(), ReliquaryClientState.revealZ()),
					Component.translatable("item.summy-reliquary.dimension.overworld"))
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))));
		} else {
			tooltip.add(Component.translatable("item.summy-reliquary.star_of_bethlehem.countdown",
					ReliquaryClientState.revealRemainingSeconds())
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		// 1.6.3：把"攻击速度 / 造成伤害 / 揭示说明"拆成三条，前两条按"属性名+数值"两段配色
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.star_of_bethlehem.desc",
					com.summy.reliquary.config.ReliquaryConfig.starAttackSpeedPercent());
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.star_of_bethlehem.desc.damage",
					com.summy.reliquary.config.ReliquaryConfig.starDamagePercent());
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.star_of_bethlehem.desc.detail",
					com.summy.reliquary.config.ReliquaryConfig.revealSeconds());
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.star_of_bethlehem",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
