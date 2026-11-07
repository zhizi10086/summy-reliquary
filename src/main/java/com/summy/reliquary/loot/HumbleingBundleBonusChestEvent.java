package com.summy.reliquary.loot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraftforge.eventbus.api.Event;

/**
 * 一次慈善捆绑包 BONUS 开箱的公共兼容事件。
 *
 * <p>SRA 等扩展模组可在该事件中推进自己的箱子获取状态；事件在一个物理开箱中最多发布一次。
 */
public final class HumbleingBundleBonusChestEvent extends Event {
	private final ServerPlayer player;
	private final ServerLevel level;
	private final LootContext context;

	public HumbleingBundleBonusChestEvent(ServerPlayer player, ServerLevel level, LootContext context) {
		this.player = player;
		this.level = level;
		this.context = context;
	}

	public ServerPlayer getPlayer() {
		return player;
	}

	public ServerLevel getLevel() {
		return level;
	}

	public LootContext getContext() {
		return context;
	}
}
