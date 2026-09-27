package com.summy.reliquary.item;

import com.summy.reliquary.text.SinTexts;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 七宗罪碎片：暂无功能，仅注册。
 *
 * <p>物品名字使用对应贴图的核心色；描述统一为「赎去你的罪过」，白字底 + 滚动金光 + 斜体。
 */
public class SinFragmentItem extends Item {
	private final int nameColor;

	public SinFragmentItem(Properties properties, int nameColor) {
		super(properties);
		this.nameColor = nameColor;
	}

	@Override
	public Component getName(ItemStack stack) {
		return super.getName(stack).copy().setStyle(Style.EMPTY.withColor(TextColor.fromRgb(nameColor)));
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(SinTexts.scrollingGold("item.summy-reliquary.sin_fragment.desc", 0xFFFFFF, true));
	}
}
