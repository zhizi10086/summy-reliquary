package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端游戏刻处理器（1.7.2 从 {@code SummyReliquaryClient.ForgeBus} 里搬出来）。
 *
 * <p><b>为什么搬家</b>：实测本环境里"嵌套在 {@code SummyReliquaryClient} 里的
 * {@code @Mod.EventBusSubscriber(bus = FORGE)}"不会被注册 —— 探针日志证明那个 tick 处理器
 * 一次都没跑过，连带三件事一起失效：
 * <ul>
 *     <li>{@code ReliquaryParticles} 的每 tick 补注册（1.6.7 加的兜底）→ 复仇之魂火焰环的
 *     粒子提供者缺失 → 每颗粒子刷一条 WARN，进世界就卡在"加载地形中"；</li>
 *     <li>R 键（邦邦女仆）与 V 键（启示之光 / 恶魔之焰）的轮询；</li>
 *     <li>JEI 配方可见性的刷新。</li>
 * </ul>
 * 而**顶层**的 {@code @Mod.EventBusSubscriber(bus = FORGE, value = CLIENT)} 是有效的
 * （自检脚本 {@code ForgeDevCheck} 就是这种写法、一直在正常工作），所以这里改成同款顶层写法，
 * 真正的逻辑仍留在 {@code SummyReliquaryClient.ForgeBus#onClientTick} 里。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
		value = Dist.CLIENT)
public final class ReliquaryClientTicks {
	private ReliquaryClientTicks() {
	}

	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		SummyReliquaryClient.ForgeBus.onClientTick(event);
	}
}
