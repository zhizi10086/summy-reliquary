package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.compat.EnchantmentReforgedCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * 「白饭」效果：佩戴期间把饥饿值锁定为当前饥饿上限、饱和度锁定为 0，并禁止一切吃 / 喝行为。
 *
 * <p>饥饿上限默认是原版的 20；若运行环境里存在 Enchantment Reforged（Kilt/Fabric 侧），
 * 则通过反射使用它的 {@code EnchantmentEffects.foodLevelCap}（胸部「大胃袋」附魔会把上限抬高），
 * 因此换上 / 脱下带大胃袋的胸甲时锁定值会自动跟随。
 */
public final class RiceHungerLock {
	/** 原版饥饿上限 */
	private static final int VANILLA_FOOD_CAP = 20;

	private RiceHungerLock() {
	}

	/** 服务端每 tick 调用：锁定饥饿值与饱和度 */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// 暴食（未赎罪）时白饭失效：既不再锁饥饿，也不再禁止进食
			if (wearsRice(player) && !com.summy.reliquary.sin.SinEffects.gluttonyDisablesRice(player)) {
				lockHunger(player);
			}
		}
	}

	/** 佩戴白饭时拦下吃喝；返回 true 表示已经取消这次交互 */
	public static boolean tryBlockEating(Player player, PlayerInteractEvent event) {
		ItemStack stack = event.getItemStack();
		if (wearsRice(player) && !com.summy.reliquary.sin.SinEffects.gluttonyDisablesRice(player)
				&& isConsumable(stack)) {
			event.setCanceled(true);
			return true;
		}
		return false;
	}

	/** 当前饥饿上限：存在 Enchantment Reforged 时用「大胃袋」算出的上限 */
	public static int foodCap(Player player) {
		return EnchantmentReforgedCompat.foodLevelCap(player).orElse(VANILLA_FOOD_CAP);
	}

	/** 是否佩戴着白饭（只能装在「胃袋」栏位） */
	public static boolean wearsRice(Player player) {
		return CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.isEquipped(SummyReliquary.FREELOADERS_RICE.get()))
				.orElse(false);
	}

	/** 吃 / 喝类物品（EAT / DRINK）都算「可消耗品」 */
	public static boolean isConsumable(ItemStack stack) {
		UseAnim action = stack.getUseAnimation();
		return action == UseAnim.EAT || action == UseAnim.DRINK;
	}

	private static void lockHunger(ServerPlayer player) {
		FoodData food = player.getFoodData();
		int cap = foodCap(player);

		if (food.getFoodLevel() != cap) {
			food.setFoodLevel(cap);
		}
		if (food.getSaturationLevel() != 0.0F) {
			food.setSaturation(0.0F);
		}
		// 堵住「吃到一半才戴上白饭」的情况
		if (player.isUsingItem() && isConsumable(player.getUseItem())) {
			player.stopUsingItem();
		}
	}
}
