package com.summy.reliquary.item;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.sin.Sin;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.sin.SinProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创世纪（Genesis）：非饰品，只能使用一次，代表"一切从头开始"。
 *
 * <p>1.5.2 起右键**不再立刻生效**：先弹出聊天框确认（「你真的要重新开始一切吗？」+
 * 可点击的 [是] / [否]），点「是」才会执行重置。确认有效期 30 秒，重复右键只重发提示。
 *
 * <p><b>1.6.10 起</b>：
 * <ul>
 *     <li><b>获取途径</b>：生存里**只能**由"获得神性 / 亚巴顿"自动发放（两条线各 1 个，见
 *     {@link #grantFrom(ServerPlayer, boolean)}）；创造模式仍可从创造页取；</li>
 *     <li><b>获取前禁止查看</b>：没"知道"过它（{@code genesis_known} 未置位且身上没有它）时，
 *     提示只有名字 +「你还不知此为何物。」；</li>
 *     <li><b>使用条件</b>：天使标记 **或** 恶魔侧任一标记（当前恶魔 / 曾签约）；</li>
 *     <li><b>重置内容</b>：七罪三态与进度、「天使 / 恶魔」两侧标记与整条恶魔线（契约 / 邪恶度 /
 *     解锁位图 / 签约记录）、启示属性、派生数值全部重置，删除本模组全部物品并重发光环 + 七罪之源；
 *     **进度（成就）一律不动** —— 所以「纯洁之人 → 天使标记」与「无罪之人 → 放弃一切」会被每秒自愈补回。</li>
 * </ul>
 */
public class GenesisItem extends Item {
	/** 一次性标记（与其它本模组数据同级存放） */
	public static final String USED_KEY = "genesis_used";
	/** 亮蓝（描述用色） */
	private static final int AQUA = 0x55FFFF;
	/** 金色（Shift 行） */
	private static final int GOLD = 0xFFD700;
	/** 淡金（确认问句） */
	private static final int PALE_GOLD = 0xFFE4B5;
	/** 绿（[是]） */
	private static final int GREEN = 0x55FF55;
	/** 红（[否]） */
	private static final int RED = 0xFF5555;
	/** 确认有效期（tick）：30 秒 */
	private static final long CONFIRM_WINDOW_TICKS = 600L;
	/** 「你还不知此为何物。」的颜色（与它替换掉的功能描述同色） */
	private static final int UNKNOWN_GOLD = 0xFFD700;

	/** 玩家 UUID →（确认到期时的服务端 tick） */
	private static final Map<UUID, Long> PENDING = new HashMap<>();

	public GenesisItem(Properties properties) {
		super(properties);
	}

	/** 是否已经用过（每名玩家一次） */
	public static boolean isUsed(Player player) {
		return player.getPersistentData().getCompound(SinManager.ROOT).getBoolean(USED_KEY);
	}

	/** 重置一次性标记（OP 命令用） */
	public static void resetUsed(ServerPlayer player) {
		SinManager.mutableRoot(player).putBoolean(USED_KEY, false);
	}

	/** 自检用：清掉"已知"标记（用来验证"获取前禁止查看"） */
	public static void resetKnownForTest(ServerPlayer player) {
		PlayerFlags.setGenesisKnown(player, false);
		com.summy.reliquary.effect.RevelationTracker.sync(player);
	}

	/** 自检用：清掉两条线的"已发放"标记（便于重复验证自动发放） */
	public static void resetGrantedForTest(ServerPlayer player) {
		PlayerFlags.setGenesisGrantedGodhead(player, false);
		PlayerFlags.setGenesisGrantedAbaddon(player, false);
	}

	/**
	 * 使用条件（1.6.10）：天使标记 **或** 恶魔侧任一标记（当前恶魔 / 曾签约）。
	 *
	 * <p>服务端与客户端（提示）共用这一个判定。
	 */
	public static boolean isQualified(LivingEntity entity) {
		return entity != null && (PlayerFlags.hasAngel(entity)
				|| PlayerFlags.isDemon(entity)
				|| PlayerFlags.isDemonSealed(entity));
	}

	/** 是否"知道"过创世纪（获取前禁止查看的口径；身上正持有也算） */
	public static boolean isKnown(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		return PlayerFlags.isGenesisKnown(entity) || holdsGenesis(entity);
	}

	/**
	 * 自动发放（1.6.10）：获得**神性**或**亚巴顿**时各发 1 个（两条线各一次）。
	 *
	 * @param fromGodhead true = 来自神性，false = 来自亚巴顿
	 * @return true 表示这次真的发了
	 */
	public static boolean grantFrom(ServerPlayer player, boolean fromGodhead) {
		if (player == null) {
			return false;
		}
		if (fromGodhead ? PlayerFlags.isGenesisGrantedGodhead(player)
				: PlayerFlags.isGenesisGrantedAbaddon(player)) {
			return false;
		}
		if (fromGodhead) {
			PlayerFlags.setGenesisGrantedGodhead(player, true);
		} else {
			PlayerFlags.setGenesisGrantedAbaddon(player, true);
		}
		// 发过一次就永久"知道"它（获取前禁止查看只拦没拿到过的玩家）
		PlayerFlags.setGenesisKnown(player, true);
		// 这一位要同步给客户端（提示文本要用）
		com.summy.reliquary.effect.RevelationTracker.sync(player);
		ItemStack gift = new ItemStack(SummyReliquary.GENESIS.get());
		if (!player.getInventory().add(gift)) {
			// 1.7.3：背包满时掉在脚下，也要顺手给它"防丢失"保护
			protectDrop(player.drop(gift, false));
		}
		player.displayClientMessage(
				Component.translatable("message.summy-reliquary.genesis.granted"), true);
		return true;
	}

	/**
	 * 1.7.3：给掉在地上的创世纪加上"防丢失"保护。
	 *
	 * <p>{@code invulnerable=true} → 免疫仙人掌 / 爆炸等能毁掉掉落物的伤害；
	 * {@code lifespan = Integer.MAX_VALUE} → 永不自然消失（原版 5 分钟会 despawn）。
	 * 掉进虚空仍然会被移除 —— 那是"主动丢弃"，按需求**不补发**。
	 *
	 * @return 传入的实体（方便链式使用）
	 */
	public static net.minecraft.world.entity.item.ItemEntity protectDrop(
			net.minecraft.world.entity.item.ItemEntity entity) {
		if (entity != null) {
			entity.setInvulnerable(true);
			entity.lifespan = Integer.MAX_VALUE;
		}
		return entity;
	}

	/** 身上（背包 + 全部 Curios 栏位）是否持有创世纪 */
	public static boolean holdsGenesis(LivingEntity entity) {
		// 1.7.10 修订：按物品类型判定（忽略 NBT），并覆盖副手 / 盔甲 / 光标
		return com.summy.reliquary.util.HeldItems.holds(entity, SummyReliquary.GENESIS.get());
	}

	/**
	 * 1.7.3：死亡复活时的"创世纪不丢失"补发。
	 *
	 * <p>判据 = **原玩家身上有**创世纪，而新玩家身上**没有**（死亡掉落不会产生创世纪掉落物，
	 * 见 {@code ReliquaryEvents#onLivingDrops}）。
	 *
	 * <p>主动丢弃（丢出 / 丢入虚空）之后再死 → 原玩家身上本来就没有 → **不补发**。
	 *
	 * @param originalHadDeath 原玩家（死亡前）身上是否有创世纪
	 * @return true 表示这次补发了一个
	 */
	public static boolean restoreOnDeath(ServerPlayer player, boolean originalHadDeath) {
		if (player == null || !originalHadDeath || holdsGenesis(player)) {
			return false;
		}
		ItemStack gift = new ItemStack(SummyReliquary.GENESIS.get());
		if (!player.getInventory().add(gift)) {
			protectDrop(player.drop(gift, false));
		}
		return true;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide()) {
			return InteractionResultHolder.sidedSuccess(stack, true);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		// 1.6.10：天使 **或** 恶魔侧任一标记都可用
		if (!isQualified(serverPlayer)) {
			serverPlayer.displayClientMessage(
					Component.translatable("item.summy-reliquary.genesis.locked"), true);
			return InteractionResultHolder.fail(stack);
		}
		if (isUsed(serverPlayer)) {
			serverPlayer.displayClientMessage(Component.translatable("message.summy-reliquary.genesis.used"), true);
			return InteractionResultHolder.fail(stack);
		}

		// 先弹聊天确认框，点「是」之后才真正执行
		requestConfirmation(serverPlayer);
		return InteractionResultHolder.success(stack);
	}

	// ==================== 确认流程（1.5.2） ====================

	/** 发确认提示（重复右键只重发，不叠加状态） */
	public static void requestConfirmation(ServerPlayer player) {
		PENDING.put(player.getUUID(), now(player) + CONFIRM_WINDOW_TICKS);
		player.sendSystemMessage(Component.translatable("message.summy-reliquary.genesis.confirm.question")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)));
		player.sendSystemMessage(Component.empty()
				.append(button("message.summy-reliquary.genesis.confirm.yes",
						"message.summy-reliquary.genesis.confirm.yes.hover",
						"/summyreliquary genesis confirm", GREEN))
				.append(Component.literal(" "))
				.append(button("message.summy-reliquary.genesis.confirm.no",
						"message.summy-reliquary.genesis.confirm.no.hover",
						"/summyreliquary genesis cancel", RED)));
	}

	/** 是否还处在确认有效期内 */
	public static boolean hasPending(ServerPlayer player) {
		Long until = PENDING.get(player.getUUID());
		return until != null && now(player) <= until;
	}

	/** 清掉确认状态 */
	public static void clearPending(ServerPlayer player) {
		PENDING.remove(player.getUUID());
	}

	/**
	 * 点「是」：只有确认有效 + 有天使标记 + 尚未用过才会执行重置。
	 *
	 * @return true 表示真的执行了（并已发好提示）
	 */
	public static boolean confirm(ServerPlayer player) {
		if (!hasPending(player)) {
			clearPending(player);
			return false;
		}
		clearPending(player);
		if (!isQualified(player) || isUsed(player)) {
			return false;
		}
		performReset(player);
		SinManager.mutableRoot(player).putBoolean(USED_KEY, true);
		player.sendSystemMessage(Component.translatable("message.summy-reliquary.genesis.done"));
		// 1.6.10：进度「亘古之初」——真正用掉创世纪的那一下
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.GENESIS_USED);
		return true;
	}

	/**
	 * 点「否」：只清确认状态。
	 *
	 * @return true 表示之前确实有待确认的操作
	 */
	public static boolean cancel(ServerPlayer player) {
		if (!hasPending(player)) {
			clearPending(player);
			return false;
		}
		clearPending(player);
		return true;
	}

	private static long now(ServerPlayer player) {
		return player.getServer() == null ? 0L : player.getServer().getTickCount();
	}

	/** 一个可点击的按钮：加粗上色 + 悬停说明 + 点击执行命令 */
	private static MutableComponent button(String key, String hoverKey, String command, int color) {
		return Component.translatable(key)
				.withStyle(Style.EMPTY
						.withColor(TextColor.fromRgb(color))
						.withBold(true)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent(
								net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, command))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
								Component.translatable(hoverKey))));
	}

	/** 真正的"从头开始" */
	private static void performReset(ServerPlayer player) {
		// ① 清七罪三态与进度计数
		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.UNACTIVATED);
		}
		SinProgress.reset(player);
		// ② 清天使侧标记（**不动 sin_renounced**：无罪之人的锁按需求保持，
		//   而且它会被 SinChallenges.selfHeal 每秒补回；这里清掉只会留下 1 秒的空窗）
		PlayerFlags.setAngel(player, false);
		PlayerFlags.setStarGranted(player, false);
		PlayerFlags.setDragonVerdict(player, com.summy.reliquary.advancement.SinChallenges.VERDICT_NONE);
		// ③ 清整条恶魔线（1.6.10：契约 + 交易记录 + 邪恶度 / 解锁 / 献祭 / 「6」掉落 ……）
		//    先清「恶魔标记 / 曾签约」，再 revoke —— 这样 revoke 里的 syncSlot 才会把
		//    「恶魔契约」栏位当场压回 0 格（否则当前还持着标记，栏位会被重新撑开）
		PlayerFlags.resetDemonDeal(player);
		com.summy.reliquary.effect.DemonPact.revoke(player);
		PlayerFlags.resetDemonPact(player);
		// ④ 清派生数值
		PlayerFlags.setSoulHeartPoints(player, 0.0D);
		PlayerFlags.setAbaddonReviveReadyAt(player, 0L);
		// 「五芒星那句 300 秒的话」也一起清：重置后与一周目一样要重新累计 300 秒才会再听到
		PlayerFlags.setPentagramTicks(player, 0);
		PlayerFlags.setPentagramSpoken(player, false);
		// 「痛悔短祷」的终身一次也重置：否则重置后的玩家会永久用不了它（= 失去"恶魔 → 天使"的正常途径）
		com.summy.reliquary.item.ActOfContritionItem.resetUsed(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		// 「伯列恒之星」的累计佩戴时间与已揭示坐标同样清空（重置后要重新等 600 秒）
		com.summy.reliquary.effect.RevelationTracker.reset(player);
		// ⑤ 允许"成就奖励物品"重新领取：只清各自的发放标记，**不清成就**
		//    （条件不成立时不会立刻补发，条件再次满足才会按原途径再发）
		PlayerFlags.setPentagramGranted(player, false);
		PlayerFlags.setStarGranted(player, false);
		// 恶魔王冠：删掉物品（下面的全清会做）+ 清"已发放"标记 → 重新做出撒旦圣经可以再拿
		PlayerFlags.setDevilCrownGranted(player, false);
		// ⑥ 清"是否获取过启示" → 恶魔交易重新开放（迁移标记保留，避免又从成就里补记）
		PlayerFlags.setRevelationObtained(player, false);
		// ⑥b 1.7.3：**重新武装创世纪的发放标记** —— 用掉之后再走完天使线（神性）或恶魔线（亚巴顿）
		//     可以各再拿 1 个（最多 2 个），但 genesis_used 保持为真，所以拿到也不能使用。
		PlayerFlags.setGenesisGrantedGodhead(player, false);
		PlayerFlags.setGenesisGrantedAbaddon(player, false);
		// 1.7.10 收尾：七罪之源的佩戴计时也一起清零 —— 重置后佩戴率重新从 100% 起算、
		// 并重新获得开局宽限，保证"两段线之间"还能做出「纯洁无瑕」。
		PlayerFlags.resetSinWearTracking(player);
		// ⑥c 1.7.3：清掉所有与本模组相关的**内存态**（这些都是每秒/每 tick 维护的临时数据，
		//     不清的话重置后还会有几秒到几分钟的残留：魂心破碎的 5 秒无敌窗口、亚巴顿的复活守卫与
		//     恶魔光环计时、光束冷却与蓄力状态、恶魔交易的驻留计数、黑心补满计时、伤害池的挂起记录）
		com.summy.reliquary.effect.SoulShield.forget(player);
		com.summy.reliquary.effect.DamagePools.forget(player);
		com.summy.reliquary.effect.Abaddon.forget(player);
		com.summy.reliquary.effect.DemonDeal.forget(player);
		com.summy.reliquary.effect.RevelationBeam.forget(player);
		com.summy.reliquary.effect.DemonPact.forget(player);
		// ⑦ 删除栏位与背包里本模组的全部物品
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (isOurItem(player.getInventory().getItem(slot))) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (var entry : handler.getCurios().entrySet()) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					if (isOurItem(entry.getValue().getStacks().getStackInSlot(index))) {
						entry.getValue().getStacks().setStackInSlot(index, ItemStack.EMPTY);
					}
				}
			}
		});
		// ⑧ 重新发放光环与七罪之源
		for (ItemStack gift : List.of(new ItemStack(SummyReliquary.THE_HALO.get()),
				new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()))) {
			if (!player.getInventory().add(gift)) {
				player.drop(gift, false);
			}
		}
		// ⑨ 进度（成就）**一律保留**（1.6.10 起不再撤销 10 条成就）
		com.summy.reliquary.effect.AttributeManager.apply(player);
		com.summy.reliquary.effect.RevelationTracker.sync(player);
		com.summy.reliquary.effect.AngelRoster.refresh(player.getServer());
		com.summy.reliquary.effect.DemonRoster.refresh(player.getServer());
		// ⑩ 1.7.2：送返回重生点 + 不死图腾动画（贴图换成创世纪）+ 音效
		com.summy.reliquary.util.RespawnTeleport.teleport(player);
		activationCount++;
		com.summy.reliquary.net.ReliquaryNetworking.sendGenesisUsedAnimation(player);
		net.minecraft.server.level.ServerLevel effectLevel = player.serverLevel();
		effectLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
				net.minecraft.sounds.SoundEvents.TOTEM_USE, net.minecraft.sounds.SoundSource.PLAYERS,
				1.0F, 1.0F);
		effectLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
				net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.PLAYERS,
				0.8F, 1.0F);
		soundCount += 2;
	}

	/** 自检用：真正执行过多少次"激活表现"（传送 + 动画 + 音效是一次性的，这里只统计次数） */
	private static int activationCount;
	/** 自检用：累计播发过多少次音效（每次激活 2 声：图腾 + 信标） */
	private static int soundCount;

	/** 自检用：复位激活表现的计数 */
	public static void resetActivationCountForTest() {
		activationCount = 0;
		soundCount = 0;
	}

	/** 自检用：读取激活表现的计数（应 > 0） */
	public static int activationCount() {
		return activationCount;
	}

	/** 自检用：读取音效播发计数（应 > 0） */
	public static int soundCount() {
		return soundCount;
	}

	private static boolean isOurItem(ItemStack stack) {
		return !stack.isEmpty()
				&& SummyReliquary.NAMESPACE
						.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
								.getNamespace());
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 1.6.10：获取前禁止查看 —— 没"知道"过它时只有名字 + 一行「你还不知此为何物。」
		LivingEntity self = ReliquaryTooltips.localPlayer();
		if (self != null && !isKnown(self)) {
			tooltip.add(Component.translatable("item.summy-reliquary.genesis.unknown")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(UNKNOWN_GOLD))));
			return;
		}
		for (String key : new String[]{"item.summy-reliquary.genesis.tagline.1",
				"item.summy-reliquary.genesis.tagline.2", "item.summy-reliquary.genesis.tagline.3",
				"item.summy-reliquary.genesis.tagline.4"}) {
			tooltip.add(Component.translatable(key).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA))));
		}
		// 1.6.10：门槛改成"天使或恶魔侧任一标记"
		if (self != null && !isQualified(self)) {
			tooltip.add(Component.translatable("item.summy-reliquary.genesis.locked")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		if (ReliquaryTooltips.shiftDown()) {
			tooltip.add(Component.translatable("item.summy-reliquary.genesis.desc")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(GOLD))));
			tooltip.add(Component.translatable("item.summy-reliquary.genesis.desc.once")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}
}
