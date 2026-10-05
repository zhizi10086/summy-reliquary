package com.summy.reliquary.item;

import com.summy.reliquary.effect.EvilUnlock;
import com.summy.reliquary.slot.ReliquarySlots;
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
 * 亚巴顿（1.6.8 转正）：装进「启示之座」的恶魔侧终极饰品。
 *
 * <p>门槛是**恶魔线**：需要恶魔标记，且邪恶度达到 1000；佩戴时的四条能力见 {@code effect/Abaddon}
 * （复仇之魂半径 5、继承强化硫磺火、不减速飞行、被击杀时拦截复活 + 恶魔光环）。本轮只登记门槛、不给配方。
 */
public class AbaddonItem extends Item implements ICurioItem {
	/** 描述行用的恶魔深红（与恶魔线属性名同色） */
	public static final int TAGLINE_COLOR = 0xC03030;

	public AbaddonItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.REVELATION, false,
				entity -> EvilUnlock.usable(entity, EvilUnlock.ABADDON),
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
		// 描述行：按你的要求用红色
		tooltip.add(Component.translatable("item.summy-reliquary.abaddon.tagline.1")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(TAGLINE_COLOR))));

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.ABADDON)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 六行功能：按 | 拆两段、各上一色（恶魔线深红 + 亮红）
			for (int index = 1; index <= 6; index++) {
				ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
						"item.summy-reliquary.abaddon.shift." + index);
			}
		} else if (ReliquaryTooltips.altDown()) {
			// 五行诗句「你即是 x，……」
			for (int index = 1; index <= 5; index++) {
				ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
						"item.summy-reliquary.abaddon.alt." + index);
			}
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
			tooltip.add(ReliquaryTooltips.altHint());
		}
	}

	/** 物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.abaddon",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}
}
