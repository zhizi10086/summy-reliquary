package com.summy.reliquary.sin;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.client.ReliquaryClientState;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.RevelationTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 七罪的三种状态与读写。
 *
 * <ul>
 *     <li>{@link SinState#UNACTIVATED} 未激活：提示为灰色；</li>
 *     <li>{@link SinState#ACTIVATED} 已激活：提示为暗红↔亮红呼吸；</li>
 *     <li>{@link SinState#REDEEMED} 已赎罪：提示为金色 + 斜体 + 删除线。</li>
 * </ul>
 *
 * <p>状态按玩家保存在存档数据里（两个位掩码），跨死亡保留、卸下饰品不清除；
 * 命令 {@code /summyreliquary sin <罪> <on|off|redeem|unredeem|query>} 可手动切换，
 * 也可以手持对应的七宗罪碎片右击把「已激活」的罪赎掉。
 * 客户端通过 {@link ReliquaryClientState} 里的同步缓存读取，供提示文本使用。
 */
public final class SinManager {
	/** 玩家持久化 NBT 的根键 */
	public static final String ROOT = "summy_reliquary";
	/** 已激活位掩码 */
	public static final String SINS = "sins";
	/** 已赎罪位掩码 */
	public static final String REDEEMED = "redeemed";

	private SinManager() {
	}

	/** 七罪状态 */
	public enum SinState {
		UNACTIVATED,
		ACTIVATED,
		REDEEMED
	}

	/** 服务端：读取已激活位掩码 */
	public static int mask(LivingEntity entity) {
		return root(entity).getInt(SINS);
	}

	/** 服务端：读取已赎罪位掩码 */
	public static int redeemedMask(LivingEntity entity) {
		return root(entity).getInt(REDEEMED);
	}

	private static CompoundTag root(LivingEntity entity) {
		CompoundTag data = entity.getPersistentData();
		return data.contains(ROOT, CompoundTag.TAG_COMPOUND) ? data.getCompound(ROOT) : new CompoundTag();
	}

	/**
	 * 取出**可变**的本模组根标签：不存在时新建并**挂回**玩家持久化数据。
	 *
	 * <p>注意不能用 {@code getPersistentData().getCompound(ROOT)} 直接写：那个方法在键不存在时
	 * 返回一个**游离**的新标签，写进去的内容会被丢掉（1.5.3 修的就是这个坑）。
	 */
	public static CompoundTag mutableRoot(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		CompoundTag root = data.contains(ROOT, CompoundTag.TAG_COMPOUND) ? data.getCompound(ROOT) : new CompoundTag();
		data.put(ROOT, root);
		return root;
	}

	/** 某个罪当前的状态（客户端读同步缓存） */
	public static SinState state(LivingEntity entity, Sin sin) {
		if (entity == null) {
			return SinState.UNACTIVATED;
		}
		if (entity.level().isClientSide()) {
			int ordinal = sin.ordinal();
			if (ReliquaryClientState.isRedeemed(ordinal)) {
				return SinState.REDEEMED;
			}
			return ReliquaryClientState.isSinActive(ordinal) ? SinState.ACTIVATED : SinState.UNACTIVATED;
		}
		int bit = 1 << sin.ordinal();
		if ((redeemedMask(entity) & bit) != 0) {
			return SinState.REDEEMED;
		}
		return (mask(entity) & bit) != 0 ? SinState.ACTIVATED : SinState.UNACTIVATED;
	}

	/** 是否还有「已激活但未赎罪」的罪（光环减半的判定条件） */
	public static boolean hasAnyUnredeemed(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.hasAnyUnredeemed();
		}
		return (mask(entity) & ~redeemedMask(entity)) != 0;
	}

	/** 七罪是否全部已赎罪 */
	public static boolean allRedeemed(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.allRedeemed(Sin.values().length);
		}
		int all = (1 << Sin.values().length) - 1;
		return (redeemedMask(entity) & all) == all;
	}

	/**
	 * 「七罪全触发」（1.6.10）：**没有任何一项处于未激活**（已激活或已赎罪都算已触发）。
	 *
	 * <p>这是"罪无可赦"与**五芒星发放**共用的唯一判定 —— 两者口径必须一致，
	 * 所以判定逻辑集中在这里，{@link #setState} 触发进度时与 {@code Pentagram} 发放时都调它。
	 */
	public static boolean allSinsTriggered(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		return allSinsTriggered(mask(entity));
	}

	/** 同上，但直接吃位掩码（{@code setState} 里刚写好、还没读盘时用） */
	public static boolean allSinsTriggered(int sinsMask) {
		int all = (1 << Sin.values().length) - 1;
		return (sinsMask & all) == all;
	}

	/** 服务端：写入某个罪的状态 */
	public static void setState(ServerPlayer player, Sin sin, SinState state) {
		int bit = 1 << sin.ordinal();
		CompoundTag root = mutableRoot(player);
		int sins = root.getInt(SINS);
		int redeemed = root.getInt(REDEEMED);

		switch (state) {
			case ACTIVATED -> {
				sins |= bit;
				redeemed &= ~bit;
			}
			case UNACTIVATED -> {
				sins &= ~bit;
				redeemed &= ~bit;
			}
			case REDEEMED -> {
				sins |= bit;
				redeemed |= bit;
			}
		}

		root.putInt(SINS, sins);
		// 进度：七罪同时处于已激活（"罪无可赦"）
		if (allSinsTriggered(sins)) {
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
					com.summy.reliquary.advancement.ReliquaryAdvancements.ALL_SINS_ACTIVE);
			// 1.6.10：五芒星的发放与"罪无可赦"**同一条件、同一时刻**（不再看成就是否已完成）
			com.summy.reliquary.effect.Pentagram.grantIfEarned(player);
		}
		root.putInt(REDEEMED, redeemed);
		SummyReliquary.LOGGER.debug("[Summy Reliquary] {} 的 {} 状态改为 {}（激活掩码 {} / 已赎罪掩码 {}）",
				player.getName().getString(), sin.id(), state, sins, redeemed);
	}

	/**
	 * 由触发条件把某个罪置为「已激活」，并同步客户端与属性。
	 *
	 * @param silent true = 静默激活（例如色欲的隐藏触发条件），不播提示与音效
	 * @return true 表示这次真的从其它状态变成了已激活
	 */
	public static boolean activate(ServerPlayer player, Sin sin, boolean silent) {
		if (state(player, sin) == SinState.ACTIVATED) {
			return false;
		}
		setState(player, sin, SinState.ACTIVATED);
		// 刷新客户端缓存（提示里的三态着色）与属性修正
		RevelationTracker.sync(player);
		AttributeManager.apply(player);

		if (!silent) {
			// 七罪触发提示：只显示该罪的一句台词，整句用该罪的对应色（不显示罪名）
			player.displayClientMessage(
					Component.translatable(sin.awakenedKey())
							.withStyle(net.minecraft.network.chat.Style.EMPTY
									.withColor(net.minecraft.network.chat.TextColor.fromRgb(sin.color()))),
					true);
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 0.9F, 0.7F);
		}
		SummyReliquary.LOGGER.info("[Summy Reliquary] {} 的 {} 因满足条件而觉醒{}",
				player.getName().getString(), sin.id(), silent ? "（隐藏条件）" : "");
		return true;
	}
}
