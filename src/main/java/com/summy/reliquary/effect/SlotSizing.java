package com.summy.reliquary.effect;

import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * 按「阶段」动态调整栏位格数（1.6.2）。
 *
 * <p>阶段判定只看当前持有的标记：**恶魔标记 → 恶魔线；天使标记 → 天使线；都没有 → 七罪阶段**。
 *
 * <table>
 *     <tr><th>栏位</th><th>七罪</th><th>天使</th><th>恶魔</th></tr>
 *     <tr><td>灵台</td><td>0</td><td>3</td><td>1（放咒印）</td></tr>
 *     <tr><td>启示之座</td><td>0</td><td>1</td><td>1</td></tr>
 *     <tr><td>加护</td><td>0</td><td>2</td><td>2</td></tr>
 * </table>
 *
 * <p>缩格时先把格内物品**退回背包（满了掉脚下）**再缩，避免物品凭空消失；
 * 登录读档阶段（本模组数据还没就绪）不做任何调整，防止把刚读出来的饰品误判成"七罪阶段"。
 */
public final class SlotSizing {
	/** 阶段 */
	public enum Phase {
		/** 七罪阶段（既没有天使标记也没有恶魔标记） */
		SINS,
		/** 天使线 */
		ANGEL,
		/** 恶魔线 */
		DEMON
	}

	private SlotSizing() {
	}

	/** 当前阶段（客户端也能用，标记读取是双端的） */
	public static Phase phaseOf(net.minecraft.world.entity.LivingEntity entity) {
		if (PlayerFlags.isDemon(entity)) {
			return Phase.DEMON;
		}
		if (PlayerFlags.hasAngel(entity)) {
			return Phase.ANGEL;
		}
		return Phase.SINS;
	}

	/** 某栏位在该阶段应有的格数（不在管辖范围返回 -1） */
	public static int targetSlots(String slotId, Phase phase) {
		if (ReliquarySlots.SPIRIT_ALTAR.equals(slotId)) {
			return switch (phase) {
				case ANGEL -> 3;
				case DEMON -> 1;
				case SINS -> 0;
			};
		}
		if (ReliquarySlots.REVELATION.equals(slotId)) {
			return phase == Phase.SINS ? 0 : 1;
		}
		if (ReliquarySlots.BLESSING.equals(slotId)) {
			return phase == Phase.SINS ? 0 : 2;
		}
		return -1;
	}

	/** 服务端每秒校正（登录读档阶段跳过） */
	public static void tickPlayer(ServerPlayer player) {
		if (!PlayerFlags.isDataReady(player)) {
			return;
		}
		Phase phase = phaseOf(player);
		for (String slotId : new String[]{ReliquarySlots.SPIRIT_ALTAR, ReliquarySlots.REVELATION,
				ReliquarySlots.BLESSING}) {
			sync(player, slotId, targetSlots(slotId, phase));
		}
	}

	/** 把某个栏位的格数校正到目标值（缩格前先退回物品） */
	public static void sync(ServerPlayer player, String slotId, int target) {
		if (target < 0) {
			return;
		}
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return;
		}
		var stacks = handler.getCurios().get(slotId);
		if (stacks == null) {
			return;
		}
		int current = stacks.getSlots();
		if (current == target) {
			return;
		}
		if (target > current) {
			handler.growSlotType(slotId, target - current);
			return;
		}
		// 缩格：先把要移除的格子里的物品退回背包
		for (int index = target; index < current; index++) {
			ItemStack stack = stacks.getStacks().getStackInSlot(index);
			if (stack.isEmpty()) {
				continue;
			}
			handler.setEquippedCurio(slotId, index, ItemStack.EMPTY);
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false);
			}
		}
		handler.shrinkSlotType(slotId, current - target);
	}

	/** 自检 / 指令用：立即按当前阶段校正所有受管辖栏位 */
	public static void syncNow(ServerPlayer player) {
		Phase phase = phaseOf(player);
		for (String slotId : new String[]{ReliquarySlots.SPIRIT_ALTAR, ReliquarySlots.REVELATION,
				ReliquarySlots.BLESSING}) {
			sync(player, slotId, targetSlots(slotId, phase));
		}
	}

	/** 该玩家某个栏位当前的格数（自检用） */
	public static int slotsOf(ServerPlayer player, String slotId) {
		return CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(slotId)
						? handler.getCurios().get(slotId).getSlots() : -1)
				.orElse(-1);
	}

	/** 是否佩戴着某件物品（供自检断言阶段与内容的配合） */
	public static boolean wears(ServerPlayer player, net.minecraft.world.item.Item item) {
		return CurioHelper.wears(player, item);
	}
}
