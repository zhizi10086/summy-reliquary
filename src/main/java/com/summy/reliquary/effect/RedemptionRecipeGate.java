package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.sin.SinManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * 「七宗罪碎片 + 下界之星 → 赎罪」这张合成表的开放条件。
 *
 * <p>配方本身是全局的（数据包条件做不到按玩家判定），所以这里在取出产物时兜底：
 * 玩家七罪未全部赎罪时，把刚做出来的赎罪收走、并把 8 个材料退回背包（背包满则掉在脚下）。
 * 客户端装 JEI 时由 {@code ReliquaryJeiPlugin} 按玩家隐藏这张配方。
 */
public final class RedemptionRecipeGate {
	/** 配方的 8 个材料（与 redemption.json 保持一致） */
	private static final List<Item> INGREDIENTS = List.of(
			SummyReliquary.SIN_FRAGMENT_PRIDE.get(),
			SummyReliquary.SIN_FRAGMENT_GREED.get(),
			SummyReliquary.SIN_FRAGMENT_LUST.get(),
			SummyReliquary.SIN_FRAGMENT_ENVY.get(),
			SummyReliquary.SIN_FRAGMENT_GLUTTONY.get(),
			SummyReliquary.SIN_FRAGMENT_WRATH.get(),
			SummyReliquary.SIN_FRAGMENT_SLOTH.get(),
			Items.NETHER_STAR);

	private RedemptionRecipeGate() {
	}

	/** 玩家取走合成产物时调用（服务端） */
	public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}
		ItemStack crafted = event.getCrafting();
		if (!crafted.is(SummyReliquary.REDEMPTION.get())) {
			return;
		}
		// 七罪全部赎清 → 正常放行
		if (SinManager.allRedeemed(player)) {
			return;
		}

		int count = Math.max(1, crafted.getCount());
		// 1) 把刚做出来的赎罪收走（可能在光标上，也可能已进背包）
		ItemStack carried = player.inventoryMenu.getCarried();
		if (carried.is(SummyReliquary.REDEMPTION.get())) {
			carried.shrink(count);
			player.inventoryMenu.setCarried(carried);
		} else {
			player.getInventory().clearOrCountMatchingItems(
					stack -> stack.is(SummyReliquary.REDEMPTION.get()), count,
					player.inventoryMenu.getCraftSlots());
		}

		// 2) 退回材料
		for (int round = 0; round < count; round++) {
			for (Item item : INGREDIENTS) {
				ItemStack stack = new ItemStack(item);
				if (!player.getInventory().add(stack)) {
					player.drop(stack, false);
				}
			}
		}

		player.displayClientMessage(Component.translatable("message.summy-reliquary.recipe.locked"), true);
	}
}
