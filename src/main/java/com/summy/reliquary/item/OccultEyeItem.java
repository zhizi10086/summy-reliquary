package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.EvilUnlock;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
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
 * 玄秘魔眼（1.6.5）：装进「加护」栏的恶魔侧饰品，由**夜之幽魂 + 3×「6」无序合成**升级而来。
 *
 * <p>门槛：**恶魔标记 + 邪恶度 700 已解锁**；**继承夜之幽魂全部能力**（移速 +20% 与创造飞行半速，
 * 见 {@code AttributeManager}）；另外对**注视到的那一具生物**（与「思想：看向发光」同一套射线判定）
 * 每秒施加「恐惧」，并对自己造成的伤害 ×1.3（圣心只免疫恐惧减益、不免疫这个增伤）。
 */
public class OccultEyeItem extends Item implements ICurioItem {
	/** 本物品所属派系（恶魔线） */
	private static final ReliquaryFaction FACTION = ReliquaryFaction.DEMON;

	public OccultEyeItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, false,
				entity -> ReliquaryConfig.enableOccultEye()
						&& EvilUnlock.usable(entity, EvilUnlock.OCCULT_EYE),
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
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.occult_eye.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.occult_eye.tagline.2");

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.OCCULT_EYE)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 第一行是叙述（继承夜之幽魂，不重复列具体属性），其余按「属性名|数值」两段配色
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.occult_eye.shift.1");
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.occult_eye.shift.2");
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.occult_eye.shift.3");
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.occult_eye.shift.4",
					String.format(java.util.Locale.ROOT, "%.1f", ReliquaryConfig.fearDamageMultiplier()));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.5：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary.occult_eye", FACTION);
	}
}
