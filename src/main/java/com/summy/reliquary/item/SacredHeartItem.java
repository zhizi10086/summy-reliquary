package com.summy.reliquary.item;

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
 * 圣心（Sacred Heart）：装入「加护」栏位（1.5.5）。
 *
 * <p>门槛与其它加护饰品一致：需要**天使标记**才能佩戴与查阅。
 * 效果：大批属性提升（生命/护甲/韧性/攻速/移速/挖掘，见配置 {@code sacred_heart}）+
 * 与神性共用同一个「全伤害最终倍率」乘区（默认 +30%，相加）+ 箭矢追踪
 * （追踪逻辑在 {@code effect/SacredHeart}）。
 */
public class SacredHeartItem extends Item implements ICurioItem {
	/** 淡金（经文用色） */
	private static final int PALE_GOLD = 0xFFE4B5;

	public SacredHeartItem(Properties properties) {
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
		ReliquaryTooltips.add(tooltip, Component.translatable("item.summy-reliquary.sacred_heart.desc.1")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)));
		ReliquaryTooltips.add(tooltip, Component.translatable("item.summy-reliquary.sacred_heart.desc.2")
				.withStyle(ChatFormatting.GRAY));

		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			for (Component line : shiftLines()) {
				ReliquaryTooltips.add(tooltip, line);
			}
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/**
	 * Shift 功能行（**一行一项属性**，与「光环」同款格式：{@code 属性名|+数值} 两段配色）：
	 * 7 条数值取 {@code [sacred_heart]} 配置的实测值，随后是金色引言行「祂与你同在：」、
	 * 两条效果行（「·追踪能力」/「你不再恐惧深渊」）与联动声明（1.8.2）。
	 *
	 * <p>1.8.5：原先只有一行「大量属性提升」，看不到任何数字；现在逐项列出生命 / 护甲 / 护甲韧性 /
	 * 攻击速度 / 移动速度 / 挖掘速度 / 造成伤害。**乘区口径不写进提示**（"与神性相加"这类跨条目标注
	 * 属于冗余，机制说明留在配置注释与文档里）；数值之后保留旧版的金色引言行「祂与你同在：」，
	 * 用来引出下面两条效果行。
	 * 追踪半径仍按"当前是否同时佩戴神性"实时显示（基础 8 格 / 联动 12 格）。
	 */
	public static java.util.List<Component> shiftLines() {
		String radius = ReliquaryTooltips.number(com.summy.reliquary.effect.Synergies
				.sacredHeartArrowRadius(ReliquaryTooltips.localPlayer()));
		java.util.List<Component> lines = new java.util.ArrayList<>();
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.1",
				ReliquaryTooltips.number(com.summy.reliquary.config.ReliquaryConfig.sacredHeartMaxHealth())));
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.2",
				ReliquaryTooltips.number(com.summy.reliquary.config.ReliquaryConfig.sacredHeartArmor())));
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.3",
				ReliquaryTooltips.number(com.summy.reliquary.config.ReliquaryConfig.sacredHeartToughness())));
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.4",
				ReliquaryTooltips.number(com.summy.reliquary.config.ReliquaryConfig.sacredHeartAttackSpeed())));
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.5",
				com.summy.reliquary.config.ReliquaryConfig.sacredHeartMovementPercent()));
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.6",
				com.summy.reliquary.config.ReliquaryConfig.sacredHeartBreakSpeedPercent()));
		lines.add(stat("item.summy-reliquary.sacred_heart.shift.7",
				com.summy.reliquary.config.ReliquaryConfig.sacredHeartDamagePercent()));
		// 金色引言行（旧版第 2 行）：位置固定在七条属性之后、两条效果行之前
		lines.add(Component.translatable("item.summy-reliquary.sacred_heart.shift.presence")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD))));
		lines.add(Component.translatable("item.summy-reliquary.sacred_heart.shift.arrow", radius)
				.withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("item.summy-reliquary.sacred_heart.shift.abyss")
				.withStyle(ChatFormatting.GRAY));
		lines.add(stat("item.summy-reliquary.sacred_heart.linkage"));
		return lines;
	}

	/** 一条「属性名|+数值」行（天使配色，与光环同款） */
	private static Component stat(String key, Object... args) {
		return ReliquaryTooltips.statComponent(com.summy.reliquary.text.ReliquaryFaction.ANGEL, key, args);
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.sacred_heart",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
