package com.summy.reliquary.item;

import com.google.common.collect.Multimap;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.HaloState;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.LivingEntity;
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
 * 光环：光环栏位饰品（与邦邦咔邦光环互斥，一次只能戴一个）。
 *
 * <p>属性修正由 {@code AttributeManager} 每秒重算：佩戴七罪之源且至少激活一项时整套 ×0.5，
 * 佩戴美德时 ×2。提示里会追加对应的「你当知罪」/「你已赎罪」。
 */
public class TheHaloItem extends Item implements ICurioItem {
	/** 七德文本用色（与美德饰品一致） */
	private static final int VIRTUE_GOLD = 0xFFD700;
	private static final int VIRTUE_PALE_GOLD = 0xFFE4B5;
	/** 1.7.1：本物品所属派系（天使线） */
	private static final com.summy.reliquary.text.ReliquaryFaction FACTION =
			com.summy.reliquary.text.ReliquaryFaction.ANGEL;

	public TheHaloItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.HALO);
	}

	/**
	 * 允许手持右键快捷佩戴。
	 *
	 * <p>注意：Curios 的 {@code ICurio.canRightClickEquip()} 默认返回 {@code false}，
	 * 所以"默认允许右键"并不成立，必须在这里显式打开；能否装进目标栏位仍由 {@link #canEquip} 判定。
	 */
	@Override
	public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
		return true;
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
		tooltip.add(Component.translatable("item.summy-reliquary.the_halo.tagline")
				.withStyle(ChatFormatting.GRAY));

		// 佩戴七罪之源（且至少激活一项）/ 美德 时追加的状态行
		// 只有客户端才有本地玩家；服务端读到提示时按"未佩戴"处理（本地玩家获取见 ReliquaryTooltips）
		LivingEntity wearer = ReliquaryTooltips.localPlayer();
		switch (HaloState.state(wearer)) {
			case SIN -> tooltip.add(SinTexts.colored("item.summy-reliquary.the_halo.sin",
					SinTexts.breathing(SinTexts.PRAYER_BASE, SinTexts.PRAYER_FLASH, 2200L)));
			case VIRTUE -> tooltip.add(SinTexts.colored("item.summy-reliquary.the_halo.virtue",
					SinTexts.breathing(VIRTUE_GOLD, VIRTUE_PALE_GOLD, 2400L)));
			default -> {
			}
		}

		if (ReliquaryTooltips.shiftDown()) {
			tooltip.addAll(shiftLines(wearer));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/**
	 * Shift 功能行（抽成纯函数便于自检逐行断言）：
	 * 7 条 {@code 属性名|数值} 行，数值 = 配置值 × 当前实际倍率；
	 * 另加一行条件行 —— **只在"获取过启示"时显示**，整句天使淡金、其中「生命回复」单独用亮金。
	 */
	public static List<Component> shiftLines(LivingEntity wearer) {
		List<Component> lines = new java.util.ArrayList<>();
		// 1.7.1：属性逐条写进 Shift，数值 = 配置值 × 当前倍率（实际生效值，戴撒旦圣经时为 0）
		double factor = HaloState.multiplier(wearer);
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.1",
				format(ReliquaryConfig.haloMaxHealth() * factor)));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.2",
				format(ReliquaryConfig.haloAttackDamage() * factor)));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.3",
				format(ReliquaryConfig.haloAttackSpeed() * factor)));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.4",
				format(ReliquaryConfig.haloArmor() * factor)));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.5",
				format(ReliquaryConfig.haloArmorToughness() * factor)));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.6",
				format(ReliquaryConfig.haloMovementPercent() * factor)));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.the_halo.shift.7",
				format(ReliquaryConfig.haloBreakSpeedPercent() * factor)));
		// 条件行：只在"获取过启示"（revelation_obtained）时显示；整句天使淡金、
		// 其中「生命回复」四字用亮金单独调色（它是一个 buff，需要注明）
		if (wearer != null && PlayerFlags.isRevelationObtained(wearer)) {
			// 注意：这里**不用** %s 把组件当参数塞进去 —— 服务端线程上 TranslatableContents 的组件参数
			// 不会被替换（实测会留下字面 "%s"）。改成"前缀 + 亮金 buff 名"两段拼接，
			// 渲染出来仍是同一行、同一句话，只是「生命回复」四个字单独上了亮金。
			MutableComponent regen = Component.translatable("item.summy-reliquary.the_halo.regen")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(VIRTUE_GOLD)));
			lines.add(Component
					.translatable("item.summy-reliquary.the_halo.shift.conditional")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(VIRTUE_PALE_GOLD)))
					.append(regen));
		}
		return lines;
	}

	/** 去掉小数点后缀（4.0 → 4、0.5 → 0.5） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

}
