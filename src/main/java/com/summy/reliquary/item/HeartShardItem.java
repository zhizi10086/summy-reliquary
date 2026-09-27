package com.summy.reliquary.item;

import net.minecraft.ChatFormatting;
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
 * 心之碎片（Heart Shard，1.5.6）：由带有「启示之光」的生物死亡时掉落的材料。
 *
 * <p>图标用自家贴图 {@code summy-reliquary:item/heart_shard}（1.6.1 起，不再借用下界之星）。
 * 掉落时会被标记为**不可摧毁**（防火 / 防爆 / 防雷 / 防刺），所以丢进岩浆或 TNT 也炸不掉。
 */
public class HeartShardItem extends Item {
	/** 淡金（经文用色） */
	private static final int PALE_GOLD = 0xFFE4B5;

	public HeartShardItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.summy-reliquary.heart_shard.desc.1")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)));
		tooltip.add(Component.translatable("item.summy-reliquary.heart_shard.desc.2")
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable("item.summy-reliquary.heart_shard.desc.3")
				.withStyle(ChatFormatting.WHITE));
	}
}
