package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邪恶度配方门禁（1.6.0）：未解锁时"收走产物 + 退回材料"。
 *
 * <p>配方本身是全局的（数据包条件做不到按玩家判定），所以照 {@code RedemptionRecipeGate} 的做法在
 * 取出产物时兜底；客户端装 JEI 时由 {@code ReliquaryJeiPlugin} 按玩家隐藏这些配方（见
 * {@link EvilUnlock#gatedRecipes()}）。解锁状态是**永久**的（`evil_unlocks` 位图，邪恶度衰减不收回）。
 */
public final class EvilRecipeGate {
	/**
	 * 受管配方的完整材料清单（按产物登记；没有登记的只收走产物、不退材料）。
	 *
	 * <p>1.7.0 起恶魔线的 6 张配方都有正式合成表，这里必须与
	 * {@code data/summy-reliquary/recipes/<item>.json} 里的材料**逐项一致**（自检有自动比对）。
	 */
	private static final Map<Item, List<ItemStack>> REFUNDS = new LinkedHashMap<>();

	static {
		REFUNDS.put(SummyReliquary.THE_MARK.get(), List.of(
				new ItemStack(SummyReliquary.SIX.get(), 3)));
		REFUNDS.put(SummyReliquary.VENGEFUL_SPIRIT.get(), List.of(
				new ItemStack(Items.BLAZE_POWDER, 4),
				new ItemStack(Items.SOUL_SAND, 4),
				new ItemStack(Items.NETHERITE_SCRAP)));
		REFUNDS.put(SummyReliquary.NIGHT_WRAITH.get(), List.of(
				new ItemStack(Items.PHANTOM_MEMBRANE, 4),
				new ItemStack(Items.ENDER_PEARL, 2),
				new ItemStack(Items.SOUL_SAND, 2),
				new ItemStack(Items.NETHERITE_SCRAP)));
		REFUNDS.put(SummyReliquary.BRIMSTONE.get(), List.of(
				new ItemStack(Items.BLAZE_ROD, 4),
				new ItemStack(Items.DRAGON_BREATH, 2),
				new ItemStack(Items.SOUL_SAND, 2),
				new ItemStack(Items.NETHER_STAR)));
		REFUNDS.put(SummyReliquary.OCCULT_EYE.get(), List.of(
				new ItemStack(Items.ENDER_EYE, 4),
				new ItemStack(Items.ECHO_SHARD, 3),
				new ItemStack(SummyReliquary.NIGHT_WRAITH.get()),
				new ItemStack(Items.NETHERITE_SCRAP)));
		REFUNDS.put(SummyReliquary.ABYSS_LORD.get(), List.of(
				new ItemStack(Items.WITHER_SKELETON_SKULL, 2),
				new ItemStack(Items.NETHER_STAR, 2),
				new ItemStack(Items.DRAGON_BREATH, 2),
				new ItemStack(Items.GHAST_TEAR),
				new ItemStack(Items.OBSIDIAN, 2)));
		// 1.7.6：暗仪刺刀（借 700 档）—— [ ][下界之星][ ] / [ ][献祭匕首][ ] / [黑曜石][下界合金锭][黑曜石]
		REFUNDS.put(SummyReliquary.DARK_ARTS.get(), List.of(
				new ItemStack(Items.NETHER_STAR),
				new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()),
				new ItemStack(Items.OBSIDIAN, 2),
				new ItemStack(Items.NETHERITE_INGOT)));
		REFUNDS.put(SummyReliquary.ABADDON.get(), List.of(
				new ItemStack(Items.ECHO_SHARD, 4),
				new ItemStack(Items.DRAGON_HEAD),
				new ItemStack(Items.NETHER_STAR, 2),
				new ItemStack(SummyReliquary.BRIMSTONE.get()),
				new ItemStack(Items.DRAGON_BREATH)));
	}

	private EvilRecipeGate() {
	}

	/** 自检用：受管配方的退料清单（产物 → 材料） */
	public static Map<Item, List<ItemStack>> refunds() {
		return new LinkedHashMap<>(REFUNDS);
	}

	/** 玩家取走合成产物时调用（服务端） */
	public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}
		ItemStack crafted = event.getCrafting();
		if (crafted.isEmpty()) {
			return;
		}
		var id = BuiltInRegistries.ITEM.getKey(crafted.getItem());
		if (id == null || !SummyReliquary.NAMESPACE.equals(id.getNamespace())) {
			return;
		}
		EvilUnlock unlock = EvilUnlock.byItemId(id.getPath());
		if (unlock == null) {
			return; // 不受管 → 正常放行
		}
		if (unlock.unlocked(PlayerFlags.evilUnlocks(player))) {
			// 已解锁 → 正常放行。1.6.9：进度「魔眼」就在这里判定 —— 只有真正走门禁放行的
			// 「夜之幽魂 + 3×「6」→ 玄秘魔眼」才算（被 700 拦下的强合、指令直接给都不算）。
			// 1.7.6：暗仪刺刀也借 700 档，但它**不该**触发「魔眼」进度，所以再确认产物本身。
			if (unlock == EvilUnlock.OCCULT_EYE
					&& crafted.getItem() == SummyReliquary.OCCULT_EYE.get()) {
				com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
						com.summy.reliquary.advancement.ReliquaryAdvancements.OCCULT_EYE_UPGRADED);
			}
			return;
		}

		int count = Math.max(1, crafted.getCount());
		// 1) 收走产物（可能在光标上，也可能已进背包）
		ItemStack carried = player.inventoryMenu.getCarried();
		if (carried.is(crafted.getItem())) {
			carried.shrink(count);
			player.inventoryMenu.setCarried(carried);
		} else {
			player.getInventory().clearOrCountMatchingItems(
					stack -> stack.is(crafted.getItem()), count, player.inventoryMenu.getCraftSlots());
		}
		// 2) 退回材料
		for (int round = 0; round < count; round++) {
			for (ItemStack refund : REFUNDS.getOrDefault(crafted.getItem(), List.of())) {
				ItemStack give = refund.copy();
				if (!player.getInventory().add(give)) {
					player.drop(give, false);
				}
			}
		}
		// 3) 提示（按"恶魔话语"约定：暗红斜体 + 行动栏）
		player.displayClientMessage(Component.translatable("message.summy-reliquary.recipe.evil_locked")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(DemonPact.CHAT_RED)).withItalic(true)), true);
	}
}
