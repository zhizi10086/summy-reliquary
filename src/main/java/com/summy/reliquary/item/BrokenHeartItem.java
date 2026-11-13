package com.summy.reliquary.item;

import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 恶魔线与心之碎片对位的高级材料。 */
public class BrokenHeartItem extends Item {
	public BrokenHeartItem(Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary.broken_heart", ReliquaryFaction.DEMON);
	}

	@Override
	public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context,
			List<Component> tooltip, TooltipFlag flag) {
		ReliquaryTooltips.demonFlavorLine(tooltip,
				"item.summy-reliquary.broken_heart.desc.1");
		tooltip.add(Component.translatable("item.summy-reliquary.broken_heart.desc.2")
				.withStyle(ChatFormatting.GRAY));
	}
}
