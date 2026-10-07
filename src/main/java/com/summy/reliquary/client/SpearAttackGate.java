package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.item.SpearItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 长矛的「没冷却完不能出手」门禁（1.8.4）。
 *
 * <p>只做一件事：主手拿着两把长矛之一、攻击强度又没恢复到位时**吞掉左键** ——
 * 不出手、不挥臂，也不重置攻击强度，因此连点不会推迟下一次真攻击。
 *
 * <p><b>瞄准方块一律放行</b>：Forge 在 {@code continueAttack}（挖方块）路径上也会 post 同一个事件，
 * 不分流的话"冷却未满时用手持矛挖方块"会被连带取消、直接挖不动。
 *
 * <p>本类**必须是顶层类**并带 {@code @Mod.EventBusSubscriber} —— 嵌套类的 FORGE 事件
 * 在本工程实测不会被注册（见 {@code ReliquaryClientTicks} 的注释）。
 *
 * <p><b>历史</b>：1.8.4 曾在这里实现过第一人称「左键戳刺」动画（接管
 * {@code applyForgeHandTransform}），因观感调不到位已**整体移除**，只保留本门禁；
 * 重启该动画的思路与踩过的坑记在 {@code docs/SummyReliquary-待办与借鉴.md}。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
		value = Dist.CLIENT)
public final class SpearAttackGate {
	/** 就绪阈值：攻击强度恢复到这里就放行（原版 1.0 才算满，这里放宽一档避免"差一点点打不出去"） */
	private static final float READY_SCALE = 0.9F;

	private SpearAttackGate() {
	}

	/** 冷却未满时吞掉左键（瞄准方块时放行） */
	@SubscribeEvent
	public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
		if (!event.isAttack()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || !isSpearInMainHand(player) || isChargingSpear(player)) {
			return;
		}
		// 挖掘：放行（不干预破坏方块）
		if (client.hitResult instanceof BlockHitResult) {
			return;
		}
		if (player.getAttackStrengthScale(0.5F) <= READY_SCALE) {
			event.setCanceled(true);
			event.setSwingHand(false);
		}
	}

	/** 主手是不是两把矛之一 */
	private static boolean isSpearInMainHand(LocalPlayer player) {
		return player.getMainHandItem().getItem() instanceof SpearItem;
	}

	/** 是否正在举矛蓄力（投掷前摇）—— 这种时候交还原版 */
	private static boolean isChargingSpear(LocalPlayer player) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof SpearItem;
	}
}
