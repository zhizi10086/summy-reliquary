package com.summy.reliquary.loot;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
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
@EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class HumbleingBundleLoot {
	private static final ThreadLocal<Integer> REPLAY_DEPTH = ThreadLocal.withInitial(() -> 0);
	private static final Map<UUID, OpenWindow> OPEN_WINDOWS = new HashMap<>();
	private static final Map<KillKey, KillTransaction> KILL_TRANSACTIONS = new HashMap<>();
	private static final Map<LivingDropsEvent, DropSnapshot> DROP_SNAPSHOTS = new IdentityHashMap<>();
	private static Boolean forcedKillSuccess;

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

		boolean firstPhysicalRoll = !window.naturalRolled && !window.bonusRolled;
		boolean naturalGranted = false;
		// 每个物理开箱只进行一次本体获取与一次 BONUS 概率判定，双箱第二半不会重复。
		if (!window.naturalRolled) {
			window.naturalRolled = true;
			if (chance(context.getRandom(), ReliquaryConfig.humbleingBundleNaturalLootChancePercent())) {
				generatedLoot.add(new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get()));
				naturalGranted = true;
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
		if (firstPhysicalRoll && (naturalGranted || window.bonusScheduled)) {
			SummyReliquary.LOGGER.info(
					"[Summy Reliquary] 慈善捆绑包原始开箱：玩家={}、容器={}、表={}、tick={}、本体={}、额外={}",
					player.getName().getString(), window.containerSummary(), window.tableSummary(),
					context.getLevel().getServer().getTickCount(), naturalGranted, window.bonusScheduled);
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

		KillKey key = keyFor(victim);
		if (KILL_TRANSACTIONS.containsKey(key)) {
			return;
		}
		boolean success = forcedKillSuccess != null ? forcedKillSuccess
				: chance(context.getRandom(), ReliquaryConfig.humbleingBundleExtraLootChancePercent());
		KILL_TRANSACTIONS.put(key, new KillTransaction(player.getUUID(), success,
				success ? context : null, success ? tableId : null));
		if (success) {
			SummyReliquary.LOGGER.info(
					"[Summy Reliquary] 慈善捆绑包击杀触发：玩家={}、目标={}、表={}、tick={}",
					player.getName().getString(), entityId(victim), tableId, key.tick());
		}
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
		forcedKillSuccess = null;
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
		markKillSuccessForTest(player, victim, null, null);
	}

	/** 自检入口：使用真实战利品上下文预置事务，验证重掷与两阶段一次性消费。 */
	public static void markKillSuccessForTest(ServerPlayer player, LivingEntity victim,
			@Nullable LootContext context, @Nullable ResourceLocation table) {
		if (Boolean.getBoolean("summy.devcheck")) {
			KILL_TRANSACTIONS.put(keyFor(victim),
					new KillTransaction(player.getUUID(), true, context, table));
		}
	}

	/**
	 * 特殊稀有材料专用：只查询本 tick 已成功的击杀事务，不消费、不递归。
	 *
	 * <p>心之碎片与碎心在实体死亡掉落事件直接生成，不经过战利品表；它们据此决定
	 * 是否追加一次独立掉率判定。
	 */
	@Nullable
	public static ServerPlayer specialDropBonusPlayer(LivingEntity victim) {
		KillTransaction transaction = KILL_TRANSACTIONS.get(keyFor(victim));
		if (transaction == null || !transaction.success()) {
			return null;
		}
		return transactionPlayer(victim, transaction);
	}

	/** 自检入口：绕过 50% 随机，验证真实厄咒转移击杀的完整服务端链路。 */
	public static void setForcedKillSuccessForTest(@Nullable Boolean value) {
		if (Boolean.getBoolean("summy.devcheck")) {
			forcedKillSuccess = value;
		}
	}

	/**
	 * 自检入口：返回当前目标本 tick 的事务状态。
	 *
	 * <p>位标记：1=事务存在、2=成功、4=掉落已消费、8=经验已消费。
	 */
	public static int killTransactionStateForTest(LivingEntity victim) {
		if (!Boolean.getBoolean("summy.devcheck")) {
			return 0;
		}
		KillTransaction transaction = KILL_TRANSACTIONS.get(keyFor(victim));
		if (transaction == null) {
			return 0;
		}
		return 1 | (transaction.success() ? 2 : 0)
				| (transaction.dropsHandled ? 4 : 0)
				| (transaction.experienceHandled ? 8 : 0);
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
		NeoForge.EVENT_BUS.post(new HumbleingBundleBonusChestEvent(player, level, firstContext));

		boolean grantBundle = naturalBundleRoll(level.getRandom());
		boolean bundleAdded = false;
		int affectedTables = 0;
		int overflow = 0;
		List<ItemStack> rerolledItems = new ArrayList<>();
		for (TableTarget target : window.targets()) {
			Container container = target.container(level);
			if (container == null) {
				continue;
			}
			LootContext context = target.context(level, player);
			if (context == null) {
				continue;
			}
			affectedTables++;
			ObjectArrayList<ItemStack> rolled = replay(context, target.table());
			if (grantBundle && !bundleAdded) {
				rolled.add(new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get()));
				bundleAdded = true;
			}
			rerolledItems.addAll(rolled);
			for (ItemStack stack : rolled) {
				ItemStack remainder = insert(container, stack);
				if (!remainder.isEmpty()) {
					overflow += remainder.getCount();
					Containers.dropItemStack(level, target.origin().x, target.origin().y, target.origin().z,
							remainder);
				}
			}
			container.setChanged();
		}
		if (grantBundle && !bundleAdded) {
			ItemStack fallbackBundle = new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get());
			Containers.dropItemStack(level, window.primaryOrigin().x, window.primaryOrigin().y,
					window.primaryOrigin().z, fallbackBundle);
			rerolledItems.add(fallbackBundle);
		}
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 慈善捆绑包额外开箱：玩家={}、容器={}、表={}、tick={}、表数={}、本体={}、重掷物品={}、溢出={}、誓约BONUS=已发布",
				player.getName().getString(), window.containerSummary(), window.tableSummary(),
				server.getTickCount(), affectedTables, grantBundle, itemSummary(rerolledItems), overflow);
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

	/** 日志只输出物品 ID 与数量，避免记录完整 NBT。 */
	private static String itemSummary(List<ItemStack> stacks) {
		int total = 0;
		int stacksWithItems = 0;
		int shown = 0;
		List<String> parts = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) {
				continue;
			}
			total += stack.getCount();
			stacksWithItems++;
			if (shown < 8) {
				parts.add(stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
				shown++;
			}
		}
		if (stacksWithItems == 0) {
			return "0件";
		}
		return total + "件[" + String.join(", ", parts)
				+ (stacksWithItems > shown ? ", …" : "") + "]";
	}

	private static ResourceLocation entityId(LivingEntity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
	}

	private static KillKey keyFor(LivingEntity victim) {
		MinecraftServer server = victim.getServer();
		return new KillKey(victim.getUUID(), server == null ? 0 : server.getTickCount());
	}

	@Nullable
	private static ServerPlayer transactionPlayer(LivingEntity victim, KillTransaction transaction) {
		MinecraftServer server = victim.getServer();
		return server == null ? null : server.getPlayerList().getPlayer(transaction.playerId());
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onExperienceDrop(LivingExperienceDropEvent event) {
		if (event.isCanceled()) {
			return;
		}
		ServerPlayer eventPlayer = event.getAttackingPlayer() instanceof ServerPlayer serverPlayer
				? serverPlayer : null;
		KillKey key = keyFor(event.getEntity());
		KillTransaction transaction = KILL_TRANSACTIONS.get(key);
		if (transaction == null || !transaction.success() || transaction.experienceHandled) {
			return;
		}
		if (eventPlayer != null && !transaction.playerId().equals(eventPlayer.getUUID())) {
			return;
		}
		transaction.experienceHandled = true;
		ServerPlayer player = eventPlayer != null ? eventPlayer : transactionPlayer(event.getEntity(), transaction);
		int dropped = event.getDroppedExperience();
		int updated = dropped > Integer.MAX_VALUE - dropped ? Integer.MAX_VALUE : dropped * 2;
		event.setDroppedExperience(updated);
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 慈善捆绑包经验复制：玩家={}、目标={}、tick={}、经验={}→{}",
				player == null ? "未知" : player.getName().getString(),
				entityId(event.getEntity()), key.tick(), dropped, updated);
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void captureLivingDrops(LivingDropsEvent event) {
		KillKey key = keyFor(event.getEntity());
		KillTransaction transaction = KILL_TRANSACTIONS.get(key);
		if (transaction == null || !transaction.success() || transaction.dropsHandled) {
			return;
		}
		ServerPlayer eventPlayer = creditedPlayer(event.getSource());
		if (eventPlayer != null && !transaction.playerId().equals(eventPlayer.getUUID())) {
			return;
		}
		transaction.dropsHandled = true;
		if (transaction.context() != null && transaction.table() != null) {
			ObjectArrayList<ItemStack> rerolled = replay(transaction.context(), transaction.table());
			ServerPlayer player = eventPlayer != null
					? eventPlayer : transactionPlayer(event.getEntity(), transaction);
			SummyReliquary.LOGGER.info(
					"[Summy Reliquary] 慈善捆绑包击杀重掷：玩家={}、目标={}、表={}、tick={}、物品={}",
					player == null ? "未知" : player.getName().getString(), entityId(event.getEntity()),
					transaction.table(), key.tick(), itemSummary(rerolled));
			for (ItemStack stack : rerolled) {
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
		ServerPlayer player = creditedPlayer(event.getSource());
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 慈善捆绑包外部掉落复制：玩家={}、目标={}、tick={}、物品={}",
				player == null ? "未知" : player.getName().getString(), entityId(event.getEntity()),
				event.getEntity().getServer().getTickCount(),
				itemSummary(copies.stream().map(ItemEntity::getItem).toList()));
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
	public static void onServerTickPre(ServerTickEvent.Pre event) {
		OPEN_WINDOWS.clear();
	}

	@SubscribeEvent
	public static void onServerTickPost(ServerTickEvent.Post event) {
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

	private record KillKey(UUID victim, int tick) {
	}

	private static final class KillTransaction {
		private final UUID playerId;
		private final boolean success;
		private final @Nullable LootContext context;
		private final @Nullable ResourceLocation table;
		private boolean dropsHandled;
		private boolean experienceHandled;

		private KillTransaction(UUID playerId, boolean success, @Nullable LootContext context,
				@Nullable ResourceLocation table) {
			this.playerId = playerId;
			this.success = success;
			this.context = context;
			this.table = table;
		}

		private UUID playerId() {
			return playerId;
		}

		private boolean success() {
			return success;
		}

		private @Nullable LootContext context() {
			return context;
		}

		private @Nullable ResourceLocation table() {
			return table;
		}
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

		String containerSummary() {
			if (!blockTables.isEmpty()) {
				return dimension.location() + "@" + primaryOrigin();
			}
			return dimension.location() + "@minecart" + cartOrigin;
		}

		String tableSummary() {
			if (blockTables.isEmpty()) {
				return String.valueOf(cartTable);
			}
			return String.join(", ", blockTables.values().stream()
					.map(ResourceLocation::toString)
					.distinct()
					.toList());
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
