package com.summy.reliquary.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.integration.RecipeVisibility;
import com.summy.reliquary.net.ReliquaryNetworking;
import com.summy.reliquary.sin.Sin;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;

/**
 * 客户端入口：注册按键 R、发送切换请求、把光环渲染器挂到 Curios 上。
 *
 * <p>两个内部类分别订阅模组事件总线（注册阶段）与 Forge 事件总线（游戏运行阶段）。
 */
public final class SummyReliquaryClient {
	/** 切换邦邦女仆状态的按键：默认 R，玩家可在原版「控制」里改键 */
	public static final KeyMapping TOGGLE_MAID_KEY = new KeyMapping(
			"key.summy-reliquary.toggle_maid",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			"key.categories.summy-reliquary");

	/** 启示之光：长按蓄力，默认 V，玩家可在原版「控制」里改键 */
	public static final KeyMapping REVELATION_BEAM_KEY = new KeyMapping(
			"key.summy-reliquary.revelation_beam",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_V,
			"key.categories.summy-reliquary");

	/** 神性回溯 / 亚巴顿主动恶魔形态：默认 X，玩家可在原版「控制」里改键 */
	public static final KeyMapping DIVINE_ACTION_KEY = new KeyMapping(
			"key.summy-reliquary.divine_action",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_X,
			"key.categories.summy-reliquary");

	/** 当前蓄力 tick 数 */
	private static int beamCharge;
	/** 本地冷却剩余 tick（服务端仍会再校验一次） */
	private static int beamCooldown;
	/** 上一次发射的光束种类（冷却提示文案用；1.6.4） */
	private static com.summy.reliquary.effect.RevelationBeam.BeamKind lastBeamKind =
			com.summy.reliquary.effect.RevelationBeam.BeamKind.HOLY;
	/** 本次按住是否已经发射过（避免按住时连续发射） */
	private static boolean beamFiredWhileHeld;
	/** 上一次通知给服务端的蓄力状态（只在变化时发包） */
	private static boolean chargeReported;

	/** X 技能（神性回溯 / 亚巴顿恶魔形态）当前蓄力 tick 数 */
	private static int divineCharge;
	/** 本次按住 X 是否已经触发过（避免按住时连发） */
	private static boolean divineFiredWhileHeld;

	/** 当前蓄力进度（0~1；未蓄力为 0），供 FOV 收缩使用 */
	public static double chargeProgress() {
		int needed = chargeTicks();
		return needed <= 0 ? 0.0D : Math.min(1.0D, beamCharge / (double) needed);
	}

	/** X 技能蓄力进度（0~1；未蓄力为 0），供 FOV 收缩使用 */
	public static double divineChargeProgress() {
		int needed = ReliquaryConfig.divineActionChargeTicks();
		return needed <= 0 ? 0.0D : Math.min(1.0D, divineCharge / (double) needed);
	}

	/** 当前需要的蓄力 tick：按"现在 V 键会放哪一种光束"取（硫磺火 1.5 秒 / 亚巴顿 1.0 秒 / 启示之光按神性覆盖） */
	private static int chargeTicks() {
		var local = Minecraft.getInstance().player;
		var kind = local == null ? null : com.summy.reliquary.effect.RevelationBeam.kindFor(local);
		return com.summy.reliquary.effect.RevelationBeam.chargeTicks(
				local,
				kind == null ? com.summy.reliquary.effect.RevelationBeam.BeamKind.HOLY : kind);
	}

	private SummyReliquaryClient() {
	}

	/** 模组加载阶段：注册按键与渲染器 */
	@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
	public static final class ModBus {
		private ModBus() {
		}

		@SubscribeEvent
		public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
			event.register(TOGGLE_MAID_KEY);
			event.register(REVELATION_BEAM_KEY);
			event.register(DIVINE_ACTION_KEY);
		}

		@SubscribeEvent
		public static void onClientSetup(FMLClientSetupEvent event) {
			// 1.7.9：举矛蓄力的判定谓词 —— 必须在**第一次模型烘焙之前**注册，否则模型 JSON 里的
			// overrides 会因为"找不到谓词"被丢弃（模型烘焙发生在客户端资源重载时）。
			registerUsingProperty();
			// 装备后按 Curios 的渲染回调绘制头顶光环
			CuriosRendererRegistry.register(SummyReliquary.BANG_BANG_HALO.get(), BangBangHaloRenderer::new);
			// 启示之光的光柱渲染器（通用代码只通过 ClientRenderHooks 回调）
			ClientRenderHooks.beamSink = RevelationBeamRenderer::spawn;
			// 恶魔交易签约：播一次"不死图腾动画"，但贴图换成五芒星（1.5.9）
			ClientRenderHooks.itemActivationSink = () -> Minecraft.getInstance().gameRenderer
					.displayItemActivation(new net.minecraft.world.item.ItemStack(
							SummyReliquary.PENTAGRAM.get()));
			// 1.7.2：创世纪生效 → 同样的图腾动画，贴图换成创世纪
			ClientRenderHooks.genesisActivationSink = () -> Minecraft.getInstance().gameRenderer
					.displayItemActivation(new net.minecraft.world.item.ItemStack(
							SummyReliquary.GENESIS.get()));
		}

		/** 黑心 HUD（1.6.0）+ 魂心 HUD（1.6.1）：都挂在原版血条之上，客户端专用、无需 mixin */
		@SubscribeEvent
		public static void onRegisterGuiOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
			event.registerAbove(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.PLAYER_HEALTH.id(),
					"demon_black_hearts", new DemonBlackHeartOverlay());
			// 魂心：把属于我们的那几颗黄心重新画成蓝心（不隐藏原版黄心）
			event.registerAbove(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.PLAYER_HEALTH.id(),
					"soul_hearts_hud", new SoulHeartOverlay());
		}

		/** 1.7.8：幻影矛的渲染器（投掷出去的矛用物品模型画） */
		@SubscribeEvent
		public static void onRegisterRenderers(
				net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
			event.registerEntityRenderer(SummyReliquary.THROWN_SPEAR.get(), ThrownSpearRenderer::new);
			// 1.7.10：金刀片投掷物的渲染器（同一套朝向链，但不需要平面内补偿）
			event.registerEntityRenderer(SummyReliquary.THROWN_RAZOR.get(), ThrownRazorRenderer::new);
		}
	}

	/**
	 * 游戏运行阶段：轮询按键并发包。
	 *
	 * <p>1.7.2 起不再依赖本类的注解自动注册 —— 实测嵌套类的 {@code Bus.FORGE} 注解在本环境里
	 * 没有生效（探针日志证明 tick 处理器一次都没跑），改由顶层的 {@link ReliquaryClientTicks}
	 * 转发进来（同款顶层写法见自检脚本 {@code ForgeDevCheck}）。
	 */
	public static final class ForgeBus {
		private ForgeBus() {
		}

		/** 真正的客户端 tick 逻辑（由 {@link ReliquaryClientTicks} 调用） */
		static void onClientTick(TickEvent.ClientTickEvent event) {
			if (event.phase != TickEvent.Phase.END) {
				return;
			}
			// 1.7.3：短寿命火焰粒子的提供者 —— 自己造 SpriteSet + 纯 providers.put 注册，
			// 不依赖 Forge 的粒子事件（Kilt 上那个事件不会 post），每 tick 幂等调用。
			ReliquaryParticles.register();
			// 1.7.9：蓄力谓词的兜底注册（Kilt 上模组总线事件可能不触发，这里每 tick 幂等补一次）
			registerUsingProperty();
			Minecraft client = Minecraft.getInstance();
			// 用 while 把所有排队的按下事件都消费掉，避免连按时输入堆积
			while (TOGGLE_MAID_KEY.consumeClick()) {
				if (client.player != null && client.getConnection() != null) {
					ReliquaryNetworking.sendToggleMaid();
				}
			}
			// 1.8.2：X 键 —— 神性回溯 / 亚巴顿主动恶魔形态（长按蓄力后发包，服务端再校验装备）
			tickDivineAction(client);

			// JEI：按玩家状态隐藏 / 放开「赎罪」与天使线（三件套 / 圣光 / 斗篷 / 神性 / 救恩 / 圣心）与仪式法袍的配方。
			// 桥内部只在目标状态变化或刚换世界时才真正通知 JEI，没装 JEI 时这里是空操作。
			RecipeVisibility.tick(client.level, wantedRecipeVisibility());

			tickRevelationBeam(client);
		}

		/** 目标可见性：赎罪配方看「七罪是否全部已赎罪」，天使线配方看「是否有天使标记」，法袍看「是否已签约」 */
		private static java.util.Map<net.minecraft.resources.ResourceLocation, Boolean> wantedRecipeVisibility() {
			boolean angel = ReliquaryClientState.isAngel();
			boolean signed = ReliquaryClientState.isDemonSealed();
			java.util.Map<net.minecraft.resources.ResourceLocation, Boolean> wanted = new java.util.LinkedHashMap<>();
			wanted.put(SummyReliquary.id("redemption"), ReliquaryClientState.allRedeemed(Sin.values().length));
			// 1.7.0：天使线（三件套 / 圣光 / 斗篷 / 神性 / 救恩）与仪式法袍的可见性统一由门禁表给出
			// 1.7.6：献祭匕首的「防丢失配方」另看一个同步位（长时间没匕首才开放）
			wanted.putAll(com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(angel, signed,
					ReliquaryClientState.isDemon(),
					ReliquaryClientState.isDaggerRecoveryOpen(), ReliquaryClientState.isSpearRecoveryOpen()));
			// 邪恶度门禁（1.6.0）：未解锁的配方在 JEI 里也查不到（服务端另有一道拦截）
			int unlocks = ReliquaryClientState.evilUnlocks();
			for (java.util.Map.Entry<net.minecraft.resources.ResourceLocation,
					com.summy.reliquary.effect.EvilUnlock> entry
					: com.summy.reliquary.effect.EvilUnlock.gatedRecipes().entrySet()) {
				wanted.put(entry.getKey(), entry.getValue().unlocked(unlocks));
			}
			return wanted;
		}

		/** 启示之光：长按 V 蓄力，蓄满自动发射；中途松手 = 无事发生、不进 CD */
		private static void tickRevelationBeam(Minecraft client) {
			boolean holding = REVELATION_BEAM_KEY.isDown();

			if (client.player == null || client.level == null || client.screen != null) {
				beamCharge = 0;
				beamFiredWhileHeld = false;
				reportCharge(false);
				return;
			}

			if (beamCooldown > 0) {
				beamCooldown--;
				reportCharge(false);
				if (holding && !beamFiredWhileHeld) {
					beamFiredWhileHeld = true;
					client.player.displayClientMessage(Component.translatable(
							lastBeamKind.messageKey("cooldown"),
							Math.max(1, (beamCooldown + 19) / 20)), true);
				} else if (!holding) {
					beamFiredWhileHeld = false;
				}
				return;
			}

			if (!holding) {
				beamCharge = 0;
				beamFiredWhileHeld = false;
				reportCharge(false);
				return;
			}
			// 1.6.4：V 键门槛与服务端共用同一判定（硫磺火的恶魔之焰优先，其次启示之光）
			var kind = com.summy.reliquary.effect.RevelationBeam.kindFor(client.player);
			if (kind == null) {
				beamCharge = 0;
				reportCharge(false);
				return;
			}
			if (beamFiredWhileHeld) {
				reportCharge(false);
				return;
			}

			int needed = chargeTicks();
			beamCharge++;
			if (beamCharge >= needed) {
				ReliquaryNetworking.sendFireRevelation();
				beamCharge = 0;
				beamFiredWhileHeld = true;
				lastBeamKind = kind;
				beamCooldown = com.summy.reliquary.effect.RevelationBeam.cooldownTicks(client.player, kind);
				reportCharge(false);
			} else if (beamCharge % 5 == 0) {
				int percent = (int) Math.round(beamCharge * 100.0D / needed);
				client.player.displayClientMessage(Component.translatable(
						kind.messageKey("charging"), percent), true);
				reportCharge(true);
			} else if (beamCharge == 1) {
				reportCharge(true);
			}
		}

		/**
		 * X 技能：长按蓄力 {@code [divine_action] charge_seconds}（默认 1 秒）后才发包。
		 *
		 * <p>松手 / 打开界面 / 离开世界都会清零重来；蓄力期间不输出行动栏文字，
		 * 只由 {@link #onComputeFov} 做轻微 FOV 收缩作为反馈。
		 */
		private static void tickDivineAction(Minecraft client) {
			boolean holding = DIVINE_ACTION_KEY.isDown();
			if (client.player == null || client.level == null || client.screen != null) {
				divineCharge = 0;
				divineFiredWhileHeld = false;
				return;
			}
			if (!holding) {
				divineCharge = 0;
				divineFiredWhileHeld = false;
				return;
			}
			if (divineFiredWhileHeld) {
				return;
			}
			int needed = ReliquaryConfig.divineActionChargeTicks();
			divineCharge++;
			if (divineCharge >= needed) {
				ReliquaryNetworking.sendDivineAction();
				divineCharge = 0;
				divineFiredWhileHeld = true;
			}
		}

		/** 蓄力状态变化时通知服务端（用于附近可见的蓄力粒子） */
		private static void reportCharge(boolean charging) {
			if (chargeReported == charging) {
				return;
			}
			chargeReported = charging;
			ReliquaryNetworking.sendCharge(charging);
		}

		/**
		 * 蓄力时按拉弓方式收缩视野。
		 *
		 * <p><b>1.8.2 修</b>：本方法**必须**由顶层 {@code ReliquaryClientTicks#onComputeFov} 转发 ——
		 * 嵌套类里的 FORGE 事件不会注册，之前写在这里导致三处 FOV 收缩全部失效。
		 */
		public static void onComputeFov(net.minecraftforge.client.event.ViewportEvent.ComputeFov event) {
			// ① 启示之光（V 键）蓄力
			double beam = chargeProgress();
			if (beam > 0.0D) {
				event.setFOV(event.getFOV() * (1.0D - ReliquaryConfig.chargeFovScale() * beam));
			}
			// ② 恶魔交易：长按五芒星签约（1.5.10）—— 不再有文字进度，只靠视野收缩反馈
			// （1.6.2：手持贴图拉伸已按需求移除，只保留镜头缩放）
			float deal = pentagramProgress(Minecraft.getInstance().player);
			if (deal > 0.0F) {
				event.setFOV(event.getFOV() * (1.0D - ReliquaryConfig.demonChargeFovScale() * deal));
			}
			// ③ X 技能蓄力（神性回溯 / 亚巴顿恶魔形态）：同样只做视野收缩
			double divine = divineChargeProgress();
			if (divine > 0.0D) {
				event.setFOV(event.getFOV() * (1.0D - ReliquaryConfig.chargeFovScale() * divine));
			}
		}
	}

	/**
	 * 五芒星签约蓄力进度（0~1）；没在长按五芒星时返回 0。
	 *
	 * <p>1.6.2：手持贴图拉伸已经删掉，但视野收缩仍然需要这个进度，所以逻辑搬到这里。
	 */
	public static float pentagramProgress(net.minecraft.client.player.LocalPlayer player) {
		if (player == null || !player.isUsingItem()) {
			return 0.0F;
		}
		if (!player.getUseItem().is(SummyReliquary.PENTAGRAM.get())) {
			return 0.0F;
		}
		int total = SummyReliquary.PENTAGRAM.get().getUseDuration(player.getUseItem());
		if (total <= 0) {
			return 0.0F;
		}
		int remaining = player.getUseItemRemainingTicks();
		float progress = 1.0F - (float) remaining / (float) total;
		return Math.max(0.0F, Math.min(1.0F, progress));
	}

	// ==================== 举矛蓄力的判定谓词（1.7.9） ====================

	/** 谓词是否已经注册（幂等） */
	private static boolean usingPropertyRegistered;

	/**
	 * 注册全局物品谓词 {@code summy-reliquary:using}：**只看实体状态**。
	 *
	 * <p>1.7.7 那次失败是因为谓词想区分"渲染上下文"（GUI / 手持），而 GUI 渲染同样会把玩家传进来；
	 * 这次只判断"这个实体正在使用、而且用的就是这一件物品"，因此 GUI 与掉落物的实体为 null → 恒 0，
	 * 两把矛的模型 {@code overrides} 只有在**举矛蓄力**时才会切到 {@code *_using} 模型
	 * （它把第一 / 第三人称的 Y 轴旋转取反，于是矛头朝前）。
	 */
	public static void registerUsingProperty() {
		if (usingPropertyRegistered) {
			return;
		}
		usingPropertyRegistered = true;
		net.minecraft.client.renderer.item.ItemProperties.registerGeneric(SummyReliquary.id("using"),
				(stack, level, entity, seed) -> entity != null && entity.isUsingItem()
						&& net.minecraft.world.item.ItemStack.isSameItemSameTags(stack, entity.getUseItem())
								? 1.0F : 0.0F);
	}

	/** 自检用：谓词是否已经注册成功 */
	public static boolean isUsingPropertyRegistered() {
		return net.minecraft.client.renderer.item.ItemProperties.getProperty(
				SummyReliquary.HOLY_SPEAR.get(), SummyReliquary.id("using")) != null;
	}
}
