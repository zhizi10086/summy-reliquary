package com.summy.reliquary.item;

import com.google.common.collect.Multimap;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;
import java.util.UUID;

/**
 * 恶魔王冠（1.7.1）：光环栏饰品（与光环 / 邦邦咔邦光环互斥）。
 *
 * <p><b>需佩戴撒旦圣经才生效</b>；属性由 {@code AttributeManager} 每秒重算（固定值，不受光环的倍率影响）；
 * 邪恶 666（`hell_locked`）后按"已损失生命"获得分出加成（见 {@code DevilCrown.damageBonusPercent}）。
 *
 * <p>获取：在"七罪之源 → 撒旦圣经"转化成功那一刻发放一次；**死亡不掉落**；
 * 签约与忏悔都不会发放或没收它，只有创世纪会删掉（连"已发放"标记一起清）。
 */
public class DevilCrownItem extends Item implements ICurioItem {
	/** 风味行颜色（恶魔线深红，与 1.6.9 的规范一致） */
	private static final int FLAVOR_RED = 0xC03030;
	/** 条件行颜色（同样是恶魔线深红，整句不拆色） */
	private static final int CONDITIONAL_RED = 0xC03030;
	/** 1.7.1：本物品所属派系（恶魔线） */
	private static final com.summy.reliquary.text.ReliquaryFaction FACTION =
			com.summy.reliquary.text.ReliquaryFaction.DEMON;

	public DevilCrownItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.HALO);
	}

	/** 与「光环」一致：允许手持右键快捷佩戴 */
	@Override
	public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
		return true;
	}

	/** 死亡不掉落 */
	@Override
	public ICurio.DropRule getDropRule(SlotContext slotContext, DamageSource source,
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

	/** 属性全部由 {@code AttributeManager} 写入，避免 Curios 只看单格重算时的漏算 */
	@Override
	public Multimap<Attribute, AttributeModifier> getAttributeModifiers(SlotContext slotContext, UUID uuid,
			ItemStack stack) {
		return CurioItemSupport.noAttributes();
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 风味两行（恶魔线深红）
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.devil_crown.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.devil_crown.tagline.2");

		if (!ReliquaryTooltips.shiftDown()) {
			tooltip.add(ReliquaryTooltips.shiftHint());
			return;
		}
		tooltip.addAll(shiftLines(ReliquaryTooltips.localPlayer()));
	}

	/**
	 * Shift 功能行（抽成纯函数便于自检逐行断言）：
	 * 6 条属性行（两段配色）+ 一行灰色说明 + （邪恶 666 后）一行恶魔深红条件行。
	 */
	public static List<Component> shiftLines(LivingEntity self) {
		List<Component> lines = new java.util.ArrayList<>();
		// 六项属性（固定值，走「属性名|数值」两段配色）
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.devil_crown.shift.1",
				ReliquaryConfig.devilCrownMovementPercent()));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.devil_crown.shift.2",
				format(ReliquaryConfig.devilCrownAttackSpeed())));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.devil_crown.shift.3",
				format(ReliquaryConfig.devilCrownMeleeDamage())));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.devil_crown.shift.4",
				format(ReliquaryConfig.devilCrownMaxHealth())));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.devil_crown.shift.5",
				format(ReliquaryConfig.devilCrownArmor())));
		lines.add(ReliquaryTooltips.statComponent(FACTION, "item.summy-reliquary.devil_crown.shift.6",
				format(ReliquaryConfig.devilCrownArmorToughness())));
		// 说明行（灰）
		lines.add(Component.translatable("item.summy-reliquary.devil_crown.shift.note")
				.withStyle(ChatFormatting.GRAY));
		// 条件行：只在"已被永久锁定天使线"（邪恶 666）时显示；整句恶魔深红、不拆句
		if (self != null && PlayerFlags.isHellLocked(self)) {
			lines.add(Component.translatable("item.summy-reliquary.devil_crown.shift.conditional")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(CONDITIONAL_RED))));
		}
		return lines;
	}

	/** 去掉小数点后缀（0.4 → 0.4、-4.0 → -4） */
	private static String format(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 恶魔线物品名（暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.devil_crown",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}
}
