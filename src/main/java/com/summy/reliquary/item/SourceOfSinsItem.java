package com.summy.reliquary.item;

import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.client.ReliquaryClientState;
import com.summy.reliquary.sin.Sin;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
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
 * 七罪之源：装入「魂印」栏位的饰品（暂无效果）。
 *
 * <p>名字为深红底 + 亮红扫光；提示按「风味 → 栏位行 → Shift 祷文 / Shift 提示」输出，
 * 其中七行罪名单词用褪色暗红并做暗红 ↔ 亮红的呼吸渐变。
 *
 * <p>七罪有三种状态：未激活（灰）/ 已激活（暗红↔亮红呼吸）/ 已赎罪（金色 + 斜体 + 删除线）。
 * 七罪全部赎清后风味行换成亮蓝的「他等待你最后的救赎」；此物品非创造模式无法摘下、死亡也不掉落。
 */
public class SourceOfSinsItem extends Item implements ICurioItem {
	/** 未激活时罪名的颜色（灰） */
	private static final int INACTIVE_COLOR = 0x555555;
	/** 已赎罪时罪名的颜色（金） */
	private static final int REDEEMED_COLOR = 0xFFD700;
	/** 七罪赎清后的风味文本颜色（亮蓝） */
	private static final int FULLY_REDEEMED_COLOR = 0x55FFFF;

	public SourceOfSinsItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		// 完成「无罪之人」后无法再佩戴七罪之源
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.SOUL_SEAL)
				&& !com.summy.reliquary.effect.PlayerFlags.isSinRenounced(slotContext.entity());
	}

	/**
	 * 禁止手持时右键快捷佩戴：七罪之源必须手动放进魂印栏。
	 *
	 * <p>（Curios 的右键快捷佩戴只认这个方法；光环等其它饰品保持默认的允许。）
	 */
	@Override
	public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
		return false;
	}

	/** 戴上之后就摘不下来了；只有创造模式可以取下（仍可用「赎罪」把它变成美德脱离） */
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
	public Component getName(ItemStack stack) {
		// 深红为底、亮红扫光
		return SinTexts.scrolling("item.summy-reliquary.source_of_sins", SinTexts.SIN_FROM, SinTexts.PRAYER_FLASH, false);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 风味（褪色暗红；七罪赎清后换成亮蓝的等待救赎）；栏位行由 Curios 自带文本负责
		boolean allRedeemed = ReliquaryClientState.allRedeemed(Sin.values().length);
		tooltip.add(allRedeemed
				? SinTexts.colored("item.summy-reliquary.source_of_sins.tagline.redeemed", FULLY_REDEEMED_COLOR)
				: SinTexts.colored("item.summy-reliquary.source_of_sins.tagline", SinTexts.PRAYER_BASE));

		if (ReliquaryTooltips.shiftDown()) {
			tooltip.add(SinTexts.colored("item.summy-reliquary.source_of_sins.shift.1", SinTexts.PRAYER_BASE));
			tooltip.add(SinTexts.colored("item.summy-reliquary.source_of_sins.shift.2", SinTexts.PRAYER_BASE));
			tooltip.add(SinTexts.colored("item.summy-reliquary.source_of_sins.shift.3", SinTexts.PRAYER_BASE));

			// 每个罪最多三行：罪名 / ·增益（未激活时是触发条件）/ ·减益（赎罪后是赎罪文本）
			// 整块颜色沿用三态：未激活灰、已激活暗红↔亮红呼吸、已赎罪纯金色（不再用斜体与删除线）
			int sinColor = SinTexts.breathing(SinTexts.PRAYER_BASE, SinTexts.PRAYER_FLASH, 5000L);
			for (Sin sin : Sin.values()) {
				int ordinal = sin.ordinal();
				SinManager.SinState state;
				if (ReliquaryClientState.isRedeemed(ordinal)) {
					state = SinManager.SinState.REDEEMED;
				} else if (ReliquaryClientState.isSinActive(ordinal)) {
					state = SinManager.SinState.ACTIVATED;
				} else {
					state = SinManager.SinState.UNACTIVATED;
				}
				int color = switch (state) {
					case REDEEMED -> REDEEMED_COLOR;
					case ACTIVATED -> sinColor;
					default -> INACTIVE_COLOR;
				};
				Style style = Style.EMPTY.withColor(TextColor.fromRgb(color));
				// 1.8.2：每个罪的整块按提示框宽度折行，长句不再顶出屏幕
				ReliquaryTooltips.add(tooltip, styled(Component.translatable(sin.nameKey()), style));
				ReliquaryTooltips.add(tooltip,
						bullet(com.summy.reliquary.sin.SinDescriptions.secondLine(sin, state), style));
				Component third = com.summy.reliquary.sin.SinDescriptions.thirdLine(sin, state);
				if (third != null) {
					ReliquaryTooltips.add(tooltip, bullet(third, style));
				}
			}

			tooltip.add(SinTexts.colored("item.summy-reliquary.source_of_sins.shift.4", SinTexts.PRAYER_BASE));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}

		// 非创造模式提示摘不下来：文案换成「你无法逃脱」，并用与七罪祷文相同的呼吸特效
		Player local = ReliquaryTooltips.localPlayer() instanceof Player candidate ? candidate : null;
		if (local != null && !local.isCreative()) {
			tooltip.add(SinTexts.colored("item.summy-reliquary.source_of_sins.locked",
					SinTexts.breathing(SinTexts.PRAYER_BASE, SinTexts.PRAYER_FLASH, 5000L)));
		}
		// 完成「无罪之人」后：无法再佩戴
		if (local != null && com.summy.reliquary.effect.PlayerFlags.isSinRenounced(local)) {
			tooltip.add(SinTexts.colored("item.summy-reliquary.source_of_sins.renounced",
					SinTexts.breathing(SinTexts.PRAYER_BASE, SinTexts.PRAYER_FLASH, 5000L)));
		}
	}

	/** 把整行（含追加的说明）统一着色：Minecraft 的样式是按段生效的，所以要逐段设置 */
	private static MutableComponent styled(Component component, Style style) {
		return component.copy().setStyle(style);
	}

	/** 「·说明」这一行：点号与说明都显式着色，避免依赖样式继承 */
	private static MutableComponent bullet(Component text, Style style) {
		return Component.literal("·").setStyle(style).append(text.copy().setStyle(style));
	}
}
