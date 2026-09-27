package com.summy.reliquary.item;

import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.network.chat.Component;
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
 * 美德（Virtues）：装入「魂印」栏位的饰品（暂无功能），由赎罪右击七罪之源转化而来。
 *
 * <p>名字为「中心纯白 → 两端亮金」的对称渐变；常态描述柔白 + 淡金扫光；
 * 按住 Shift 显示七德祷文，其中七行美德用亮金渐变光。
 */
public class VirtuesItem extends Item implements ICurioItem {
	/** 柔白（常态文本底色） */
	private static final int SOFT_WHITE = 0xF0F0F0;
	/** 淡金（常态文本高光） */
	private static final int PALE_GOLD = 0xFFE4B5;
	/** 亮金（七德文本） */
	private static final int BRIGHT_GOLD = 0xFFD700;
	/** 名字：中心纯白 / 两端亮金 */
	private static final int NAME_CENTER = 0xFFFFFF;
	private static final int NAME_EDGE = 0xFFD700;

	/** 七行美德的顺序与显示键 */
	private static final String[] VIRTUE_KEYS = {
			"item.summy-reliquary.virtue.humility",
			"item.summy-reliquary.virtue.charity",
			"item.summy-reliquary.virtue.chastity",
			"item.summy-reliquary.virtue.kindness",
			"item.summy-reliquary.virtue.temperance",
			"item.summy-reliquary.virtue.patience",
			"item.summy-reliquary.virtue.diligence"
	};

	public VirtuesItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.SOUL_SEAL);
	}

	/** 1.6.1：与七罪之源一致 —— 戴上后非创造模式摘不下来 */
	@Override
	public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
		return slotContext.entity() instanceof net.minecraft.world.entity.player.Player player && player.isCreative();
	}

	/** 1.6.1：与七罪之源一致 —— 死亡不掉落 */
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
	public Component getName(ItemStack stack) {
		// 名字很短（如中文“美德”只有两个字）时对称渐变没有中心可言，改用「白底金高光」扫光
		String name = Component.translatable("item.summy-reliquary.virtues").getString();
		return name.length() >= 3
				? SinTexts.symmetricGradient("item.summy-reliquary.virtues", NAME_CENTER, NAME_EDGE)
				: SinTexts.scrolling("item.summy-reliquary.virtues", NAME_CENTER, NAME_EDGE, false);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 1.4.4 双重门：七罪还没全部赎清时不能查阅属性（效果同样不生效）
		// 1.5.2：这时候连风味行「你已赎清罪过」都不显示，只留一行「七罪尚未赎清」
		if (!com.summy.reliquary.client.ReliquaryClientState.allRedeemed(
				com.summy.reliquary.sin.Sin.values().length)) {
			tooltip.add(Component.translatable("item.summy-reliquary.virtues.locked")
					.withStyle(net.minecraft.ChatFormatting.GRAY));
			return;
		}

		// 常态：柔白底 + 淡金扫光
		tooltip.add(SinTexts.scrolling("item.summy-reliquary.virtues.tagline", SOFT_WHITE, PALE_GOLD, false));

		if (ReliquaryTooltips.shiftDown()) {
			tooltip.add(SinTexts.scrolling("item.summy-reliquary.virtues.shift.1", SOFT_WHITE, PALE_GOLD, false));
			tooltip.add(SinTexts.scrolling("item.summy-reliquary.virtues.shift.2", SOFT_WHITE, PALE_GOLD, false));

			// 七行美德：整列一起做亮金渐变光
			int virtueColor = SinTexts.breathing(BRIGHT_GOLD, PALE_GOLD, 2400L);
			for (String virtueKey : VIRTUE_KEYS) {
				tooltip.add(SinTexts.colored(virtueKey, virtueColor));
				// 1.4.4：每条美德后补一行效果说明（数值读 [virtues] 配置）
				tooltip.add(Component.translatable(virtueKey + ".effect",
						com.summy.reliquary.effect.VirtuesEffects.descriptionArgs(virtueKey))
						.withStyle(net.minecraft.network.chat.Style.EMPTY
								.withColor(net.minecraft.network.chat.TextColor.fromRgb(virtueColor))));
			}

			tooltip.add(SinTexts.scrolling("item.summy-reliquary.virtues.shift.3", SOFT_WHITE, PALE_GOLD, false));
			tooltip.add(SinTexts.scrolling("item.summy-reliquary.virtues.shift.4", SOFT_WHITE, PALE_GOLD, false));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}
}
