package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.advancement.ItemObtained;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.item.ActOfContritionItem;
import com.summy.reliquary.net.ReliquaryNetworking;
import com.summy.reliquary.sin.SinManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biomes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「恶魔交易」（1.5.9）：灵魂沙峡谷的驻留对话 + 手持五芒星长按右键 5 秒签约。
 *
 * <p>流程（全部前置：身上有五芒星 + 已说过那句「交易」+ 尚未签过契约）：
 * <ol>
 *     <li><b>邀请</b>：在灵魂沙峡谷连续停留 5 秒 → 按赎罪进度分三版台词（A 默认 / B 部分赎罪 / C 全赎清或已有天使标记）；</li>
 *     <li><b>离场</b>：带着邀请离开峡谷 → 3 行「没关系……」；再次回来驻留满 5 秒 → 2 行「想的怎么样……」；</li>
 *     <li><b>签约</b>：手持五芒星长按右键 5 秒 → 图腾动画（贴图换成五芒星）+ 灵魂低鸣 + 火焰/灵魂粒子 → 获得恶魔标记。</li>
 * </ol>
 *
 * <p>天启判据（首次与回归共用）：进度 {@code revelation} 已完成 **或** 身上有终末天启 → 只静默置位，不再开口。
 * 契约**终身一次**；签过之后（{@code demon_sealed}）既不能再签，也不会再触发任何对话。
 */
public final class DemonDeal {
	/** 恶魔台词在聊天框里的颜色：暗红（长句在聊天背景上更耐看） */
	public static final int CHAT_RED = 0x8B0000;
	/** 「（手持五芒星，长按右键签署契约）」这一行的灰 */
	public static final int HINT_GRAY = 0xAAAAAA;
	/** 签约特效里每种粒子的数量（火焰 / 灵魂 / 灵魂火各一圈） */
	private static final int SOUL_PARTICLES = 20;

	/** 玩家 → 已在峡谷里连续停留的秒数 */
	private static final Map<UUID, Integer> DWELL = new HashMap<>();
	/** 玩家 → 上一次 tick 时是否算"在峡谷里"（用于识别"离开"） */
	private static final Map<UUID, Boolean> LAST_IN_BIOME = new HashMap<>();
	/** 玩家 → 上次发「这里不是那个地方」的 tick（按玩家节流） */
	private static final Map<UUID, Long> WRONG_PLACE_AT = new HashMap<>();

	/** 自检缝：强制"在 / 不在灵魂沙峡谷"（null = 真实判定） */
	private static Boolean forcedValley = null;
	/** 自检缝：最近一次对话类型（invite:A / invite:B / invite:C / leave / return / signed / signed-return / wrong-place） */
	private static String lastDialogue = null;

	private DemonDeal() {
	}

	// ==================== 台词（自检与客户端断言复用） ====================

	/** 恶魔的一句话：暗红 + 斜体 */
	public static Component demon(String key) {
		return Component.translatable(key).withStyle(
				Style.EMPTY.withColor(TextColor.fromRgb(CHAT_RED)).withItalic(true));
	}

	/** 灰色正体提示行（「（手持五芒星，长按右键签署契约）」） */
	public static Component hint(String key) {
		return Component.translatable(key).withStyle(
				Style.EMPTY.withColor(TextColor.fromRgb(HINT_GRAY)));
	}

	/** 邀请台词版本：2 = C（七罪全赎清或已有天使标记）、1 = B（有赎罪进度但没全赎清）、0 = A（默认） */
	public static int inviteVariant(ServerPlayer player) {
		if (SinManager.allRedeemed(player) || PlayerFlags.hasAngel(player)) {
			return 2;
		}
		return SinManager.redeemedMask(player) != 0 ? 1 : 0;
	}

	/** 邀请台词（含末尾那行灰色提示） */
	public static List<Component> inviteLines(ServerPlayer player) {
		List<Component> lines = new ArrayList<>();
		lines.add(demon("message.summy-reliquary.demon.invite.1"));
		switch (inviteVariant(player)) {
			case 2 -> {
				lines.add(demon("message.summy-reliquary.demon.invite.perfect.1"));
				lines.add(demon("message.summy-reliquary.demon.invite.perfect.2"));
				lines.add(demon("message.summy-reliquary.demon.invite.perfect.3"));
			}
			case 1 -> {
				lines.add(demon("message.summy-reliquary.demon.invite.2"));
				// 「虽然你做了很多，但我有更简单的办法」= 插在默认文本第 2~3 行**中间**（不替换原文）
				lines.add(demon("message.summy-reliquary.demon.invite.partial"));
				lines.add(demon("message.summy-reliquary.demon.invite.3"));
			}
			default -> {
				lines.add(demon("message.summy-reliquary.demon.invite.2"));
				lines.add(demon("message.summy-reliquary.demon.invite.3"));
			}
		}
		lines.add(demon("message.summy-reliquary.demon.invite.4"));
		lines.add(demon("message.summy-reliquary.demon.invite.5"));
		lines.add(hint("message.summy-reliquary.demon.invite.hint"));
		return lines;
	}

	/** 离开峡谷的三行 */
	public static List<Component> leaveLines() {
		return List.of(
				demon("message.summy-reliquary.demon.leave.1"),
				demon("message.summy-reliquary.demon.leave.2"),
				demon("message.summy-reliquary.demon.leave.3"));
	}

	/** 回到峡谷的两行 */
	public static List<Component> returnLines() {
		return List.of(
				demon("message.summy-reliquary.demon.return.1"),
				demon("message.summy-reliquary.demon.return.2"));
	}

	/**
	 * 签约台词：**已用过痛悔短祷**的玩家用两行替换版，其余玩家用原来的四行。
	 */
	public static List<Component> signedLines(ServerPlayer player) {
		if (ActOfContritionItem.isUsed(player)) {
			return List.of(
					demon("message.summy-reliquary.demon.signed.return.1"),
					demon("message.summy-reliquary.demon.signed.return.2"));
		}
		return List.of(
				demon("message.summy-reliquary.demon.signed.1"),
				demon("message.summy-reliquary.demon.signed.2"),
				demon("message.summy-reliquary.demon.signed.3"),
				demon("message.summy-reliquary.demon.signed.4"));
	}

	/** 「这里显然不是那个"埋葬无尽灵魂"的地方」 */
	public static Component wrongPlaceLine() {
		return demon("message.summy-reliquary.demon.wrong_place");
	}

	// ==================== 判定 ====================

	/** 身上（背包或饰品栏）是否有五芒星 */
	public static boolean hasPentagram(ServerPlayer player) {
		return ItemObtained.has(player, SummyReliquary.PENTAGRAM.get());
	}

	/** 是否位于灵魂沙峡谷（自检可用 {@link #setForcedValley} 伪造） */
	public static boolean inSoulSandValley(net.minecraft.world.entity.Entity entity) {
		if (forcedValley != null) {
			return forcedValley;
		}
		if (entity == null) {
			return false;
		}
		BlockPos pos = entity.blockPosition();
		return entity.level().getBiome(pos).is(Biomes.SOUL_SAND_VALLEY);
	}

	/**
	 * 前置：身上有五芒星 + 已说过那句「交易」+ **当前不是恶魔**（1.5.10：曾签约不再阻止再来一次，
	 * 只有"此刻还持着恶魔标记"才挡住 —— 这样「签署 → 忏悔 → 再签署」才能走通）。
	 *
	 * <p><b>1.6.10</b>：新增"**没有获取过启示**"这一条 —— 一旦拿到启示（星→天启 / 持有天启或神性），
	 * 恶魔交易就**彻底关闭**（启示是天使线的顶点，两条线从此互斥）。创世纪会清掉该属性，
	 * 于是重置之后恶魔线重新开放。
	 *
	 * <p><b>1.7.2</b>：恶魔交易的三条封锁统一收在这里 ——
	 * <ul>
	 *     <li><b>已获取启示</b>（标记级、不可逆；持有终末天启 / 神性会被每秒兜底置位）；</li>
	 *     <li><b>已放弃一切</b>（「无罪之人」的终身锁，唯一解锁途径是 OP {@code dragon reset}）；</li>
	 *     <li><b>持有圣心</b>（物品级：把圣心放进箱子 / 交给别人就能解除封锁）。</li>
	 * </ul>
	 */
	public static boolean isQualified(ServerPlayer player) {
		return ReliquaryConfig.enableDemonDeal()
				&& hasPentagram(player)
				&& PlayerFlags.isPentagramSpoken(player)
				&& !PlayerFlags.isDemon(player)
				&& !PlayerFlags.isRevelationObtained(player)
				&& !PlayerFlags.isSinRenounced(player)
				&& !holdsSacredHeart(player);
	}

	/**
	 * 恶魔交易是否已被永久/半永久关闭（提示与客户端预判共用，1.7.2）。
	 *
	 * <p>= 已获取启示 ‖ 已放弃一切 ‖ 持有圣心。
	 */
	public static boolean tradeClosed(Player player) {
		return PlayerFlags.isRevelationObtained(player)
				|| PlayerFlags.isSinRenounced(player)
				|| holdsSacredHeart(player);
	}

	/**
	 * 是否持有圣心（背包或任意饰品栏，1.7.2）。
	 *
	 * <p>参数故意用 {@link Player} 而不是 {@code ServerPlayer} —— 客户端预判（{@code PentagramItem}
	 * 的右键门槛）也要调它，而客户端只有 LocalPlayer。客户端读的是自己那份背包与 Curios 副本，
	 * 背包里的圣心是即时可见的。
	 */
	public static boolean holdsSacredHeart(Player player) {
		if (player == null) {
			return false;
		}
		return com.summy.reliquary.util.CurioHelper.wears(player, SummyReliquary.SACRED_HEART.get())
				// 1.7.10 修订：按物品类型判定（忽略 NBT），并覆盖副手 / 盔甲 / 光标
				|| com.summy.reliquary.util.HeldItems.holds(player, SummyReliquary.SACRED_HEART.get());
	}

	/** 能否签约（长按蓄满时的复核条件） */
	public static boolean canSign(ServerPlayer player) {
		return isQualified(player) && inSoulSandValley(player) && PlayerFlags.isDemonInvited(player);
	}

	/** 签约所需的蓄力 tick 数 */
	public static int chargeTicks() {
		return ReliquaryConfig.demonChargeTicks();
	}

	// ==================== 每秒推进（驻留 / 离场 / 回归） ====================

	/** 服务端每秒对每个玩家调用一次 */
	public static void tick(ServerPlayer player) {
		if (player == null || !ReliquaryConfig.enableDemonDeal()) {
			return;
		}
		UUID id = player.getUUID();
		// 1.5.10：曾签过契约的玩家**永远不再触发任何对话**（邀请 / 离场 / 回归），
		// 于是"第二轮"天然静默——玩家回到峡谷直接长按 5 秒即可再签。
		if (!isQualified(player) || PlayerFlags.isDemonSealed(player)) {
			// 前置不满足（没有五芒星 / 还没听过那句交易 / 已经签过）：不推进任何对话
			DWELL.remove(id);
			LAST_IN_BIOME.remove(id);
			return;
		}
		boolean inValley = inSoulSandValley(player);
		boolean wasIn = LAST_IN_BIOME.getOrDefault(id, false);
		LAST_IN_BIOME.put(id, inValley);

		if (inValley) {
			int dwell = DWELL.merge(id, 1, Integer::sum);
			if (dwell >= ReliquaryConfig.demonBiomeDwellSeconds()) {
				DWELL.remove(id);
				onDwellComplete(player);
			}
			return;
		}
		// 不在峡谷：如果刚才还在（说明是"离开"），发那三行
		DWELL.remove(id);
		if (wasIn && PlayerFlags.isDemonInvited(player) && !PlayerFlags.isDemonLeft(player)) {
			PlayerFlags.setDemonLeft(player, true);
			lastDialogue = "leave";
			DelayedChat.send(player, leaveLines());
		}
	}

	/** 驻留满 5 秒：发邀请，或（已邀请且离开过）发回归 */
	private static void onDwellComplete(ServerPlayer player) {
		if (!PlayerFlags.isDemonInvited(player)) {
			PlayerFlags.setDemonInvited(player, true);
			// 1.6.10：拿到启示的玩家根本进不到这里（isQualified 已把他挡在门外），
			// 所以不再有"静默邀请"这一态，一律发邀请台词。
			lastDialogue = "invite:" + switch (inviteVariant(player)) {
				case 2 -> "C";
				case 1 -> "B";
				default -> "A";
			};
			DelayedChat.send(player, inviteLines(player));
			return;
		}
		if (PlayerFlags.isDemonLeft(player) && !PlayerFlags.isDemonReturned(player)) {
			PlayerFlags.setDemonReturned(player, true);
			lastDialogue = "return";
			DelayedChat.send(player, returnLines());
		}
	}

	// ==================== 错误位置提示 ====================

	/**
	 * 不在灵魂沙峡谷时提示「这里显然不是那个"埋葬无尽灵魂"的地方」（按玩家节流）。
	 *
	 * <p>把"当前 tick"作为参数传入，自检就能伪造时间、不必真的等 5 秒。
	 *
	 * @return true 表示这次真的提示了
	 */
	public static boolean speakWrongPlaceIfNeeded(ServerPlayer player, long gameTime) {
		if (player == null || inSoulSandValley(player)) {
			return false;
		}
		Long last = WRONG_PLACE_AT.get(player.getUUID());
		if (last != null && gameTime - last < ReliquaryConfig.demonErrorCooldownTicks()) {
			return false;
		}
		WRONG_PLACE_AT.put(player.getUUID(), gameTime);
		player.sendSystemMessage(wrongPlaceLine());
		lastDialogue = "wrong-place";
		return true;
	}

	// ==================== 签约 ====================

	/** 签下契约：置恶魔标记（清掉天使标记）、播特效与台词。返回是否真的签了 */
	public static boolean sign(ServerPlayer player) {
		if (!canSign(player)) {
			return false;
		}
		PlayerFlags.setDemonSealed(player, true);
		PlayerFlags.setDemonInvited(player, true);
		// 互斥：置恶魔标记会顺带清掉天使标记，并广播两份名单
		PlayerFlags.setDemon(player, true);
		RevelationTracker.sync(player);
		// 1.6.0：发放「恶魔契约」栏位与契约（强制佩戴）、重置献祭流程、没收天国圣物
		DemonPact.grant(player, true);

		ServerLevel level = player.serverLevel();
		// ① 不死图腾动画（贴图换成五芒星）—— 由客户端播
		ReliquaryNetworking.sendDemonDealAnimation(player);
		// ② 灵魂低鸣（明确不是图腾音效）
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 1.0F, 0.5F);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 1.0F, 0.7F);
		// ③ 火焰 + 灵魂 + 灵魂火粒子
		spawnDealParticles(level, player);
		// ④ 台词：用过痛悔短祷的玩家走两行替换版
		lastDialogue = ActOfContritionItem.isUsed(player) ? "signed-return" : "signed";
		DelayedChat.send(player, signedLines(player));
		// ⑤ 不消耗五芒星
		return true;
	}

	/** 签约特效：三种粒子各一圈环绕玩家 */
	private static void spawnDealParticles(ServerLevel level, ServerPlayer player) {
		double x = player.getX();
		double y = player.getY() + 1.0D;
		double z = player.getZ();
		level.sendParticles(ParticleTypes.FLAME, x, y, z, SOUL_PARTICLES, 0.6D, 0.8D, 0.6D, 0.05D);
		level.sendParticles(ParticleTypes.SOUL, x, y, z, SOUL_PARTICLES, 0.6D, 0.8D, 0.6D, 0.02D);
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, SOUL_PARTICLES, 0.6D, 0.8D, 0.6D, 0.02D);
	}

	// ==================== 自检接口 ====================

	/** 最近一次对话类型（自检断言用） */
	public static String lastDialogue() {
		return lastDialogue;
	}

	/** 自检缝：强制"在 / 不在灵魂沙峡谷"（null = 真实判定） */
	public static void setForcedValley(Boolean value) {
		forcedValley = value;
	}

	/** 自检用：只清掉"错误位置提示"的节流记录（方便连续断言） */
	public static void forgetThrottle(ServerPlayer player) {
		if (player != null) {
			WRONG_PLACE_AT.remove(player.getUUID());
		}
	}

	/** 自检：清掉内存状态与五个 NBT 键（对话相关） */
	public static void resetForTest(ServerPlayer player) {
		DWELL.clear();
		LAST_IN_BIOME.clear();
		WRONG_PLACE_AT.clear();
		forcedValley = null;
		lastDialogue = null;
		if (player != null) {
			PlayerFlags.resetDemonDeal(player);
		}
	}

	/** 登出：清掉该玩家的内存状态与延迟队列 */
	public static void forget(ServerPlayer player) {
		if (player == null) {
			return;
		}
		DWELL.remove(player.getUUID());
		LAST_IN_BIOME.remove(player.getUUID());
		WRONG_PLACE_AT.remove(player.getUUID());
		DelayedChat.forget(player);
	}

	/** 服务器停止：清空全部内存状态 */
	public static void clear() {
		DWELL.clear();
		LAST_IN_BIOME.clear();
		WRONG_PLACE_AT.clear();
		forcedValley = null;
		lastDialogue = null;
	}

	/** 自检用：该玩家当前的峡谷驻留秒数（1.7.3 用来验证创世纪会把它清掉） */
	public static int dwellSecondsForTest(ServerPlayer player) {
		return DWELL.getOrDefault(player.getUUID(), 0);
	}

	/** 自检用：直接写峡谷驻留计数（1.7.3 用来验证创世纪会把它清掉） */
	public static void setDwellForTest(ServerPlayer player, int seconds) {
		DWELL.put(player.getUUID(), seconds);
	}

}
