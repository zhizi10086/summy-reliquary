package com.summy.reliquary.item;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 救恩（Salvation）：装入新增的「加护」栏位。
 *
 * <p>门槛：必须已经拿到**天使标记**才能佩戴与查阅（与灵台三件套 / 伯列恒之星 / 终末天启同一把门）；
 * 缺少标记时提示只显示风味行 + 一行「你尚未获得天国的赐福」。
 *
 * <p>领域（半径 2 格，同时佩戴终末天启时 3 格、对领域内敌对生物累计锁定 1 秒后结算 7 点启示伤害）
 * 由 {@code effect/SalvationDomain} 在服务端维护。
 */
public class SalvationItem extends Item implements ICurioItem {
	/** 亮蓝（描述用色） */
	private static final int AQUA = 0x55FFFF;

	public SalvationItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, true);
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
		// 描述：两条弧线，一个名字（亮蓝）
		tooltip.add(Component.translatable("item.summy-reliquary.salvation.tagline")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA))));
		tooltip.add(Component.translatable("item.summy-reliquary.salvation.tagline.2")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA))));

		// 没有天使标记：不展开功能描述
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			tooltip.add(Component.translatable("item.summy-reliquary.salvation.desc")
					.withStyle(ChatFormatting.GRAY));
			// 1.8.4：受益方不设联动行，半径按当前佩戴状态实时显示
			//（基础 3 格 / 戴终末天启 4 格 / 戴神性 5 格）
			tooltip.add(Component.translatable("item.summy-reliquary.salvation.desc.extended",
							ReliquaryTooltips.number(com.summy.reliquary.effect.SalvationDomain
									.radiusFor(ReliquaryTooltips.localPlayer())))
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA))));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.salvation",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
