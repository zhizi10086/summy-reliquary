package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.EvilUnlock;
import com.summy.reliquary.effect.PlayerFlags;
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
 * 咒印（1.6.2）：装进「灵台」栏位的恶魔侧饰品。
 *
 * <p>门槛是**恶魔线**：需要恶魔标记，且邪恶度达到解锁门槛（300）。获取途径是 3×「6」无序合成，
 * 或签约时按被没收的灵台部件数量补偿（三件全没收则直接补 1 个咒印）。
 *
 * <p>效果：继承天使线灵台套装的全部属性（生命 / 套装减伤 / 发光 / 20% 免死），
 * 但灵魂的魂心转化成黑心；佩戴期间邪恶度不再衰减、黑心碎裂伤害升到 40。
 */
public class TheMarkItem extends Item implements ICurioItem {
	/** 恶魔话语的暗红 */
	private static final int DARK_RED = 0x8B0000;

	public TheMarkItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.SPIRIT_ALTAR, false,
				entity -> EvilUnlock.usable(entity, EvilUnlock.THE_MARK),
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
		// 风味行（1.6.9：恶魔线深红）；第二行是引号句，保持暗红斜体
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.the_mark.tagline.1");
		tooltip.add(Component.translatable("item.summy-reliquary.the_mark.tagline.2")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(DARK_RED)).withItalic(true)));

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.THE_MARK)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			tooltip.add(Component.translatable("item.summy-reliquary.the_mark.shift.1")
					.withStyle(ChatFormatting.GRAY));
			// 1.6.3：带数值的一行按「属性名 + 数值」两段配色（恶魔线）
			// 1.8.4：数值按"当前是否同时佩戴亚巴顿"实时显示（佩戴咒印 40 / 联动 60）
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
					"item.summy-reliquary.the_mark.shift.2",
					ReliquaryTooltips.number(com.summy.reliquary.effect.Synergies
							.shatterDamage(ReliquaryTooltips.localPlayer())));
			tooltip.add(Component.translatable("item.summy-reliquary.the_mark.shift.3")
					.withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.the_mark",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}
}
