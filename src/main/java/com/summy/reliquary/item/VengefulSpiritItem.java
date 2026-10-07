package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.EvilUnlock;
import com.summy.reliquary.effect.PlayerFlags;
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
 * 复仇之魂（1.6.2）：装进「加护」栏位的恶魔侧饰品。
 *
 * <p>门槛是**恶魔线**：需要恶魔标记，且邪恶度达到解锁门槛（100）。佩戴时每秒对半径内的敌人
 * 造成固定点数的狱火伤害（见 {@code effect/VengefulSpirit}）。
 */
public class VengefulSpiritItem extends Item implements ICurioItem {
	public VengefulSpiritItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, false,
				entity -> EvilUnlock.usable(entity, EvilUnlock.VENGEFUL_SPIRIT),
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
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.vengeful_spirit.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.vengeful_spirit.tagline.2");

		if (ReliquaryTooltips.demonUnlockLocked(EvilUnlock.VENGEFUL_SPIRIT)) {
			ReliquaryTooltips.appendDemonUnlockHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 1.8.4：受益方不设联动行，半径与伤害都按当前佩戴状态实时显示
			//（基础 3 格 / 戴硫磺火 4 格 / 戴亚巴顿 5 格）
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
					"item.summy-reliquary.vengeful_spirit.shift.1",
					ReliquaryTooltips.number(com.summy.reliquary.effect.VengefulSpirit
							.effectiveRadius(ReliquaryTooltips.localPlayer())),
					format(ReliquaryConfig.vengefulHellfireDamage()));
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.vengeful_spirit.shift.2");
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 去掉小数点后缀（6.0 → 6） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 1.6.3：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.vengeful_spirit",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}
}
