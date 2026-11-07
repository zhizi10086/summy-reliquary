package com.summy.reliquary.loot;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 慈善捆绑包的全部服务端结算。
 *
 * <p>真实物理开箱只在当前 tick 的交互窗口中生效；BONUS 由当前战利品表调用结束后调度，
 * 因此不会在容器尚未完成第一次填装时改写其内容。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HumbleingBundleLoot {
	private static final ThreadLocal<Integer> REPLAY_DEPTH = ThreadLocal.withInitial(() -> 0);
	private static final Map<UUID, OpenWindow> OPEN_WINDOWS = new HashMap<>();
	private static final Map<KillKey, KillTransaction> KILL_TRANSACTIONS = new HashMap<>();
	private static final Map<LivingDropsEvent, DropSnapshot> DROP_SNAPSHOTS = new IdentityHashMap<>();

	private HumbleingBundleLoot() {
	}

	/** GML 唯一入口。 */
	public static void apply(LootContext context, ObjectArrayList<ItemStack> generatedLoot) {
		if (REPLAY_DEPTH.get() > 0 || !ReliquaryConfig.enableHumbleingBundle()) {
			return;
		}
		ResourceLocation tableId = context.getQueriedLootTableId();
		if (tableId == null) {
			return;
		}
		if (CovenantAcquisition.isChestTable(tableId)) {
			applyChest(context, generatedLoot);
		} else if (context.hasParam(LootContextParams.DAMAGE_SOURCE)) {
			applyKill(context, generatedLoot);
		}
	}

	private static void applyChest(LootContext context, ObjectArrayList<ItemStack> generatedLoot) {
		if (!(context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player)
				|| player.isSpectator()) {
			return;
		}
		OpenWindow window = OPEN_WINDOWS.get(player.getUUID());
		if (window == null || !window.matches(context)) {
			return;
		}

		// 每个物理开箱只进行一次本体获取与一次 BONUS 概率判定，双箱第二半不会重复。
		if (!window.naturalRolled) {
			window.naturalRolled = true;
			if (chance(context.getRandom(), ReliquaryConfig.humbleingBundleNaturalLootChancePercent())) {
				generatedLoot.add(new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get()));
			}
		}
		if (!window.bonusRolled) {
			window.bonusRolled = true;
			if (CurioHelper.wears(player, SummyReliquary.HUMBLEING_BUNDLE.get())
					&& chance(context.getRandom(), ReliquaryConfig.humbleingBundleExtraLootChancePercent())) {
				window.bonusContext = context;
				window.bonusScheduled = true;
				player.server.execute(() -> runBonusPass(player.getUUID(), window));
			}
		}
	}

	private static void applyKill(LootContext context, ObjectArrayList<ItemStack> generatedLoot) {
		if (!(context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof LivingEntity victim)
				|| victim instanceof ServerPlayer) {
			return;
		}
		ResourceLocation tableId = context.getQueriedLootTableId();
		if (!tableId.equals(victim.getLootTable())) {
			return;
		}
		ServerPlayer player = creditedPlayer(context);
		if (player == null || player.isSpectator()
				|| !CurioHelper.wears(player, SummyReliquary.HUMBLEING_BUNDLE.get())) {
			return;
		}

		KillKey key = new KillKey(victim.getUUID(), player.getUUID(), victim.getServer().getTickCount());
		if (KILL_TRANSACTIONS.containsKey(key)) {
			return;
		}
		boolean success = chance(context.getRandom(),
				ReliquaryConfig.humbleingBundleExtraLootChancePercent());
		KILL_TRANSACTIONS.put(key, new KillTransaction(success,
				success ? context : null, success ? tableId : null));
	}

	/** SR 誓约系统在 BONUS 阶段直接调用一次，不依赖第二次真实右键。 */
	public static void grantCovenantBonus(LootContext context) {
		CovenantAcquisition.tryGrantForBonusPass(context);
	}

	/** 自检入口：绕过物理窗口，验证本体 5% 与单次奖励规则。 */
	static boolean naturalBundleRoll(RandomSource random) {
		return chance(random, ReliquaryConfig.humbleingBundleNaturalLootChancePercent());
	}

	/** 自检入口：清理跨用例状态。 */
	public static void clearStateForTest() {
		OPEN_WINDOWS.clear();
		KILL_TRANSACTIONS.clear();
		DROP_SNAPSHOTS.clear();
		REPLAY_DEPTH.remove();
	}

	/** 自检入口：直接检查 50% / 5% 的整数阈值边界。 */
	public static boolean chanceForTest(int forcedRoll, boolean natural) {
		if (!Boolean.getBoolean("summy.devcheck")) {
			return false;
		}
		int chance = natural
				? ReliquaryConfig.humbleingBundleNaturalLootChancePercent()
				: ReliquaryConfig.humbleingBundleExtraLootChancePercent();
		return forcedRoll >= 0 && forcedRoll < chance;
	}

	/** 自检入口：预置一次已成功的击杀事务，后续事件仍走正式监听器。 */
	public static void markKillSuccessForTest(ServerPlayer player, LivingEntity victim) {
		if (Boolean.getBoolean("summy.devcheck")) {
			KILL_TRANSACTIONS.put(new KillKey(victim.getUUID(), player.getUUID(),
					victim.getServer().getTickCount()), new KillTransaction(true, null, null));
		}
	}

	private static void runBonusPass(UUID playerId, OpenWindow window) {
		MinecraftServer server = window.server;
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null || player.isSpectator() || !window.bonusScheduled
				|| !ReliquaryConfig.enableHumbleingBundle()
				|| !window.dimension.equals(player.serverLevel().dimension())) {
			return;
		}
		ServerLevel level = server.getLevel(window.dimension);
		if (level == null) {
			return;
		}

		LootContext firstContext = window.bonusContext != null
				? window.bonusContext
				: chestContext(level, player, window.primaryOrigin(), window.primaryTable());
		if (firstContext == null) {
			return;
		}

		// 每个 BONUS pass 只推进 SR/SRA 各一次；事件只发布一次。
		grantCovenantBonus(firstContext);
		MinecraftForge.EVENT_BUS.post(new HumbleingBundleBonusChestEvent(player, level, firstContext));

		boolean grantBundle = naturalBundleRoll(level.getRandom());
		boolean bundleAdded = false;
		for (TableTarget target : window.targets()) {
			Container container = target.container(level);
			if (container == null) {
				continue;
			}
			LootContext context = target.context(level, player);
			if (context == null) {
				continue;
			}
			ObjectArrayList<ItemStack> rolled = replay(context, target.table());
			if (grantBundle && !bundleAdded) {
				rolled.add(new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get()));
				bundleAdded = true;
			}
			for (ItemStack stack : rolled) {
				ItemStack remainder = insert(container, stack);
				if (!remainder.isEmpty()) {
					Containers.dropItemStack(level, target.origin().x, target.origin().y, target.origin().z,
							remainder);
				}
			}
			container.setChanged();
		}
		if (grantBundle && !bundleAdded) {
			Containers.dropItemStack(level, window.primaryOrigin().x, window.primaryOrigin().y,
					window.primaryOrigin().z, new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get()));
		}
	}

	private static ObjectArrayList<ItemStack> replay(LootContext source, ResourceLocation tableId) {
		ObjectArrayList<ItemStack> result = new ObjectArrayList<>();
		LootTable table = source.getResolver().getLootTable(tableId);
		if (table == null || table == LootTable.EMPTY) {
			return result;
		}
		long seed = source.getRandom().nextLong();
		LootContext replay = new LootContext.Builder(source)
				.withOptionalRandomSeed(seed)
				.withQueriedLootTableId(tableId)
				.create(tableId);
		REPLAY_DEPTH.set(REPLAY_DEPTH.get() + 1);
		try {
			table.getRandomItems(replay, result::add);
		} finally {
			int depth = REPLAY_DEPTH.get() - 1;
			if (depth <= 0) {
				REPLAY_DEPTH.remove();
			} else {
				REPLAY_DEPTH.set(depth);
			}
		}
		return result;
	}

	private static ItemStack insert(Container container, ItemStack incoming) {
		ItemStack remaining = incoming.copy();
		int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
		for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
			ItemStack existing = container.getItem(slot);
			if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, remaining)) {
				continue;
			}
			int moved = Math.min(remaining.getCount(), max - existing.getCount());
			if (moved > 0) {
				existing.grow(moved);
				remaining.shrink(moved);
				container.setItem(slot, existing);
			}
		}
		for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
			if (!container.getItem(slot).isEmpty()) {
				continue;
			}
			int moved = Math.min(remaining.getCount(), max);
			ItemStack placed = remaining.copy();
			placed.setCount(moved);
			container.setItem(slot, placed);
			remaining.shrink(moved);
		}
		return remaining;
	}

	private static LootContext chestContext(ServerLevel level, ServerPlayer player, Vec3 origin,
			ResourceLocation tableId) {
		if (origin == null || tableId == null) {
			return null;
		}
		LootParams params = new LootParams.Builder(level)
				.withParameter(LootContextParams.ORIGIN, origin)
				.withOptionalParameter(LootContextParams.THIS_ENTITY, player)
				.withLuck(player.getLuck())
				.create(LootContextParamSets.CHEST);
		return new LootContext.Builder(params)
				.withQueriedLootTableId(tableId)
				.create(tableId);
	}

	@Nullable
	private static ServerPlayer creditedPlayer(LootContext context) {
		if (context.getParamOrNull(LootContextParams.LAST_DAMAGE_PLAYER) instanceof ServerPlayer player) {
			return player;
		}
		Entity entity = context.getParamOrNull(LootContextParams.KILLER_ENTITY);
		if (entity == null) {
			entity = context.getParamOrNull(LootContextParams.DIRECT_KILLER_ENTITY);
		}
		return resolveOwner(entity, new HashSet<>(), 0);
	}

	@Nullable
	private static ServerPlayer resolveOwner(@Nullable Entity entity, Set<UUID> visited, int depth) {
		if (entity == null || depth > 4 || !visited.add(entity.getUUID())) {
			return null;
		}
		if (entity instanceof ServerPlayer player) {
			return player;
		}
		if (entity instanceof OwnableEntity ownable) {
			ServerPlayer owner = resolveOwner(ownable.getOwner(), visited, depth + 1);
			if (owner != null) {
				return owner;
			}
		}
		if (entity instanceof Projectile projectile) {
			ServerPlayer owner = resolveOwner(projectile.getOwner(), visited, depth + 1);
			if (owner != null) {
				return owner;
			}
		}
		return null;
	}

	@Nullable
	private static ServerPlayer creditedPlayer(DamageSource source) {
		Entity entity = source.getEntity();
		if (entity == null) {
			entity = source.getDirectEntity();
		}
		return resolveOwner(entity, new HashSet<>(), 0);
	}

	private static boolean chance(RandomSource random, int percent) {
		return percent > 0 && (percent >= 100 || random.nextInt(100) < percent);
	}

	private static KillKey keyFor(LivingEntity victim, @Nullable ServerPlayer player) {
		return player == null ? null
				: new KillKey(victim.getUUID(), player.getUUID(), victim.getServer().getTickCount());
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onExperienceDrop(LivingExperienceDropEvent event) {
		if (event.isCanceled()) {
			return;
		}
		KillKey key = keyFor(event.getEntity(), event.getAttackingPlayer() instanceof ServerPlayer player
				? player : null);
		KillTransaction transaction = key == null ? null : KILL_TRANSACTIONS.get(key);
		if (transaction == null || !transaction.success()) {
			return;
		}
		int dropped = event.getDroppedExperience();
		event.setDroppedExperience(dropped > Integer.MAX_VALUE - dropped
				? Integer.MAX_VALUE : dropped * 2);
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void captureLivingDrops(LivingDropsEvent event) {
		KillKey key = keyFor(event.getEntity(), creditedPlayer(event.getSource()));
		KillTransaction transaction = key == null ? null : KILL_TRANSACTIONS.get(key);
		if (transaction == null || !transaction.success()) {
			return;
		}
		if (transaction.context() != null && transaction.table() != null) {
			for (ItemStack stack : replay(transaction.context(), transaction.table())) {
				if (stack.isEmpty()) {
					continue;
				}
				ItemEntity drop = new ItemEntity(event.getEntity().level(),
						event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ(),
						stack.copy());
				drop.setPickUpDelay(10);
				drop.setDeltaMovement(event.getEntity().getRandom().nextDouble() * 0.2D - 0.1D,
						0.2D, event.getEntity().getRandom().nextDouble() * 0.2D - 0.1D);
				event.getDrops().add(drop);
			}
		}
		Set<ItemEntity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
		identities.addAll(event.getDrops());
		DROP_SNAPSHOTS.put(event, new DropSnapshot(identities));
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void copyEventAddedDrops(LivingDropsEvent event) {
		DropSnapshot snapshot = DROP_SNAPSHOTS.remove(event);
		if (snapshot == null) {
			return;
		}
		List<ItemEntity> added = event.getDrops().stream()
				.filter(drop -> !snapshot.baseline.contains(drop))
				.toList();
		if (added.isEmpty()) {
			return;
		}
		List<ItemEntity> copies = new ArrayList<>(added.size());
		for (ItemEntity drop : added) {
			ItemEntity copy = new ItemEntity(drop.level(), drop.getX(), drop.getY(), drop.getZ(),
					drop.getItem().copy());
			copy.setPickUpDelay(10);
			copies.add(copy);
		}
		event.getDrops().addAll(copies);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
				|| !(event.getLevel() instanceof ServerLevel level) || player.isSpectator()) {
			return;
		}
		Map<BlockPos, ResourceLocation> tables =
				CovenantAcquisition.blockContainerLootTables(level, event.getPos());
		if (!tables.isEmpty()) {
			OPEN_WINDOWS.put(player.getUUID(),
					OpenWindow.blocks(level, tables, level.getServer().getTickCount()));
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
				|| !(event.getLevel() instanceof ServerLevel level) || player.isSpectator()
				|| !(event.getTarget() instanceof AbstractMinecartContainer cart)
				|| !CovenantAcquisition.isChestTable(cart.getLootTable())) {
			return;
		}
		OPEN_WINDOWS.put(player.getUUID(),
				OpenWindow.cart(level, cart, level.getServer().getTickCount()));
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			OPEN_WINDOWS.clear();
			return;
		}
		int tick = event.getServer().getTickCount();
		KILL_TRANSACTIONS.keySet().removeIf(key -> key.tick() < tick - 1);
	}

	@SubscribeEvent
	public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		OPEN_WINDOWS.remove(event.getEntity().getUUID());
	}

	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		clearStateForTest();
	}

	private record KillKey(UUID victim, UUID player, int tick) {
	}

	private record KillTransaction(boolean success, @Nullable LootContext context,
			@Nullable ResourceLocation table) {
	}

	private record DropSnapshot(Set<ItemEntity> baseline) {
	}

	private interface TableTarget {
		@Nullable
		Container container(ServerLevel level);

		@Nullable
		LootContext context(ServerLevel level, ServerPlayer player);

		ResourceLocation table();

		Vec3 origin();
	}

	private static final class OpenWindow {
		private final MinecraftServer server;
		private final ResourceKey<Level> dimension;
		private final int tick;
		private final Map<BlockPos, ResourceLocation> blockTables;
		private final UUID cartId;
		private final Vec3 cartOrigin;
		private final ResourceLocation cartTable;
		private boolean naturalRolled;
		private boolean bonusRolled;
		private boolean bonusScheduled;
		private LootContext bonusContext;

		private OpenWindow(ServerLevel level, Map<BlockPos, ResourceLocation> blockTables, UUID cartId,
				Vec3 cartOrigin, ResourceLocation cartTable, int tick) {
			this.server = level.getServer();
			this.dimension = level.dimension();
			this.tick = tick;
			this.blockTables = Map.copyOf(blockTables);
			this.cartId = cartId;
			this.cartOrigin = cartOrigin;
			this.cartTable = cartTable;
		}

		static OpenWindow blocks(ServerLevel level, Map<BlockPos, ResourceLocation> tables, int tick) {
			return new OpenWindow(level, tables, null, null, null, tick);
		}

		static OpenWindow cart(ServerLevel level, AbstractMinecartContainer cart, int tick) {
			return new OpenWindow(level, Map.of(), cart.getUUID(), cart.position(), cart.getLootTable(), tick);
		}

		boolean matches(LootContext context) {
			if (context.getLevel().getServer().getTickCount() != tick
					|| !dimension.equals(context.getLevel().dimension())) {
				return false;
			}
			Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
			ResourceLocation table = context.getQueriedLootTableId();
			if (origin == null || table == null) {
				return false;
			}
			if (!blockTables.isEmpty()) {
				return table.equals(blockTables.get(BlockPos.containing(origin)));
			}
			return cartTable != null && cartTable.equals(table) && cartOrigin.distanceToSqr(origin) < 0.01D;
		}

		Vec3 primaryOrigin() {
			if (!blockTables.isEmpty()) {
				return Vec3.atCenterOf(blockTables.keySet().iterator().next());
			}
			return cartOrigin;
		}

		ResourceLocation primaryTable() {
			if (!blockTables.isEmpty()) {
				return blockTables.values().iterator().next();
			}
			return cartTable;
		}

		List<TableTarget> targets() {
			if (!blockTables.isEmpty()) {
				return blockTables.entrySet().stream()
						.<TableTarget>map(entry -> new BlockTarget(entry.getKey(), entry.getValue()))
						.toList();
			}
			return cartId == null ? List.of()
					: List.of(new CartTarget(cartId, cartOrigin, cartTable));
		}
	}

	private record BlockTarget(BlockPos pos, ResourceLocation table) implements TableTarget {
		@Override
		public @Nullable Container container(ServerLevel level) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			return blockEntity instanceof RandomizableContainerBlockEntity container ? container : null;
		}

		@Override
		public @Nullable LootContext context(ServerLevel level, ServerPlayer player) {
			return chestContext(level, player, Vec3.atCenterOf(pos), table);
		}

		@Override
		public Vec3 origin() {
			return Vec3.atCenterOf(pos);
		}
	}

	private record CartTarget(UUID cartId, Vec3 origin, ResourceLocation table) implements TableTarget {
		@Override
		public @Nullable Container container(ServerLevel level) {
			Entity entity = level.getEntity(cartId);
			return entity instanceof AbstractMinecartContainer cart ? cart : null;
		}

		@Override
		public @Nullable LootContext context(ServerLevel level, ServerPlayer player) {
			return chestContext(level, player, origin, table);
		}
	}
}
