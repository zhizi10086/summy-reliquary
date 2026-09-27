package com.summy.reliquary.item;

import com.summy.reliquary.text.SinTexts;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 赎罪（Redemption）：普通物品；手持在世界里右击，可把魂印栏里的七罪之源转化为美德。
 *
 * <p>描述为「救我们脱离那恶」，白字底 + 滚动金光（不斜体）。
 */
public class RedemptionItem extends Item {
	public RedemptionItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(SinTexts.scrollingGold("item.summy-reliquary.redemption.desc", 0xFFFFFF, false));
	}
}
