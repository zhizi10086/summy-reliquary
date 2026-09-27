package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.DemonPact;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.LivingEntity;
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
 * 契约（The Pact，1.6.0）：装进**动态**的「恶魔契约」栏位。
 *
 * <p>它只能由「恶魔交易」签约时发放：签约后**强制佩戴**、**非创造模式无法摘除**、**死亡不掉**；
 * 忏悔（痛悔短祷成功使用）时连栏位一起被收回。效果全部挂在"佩戴契约"上（见 {@link DemonPact}）。
 */
public class ThePactItem extends Item implements ICurioItem {
	/** 恶魔话语的暗红 */
	private static final int DARK_RED = 0x8B0000;
	/** 1.6.3：本物品所属派系（恶魔线） */
	private static final com.summy.reliquary.text.ReliquaryFaction FACTION =
			com.summy.reliquary.text.ReliquaryFaction.DEMON;

	public ThePactItem(Properties properties) {
		super(properties);
	}

	/** 只允许装进「恶魔契约」栏位（重复佩戴由公共检查拦住） */
	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.DEMON_PACT);
	}

	/** 禁止右键快捷佩戴（契约只能由签约流程戴上） */
	@Override
	public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
		return false;
	}

	/** 非创造模式摘不下来；只有创造模式可以取下（便于调试） */
	@Override
	public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
		LivingEntity entity = slotContext.entity();
		return entity instanceof Player player && player.isCreative();
	}

	/** 死亡也不掉落 */
	@Override
	public ICurio.DropRule getDropRule(SlotContext slotContext, net.minecraft.world.damagesource.DamageSource source,
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
		LivingEntity self = ReliquaryTooltips.localPlayer();
		// 风味行（1.6.9：恶魔线深红）+ 引号行（恶魔话语：暗红斜体）
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.the_pact.tagline");
		tooltip.add(Component.translatable("item.summy-reliquary.the_pact.quote")
				.withStyle(demonStyle()));

		if (ReliquaryTooltips.shiftDown()) {
			// 1.6.3：七行属性统一按「属性名 + 数值」两段配色（恶魔线 = 深红 + 亮红）
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.damage",
					format(DemonPact.attackBonusPercent(self)));
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.attack_speed",
					format(DemonPact.attackSpeedBonusPercent(self)));
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.curses",
					String.valueOf(DemonPact.curseCount(self)));
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.health",
					format(DemonPact.healthBonusPercent(self)));
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.speed",
					format(DemonPact.speedBonusPercent(self)));
			// 生命吸取：只有诅咒 > 阈值时才显示
			if (DemonPact.lifestealActive(self)) {
				ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.lifesteal",
						format(ReliquaryConfig.pactLifestealPercent()));
			}
			ReliquaryTooltips.statLine(tooltip, FACTION, "item.summy-reliquary.the_pact.shift.evil",
					// 1.6.4：邪恶度显示一位小数（键与文案不变）
					String.format(java.util.Locale.ROOT, "%.1f", DemonPact.evilExact(self)),
					String.valueOf(ReliquaryConfig.evilMax()));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
		// 常驻最后一行：永远摘不掉
		tooltip.add(Component.translatable("item.summy-reliquary.the_pact.shift.bound")
				.withStyle(demonStyle()));
	}

	private static Style demonStyle() {
		return Style.EMPTY.withColor(TextColor.fromRgb(DARK_RED)).withItalic(true);
	}

	/** 提示里去掉多余的小数点（16.0 → 16，16.4 → 16.4） */
	private static String format(double value) {
		return Math.abs(value - Math.rint(value)) < 1.0E-6D
				? String.valueOf((long) Math.rint(value))
				: String.valueOf(Math.round(value * 10.0D) / 10.0D);
	}

	/** 1.6.3：物品名按派系上色（恶魔线 = 暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.the_pact", FACTION);
	}
}
