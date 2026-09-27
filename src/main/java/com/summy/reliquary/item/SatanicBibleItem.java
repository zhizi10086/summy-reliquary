package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 撒旦圣经（Satanic Bible，1.6.1）：装入「魂印」栏位。
 *
 * <p>它不是直接发放的：**已签约 + 七罪全部激活**时，魂印栏里的七罪之源会**自动转化**成它
 * （见 {@code DemonPact.tickSatanicBible}）。佩戴期间：
 * <ul>
 *     <li>继承七罪的**全部加成**、**屏蔽全部负面**（{@code SinEffects} 里新增的分支）；</li>
 *     <li>「光环」属性**归零**（{@code HaloState} 返回 0）；</li>
 *     <li>黑心池上限 +1 心、每 30 秒补满、黑心被击碎时对周围敌人造成伤害。</li>
 * </ul>
 * 与七罪之源 / 美德同款：非创造模式摘不下来、死亡不掉落、禁止右键快捷佩戴。
 */
public class SatanicBibleItem extends Item implements ICurioItem {
	/** 恶魔话语的暗红 */
	private static final int DARK_RED = 0x8B0000;
	/** 1.6.3：本物品所属派系（恶魔线） */
	private static final com.summy.reliquary.text.ReliquaryFaction FACTION =
			com.summy.reliquary.text.ReliquaryFaction.DEMON;

	public SatanicBibleItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.SOUL_SEAL);
	}

	/** 与七罪之源一致：必须手动放进魂印栏（或由转化放入），不允许右键快捷佩戴 */
	@Override
	public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
		return false;
	}

	/** 戴上就摘不下来，只有创造模式可以取下 */
	@Override
	public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
		return slotContext.entity() instanceof Player player && player.isCreative();
	}

	/** 死亡也不掉落 */
	@Override
	public ICurio.DropRule getDropRule(SlotContext slotContext,
			net.minecraft.world.damagesource.DamageSource source,
			int lootingLevel, boolean recentlyHit, ItemStack stack) {
		return ICurio.DropRule.ALWAYS_KEEP;
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
		// 三行深红斜体风味（1.6.9：颜色统一到恶魔线 0xC03030，仍保持斜体）
		for (int index = 1; index <= 3; index++) {
			tooltip.add(Component.translatable("item.summy-reliquary.satanic_bible.tagline." + index)
					.withStyle(Style.EMPTY
							.withColor(TextColor.fromRgb(ReliquaryTooltips.DEMON_FLAVOR_COLOR))
							.withItalic(true)));
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 1.6.4：数值行按「属性名 + 数值」两段配色（恶魔线）；注意 shift.3 的参数顺序已改为（半径, 伤害）
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.satanic_bible.shift.1",
					format(ReliquaryConfig.satanicBibleBlackHearts()));
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.satanic_bible.shift.2",
					String.valueOf(ReliquaryConfig.satanicRefillSeconds()));
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.satanic_bible.shift.3",
					format(ReliquaryConfig.satanicShatterRadius()),
					format(ReliquaryConfig.satanicShatterDamage()));
			tooltip.add(Component.translatable("item.summy-reliquary.satanic_bible.shift.4")
					.withStyle(net.minecraft.ChatFormatting.GRAY));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 去掉小数点后缀（2.0 → 2） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 1.6.3：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.satanic_bible", FACTION);
	}
}
