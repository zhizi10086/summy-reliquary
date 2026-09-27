package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 仪式法袍（Ceremonial Robes，1.6.1）：装入 **Curios 自带的「背饰」(`back`) 栏位**。
 *
 * <p>门槛是**恶魔线**而不是天使线：只有"曾与恶魔签过约（`demon_sealed`）或当前仍是恶魔"的玩家
 * 才能佩戴与查阅；没签过约时提示只显示名字 + 一行「你尚未与它达成交易」。
 *
 * <p>佩戴效果（实现在 {@code AttributeManager} / {@code DemonPact}）：
 * 黑心池上限 +{@code robe_black_hearts} 心、攻击力 +{@code robe_attack_damage}（加法）、
 * 邪恶度的每日自然衰减从默认值换成 {@code robe_daily_decay}。
 */
public class CeremonialRobesItem extends Item implements ICurioItem {
	public CeremonialRobesItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		// 恶魔门槛：曾签约 或 当前是恶魔（读档阶段由 CurioItemSupport 统一放行）
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BACK, false,
				entity -> com.summy.reliquary.effect.PlayerFlags.isDemonSealed(entity)
						|| com.summy.reliquary.effect.PlayerFlags.isDemon(entity),
				"缺少恶魔契约");
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
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 风味行（1.6.9：恶魔线深红）
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.ceremonial_robes.tagline");

		if (ReliquaryTooltips.demonLocked()) {
			ReliquaryTooltips.appendDemonHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 1.6.3：三行统一按「属性名 + 数值」两段配色（恶魔线）
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
					"item.summy-reliquary.ceremonial_robes.shift.1", format(ReliquaryConfig.robeBlackHearts()));
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
					"item.summy-reliquary.ceremonial_robes.shift.2", format(ReliquaryConfig.robeAttackDamage()));
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
					"item.summy-reliquary.ceremonial_robes.shift.3", format(ReliquaryConfig.robeDailyDecay()));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.ceremonial_robes",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}

	/** 去掉小数点后缀（2.0 → 2），让提示里的数字好看一点 */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}
}
