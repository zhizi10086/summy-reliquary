package com.summy.reliquary.loot;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.effect.RevelationTracker;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 誓约五件的野箱获取状态机。
 *
 * <p>只处理玩家真实打开、首次生成内容的箱子：无阵营时仅按概率尝试生死逆转且无保底；
 * 单阵营时从“本家两件 + 生死逆转”的缺失池中抽取，并使用该路线独立的连续失败保底。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CovenantAcquisition {
	public static final int LIFE_DEATH_BIT = 1;
	public static final int DIVINE_INTERVENTION_BIT = 1 << 1;
	public static final int SACRED_VERDICT_BIT = 1 << 2;
	public static final int BLOOD_DEBT_ALTAR_BIT = 1 << 3;
	public static final int HEX_SEAL_BIT = 1 << 4;

	private static final List<Entry> ANGEL_ITEMS = List.of(
			new Entry(LIFE_DEATH_BIT, SummyReliquary.LIFE_DEATH),
			new Entry(DIVINE_INTERVENTION_BIT, SummyReliquary.DIVINE_INTERVENTION),
			new Entry(SACRED_VERDICT_BIT, SummyReliquary.SACRED_VERDICT)
	);
	private static final List<Entry> DEMON_ITEMS = List.of(
			new Entry(LIFE_DEATH_BIT, SummyReliquary.LIFE_DEATH),
			new Entry(BLOOD_DEBT_ALTAR_BIT, SummyReliquary.BLOOD_DEBT_ALTAR),
			new Entry(HEX_SEAL_BIT, SummyReliquary.HEX_SEAL)
	);
	private static final List<Entry> ALL_ITEMS = List.of(
			ANGEL_ITEMS.get(0), ANGEL_ITEMS.get(1), ANGEL_ITEMS.get(2),
			DEMON_ITEMS.get(1), DEMON_ITEMS.get(2)
	);

	/** 玩家本 tick 实际交互过的未生成容器。 */
	private static final Map<UUID, ContainerWindow> CONTAINER_WINDOWS = new HashMap<>();

	private CovenantAcquisition() {
	}

	/** Global Loot Modifier 的唯一业务入口。 */
	public static void tryGrant(LootContext context) {
		if (!ReliquaryConfig.enableCovenantAcquisition()) {
			return;
		}
		ResourceLocation table = context.getQueriedLootTableId();
		if (!isChestTable(table)) {
			return;
		}
		if (!(context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player)
				|| player.isSpectator()) {
			return;
		}
		Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
		ContainerWindow window = CONTAINER_WINDOWS.get(player.getUUID());
		if (origin == null || window == null || !window.matches(context.getLevel(), table, origin)) {
			return;
		}
		// 一次真实开箱只允许消费一个来源窗口。
		CONTAINER_WINDOWS.remove(player.getUUID());
		reconcileOwned(player);
		attempt(player, context.getRandom(), null);
	}

	/** 每 20 tick 扫描一次背包与 Curios，补记 /give、创造模式和旧存档已有的物品。 */
	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			CONTAINER_WINDOWS.clear();
			return;
		}
		if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0) {
			return;
		}
		for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
			reconcileOwned(player);
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
				|| !(event.getLevel() instanceof ServerLevel level) || player.isSpectator()) {
			return;
		}
		Map<BlockPos, ResourceLocation> tables = blockContainerLootTables(level, event.getPos());
		if (!tables.isEmpty()) {
			CONTAINER_WINDOWS.put(player.getUUID(), new ContainerWindow(
					level.dimension(), Map.copyOf(tables), Vec3.ZERO, null, level.getServer().getTickCount()));
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
				|| !(event.getLevel() instanceof ServerLevel level) || player.isSpectator()
				|| !(event.getTarget() instanceof AbstractMinecartContainer cart)
				|| !isChestTable(cart.getLootTable())) {
			return;
		}
		CONTAINER_WINDOWS.put(player.getUUID(), new ContainerWindow(
				level.dimension(), Map.of(), cart.position(), cart.getLootTable(), level.getServer().getTickCount()));
	}

	@SubscribeEvent
	public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			reconcileOwned(player);
		}
	}

	@SubscribeEvent
	public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		CONTAINER_WINDOWS.remove(event.getEntity().getUUID());
	}

	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		CONTAINER_WINDOWS.clear();
	}

	private static void attempt(ServerPlayer player, RandomSource random, Integer forcedRoll) {
		boolean angel = PlayerFlags.hasAngel(player);
		boolean demon = PlayerFlags.isDemon(player);
		if (angel && demon) {
			return;
		}
		int owned = PlayerFlags.covenantOwned(player);
		int chance = ReliquaryConfig.covenantLootChancePercent();
		if (!angel && !demon) {
			if ((owned & LIFE_DEATH_BIT) != 0) {
				return;
			}
			int roll = roll(random, forcedRoll);
			if (roll >= chance) {
				return;
			}
			grant(player, SummyReliquary.LIFE_DEATH.get(), LIFE_DEATH_BIT);
			return;
		}

		List<Entry> route = angel ? ANGEL_ITEMS : DEMON_ITEMS;
		List<Entry> missing = route.stream()
				.filter(entry -> (owned & entry.bit()) == 0)
				.toList();
		if (missing.isEmpty()) {
			PlayerFlags.setCovenantPity(player, angel, 0);
			return;
		}

		int pity = PlayerFlags.covenantPity(player, angel);
		int pityLimit = ReliquaryConfig.covenantPityChests();
		boolean guaranteed = pityLimit > 0 && pity >= pityLimit;
		int roll = roll(random, forcedRoll);
		if (!guaranteed && roll >= chance) {
			PlayerFlags.setCovenantPity(player, angel, pity == Integer.MAX_VALUE ? pity : pity + 1);
			return;
		}

		int selected = random == null
				? Math.floorMod(roll * 31 + 17, missing.size())
				: random.nextInt(missing.size());
		Entry entry = missing.get(selected);
		grant(player, entry.item().get(), entry.bit());
		PlayerFlags.setCovenantPity(player, angel, 0);
	}

	private static int roll(RandomSource random, Integer forcedRoll) {
		return forcedRoll == null ? random.nextInt(100) : Math.max(0, Math.min(99, forcedRoll));
	}

	private static void grant(ServerPlayer player, Item item, int bit) {
		ItemStack stack = new ItemStack(item);
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		PlayerFlags.setCovenantOwned(player, PlayerFlags.covenantOwned(player) | bit);
		RevelationTracker.sync(player);
	}

	public static boolean acquired(net.minecraft.world.entity.LivingEntity entity, Item item) {
		int bit = bitFor(item);
		return bit != 0 && (PlayerFlags.covenantOwned(entity) & bit) != 0;
	}

	public static int bitFor(Item item) {
		if (item == SummyReliquary.LIFE_DEATH.get()) {
			return LIFE_DEATH_BIT;
		}
		if (item == SummyReliquary.DIVINE_INTERVENTION.get()) {
			return DIVINE_INTERVENTION_BIT;
		}
		if (item == SummyReliquary.SACRED_VERDICT.get()) {
			return SACRED_VERDICT_BIT;
		}
		if (item == SummyReliquary.BLOOD_DEBT_ALTAR.get()) {
			return BLOOD_DEBT_ALTAR_BIT;
		}
		if (item == SummyReliquary.HEX_SEAL.get()) {
			return HEX_SEAL_BIT;
		}
		return 0;
	}

	/** 登录与定期扫描：已有物品自动补记取得位，避免 /give 或旧存档重复发放。 */
	public static void reconcileOwned(ServerPlayer player) {
		int mask = PlayerFlags.covenantOwned(player);
		int before = mask;
		for (Entry entry : ALL_ITEMS) {
			if (countEverywhere(player, entry.item().get()) > 0) {
				mask |= entry.bit();
			}
		}
		if (mask != before) {
			PlayerFlags.setCovenantOwned(player, mask);
			RevelationTracker.sync(player);
		}
	}

	private static int countEverywhere(Player player, Item item) {
		int count = 0;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		if (CurioHelper.wears(player, item)) {
			count++;
		}
		return count;
	}

	private static Map<BlockPos, ResourceLocation> blockContainerLootTables(ServerLevel level, BlockPos pos) {
		Map<BlockPos, ResourceLocation> tables = new HashMap<>();
		ResourceLocation table = readContainerLootTable(level.getBlockEntity(pos));
		if (table != null) {
			tables.put(pos, table);
		}
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos connectedPos = pos.relative(ChestBlock.getConnectedDirection(state));
			ResourceLocation connectedTable = readContainerLootTable(level.getBlockEntity(connectedPos));
			if (connectedTable != null) {
				tables.put(connectedPos, connectedTable);
			}
		}
		tables.entrySet().removeIf(entry -> !isChestTable(entry.getValue()));
		return tables;
	}

	private static ResourceLocation readContainerLootTable(BlockEntity blockEntity) {
		if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
			return null;
		}
		CompoundTag saved = container.saveWithoutMetadata();
		if (!saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG, CompoundTag.TAG_STRING)) {
			return null;
		}
		return ResourceLocation.tryParse(saved.getString(RandomizableContainerBlockEntity.LOOT_TABLE_TAG));
	}

	private static boolean isChestTable(ResourceLocation table) {
		return table != null && table.getPath().startsWith("chests/");
	}

	/** 自检入口：跳过容器窗口，只执行同一套获得 / 概率 / 保底逻辑。 */
	public static boolean attemptForTest(ServerPlayer player, int forcedRoll) {
		if (!Boolean.getBoolean("summy.devcheck")) {
			return false;
		}
		reconcileOwned(player);
		int before = PlayerFlags.covenantOwned(player);
		boolean angel = PlayerFlags.hasAngel(player);
		int pityBefore = PlayerFlags.covenantPity(player, angel);
		attempt(player, null, forcedRoll);
		boolean obtained = PlayerFlags.covenantOwned(player) != before;
		boolean pityChanged = PlayerFlags.covenantPity(player, angel) != pityBefore;
		return obtained || pityChanged;
	}

	public static int ownedMaskForTest(ServerPlayer player) {
		return Boolean.getBoolean("summy.devcheck") ? PlayerFlags.covenantOwned(player) : 0;
	}

	public static int pityForTest(ServerPlayer player, boolean angel) {
		return Boolean.getBoolean("summy.devcheck") ? PlayerFlags.covenantPity(player, angel) : 0;
	}

	public static void setPityForTest(ServerPlayer player, boolean angel, int value) {
		if (Boolean.getBoolean("summy.devcheck")) {
			PlayerFlags.setCovenantPity(player, angel, value);
		}
	}

	public static void clearStateForTest(ServerPlayer player) {
		if (Boolean.getBoolean("summy.devcheck")) {
			PlayerFlags.setCovenantOwned(player, 0);
			PlayerFlags.setCovenantPity(player, true, 0);
			PlayerFlags.setCovenantPity(player, false, 0);
			RevelationTracker.sync(player);
		}
	}

	public static int containerWindowCountForTest() {
		return Boolean.getBoolean("summy.devcheck") ? CONTAINER_WINDOWS.size() : 0;
	}

	public static void clearContainerWindowsForTest() {
		if (Boolean.getBoolean("summy.devcheck")) {
			CONTAINER_WINDOWS.clear();
		}
	}

	private record Entry(int bit, Supplier<? extends Item> item) {
	}

	private record ContainerWindow(ResourceKey<Level> dimension, Map<BlockPos, ResourceLocation> blockTables,
			Vec3 origin, ResourceLocation table, int tick) {
		private boolean matches(ServerLevel level, ResourceLocation actualTable, Vec3 actualOrigin) {
			if (!dimension.equals(level.dimension()) || tick != level.getServer().getTickCount()) {
				return false;
			}
			if (!blockTables.isEmpty()) {
				ResourceLocation expected = blockTables.get(BlockPos.containing(actualOrigin));
				return expected != null && expected.equals(actualTable);
			}
			return table != null && table.equals(actualTable) && origin.distanceToSqr(actualOrigin) < 0.01D;
		}
	}
}
