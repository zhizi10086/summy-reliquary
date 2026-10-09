package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.net.ReliquaryNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端「玩家动作」交互（1.8.5）。
 *
 * <p>目前只有一条：**在背包 / Curios 面板里对着「神性」右键** → 切换它的「神圣光环」开关。
 *
 * <p>实现说明（1.20.1 实测口径，零 Mixin）：
 * <ul>
 *     <li>Forge 是在 {@code MouseHandler} 里就 post 了 {@link ScreenEvent.MouseButtonPressed.Pre}，
 *     **取消即跳过整个 {@code screen.mouseClicked(...)}** —— 所以在这里取消能吃掉原版的"取半栈"；</li>
 *     <li>{@code AbstractContainerScreen#getSlotUnderMouse()} 是 **public**；Curios 的栏目是真
 *     {@code Slot}（{@code CurioSlot extends SlotItemHandler}，且被 {@code CuriosContainerV2.addSlot} 注册进菜单），
 *     所以**装备在 Curios 栏里的那一件同样能被命中**；</li>
 *     <li><b>范围只限"玩家自己的背包 + Curios 栏目"</b>（1.8.5 收紧）：箱子 / 工作台 / 熔炉 / 末影箱等
 *     容器界面里悬停神性右键**不再被吃掉**，恢复原版的"取半栈"。判定见 {@link #isPlayerOwnedSlot(Slot)}。</li>
 * </ul>
 *
 * <p>手持神性 + 潜行右键那条走 {@code GodheadItem#use}，不走这里。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
		value = Dist.CLIENT)
public final class ReliquaryClientInteractions {
	/** 右键 */
	private static final int RIGHT_BUTTON = 1;

	private ReliquaryClientInteractions() {
	}

	/** 背包 / Curios 面板里右键神性 → 请求切换光环开关 */
	@SubscribeEvent
	public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
		if (event.getButton() != RIGHT_BUTTON) {
			return;
		}
		if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
			return;
		}
		if (!takesOver(screen.getSlotUnderMouse())) {
			return;
		}
		// 吃掉这次右键：不取半栈、不触发其它模组的容器点击逻辑
		event.setCanceled(true);
		ReliquaryNetworking.sendToggleAura();
	}

	/** 这次右键要不要被我们接管：既是神性，又位于玩家自己的背包 / Curios 栏目里 */
	public static boolean takesOver(Slot slot) {
		return isGodheadSlot(slot) && isPlayerOwnedSlot(slot);
	}

	/** 该栏位是不是装着「神性」（抽成纯函数，自检直接断言） */
	public static boolean isGodheadSlot(Slot slot) {
		return slot != null && slot.getItem().is(SummyReliquary.GODHEAD.get());
	}

	/**
	 * 该栏位是不是"玩家自己的"（抽成纯函数，自检直接断言）。
	 *
	 * <p>两个来源：
	 * <ul>
	 *     <li>{@code CurioSlot}（含 {@code CosmeticCurioSlot}）—— Curios 的栏目，无论出现在背包界面还是
	 *     Curios 面板里都算；</li>
	 *     <li>栏位容器就是本地玩家的 {@code Inventory} —— 覆盖背包 / 快捷栏 / 护甲 / 副手，生存与创造背包
	 *     都成立（创造背包的玩家行也是 {@code new Slot(player.getInventory(), …)}）。</li>
	 * </ul>
	 * 箱子、工作台、熔炉、末影箱的栏位由各自的容器支撑，因此一律返回 false。
	 */
	public static boolean isPlayerOwnedSlot(Slot slot) {
		if (slot == null) {
			return false;
		}
		if (slot instanceof top.theillusivec4.curios.common.inventory.CurioSlot) {
			return true;
		}
		var local = Minecraft.getInstance().player;
		return local != null && slot.container == local.getInventory();
	}
}
