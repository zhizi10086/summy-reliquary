package com.summy.reliquary.client;

/**
 * 通用代码 → 客户端渲染的中转。
 *
 * <p>为什么需要它：通用类（网络包等）只要在字节码里直接引用客户端类，专用服务端加载/校验该类时
 * 就会被 Forge 的 dist 校验判为非法而崩溃（我们此前正好踩过这个坑）。所以通用代码只调用这里定义的
 * 函数式接口，真正的客户端实现由客户端初始化阶段（{@code SummyReliquaryClient}）注册进来。
 *
 * <p>本类本身**不引用任何客户端类型**，在服务端加载是安全的。
 */
public final class ClientRenderHooks {
	/** 光柱渲染回调 */
	public interface BeamSink {
		void spawn(double x, double y, double z, float dirX, float dirY, float dirZ,
				float length, float radius, int durationTicks, int kind);
	}

	/** 默认什么都不做；客户端初始化时会被替换为真正的渲染器 */
	public static BeamSink beamSink = (x, y, z, dirX, dirY, dirZ, length, radius, durationTicks, kind) -> {
	};

	/**
	 * 「播放不死图腾动画（贴图换成五芒星）」的回调（1.5.9）。
	 *
	 * <p>契约签署成功时由 {@code DemonDealMessage} 触发；默认空操作，客户端初始化时替换为
	 * {@code Minecraft.getInstance().gameRenderer.displayItemActivation(new ItemStack(五芒星))}。
	 */
	public static Runnable itemActivationSink = () -> {
	};

	/**
	 * 「播放不死图腾动画（贴图换成创世纪）」的回调（1.7.2）。
	 *
	 * <p>创世纪真正生效之后由 {@code GenesisUsedMessage} 触发；默认空操作，客户端初始化时替换为
	 * {@code Minecraft.getInstance().gameRenderer.displayItemActivation(new ItemStack(创世纪))}。
	 */
	public static Runnable genesisActivationSink = () -> {
	};

	private ClientRenderHooks() {
	}
}
