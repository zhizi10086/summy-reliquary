package com.summy.reliquary.item;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.effect.CovenantActions;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.LivingEntity;
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
 * 誓约栏位的五件主动饰品。
 *
 * <p>五件共用同一个 Curios 栏位，因此用一个物品基类统一栏位校验、死亡不掉落和提示排版；
 * 各自的运行逻辑仍由 {@link CovenantActions} 分派到独立效果类。
 */
public class CovenantItem extends Item implements ICurioItem {
	private static final int LIFE_COLOR = 0xBFE3FF;
	private static final int DEATH_COLOR = 0xC03030;
	private final Kind kind;

	public CovenantItem(Properties properties, Kind kind) {
		super(properties);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		java.util.function.Predicate<LivingEntity> gate = switch (kind.gate()) {
			case NONE -> null;
			case ANGEL -> PlayerFlags::hasAngel;
			case DEMON -> PlayerFlags::isDemon;
		};
		if (!CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.COVENANT,
				false, gate, kind.gateReasonKey())) {
			return false;
		}
		// 誓约五件是同一互斥组：即使外部指令强行扩容栏位，也不允许同时佩戴两件。
		return !anotherCovenantEquipped(slotContext);
	}

	private static boolean anotherCovenantEquipped(SlotContext context) {
		LivingEntity entity = context.entity();
		if (entity == null) {
			return false;
		}
		var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity).orElse(null);
		if (handler == null) {
			return false;
		}
		for (var entry : handler.getCurios().entrySet()) {
			for (int index = 0; index < entry.getValue().getSlots(); index++) {
				if (entry.getKey().equals(context.identifier()) && index == context.index()) {
					continue;
				}
				ItemStack worn = entry.getValue().getStacks().getStackInSlot(index);
				if (worn.getItem() instanceof CovenantItem) {
					return true;
				}
			}
		}
		return false;
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
	public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
		if (slotContext.entity() instanceof net.minecraft.server.level.ServerPlayer player) {
			CovenantActions.onUnequip(player, kind);
			com.summy.reliquary.effect.AttributeManager.apply(player);
		}
	}

	@Override
	public void onEquip(SlotContext slotContext, ItemStack previousStack, ItemStack stack) {
		if (slotContext.entity() instanceof net.minecraft.server.level.ServerPlayer player) {
			com.summy.reliquary.effect.AttributeManager.apply(player);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
			TooltipFlag flag) {
		appendFlavor(tooltip, kind, "desc.1", true);
		appendFlavor(tooltip, kind, "desc.2", false);

		if (locked(kind)) {
			if (kind.gate() == Gate.ANGEL) {
				ReliquaryTooltips.appendAngelHint(tooltip);
			} else if (kind.gate() == Gate.DEMON) {
				ReliquaryTooltips.appendDemonHint(tooltip);
			}
			return;
		}

		if (kind == Kind.LIFE_DEATH) {
			appendLifeDeathState(tooltip);
		}
		if (!ReliquaryTooltips.shiftDown()) {
			tooltip.add(ReliquaryTooltips.shiftHint());
			return;
		}
		for (int index = 1; index <= kind.functionLineCount(); index++) {
			ReliquaryTooltips.statLine(tooltip, kind.faction(),
					"item.summy-reliquary." + kind.id() + ".shift." + index,
					CovenantActions.tooltipArguments(kind));
		}
	}

	private static void appendFlavor(List<Component> tooltip, Kind kind, String suffix, boolean primary) {
		String key = "item.summy-reliquary." + kind.id() + "." + suffix;
		MutableComponent line = Component.translatable(key);
		if (kind.faction() == ReliquaryFaction.DEMON) {
			line.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(ReliquaryTooltips.DEMON_FLAVOR_COLOR)));
		} else if (kind.faction() == ReliquaryFaction.ANGEL) {
			line.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(ReliquaryFaction.ANGEL.nameColor())));
		} else {
			line.withStyle(primary ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY);
		}
		ReliquaryTooltips.add(tooltip, line);
	}

	private static void appendLifeDeathState(List<Component> tooltip) {
		boolean death = PlayerFlags.isDeathForm(ReliquaryTooltips.localPlayer());
		int currentColor = death ? DEATH_COLOR : LIFE_COLOR;
		int nextColor = death ? LIFE_COLOR : DEATH_COLOR;
		tooltip.add(Component.translatable("item.summy-reliquary.life_death.state.current",
				Component.translatable(death
						? "item.summy-reliquary.life_death.form.death"
						: "item.summy-reliquary.life_death.form.life")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(currentColor))))
				.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable("item.summy-reliquary.life_death.state.next",
				Component.translatable(death
						? "item.summy-reliquary.life_death.form.life"
						: "item.summy-reliquary.life_death.form.death")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(nextColor))))
				.withStyle(ChatFormatting.GRAY));
	}

	private static boolean locked(Kind kind) {
		LivingEntity player = ReliquaryTooltips.localPlayer();
		if (player == null) {
			return false;
		}
		return switch (kind.gate()) {
			case NONE -> false;
			case ANGEL -> !PlayerFlags.hasAngel(player);
			case DEMON -> !PlayerFlags.isDemon(player);
		};
	}

	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary." + kind.id(), kind.faction());
	}

	@Override
	public ICurio.DropRule getDropRule(SlotContext slotContext, net.minecraft.world.damagesource.DamageSource source,
			int lootingLevel, boolean recentlyHit, ItemStack stack) {
		return CurioItemSupport.keepOnDeath();
	}

	public enum Gate {
		NONE(""),
		ANGEL("item.summy-reliquary.angel.required"),
		DEMON("item.summy-reliquary.demon.required");

		private final String reasonKey;

		Gate(String reasonKey) {
			this.reasonKey = reasonKey;
		}

		public String reasonKey() {
			return reasonKey;
		}
	}

	public enum Kind {
		LIFE_DEATH("life_death", Gate.NONE, ReliquaryFaction.NEUTRAL, 6),
		DIVINE_INTERVENTION("divine_intervention", Gate.ANGEL, ReliquaryFaction.ANGEL, 4),
		SACRED_VERDICT("sacred_verdict", Gate.ANGEL, ReliquaryFaction.ANGEL, 5),
		BLOOD_DEBT_ALTAR("blood_debt_altar", Gate.DEMON, ReliquaryFaction.DEMON, 5),
		HEX_SEAL("hex_seal", Gate.DEMON, ReliquaryFaction.DEMON, 5);

		private final String id;
		private final Gate gate;
		private final ReliquaryFaction faction;
		private final int functionLineCount;

		Kind(String id, Gate gate, ReliquaryFaction faction, int functionLineCount) {
			this.id = id;
			this.gate = gate;
			this.faction = faction;
			this.functionLineCount = functionLineCount;
		}

		public String id() {
			return id;
		}

		public Gate gate() {
			return gate;
		}

		public ReliquaryFaction faction() {
			return faction;
		}

		public int functionLineCount() {
			return functionLineCount;
		}

		public String gateReasonKey() {
			return gate.reasonKey();
		}

		public static Kind byId(String id) {
			for (Kind value : values()) {
				if (value.id.equals(id)) {
					return value;
				}
			}
			return null;
		}

		public Item item() {
			return switch (this) {
				case LIFE_DEATH -> SummyReliquary.LIFE_DEATH.get();
				case DIVINE_INTERVENTION -> SummyReliquary.DIVINE_INTERVENTION.get();
				case SACRED_VERDICT -> SummyReliquary.SACRED_VERDICT.get();
				case BLOOD_DEBT_ALTAR -> SummyReliquary.BLOOD_DEBT_ALTAR.get();
				case HEX_SEAL -> SummyReliquary.HEX_SEAL.get();
			};
		}
	}
}
