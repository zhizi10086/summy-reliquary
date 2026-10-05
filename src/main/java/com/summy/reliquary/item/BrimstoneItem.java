package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.EvilUnlock;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
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
 * 硫磺火（1.6.4）：装进「启示之座」的恶魔侧饰品（与伯列恒之星 / 终末天启 / 神性互斥）。
 *
 * <p>门槛：**恶魔标记 + 邪恶度 666 已解锁**（沿用小节 `unlockedFor` 的"位图或当前值"容错）。
 * 666 同时会永久锁定天使线（{@code hell_locked}），所以"获取后无法忏悔"是既有行为，不需要额外标记。
 *
 * <p>技能：长按 V 1.5 秒发射**恶魔之焰**（见 {@code RevelationBeam.BeamKind#DEMON_FLAME}）——
 * 长 18 格、半径 2 格、持续 1.3 秒，每 0.1 秒对范围内单位造成 6 点狱火（真伤），冷却 6 秒。
 * 本轮**没有配方**：靠创造模式或 {@code /summyreliquary demon grant <玩家>} 获取。
 */
public class BrimstoneItem extends Item implements ICurioItem {
	/** 恶魔话语在提示里的深红 */
	private static final int DEMON_RED = 0xB22222;
	/** 本物品所属派系（恶魔线） */
	private static final ReliquaryFaction FACTION = ReliquaryFaction.DEMON;

	public BrimstoneItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.REVELATION, false,
				entity -> ReliquaryConfig.enableBrimstone()
						&& EvilUnlock.usable(entity, EvilUnlock.BRIMSTONE),
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
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.brimstone.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.brimstone.tagline.2");
		tooltip.add(Component.translatable("item.summy-reliquary.brimstone.quote")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(DEMON_RED)).withItalic(true)));

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.BRIMSTONE)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.brimstone.shift.1",
					format(ReliquaryConfig.brimstoneChargeSeconds()));
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.brimstone.shift.2");
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.brimstone.shift.3",
					String.valueOf(ReliquaryConfig.brimstoneCooldownSeconds()));
			// 1.8.2：提供方声明为谁提供联动
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.brimstone.linkage");
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 去掉小数点后缀（1.5 → 1.5、2.0 → 2） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 1.6.4：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary.brimstone", FACTION);
	}
}
