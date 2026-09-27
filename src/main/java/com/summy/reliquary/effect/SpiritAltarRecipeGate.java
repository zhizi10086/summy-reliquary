package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「天使线 + 仪式法袍」配方的服务端门禁（1.7.0 起由"占位表"泛化为按物品登记的门禁表）。
 *
 * <p>两类判据：
 * <ul>
 *     <li><b>天使标记</b>：肉体 / 思想 / 灵魂 / 圣光 / 神圣斗篷 / 神性 / 救恩；</li>
 *     <li><b>已签约</b>（曾签过契约或当前是恶魔）：仪式法袍。</li>
 * </ul>
 *
 * <p>配方本身是全局的（数据包条件做不到按玩家判定），所以照旧在玩家**取走产物**时兜底：
 * 不满足判据就把产物收走、把**该配方的全部材料逐份退回**（背包满则掉在脚下），并给一条提示；
 * 客户端装 JEI 时由 {@code ReliquaryJeiPlugin} 按玩家状态隐藏（见 {@link #jeiVisibility}）。
 *
 * <p>救恩的判据同样是天使标记，但它**在 JEI 里永久隐藏**（{@link Lock#ALWAYS_HIDDEN}）——
 * 这是 1.5.6 起就有的行为，本轮保持不变。
 *
 * <p>另外这里还管「三位一体」的**退还三件套**：那张配方没有门槛，但按需求**只实耗心之碎片**，
 * 肉体 / 思想 / 灵魂在取产物时原样退回（{@link #RETURNED}）。
 */
public final class SpiritAltarRecipeGate {
	/** 门禁类型 */
	private enum Lock {
		/** 需要天使标记（JEI：有标记才可见） */
		ANGEL,
		/** 需要已签约（JEI：已签约才可见） */
		SIGNED,
		/** 服务端需要天使标记，但 JEI 里永久隐藏（救恩） */
		ALWAYS_HIDDEN,
		/** 1.7.6：献祭匕首的「防丢失配方」—— 只有"长时间没有匕首"时才开放 */
		DAGGER_LOST,
		/** 1.7.9：圣光短矛的「防丢失配方」—— 只有"曾获得过 + 长时间没有长矛"且仍有天使标记时才开放 */
		SPEAR_LOST
	}

	/** 一条门禁：判据类型 + 不满足时要退回的全部材料 */
	private record Gate(Lock lock, List<ItemStack> refunds) {
	}

	/** 受管配方：产物 → 门禁 */
	private static final Map<Item, Gate> GATES = new LinkedHashMap<>();
	/** 无门槛但"取产物时要退还部分材料"的配方：产物 → 要退还的材料 */
	private static final Map<Item, List<ItemStack>> RETURNED = new LinkedHashMap<>();

	static {
		// ===== 天使线：需要天使标记 =====
		register(SummyReliquary.THE_BODY.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.BEEF), new ItemStack(Items.PRISMARINE_CRYSTALS), new ItemStack(Items.MUTTON),
				new ItemStack(Items.BONE, 3), new ItemStack(Items.GOLDEN_APPLE),
				new ItemStack(Items.CHICKEN), new ItemStack(Items.PORKCHOP)));
		register(SummyReliquary.THE_MIND.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.BOOK, 4), new ItemStack(Items.AMETHYST_SHARD, 4),
				new ItemStack(Items.EXPERIENCE_BOTTLE)));
		register(SummyReliquary.THE_SOUL.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.SOUL_SAND, 4), new ItemStack(Items.ENDER_PEARL, 4),
				new ItemStack(Items.GLOWSTONE)));
		register(SummyReliquary.HOLY_LIGHT.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.GLOWSTONE, 4), new ItemStack(Items.SEA_LANTERN, 4),
				new ItemStack(Items.GOLDEN_APPLE)));
		register(SummyReliquary.HOLY_MANTLE.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.WHITE_WOOL, 4), new ItemStack(Items.PRISMARINE_CRYSTALS, 2),
				new ItemStack(Items.PRISMARINE_SHARD, 2), new ItemStack(SummyReliquary.WOODEN_CROSS.get())));
		register(SummyReliquary.GODHEAD.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.ECHO_SHARD, 4), new ItemStack(Items.DRAGON_HEAD),
				new ItemStack(Items.NETHER_STAR, 2), new ItemStack(SummyReliquary.FINAL_REVELATION.get()),
				new ItemStack(SummyReliquary.TRINITY.get())));
		// 圣心（1.7.1 修正：以前它的配方完全没门禁，JEI 里谁都看得到）
		register(SummyReliquary.SACRED_HEART.get(), Lock.ANGEL, List.of(
				new ItemStack(SummyReliquary.HEART_SHARD.get(), 6),
				new ItemStack(Items.BLAZE_POWDER, 2),
				new ItemStack(SummyReliquary.WOODEN_CROSS.get())));
		// 救恩：判据同上，但 JEI 永久隐藏（1.5.6 起的既有行为）
		register(SummyReliquary.SALVATION.get(), Lock.ALWAYS_HIDDEN, List.of(
				new ItemStack(Items.BREAD, 5), new ItemStack(Items.COOKED_COD, 2)));

		// ===== 献祭匕首的「防丢失配方」（1.7.6）：只在"连续一段时间没匕首"时开放 =====
		register(SummyReliquary.SACRIFICIAL_DAGGER.get(), Lock.DAGGER_LOST, List.of(
				new ItemStack(Items.NETHERITE_SCRAP), new ItemStack(Items.IRON_INGOT),
				new ItemStack(Items.OBSIDIAN)));

		// ===== 天使线长矛（1.7.9）：炽天使之枪 = 升级表（需天使标记，中心消耗圣光短矛） =====
		register(SummyReliquary.SERAPH_SPEAR.get(), Lock.ANGEL, List.of(
				new ItemStack(Items.NETHER_STAR), new ItemStack(SummyReliquary.HEART_SHARD.get(), 2),
				new ItemStack(SummyReliquary.HOLY_SPEAR.get()), new ItemStack(Items.GOLD_BLOCK)));
		// 圣光短矛的「防丢失配方」：丢失态 + 天使标记（把圣光还给天使，才能重铸一柄）
		register(SummyReliquary.HOLY_SPEAR.get(), Lock.SPEAR_LOST, List.of(
				new ItemStack(SummyReliquary.HOLY_LIGHT.get()), new ItemStack(Items.TRIDENT),
				new ItemStack(Items.GLOWSTONE_DUST, 2), new ItemStack(Items.GOLD_BLOCK)));

		// ===== 恶魔侧（但门槛不是邪恶度）：仪式法袍 =====
		register(SummyReliquary.CEREMONIAL_ROBES.get(), Lock.SIGNED, List.of(
				new ItemStack(Items.GOLD_INGOT, 3), new ItemStack(Items.BLACK_WOOL, 5),
				new ItemStack(Items.NETHERITE_SCRAP)));

		// ===== 三位一体：无门槛，但只实耗心之碎片 → 取产物时退还三件套 =====
		RETURNED.put(SummyReliquary.TRINITY.get(), List.of(
				new ItemStack(SummyReliquary.THE_BODY.get()),
				new ItemStack(SummyReliquary.THE_MIND.get()),
				new ItemStack(SummyReliquary.THE_SOUL.get())));
	}

	private SpiritAltarRecipeGate() {
	}

	private static void register(Item result, Lock lock, List<ItemStack> refunds) {
		GATES.put(result, new Gate(lock, refunds));
	}

	/** 自检用：受管配方的退料清单（产物 → 材料） */
	public static Map<Item, List<ItemStack>> gateRefunds() {
		Map<Item, List<ItemStack>> copy = new LinkedHashMap<>();
		GATES.forEach((item, gate) -> copy.put(item, gate.refunds()));
		return copy;
	}

	/** 自检用：无门槛但要退还材料的配方（产物 → 退还的材料） */
	public static Map<Item, List<ItemStack>> returnedRefunds() {
		return new LinkedHashMap<>(RETURNED);
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
		Item item = crafted.getItem();
		int count = Math.max(1, crafted.getCount());

		// ① 有条件门禁：不满足 → 收走产物 + 退回全部材料 + 提示
		Gate gate = GATES.get(item);
		if (gate != null && !allowed(player, gate.lock())) {
			confiscate(player, crafted, count);
			give(player, gate.refunds(), count);
			player.displayClientMessage(Component.translatable(lockHint(gate.lock())), true);
			return;
		}
		// 救恩：真的合出来了 → 进度「五饼二鱼」
		if (item == SummyReliquary.SALVATION.get()) {
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
					com.summy.reliquary.advancement.ReliquaryAdvancements.SALVATION_CRAFTED);
		}
		// ② 无门槛但需要退还部分材料（三位一体 → 肉体 / 思想 / 灵魂）
		List<ItemStack> returned = RETURNED.get(item);
		if (returned != null) {
			give(player, returned, count);
		}
	}

	/** 判据：天使标记 / 已签约 */
	private static boolean allowed(ServerPlayer player, Lock lock) {
		return switch (lock) {
			case ANGEL, ALWAYS_HIDDEN -> PlayerFlags.hasAngel(player);
			case SIGNED -> PlayerFlags.isDemonSealed(player) || PlayerFlags.isDemon(player);
			case DAGGER_LOST -> DaggerRecovery.isOpen(player);
			// 1.7.9：丢失态 + 天使标记（两个条件都要满足）
			case SPEAR_LOST -> SpearRecovery.isOpen(player) && PlayerFlags.hasAngel(player);
		};
	}

	/** 被门禁拦下时的行动栏提示（按门禁类型区分） */
	private static String lockHint(Lock lock) {
		return switch (lock) {
			case SIGNED -> "item.summy-reliquary.demon.required";
			case DAGGER_LOST -> "message.summy-reliquary.recipe.dagger_present";
			case SPEAR_LOST -> "message.summy-reliquary.recipe.spear_present";
			case ANGEL, ALWAYS_HIDDEN -> "message.summy-reliquary.recipe.angel_locked";
		};
	}

	/**
	 * JEI 目标可见性（客户端用）：受管配方 id → 是否可见。
	 *
	 * <p>天使线的配方"有标记才可见"、仪式法袍"已签约才可见"、救恩永久隐藏；恶魔线的可见性由
	 * {@code EvilUnlock.gatedRecipes()} 那套单独处理。
	 */
	public static Map<ResourceLocation, Boolean> jeiVisibility(boolean angel, boolean signed,
			boolean daggerLost, boolean spearLost) {
		Map<ResourceLocation, Boolean> wanted = new LinkedHashMap<>();
		GATES.forEach((item, gate) -> wanted.put(
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item),
				switch (gate.lock()) {
					case ANGEL -> angel;
					case SIGNED -> signed;
					case ALWAYS_HIDDEN -> false;
					case DAGGER_LOST -> daggerLost;
					case SPEAR_LOST -> spearLost && angel;
				}));
		return wanted;
	}

	/** 收走刚做出来的产物（可能在光标上，也可能已进背包） */
	private static void confiscate(ServerPlayer player, ItemStack crafted, int count) {
		ItemStack carried = player.inventoryMenu.getCarried();
		if (carried.is(crafted.getItem())) {
			carried.shrink(count);
			player.inventoryMenu.setCarried(carried);
			return;
		}
		player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(crafted.getItem()), count, player.inventoryMenu.getCraftSlots());
	}

	/** 按份数退材料（背包满则掉在脚下） */
	private static void give(ServerPlayer player, List<ItemStack> stacks, int rounds) {
		for (int round = 0; round < rounds; round++) {
			for (ItemStack template : stacks) {
				ItemStack give = template.copy();
				if (!player.getInventory().add(give)) {
					player.drop(give, false);
				}
			}
		}
	}
}
