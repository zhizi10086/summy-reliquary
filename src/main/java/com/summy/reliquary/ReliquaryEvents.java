package com.summy.reliquary;

import com.summy.reliquary.command.ReliquaryCommand;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.DeathImmunity;
import com.summy.reliquary.effect.HaloState;
import com.summy.reliquary.effect.RiceHungerLock;
import com.summy.reliquary.effect.RevelationTracker;
import com.summy.reliquary.effect.RevelationAscension;
import com.summy.reliquary.effect.RevelationBeam;
import com.summy.reliquary.effect.RedemptionRecipeGate;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.effect.SinRedemption;
import com.summy.reliquary.effect.SinFragments;
import com.summy.reliquary.effect.SpiritAltarSet;
import com.summy.reliquary.effect.SoulShield;
import com.summy.reliquary.effect.StarterKit;
import com.summy.reliquary.maid.MaidModeManager;
import com.summy.reliquary.advancement.SinChallenges;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.sin.SinEffects;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.event.CurioEquipEvent;
import top.theillusivec4.curios.api.event.CurioUnequipEvent;

/**
 * 服务端 / 通用事件入口（全部走 Forge 事件总线，不依赖任何 Mixin）。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ReliquaryEvents {
	/** 上次同步给客户端的「主世界有个人重生点」值（只在变化时补发包，避免每秒刷屏） */
	private static final java.util.Map<java.util.UUID, Boolean> LAST_OVERWORLD_RESPAWN = new java.util.HashMap<>();
	/** 1.7.9：上次看到的**主手物品**（天使线长矛的攻击距离加成要"切换物品即生效"） */
	private static final java.util.Map<java.util.UUID, net.minecraft.world.item.Item> LAST_MAIN_HAND =
			new java.util.HashMap<>();

	private ReliquaryEvents() {
	}

	/** 每个服务端 tick：维护邦邦女仆状态并锁定白饭佩戴者的饥饿值 */
	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		MinecraftServer server = event.getServer();
		MaidModeManager.tickServer(server);
		// 恶魔交易的台词逐行延迟发送（1.5.9）
		com.summy.reliquary.effect.DelayedChat.tick(server);
		// 天使名单：每秒兜底比对一次（登录 / 登出 / 标记变化时也会立刻重算）
		if (server.getTickCount() % 20 == 0) {
			com.summy.reliquary.effect.AngelRoster.refresh(server);
			// 恶魔名单：同口径兜底（1.5.9）
			com.summy.reliquary.effect.DemonRoster.refresh(server);
				// 1.7.6：献祭匕首的「防丢失配方」状态机（每秒一次）
				com.summy.reliquary.effect.DaggerRecovery.tickServer(server);
				// 1.7.9：圣光短矛的「防丢失配方」状态机（每秒一次）
				com.summy.reliquary.effect.SpearRecovery.tickServer(server);
			}
		RiceHungerLock.tick(server);
		SpiritAltarSet.tickServer(server);
		RevelationTracker.tickServer(server);
		RevelationAscension.tickServer(server);
		// 启示之光：驱动持续照射（结算 + 粒子）
		RevelationBeam.tickServer(server);
		// 救恩：领域审判（半径、锁定、伤害与粒子）
		com.summy.reliquary.effect.SalvationDomain.tickServer(server);
		// 免死后的 2 秒无敌守卫：清理过期项
		DeathImmunity.tick(server);
		// 神圣斗篷的无敌守卫：清理过期项
		com.summy.reliquary.effect.HolyMantle.tick(server);
		// 圣光：结算本 tick 记录的命中 + 重画还没过期的光柱
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		// 圣心：箭矢追踪（以箭矢为中心找最近的敌对生物）
		com.summy.reliquary.effect.SacredHeart.tick(server);
		// 玄秘魔眼（1.6.10 联动）：清理过期的"恐惧移速归零"
		com.summy.reliquary.effect.OccultEye.tickServer(server);
		// 遁入暗影（1.7.5）：推进两把仪式匕首的右键技能
		com.summy.reliquary.effect.ShadowDash.tickServer(server);
		// 神性：8 格光环审判（内部按每秒结算）
		com.summy.reliquary.effect.Godhead.tickAura(server);
		AttributeManager.tickServer(server);
	}

	/** 服务端停止时清掉活动光束与登记，避免残留到下一个存档 */
	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		RevelationBeam.clear();
		com.summy.reliquary.sin.SinEffects.clearAllEnvyTargets();
		com.summy.reliquary.effect.AngelRoster.clear();
		com.summy.reliquary.effect.DemonRoster.clear();
		com.summy.reliquary.effect.DemonDeal.clear();
		com.summy.reliquary.effect.DelayedChat.clear();
		com.summy.reliquary.effect.PlayerFlags.clearDataReady();
		com.summy.reliquary.effect.DemonPact.clear();
		com.summy.reliquary.effect.HolyLightEffect.clear();
		SoulShield.clear();
		com.summy.reliquary.effect.CombatTuning.clear();
		com.summy.reliquary.effect.DamagePools.clear();
		com.summy.reliquary.effect.Abaddon.clear();
		com.summy.reliquary.effect.Godhead.clearGuards();
		com.summy.reliquary.effect.Sacrifice.clear();
	}

	/**
	 * 玩家 tick 结束后维护黄血。
	 *
	 * <p>时机很关键：{@code PlayerTickEvent} 的 END 阶段发生在 {@code Player#tick()} 的最后，
	 * 也就是 enchantment-reforged 那类"在 {@code LivingEntity#tick()} 末尾重写吸收值"的模组之后，
	 * 因此被它们清掉的黄血能在同一 tick 内补回来。
	 */
	@SubscribeEvent
	public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
			return;
		}
		// 1.5.9：本模组数据就绪登记（"这个实体实例 tick 过"）—— 读档阶段的 canEquip 一律放行
		com.summy.reliquary.effect.PlayerFlags.markDataReady(player);
		// 1.7.9：主手换了就立刻重算属性 —— 长矛的"攻击距离 +0.5 / +1.0"不能等下一次 10 tick 校准
		net.minecraft.world.item.Item mainHand = player.getMainHandItem().getItem();
		if (LAST_MAIN_HAND.put(player.getUUID(), mainHand) != mainHand) {
			AttributeManager.apply(player);
		}
		// 伤害池（1.6.4）：清掉跨 tick 还没结算的"临时并入吸收值"残留
		com.summy.reliquary.effect.DamagePools.tickPlayer(player);
		// 无敌帧窗口（1.6.5）：Fabric 侧在 tick 末尾把窗口钳到"有效窗口 + 10"
		com.summy.reliquary.effect.CombatTuning.clampFabricWindow(player);
		// 受伤节流汇总日志（1.6.3）：每 5 秒一条，便于确认"帧伤"来自哪个伤害类型
		if (player.tickCount % 100 == 0) {
			com.summy.reliquary.effect.CombatTuning.flushLog(player);
		}
		// 复仇之魂（1.6.3）：范围圈每 tick 一批火焰粒子（逆时针、与救恩反相）；伤害仍是每秒结算
		com.summy.reliquary.effect.VengefulSpirit.tickRing(player);
		// 恶魔线补强（1.6.7）：当前持恶魔标记时保持"不着火"（服务端清火会同步，客户端火焰 HUD 覆盖层随之不显示）
		clearDemonFire(player);
		// 亚巴顿（1.6.8）：恶魔光环每 tick 结算 + 球内黑烟；顺带清理过期的无敌记录
		com.summy.reliquary.effect.Abaddon.tickPlayer(player);
		// 神性（1.8.2）：清理过期的"死亡拦截后 2 秒无敌"记录
		com.summy.reliquary.effect.Godhead.tickPlayer(player);
		// 献祭（1.8.2）：清理过期的近战增伤记录
		com.summy.reliquary.effect.Sacrifice.tickPlayer(player);
		// 七罪：每 tick 的即时压制（暴食的饥饿上限必须是每 tick 生效，否则吃东西后会闪回 19/20）
		SinEffects.tickPlayerEveryTick(player);
		com.summy.reliquary.effect.VirtuesEffects.tickPlayerEveryTick(player);
		// 七罪：每秒维护一次（触发条件检测 + 持续性效果）
		if (player.tickCount % 20 == 0) {
			com.summy.reliquary.sin.SinEffects.tickPlayer(player);
			// 「有罪之人」的兜底判定：每秒检查是否持有七罪之源
			SinChallenges.tickObtained(player);
			// 「心」/「神」的兜底判定：每秒检查是否持有圣心 / 神性（1.5.6）
			com.summy.reliquary.advancement.ItemObtained.tick(player);
			// 五芒星：兜底补发（老存档） + 累计持有时间（1.5.8）
			com.summy.reliquary.effect.Pentagram.tick(player);
			// 伯列恒之星（1.6.10）：与「三位一体」同条件（真三件套）的每秒兜底
			com.summy.reliquary.advancement.SinChallenges.tickStar(player);
			// 「是否获取过启示」（1.6.10）：持有终末天启 / 神性 → 置位；顺带做老存档一次性迁移
			if (com.summy.reliquary.effect.RevelationTracker.updateObtained(player)) {
				com.summy.reliquary.effect.RevelationTracker.sync(player);
			}
			// 恶魔交易：灵魂沙峡谷驻留 / 离场 / 回归（1.5.9）
			com.summy.reliquary.effect.DemonDeal.tick(player);
			// 恶魔契约（1.6.0）：契约自愈 + 每日邪恶度刷新 + 里程碑兜底
			com.summy.reliquary.effect.DemonPact.tickPlayer(player);
			// 魂心（1.6.9）：补满计时按"秒"走，所以必须放在每秒分支（以前每 tick 递减 → 30 tick ≈ 1.5 秒）
			SoulShield.tickPlayer(player);
			// 复仇之魂（1.6.2）：每秒对半径内的敌人结算一次狱火伤害（范围圈见上面的每 tick 调用）
			com.summy.reliquary.effect.VengefulSpirit.tick(player);
			// 玄秘魔眼（1.6.5）：每秒给"注视到的那一具"施加/刷新恐惧
			com.summy.reliquary.effect.OccultEye.tickPlayer(player);
			// 圣心 / 神性（1.6.7）：每秒清掉身上已有的黑暗与恐惧（拦住"先中招、后戴上"的情况）
			com.summy.reliquary.effect.DivineImmunity.tickPlayer(player);
			// 神性 / 亚巴顿（1.8.2）：每秒清掉身上已有的**原版负面效果**
			com.summy.reliquary.effect.DebuffImmunity.tickPlayer(player);
			// 光环（1.7.1）：获取过启示后，低血时每秒刷新「生命回复」
			com.summy.reliquary.effect.HaloBlessing.tickPlayer(player);
			// 栏位阶段化（1.6.2）：灵台 / 启示之座 / 加护的格数按当前阶段校正
			com.summy.reliquary.effect.SlotSizing.tickPlayer(player);
			// 标记自愈：老存档补发天使 / 放弃一切标记，补做「纯洁无瑕」的转化
			SinChallenges.selfHeal(player);
			com.summy.reliquary.effect.VirtuesEffects.tickPlayer(player);
			// 佩戴伯列恒之星且还没揭示时，每秒把"剩余秒数"同步给客户端（提示里的倒计时）
			if (CurioHelper.wears(player, SummyReliquary.STAR_OF_BETHLEHEM.get())
					&& !RevelationTracker.isRevealed(player)) {
				RevelationTracker.sync(player);
			}
			// 「主世界有个人重生点」只在状态**变化**时补发一次同步（痛悔短祷的风味文本要用）
			boolean overworldRespawn = com.summy.reliquary.effect.PlayerFlags.hasOverworldRespawn(player);
			if (LAST_OVERWORLD_RESPAWN.getOrDefault(player.getUUID(), false) != overworldRespawn) {
				LAST_OVERWORLD_RESPAWN.put(player.getUUID(), overworldRespawn);
				RevelationTracker.sync(player);
			}
		}
	}

	/** 受伤（原版已扣完吸收）时记录扣完后的吸收总量，供黄血份额结算使用 */
	/** 受伤尝试（在原版无敌帧判定之前）：只用于受伤节流的汇总日志 */
	@SubscribeEvent
	public static void onLivingAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
		if (!event.getEntity().level().isClientSide()) {
			com.summy.reliquary.effect.CombatTuning.recordAttempt(event);
		}
	}

	/** 生物死亡：七罪的击杀计数与暴食的击杀回复；玩家自己死亡时结算贪婪的死亡惩罚 */
	@SubscribeEvent
	public static void onLivingDeath(LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) {
			return;
		}
		Entity source = event.getSource().getEntity();
		if (source instanceof ServerPlayer killer && killer != event.getEntity()) {
			com.summy.reliquary.sin.SinEffects.onKill(killer, event.getEntity());
			// 末影龙：两条挑战成就（与「解放末地」同口径 —— 击杀者是该玩家）
			if (event.getEntity() instanceof EnderDragon) {
				SinChallenges.onDragonSlainBy(killer);
			}
		}
		if (event.getEntity() instanceof ServerPlayer victim) {
			com.summy.reliquary.sin.SinEffects.onPlayerDeath(victim);
			// 1.8.2：记录死亡地点，供神性「回溯」（X）使用
			com.summy.reliquary.effect.PlayerFlags.setLastDeath(victim,
					victim.level().dimension().location().toString(),
					victim.getX(), victim.getY(), victim.getZ());
			// 1.8.2：献祭的近战增伤不跨死亡保留
			com.summy.reliquary.effect.Sacrifice.forget(victim);
		}
		// 心之碎片：带启示之光的生物死亡时按几率掉落（1.5.6）
		com.summy.reliquary.effect.HeartShardDrop.onDeath(event.getEntity(), event.getSource().getEntity());
		// 恶魔契约（1.6.0）：献祭完成判定 + 邪恶度积攒
		com.summy.reliquary.effect.DemonPact.onDeath(event.getEntity(), event.getSource().getEntity());
	}

	/** 丢弃物品：慷慨叠层（七德） */
	@SubscribeEvent
	public static void onItemToss(net.minecraftforge.event.entity.item.ItemTossEvent event) {
		if (event.getPlayer() instanceof ServerPlayer player) {
			com.summy.reliquary.effect.VirtuesEffects.onItemToss(player);
		}
		// 1.7.3：创世纪的掉落实体加"防丢失"保护（不烧不炸不自然消失）。
		// 主动丢弃本身仍然算"玩家自己的选择" —— 死亡时不会补发（见 onPlayerClone）。
		protectKeyDrop(event.getEntity());
	}

	/**
	 * 1.7.3：全模组"创世纪掉落物"的防丢失保护。
	 *
	 * <p>任何途径产生的创世纪掉落物（丢弃 / 死亡掉落前的瞬间 / 指令 / 背包满掉脚下）都会经过这里 ——
	 * 设成 {@code invulnerable}（免疫仙人掌、爆炸等会毁掉掉落物的伤害）并把 {@code lifespan} 拉满
	 * （原版 5 分钟会 despawn）。掉进虚空仍会被移除，那是"主动丢弃"，按需求不补发。
	 */
	@SubscribeEvent
	public static void onEntityJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide()) {
			return;
		}
		protectKeyDrop(event.getEntity());
	}

	/** 是创世纪 / 四把仪式武器的掉落物就加保护；其它实体原样返回 */
	private static void protectKeyDrop(Entity entity) {
		if (entity instanceof net.minecraft.world.entity.item.ItemEntity item
				&& (item.getItem().is(SummyReliquary.GENESIS.get())
						|| item.getItem().is(SummyReliquary.SACRIFICIAL_DAGGER.get())
						|| item.getItem().is(SummyReliquary.DARK_ARTS.get())
						// 1.7.9：两把天使线长矛同样"不烧不炸不自然消失"
						|| item.getItem().is(SummyReliquary.HOLY_SPEAR.get())
						|| item.getItem().is(SummyReliquary.SERAPH_SPEAR.get()))) {
			com.summy.reliquary.item.GenesisItem.protectDrop(item);
		}
	}

	/**
	 * 1.7.3：创世纪"死亡不掉落" —— 死亡时**不产生**创世纪掉落物（避免和复活补发重复）。
	 *
	 * <p>复活补发在 {@link #onPlayerClone}：只要"原玩家身上有、新玩家身上没有"就补一个；
	 * 因此主动丢弃（丢出 / 丢入虚空）之后再死不会补发 —— 那时原玩家身上本来就没有。
	 */
	@SubscribeEvent
	public static void onLivingDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
		if (event.getEntity().level().isClientSide()
				|| !(event.getEntity() instanceof ServerPlayer)) {
			return;
		}
		// 1.7.6 / 1.7.9：创世纪与四把仪式武器都是"死亡不掉落"（复活时补发，见 onPlayerClone）
		event.getDrops().removeIf(drop -> drop.getItem().is(SummyReliquary.GENESIS.get())
				|| drop.getItem().is(SummyReliquary.SACRIFICIAL_DAGGER.get())
				|| drop.getItem().is(SummyReliquary.DARK_ARTS.get())
				|| drop.getItem().is(SummyReliquary.HOLY_SPEAR.get())
				|| drop.getItem().is(SummyReliquary.SERAPH_SPEAR.get()));
	}

	/** 进度达成：纯洁之人 → 天使标记；无罪之人 → 放弃一切；解放末地 → 判定两条末影龙挑战 */
	@SubscribeEvent
	public static void onAdvancementEarned(net.minecraftforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			SinChallenges.onAdvancementEarned(player, event.getAdvancement());
		}
	}

	/** 玩家显示名（聊天栏、死亡提示等）：恶魔标记 = 深红（优先），否则天使标记 = 金色 */
	@SubscribeEvent
	public static void onNameFormat(PlayerEvent.NameFormat event) {
		if (com.summy.reliquary.effect.PlayerFlags.isDemon(event.getEntity())) {
			event.setDisplayname(net.minecraft.network.chat.Component
					.literal(event.getUsername().getString())
					.withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(
							net.minecraft.network.chat.TextColor.fromRgb(
									com.summy.reliquary.effect.DemonDeal.CHAT_RED))));
			return;
		}
		if (PlayerFlags.hasAngel(event.getEntity())) {
			event.setDisplayname(net.minecraft.network.chat.Component
					.literal(event.getUsername().getString())
					.withStyle(net.minecraft.ChatFormatting.GOLD));
		}
	}

	/** Tab 列表里的名字：恶魔 = 深红（优先），否则天使 = 金色 */
	@SubscribeEvent
	public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
		if (com.summy.reliquary.effect.PlayerFlags.isDemon(event.getEntity())) {
			net.minecraft.network.chat.Component base = event.getDisplayName() != null
					? event.getDisplayName()
					: event.getEntity().getName();
			event.setDisplayName(net.minecraft.network.chat.Component
					.literal(base.getString())
					.withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(
							net.minecraft.network.chat.TextColor.fromRgb(
									com.summy.reliquary.effect.DemonDeal.CHAT_RED))));
			return;
		}
		if (PlayerFlags.hasAngel(event.getEntity())) {
			// TabListNameFormat 没有 getUsername()：优先用事件自带的显示名，没有就用实体名
			net.minecraft.network.chat.Component base = event.getDisplayName() != null
					? event.getDisplayName()
					: event.getEntity().getName();
			event.setDisplayName(net.minecraft.network.chat.Component
					.literal(base.getString())
					.withStyle(net.minecraft.ChatFormatting.GOLD));
		}
	}

	/** 上床睡觉：怠惰的「早睡」计数 */
	@SubscribeEvent
	public static void onSleepInBed(PlayerSleepInBedEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			com.summy.reliquary.sin.SinEffects.onSleep(player);
		}
	}

	/** 动物繁殖成功：色欲的繁殖计数 */
	@SubscribeEvent
	public static void onBabySpawn(BabyEntitySpawnEvent event) {
		if (event.getCausedByPlayer() instanceof ServerPlayer player) {
			com.summy.reliquary.sin.SinEffects.onBred(player);
		}
	}

	/** 吃完 / 喝完一件物品：暴食的高饱和度进食计数 */
	@SubscribeEvent
	public static void onUseItemFinish(LivingEntityUseItemEvent.Finish event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			com.summy.reliquary.sin.SinEffects.onItemConsumed(player, event.getItem());
		}
	}

	/** 玩家退出时清掉邦邦女仆状态，避免重新进来还是开启状态 */
	@SubscribeEvent
	public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			// 1.5.8 诊断：离场瞬间的栏位快照 —— 配合登录 / 保存三条日志，用来定位"重登丢饰品"
			logSlotSnapshot(player, "离场");
			MaidModeManager.forget(player);
			SoulShield.forget(player);
			com.summy.reliquary.effect.CombatTuning.forget(player);
			// 1.6.4：还原可能残留的"临时并入吸收值"，并清掉挂起记录
			com.summy.reliquary.effect.DamagePools.forget(player);
			com.summy.reliquary.effect.Abaddon.forget(player);
			com.summy.reliquary.effect.Godhead.forget(player);
			com.summy.reliquary.effect.Sacrifice.forget(player);
			RevelationAscension.forget(player);
			RevelationBeam.forget(player);
			AttributeManager.forget(player);
			SinEffects.forget(player);
			DeathImmunity.forget(player);
			com.summy.reliquary.effect.VirtuesEffects.forget(player);
			// 痛悔短祷的失败提示节流表
			com.summy.reliquary.item.ActOfContritionItem.forget(player);
			// 救恩：清掉该玩家的锁定进度与还在重画的粒子束
			com.summy.reliquary.effect.SalvationDomain.forget(player);
			com.summy.reliquary.effect.HolyMantle.forget(player);
			// 1.5.9：清掉"数据就绪"登记、恶魔交易的驻留状态与延迟台词队列
				com.summy.reliquary.effect.PlayerFlags.forgetDataReady(player);
				com.summy.reliquary.effect.DemonDeal.forget(player);
				LAST_OVERWORLD_RESPAWN.remove(player.getUUID());
				LAST_MAIN_HAND.remove(player.getUUID());
			// 名单里去掉他，并把新名单广播给剩下的人
			com.summy.reliquary.effect.AngelRoster.refresh(player.getServer());
			com.summy.reliquary.effect.DemonRoster.refresh(player.getServer());
		}
	}

	/** 登录时把七罪状态与启示坐标同步给客户端（提示文本要用） */
	@SubscribeEvent
	public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			// 1.5.8 诊断：**最开始**先打一条快照 —— 这样能被用来判断
			// "饰品位在登录前就已经是空的（写盘/读档丢）" 还是 "登录后的自愈/反序列化把它卸了"。
			logSlotSnapshot(player, "登录早期");
			// 首次进入世界：发放七罪之源与光环（只发一次；是否自动佩戴七罪之源按配置）
			StarterKit.grantIfFirst(player);
			RevelationTracker.sync(player);
			AttributeManager.apply(player);
			SoulShield.onJoin(player);
			// 1.6.9：黑心补满口径与魂心对齐（戴撒旦圣经且池子空了就补满）
			com.summy.reliquary.effect.DemonPact.onJoin(player);
			// 老存档自愈：过期存档里可能已有纯洁之人 / 无罪之人，但没有对应的标记
			SinChallenges.selfHeal(player);
			com.summy.reliquary.effect.VirtuesEffects.tickPlayer(player);
			// 自愈 / 首次发放可能刚给了天使标记：重算并广播天使名单
			com.summy.reliquary.effect.AngelRoster.refresh(player.getServer());
			com.summy.reliquary.effect.DemonRoster.refresh(player.getServer());
			// 1.5.7：登录快照 —— 出现"重登后饰品位空了"时，一眼能看出是登录前就被卸还是别的原因
			logSlotSnapshot(player, "登录");
		}
	}

	/**
	 * 通用栏位快照（1.5.8）：6 个本模组栏位的内容 + 背包里的本模组物品 + 天使标记。
	 *
	 * <p>在**登录早期 / 登录 / 离场 / 保存**四个时点各打一条，用来定位"重登后天使门槛饰品掉回背包"：
	 * 只要对比"离场还戴着"和"下次登录早期已经空了"，就能判断是写盘 / 读档丢的，
	 * 还是读档之后被 Curios 的反序列化 / 重算卸掉的。
	 */
	public static void logSlotSnapshot(ServerPlayer player, String tag) {
		StringBuilder slots = new StringBuilder();
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (String identifier : new String[]{com.summy.reliquary.slot.ReliquarySlots.HALO,
					com.summy.reliquary.slot.ReliquarySlots.STOMACH,
					com.summy.reliquary.slot.ReliquarySlots.SOUL_SEAL,
					com.summy.reliquary.slot.ReliquarySlots.SPIRIT_ALTAR,
					com.summy.reliquary.slot.ReliquarySlots.REVELATION,
					com.summy.reliquary.slot.ReliquarySlots.BLESSING}) {
				var stacksHandler = handler.getCurios().get(identifier);
				if (stacksHandler == null) {
					continue;
				}
				for (int index = 0; index < stacksHandler.getSlots(); index++) {
					net.minecraft.world.item.ItemStack stack =
							stacksHandler.getStacks().getStackInSlot(index);
					if (!stack.isEmpty()) {
						slots.append(identifier).append('#').append(index).append('=')
								.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
										.getKey(stack.getItem()).getPath())
								.append(' ');
					}
				}
			}
		});
		StringBuilder carried = new StringBuilder();
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(index);
			if (stack.isEmpty()) {
				continue;
			}
			net.minecraft.resources.ResourceLocation id =
					net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
			if (!SummyReliquary.NAMESPACE.equals(id.getNamespace())) {
				continue;
			}
			carried.append(id.getPath()).append('x').append(stack.getCount()).append(' ');
		}
		SummyReliquary.LOGGER.info("[Summy Reliquary] {}快照：天使标记={}、栏位内容={}、背包内本模组物品={}",
				tag,
				com.summy.reliquary.effect.PlayerFlags.hasAngel(player),
				slots.length() == 0 ? "（空）" : slots.toString().trim(),
				carried.length() == 0 ? "（无）" : carried.toString().trim());
	}

	/** 写盘时打一条快照（1.5.8）：用来区分"离场就丢"还是"写盘/读档丢" */
	@SubscribeEvent
	public static void onPlayerSaveToFile(PlayerEvent.SaveToFile event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			logSlotSnapshot(player, "保存");
		}
	}

	/** 换维度后重新同步客户端显示 */
	@SubscribeEvent
	public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			RevelationTracker.sync(player);
			SoulShield.onJoin(player);
			com.summy.reliquary.effect.DemonPact.onJoin(player);
		}
	}

	/** 饰品装卸后立刻重算属性（血条/攻速/生命上限都不再等 1 秒） */
	@SubscribeEvent
	public static void onCurioEquip(CurioEquipEvent event) {
		if (event.getSlotContext().entity() instanceof ServerPlayer player) {
			AttributeManager.apply(player);
		}
	}

	@SubscribeEvent
	public static void onCurioUnequip(CurioUnequipEvent event) {
		if (event.getSlotContext().entity() instanceof ServerPlayer player) {
			AttributeManager.apply(player);
		}
	}

	/** 合成产物被取走：检查「赎罪」配方的开放条件 */
	@SubscribeEvent
	public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
		RedemptionRecipeGate.onCrafted(event);
		// 天使线配方（三件套 / 圣光 / 斗篷 / 神性 / 救恩 / 圣心）与仪式法袍的门禁：
		// 判据不满足时收走产物并按该配方材料原样退回
		com.summy.reliquary.effect.SpiritAltarRecipeGate.onCrafted(event);
		// 邪恶度门禁（1.6.0）：未解锁时收走产物、退回材料
		com.summy.reliquary.effect.EvilRecipeGate.onCrafted(event);
	}

	/** 死亡/切换维度后把本模组的存档数据带到新实体上 */
	@SubscribeEvent
	public static void onPlayerClone(PlayerEvent.Clone event) {
		CompoundTag oldData = event.getOriginal().getPersistentData();
		if (oldData.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)) {
			event.getEntity().getPersistentData().put(SinManager.ROOT,
					oldData.getCompound(SinManager.ROOT).copy());
		}
		if (event.getEntity() instanceof ServerPlayer player) {
			// 复活 / 换维度后按当前装备状态重建黄血
			SoulShield.onJoin(player);
			com.summy.reliquary.effect.DemonPact.onJoin(player);
			// 1.7.3：创世纪"死亡不掉落" —— 只按"原玩家身上有没有"判定补发
			// （主动丢弃后原玩家身上本来就没有 → 不补发；掉入虚空同理）
			if (event.isWasDeath()) {
				com.summy.reliquary.item.GenesisItem.restoreOnDeath(player,
						com.summy.reliquary.item.GenesisItem.holdsGenesis(event.getOriginal()));
				// 1.7.6：两把仪式匕首同样"死亡不掉落"
					com.summy.reliquary.item.RitualDaggerItem.restoreOnDeath(player,
							com.summy.reliquary.effect.DaggerRecovery.holds(event.getOriginal(),
									SummyReliquary.SACRIFICIAL_DAGGER.get()),
							com.summy.reliquary.effect.DaggerRecovery.holds(event.getOriginal(),
									SummyReliquary.DARK_ARTS.get()));
					// 1.7.9：两把天使线长矛同样"死亡不掉落"
					com.summy.reliquary.effect.SpearRecovery.restoreOnDeath(player,
							com.summy.reliquary.effect.SpearRecovery.holds(event.getOriginal(),
									SummyReliquary.HOLY_SPEAR.get()),
							com.summy.reliquary.effect.SpearRecovery.holds(event.getOriginal(),
									SummyReliquary.SERAPH_SPEAR.get()));
				}
		}
	}

	/** 战斗结算：套装加成、投射物免疫、伯列恒之星加伤 */
	@SubscribeEvent
	public static void onLivingHurt(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) {
			return;
		}
		// 1.7.9：四把仪式武器的门槛 —— 未达标时"完全禁用所有能力"，左键近战伤害整体归零。
		// 判据只看**主手**：其余武器 / 空手 / 别人的攻击一律不受影响。
		if (event.getSource().getEntity() instanceof ServerPlayer gateAttacker
				&& gateAttacker != event.getEntity()
				&& !com.summy.reliquary.effect.WeaponGates.mainHandAttackAllowed(gateAttacker)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
			return;
		}
		// 遁入暗影（1.7.5）：技能期间无敌 —— 从按下右键开始、到全部斩击完成结束，整击取消
		if (event.getEntity() instanceof ServerPlayer dashPlayer
				&& com.summy.reliquary.effect.ShadowDash.isInvulnerable(dashPlayer)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
			return;
		}
		// 恶魔线补强（1.6.7）：当前持恶魔标记的玩家完全免疫火焰类伤害（火焰 / 着火 / 岩浆 / 岩浆块地板）
		if (event.getEntity() instanceof ServerPlayer demonPlayer
				&& isDemonFireImmune(demonPlayer)
				&& event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
			return;
		}
		// 飞行免摔（1.6.8）：处于**本模组授予的飞行**（幽魂 / 魔眼 / 终末天启 / 亚巴顿 / 神性）时免疫摔落伤害
		if (event.getEntity() instanceof ServerPlayer flyer
				&& AttributeManager.hasGrantedFlight(flyer)
				&& event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
			return;
		}
		// 亚巴顿复活后的 8 秒无敌：取消一切来源的伤害
		com.summy.reliquary.effect.Abaddon.onHurt(event);
		if (event.isCanceled()) {
			return;
		}
		// 神性死亡拦截后的 2 秒无敌：同样取消一切来源的伤害（1.8.2）
		com.summy.reliquary.effect.Godhead.onHurt(event);
		if (event.isCanceled()) {
			return;
		}
		// 免死后的 2 秒无敌：直接取消这次伤害
		DeathImmunity.onHurt(event);
		if (event.isCanceled()) {
			return;
		}
		// 神圣斗篷：无敌期内取消伤害；否则佩戴者受击后开启无敌窗口（1.5.4）
		com.summy.reliquary.effect.HolyMantle.onHurt(event);
		if (event.isCanceled()) {
			return;
		}
		// 魂心完全破碎后的无敌：同样取消一切来源的伤害（1.6.2）
		SoulShield.onHurt(event);
		if (event.isCanceled()) {
			return;
		}
		// 神性：清单里的原版环境伤害全部免疫（虚空不在清单内，不免疫）
		if (event.getEntity() instanceof ServerPlayer protectedPlayer
				&& com.summy.reliquary.effect.Godhead.isEnvironmentImmune(protectedPlayer, event.getSource())) {
			event.setAmount(0.0F);
			event.setCanceled(true);
			return;
		}
		// 受伤无敌帧（1.6.3）：沿用原版逻辑，只把窗口长度改写成"有效窗口 + 10"
		com.summy.reliquary.effect.CombatTuning.applyWindow(event);
		// 1.7.5：强力斩击的数值已经是一次「基础斩击」的汇总，不再走一遍增伤乘区
		boolean suppressBonus = com.summy.reliquary.effect.ShadowDash.suppressOutgoingBonus();
		if (!suppressBonus) {
			SpiritAltarSet.onLivingHurt(event);
		}

		// 七罪：攻击者是本模组玩家时的增伤与暴怒自伤
		if (event.getSource().getEntity() instanceof ServerPlayer attacker
				&& attacker != event.getEntity() && !event.isCanceled()
				// 本模组的神性伤害（圣光 / 启示之光）不再吃一遍七罪七德倍率
				&& !com.summy.reliquary.effect.HolyLightEffect.isDivine(event.getSource())) {
			if (!suppressBonus) {
				float sinDamage = SinEffects.modifyOutgoingDamage(attacker, event.getEntity(), event.getSource(), event.getAmount());
				float virtueDamage = com.summy.reliquary.effect.VirtuesEffects.modifyOutgoingDamage(
						attacker, event.getEntity(), event.getSource(), sinDamage);
				// 圣心 + 神性：同一个「全伤害最终倍率」乘区（相加），并且不吃到本模组的神性伤害上
				event.setAmount(virtueDamage * finalDamageMultiplier(attacker, event.getSource()));
			}
			// 恶魔契约（1.6.0）：诅咒 > 阈值时按本次出伤值吸血（口径同 ER 嗜血）
			if (com.summy.reliquary.effect.DemonPact.lifestealActive(attacker)) {
				attacker.heal(event.getAmount()
						* (float) (ReliquaryConfig.pactLifestealPercent() / 100.0D));
			}
		}
		// 七罪：受害者是本模组玩家时的受伤增加与色欲受击脱甲
		// 1.8.2：献祭的自伤不触发傲慢放大与色欲脱甲
		if (event.getEntity() instanceof ServerPlayer victim && !event.isCanceled()
				&& !com.summy.reliquary.effect.Sacrifice.isSelfDamage(event.getSource())) {
			event.setAmount(SinEffects.modifyIncomingDamage(victim, event.getAmount()));
		}

		// 深渊领主（1.6.7）：佩戴者造成的所有伤害给目标叠一层「狱火」（定值真伤与狱火自身不触发）
		if (!event.isCanceled()
				&& event.getSource().getEntity() instanceof ServerPlayer hellfireOwner
				&& hellfireOwner != event.getEntity()
				&& com.summy.reliquary.effect.AbyssLord.wears(hellfireOwner)
				&& com.summy.reliquary.effect.AbyssLord.shouldApply(event.getSource())) {
			// 1.6.10：把攻击者一并传进去 —— 同时佩戴亚巴顿时要打"抗性联动"时间戳
			com.summy.reliquary.effect.AbyssLord.applyStack(event.getEntity(), hellfireOwner);
		}
	}

	/**
	 * 圣心 / 神性的效果免疫（1.6.7）：拦住新施加的「黑暗」与「恐惧」。
	 *
	 * <p>{@code MobEffectEvent.Applicable} 是 {@code @HasResult} 事件（Forge 自己读的就是 {@code getResult()}），
	 * 所以这里用 {@code setResult(DENY)} 让 {@code addEffect} 直接返回 false。
	 */
	@SubscribeEvent
	public static void onEffectApplicable(net.minecraftforge.event.entity.living.MobEffectEvent.Applicable event) {
		if (event.getEntity().level().isClientSide()) {
			return;
		}
		// 1.8.2：佩戴神性 / 亚巴顿时，所有**原版**负面效果一律拦住
		// （必须放在前面的免疫判定之前：亚巴顿不在 DivineImmunity 的名单里）
		if (com.summy.reliquary.effect.DebuffImmunity.covers(event.getEntity())
				&& com.summy.reliquary.effect.DebuffImmunity.blocks(event.getEffectInstance().getEffect())) {
			event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
			return;
		}
		if (!com.summy.reliquary.effect.DivineImmunity.immune(event.getEntity())) {
			return;
		}
		if (com.summy.reliquary.effect.DivineImmunity.blocks(event.getEffectInstance().getEffect())) {
			event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
		}
	}

	/**
	 * 恶魔线火焰免疫的判定（1.6.7）：开关开、且**当前**持恶魔标记。
	 *
	 * <p>忏悔换回天使标记后自动失效（= "仅当前恶魔线"，与"签过约就永久"区分开）。
	 */
	public static boolean isDemonFireImmune(net.minecraft.world.entity.LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) {
			return false;
		}
		return com.summy.reliquary.config.ReliquaryConfig.enableDemonFireImmunity()
				&& com.summy.reliquary.effect.PlayerFlags.isDemon(player);
	}

	/**
	 * 恶魔线：保持"不着火"。
	 *
	 * <p>服务端清火会同步给客户端，所以屏幕上的火焰覆盖层（HUD）也随之完全不出现。
	 */
	public static void clearDemonFire(ServerPlayer player) {
		if (isDemonFireImmune(player) && player.isOnFire()) {
			player.clearFire();
		}
	}

	/** 挖掘速度：Forge 1.20.1 没有"挖掘速度"属性，用 BreakSpeed 事件实现 */
	/**
	 * 圣光：记录一次命中（LOWEST 优先级 —— 此时各类倍率都算完了，但目标还没减伤）。
	 *
	 * <p>用 LOWEST 是因为本模组的七罪/七德加成与其它模组的倍率都在更早的优先级里改过数值，
	 * 这样才能拿到你要的「结算各类伤害倍率后、结算减伤前」的基准。
	 */
	@SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
	public static void onLivingHurtRecordHolyLight(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) {
			return;
		}
        com.summy.reliquary.effect.HolyLightEffect.record(event);
        // 恶魔契约（1.6.0）：佩戴契约时对村民追加献祭真实伤害（一击必杀）
        com.summy.reliquary.effect.DemonPact.extraSacrificeDamage(event);
    }

	/**
	 * 伤害池（1.6.5）：命中前把魂心 / 黑心点数**当场扣掉、并临时并入原版吸收值**（LOWEST —— 在取消、倍率、献祭追加之后）。
	 *
	 * <p>这样原版自己就会按「吸收 / 护盾 → 生命」的顺序结算；多并入的部分由 {@code DamagePools.reconcile}
	 * （下一次命中前 / 玩家 tick 末尾）退还并还原吸收值。必须排在 LOWEST 的最后，所以写在
	 * {@code onLivingHurtRecordHolyLight} 之后。
	 */
	@SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
	public static void onLivingHurtPreparePools(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) {
			return;
		}
		com.summy.reliquary.effect.DamagePools.prepare(event);
	}

	/** 挖掘速度：Forge 1.20.1 没有"挖掘速度"属性，用 BreakSpeed 事件实现 */
	@SubscribeEvent
	public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
		Player player = event.getEntity();
		float speed = event.getNewSpeed();
		if (CurioHelper.wears(player, SummyReliquary.THE_HALO.get())) {
			double factor = HaloState.multiplier(player);
			speed = (float) (speed * (1.0D + ReliquaryConfig.haloBreakSpeedPercent() / 100.0D * factor));
		}
		// 怠惰（未赎罪）：挖掘速度 -20%
		speed *= SinEffects.breakSpeedFactor(player);
		// 勤勉：挖掘速度 +10%（七德）
		speed *= com.summy.reliquary.effect.VirtuesEffects.breakSpeedFactor(player);
		// 圣心：挖掘速度 +15%（与光环同款算法）
		if (CurioHelper.wears(player, SummyReliquary.SACRED_HEART.get())) {
			speed *= 1.0F + ReliquaryConfig.sacredHeartBreakSpeedPercent() / 100.0F;
		}
		if (speed != event.getNewSpeed()) {
			event.setNewSpeed(speed);
		}
	}

	// 圣心 + 神性的「全伤害最终倍率」：两者同属一个乘区，相加后一次性相乘
	// （同时佩戴 = ×1.5，而不是 ×1.56）。调用点已经排除了本模组的神性伤害
	// （圣光 / 光柱 / 领域），所以不会出现"圣光伤害再乘一遍"的重复计算。
	private static float finalDamageMultiplier(ServerPlayer attacker,
			net.minecraft.world.damagesource.DamageSource source) {
		double percent = 0.0D;
		if (CurioHelper.wears(attacker, SummyReliquary.SACRED_HEART.get())) {
			percent += ReliquaryConfig.sacredHeartDamagePercent();
		}
		if (com.summy.reliquary.effect.Godhead.active(attacker)) {
			percent += ReliquaryConfig.godheadDamagePercent();
		}
		// 恶魔契约（1.6.0）：契约 16% + 邪恶度（奇偶交替）也进同一个乘区
		percent += com.summy.reliquary.effect.DemonPact.attackBonusPercent(attacker);
		// 献祭（1.8.2）：只对严格左键近战生效，并随剩余时间线性衰减
		if (com.summy.reliquary.effect.Sacrifice.isMeleeHit(attacker, source)) {
			percent += com.summy.reliquary.effect.Sacrifice.bonusPercent(attacker);
		}
		return (float) (1.0D + percent / 100.0D);
	}

	/** 右击空气 / 右击没有交互的方块 */
	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		// 遁入暗影（1.7.5）：技能期间禁用右键（使用物品 / 交互）
		if (event.getEntity() instanceof ServerPlayer dashPlayer
				&& com.summy.reliquary.effect.ShadowDash.isInvulnerable(dashPlayer)) {
			event.setCancellationResult(InteractionResult.FAIL);
			event.setCanceled(true);
			return;
		}
		if (RiceHungerLock.tryBlockEating(event.getEntity(), event)) {
			event.setCancellationResult(InteractionResult.FAIL);
			return;
		}
		if (!event.getLevel().isClientSide()) {
			// 手持七宗罪碎片右击 → 把已激活的那一罪变成「已赎罪」
			if (SinFragments.tryRedeem(event.getEntity(), event)) {
				event.setCanceled(true);
				return;
			}
			SinRedemption.tryRedeem(event.getEntity(), event);
		}
	}

	/** 右击有交互的方块：白饭要在这里也拦一次，"赎罪→美德"也要覆盖这条路径 */
	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		// 遁入暗影（1.7.5）：技能期间禁用右键（使用物品 / 交互）
		if (event.getEntity() instanceof ServerPlayer dashPlayer
				&& com.summy.reliquary.effect.ShadowDash.isInvulnerable(dashPlayer)) {
			event.setCancellationResult(InteractionResult.FAIL);
			event.setCanceled(true);
			return;
		}
		if (RiceHungerLock.tryBlockEating(event.getEntity(), event)) {
			event.setCancellationResult(InteractionResult.FAIL);
			return;
		}
		if (!event.getLevel().isClientSide()) {
			// 手持七宗罪碎片右击 → 把已激活的那一罪变成「已赎罪」
			if (SinFragments.tryRedeem(event.getEntity(), event)) {
				event.setCanceled(true);
				return;
			}
			SinRedemption.tryRedeem(event.getEntity(), event);
		}
	}

	/** 遁入暗影（1.7.5）：技能期间禁用左键攻击 */
	@SubscribeEvent
	public static void onAttackEntity(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
		if (event.getEntity() instanceof ServerPlayer player
				&& com.summy.reliquary.effect.ShadowDash.isInvulnerable(player)) {
			event.setCanceled(true);
		}
	}

	/** 遁入暗影（1.7.5）：技能期间禁用左键挖方块 */
	@SubscribeEvent
	public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
		if (event.getEntity() instanceof ServerPlayer player
				&& com.summy.reliquary.effect.ShadowDash.isInvulnerable(player)) {
			event.setCanceled(true);
		}
	}

	/** 遁入暗影（1.7.5）：无敌期间免疫击退（只免疫击退，不清除药水效果） */
	@SubscribeEvent
	public static void onLivingKnockBack(
			net.minecraftforge.event.entity.living.LivingKnockBackEvent event) {
		if (event.getEntity() instanceof ServerPlayer player
				&& com.summy.reliquary.effect.ShadowDash.isInvulnerable(player)) {
			event.setStrength(0.0F);
			event.setCanceled(true);
		}
	}

	/** 注册 /summyreliquary slots 诊断命令 */
	@SubscribeEvent
	public static void onRegisterCommands(RegisterCommandsEvent event) {
		ReliquaryCommand.register(event.getDispatcher());
	}
}
