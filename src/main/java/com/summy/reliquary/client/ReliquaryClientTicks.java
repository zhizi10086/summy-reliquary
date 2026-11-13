package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 客户端游戏刻处理器（1.7.2 从 {@code SummyReliquaryClient.ForgeBus} 里搬出来）。
 *
 * <p><b>为什么搬家</b>：实测本环境里"嵌套在 {@code SummyReliquaryClient} 里的
 * {@code @EventBusSubscriber(bus = FORGE)}"不会被注册 —— 探针日志证明那个 tick 处理器
 * 一次都没跑过，连带三件事一起失效：
 * <ul>
 *     <li>{@code ReliquaryParticles} 的每 tick 补注册（1.6.7 加的兜底）→ 复仇之魂火焰环的
 *     粒子提供者缺失 → 每颗粒子刷一条 WARN，进世界就卡在"加载地形中"；</li>
 *     <li>R 键（邦邦女仆）与 V 键（启示之光 / 恶魔之焰）的轮询；</li>
 *     <li>JEI 配方可见性的刷新。</li>
 * </ul>
 * 而**顶层**的 {@code @EventBusSubscriber(bus = FORGE, value = CLIENT)} 是有效的
 * （自检脚本 {@code ForgeDevCheck} 就是这种写法、一直在正常工作），所以这里改成同款顶层写法，
 * 真正的逻辑仍留在 {@code SummyReliquaryClient.ForgeBus#onClientTick} 里。
 */
@EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = EventBusSubscriber.Bus.GAME,
		value = Dist.CLIENT)
public final class ReliquaryClientTicks {
	private ReliquaryClientTicks() {
	}

	@SubscribeEvent
	public static void onClientTickPre(ClientTickEvent.Pre event) {
		SummyReliquaryClient.ForgeBus.onClientTickStart(net.minecraft.client.Minecraft.getInstance());
	}

	@SubscribeEvent
	public static void onClientTickPost(ClientTickEvent.Post event) {
		ClientBloodDebtState.tick();
		// 部分加载器会在客户端 tick 之后才处理网络包；这里先做一次兜底，
		// 真正稳定的清除点仍在每帧渲染开始前。
		ClientBloodDebtState.suppressMatchingDamageTilt(
				net.minecraft.client.Minecraft.getInstance().player);
		SummyReliquaryClient.ForgeBus.onClientTickEnd(net.minecraft.client.Minecraft.getInstance());
	}

	/**
	 * 渲染帧开始：清掉血债静默还款触发的原版受击镜头。
	 *
	 * <p>1.20.1 的 {@code GameRenderer#bobHurt} 在渲染阶段直接读取
	 * {@code LivingEntity.hurtTime}；在这里归零发生在镜头矩阵生成之前，因此不必新增 Mixin。
	 */
	@SubscribeEvent
	public static void onRenderFramePre(RenderFrameEvent.Pre event) {
		ClientBloodDebtState.suppressMatchingDamageTilt(
				net.minecraft.client.Minecraft.getInstance().player);
	}

	/**
	 * 视野收缩（1.8.2 修）：启示之光 / 五芒星签约 / X 技能蓄力三处的 FOV 反馈。
	 *
	 * <p>以前这个处理器写在 {@code SummyReliquaryClient.ForgeBus} 里 —— 与上面的 tick 同一个坑：
	 * 嵌套类的 FORGE 事件不会被注册，所以三处 FOV 收缩**从来没有生效过**。这里与 tick 一样
	 * 用顶层转发，真正的逻辑仍在 {@code SummyReliquaryClient.ForgeBus#onComputeFov} 里。
	 */
	@SubscribeEvent
	public static void onComputeFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
		SummyReliquaryClient.ForgeBus.onComputeFov(event);
	}

	/** 原始按键事件：誓约 R 键在按下瞬间即可发包，不等待 END tick。 */
	@SubscribeEvent
	public static void onKeyInput(net.neoforged.neoforge.client.event.InputEvent.Key event) {
		SummyReliquaryClient.ForgeBus.onKeyInput(event);
	}
}
