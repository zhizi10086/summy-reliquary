package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.EvilUnlock;
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
 * 深渊领主（1.6.7 转正）：装进「加护」栏位的恶魔侧饰品。
 *
 * <p>门槛是**恶魔线**：需要恶魔标记，且邪恶度达到 900；佩戴时佩戴者造成的所有伤害都会给目标叠「狱火」
 * （见 {@code effect/AbyssLord} 与 {@code effect/HellfireEffect}）。本轮只登记门槛、不给配方。
 */
public class AbyssLordItem extends Item implements ICurioItem {
	public AbyssLordItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, false,
				entity -> EvilUnlock.usable(entity, EvilUnlock.ABYSS_LORD),
				"缺少恶魔标记或邪恶度未达标");
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
		// 风味两行（1.6.9：恶魔线深红）
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.abyss_lord.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.abyss_lord.tagline.2");

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.ABYSS_LORD)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 1.6.8：按需求改成四行叙述（数值仍读配置；不带 | 所以统一灰色）
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.abyss_lord.shift.1");
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.abyss_lord.shift.2",
					String.valueOf(ReliquaryConfig.hellfireMaxLevel()));
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.abyss_lord.shift.3");
			// 1.8.4：「满级时目标的抗性提升等级减半」是亚巴顿专属联动，按当前佩戴状态追加
			boolean suppress = com.summy.reliquary.effect.Synergies
					.hellfireSuppressesResistance(ReliquaryTooltips.localPlayer());
			ReliquaryTooltips.narrativeLine(tooltip, suppress
							? "item.summy-reliquary.abyss_lord.shift.4.abaddon"
							: "item.summy-reliquary.abyss_lord.shift.4",
					format(ReliquaryConfig.hellfireArmorPercentPerLevel()),
					format(ReliquaryConfig.hellfireDamagePerLevelPerSecond()));
			// 1.8.2：提供方声明为谁提供联动
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
					"item.summy-reliquary.abyss_lord.linkage");
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 去掉小数点后缀（10.0 → 10） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 1.6.3 起的规范：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.abyss_lord",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}
}
