package com.summy.reliquary.item;

import com.summy.reliquary.effect.ShadowDash;
import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 仪式匕首（1.7.5）：献祭匕首与暗仪刺刀共用同一套实现。
 *
 * <p>只做两件事：物品名按**恶魔线**上色；右键触发 {@link ShadowDash}「遁入暗影」。
 * 两者只有参数不同（持续时间、斩击倍率），分别在构造时由 {@code darkArts} 区分。
 */
public class RitualDaggerItem extends SwordItem {
	/** 物品名的语言键 */
	private final String nameKey;
	/** true = 暗仪刺刀（2 秒 / 2 倍），false = 献祭匕首（1 秒 / 1 倍） */
	private final boolean darkArts;

	public RitualDaggerItem(Tier tier, int attackDamageModifier, float attackSpeedModifier,
			Properties properties, String nameKey, boolean darkArts) {
		super(tier, attackDamageModifier, attackSpeedModifier, properties);
		this.nameKey = nameKey;
		this.darkArts = darkArts;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		// 1.7.9：未达标（没签过约 / 邪恶 700 未解锁）→ 完全禁用，右键只给提示
		if (!com.summy.reliquary.effect.WeaponGates.qualified(player, this)) {
			if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
				serverPlayer.displayClientMessage(Component.translatable(
						com.summy.reliquary.effect.WeaponGates.hintKey(player, this)), true);
			}
			return InteractionResultHolder.fail(stack);
		}
		if (level.isClientSide()) {
			return InteractionResultHolder.sidedSuccess(stack, true);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		// 1.8.2：暗仪刺刀仍是「遁入暗影」；献祭匕首换成「献祭」（自损换近战增伤）
		boolean started = darkArts
				? com.summy.reliquary.effect.ShadowDash.tryStart(serverPlayer, stack, true)
				: com.summy.reliquary.effect.Sacrifice.tryUse(serverPlayer);
		if (!started) {
			return InteractionResultHolder.fail(stack);
		}
		return InteractionResultHolder.success(stack);
	}

	/** 物品名按恶魔线上色（暗红底 + 亮红扫光） */
	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName(nameKey, ReliquaryFaction.DEMON);
	}

	/**
	 * 1.7.6：风味一行常驻；Shift 展开「遁入暗影」的说明。
	 *
	 * <p>配色沿用既有规范：风味行恶魔深红，Shift 的标题与叙述行统一灰色；
	 * 暗仪刺刀比献祭匕首多一行括注（无敌更久、斩击更痛）。
	 */
	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
			TooltipFlag flag) {
		ReliquaryTooltips.demonFlavorLine(tooltip, nameKey + ".tagline.1");
		// 1.8.2：未进恶魔线（暗仪刺刀还要邪恶 700）时与其它路线饰品一致 —— 不可查阅功能
		if (ReliquaryTooltips.weaponLocked(stack.getItem())) {
			ReliquaryTooltips.appendWeaponHint(tooltip, stack.getItem());
			return;
		}
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.1");
			if (darkArts) {
				// 1.8.4：三处联动数值按"当前是否佩戴亚巴顿"实时显示
				//（时长 2 → 3 秒、接触半径 2 → 3 格、强力斩击半径 3 → 5 格）
				net.minecraft.world.entity.LivingEntity self = ReliquaryTooltips.localPlayer();
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.2",
						ReliquaryTooltips.number(com.summy.reliquary.effect.Synergies
								.shadowDashDurationTicks(self, true) / 20.0D));
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.3",
						ReliquaryTooltips.number(com.summy.reliquary.effect.Synergies
								.shadowDashContactRadius(self)));
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.4",
						ReliquaryTooltips.number(com.summy.reliquary.effect.Synergies
								.shadowDashHeavyRadius(self)));
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.5");
			} else {
				// 献祭匕首（1.8.2 起走「献祭」技能），三行都无联动数值
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.2");
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.3");
				ReliquaryTooltips.narrativeLine(tooltip, nameKey + ".shift.4");
			}
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/**
	 * 1.7.6：死亡不掉落 —— 只要**原玩家身上有**对应匕首、新玩家身上没有，就补回一把。
	 *
	 * <p>与创世纪同款口径：主动丢弃 / 丢进虚空后原玩家身上本来就没有 → 不补发。
	 */
	public static void restoreOnDeath(ServerPlayer player, boolean hadSacrificial, boolean hadDarkArts) {
		if (player == null) {
			return;
		}
		restoreOne(player, hadSacrificial, com.summy.reliquary.SummyReliquary.SACRIFICIAL_DAGGER.get());
		restoreOne(player, hadDarkArts, com.summy.reliquary.SummyReliquary.DARK_ARTS.get());
	}

	private static void restoreOne(ServerPlayer player, boolean had, net.minecraft.world.item.Item item) {
		if (!had || com.summy.reliquary.effect.DaggerRecovery.holds(player, item)) {
			return;
		}
		ItemStack stack = new ItemStack(item);
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}
}
