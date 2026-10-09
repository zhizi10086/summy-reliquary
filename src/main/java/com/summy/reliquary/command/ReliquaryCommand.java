package com.summy.reliquary.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.RevelationTracker;
import com.summy.reliquary.sin.Sin;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 命令入口（1.4.3 起做了权限分层）：
 *
 * <ul>
 *     <li><b>玩家可用</b>（只要求执行者是玩家）：{@code sin list}、{@code sin <罪> query}、
 *     {@code sin refresh}、{@code angel query}、{@code angel refresh}；</li>
 *     <li><b>仅 OP（权限等级 2）</b>：{@code sin <罪> <on|off|redeem|unredeem>}、
 *     {@code sin all <on|off|redeem|unredeem>}（调试用批量）、{@code slots}、
 *     {@code angel <grant|revoke>}、{@code dragon <query|reset>}。</li>
 * </ul>
 *
 * <p>之所以把改状态的入口收归 OP：天使标记与末影龙裁决都是进度门槛，任何玩家都能自己发放会直接绕过玩法。
 */
public final class ReliquaryCommand {
	/** 玩家可用的节点：只要求执行者是玩家（控制台请走 OP 分支） */
	private static final Predicate<CommandSourceStack> PLAYER_ONLY =
			source -> source.getEntity() instanceof Player;
	/** 管理类节点：权限等级 2 */
	private static final Predicate<CommandSourceStack> OP_ONLY =
			source -> source.hasPermission(2);

	/** 七罪 id 的补全 */
	private static final SuggestionProvider<CommandSourceStack> SIN_SUGGESTIONS =
			(context, builder) -> SharedSuggestionProvider.suggest(
					Arrays.stream(Sin.values()).map(Sin::id).toList(), builder);

	private ReliquaryCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("summyreliquary")
				.then(Commands.literal("slots").requires(OP_ONLY).executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					collect(player).forEach(line ->
							context.getSource().sendSuccess(() -> Component.literal(line), false));
					return 1;
				}))
				// 五芒星（1.5.8）：查询状态 / 补发 / 清掉发放标记（仅 OP；标记清掉后每秒兜底会自动补发）
				.then(Commands.literal("pentagram").requires(OP_ONLY)
						.then(Commands.literal("query")
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> pentagram(context, "query"))))
						.then(Commands.literal("grant")
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> pentagram(context, "grant"))))
						.then(Commands.literal("reset")
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> pentagram(context, "reset")))))
				// 恶魔交易（1.5.9）：查询 / 直接发放 / 撤销（仅 OP）
				.then(Commands.literal("demon").requires(OP_ONLY)
						.then(Commands.literal("query")
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> demon(context, "query"))))
						.then(Commands.literal("grant")
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> demon(context, "grant"))))
						.then(Commands.literal("revoke")
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> demon(context, "revoke")))))
				.then(Commands.literal("sin").requires(PLAYER_ONLY)
						// 玩家：列出七罪状态
						.then(Commands.literal("list").executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							for (Sin sin : Sin.values()) {
								post(context, sinStateLine(player, sin));
							}
							return 1;
						}))
						// 玩家：重新把状态推给客户端并重算属性
						.then(Commands.literal("refresh").executes(ReliquaryCommand::refresh))
						// OP：一次性把七罪设成同一状态（调试用）
						.then(Commands.literal("all").requires(OP_ONLY)
								.then(Commands.literal("on").executes(context ->
										setAll(context, SinManager.SinState.ACTIVATED)))
								.then(Commands.literal("off").executes(context ->
										setAll(context, SinManager.SinState.UNACTIVATED)))
								.then(Commands.literal("redeem").executes(context ->
										setAll(context, SinManager.SinState.REDEEMED)))
								.then(Commands.literal("unredeem").executes(context ->
										setAll(context, SinManager.SinState.ACTIVATED))))
						.then(Commands.argument("sin", StringArgumentType.word())
								.suggests(SIN_SUGGESTIONS)
								// 玩家：查询单个罪
								.then(Commands.literal("query").executes(context -> {
									ServerPlayer player = context.getSource().getPlayer();
									if (player == null) {
										return playerOnly(context);
									}
									Sin sin = resolveSin(context);
									if (sin == null) {
										return 0;
									}
									post(context, sinStateLine(player, sin));
									return 1;
								}))
								// 以下四项会改状态：仅 OP
								.then(Commands.literal("on").requires(OP_ONLY)
										.executes(context -> setState(context, SinManager.SinState.ACTIVATED)))
								.then(Commands.literal("off").requires(OP_ONLY)
										.executes(context -> setState(context, SinManager.SinState.UNACTIVATED)))
								.then(Commands.literal("redeem").requires(OP_ONLY)
										.executes(context -> redeem(context, true)))
								.then(Commands.literal("unredeem").requires(OP_ONLY)
										.executes(context -> redeem(context, false)))))
				.then(Commands.literal("angel").requires(PLAYER_ONLY)
						// 玩家：查询
						.then(Commands.literal("query").executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								return playerOnly(context);
							}
							post(context, Component.translatable("message.summy-reliquary.angel.query",
									com.summy.reliquary.effect.PlayerFlags.hasAngel(player)
											? Component.translatable("message.summy-reliquary.angel.yes").getString()
											: Component.translatable("message.summy-reliquary.angel.no").getString())
									.getString());
							return 1;
						}))
						// 玩家：重新同步（含属性与显示重算）
						.then(Commands.literal("refresh").executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								return playerOnly(context);
							}
							applyChanges(player);
							post(context, Component.translatable(
									"message.summy-reliquary.angel.refreshed").getString());
							return 1;
						}))
						// OP：发放 / 撤销
						.then(Commands.literal("grant").requires(OP_ONLY)
								.executes(context -> setAngel(context, true)))
						.then(Commands.literal("revoke").requires(OP_ONLY)
								.executes(context -> setAngel(context, false))))
				// 创世纪：confirm / cancel 由**玩家自己**点聊天按钮触发（权限 0），reset 仍是 OP
				.then(Commands.literal("genesis")
						.then(Commands.literal("confirm").requires(PLAYER_ONLY).executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								return playerOnly(context);
							}
							if (!com.summy.reliquary.item.GenesisItem.confirm(player)) {
								player.sendSystemMessage(Component.translatable(
										"message.summy-reliquary.genesis.confirm.none"));
							}
							return 1;
						}))
						.then(Commands.literal("cancel").requires(PLAYER_ONLY).executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								return playerOnly(context);
							}
							if (com.summy.reliquary.item.GenesisItem.cancel(player)) {
								player.sendSystemMessage(Component.translatable(
										"message.summy-reliquary.genesis.confirm.cancelled"));
							}
							return 1;
						}))
						.then(Commands.literal("reset").requires(OP_ONLY)
								.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> {
											ServerPlayer target = net.minecraft.commands.arguments.EntityArgument
													.getPlayer(context, "player");
											com.summy.reliquary.item.GenesisItem.resetUsed(target);
											com.summy.reliquary.item.ActOfContritionItem.resetUsed(target);
											com.summy.reliquary.item.ActOfContritionItem.resetNotified(target);
											com.summy.reliquary.item.GenesisItem.clearPending(target);
											// 1.5.9：恶魔交易的五个键也一并清零（回到最初）
											com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(target);
											// 1.6.0：契约 / 邪恶度的全部键也清零，并收回契约栏位
											com.summy.reliquary.effect.PlayerFlags.resetDemonPact(target);
											com.summy.reliquary.effect.DemonPact.revoke(target);
											// 1.6.6：初始饰品（七罪之源 + 光环）的"只发一次"标记也清掉 —— 重置＝回到最初，可重新发放
											com.summy.reliquary.effect.StarterKit.resetGranted(target);
											post(context, Component.translatable(
													"message.summy-reliquary.genesis.reset").getString());
											return 1;
										}))))
				.then(Commands.literal("salvation").requires(OP_ONLY)
						.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
								.then(Commands.literal("on").executes(context -> setSalvation(context, true)))
								.then(Commands.literal("off").executes(context -> setSalvation(context, false)))
								.then(Commands.literal("query").executes(context -> {
									ServerPlayer target = net.minecraft.commands.arguments.EntityArgument
											.getPlayer(context, "player");
									post(context, Component.translatable("message.summy-reliquary.salvation.query",
											target.getName().getString(),
											Component.translatable(com.summy.reliquary.effect.SalvationDomain
													.isTargetable(target)
													? "message.summy-reliquary.angel.yes"
													: "message.summy-reliquary.angel.no").getString()).getString());
									return 1;
								}))))
				// 1.8.5 补修：把被"死亡拦截并入吸收值"污染的吸收值夹回本模组能解释的部分（手动清一次）
				.then(Commands.literal("shield").requires(OP_ONLY)
						.then(Commands.literal("clamp")
								.executes(context -> clampShield(context,
										context.getSource().getPlayerOrException()))
								.then(Commands.argument("player",
												net.minecraft.commands.arguments.EntityArgument.player())
										.executes(context -> clampShield(context,
												net.minecraft.commands.arguments.EntityArgument
														.getPlayer(context, "player"))))))
				.then(Commands.literal("dragon").requires(OP_ONLY)
						.then(Commands.literal("query").executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								return playerOnly(context);
							}
							post(context, Component.translatable("message.summy-reliquary.dragon.query",
									switch (com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)) {
										case com.summy.reliquary.advancement.SinChallenges.VERDICT_SINLESS ->
												Component.translatable(
														"message.summy-reliquary.dragon.sinless").getString();
										case com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS ->
												Component.translatable(
														"message.summy-reliquary.dragon.flawless").getString();
										default -> Component.translatable(
												"message.summy-reliquary.dragon.none").getString();
									}).getString());
							return 1;
						}))
						.then(Commands.literal("reset").executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								return playerOnly(context);
							}
							com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
							post(context, Component.translatable(
									"message.summy-reliquary.dragon.reset").getString());
							return 1;
						}))));
	}

	/** OP：把玩家加入 / 移出「救恩可审判」名单 */
	/** OP：五芒星的查询 / 补发 / 清除发放标记（1.5.8） */
	private static int pentagram(CommandContext<CommandSourceStack> context, String action)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
		boolean done = com.summy.reliquary.advancement.SinChallenges.advancementDone(
				target, com.summy.reliquary.effect.Pentagram.ADVANCEMENT);
		switch (action) {
			case "grant" -> {
				com.summy.reliquary.effect.Pentagram.grant(target);
				post(context, Component.translatable("message.summy-reliquary.pentagram.granted",
						target.getName().getString()).getString());
			}
			case "reset" -> {
				com.summy.reliquary.effect.Pentagram.resetGranted(target);
				post(context, Component.translatable("message.summy-reliquary.pentagram.reset",
						target.getName().getString()).getString());
			}
			default -> post(context, Component.translatable("message.summy-reliquary.pentagram.query",
					target.getName().getString(),
					yesNo(done),
					yesNo(com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(target)),
					String.valueOf(com.summy.reliquary.effect.Pentagram.heldCount(target))).getString());
		}
		return 1;
	}

	/** 「有 / 无」的统一样式（与天使查询一致） */
	private static String yesNo(boolean value) {
		return Component.translatable(value
				? "message.summy-reliquary.angel.yes"
				: "message.summy-reliquary.angel.no").getString();
	}

	/** OP：恶魔交易的查询 / 发放 / 撤销（1.5.9） */
	private static int demon(CommandContext<CommandSourceStack> context, String action)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
		switch (action) {
			case "grant" -> {
				// 直接发放：同时置"已签约"，避免玩家还能再签一次
				com.summy.reliquary.effect.PlayerFlags.setDemon(target, true);
				com.summy.reliquary.effect.PlayerFlags.setDemonSealed(target, true);
				applyChanges(target);
				post(context, Component.translatable("message.summy-reliquary.demon.granted",
						target.getName().getString()).getString());
			}
			case "revoke" -> {
				com.summy.reliquary.effect.PlayerFlags.setDemon(target, false);
				applyChanges(target);
				post(context, Component.translatable("message.summy-reliquary.demon.revoked",
						target.getName().getString()).getString());
			}
			default -> post(context, Component.translatable("message.summy-reliquary.demon.query",
					target.getName().getString(),
					yesNo(com.summy.reliquary.effect.PlayerFlags.isDemon(target)),
					yesNo(com.summy.reliquary.effect.PlayerFlags.isDemonSealed(target)),
					yesNo(com.summy.reliquary.effect.PlayerFlags.isDemonInvited(target)),
					yesNo(com.summy.reliquary.effect.PlayerFlags.hasAngel(target))).getString());
		}
		return 1;
	}

	/** OP：把玩家加入 / 移出「救恩可审判」名单 */
	private static int setSalvation(CommandContext<CommandSourceStack> context, boolean value)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
		com.summy.reliquary.effect.SalvationDomain.setTargetable(target, value);
		// 回显**重新读取**的真实值：写入路径出问题（例如根标签没挂回）时能立刻看出来
		boolean actual = com.summy.reliquary.effect.SalvationDomain.isTargetable(target);
		post(context, Component.translatable("message.summy-reliquary.salvation.set",
				target.getName().getString(),
				Component.translatable(actual ? "message.summy-reliquary.angel.yes"
						: "message.summy-reliquary.angel.no").getString()).getString());
		return 1;
	}

	/** 玩家：把服务端七罪状态与标记重新推送给客户端并重算属性 */
	private static int refresh(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return playerOnly(context);
		}
		applyChanges(player);
		post(context, Component.translatable("message.summy-reliquary.sin.refreshed").getString());
		return 1;
	}

	/** OP：一次把七罪全部设为同一状态（调试用；全部设为 on 会顺带触发「罪无可赦」判据） */
	private static int setAll(CommandContext<CommandSourceStack> context, SinManager.SinState state) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return playerOnly(context);
		}
		for (Sin sin : Sin.values()) {
			if (state == SinManager.SinState.REDEEMED) {
				// 1.8.0：批量赎罪同样清零每一项的计数
				SinManager.redeem(player, sin);
			} else {
				SinManager.setState(player, sin, state);
			}
		}
		applyChanges(player);
		post(context, Component.translatable("message.summy-reliquary.sin.all.done",
				Component.translatable(stateKey(state)).getString()).getString());
		return 1;
	}

	/** 发放 / 撤销天使标记（标记存在本模组 NBT 里，没有命令就无法查看与调试；仅 OP） */
	private static int setAngel(CommandContext<CommandSourceStack> context, boolean value) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return playerOnly(context);
		}
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, value);
		applyChanges(player);
		post(context, Component.translatable(value
				? "message.summy-reliquary.angel.granted"
				: "message.summy-reliquary.angel.revoked").getString());
		return 1;
	}

	/** 直接设为已激活 / 未激活（off 也会清掉已赎罪） */
	private static int setState(CommandContext<CommandSourceStack> context, SinManager.SinState state) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return playerOnly(context);
		}
		Sin sin = resolveSin(context);
		if (sin == null) {
			return 0;
		}
		SinManager.setState(player, sin, state);
		applyChanges(player);
		post(context, sinStateLine(player, sin));
		return 1;
	}

	/** redeem = 已激活 → 已赎罪；unredeem = 已赎罪 → 已激活 */
	private static int redeem(CommandContext<CommandSourceStack> context, boolean redeem) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return playerOnly(context);
		}
		Sin sin = resolveSin(context);
		if (sin == null) {
			return 0;
		}
		SinManager.SinState current = SinManager.state(player, sin);
		if (redeem && current != SinManager.SinState.ACTIVATED) {
			context.getSource().sendFailure(
					Component.translatable("message.summy-reliquary.sin.redeem.need_activated"));
			return 0;
		}
		if (!redeem && current != SinManager.SinState.REDEEMED) {
			context.getSource().sendFailure(
					Component.translatable("message.summy-reliquary.sin.unredeem.need_redeemed"));
			return 0;
		}

		if (redeem) {
			// 1.8.0：赎罪走统一入口（置为已赎罪 + 清零该罪计数）
			SinManager.redeem(player, sin);
		} else {
			SinManager.setState(player, sin, SinManager.SinState.ACTIVATED);
			applyChanges(player);
		}
		if (redeem) {
			// 赎罪成功的提示：整行金色
			Component line = Component.translatable("message.summy-reliquary.sin.redeem.done",
					Component.translatable(sin.nameKey()).withStyle(net.minecraft.ChatFormatting.GOLD))
					.withStyle(net.minecraft.ChatFormatting.GOLD);
			context.getSource().sendSuccess(() -> line, false);
		} else {
			post(context, sinStateLine(player, sin));
		}
		return 1;
	}

	/** 状态变化后立刻重算属性并同步给客户端 */
	private static void applyChanges(ServerPlayer player) {
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
	}

	private static int playerOnly(CommandContext<CommandSourceStack> context) {
		context.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
		return 0;
	}

	private static Sin resolveSin(CommandContext<CommandSourceStack> context) {
		String id = StringArgumentType.getString(context, "sin");
		Sin sin = Sin.byId(id);
		if (sin == null) {
			context.getSource().sendFailure(
					Component.translatable("message.summy-reliquary.sin.unknown", id));
		}
		return sin;
	}

	/** 状态对应的语言键 */
	private static String stateKey(SinManager.SinState state) {
		return switch (state) {
			case ACTIVATED -> "message.summy-reliquary.sin.state.on";
			case REDEEMED -> "message.summy-reliquary.sin.state.redeemed";
			default -> "message.summy-reliquary.sin.state.off";
		};
	}

	private static String sinStateLine(ServerPlayer player, Sin sin) {
		String line = Component.translatable("message.summy-reliquary.sin.set",
				Component.translatable(sin.nameKey()).getString(),
				Component.translatable(stateKey(SinManager.state(player, sin))).getString()).getString();
		return line + progressText(player, sin);
	}

	/** 该罪的触发进度（诊断用） */
	private static String progressText(ServerPlayer player, Sin sin) {
		return switch (sin) {
			case PRIDE -> String.format("（击杀中立/友善 %d/%d）",
					com.summy.reliquary.sin.SinProgress.get(player, com.summy.reliquary.sin.SinProgress.PRIDE_KILLS),
					com.summy.reliquary.config.ReliquaryConfig.prideKillRequired());
			case ENVY -> String.format("（观察到满足条件的目标 %d/1）",
					com.summy.reliquary.sin.SinProgress.get(player, com.summy.reliquary.sin.SinProgress.ENVY_SEEN));
			case WRATH -> String.format("（累计击杀 %d/%d）",
					com.summy.reliquary.sin.SinProgress.get(player, com.summy.reliquary.sin.SinProgress.WRATH_KILLS),
					com.summy.reliquary.config.ReliquaryConfig.wrathKillRequired());
			case SLOTH -> String.format("（早睡 %d/%d）",
					com.summy.reliquary.sin.SinProgress.get(player, com.summy.reliquary.sin.SinProgress.SLOTH_SLEEPS),
					com.summy.reliquary.config.ReliquaryConfig.slothSleepRequired());
			case GREED -> String.format("（钻石峰值 %d/%d）",
					com.summy.reliquary.sin.SinProgress.get(player,
							com.summy.reliquary.sin.SinProgress.GREED_PEAK_DIAMONDS),
					com.summy.reliquary.config.ReliquaryConfig.greedDiamondThreshold());
			case GLUTTONY -> String.format("（高饱和进食 %d/%d）",
					com.summy.reliquary.sin.SinProgress.get(player,
							com.summy.reliquary.sin.SinProgress.GLUTTONY_MEALS),
					com.summy.reliquary.config.ReliquaryConfig.gluttonyMealRequired());
			case LUST -> String.format("（繁殖 %d/%d）",
					com.summy.reliquary.sin.SinProgress.get(player, com.summy.reliquary.sin.SinProgress.LUST_BREEDS),
					com.summy.reliquary.config.ReliquaryConfig.lustBreedRequired());
		};
	}

	private static void post(CommandContext<CommandSourceStack> context, String line) {
		context.getSource().sendSuccess(() -> Component.literal(line), false);
	}

	/**
	 * OP：把目标玩家的吸收值夹回「本模组能解释的部分」（1.8.5 补修）。
	 *
	 * <p>只处理"死亡拦截把整击金额并入吸收值"留下的残留：软读 Enchantment Reforged 的生命护盾
	 * 属性（{@code enchantment_reforged:life_shield}），取到就设为它的基础值、取不到就清零。
	 * **金苹果等其它来源的吸收值会被一并清掉** —— 这是本命令的明确语义，且只由 OP 手动执行，
	 * 不做任何自动夹取。
	 */
	private static int clampShield(CommandContext<CommandSourceStack> context, ServerPlayer target) {
		float before = target.getAbsorptionAmount();
		double external = com.summy.reliquary.util.ExternalShields.enchantmentReforgedLifeShield(target);
		float after = (float) Math.max(0.0D, external);
		target.setAbsorptionAmount(after);
		post(context, "已把「" + target.getName().getString() + "」的吸收值从 " + shieldText(before)
				+ " 夹回 " + shieldText(after)
				+ (external > 0.0D
						? "（保留检测到的 Enchantment Reforged 生命护盾 " + shieldText(after) + "）"
						: "（未检测到 Enchantment Reforged 生命护盾，直接清零）"));
		return 1;
	}

	/** 吸收值的显示文本（统一一位小数，避免科学计数法糊在聊天框里） */
	private static String shieldText(float value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private static List<String> collect(ServerPlayer player) {
		List<String> lines = new ArrayList<>();
		lines.add("=== Summy Reliquary 槽位诊断（Curios） ===");

		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			lines.add("未找到 Curios 玩家数据");
			return lines;
		}

		Map<String, ICurioStacksHandler> curios = handler.getCurios();
		if (curios.isEmpty()) {
			lines.add("当前没有任何 Curios 栏位");
			return lines;
		}

		for (Map.Entry<String, ICurioStacksHandler> entry : curios.entrySet()) {
			String identifier = entry.getKey();
			ICurioStacksHandler stacks = entry.getValue();
			String displayName = Component.translatable(ReliquarySlots.nameKey(identifier)).getString();

			for (int index = 0; index < stacks.getSlots(); index++) {
				ItemStack stack = stacks.getStacks().getStackInSlot(index);
				lines.add(String.format("%s#%d（%s）| 本模组栏位=%s | 内容=%s",
						identifier, index, displayName,
						ReliquarySlots.isCustom(identifier) ? "是" : "否",
						stack.isEmpty() ? "空" : stack.getItem() + " x" + stack.getCount()));
			}
		}
		return lines;
	}
}
