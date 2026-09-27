package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.EvilUnlock;
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
 * 夜之幽魂（1.6.3）：装进「加护」栏位的恶魔侧饰品。
 *
 * <p>门槛：**恶魔标记 + 邪恶度 500 已解锁**（`EvilUnlock.NIGHT_WRAITH`，解锁永久）。
 * 效果：**移动速度 +20%** + **获得创造飞行**（速度为原版的一半，与终末天启共用同一套飞行逻辑、不叠加）。
 */
public class NightWraithItem extends Item implements ICurioItem {
	/** 恶魔话语在提示里的深红 */
	private static final int DEMON_RED = 0xB22222;
	/** 本物品所属派系（恶魔线） */
	private static final com.summy.reliquary.text.ReliquaryFaction FACTION =
			com.summy.reliquary.text.ReliquaryFaction.DEMON;

	public NightWraithItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, false,
				entity -> EvilUnlock.usable(entity, EvilUnlock.NIGHT_WRAITH),
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
		// 风味两行（1.6.9：恶魔线深红）；引号句保持暗红斜体
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.night_wraith.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.night_wraith.tagline.2");
		tooltip.add(Component.translatable("item.summy-reliquary.night_wraith.quote")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(DEMON_RED)).withItalic(true)));

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.NIGHT_WRAITH)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.night_wraith.shift.1",
					format(ReliquaryConfig.nightWraithMovementPercent()));
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.night_wraith.shift.2");
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 去掉小数点后缀（20.0 → 20） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 1.6.3：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.night_wraith", FACTION);
	}
}
