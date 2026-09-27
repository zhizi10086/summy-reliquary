package com.summy.reliquary.item;

import com.summy.reliquary.sin.SinManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 痛悔短祷（Act of Contrition）：非饰品，一次性消耗品。
 *
 * <p>使用条件（1.5.2 起按「先看维度、再看坐标、最后看天」的顺序分别提示）：
 * 必须身处**主世界**、水平 8 格内贴着**世界出生点**或**自己在主世界里的重生点**、且**头顶露天**。
 * 效果（暂定的测试功能）：清除自身全部负面效果 + 30 秒抗性提升 I + 钟声与圣光粒子；用 `contrition_used`
 * 保证每名玩家一次（OP 可用 {@code /summyreliquary genesis reset <玩家>} 一并重置）。
 *
 * <p>1.5.2 的提示规则：所有结果都走**聊天框**（多行 = 多条消息，不依赖换行符渲染）；
 * **位置失败**按原因分别做 5 秒节流；「已经用过」的三行提示**终身只发一次**（{@code contrition_notified}）。
 */
public class ActOfContritionItem extends Item {
	/** 一次性标记 */
	public static final String USED_KEY = "contrition_used";
	/** 「已经用过」的三行提示只发一次的标记 */
	public static final String NOTIFIED_KEY = "contrition_notified";
	/** 允许的水平距离（格） */
	private static final double RADIUS = 8.0D;
	/** 同一失败原因的最短提示间隔（tick）：5 秒 */
	private static final int FAILURE_COOLDOWN_TICKS = 100;

	// ===== 配色（与文案需求一一对应） =====
	/** 白（成功提示前半句） */
	private static final int WHITE = 0xFFFFFF;
	/** 亮金（成功提示后半句） */
	private static final int BRIGHT_GOLD = 0xFFD700;
	/** 淡金（引文 / 失败提示第二句） */
	private static final int PALE_GOLD = 0xFFE4B5;
	/** 灰（失败提示第一句） */
	private static final int GRAY = 0xAAAAAA;
	/** 暗红（背弃誓言） */
	private static final int DARK_RED = 0x8B0000;
	/** 深灰（没有第二次机会） */
	private static final int DARK_GRAY = 0x555555;
	/** 金色（祷文强调段落用色） */
	private static final int GOLD = 0xFFD700;
	/** 柔白（祷文其余部分） */
	private static final int PRAYER_WHITE = 0xF0F0F0;

	/** 失败原因：不在主世界 */
	public static final String FAIL_DIMENSION = "message.summy-reliquary.contrition.fail.dimension";
	/** 失败原因：在主世界但坐标不在允许范围内 */
	public static final String FAIL_RANGE = "message.summy-reliquary.contrition.fail.range";
	/** 失败原因：坐标正确但头顶不露天 */
	public static final String FAIL_SKY = "message.summy-reliquary.contrition.fail.sky";

	/** 玩家 UUID →（失败原因 → 上次提示的游戏 tick） */
	private static final Map<UUID, Map<String, Long>> FAILURE_SPOKEN_AT = new HashMap<>();

	public ActOfContritionItem(Properties properties) {
		super(properties);
	}

	/** 是否已经用过（每名玩家一次） */
	public static boolean isUsed(Player player) {
		return player.getPersistentData().getCompound(SinManager.ROOT).getBoolean(USED_KEY);
	}

	/** 重置一次性标记 */
	public static void resetUsed(ServerPlayer player) {
		SinManager.mutableRoot(player).putBoolean(USED_KEY, false);
	}

	/** 「已经用过」的三行提示是否已经发过（终身一次） */
	public static boolean isNotified(Player player) {
		return player.getPersistentData().getCompound(SinManager.ROOT).getBoolean(NOTIFIED_KEY);
	}

	/** 复位「已经用过」的提示标记（OP 重置命令用） */
	public static void resetNotified(ServerPlayer player) {
		SinManager.mutableRoot(player).putBoolean(NOTIFIED_KEY, false);
	}

	/**
	 * 当前位置的失败原因：不在主世界 / 坐标不符 / 头顶不露天；全部满足时返回 null。
	 *
	 * <p>顺序即提示顺序：先看维度、再看坐标、最后看天。
	 */
	public static String failureKey(ServerPlayer player) {
		if (player.level().dimension() != Level.OVERWORLD) {
			return FAIL_DIMENSION;
		}
		if (!nearAnchor(player)) {
			return FAIL_RANGE;
		}
		if (!player.level().canSeeSky(player.blockPosition())) {
			return FAIL_SKY;
		}
		return null;
	}

	/** 是否水平 8 格内贴着世界出生点或「自己在主世界的重生点」（忽略 Y） */
	private static boolean nearAnchor(ServerPlayer player) {
		BlockPos spawn = player.serverLevel().getSharedSpawnPos();
		if (horizontalDistance(player.getX(), player.getZ(), spawn.getX(), spawn.getZ()) <= RADIUS) {
			return true;
		}
		BlockPos respawn = player.getRespawnPosition();
		return respawn != null
				&& player.getRespawnDimension() == Level.OVERWORLD
				&& horizontalDistance(player.getX(), player.getZ(), respawn.getX(), respawn.getZ()) <= RADIUS;
	}

	private static double horizontalDistance(double x1, double z1, double x2, double z2) {
		double dx = x1 - x2;
		double dz = z1 - z2;
		return Math.sqrt(dx * dx + dz * dz);
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
		// 1.6.0：邪恶度达到 666 之后永久锁定天使线 —— 拒绝使用且**不消耗**
		if (com.summy.reliquary.effect.PlayerFlags.isHellLocked(serverPlayer)) {
			sendLines(serverPlayer, hellLockedLines());
			return InteractionResultHolder.fail(stack);
		}
		// ① 已经用过：三行提示终身只发一次，之后静默（但仍照常失败、不消耗）
		if (isUsed(serverPlayer)) {
			notifyUsedOnce(serverPlayer);
			return InteractionResultHolder.fail(stack);
		}
		// ② 位置不符：按原因分别 5 秒节流后走聊天框
		String failure = failureKey(serverPlayer);
		if (failure != null) {
			speakFailure(serverPlayer, failure, serverPlayer.serverLevel().getGameTime());
			return InteractionResultHolder.fail(stack);
		}

		// 测试功能：清负面 + 30 秒抗性提升 I + 钟声与圣光粒子
		serverPlayer.removeAllEffects();
		serverPlayer.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 600, 0, false, false));
		ServerLevel serverLevel = serverPlayer.serverLevel();
		serverLevel.playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
				SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.0F, 1.0F);
		serverLevel.sendParticles(ParticleTypes.END_ROD, serverPlayer.getX(), serverPlayer.getY() + 1.0D,
				serverPlayer.getZ(), 40, 0.6D, 0.8D, 0.6D, 0.05D);
		SinManager.mutableRoot(serverPlayer).putBoolean(USED_KEY, true);
		stack.shrink(1);
		sendLines(serverPlayer, successLines());
		// 1.5.9：持有恶魔标记的玩家，用痛悔短祷把契约标记换回天使标记（依然限用一次）
		swapDemonToAngel(serverPlayer);
		return InteractionResultHolder.success(stack);
	}

	/**
	 * 成功使用痛悔短祷时把「恶魔」标记换回「天使」标记（1.5.9）。
	 *
	 * <p>互斥关系由 {@code PlayerFlags.setDemon/setAngel} 处理（后设置者生效）；契约历史
	 * （{@code demon_sealed}）保留——所以恶魔之后仍会在五芒星提示里说"我们的约定依然作数"。
	 *
	 * @return true 表示这次真的发生了替换
	 */
	public static boolean swapDemonToAngel(ServerPlayer player) {
		if (!com.summy.reliquary.effect.PlayerFlags.isDemon(player)) {
			return false;
		}
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		// 1.6.0：忏悔会**连「恶魔契约」栏位与契约一起收回**（邪恶度与解锁保留在玩家身上）
		com.summy.reliquary.effect.DemonPact.revoke(player);
		// 1.6.1：按签约快照恢复魂印栏物品与七罪逐项状态
		// （美德进的签约 → 拿回美德；3 罪已赎 + 4 罪激活 → 仍是 3+4）
		com.summy.reliquary.effect.DemonPact.restoreSinSnapshot(player);
		// 1.7.2：按"不可回头点为界"的口径收尾 ——
		//   ① 魂印栏的撒旦圣经被没收（快照缺失时换成七罪之源，保证它不会留在原位）；
		//   ② 666 之前可得的恶魔线饰品一律没收（契约已在上面收回）；
		//   ③ 666 之后才解锁的（硫磺火 / 魔眼 / 深渊领主 / 亚巴顿）只摘除不删除；
		//   ④ 恶魔王冠按需求不没收、不摘除（留作纪念）。
		com.summy.reliquary.effect.DemonPact.confiscateSatanicBible(player);
		com.summy.reliquary.effect.DemonPact.confiscateDemonRelics(player);
		com.summy.reliquary.effect.DemonPact.unequipHighDemonRelics(player);
		// 1.6.2：清掉「6」的掉落记录 —— 忏悔后三类 Boss 可以再各掉一次
		com.summy.reliquary.effect.PlayerFlags.clearSixDrops(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.RevelationTracker.sync(player);
		return true;
	}

	/**
	 * 邪恶度达到 666 之后（`hell_locked`）念祷词的四行反馈（1.6.0）。
	 *
	 * <p>前三行是叙事（灰），最后一行引号里的低语用暗红斜体（与"恶魔话语"的约定一致）。
	 */
	public static List<Component> hellLockedLines() {
		List<Component> lines = new ArrayList<>();
		for (int index = 1; index <= 3; index++) {
			lines.add(Component.translatable("message.summy-reliquary.pact.hell_locked." + index)
					.withStyle(color(GRAY)));
		}
		lines.add(Component.translatable("message.summy-reliquary.pact.hell_locked.4")
				.withStyle(color(DARK_RED).withItalic(true)));
		return lines;
	}

	// ==================== 提示文本（自检直接复用这些方法） ====================

	/** 成功提示两行：行1 白 + 亮金、行2 淡金斜体引文 */
	public static List<Component> successLines() {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.empty()
				.append(Component.translatable("message.summy-reliquary.contrition.success.1")
						.withStyle(color(WHITE)))
				.append(Component.translatable("message.summy-reliquary.contrition.success.2")
						.withStyle(color(BRIGHT_GOLD))));
		lines.add(Component.translatable("message.summy-reliquary.contrition.success.quote")
				.withStyle(color(PALE_GOLD).withItalic(true)));
		return lines;
	}

	/** 「已经用过」的三行提示：灰 / 暗红 / 深灰斜体 */
	public static List<Component> repeatLines() {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("message.summy-reliquary.contrition.repeat.1")
				.withStyle(color(GRAY)));
		lines.add(Component.translatable("message.summy-reliquary.contrition.repeat.2")
				.withStyle(color(DARK_RED)));
		lines.add(Component.translatable("message.summy-reliquary.contrition.repeat.3")
				.withStyle(color(DARK_GRAY).withItalic(true)));
		return lines;
	}

	/** 某种位置失败的两行提示：灰 + 淡金斜体 */
	public static List<Component> failureLines(String failureKey) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable(failureKey + ".1").withStyle(color(GRAY)));
		lines.add(Component.translatable(failureKey + ".2").withStyle(color(PALE_GOLD).withItalic(true)));
		return lines;
	}

	/** 风味文本两行的语言键：第二行按「有没有主世界重生点」切换 */
	public static String[] flavorKeys(boolean overworldRespawn) {
		return new String[]{
				"item.summy-reliquary.act_of_contrition.tagline.head",
				overworldRespawn
						? "item.summy-reliquary.act_of_contrition.tagline.respawn"
						: "item.summy-reliquary.act_of_contrition.tagline.origin"};
	}

	// ==================== 节流与发送 ====================

	/**
	 * 同一失败原因 5 秒内只提示一次（不同原因各自计时）。
	 *
	 * <p>把「当前 tick」作为参数传入，是为了让自检能伪造时间、不必真的等 5 秒。
	 *
	 * @return true 表示这次可以提示（并已记录时间）
	 */
	public static boolean claimFailureSlot(ServerPlayer player, String failureKey, long gameTime) {
		Map<String, Long> spoken = FAILURE_SPOKEN_AT.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
		Long last = spoken.get(failureKey);
		if (last != null && gameTime - last < FAILURE_COOLDOWN_TICKS) {
			return false;
		}
		spoken.put(failureKey, gameTime);
		return true;
	}

	/** 位置失败：先过节流，再发两行 */
	private static void speakFailure(ServerPlayer player, String failureKey, long gameTime) {
		if (claimFailureSlot(player, failureKey, gameTime)) {
			sendLines(player, failureLines(failureKey));
		}
	}

	/**
	 * 「已经用过」的三行提示只发一次（终身，落盘在 {@code contrition_notified}）。
	 *
	 * @return true 表示这次发了
	 */
	public static boolean notifyUsedOnce(ServerPlayer player) {
		if (isNotified(player)) {
			return false;
		}
		sendLines(player, repeatLines());
		SinManager.mutableRoot(player).putBoolean(NOTIFIED_KEY, true);
		return true;
	}

	/** 逐行发到聊天框：每行一条消息，避免依赖换行符渲染 */
	private static void sendLines(ServerPlayer player, List<Component> lines) {
		for (Component line : lines) {
			player.sendSystemMessage(line);
		}
	}

	private static Style color(int rgb) {
		return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
	}

	/** 退出游戏时清掉节流表 */
	public static void forget(ServerPlayer player) {
		FAILURE_SPOKEN_AT.remove(player.getUUID());
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 风味文本：第二行按「有没有位于主世界的个人重生点」切换（客户端读同步来的标记位）
		boolean overworldRespawn = com.summy.reliquary.effect.PlayerFlags
				.hasOverworldRespawn(ReliquaryTooltips.localPlayer());
		for (String key : flavorKeys(overworldRespawn)) {
			tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
		}
		if (ReliquaryTooltips.shiftDown()) {
			// 祷文很长，原版提示框不会自动折行，这里按提示框宽度手动切行
			tooltip.addAll(ReliquaryTooltips.wrap(shiftPrayer()));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/**
	 * Shift 里的一行祷文：前后白色、"我今定志……" 一段金色。
	 * 1.5.1 起三段合并成**同一行**（不再拆成三行）；自检也复用本方法，便于断言"不换行 + 配色"。
	 */
	public static Component shiftPrayer() {
		return Component.empty()
				.append(Component.translatable("item.summy-reliquary.act_of_contrition.desc.1")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PRAYER_WHITE))))
				.append(Component.translatable("item.summy-reliquary.act_of_contrition.desc.2")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(GOLD))))
				.append(Component.translatable("item.summy-reliquary.act_of_contrition.desc.3")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PRAYER_WHITE))));
	}
}
