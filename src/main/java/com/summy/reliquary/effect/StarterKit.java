package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * 首次进入世界的发放：七罪之源 + 光环（the_halo）。
 *
 * <p>两个物品都只放进背包、不自动佩戴；是否把七罪之源直接装进魂印栏由配置
 * {@code start.auto_equip_source_of_sins} 决定。用玩家持久化数据里的标记保证只发一次
 * （老存档玩家下次登录会补发一次，不会重复）。
 *
 * <p><b>1.6.6 修正</b>：标记以前写在 ForgeData 的**顶层**，而死亡复活时新实体只会带走 Forge 的
 * {@code PlayerPersisted} 子标签（Kilt 连这个都没实现）→ 标记整片丢失 → 下次登录又被当成"从没发过"，
 * 于是重发一份七罪之源 + 光环。现在标记写在**模组根标签**（{@code summy_reliquary.start_granted}）里，
 * 它由 {@code ReliquaryEvents#onPlayerClone} 复制到复活后的新实体；同时兼容读取旧位置。
 */
public final class StarterKit {
	/** 只发一次的标记名（1.6.6 起写在模组根标签里；也兼容读 ForgeData 顶层的旧位置） */
	private static final String GRANTED_FLAG = "start_granted";

	private StarterKit() {
	}

	/** 登录时调用：没发过就发一次 */
	public static void grantIfFirst(ServerPlayer player) {
		if (!ReliquaryConfig.grantStartItems()) {
			return;
		}
		if (alreadyGranted(player)) {
			return;
		}
		// 先落标记：判过一次就不再重复判，避免"补发到一半又崩"留下的半成品状态
		mutableRoot(player).putBoolean(GRANTED_FLAG, true);
		// 1.6.6 保险：老存档的标记丢过一次时，只要背包 / 饰品里已经有其中任意一件，就不再补发
		if (alreadyOwnsStarterItems(player)) {
			SummyReliquary.LOGGER.info(
					"[Summy Reliquary] {} 的初始饰品标记缺失，但背包 / 饰品里已经有七罪之源或光环 → 跳过补发",
					player.getName().getString());
			return;
		}

		ItemStack sins = new ItemStack(SummyReliquary.SOURCE_OF_SINS.get());
		boolean sinsEquipped = false;
		if (ReliquaryConfig.autoEquipSourceOfSins()) {
			ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
			if (handler != null) {
				handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
				sinsEquipped = handler.isEquipped(SummyReliquary.SOURCE_OF_SINS.get());
			}
		}
		if (!sinsEquipped) {
			give(player, sins);
		}
		give(player, new ItemStack(SummyReliquary.THE_HALO.get()));

		SummyReliquary.LOGGER.info("[Summy Reliquary] 向 {} 发放初始饰品（七罪之源{}、光环）",
				player.getName().getString(), sinsEquipped ? "已直接佩戴" : "放入背包");

		// 「有罪之人」的判据是「获得七罪之源」：发放完立刻判定一次
		com.summy.reliquary.advancement.SinChallenges.tickObtained(player);
	}

	/** 是否已经发放过（根标签优先，同时兼容 1.6.5 及以前写在顶层的旧标记） */
	public static boolean alreadyGranted(ServerPlayer player) {
		return root(player).getBoolean(GRANTED_FLAG) || player.getPersistentData().getBoolean(GRANTED_FLAG);
	}

	/** 清掉发放标记（自检与 {@code genesis reset} 用；两个位置都清，这样重置后能重新发放） */
	public static void resetGranted(ServerPlayer player) {
		mutableRoot(player).remove(GRANTED_FLAG);
		player.getPersistentData().remove(GRANTED_FLAG);
	}

	/** 模组根标签（不存在时返回一个游离的临时标签，只读用） */
	private static CompoundTag root(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		return data.contains(com.summy.reliquary.sin.SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(com.summy.reliquary.sin.SinManager.ROOT)
				: new CompoundTag();
	}

	/** 可写的模组根标签（不存在时新建并挂到玩家数据上） */
	private static CompoundTag mutableRoot(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		CompoundTag root = data.contains(com.summy.reliquary.sin.SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(com.summy.reliquary.sin.SinManager.ROOT)
				: new CompoundTag();
		data.put(com.summy.reliquary.sin.SinManager.ROOT, root);
		return root;
	}

	/** 背包（含快捷栏 / 护甲 / 副手）或任意 Curios 栏位里是否已经有七罪之源 / 光环 */
	private static boolean alreadyOwnsStarterItems(ServerPlayer player) {
		return owns(player, SummyReliquary.SOURCE_OF_SINS.get())
				|| owns(player, SummyReliquary.THE_HALO.get());
	}

	private static boolean owns(ServerPlayer player, net.minecraft.world.item.Item item) {
		for (ItemStack stack : player.getInventory().items) {
			if (stack.is(item)) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().armor) {
			if (stack.is(item)) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().offhand) {
			if (stack.is(item)) {
				return true;
			}
		}
		return com.summy.reliquary.util.CurioHelper.wears(player, item);
	}

	/** 背包优先，放不下就掉在脚下 */
	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}
}
