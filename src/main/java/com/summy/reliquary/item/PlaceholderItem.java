package com.summy.reliquary.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 占位物品（1.5.4）：贴图与名字已经就位，功能等后续补充。
 *
 * <p>只额外输出一行浅灰「功能待补充」，不带任何右键行为、不进 Curios 栏位、也没有配方。
 */
public class PlaceholderItem extends Item {
	public PlaceholderItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.summy-reliquary.placeholder.pending")
				.withStyle(ChatFormatting.GRAY));
	}
}
