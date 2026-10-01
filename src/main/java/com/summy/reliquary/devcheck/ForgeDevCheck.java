package com.summy.reliquary.devcheck;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.attribute.ReliquaryAttributes;
import com.summy.reliquary.effect.RevelationTracker;
import com.summy.reliquary.effect.RiceHungerLock;
import com.summy.reliquary.maid.MaidModeManager;
import com.summy.reliquary.sin.Sin;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.List;
import java.util.UUID;

/**
 * 开发自检脚本（默认关闭，只有加 JVM 参数 {@code -Dsummy.devcheck=true} 时才运行）。
 *
 * <p>自动跑一遍：测试环境保护 → 栏位校验矩阵 → 装备 → 女仆吸附 → 白饭锁饥饿 / 禁食 → 赎罪转化 → 栏位内容 dump。
 * 正式打包时通过 build.gradle 的 exclude 把它排除在 jar 之外。
 */
// 只挂在客户端：自检要用 Minecraft / LocalPlayer，专用服务端不能加载这个类（会触发 dist 校验失败）
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ForgeDevCheck {
	/** 只有显式打开时才跑，避免影响正常游戏 */
	private static final boolean ENABLED = Boolean.getBoolean("summy.devcheck");

	private static int tick = 0;
	private static int clientTick = 0;
	/**
	 * 1.7.2 保险：客户端在"没有玩家 / 关卡"的状态下累计了多少 tick。
	 *
	 * <p>以前这里直接把 {@code clientTick} 清零，于是只要客户端长期处于这种状态
	 * （例如跨维度传送后一直停在加载界面），自检的退出条件就永远达不到、测试实例会一直挂着。
	 * 现在除了计数清零，还会打一条诊断并按 {@link #CLIENT_IDLE_HARD_STOP_TICKS} 强制退出。
	 */
	private static int clientIdleTicks = 0;
	/** 已经打过几条诊断（避免刷屏） */
	private static int clientIdleLogged = 0;
	/** 客户端累计 tick（不受玩家是否为空影响），超过 {@link #CLIENT_HARD_STOP_TICKS} 强制退出 */
	private static int clientTotalTicks = 0;
	/** 客户端"没有玩家 / 关卡"连续多久就强制退出（约 30 秒） */
	private static final int CLIENT_IDLE_HARD_STOP_TICKS = 600;
	/** 客户端总 tick 的硬上限（约 5 分钟；正常自检约 2420 tick 就会自己结束） */
	private static final int CLIENT_HARD_STOP_TICKS = 6000;
	private static double itemStartDistance = -1.0D;
	private static UUID pulledItemId = null;
	private static int redemptionCountBefore = -1;
	private static UUID glowZombieId = null;
	/** 嫉妒自检用的测试狼 */
	private static UUID envyWolfId = null;
	private static double damagePlain = 0.0D;
	private static double damageGlowing = 0.0D;
	/** 黄血受伤测试前的生命值（用来确认这次伤害没打到血条） */
	private static float soulHealthBefore = 0.0F;
	/** 三池（护盾 → 魂心 → 黑心）结算前的生命值 */
	private static float shieldsHealthBefore = 0.0F;
	/** 光柱测试的五个目标（0=正前 10 格、1=侧向 3 格、2=背后、3=超距、4=正前 6 格但中途挪走） */
	private static final UUID[] beamZombies = new UUID[5];
	private static final float[] beamHealthBefore = new float[5];
	private static boolean beamFirstFire;
	private static boolean beamSecondFire;
	private static float beamCasterHealthBefore = 0.0F;

	/** 光环观感截图用的头部姿态：{偏航角, 俯仰角} */
	private static final float[][] HALO_POSES = {
			{0.0F, 0.0F},
			{0.0F, -40.0F},
			{0.0F, 40.0F},
			{90.0F, 0.0F}
	};
	/** 从第几个客户端 tick 开始摆姿势；每个姿势占 30 tick：第 0 tick 摆姿势、第 10 tick 截图 */
	private static final int POSE_START_TICK = 800;
	private static final int POSE_INTERVAL = 30;

	/** 待客户端核对的「主世界重生点」标记：0 = 无、1 = 应为 true、2 = 应为 false */
	private static int pendingRespawnCheck = 0;
	private static int pendingRespawnCheckDelay = 0;

	private ForgeDevCheck() {
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (!ENABLED || event.phase != TickEvent.Phase.END) {
			return;
		}
		MinecraftServer server = event.getServer();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			tick = 0;
			return;
		}
		ServerPlayer player = players.get(0);
		tick++;

		try {
			switch (tick) {
				case 5 -> prepareSafeWorld(player);
				case 10 -> diagnose(player);
				case 20 -> giveItems(player);
				case 40 -> checkValidators(player);
				case 60 -> equipAll(player);
				case 80 -> checkEquipped(player);
				case 100 -> startMaidTest(player);
				case 140 -> checkMaidPull(player);
				case 160 -> testRiceLock(player);
				case 180 -> checkRiceLock(player);
				case 200 -> testEatingBlocked(player);
				case 220 -> testRedemption(player);
				case 240 -> checkRedemption(player);
				case 250 -> checkClientSlots();
				case 260 -> {
					dumpSlots(player);
					log("===== 功能自检结束，接下来截图光环观感 =====");
				}
				case 280 -> prepareHaloBaseline(player);
				case 300 -> activateOneSin(player);
				case 320 -> equipVirtues(player);
				case 340 -> clearSoulSeal(player);
				case 360 -> equipBodyOnly(player);
				case 380 -> equipFullSet(player);
				case 400 -> spawnGlowZombie(player);
				case 440 -> checkGlow(player, true);
				case 460 -> moveZombieAway(player);
				case 500 -> checkGlow(player, false);
				case 520 -> testDamageBonus(player);
				case 560 -> prepareRevelation(player);
				case 600 -> checkRevelation(player);
				case 620 -> checkDuplicatePrevention(player);
				case 640 -> prepareSinTests(player);
				case 660 -> testFragmentRedeem(player);
				case 680 -> testRestrictions(player);
				case 700 -> testRedemptionGate(player);
				case 720 -> testRecipeGate(player);
				case 740 -> testCombat(player);
				case 745 -> prepareSoulShieldTest(player);
				case 765 -> checkSoulShieldStable(player);
				case 800 -> checkSoulShieldAfterWipe(player);
				case 820 -> testSoulShieldDamage(player);
				case 840 -> checkSoulShieldAfterDamage(player);
				case 860 -> testSoulShieldRefresh(player);
				case 880 -> checkSoulShieldRefreshed(player);
				case 900 -> testSoulShieldUnequip(player);
				case 920 -> checkSoulShieldUnequipped(player);
				case 925 -> prepareAdditiveShieldTest(player);
				case 945 -> checkAdditiveShield(player);
				case 965 -> testAdditiveShieldDamage(player);
				case 985 -> testShieldExhaustedAndNoSteal(player);
				case 1000 -> checkShieldExhausted(player);
				case 1005 -> prepareFinalRevelation(player);
				case 1025 -> testRevelationBeam(player);
				case 1040 -> moveBeamTargetAway(player);
				case 1058 -> checkRevelationBeam(player);
				case 1070 -> prepareAscension(player);
				case 1180 -> checkAscension(player);
				case 1200 -> checkStarterKit(player);
				case 1220 -> checkRightClickRules(player);
				case 1240 -> checkCreativeFlight(player);
				case 1260 -> checkChargingState(player);
				case 1280 -> checkAdvancements(player);
				case 1300 -> prepareSinEffects(player);
				case 1320 -> checkPrideTrigger(player);
				case 1340 -> checkPrideDamage(player);
				case 1360 -> prepareEnvy(player);
				case 1380 -> checkEnvy(player);
				case 1400 -> checkEnvyRedeemed(player);
				case 1420 -> checkWrath(player);
				case 1440 -> checkWrathRedeemed(player);
				case 1460 -> prepareSloth(player);
				case 1500 -> checkSloth(player);
				case 1520 -> checkGreed(player);
				case 1540 -> prepareGluttony(player);
				case 1560 -> checkGluttony(player);
				case 1580 -> prepareLust(player);
				case 1600 -> checkLust(player);
				case 1620 -> checkLustHiddenTrigger(player);
				case 1640 -> checkSinMessageColors(player);
				case 1660 -> checkDeathImmunity(player);
				case 1680 -> checkChargeParticles(player);
				case 1700 -> checkUnforgivableDescription(player);
				case 1720 -> checkGluttonyTickClamp(player);
				case 1740 -> checkSinThresholds(player);
				case 1760 -> checkAngelGate(player);
				case 1780 -> checkDragonChallenges(player);
				case 1800 -> checkStarGrant(player);
				case 1820 -> checkRecipeGate(player);
				case 1840 -> checkNameFormat(player);
				case 1860 -> checkSelfHeal(player);
				case 1880 -> checkTextsAndIcons(player);
				case 1900 -> checkVirtuesInheritance(player);
				case 1920 -> checkCommandPermissions(player);
				case 1922 -> checkSalvationParticles(player);
				case 1923 -> checkSalvationTargetable(player);
				case 1925 -> prepareSalvationDomain(player);
				case 1930 -> checkSalvationRadius(player);
				case 1932 -> checkSalvationDamage(player);
				case 1933 -> checkSalvationBeams(true);
				case 1934 -> checkSalvationLockBases(player);
				case 1936 -> checkSalvationConfig(player);
				case 1938 -> prepareSalvationBoundary(player);
				case 1958 -> checkSalvationBoundary(player);
				case 1959 -> cleanupSalvationDomain(player);
				case 1945 -> checkSalvationBeams(false);
				case 1961 -> checkContritionFailureKeys(player);
				case 1963 -> checkContritionThrottle(player);
				case 1965 -> checkContritionMessages(player);
				case 1967 -> checkGenesisCancel(player);
				case 1969 -> checkGenesisConfirm(player);
				case 1971 -> grantOverworldRespawn(player);
				case 1975 -> unlockAllSinsForVirtues(player);
				case 1990 -> clearOverworldRespawn(player);
				case 1997 -> checkSalvationTargetableCommand(player);
				case 2001 -> checkHolyLightSetup(player);
				case 2003 -> checkHolyLightDamage(player);
				case 2005 -> checkHolyLightMultiSegment(player);
				case 2007 -> checkHolyLightSurprise(player);
				case 2009 -> checkHolyLightSample(player);
				case 2011 -> checkHolyMantle(player);
				case 2013 -> checkSacredHeartStats(player);
				case 2015 -> checkSacredHeartDamage(player);
				case 2017 -> checkSacredHeartHoming(player);
				case 2019 -> checkGodheadBasics(player);
				case 2021 -> checkGodheadAura(player);
				case 2023 -> checkGodheadImmunity(player);
				case 2025 -> checkGodheadDeathGuard(player);
				case 2027 -> checkRevelationInheritance(player);
				case 2029 -> checkGodheadAuraKnockback(player);
				case 2031 -> checkHeartShard(player);
				case 2033 -> checkNewAdvancements(player);
				case 2035 -> checkAuraTypeAndCurioGate(player);
				case 2042 -> checkPentagramBasics(player);
				case 2044 -> checkPentagramGrant(player);
				case 2046 -> checkPentagramMessage(player);
				case 2048 -> checkSacredHeartRecipeAndDiagnostics(player);
				case 2050 -> checkDataReadyGate(player);
				case 2052 -> checkHeartShardOutcomes(player);
				case 2054 -> checkDemonDealDialogues(player);
				case 2056 -> checkDemonDealSign(player);
				case 2058 -> checkDemonDealContrition(player);
				case 2060 -> checkDemonDealSecondRound(player);
				case 2062 -> checkDemonPactSlotAndConfiscation(player);
				case 2064 -> checkDemonPactSacrifice(player);
				case 2066 -> checkDemonPactBlackHearts(player);
				case 2068 -> checkDemonPactEvil(player);
				case 2070 -> checkDemonPactUnlocks(player);
				case 2072 -> checkRobesGate(player);
				case 2074 -> checkRobesStatsAndDecay(player);
				case 2076 -> checkBlackHeartCap(player);
				case 2078 -> checkSatanicSnapshotVirtues(player);
				case 2080 -> checkSatanicSnapshotRedeemed(player);
				case 2082 -> checkSatanicBibleEffects(player);
				case 2084 -> checkBlackHeartShatter(player);
				case 2086 -> checkRelicConfigAndProtocol(player);
				case 2092 -> checkIframeWindows(player);
				case 2094 -> checkHellfire(player);
				case 2096 -> checkMarkEffects(player);
				case 2098 -> checkSlotPhases(player);
				case 2100 -> checkSixDrops(player);
				case 2102 -> checkSoulOrderAndMarkOnly(player);
				case 2104 -> checkHudRules(player);
				case 2106 -> checkNightWraith(player);
				case 2108 -> checkUnlockTolerance(player);
				case 2110 -> checkFlameRing(player);
				// 2112：原「深渊领主占位」用例 —— 1.6.7 转正后挪到 2142（checkAbyssLord）
				case 2112 -> {
				}
				case 2114 -> checkFactionTexts();
				case 2116 -> checkBrimstoneGates(player);
				case 2118 -> checkDemonFlame(player);
				case 2120 -> checkTextReplacements(player);
				case 2122 -> checkSlotValidatorMatrix(player);
				case 2124 -> checkPoolDoubleTrigger(player);
				case 2126 -> checkPoolSwallowedHit(player);
				case 2128 -> checkPoolGuardNullify(player);
				case 2130 -> checkFabricWindowClamp(player);
				case 2132 -> checkHudRows();
				case 2134 -> checkGazeLook(player);
				case 2136 -> checkOccultEye(player);
				// 1.6.6：Fabric 侧真实命中（池子必须真的扣）+ 复活不重发初始饰品
				case 2138 -> checkPoolsOnFabricSide(player);
				case 2140 -> checkStarterKitRespawn(player);
				// 1.6.7：深渊领主 / 狱火 / 恶魔线火焰免疫 / 圣心·神性免疫 / 黑心不超上限
				case 2142 -> checkAbyssLord(player);
				case 2144 -> checkAbyssLordHellfire(player);
				case 2146 -> checkDemonFireImmunity(player);
				case 2148 -> checkDivineImmunity(player);
				case 2150 -> checkBlackHeartNotOverCap(player);
				// 1.6.8：深渊领主文案 / 亚巴顿 / 飞行免摔 / 邪恶满值
				case 2152 -> checkAbyssLordTexts();
				case 2154 -> checkAbaddon(player);
				case 2156 -> checkAbaddonFlame(player);
				case 2158 -> checkAbaddonFlight(player);
				case 2160 -> checkAbaddonRevive(player);
				case 2162 -> checkAbaddonAura(player);
				case 2164 -> checkEvilFullLock(player);
				// 1.6.9：魂心/黑心补满对齐 + 成就页扩充（新根 + 9 条）+ 恶魔线上色 + 日志字段
				case 2166 -> checkSoulHeartRefreshCycle(player);
				case 2168 -> checkBlackHeartRefillAlign(player);
				case 2170 -> checkIconItems();
				case 2172 -> checkAdvancementGraph(player);
				case 2174 -> checkAdvancementTriggers(player);
				case 2176 -> checkDemonFlavorColors();
				case 2178 -> checkPoolLogFields();
				// 1.6.10：创世纪发放/可见性 + 启示属性 + 条件驱动发放 + 六条饰品联动 + 配置
				case 2180 -> checkGenesisGrantAndVisibility(player);
				case 2182 -> checkRevelationAttribute(player);
				case 2184 -> checkConditionDrivenStar(player);
				case 2186 -> checkGodheadSynergies(player);
				case 2188 -> checkAbaddonSynergies(player);
				case 2190 -> checkSynergyTooltips(player);
				case 2192 -> checkSynergyConfig();
				// 1.7.0：配方补齐（29 张）+ 材料/退料一致性 + 三位一体退还三件套 + 可获得性审计
				case 2194 -> checkRecipeCoverage(player);
				// 1.7.1：恶魔王冠 / 光环改造 / 圣心门禁 / 末影龙排斥恶魔线 / 圣经需佩戴契约
				case 2196 -> checkDevilCrown(player);
				case 2198 -> checkHaloShiftAndRegen(player);
				case 2200 -> checkSacredHeartGate(player);
				case 2202 -> checkDragonGuardDemon(player);
				case 2204 -> checkBibleRequiresContract(player);
				// 1.7.2：三条封锁 / 签约没收摘除 / 忏悔没收摘除 / 魂印四态 / 圣经档位与贪婪阈值 /
				//         创世纪表现 / 星之闭环 / 异常清标记
				case 2206 -> checkTradeLocks(player);
				case 2208 -> checkPactConfiscation(player);
				case 2210 -> checkRepentConfiscation(player);
				case 2212 -> checkSealSnapshotFourStates(player);
				case 2214 -> checkBibleTierAndGreedThreshold(player);
				case 2216 -> checkGenesisActivation(player);
				case 2218 -> checkStarRegrantLoop(player);
				// 1.7.6：两把匕首的获取、门禁与保护
				case 2219 -> checkDaggerAcquisition(player);
				case 2220 -> checkAbnormalUnmarkRestore(player);
				// 1.7.5：两把仪式匕首 + 遁入暗影
				case 2221 -> checkRitualDaggers(player);
				// 1.7.3：Kilt 可用粒子路径 + 创世纪防丢失/重新武装/内存清理
				case 2222 -> checkGenesisLossProtection(player);
				// 1.7.4：自愈前置门（戴撒旦圣经时不能被"纯洁无瑕"自愈写成全赎罪）
				case 2223 -> checkBibleSelfHealGuard(player);
				case 2224 -> checkGenesisReweaponAndMemory(player);
				// 1.7.9：两把天使线长矛的配方与门禁 / 投掷与圣光爆发 / 获取与防丢失 /
				//         四把武器的门槛与攻击距离 / 文案与模型 / 10% 圣光 proc / 创世纪没收
				case 2225 -> checkSpearRecipesAndGates(player);
				case 2226 -> checkSpearThrowAndBurst(player);
				case 2227 -> checkSpearAcquisitionAndRecovery(player);
				case 2228 -> checkWeaponGatesAndReach(player);
				case 2229 -> checkSpearTextsAndModels(player);
				case 2230 -> checkSpearHolyLightProc(player);
				case 2231 -> checkWeaponGenesisConfiscation(player);
				// 1.7.10：武器 × 饰品联动（恶魔线 / 天使线）+ 新武器金刀片 + 创造页顺序
				case 2232 -> checkDemonWeaponSynergies(player);
				case 2233 -> checkAngelWeaponSynergies(player);
				case 2234 -> checkGoldenRazor(player);
				case 2235 -> checkCreativeOrderAndBranding(player);
				// 1.7.10 修订：持有判定统一（忽略 NBT + 覆盖副手/盔甲/光标）+ 五芒星"一罪一赎"发放口径
				case 2236 -> checkHeldItemScan(player);
				case 2237 -> checkPentagramGrantByRedeemedPath(player);
				// 1.7.10 收尾：纯洁无瑕佩戴率判据 + 创世纪清计时 + 新成就「近乎完美」
				case 2239 -> checkFlawlessWearingRatio(player);
				case 2240 -> checkGenesisClearsWearingTimer(player);
				case 2241 -> checkNearlyPerfect(player);
				// 1.8.0：七罪激活/赎罪口径 + 匕首发放前置 + 魔眼恐惧免疫 + 愤怒自伤调整
				case 2242 -> checkRedeemedNeverReactivates(player);
				case 2243 -> checkActivationRequiresSource(player);
				case 2244 -> checkDaggerGrantGate(player);
				case 2245 -> checkOccultEyeFearImmunity(player);
				case 2246 -> checkWrathSelfHitNeverKills(player);
				case 2037 -> cleanupAfterTests(player);
				case 2040 -> checkHolyMantleExpired(player);
				default -> {
				}
			}
		} catch (Throwable throwable) {
			log("步骤 " + tick + " 出错: " + throwable);
			throwable.printStackTrace();
			Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
		}
	}

	/**
	 * 客户端 tick：把相机切到第三人称背面，依次摆出几种头部姿态并截图，
	 * 用来确认光环「中线略高于头顶、贴着头后、随转头与点头跟随」。
	 */
	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (!ENABLED || event.phase != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		// 1.7.2：无条件累计 tick，作为"永远不退出"的硬保险
		clientTotalTicks++;
		if (clientTotalTicks > CLIENT_HARD_STOP_TICKS) {
			log("===== 自检结束（硬保险：总 tick 超过 " + CLIENT_HARD_STOP_TICKS + "）=====");
			client.stop();
			return;
		}
		if (client.player == null || client.level == null) {
			clientTick = 0;
			clientIdleTicks++;
			// 诊断：这种状态以前会静默地把自检卡死，现在打出来（前 6 条）
			if (clientIdleTicks % 200 == 1 && clientIdleLogged < 6) {
				clientIdleLogged++;
				log("[诊断] 客户端空 tick=" + clientIdleTicks + "：player="
						+ (client.player == null ? "null" : "有") + "、level="
						+ (client.level == null ? "null" : "有"));
			}
			if (clientIdleTicks > CLIENT_IDLE_HARD_STOP_TICKS) {
				log("===== 自检结束（硬保险：客户端连续 " + clientIdleTicks + " tick 没有玩家/关卡）=====");
				client.stop();
			}
			return;
		}
		clientIdleTicks = 0;
		clientTick++;

		if (clientTick == POSE_START_TICK - 20) {
			// 第三人称背面，才能看到玩家自己与脑后的光环
			client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}

		if (clientTick == 700) {
			checkParticleProvider();
			dumpTooltip(client, SummyReliquary.BANG_BANG_HALO.get(), "邦邦咔邦光环");
			dumpTooltip(client, SummyReliquary.FREELOADERS_RICE.get(), "白饭");
			dumpTooltip(client, SummyReliquary.THE_BODY.get(), "肉体");
			dumpTooltip(client, SummyReliquary.THE_MIND.get(), "思想");
			dumpTooltip(client, SummyReliquary.THE_SOUL.get(), "灵魂");
			dumpTooltip(client, SummyReliquary.THE_HALO.get(), "光环");
			dumpTooltip(client, SummyReliquary.STAR_OF_BETHLEHEM.get(), "伯列恒之星");
			dumpTooltip(client, SummyReliquary.VIRTUES.get(), "美德");
		}

		// 七罪三态：未激活 / 已激活 / 已赎罪（服务端 640~700 之间逐步切换）
		if (clientTick == 760) {
			dumpTooltip(client, SummyReliquary.SOURCE_OF_SINS.get(), "七罪之源（三态：已激活/已赎罪/未激活）");
			dumpTooltip(client, SummyReliquary.REDEMPTION.get(), "赎罪");
		}

		// 1.5.1：痛悔短祷的 Shift 祷文必须是同一行（不再拆成三行），配色白 / 金 / 白
		if (clientTick == 765) {
			checkContritionPrayer();
		}

		// 1.5.2：美德提示门（七罪未赎清时不显示「你已赎清罪过」）
		if (clientTick == 1985) {
			checkVirtuesTooltip(false);
		}
		if (clientTick == 1995) {
			checkVirtuesTooltip(true);
		}
		// 1.5.2：「主世界有个人重生点」标记位 —— 由服务端步骤置位后延迟几 tick 再核对，
		// 这样不受"服务端 tick / 客户端 tick 计数不同步"的影响
		if (pendingRespawnCheck != 0) {
			pendingRespawnCheckDelay++;
			if (pendingRespawnCheckDelay > 5) {
				checkOverworldRespawnFlag(pendingRespawnCheck == 1);
				pendingRespawnCheck = 0;
				pendingRespawnCheckDelay = 0;
			}
		}

		// 1.5.3：天使名单隔离（自己有没有标记，都不能影响别人的名字染色）
		if (clientTick == 2010) {
			checkAngelRosterIsolation();
		}

		// 1.5.4：占位物品提示 + 圣光死亡文本
		if (clientTick == 767) {
			checkPlaceholderTooltip();
		}
		if (clientTick == 769) {
			checkHolyLightDeathText();
		}
		// 1.5.5：经文前缀 + 圣心 / 神性的 Shift 文本
		if (clientTick == 771) {
			checkNewItemTexts();
		}
		// 1.5.8：五芒星的提示文案 / 配色（纯函数，不改客户端状态）
		if (clientTick == 773) {
			checkPentagramTexts();
		}
		// 1.5.9：恶魔交易的文案 / 配色（纯函数，不改客户端状态）
		if (clientTick == 775) {
			checkDemonDealTexts();
		}
		// 1.6.0：契约 / 黑心 HUD / 献祭与解锁文案
			if (clientTick == 777) {
				checkDemonPactClient();
			}
			// 1.6.1：撒旦圣经 / 仪式法袍的贴图、模型、语言键与魂心 HUD 计数
			if (clientTick == 779) {
				checkSatanicBibleClient();
			}
			// 1.6.1：仪式法袍的提示（恶魔门槛两态）
			if (clientTick == 783) {
				checkRobesTooltipClient();
			}

		// 三件套提示去重：Shift 行只留基础效果，套装效果只在 Alt 行
		if (clientTick == 780) {
			checkSetTooltips(client);
		}

		// 终末天启的提示（风味金色滚动 + Shift 三行）
		if (clientTick == 790) {
			dumpTooltip(client, SummyReliquary.FINAL_REVELATION.get(), "终末天启");
		}

		// 配置化的提示文本：数值应来自配置文件
		if (clientTick == 800) {
			checkConfigTexts();
		}

		// 光环在三种状态下的提示（与服务端 280/300/320 tick 的状态对应）
		if (clientTick == 290) {
			dumpTooltip(client, SummyReliquary.THE_HALO.get(), "光环（无七罪之源/美德）");
		}
		if (clientTick == 312) {
			dumpTooltip(client, SummyReliquary.THE_HALO.get(), "光环（七罪之源 + 已激活傲慢）");
		}
		// 1.4.3：灵台需求行的分隔符不应再有两侧空格
		if (clientTick == 344) {
			java.util.List<net.minecraft.network.chat.Component> lines =
					new ItemStack(SummyReliquary.THE_SOUL.get()).getTooltipLines(
							client.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
			boolean spaced = lines.stream().anyMatch(line -> line.getString().contains(" · "));
			String requirement = lines.stream()
					.filter(line -> line.getString().contains("同时佩戴"))
					.findFirst().map(net.minecraft.network.chat.Component::getString).orElse("未找到");
			log("灵台需求行：含「 · 」=" + spaced + "（应为 false）；文案=「" + requirement + "」");
		}		if (clientTick == 332) {
			dumpTooltip(client, SummyReliquary.THE_HALO.get(), "光环（佩戴美德）");
		}

		int index = (clientTick - POSE_START_TICK) / POSE_INTERVAL;
		if (index >= 0 && index < HALO_POSES.length) {
			int phase = (clientTick - POSE_START_TICK) % POSE_INTERVAL;
			if (phase == 0) {
				applyPose(client, HALO_POSES[index][0], HALO_POSES[index][1]);
				log(String.format("光环截图姿态 %d：yaw=%.0f pitch=%.0f", index, HALO_POSES[index][0], HALO_POSES[index][1]));
			} else if (phase == 10) {
				Screenshot.grab(client.gameDirectory, "halo_pose_" + index, client.getMainRenderTarget(), message -> {
				});
			}
		}

		// 尾巴留够时间：1.5.2 的服务端用例排到 2005 tick，客户端 tick 计数会略微领先服务端
		if (clientTick > POSE_START_TICK + HALO_POSES.length * POSE_INTERVAL + 1500) {
			log("===== 自检结束 =====");
			client.stop();
		}
	}

	/** 打印某个物品在客户端实际生成的提示行，用来确认「栏位：xx」只出现一次 */
	/**
	 * 1.7.2：短寿命火焰粒子的**提供者**必须在位。
	 *
	 * <p>背景：1.6.7 起复仇之魂的火焰环用自定义粒子 {@code summy-reliquary:short_flame}；
	 * 一旦提供者缺失（或拿到空的 SpriteSet），客户端会为每颗粒子刷一条
	 * {@code Could not spawn particle effect} 的 WARN —— 火焰环每 tick 8 颗，几秒钟就把实例卡死
	 * （测试实例"卡在加载地形中"就是这个）。这里在客户端侧直接探一次。
	 */
	private static void checkParticleProvider() {
		Minecraft client = Minecraft.getInstance();
		boolean present = false;
		String error = "无";
		try {
			present = client.particleEngine != null
					&& client.particleEngine.createParticle(SummyReliquary.SHORT_FLAME.get(),
							0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D) != null;
		} catch (Throwable throwable) {
			error = throwable.toString();
		}
		String ringType = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE
				.getKey(com.summy.reliquary.effect.VengefulSpirit.ringParticle()));
		// 1.7.3：粒子走"自建 SpriteSet + 纯 providers.put"这条与加载器无关的路，
		// 所以两端都必须发 summy-reliquary:short_flame（不再有 Kilt 回退原版火焰）
		boolean ringTypeOk = "summy-reliquary:short_flame".equals(ringType);
		String sprite = com.summy.reliquary.client.ReliquaryParticles.spriteName();
		boolean spriteOk = "summy-reliquary:short_flame".equals(sprite)
				&& !com.summy.reliquary.client.ReliquaryParticles.spriteMissing();
		// 1.7.3：图集声明必须放在 minecraft 命名空间（方块图集只会读 minecraft:atlases/blocks.json），
		// 所以这里既查"我们的声明真的进了方块图集的资源栈"，也查贴图本体在包内
		boolean packOk = atlasDeclaresShortFlame(client)
				&& hasResource(client, "textures/particle/short_flame.png");
		log("火焰环粒子（1.7.3）：short_flame 提供者在位=" + present + "（应 true；异常=" + error + "）、"
				+ "火焰环实发粒子=" + ringType + "（应 summy-reliquary:short_flame，两端一致）=" + ringTypeOk
				+ "（应 true）、方块图集 sprite=" + sprite + "（应 summy-reliquary:short_flame 且非 missingno）="
				+ spriteOk + "（应 true）、包内资源（minecraft:atlases/blocks.json 里的 short_flame 声明 + 贴图）="
				+ packOk + "（应 true）");
	}

	/**
	 * 1.7.3：方块图集（{@code minecraft:atlases/blocks.json}）的资源栈里，是否有哪个资源包
	 * （也就是我们的 mod jar）声明了 {@code summy-reliquary:short_flame}。
	 * 用来把"图集声明必须放在 minecraft 命名空间"这条口径钉住。
	 */
	private static boolean atlasDeclaresShortFlame(Minecraft client) {
		net.minecraft.resources.ResourceLocation id =
				new net.minecraft.resources.ResourceLocation("minecraft", "atlases/blocks.json");
		for (net.minecraft.server.packs.resources.Resource resource
				: client.getResourceManager().getResourceStack(id)) {
			try (java.io.BufferedReader reader = resource.openAsReader()) {
				StringBuilder builder = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					builder.append(line);
				}
				if (builder.toString().contains("summy-reliquary:short_flame")) {
					return true;
				}
			} catch (Exception ignored) {
				// 某一份读不动就当它没有，不影响其它资源包
			}
		}
		return false;
	}

	/** 打印某个物品在客户端实际生成的提示行，用来确认「栏位：xx」只出现一次 */
	private static void dumpTooltip(Minecraft client, Item item, String label) {
		List<net.minecraft.network.chat.Component> lines =
				new ItemStack(item).getTooltipLines(client.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		log(label + " 提示行（共 " + lines.size() + " 行）：");
		for (net.minecraft.network.chat.Component line : lines) {
			log("   「" + line.getString() + "」");
		}
	}

	/** 1.5.1 / 1.5.2：痛悔短祷的 Shift 祷文无内嵌换行、配色 柔白 / 亮金 / 柔白，且按提示框宽度自动折行 */
	private static void checkContritionPrayer() {
		net.minecraft.network.chat.Component prayer =
				com.summy.reliquary.item.ActOfContritionItem.shiftPrayer();
		String text = prayer.getString();
		List<net.minecraft.network.chat.Component> parts = prayer.getSiblings();
		StringBuilder colors = new StringBuilder();
		for (net.minecraft.network.chat.Component part : parts) {
			if (colors.length() > 0) {
				colors.append('/');
			}
			colors.append(colorOf(part));
		}
		log("痛悔短祷祷文：含换行=" + text.contains("\n") + "（应为 false）、段数=" + parts.size()
				+ "、配色=" + colors + "（应为 #F0F0F0/#FFD700/#F0F0F0，与改动前一致）、含「我今定志」="
				+ text.contains("我今定志") + "、结尾=" + text.substring(Math.max(0, text.length() - 8)));

		// 1.5.2：原版提示框不会自动折行，必须由我们按宽度切行（否则长句顶出屏幕）
		Minecraft client = Minecraft.getInstance();
		int maxWidth = Math.max(200, client.getWindow().getGuiScaledWidth() / 2);
		List<net.minecraft.network.chat.Component> wrapped =
				com.summy.reliquary.item.ReliquaryTooltips.wrap(prayer);
		int widest = 0;
		StringBuilder joined = new StringBuilder();
		boolean goldKept = false;
		for (net.minecraft.network.chat.Component line : wrapped) {
			widest = Math.max(widest, client.font.width(line));
			joined.append(line.getString());
			for (net.minecraft.network.chat.Component part : line.getSiblings()) {
				if ("#FFD700".equals(colorOf(part))) {
					goldKept = true;
				}
			}
		}
		log("痛悔短祷折行：行数=" + wrapped.size() + "（原行宽 " + client.font.width(prayer) + "，上限 " + maxWidth
				+ "）、最宽行=" + widest + "（应 ≤ 上限）、拼接文本一致=" + joined.toString().equals(text)
				+ "（应为 true）、金色分段保留=" + goldKept + "（应为 true）");
	}

	/** 直接改客户端玩家的角度，让渲染立刻反映到画面上 */
	private static void applyPose(Minecraft client, float yaw, float pitch) {
		var player = client.player;
		if (player == null) {
			return;
		}
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
		player.setYHeadRot(yaw);
		player.yHeadRotO = yaw;
		player.yBodyRot = yaw;
		player.yBodyRotO = yaw;
		player.setOldPosAndRot();
	}

	/**
	 * 测试环境保护：和平难度、关闭刷怪、清掉附近怪物、玩家无敌并回满血，
	 * 避免测试过程中被怪物打死导致验证中断。
	 */
	private static void prepareSafeWorld(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		// 上一轮自检的残留会影响这一轮（例如贪婪的钻石、白饭、各种计数）：先清空背包
		player.getInventory().clearContent();
		// 用「简单」而不是「和平」：和平难度会立刻清除敌对生物，导致发光/伤害测试用的僵尸被删掉；
		// 关掉刷怪 + 玩家无敌已经足够安全。
		server.setDifficulty(Difficulty.EASY, true);
		server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
		server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);

		int removed = 0;
		for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(96.0D))) {
			mob.discard();
			removed++;
		}

		player.setInvulnerable(true);
		player.setHealth(player.getMaxHealth());
		player.clearFire();
		// 自检统一先给天使标记：否则灵台三件套 / 伯列恒之星 / 终末天启的佩戴与提示都会被门槛挡住，
		// 干扰既有用例；门槛本身由 checkAngelGate 专门测试（先 revoke 再 grant）。
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		// 复位黄血：清掉上一轮自检残留的吸收值与我们的份额，保证后续数字可预期
		com.summy.reliquary.effect.SoulShield.onUnequipped(player);
		player.setAbsorptionAmount(0.0F);
		// 清掉启示之座（上一轮转化出的终末天启会改变魂心数量，干扰早期检查）
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
			// 1.7.2：**必须**把上一轮残留的复仇之魂也卸下来。
			// 否则一进世界火焰环就每 tick 发 8 颗粒子；一旦粒子提供者有问题，
			// 客户端会每颗粒子刷一条 WARN，直接把实例拖到"卡在加载地形中"。
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 1, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BACK, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
		});
		// 复位时间与位置：上一轮自检把时间设成夜晚并把玩家留在揭示坐标上，
		// 不清理的话"夜间转化"会在早期自动触发，后续数字全部失真
		ServerLevel level = player.serverLevel();
		level.setDayTime(6000L);
		BlockPos spawn = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				level.getSharedSpawnPos()).above();
		player.teleportTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
		log("测试保护已开启：和平难度 / 关闭刷怪 / 清理怪物 " + removed + " 只 / 玩家无敌");
	}

	private static void diagnose(ServerPlayer player) {
		log("Curios 栏位（服务端）= " + CuriosApi.getSlots(player.level()).keySet());
		log("该玩家所属栏位 = " + CuriosApi.getEntitySlots(player).keySet());
		log("getCuriosInventory 是否有值 = " + CuriosApi.getCuriosInventory(player).isPresent());
		BuiltInRegistries.ENTITY_TYPE.stream()
				.filter(type -> !CuriosApi.getEntitySlots(type, false).isEmpty())
				.forEach(type -> log("有栏位的实体(服务端) = " + type
						+ " → " + CuriosApi.getEntitySlots(type, false).keySet()));
	}

	private static void checkClientSlots() {
		var clientPlayer = Minecraft.getInstance().player;
		if (clientPlayer == null) {
			log("客户端玩家为空");
			return;
		}
		log("客户端 PLAYER 栏位 = " + CuriosApi.getEntitySlots(EntityType.PLAYER, true).keySet());
		log("客户端 getCuriosInventory 有值 = " + CuriosApi.getCuriosInventory(clientPlayer).isPresent());
		log("客户端能力直接查询 = " + clientPlayer.getCapability(CuriosCapability.INVENTORY).isPresent());
	}

	private static void giveItems(ServerPlayer player) {
		for (Item item : new Item[]{
				SummyReliquary.BANG_BANG_HALO.get(),
				SummyReliquary.FREELOADERS_RICE.get(),
				SummyReliquary.SOURCE_OF_SINS.get(),
				SummyReliquary.VIRTUES.get(),
				SummyReliquary.REDEMPTION.get(),
				SummyReliquary.THE_BODY.get(),
				SummyReliquary.THE_MIND.get(),
				SummyReliquary.THE_SOUL.get(),
				SummyReliquary.THE_HALO.get(),
				SummyReliquary.STAR_OF_BETHLEHEM.get(),
				Items.APPLE
		}) {
			player.getInventory().add(new ItemStack(item));
		}
		log("已发放测试物品");
	}

	/** 校验矩阵：4 种物品 × 3 个栏位，只有对应组合应为 true */
	private static void checkValidators(ServerPlayer player) {
		log("===== 栏位校验矩阵 =====");
		Item[] items = {
				SummyReliquary.BANG_BANG_HALO.get(),
				SummyReliquary.FREELOADERS_RICE.get(),
				SummyReliquary.SOURCE_OF_SINS.get(),
				SummyReliquary.VIRTUES.get(),
				SummyReliquary.THE_BODY.get(),
				SummyReliquary.THE_MIND.get(),
				SummyReliquary.THE_SOUL.get(),
				SummyReliquary.THE_HALO.get(),
				SummyReliquary.STAR_OF_BETHLEHEM.get(),
				SummyReliquary.FINAL_REVELATION.get()
		};
		String[] slots = {ReliquarySlots.HALO, ReliquarySlots.STOMACH, ReliquarySlots.SOUL_SEAL,
				ReliquarySlots.SPIRIT_ALTAR, ReliquarySlots.REVELATION};

		for (Item item : items) {
			StringBuilder builder = new StringBuilder(item.toString()).append(" → ");
			for (String slot : slots) {
				SlotContext context = new SlotContext(slot, player, 0, false, true);
				builder.append(slot).append('=')
						.append(CuriosApi.isStackValid(context, new ItemStack(item)))
						.append("  ");
			}
			log(builder.toString());
		}
	}

	private static void equipAll(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			log("拿不到 Curios 数据");
			return;
		}
		handler.setEquippedCurio(ReliquarySlots.HALO, 0, new ItemStack(SummyReliquary.BANG_BANG_HALO.get()));
		handler.setEquippedCurio(ReliquarySlots.STOMACH, 0, new ItemStack(SummyReliquary.FREELOADERS_RICE.get()));
		handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
		log("已装备：光环 / 白饭 / 七罪之源");
	}

	private static void checkEquipped(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			log("拿不到 Curios 数据");
			return;
		}
		log("isEquipped 光环=" + handler.isEquipped(SummyReliquary.BANG_BANG_HALO.get())
				+ " 白饭=" + handler.isEquipped(SummyReliquary.FREELOADERS_RICE.get())
				+ " 七罪之源=" + handler.isEquipped(SummyReliquary.SOURCE_OF_SINS.get()));
	}

	private static void startMaidTest(ServerPlayer player) {
		MaidModeManager.toggle(player);
		log("女仆状态已切换，当前 isActive=" + MaidModeManager.isActive(player));

		Vec3 spawn = player.position().add(5.0D, 0.5D, 0.0D);
		ItemEntity item = new ItemEntity(player.serverLevel(), spawn.x, spawn.y, spawn.z, new ItemStack(Items.APPLE));
		item.setNoPickUpDelay();
		player.serverLevel().addFreshEntity(item);
		pulledItemId = item.getUUID();
		itemStartDistance = item.position().distanceTo(player.position());
		log(String.format("已在 5 格外放置掉落物，初始距离 %.2f", itemStartDistance));
	}

	private static void checkMaidPull(ServerPlayer player) {
		ItemEntity tracked = null;
		for (ItemEntity candidate : player.serverLevel()
				.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16.0D), ItemEntity::isAlive)) {
			if (candidate.getUUID().equals(pulledItemId)) {
				tracked = candidate;
				break;
			}
		}
		if (tracked == null) {
			log("吸附检查：目标掉落物已被吸到身边并拾取（视为通过）");
			return;
		}
		double now = tracked.position().distanceTo(player.position());
		log(String.format("吸附检查：初始 %.2f → 现在 %.2f（变小即通过）", itemStartDistance, now));
	}

	private static void testRiceLock(ServerPlayer player) {
		player.getFoodData().setFoodLevel(6);
		player.getFoodData().setSaturation(5.0F);
		log("已把饥饿改成 6 / 饱和度 5，等待锁定");
	}

	private static void checkRiceLock(ServerPlayer player) {
		log("白饭锁定检查：饥饿=" + player.getFoodData().getFoodLevel()
				+ " 饱和=" + player.getFoodData().getSaturationLevel()
				+ " 上限=" + RiceHungerLock.foodCap(player));
	}

	private static void testEatingBlocked(ServerPlayer player) {
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE));
		PlayerInteractEvent.RightClickItem event =
				new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
		MinecraftForge.EVENT_BUS.post(event);
		log("禁食检查：吃苹果被拦下=" + event.isCanceled());
	}

	private static void testRedemption(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			log("拿不到 Curios 数据");
			return;
		}
		handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SummyReliquary.REDEMPTION.get(), 2));
		redemptionCountBefore = player.getMainHandItem().getCount();

		PlayerInteractEvent.RightClickItem event =
				new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
		MinecraftForge.EVENT_BUS.post(event);
		log("赎罪转化检查：事件被取消=" + event.isCanceled());
	}

	private static void checkRedemption(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			log("拿不到 Curios 数据");
			return;
		}
		ItemStack soulSeal = handler.findFirstCurio(SummyReliquary.VIRTUES.get())
				.map(result -> result.stack()).orElse(ItemStack.EMPTY);
		log("魂印栏内容=" + soulSeal.getItem() + " x" + soulSeal.getCount()
				+ "；赎罪数量 " + redemptionCountBefore + " → " + player.getMainHandItem().getCount());
	}

	private static void dumpSlots(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			log("拿不到 Curios 数据");
			return;
		}
		log("===== Curios 栏位内容 =====");
		handler.getCurios().forEach((identifier, stacks) -> {
			for (int index = 0; index < stacks.getSlots(); index++) {
				ItemStack stack = stacks.getStacks().getStackInSlot(index);
				log(String.format("%s#%d 本模组栏位=%s 内容=%s",
						identifier, index, ReliquarySlots.isCustom(identifier) ? "是" : "否",
						stack.isEmpty() ? "空" : stack.getItem() + " x" + stack.getCount()));
			}
		});
		log("女仆状态=" + MaidModeManager.isActive(player));
	}

	private static void log(String message) {
		System.out.println("[DEVCHECK] " + message);
	}

	// ==================== 1.1.0 新增内容的自检 ====================

	/** 光环基线：清空魂印栏并装上「光环」，此时不应有附加状态行 */
	private static void prepareHaloBaseline(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return;
		}
		handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
		handler.setEquippedCurio(ReliquarySlots.HALO, 0, new ItemStack(SummyReliquary.THE_HALO.get()));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log("光环基线：已卸下魂印栏饰品的七罪之源/美德、装上光环；最大生命="
				+ player.getAttributeValue(Attributes.MAX_HEALTH));
	}

	/** 激活一项罪并把七罪之源装回魂印栏：光环属性减半、提示出现「你当知罪」 */
	private static void activateOneSin(ServerPlayer player) {
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.ACTIVATED);
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0,
					new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
		}
		com.summy.reliquary.effect.AttributeManager.apply(player);
		RevelationTracker.sync(player);
		log("已激活「傲慢」并装回七罪之源；最大生命=" + player.getAttributeValue(Attributes.MAX_HEALTH) + "（应为基准的一半加成）");
	}

	/** 换成美德：光环属性翻倍、提示出现「你已赎罪」 */
	private static void equipVirtues(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0,
						new ItemStack(SummyReliquary.VIRTUES.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		RevelationTracker.sync(player);
		log("魂印栏已换成美德；最大生命=" + player.getAttributeValue(Attributes.MAX_HEALTH) + "（应为基准的两倍加成）");
	}

	/** 清空魂印栏，避免影响后面的套装测试 */
	private static void clearSoulSeal(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
			// 顺便摘掉「光环」，让后面的灵台数字更直观（20 基础 + 肉体加成）
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log("魂印栏已清空");
	}

	/** 只戴肉体：最大生命应为 20（基础）+ 10 = 30 */
	private static void equipBodyOnly(ServerPlayer player) {
		ensureAngelLine(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			// 存档会保留上一轮自检的装备，这里先清空灵台三格
			for (int cell = 0; cell < 3; cell++) {
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, cell, ItemStack.EMPTY);
			}
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0,
					new ItemStack(SummyReliquary.THE_BODY.get()));
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log("只戴肉体：最大生命=" + player.getAttributeValue(Attributes.MAX_HEALTH) + "（应为 30）");
		// 诊断：灵台三格内容 + 生命属性上的修正列表
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			var stacks = handler.getStacksHandler(ReliquarySlots.SPIRIT_ALTAR).orElse(null);
			if (stacks != null) {
				StringBuilder builder = new StringBuilder("灵台各格：");
				for (int i = 0; i < stacks.getSlots(); i++) {
					builder.append('[').append(stacks.getStacks().getStackInSlot(i).getItem()).append(']');
				}
				log(builder.toString());
			}
		});
		var health = player.getAttribute(Attributes.MAX_HEALTH);
		if (health != null) {
			health.getModifiers().forEach(modifier ->
					log("  生命修正 " + modifier.getName() + " = " + modifier.getAmount()));
		}
	}

	/** 补上思想与灵魂：套装生效，最大生命应为 40，魂心应为 3，吸收补到 6 */
	private static void equipFullSet(ServerPlayer player) {
		ensureAngelLine(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 1,
					new ItemStack(SummyReliquary.THE_MIND.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2,
					new ItemStack(SummyReliquary.THE_SOUL.get()));
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		com.summy.reliquary.effect.SoulShield.onEquipped(player);
		log("三件套齐：最大生命=" + player.getAttributeValue(Attributes.MAX_HEALTH)
				+ "（应为 30：肉体 +10，套装效果是减伤而非加生命）、魂心=" + player.getAttributeValue(ReliquaryAttributes.SOUL_HEARTS.get())
				+ "（应为 3）、魂心池=" + com.summy.reliquary.effect.SoulShield.points(player)
				+ "（1.6.2 起是独立池，应为 6）、原版吸收=" + player.getAbsorptionAmount() + "（应为 0）");
	}

	/** 在 10 格外放一只僵尸，验证发光 */
	private static void spawnGlowZombie(ServerPlayer player) {
		Zombie zombie = new Zombie(player.serverLevel());
		zombie.moveTo(player.getX() + 10.0D, player.getY(), player.getZ());
		player.serverLevel().addFreshEntity(zombie);
		glowZombieId = zombie.getUUID();
		log("已在 10 格外生成僵尸，等待「思想」给它打发光标记");
	}

	private static void checkGlow(ServerPlayer player, boolean expected) {
		Zombie zombie = findGlowZombie(player);
		boolean glowing = zombie != null && zombie.hasGlowingTag();
		log(String.format("%s僵尸发光=%s（期望 %s）",
				expected ? "范围内" : "范围外", glowing, expected));
	}

	private static void moveZombieAway(ServerPlayer player) {
		Zombie zombie = findGlowZombie(player);
		if (zombie != null) {
			zombie.teleportTo(player.getX() + 60.0D, player.getY(), player.getZ());
		}
		log("已把僵尸移到 60 格外，等待清除发光标记");
	}

	private static void testDamageBonus(ServerPlayer player) {
		Zombie zombie = findGlowZombie(player);
		if (zombie == null) {
			log("找不到测试僵尸");
			return;
		}
		// 自己确保"伯列恒之星"在身上，避免依赖上一轮遗留的装备状态
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.STAR_OF_BETHLEHEM.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		zombie.setGlowingTag(false);
		LivingHurtEvent plain = new LivingHurtEvent(zombie,
				player.damageSources().playerAttack(player), 10.0F);
		MinecraftForge.EVENT_BUS.post(plain);
		damagePlain = plain.getAmount();

		zombie.setGlowingTag(true);
		LivingHurtEvent glowing = new LivingHurtEvent(zombie,
				player.damageSources().playerAttack(player), 10.0F);
		MinecraftForge.EVENT_BUS.post(glowing);
		damageGlowing = glowing.getAmount();

		log(String.format("伤害加成检查：普通目标 %.2f（期望 12.00 = 10×1.2 星）/ 发光目标 %.2f（期望 13.20 = 10×1.1×1.2）",
				damagePlain, damageGlowing));
		zombie.setGlowingTag(false);
	}

	/** 装备伯列恒之星并把累计计时推到揭示前一刻 */
	private static void prepareRevelation(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.STAR_OF_BETHLEHEM.get())));
		int needed = com.summy.reliquary.config.ReliquaryConfig.revealSeconds() * 20;
		RevelationTracker.setTicks(player, Math.max(0, needed - 20));
		log("已装备伯列恒之星，累计计时推到 " + Math.max(0, needed - 20) + " / " + needed + " tick");
	}

	private static void checkRevelation(ServerPlayer player) {
		boolean revealed = RevelationTracker.isRevealed(player);
		int x = RevelationTracker.revealX(player);
		int z = RevelationTracker.revealZ(player);
		var spawn = player.serverLevel().getSharedSpawnPos();
		double distance = Math.sqrt(Math.pow(x - spawn.getX(), 2.0D) + Math.pow(z - spawn.getZ(), 2.0D));
		log(String.format("启示检查：已揭示=%s 坐标=(%d, %d) 距出生点 %.1f 格（应 ≤ %d）",
				revealed, x, z, distance, com.summy.reliquary.config.ReliquaryConfig.revealRadius()));
	}

	/** 不可重复佩戴：灵台同一件物品不能放进第二个格子 */
	private static void checkDuplicatePrevention(ServerPlayer player) {
		ItemStack body = new ItemStack(SummyReliquary.THE_BODY.get());
		top.theillusivec4.curios.api.type.capability.ICurioItem curio =
				(top.theillusivec4.curios.api.type.capability.ICurioItem) SummyReliquary.THE_BODY.get();
		boolean secondCell = curio.canEquip(
				new SlotContext(ReliquarySlots.SPIRIT_ALTAR, player, 1, false, true), body);
		boolean sameCell = curio.canEquip(
				new SlotContext(ReliquarySlots.SPIRIT_ALTAR, player, 0, false, true), body);
		log("不可重复佩戴检查：放进第 2 格=" + secondCell + "（应为 false）、原格重装=" + sameCell + "（应为 true）");
		log(String.format("汇总：伤害 %.2f / %.2f（投射物免疫已按 1.4.0 改为 20%% 免死，见七罪自检后的免死用例）",
				damagePlain, damageGlowing));
	}

	private static Zombie findGlowZombie(ServerPlayer player) {
		if (glowZombieId == null) {
			return null;
		}
		for (LivingEntity entity : player.serverLevel().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(96.0D))) {
			if (entity.getUUID().equals(glowZombieId) && entity instanceof Zombie zombie) {
				return zombie;
			}
		}
		return null;
	}

	// ==================== 1.2.0：七罪三态 / 赎罪流程 / 战斗手感 ====================

	/** 装好七罪之源，把「傲慢」设为已激活，其余保持未激活 */
	private static void prepareSinTests(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0,
					new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
		});
		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.UNACTIVATED);
		}
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.ACTIVATED);
		RevelationTracker.sync(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log("七罪初始：傲慢=已激活，其余=未激活；最大生命=" + player.getAttributeValue(Attributes.MAX_HEALTH));
	}

	/** 手持七宗罪碎片右击赎罪：已激活 → 已赎罪，碎片数量不变 */
	private static void testFragmentRedeem(ServerPlayer player) {
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SummyReliquary.SIN_FRAGMENT_PRIDE.get(), 1));
		PlayerInteractEvent.RightClickItem event =
				new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
		boolean changed = com.summy.reliquary.effect.SinFragments.tryRedeem(player, event);
		log("碎片赎罪：状态变化=" + changed + "，傲慢现在=" + SinManager.state(player, Sin.PRIDE)
				+ "，手中碎片数量=" + player.getMainHandItem().getCount() + "（应为 1，不消耗）");
		// 未佩戴七罪之源时应无效
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY));
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.ACTIVATED);
		PlayerInteractEvent.RightClickItem noSource =
				new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
		boolean changedWithoutSource = com.summy.reliquary.effect.SinFragments.tryRedeem(player, noSource);
		log("未佩戴七罪之源时赎罪：状态变化=" + changedWithoutSource + "（应为 false）");
	}

	/** 七罪之源：非创造无法摘下、创造可摘下、死亡不掉落 */
	private static void testRestrictions(ServerPlayer player) {
		var curio = (top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.SOURCE_OF_SINS.get();
		ItemStack stack = new ItemStack(SummyReliquary.SOURCE_OF_SINS.get());
		boolean wasCreative = player.isCreative();
		var previousMode = player.gameMode.getGameModeForPlayer();

		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		boolean survival = curio.canUnequip(new SlotContext(ReliquarySlots.SOUL_SEAL, player, 0, false, true), stack);
		player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		boolean creative = curio.canUnequip(new SlotContext(ReliquarySlots.SOUL_SEAL, player, 0, false, true), stack);
		player.setGameMode(wasCreative ? net.minecraft.world.level.GameType.CREATIVE : previousMode);

		var dropRule = curio.getDropRule(new SlotContext(ReliquarySlots.SOUL_SEAL, player, 0, false, true),
				player.damageSources().generic(), 0, false, stack);
		log("七罪之源限制：非创造可摘下=" + survival + "（应为 false）、创造可摘下=" + creative
				+ "（应为 true）、掉落规则=" + dropRule + "（应为 ALWAYS_KEEP）");
	}

	/** 只有七罪全部已赎罪时，手持赎罪右击才能把七罪之源变成美德 */
	private static void testRedemptionGate(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0,
						new ItemStack(SummyReliquary.SOURCE_OF_SINS.get())));
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.REDEEMED);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SummyReliquary.REDEMPTION.get(), 2));

		PlayerInteractEvent.RightClickItem early =
				new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
		com.summy.reliquary.effect.SinRedemption.tryRedeem(player, early);
		log("仅在傲慢已赎罪时转化：魂印栏=" + soulSealContent(player) + "（应仍为七罪之源）");

		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.REDEEMED);
		}
		RevelationTracker.sync(player);
		PlayerInteractEvent.RightClickItem ready =
				new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
		com.summy.reliquary.effect.SinRedemption.tryRedeem(player, ready);
		log("七罪全赎清后转化：魂印栏=" + soulSealContent(player)
				+ "（应为美德）、赎罪数量=" + player.getMainHandItem().getCount() + "（应为 1）");
	}

	private static String soulSealContent(ServerPlayer player) {
		return CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getStacksHandler(ReliquarySlots.SOUL_SEAL)
						.map(stacks -> stacks.getStacks().getStackInSlot(0).getItem().toString())
						.orElse("无魂印栏"))
				.orElse("无 Curios 数据");
	}

	/** 合成拦截：未赎清时产物被收走并退回材料，赎清后放行 */
	private static void testRecipeGate(ServerPlayer player) {
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.ACTIVATED);

		ItemStack crafted = new ItemStack(SummyReliquary.REDEMPTION.get(), 1);
		player.getInventory().add(crafted.copy());
		int before = countRedemption(player);
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player, crafted,
				new net.minecraft.world.SimpleContainer(9)));
		int afterBlocked = countRedemption(player);
		log("配方拦截（七罪未清）：背包里的赎罪 " + before + " → " + afterBlocked + "（应被收走，且退回 8 个材料）");

		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.REDEEMED);
		}
		ItemStack crafted2 = new ItemStack(SummyReliquary.REDEMPTION.get(), 1);
		player.getInventory().add(crafted2.copy());
		int before2 = countRedemption(player);
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player, crafted2,
				new net.minecraft.world.SimpleContainer(9)));
		int afterAllowed = countRedemption(player);
		log("配方拦截（七罪已清）：背包里的赎罪 " + before2 + " → " + afterAllowed + "（应保持不变）");
	}

	private static int countRedemption(ServerPlayer player) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(SummyReliquary.REDEMPTION.get())) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** 战斗手感：无敌帧、攻速上限、生命上限夹紧、受击掉血 */
	private static void testCombat(ServerPlayer player) {
		player.setInvulnerable(false);

		// 1) 无敌帧（1.6.3 起是"原版判定 + 两档窗口"，详细断言见 checkIframeWindows）
		com.summy.reliquary.effect.CombatTuning.clear();
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 1.0F);
		log("受伤无敌帧（1.6.3 原版判定 + 两档窗口）：一般窗口="
				+ com.summy.reliquary.config.ReliquaryConfig.invulnerabilityTicks() + " tick / 帧伤窗口="
				+ com.summy.reliquary.config.ReliquaryConfig.invulnerabilityTicksFrameDamage()
				+ " tick、本次命中后字段=" + player.invulnerableTime + "（应为 窗口+10）");

		// 2) 受击掉血
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.CombatTuning.clear();
		float healthBefore = player.getHealth();
		player.hurt(player.damageSources().generic(), 4.0F);
		log(String.format("受击掉血检查：%.1f → %.1f（应减少）", healthBefore, player.getHealth()));

		// 3) 攻速上限
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.STAR_OF_BETHLEHEM.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log("装上伯列恒之星后攻击速度=" + player.getAttributeValue(Attributes.ATTACK_SPEED)
				+ "（上限 " + com.summy.reliquary.config.ReliquaryConfig.maxAttackSpeed() + "）");

		// 4) 生命上限下降后立刻夹紧
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0,
					new ItemStack(SummyReliquary.THE_BODY.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 1, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		float maxWithBody = player.getMaxHealth();
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log(String.format("摘下肉体：上限 %.0f → %.0f，当前血量 %.1f（应已夹回上限）",
				maxWithBody, player.getMaxHealth(), player.getHealth()));
	}

	// ==================== 1.2.1：魂心独立池（1.6.2 重写） ====================

	/** 装回灵魂并补满独立魂心池，作为魂心测试的起点；同时证明"不再写原版吸收值" */
	private static void prepareSoulShieldTest(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		player.setAbsorptionAmount(0.0F);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, new ItemStack(SummyReliquary.THE_BODY.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 1, new ItemStack(SummyReliquary.THE_MIND.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2, new ItemStack(SummyReliquary.THE_SOUL.get()));
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		com.summy.reliquary.effect.SoulShield.onEquipped(player);
		com.summy.reliquary.effect.CombatTuning.clear();
		log(String.format("魂心池起点：池子=%.1f（应为 6 = 灵魂 3 心 × 2）、原版吸收=%.1f（应为 0 = 不再写进吸收池）、魂心属性=%.1f",
				com.summy.reliquary.effect.SoulShield.points(player),
				player.getAbsorptionAmount(),
				player.getAttributeValue(ReliquaryAttributes.SOUL_HEARTS.get())));
	}

	/** 连续采样：池子不该自己掉；同时模拟"别的模组把吸收值清零" */
	private static void checkSoulShieldStable(ServerPlayer player) {
		player.setAbsorptionAmount(0.0F);
		log(String.format("魂心池稳定性（1 秒后）：池子=%.1f（应为 6）、已把原版吸收清零",
				com.summy.reliquary.effect.SoulShield.points(player)));
	}

	/** 原版吸收被外部清零不影响魂心池（ER 兼容性） */
	private static void checkSoulShieldAfterWipe(ServerPlayer player) {
		log(String.format("外部清零原版吸收后：池子=%.1f（应仍为 6，独立池互不干扰）、吸收=%.1f（应为 0）",
				com.summy.reliquary.effect.SoulShield.points(player), player.getAbsorptionAmount()));
	}

	/** 受伤 2 点：应由魂心池承担、不动生命 */
	private static void testSoulShieldDamage(ServerPlayer player) {
		soulHealthBefore = player.getHealth();
		com.summy.reliquary.effect.CombatTuning.clear();
		player.hurt(player.damageSources().generic(), 2.0F);
		log(String.format("受伤 2 点：池子=%.1f（应为 4）、生命 %.1f（应不变）",
				com.summy.reliquary.effect.SoulShield.points(player), player.getHealth()));
	}

	/** 受伤后池子应保持 4（不能每 tick 回满） */
	private static void checkSoulShieldAfterDamage(ServerPlayer player) {
		log(String.format("受伤后（1 秒）池子=%.1f（约 4，说明不会每 tick 回满）、生命 %.1f → %.1f（不得因这次伤害下降）",
				com.summy.reliquary.effect.SoulShield.points(player), soulHealthBefore, player.getHealth()));
	}

	/** 把补满倒计时提前到 1 秒，验证"周期补满（只补缺额）"这条路径 */
	private static void testSoulShieldRefresh(ServerPlayer player) {
		com.summy.reliquary.effect.SoulShield.setRefreshTimer(player, 1);
		log("已把魂心池补满倒计时改成 1 秒（仅自检用），等待周期补满");
	}

	private static void checkSoulShieldRefreshed(ServerPlayer player) {
		log(String.format("周期补满检查：池子=%.1f（应回到 6）",
				com.summy.reliquary.effect.SoulShield.points(player)));
	}

	/** 卸下灵魂：魂心池清空，且不会从原版吸收里"偷"份额 */
	private static void testSoulShieldUnequip(ServerPlayer player) {
		player.setAbsorptionAmount(20.0F);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2, ItemStack.EMPTY));
		com.summy.reliquary.effect.SoulShield.onUnequipped(player);
		log("已卸下灵魂，等待魂心池被清空");
	}

	private static void checkSoulShieldUnequipped(ServerPlayer player) {
		log(String.format("卸下后检查：池子=%.1f（应为 0）、原版吸收=%.1f（应为 20，没有被动过）",
				com.summy.reliquary.effect.SoulShield.points(player), player.getAbsorptionAmount()));
	}

	/** 三件套提示去重：Shift 描述里不该再出现套装文本，套装效果只由 Alt 行呈现 */
	private static void checkSetTooltips(Minecraft client) {
		String[] setWords = {"套装", "免疫投射物", "发光目标", "受到伤害",
				"with the full set", "projectile", "glowing", "damage reduction"};
		for (String key : new String[]{"item.summy-reliquary.the_body.desc",
				"item.summy-reliquary.the_mind.desc", "item.summy-reliquary.the_soul.desc"}) {
			String desc = net.minecraft.network.chat.Component.translatable(key).getString();
			String hit = null;
			for (String word : setWords) {
				if (desc.contains(word)) {
					hit = word;
					break;
				}
			}
			log("套装提示去重 " + key + " = 「" + desc + "」"
					+ (hit == null ? "（通过：未重复套装文本）" : "（警告：仍含「" + hit + "」）"));
		}
		log("套装块文案：");
		log("   需求行「" + net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.set.requirement",
						net.minecraft.network.chat.Component.literal("肉体 · 思想 · 灵魂"))
				.getString() + "」");
		log("   Alt 行「" + net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.set.alt_hint").getString() + "」");
		for (String key : new String[]{"item.summy-reliquary.the_body.set_desc",
				"item.summy-reliquary.the_mind.set_desc", "item.summy-reliquary.the_soul.set_desc"}) {
			log("   " + key + " = 「"
					+ net.minecraft.network.chat.Component.translatable(key).getString() + "」");
		}

		// 套装需求行里三个名字的着色（已佩戴=绿 #55FF55、未佩戴=灰 #7F7F7F）：
		// 这条同时验证「本地玩家获取」在客户端仍然有效（此前改用 DistExecutor 延迟加载客户端类）
		List<net.minecraft.network.chat.Component> lines = new ItemStack(SummyReliquary.THE_SOUL.get())
				.getTooltipLines(client.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		for (net.minecraft.network.chat.Component line : lines) {
			if (!line.getString().startsWith("当[")) {
				continue;
			}
			StringBuilder builder = new StringBuilder();
			// 译文参数（三件名字）在 TranslatableContents 里，不属于 siblings，所以用 toFlatList 展平后逐段取色
			for (net.minecraft.network.chat.Component part : line.toFlatList()) {
				net.minecraft.network.chat.TextColor color = part.getStyle().getColor();
				builder.append('「').append(part.getString()).append('=')
						.append(color == null ? "默认" : String.format("#%06X", color.getValue()))
						.append('」');
			}
			log("套装需求行着色（已佩戴应 #55FF55、未佩戴应 #7F7F7F）：" + builder);
		}
		log("本地玩家是否可读 = " + (com.summy.reliquary.item.ReliquaryTooltips.localPlayer() != null));
	}

	// ==================== 1.3.0：护盾 → 魂心 → 黑心 的结算顺序（1.6.2 重写） ====================

	/** 搭场景：原版吸收（护盾）6 + 魂心池 6 + 黑心池 4 */
	private static void prepareAdditiveShieldTest(ServerPlayer player) {
		// 黑心池要求"佩戴契约"，而契约属于恶魔线（灵台只剩 1 格 → 灵魂戴不上）。
		// 所以这里走恶魔线 + 测试用强行戴上天启（绕过天使门槛），魂心池由天启的 2 心提供。
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		// 置"曾签约"：否则每秒的自愈会把天使标记补回来、顺手清掉恶魔标记（测试会变成天使线）
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		// 注意顺序：先把阶段设成恶魔线再校正格数，否则会在"七罪阶段"把启示之座压成 0 格
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		// 1.7.2：签约/补发契约会把"过线档"的天使线饰品（终末天启 / 神性 / 圣心）**摘到背包**，
		// 所以"强行戴上天启"必须放在 grant 之后 —— 否则刚戴上就被摘走，魂心池会变成 0。
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			// 只留天启，避免其它饰品干扰数字
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
					new ItemStack(SummyReliquary.FINAL_REVELATION.get()));
		});
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		com.summy.reliquary.effect.SoulShield.onEquipped(player);
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(6.0F);
		com.summy.reliquary.effect.CombatTuning.clear();
		log(String.format("三池场景：原版吸收=%.1f（模拟护盾 6）、魂心池=%.1f（应为 4 = 天启 2 心）、黑心池=%.1f（应为 4）",
				player.getAbsorptionAmount(), com.summy.reliquary.effect.SoulShield.points(player),
				com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player)));
	}

	private static void checkAdditiveShield(ServerPlayer player) {
		log(String.format("三池（1 秒后）：原版吸收=%.1f（应仍为 6）、魂心池=%.1f（应为 4）、黑心池=%.1f（应为 4）",
				player.getAbsorptionAmount(), com.summy.reliquary.effect.SoulShield.points(player),
				com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player)));
	}

	/**
	 * 结算顺序（1.6.2）：**先原版吸收 → 再魂心池 → 最后黑心**。
	 *
	 * <p>分两段验证：① 真实受伤时原版吸收先被扣（魂心不动）；② "原版吸收已结算完"的剩余伤害
	 * 交给魂心 → 黑心 依次承担。
	 */
	/**
	 * 让玩家回到"生存 + 非无敌 + 裸装 + 无药水"的干净状态（1.6.4）。
	 *
	 * <p>池子 / 免死 / 死亡拦截的用例都改成走**真实命中管线**（不再直接构造事件），
	 * 护甲、抗性、免死守卫与无敌帧都会把数字搅乱，所以每次断言前先清干净。
	 */
	private static void prepareBarePlayer(ServerPlayer player) {
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		player.setInvulnerable(false);
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			player.setItemSlot(slot, ItemStack.EMPTY);
		}
		player.removeAllEffects();
		com.summy.reliquary.effect.CombatTuning.clear();
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		com.summy.reliquary.effect.DeathImmunity.reset();
		player.invulnerableTime = 0;
		player.setHealth(player.getMaxHealth());
	}

	private static void testAdditiveShieldDamage(ServerPlayer player) {
		shieldsHealthBefore = player.getHealth();
		prepareBarePlayer(player);
		// ① 原版吸收（护盾 6）先被扣：受 5 点时魂心 / 黑心都不该动
		player.setAbsorptionAmount(6.0F);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 4.0D);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		com.summy.reliquary.effect.CombatTuning.clear();
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 5.0F);
		double absorptionAfter = player.getAbsorptionAmount();
		double soulAfterShield = com.summy.reliquary.effect.SoulShield.points(player);
		double blackAfterShield = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		// ② 护盾清空后再受 8 点：魂心 4 + 黑心 4 全吃，红血不掉（走真实的 hurt 管线）
		float healthBeforeSecond = player.getHealth();
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 4.0D);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		com.summy.reliquary.effect.CombatTuning.clear();
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 8.0F);
		log(String.format("结算顺序（1.6.5 = 命中前扣池并临时并入原版吸收 + 事后对账）：受 5 点时原版吸收 6 → %.1f（应 1 = 只扣护盾）、"
						+ "魂心池仍 %.1f（应 4）、黑心池仍 %.1f（应 4）；护盾清空后受 8 点 → 魂心池 %.1f（应 0）、"
						+ "黑心池 %.1f（应 0）、生命 %.1f（应 %.1f = 红血不掉）",
				absorptionAfter, soulAfterShield, blackAfterShield,
				com.summy.reliquary.effect.SoulShield.points(player),
				com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player), player.getHealth(),
				healthBeforeSecond));
	}

	/** 魂心完全破碎：击退 + 无敌一次，且不会重复触发 */
	private static void testShieldExhaustedAndNoSteal(ServerPlayer player) {
		java.util.List<net.minecraft.world.entity.monster.Zombie> zombies = new java.util.ArrayList<>();
		for (int index = 0; index < 2; index++) {
			net.minecraft.world.entity.monster.Zombie zombie = spawnZombieAtSide(player, 3.0D);
			if (zombie != null) {
				zombie.setNoAi(true);
				zombies.add(zombie);
			}
		}
		int shatterBefore = com.summy.reliquary.effect.SoulShield.shatterCount();
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);
		// 把周期补满推远，避免"破碎后又被补满"干扰断言
		com.summy.reliquary.effect.SoulShield.setRefreshTimer(player, 999);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 2.0D);
		// 1.6.4：池子扣减由 DamagePools 在真实命中里驱动，这里直接驱动"扣满即破碎"的入口
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		com.summy.reliquary.effect.SoulShield.consume(player, 2.0D);
		boolean knocked = zombies.stream().anyMatch(zombie -> zombie.getDeltaMovement().horizontalDistanceSqr() > 1.0E-3D);
		boolean guarded = com.summy.reliquary.effect.SoulShield.isGuarded(player);
		int shatterAfter = com.summy.reliquary.effect.SoulShield.shatterCount();
		// 无敌期内再受击应被直接取消
		var guardedHit = new net.minecraftforge.event.entity.living.LivingHurtEvent(player,
				player.damageSources().generic(), 5.0F);
		com.summy.reliquary.effect.SoulShield.onHurt(guardedHit);
		boolean guardBlocks = guardedHit.isCanceled() && guardedHit.getAmount() == 0.0F;
		zombies.forEach(net.minecraft.world.entity.Entity::discard);
		// 只清掉"破碎无敌"，不要调用 forget（那会让下一秒的 tick 认为"刚装备"从而补满池子）
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		log("魂心破碎：触发次数 " + shatterBefore + " → " + shatterAfter + "（应 +1）、7 格内敌人被击退=" + knocked
				+ "（应 true）、玩家进入无敌=" + guarded + "（应 true）、无敌期内伤害被取消=" + guardBlocks
				+ "（应 true）");
	}

	private static void checkShieldExhausted(ServerPlayer player) {
		log(String.format("破碎后收尾：魂心池=%.1f（应为 0）、黑心池=%.1f（应为 0）、生命 %.1f（应不减）",
				com.summy.reliquary.effect.SoulShield.points(player),
				com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player), player.getHealth()));
	}

	/** 装备终末天启 + 灵魂：魂心 3+2=5、攻速加成、创造飞行（速度减半） */
	private static void prepareFinalRevelation(ServerPlayer player) {
		ensureAngelLine(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2, new ItemStack(SummyReliquary.THE_SOUL.get()));
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, new ItemStack(SummyReliquary.FINAL_REVELATION.get()));
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		com.summy.reliquary.effect.SoulShield.onEquipped(player);
		log(String.format("终末天启：魂心=%.1f（应为 5 = 灵魂 3 + 天启 2）、黄血目标=%.1f（应为 10）、"
						+ "攻速=%.2f、可飞=%s、飞行速度=%.3f（应为 0.025）",
				player.getAttributeValue(ReliquaryAttributes.SOUL_HEARTS.get()),
				com.summy.reliquary.effect.SoulShield.capacityFor(player),
				player.getAttributeValue(Attributes.ATTACK_SPEED),
				player.getAbilities().mayfly, player.getAbilities().getFlyingSpeed()));
	}

	/** 光柱：圆柱内命中、圆柱外/背后/超距不命中；抗性无效、先扣黄血；冷却内二次发射被拒 */
	private static void testRevelationBeam(ServerPlayer player) {
		// 设成夜晚，避免僵尸日晒掉血干扰数字
		player.serverLevel().setDayTime(18000L);
		player.setYRot(0.0F);
		player.setXRot(0.0F);
		player.setInvulnerable(false);
		player.invulnerableTime = 0;
		beamCasterHealthBefore = player.getHealth();
		Vec3 base = player.position();
		// 4 号目标：正前 6 格，会在照射中途被挪走（验证"离开光束就停止结算"）
		double[][] offsets = {{0.0D, 10.0D}, {3.0D, 10.0D}, {0.0D, -5.0D}, {0.0D, 20.0D}, {0.0D, 6.0D}};
		for (int index = 0; index < offsets.length; index++) {
			Zombie zombie = new Zombie(player.serverLevel());
			// 与玩家同高 + 关闭重力：保证它们确实处在光束轴线上（地形起伏不会把目标"抬出"光束）
			double x = base.x + offsets[index][0];
			double z = base.z + offsets[index][1];
			// 清掉落点处可能挡住它们的方块，避免"卡在方块里被窒息"这种无关伤害
			BlockPos feet = BlockPos.containing(x, base.y, z);
			for (int dy = 0; dy <= 1; dy++) {
				BlockPos clear = feet.above(dy);
				if (!player.serverLevel().getBlockState(clear).isAir()) {
					player.serverLevel().setBlockAndUpdate(clear,
							net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
				}
			}
			zombie.setNoGravity(true);
			zombie.moveTo(x, base.y, z);
			zombie.setNoAi(true);
			zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
			zombie.setHealth(200.0F);
			zombie.setInvulnerable(false);
			player.serverLevel().addFreshEntity(zombie);
			beamZombies[index] = zombie.getUUID();
			// 记录真实初始血量（不能硬编码，否则会出现"假伤害"）
			beamHealthBefore[index] = zombie.getHealth();
		}
		Zombie first = findBeamZombie(player, 0);
		if (first != null) {
			first.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 100000, 4, false, false));
			// 再穿一件钻石胸甲：护甲 + 抗性 + 10 点黄血同时存在，用来验证"真实伤害"仍然只吃 21
			first.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			first.setAbsorptionAmount(10.0F);
		}
		beamFirstFire = com.summy.reliquary.effect.RevelationBeam.fire(player);
		beamSecondFire = com.summy.reliquary.effect.RevelationBeam.fire(player);
		log("光柱已发射：首次=" + beamFirstFire + "（应为 true）、冷却内二次=" + beamSecondFire + "（应为 false）");
	}

	/** 照射中途把 4 号目标挪出光束：之后不应再被结算 */
	private static void moveBeamTargetAway(ServerPlayer player) {
		Zombie zombie = findBeamZombie(player, 4);
		if (zombie == null) {
			log("中途离开用例：4 号目标已不存在");
			return;
		}
		zombie.teleportTo(player.getX() + 30.0D, player.getY() + 8.0D, player.getZ() + 6.0D);
		log(String.format("已把 4 号目标挪出光束（当前生命=%.1f，接下来不应再受伤）", zombie.getHealth()));
	}

	private static void checkRevelationBeam(ServerPlayer player) {
		for (int index = 0; index < beamZombies.length; index++) {
			Zombie zombie = findBeamZombie(player, index);
			if (zombie == null) {
				log("光柱目标 " + index + "：已被击杀（命中）");
				continue;
			}
			log(String.format("光柱目标 %d：生命 %.1f → %.1f（伤害 %.1f；期望 0 号=95.0 血且黄血掉 10 = 共 105，"
							+ "4 号≈49~56（中途离开），1/2/3=0.0）、黄血 %.1f、抗性=%s",
					index, beamHealthBefore[index], zombie.getHealth(),
					beamHealthBefore[index] - zombie.getHealth(), zombie.getAbsorptionAmount(),
					zombie.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE)));
		}
		log(String.format("光柱自检：施法者未受伤=%s、剩余冷却=%d 秒",
				Math.abs(player.getHealth() - beamCasterHealthBefore) < 1.0E-3F,
				com.summy.reliquary.effect.RevelationBeam.cooldownSecondsLeft(player,
						com.summy.reliquary.effect.RevelationBeam.BeamKind.HOLY)));
	}

	private static Zombie findBeamZombie(ServerPlayer player, int index) {
		if (beamZombies[index] == null) {
			return null;
		}
		for (Zombie zombie : player.serverLevel().getEntitiesOfClass(Zombie.class,
				player.getBoundingBox().inflate(64.0D))) {
			if (zombie.getUUID().equals(beamZombies[index])) {
				return zombie;
			}
		}
		return null;
	}

	/** 转化准备：装回伯列恒之星、设为夜晚、传送到揭示坐标（露天） */
	private static void prepareAscension(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.REVELATION, 0, new ItemStack(SummyReliquary.STAR_OF_BETHLEHEM.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		ServerLevel level = player.serverLevel();
		int x = RevelationTracker.revealX(player);
		int z = RevelationTracker.revealZ(player);
		level.setDayTime(18000L);
		// 在容差范围内找一个"头顶露天"的落点（揭示坐标本身可能正好在树荫下或水下）
		BlockPos stand = null;
		int tolerance = com.summy.reliquary.config.ReliquaryConfig.transformRadius();
		for (int ring = 0; ring <= tolerance && stand == null; ring++) {
			for (int dx = -ring; dx <= ring && stand == null; dx++) {
				for (int dz = -ring; dz <= ring && stand == null; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					int cx = x + dx;
					int cz = z + dz;
					level.getChunk(cx >> 4, cz >> 4);
					BlockPos candidate = level
							.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(cx, 0, cz))
							.above();
					if (level.canSeeSky(candidate)) {
						stand = candidate;
					}
				}
			}
		}
		if (stand == null) {
			stand = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).above();
		}
		player.teleportTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
		log(String.format("转化准备：目标(%d,%d)、落点(%d,%d,%d)、时间=%d、露天=%s",
				x, z, stand.getX(), stand.getY(), stand.getZ(), level.getDayTime(),
				level.canSeeSky(player.blockPosition())));
	}

	private static void checkAscension(ServerPlayer player) {
		String content = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getStacksHandler(ReliquarySlots.REVELATION)
						.map(stacks -> stacks.getStacks().getStackInSlot(0).getItem().toString())
						.orElse("无启示之座栏位"))
				.orElse("无 Curios 数据");
		log("转化结果：启示之座=" + content + "（期望 final_revelation）；魂心="
				+ player.getAttributeValue(ReliquaryAttributes.SOUL_HEARTS.get())
				+ "、可飞=" + player.getAbilities().mayfly);
	}

	// ==================== 1.3.3：首次发放 / 右键规则 / 创造飞行 / 蓄力 / 进度 ====================

	/** 首次发放：清掉标记后应发一次（不佩戴），再次调用不重复 */
	private static void checkStarterKit(ServerPlayer player) {
		// 自检跑到这里时背包早就塞满了，若不先清空，发放的物品会掉在地上导致计数为 0
		player.getInventory().clearContent();
		com.summy.reliquary.effect.StarterKit.resetGranted(player);
		int sinsBefore = countItem(player, SummyReliquary.SOURCE_OF_SINS.get());
		int haloBefore = countItem(player, SummyReliquary.THE_HALO.get());
		com.summy.reliquary.effect.StarterKit.grantIfFirst(player);
		int sinsAfter = countItem(player, SummyReliquary.SOURCE_OF_SINS.get());
		int haloAfter = countItem(player, SummyReliquary.THE_HALO.get());
		boolean equipped = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.isEquipped(SummyReliquary.SOURCE_OF_SINS.get())).orElse(false);
		log(String.format("首次发放：七罪之源 %d → %d、光环 %d → %d（各 +1）、是否自动佩戴=%s（默认配置为 false）",
				sinsBefore, sinsAfter, haloBefore, haloAfter, equipped));
		com.summy.reliquary.effect.StarterKit.grantIfFirst(player);
		log(String.format("重复发放检查：七罪之源 %d、光环 %d（应与上次相同）",
				countItem(player, SummyReliquary.SOURCE_OF_SINS.get()),
				countItem(player, SummyReliquary.THE_HALO.get())));
	}

	/**
	 * 1.6.6：死亡复活后不再重发「七罪之源 + 光环」。
	 *
	 * <p>根因：只发一次的标记以前写在 ForgeData **顶层**，而复活时新实体只带走 Forge 的
	 * {@code PlayerPersisted} 子标签（Kilt 连这个都没实现）→ 标记整片丢失 → 下次登录被当成
	 * "从没发过"，于是又发一份。现在标记写在模组根标签里（会被 {@code onPlayerClone} 复制），
	 * 并且额外加了"背包 / 饰品里已经有就不再补发"的保险。
	 */
	private static void checkStarterKitRespawn(ServerPlayer player) {
		player.getInventory().clearContent();
		com.summy.reliquary.effect.StarterKit.resetGranted(player);
		com.summy.reliquary.effect.StarterKit.grantIfFirst(player);
		int grantedSins = countItem(player, SummyReliquary.SOURCE_OF_SINS.get());
		int grantedHalo = countItem(player, SummyReliquary.THE_HALO.get());

		// ① 模拟"复活丢掉顶层标记"（1.6.5 的病灶）：根标签里那份还在 → 不应该再发
		player.getPersistentData().remove("start_granted");
		boolean stillGranted = com.summy.reliquary.effect.StarterKit.alreadyGranted(player);
		com.summy.reliquary.effect.StarterKit.grantIfFirst(player);
		int afterRespawnSins = countItem(player, SummyReliquary.SOURCE_OF_SINS.get());
		int afterRespawnHalo = countItem(player, SummyReliquary.THE_HALO.get());

		// ② 兼容旧存档：顶层旧键还在、根标签被清掉时也不能补发
		com.summy.reliquary.effect.StarterKit.resetGranted(player);
		player.getPersistentData().putBoolean("start_granted", true);
		boolean legacySuppresses = com.summy.reliquary.effect.StarterKit.alreadyGranted(player);
		com.summy.reliquary.effect.StarterKit.grantIfFirst(player);
		int afterLegacySins = countItem(player, SummyReliquary.SOURCE_OF_SINS.get());

		// ③ 保险：标记被清掉、但背包里还带着光环 → 只落标记、不重发
		com.summy.reliquary.effect.StarterKit.resetGranted(player);
		int beforeDedupe = countItem(player, SummyReliquary.THE_HALO.get());
		com.summy.reliquary.effect.StarterKit.grantIfFirst(player);
		int afterDedupe = countItem(player, SummyReliquary.THE_HALO.get());

		log("1.6.6 复活不重发：首次发放 七罪之源×" + grantedSins + " / 光环×" + grantedHalo + "（应各 1）；"
				+ "丢掉顶层标记后仍视为已发放=" + stillGranted + "（应 true）、再调一次后 七罪之源×" + afterRespawnSins
				+ " / 光环×" + afterRespawnHalo + "（应仍是 1 / 1）；旧顶层键仍能抑制=" + legacySuppresses
				+ "（应 true）、调用后 七罪之源×" + afterLegacySins + "（应仍 1）；背包里已有物品就不补发="
				+ (afterDedupe == beforeDedupe) + "（应 true）");
		com.summy.reliquary.effect.StarterKit.resetGranted(player);
		player.getInventory().clearContent();
	}

	/** 右键快捷佩戴：七罪之源禁止、光环允许 */
	private static void checkRightClickRules(ServerPlayer player) {
		var sins = (top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.SOURCE_OF_SINS.get();
		var halo = (top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.THE_HALO.get();
		boolean sinsAllowed = sins.canEquipFromUse(
				new SlotContext(ReliquarySlots.SOUL_SEAL, player, 0, false, true),
				new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
		boolean haloAllowed = halo.canEquipFromUse(
				new SlotContext(ReliquarySlots.HALO, player, 0, false, true),
				new ItemStack(SummyReliquary.THE_HALO.get()));
		log("右键快捷佩戴：七罪之源=" + sinsAllowed + "（应为 false）、光环=" + haloAllowed + "（应为 true）");
	}

	/** 创造模式的飞行速度不应被半速影响 */
	private static void checkCreativeFlight(ServerPlayer player) {
		var previousMode = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		float creativeSpeed = player.getAbilities().getFlyingSpeed();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		float survivalSpeed = player.getAbilities().getFlyingSpeed();
		player.setGameMode(previousMode);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log(String.format("飞行速度：创造模式=%.3f（应为 0.050，不受影响）、生存=%.3f（应为 0.025）",
				creativeSpeed, survivalSpeed));
	}

	/** 蓄力状态：通知开始/结束都应被服务端正确记录 */
	private static void checkChargingState(ServerPlayer player) {
		com.summy.reliquary.effect.RevelationBeam.setCharging(player, true);
		boolean started = com.summy.reliquary.effect.RevelationBeam.isCharging(player);
		com.summy.reliquary.effect.RevelationBeam.setCharging(player, false);
		boolean stopped = com.summy.reliquary.effect.RevelationBeam.isCharging(player);
		log("蓄力状态：开始后=" + started + "（应为 true）、结束后=" + stopped + "（应为 false）");
	}

	/** 5 个进度：未触发时未完成，触发后全部完成 */
	private static void checkAdvancements(ServerPlayer player) {
		String[] ids = {"sinner", "unforgivable", "pure", "trinity", "revelation"};
		String[] events = {
				// 1.7.1：死事件 sins_equipped 已删除；「有罪之人」真正听的是 sins_obtained
				com.summy.reliquary.advancement.ReliquaryAdvancements.SINS_OBTAINED,
				com.summy.reliquary.advancement.ReliquaryAdvancements.ALL_SINS_ACTIVE,
				com.summy.reliquary.advancement.ReliquaryAdvancements.REDEEMED_TO_VIRTUES,
				com.summy.reliquary.advancement.ReliquaryAdvancements.SPIRIT_ALTAR_FULL_SET,
				com.summy.reliquary.advancement.ReliquaryAdvancements.REVELATION_ASCENDED
		};
		log("进度检查（触发前）：启示=" + advancementDone(player, "revelation")
				+ "（转化自检在更早的 tick 已跑过，这里通常已是 true；下面逐个触发再验证全部为 true）");
		for (int index = 0; index < ids.length; index++) {
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player, events[index]);
			log("   触发 " + ids[index] + "（" + events[index] + "）→ 完成="
					+ advancementDone(player, ids[index]));
		}
	}

	private static boolean advancementDone(ServerPlayer player, String id) {
		var server = player.getServer();
		if (server == null) {
			return false;
		}
		var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(id));
		return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
	}

	private static int countItem(ServerPlayer player, Item item) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** 打印配置化的提示文本，确认数值来自配置文件而不是写死的 */
	private static void checkConfigTexts() {
		log("配置化提示文本：");
		log("   思想=" + net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.the_mind.desc",
				com.summy.reliquary.config.ReliquaryConfig.glowRadius()).getString());
		log("   灵魂=" + net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.the_soul.desc",
				(int) com.summy.reliquary.config.ReliquaryConfig.soulHearts(),
				com.summy.reliquary.config.ReliquaryConfig.absorptionPerSoulHeart(),
				com.summy.reliquary.config.ReliquaryConfig.soulRefreshSeconds()).getString());
		log("   终末天启.1=" + net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.final_revelation.desc.1",
				(int) com.summy.reliquary.config.ReliquaryConfig.finalSoulHearts()).getString());
		log("   肉体套装=" + net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.the_body.set_desc",
				com.summy.reliquary.config.ReliquaryConfig.bodySetDamageReductionPercent()).getString());
	}

	// ==================== 1.4.0：七罪效果 / 免死 / 蓄力粒子 / 成就描述 ====================

	/** 七罪测试的公共准备：清空背包、戴上七罪之源与灵台三件套、复位状态与计数 */
	private static void prepareSinEffects(ServerPlayer player) {
		player.getInventory().clearContent();
		log("===== 七罪效果自检 =====");
		com.summy.reliquary.sin.SinEffects.clearAllEnvyTargets();
		com.summy.reliquary.sin.SinEffects.resetCounters();
		com.summy.reliquary.effect.DeathImmunity.reset();
		resetSinState(player);
		// 戴上七罪之源与灵台三件套
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, new ItemStack(SummyReliquary.THE_BODY.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 1, new ItemStack(SummyReliquary.THE_MIND.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2, new ItemStack(SummyReliquary.THE_SOUL.get()));
		});
		player.setHealth(player.getMaxHealth());
		player.removeAllEffects();
		log("已清空背包、戴上七罪之源与灵台三件套，七罪状态与进度复位");
	}

	/** 把七罪状态与进度全部复位（自检每一轮都要从零开始） */
	private static void resetSinState(ServerPlayer player) {
		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.UNACTIVATED);
		}
		com.summy.reliquary.sin.SinProgress.reset(player);
	}

	/** 自检结束：清空背包并复位七罪，避免影响下一次运行 */
	private static void cleanupAfterTests(ServerPlayer player) {
		player.getInventory().clearContent();
		com.summy.reliquary.sin.SinEffects.clearAllEnvyTargets();
		com.summy.reliquary.effect.DeathImmunity.reset();
		resetSinState(player);
		player.removeAllEffects();
		log("自检收尾：已清空背包并复位七罪状态与进度");
	}

	/** 傲慢：击杀 10 个中立/友善生物后触发 */
	private static void checkPrideTrigger(ServerPlayer player) {
		net.minecraft.world.entity.animal.Chicken chicken =
				EntityType.CHICKEN.create(player.serverLevel());
		if (chicken == null) {
			log("傲慢：无法生成测试鸡");
			return;
		}
		chicken.moveTo(player.getX(), player.getY(), player.getZ());
		for (int index = 0; index < com.summy.reliquary.config.ReliquaryConfig.prideKillRequired(); index++) {
			com.summy.reliquary.sin.SinEffects.onKill(player, chicken);
		}
		chicken.discard();
		log("傲慢：模拟击杀 " + com.summy.reliquary.config.ReliquaryConfig.prideKillRequired()
				+ " 个中立生物 → 状态=" + SinManager.state(player, Sin.PRIDE) + "（应为 ACTIVATED）");
	}

	/** 傲慢：受伤 +50%，赎罪后移除 */
	private static void checkPrideDamage(ServerPlayer player) {
		// 只留傲慢在生效，保证数字干净
		resetSinState(player);
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.ACTIVATED);
		Zombie zombie = spawnTestZombie(player, 5.0D);
		zombie.setHealth(zombie.getMaxHealth() * 0.5F);
		float outgoing = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(
				player, zombie, 10.0F);
		float incoming = com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 10.0F);
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.REDEEMED);
		float afterRedeem = com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 10.0F);
		log(String.format("傲慢：对半血目标 10 → %.1f（应为 15）、自身受伤 10 → %.1f（应为 15）；"
				+ "赎罪后受伤 10 → %.1f（应回到 10）", outgoing, incoming, afterRedeem));
		zombie.discard();
	}

	/** 嫉妒：准备一个穿甲目标与一只狼 */
	private static void prepareEnvy(ServerPlayer player) {
		// 目标改到 checkEnvy 的同一 tick 里生成：上一轮实测前方 4 格容易被地形卡住窒息，
		// 20 tick 之后就"不算活着"，观察判定自然扫不到。
		log("嫉妒：准备（穿甲僵尸与狼会在检查的同一 tick 内生成）");
	}

	/** 嫉妒：观察到穿甲目标即触发；8 格内有战斗 AI 的中立生物被强制敌对 */
	private static void checkEnvy(ServerPlayer player) {
		resetSinState(player);
		// 同一 tick 内放置目标，避免地形伤害把它们弄死
		Zombie armored = spawnTestZombie(player, 4.0D);
		if (armored != null) {
			armored.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			glowZombieId = armored.getUUID();
		}
		net.minecraft.world.entity.animal.Wolf wolf = EntityType.WOLF.create(player.serverLevel());
		if (wolf != null) {
			wolf.moveTo(player.getX() + 3.0D, player.getY(), player.getZ());
			player.serverLevel().addFreshEntity(wolf);
			envyWolfId = wolf.getUUID();
		}
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		log("嫉妒：观察到穿甲目标 → 状态=" + SinManager.state(player, Sin.ENVY) + "（应为 ACTIVATED）");
		LivingEntity target = findEntity(player, glowZombieId);
		if (target != null) {
			float bonus = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, target, 10.0F);
			log(String.format("嫉妒：对满足条件目标 10 → %.1f（应为 15，注意同时叠加了傲慢则更高）", bonus));
		}
		// 仇恨维护：自检直接强制刷新一次，不等 20 tick 的间隔
		// 注意 canAttack() 会因为"自检用的无敌"而返回 false，这里临时关掉再打开
		player.setInvulnerable(false);
		com.summy.reliquary.sin.SinEffects.forceEnvyMaintenance(player);
		log("嫉妒：强制维护后敌对表中立生物数量=" + com.summy.reliquary.sin.SinEffects.envyHostileCount());
		reportEnvyWolf(player, "嫉妒");
		player.setInvulnerable(true);
	}

	/** 嫉妒：赎罪后清除仇恨，但保留增伤 */
	private static void checkEnvyRedeemed(ServerPlayer player) {
		SinManager.setState(player, Sin.ENVY, SinManager.SinState.REDEEMED);
		com.summy.reliquary.sin.SinEffects.forceEnvyMaintenance(player);
		reportEnvyWolf(player, "嫉妒（赎罪后）");
		LivingEntity armored = findEntity(player, glowZombieId);
		if (armored != null) {
			float bonus = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, armored, 10.0F);
			log(String.format("嫉妒（赎罪后）：增伤保留 10 → %.1f（仍应受增伤）", bonus));
		}
		if (armored != null) {
			armored.discard();
		}
		LivingEntity wolf = findEntity(player, envyWolfId);
		if (wolf != null) {
			wolf.discard();
		}
	}

	private static void reportEnvyWolf(ServerPlayer player, String label) {
		LivingEntity wolf = findEntity(player, envyWolfId);
		if (wolf instanceof Mob mob) {
			log(label + "：狼的目标 = " + (mob.getTarget() == null ? "无" : mob.getTarget().getName().getString()));
		} else {
			log(label + "：找不到测试狼");
		}
	}

	/** 暴怒：50 次击杀触发；随机浮动与概率自伤 */
	private static void checkWrath(ServerPlayer player) {
		Zombie victim = spawnTestZombie(player, 8.0D);
		for (int index = 0; index < com.summy.reliquary.config.ReliquaryConfig.wrathKillRequired(); index++) {
			com.summy.reliquary.sin.SinEffects.onKill(player, victim);
		}
		log("暴怒：累计击杀 " + com.summy.reliquary.config.ReliquaryConfig.wrathKillRequired()
				+ " 次 → 状态=" + SinManager.state(player, Sin.WRATH) + "（应为 ACTIVATED）");
		com.summy.reliquary.sin.SinEffects.resetCounters();
		double min = Double.MAX_VALUE;
		double max = 0.0D;
		for (int index = 0; index < 400; index++) {
			float value = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 1.0F);
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		log(String.format("暴怒：400 次攻击系数范围 %.2f~%.2f（应在 0.5~1.5）、自伤 %d 次（约 15%%）",
				min, max, com.summy.reliquary.sin.SinEffects.wrathSelfHitCount()));
		victim.discard();
	}

	/** 暴怒：赎罪后移除自伤、上限升到 2.0 */
	private static void checkWrathRedeemed(ServerPlayer player) {
		SinManager.setState(player, Sin.WRATH, SinManager.SinState.REDEEMED);
		com.summy.reliquary.sin.SinEffects.resetCounters();
		Zombie victim = spawnTestZombie(player, 8.0D);
		double max = 0.0D;
		for (int index = 0; index < 400; index++) {
			max = Math.max(max, com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 1.0F));
		}
		log(String.format("暴怒（赎罪后）：系数上限 %.2f（应接近 2.0）、自伤 %d 次（应为 0）",
				max, com.summy.reliquary.sin.SinEffects.wrathSelfHitCount()));
		// 1.8.0：赎罪是终态 —— 再击杀 200 次也不能把它变回「已激活」
		for (int index = 0; index < 200; index++) {
			com.summy.reliquary.sin.SinEffects.onKill(player, victim);
		}
		log("暴怒（赎罪后·1.8.0）：再击杀 200 次后仍为已赎罪="
				+ (SinManager.state(player, Sin.WRATH) == SinManager.SinState.REDEEMED) + "（应 true）");
		victim.discard();
	}

	/** 怠惰：准备时间与状态 */
	private static void prepareSloth(ServerPlayer player) {
		player.serverLevel().setDayTime(16000L);
		log("怠惰：把游戏时间设为 16000 tick（时钟 22:00，不算早睡）");
	}

	/** 怠惰：晚睡不计、早睡 3 次触发、减速与抗性、赎罪后的变化 */
	private static void checkSloth(ServerPlayer player) {
		SinManager.setState(player, Sin.SLOTH, SinManager.SinState.UNACTIVATED);
		com.summy.reliquary.sin.SinProgress.reset(player);
		com.summy.reliquary.sin.SinEffects.onSleep(player);
		int lateCount = com.summy.reliquary.sin.SinProgress.get(player,
				com.summy.reliquary.sin.SinProgress.SLOTH_SLEEPS);
		player.serverLevel().setDayTime(19000L);
		for (int index = 0; index < com.summy.reliquary.config.ReliquaryConfig.slothSleepRequired(); index++) {
			com.summy.reliquary.sin.SinEffects.onSleep(player);
		}
		log("怠惰：22:00 入睡计数=" + lateCount + "（应为 0）；01:00 入睡 "
				+ com.summy.reliquary.config.ReliquaryConfig.slothSleepRequired() + " 次 → 状态="
				+ SinManager.state(player, Sin.SLOTH) + "（应为 ACTIVATED）");
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		boolean resistance = player.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		log(String.format("怠惰：移动速度 %.3f（基础 0.100，激活时约 0.080）、抗性提升=%s", speed, resistance));
		SinManager.setState(player, Sin.SLOTH, SinManager.SinState.REDEEMED);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		player.removeAllEffects();
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		double redeemedSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		net.minecraft.world.effect.MobEffectInstance effect =
				player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		log(String.format("怠惰（赎罪后）：移动速度 %.3f（应回到 0.100）、抗性等级 %s（应为 II）",
				redeemedSpeed, effect == null ? "无" : effect.getAmplifier()));
	}

	/** 贪婪：钻石触发、增伤、低于阈值惩罚、死亡扣钻、赎罪后的变化 */
	private static void checkGreed(ServerPlayer player) {
		resetSinState(player);
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(Items.DIAMOND, 40));
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		log("贪婪：背包 40 钻石 → 状态=" + SinManager.state(player, Sin.GREED) + "（应为 ACTIVATED）");
		Zombie victim = spawnTestZombie(player, 8.0D);
		float rich = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 10.0F);
		com.summy.reliquary.sin.SinEffects.removeDiamonds(player, 10);
		int left = com.summy.reliquary.sin.SinEffects.countDiamonds(player);
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		float poor = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 10.0F);
		log(String.format("贪婪：40 颗时 10 → %.1f（应 14 = +40%%）；扣到 %d 颗时 10 → %.1f（应 1.0 = 一成）",
				rich, left, poor));
		if (com.summy.reliquary.sin.SinEffects.countDiamonds(player) < 40) {
			player.getInventory().add(new ItemStack(Items.DIAMOND, 40));
		}
		int before = com.summy.reliquary.sin.SinEffects.countDiamonds(player);
		com.summy.reliquary.sin.SinEffects.onPlayerDeath(player);
		int after = com.summy.reliquary.sin.SinEffects.countDiamonds(player);
		log("贪婪：死亡扣钻石 " + before + " → " + after + "（应扣掉 3~8 颗）");
		SinManager.setState(player, Sin.GREED, SinManager.SinState.REDEEMED);
		com.summy.reliquary.sin.SinEffects.removeDiamonds(player, 100);
		float redeemed = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 10.0F);
		log(String.format("贪婪（赎罪后）：钻石 0 颗时 10 → %.1f（应 10，无惩罚也无加成）", redeemed));
		victim.discard();
	}

	/** 暴食：准备（复位状态与饥饿） */
	private static void prepareGluttony(ServerPlayer player) {
		SinManager.setState(player, Sin.GLUTTONY, SinManager.SinState.UNACTIVATED);
		com.summy.reliquary.sin.SinProgress.reset(player);
		player.getInventory().clearContent();
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5.0F);
		log("暴食：已复位状态、饥饿值与背包");
	}

	/** 暴食：5 次高饱和进食触发；饥饿上限 18、低饥饿负面、白饭失效；赎罪后的变化 */
	private static void checkGluttony(ServerPlayer player) {
		for (int index = 0; index < com.summy.reliquary.config.ReliquaryConfig.gluttonyMealRequired(); index++) {
			com.summy.reliquary.sin.SinEffects.onItemConsumed(player, new ItemStack(Items.GOLDEN_CARROT));
		}
		log("暴食：食用 " + com.summy.reliquary.config.ReliquaryConfig.gluttonyMealRequired()
				+ " 次金胡萝卜（饱和度 9.6 > 阈值 " + com.summy.reliquary.config.ReliquaryConfig.gluttonySaturationThreshold()
				+ "）→ 状态=" + SinManager.state(player, Sin.GLUTTONY) + "（应为 ACTIVATED）");
		player.getFoodData().setFoodLevel(20);
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		int capped = player.getFoodData().getFoodLevel();
		player.getFoodData().setFoodLevel(5);
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		boolean slow = player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
		boolean weak = player.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
		log("暴食：饥饿上限=" + capped + "（应为 18）、饥饿 5 时缓慢=" + slow + "、虚弱=" + weak);
		// 白饭失效：戴上白饭后饥饿值不应被锁到上限
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.STOMACH, 0, new ItemStack(SummyReliquary.FREELOADERS_RICE.get())));
		player.getFoodData().setFoodLevel(10);
		net.minecraft.server.MinecraftServer server = player.getServer();
		if (server != null) {
			RiceHungerLock.tick(server);
		}
		log("暴食：戴白饭且饥饿 10 时白饭锁定后=" + player.getFoodData().getFoodLevel() + "（应为 10 = 白饭失效）");
		SinManager.setState(player, Sin.GLUTTONY, SinManager.SinState.REDEEMED);
		player.getFoodData().setFoodLevel(20);
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		log("暴食（赎罪后）：饥饿值 20 → " + player.getFoodData().getFoodLevel() + "（应为 20，不再被压到 18）");
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.STOMACH, 0, ItemStack.EMPTY));
		player.removeAllEffects();
	}

	/** 色欲：准备（复位状态与护甲） */
	private static void prepareLust(ServerPlayer player) {
		SinManager.setState(player, Sin.LUST, SinManager.SinState.UNACTIVATED);
		com.summy.reliquary.sin.SinProgress.reset(player);
		player.getInventory().clearContent();
		equipFullDiamondArmor(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		log("色欲：已复位状态并穿上四件钻石装备");
	}

	/** 给玩家穿上一整套钻石装备（护甲值合计 20，便于核对 -30% 后的数值） */
	private static void equipFullDiamondArmor(ServerPlayer player) {
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
	}

	/** 色欲：繁殖 10 次触发；护甲 -30%、攻击脱目标甲、受击脱自己甲；赎罪后的变化 */
	private static void checkLust(ServerPlayer player) {
		for (int index = 0; index < com.summy.reliquary.config.ReliquaryConfig.lustBreedRequired(); index++) {
			com.summy.reliquary.sin.SinEffects.onBred(player);
		}
		log("色欲：繁殖 " + com.summy.reliquary.config.ReliquaryConfig.lustBreedRequired()
				+ " 次 → 状态=" + SinManager.state(player, Sin.LUST) + "（应为 ACTIVATED）");
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double armor = player.getAttributeValue(Attributes.ARMOR);
		log(String.format("色欲：四件钻石装备的护甲值合计 %.1f（原版 20，激活时约为 14）", armor));
		com.summy.reliquary.sin.SinEffects.resetCounters();
		Zombie victim = spawnTestZombie(player, 6.0D);
		victim.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		for (int index = 0; index < 400; index++) {
			victim.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 1.0F);
		}
		for (int index = 0; index < 400; index++) {
			equipFullDiamondArmor(player);
			com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 1.0F);
		}
		log(String.format("色欲：400 次攻击脱目标甲 %d 次、400 次受击脱自己甲 %d 次（各约 15%%）",
				com.summy.reliquary.sin.SinEffects.lustStripArmorCount(),
				com.summy.reliquary.sin.SinEffects.lustSelfStripCount()));
		SinManager.setState(player, Sin.LUST, SinManager.SinState.REDEEMED);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double redeemedArmor = player.getAttributeValue(Attributes.ARMOR);
		com.summy.reliquary.sin.SinEffects.resetCounters();
		for (int index = 0; index < 200; index++) {
			equipFullDiamondArmor(player);
			com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 1.0F);
		}
		log(String.format("色欲（赎罪后）：护甲值 %.1f（应回到 20）、受击脱甲 %d 次（应为 0）",
				redeemedArmor, com.summy.reliquary.sin.SinEffects.lustSelfStripCount()));
		victim.discard();
	}

	/** 色欲：背包里带末地烛时的隐藏触发 */
	private static void checkLustHiddenTrigger(ServerPlayer player) {
		SinManager.setState(player, Sin.LUST, SinManager.SinState.UNACTIVATED);
		com.summy.reliquary.sin.SinProgress.reset(player);
		// 上一项测试可能把背包塞满（受击脱下来的护甲会回到背包），先清空再放末地烛
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(Items.END_ROD));
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		log("色欲（隐藏条件）：背包内有末地烛 → 状态=" + SinManager.state(player, Sin.LUST) + "（应为 ACTIVATED）");
		player.getInventory().clearContent();
		SinManager.setState(player, Sin.LUST, SinManager.SinState.UNACTIVATED);
	}

	/** 提示配色：触发提示为红、赎罪提示为金 */
	private static void checkSinMessageColors(ServerPlayer player) {
		// 觉醒提示：只显示台词，整句用该罪的对应色（1.4.2 起）
		net.minecraft.network.chat.MutableComponent awakened = net.minecraft.network.chat.Component
				.translatable(Sin.PRIDE.awakenedKey())
				.withStyle(net.minecraft.network.chat.Style.EMPTY
						.withColor(net.minecraft.network.chat.TextColor.fromRgb(Sin.PRIDE.color())));
		String sinName = net.minecraft.network.chat.Component.translatable(Sin.PRIDE.nameKey()).getString();
		net.minecraft.network.chat.MutableComponent redeemed = net.minecraft.network.chat.Component.translatable(
				"message.summy-reliquary.sin.redeem.done",
				net.minecraft.network.chat.Component.translatable(Sin.PRIDE.nameKey())
						.withStyle(net.minecraft.ChatFormatting.GOLD))
				.withStyle(net.minecraft.ChatFormatting.GOLD);
		log("觉醒提示：文案=「" + awakened.getString() + "」颜色=" + colorOf(awakened)
				+ "（应为 #A020F0）、是否含罪名=" + awakened.getString().contains(sinName) + "（应为 false）");
		log("赎罪提示：「" + redeemed.getString() + "」颜色=" + colorOf(redeemed) + "（应为 #FFAA00）");
	}

	private static String colorOf(net.minecraft.network.chat.Component component) {
		net.minecraft.network.chat.TextColor color = component.getStyle().getColor();
		return color == null ? "无" : String.format("#%06X", color.getValue());
	}

	/**
	 * 灵魂三件套免死：20% 触发、恢复半血、2 秒无敌。
	 *
	 * <p>1.6.4：免死不再读 {@code LivingDamageEvent} 的金额（Kilt 上不可靠），改成命中前用
	 * {@code DamageEstimate} 估算"会不会致命"，所以这里直接驱动 {@code tryNullify}。
	 */
	private static void checkDeathImmunity(ServerPlayer player) {
		com.summy.reliquary.effect.DeathImmunity.reset();
		int trials = 400;
		int triggered = 0;
		for (int index = 0; index < trials; index++) {
			player.setHealth(player.getMaxHealth());
			if (com.summy.reliquary.effect.DeathImmunity.tryNullify(player, player.getMaxHealth() * 2.0F)) {
				triggered++;
			}
		}
		double rate = triggered * 100.0D / trials;
		// 再触发一次（最多试 200 次直到命中），检查半血与无敌守卫
		for (int index = 0; index < 200; index++) {
			player.setHealth(player.getMaxHealth());
			if (com.summy.reliquary.effect.DeathImmunity.tryNullify(player, player.getMaxHealth() * 2.0F)) {
				break;
			}
		}
		float healthAfter = player.getHealth();
		boolean guarded = com.summy.reliquary.effect.DeathImmunity.isGuarded(player);
		net.minecraftforge.event.entity.living.LivingHurtEvent hurt =
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player,
						player.damageSources().generic(), 5.0F);
		MinecraftForge.EVENT_BUS.post(hurt);
		log(String.format("免死：%d 次致命伤害触发 %d 次（%.0f%%，应约 20%%）；本次触发后生命 %.1f/%.1f（应为半血）、"
				+ "无敌守卫=%s、无敌期内受伤被取消=%s",
				trials, triggered, rate, healthAfter, player.getMaxHealth() / 2.0F, guarded, hurt.isCanceled()));
	}

	/** 蓄力粒子：半径随进度收缩、圆心低于眼睛 */
	private static void checkChargeParticles(ServerPlayer player) {
		double start = com.summy.reliquary.effect.RevelationBeam.chargeRingRadius(0.0D);
		double middle = com.summy.reliquary.effect.RevelationBeam.chargeRingRadius(0.5D);
		double end = com.summy.reliquary.effect.RevelationBeam.chargeRingRadius(1.0D);
		double centerY = com.summy.reliquary.effect.RevelationBeam.chargeCenter(player).y;
		log(String.format("蓄力粒子：半径 进度0=%.2f、0.5=%.2f、1=%.2f（应 1.90 → 1.15 → 0.40 单调收缩）；"
				+ "圆心 y=%.2f 眼睛 y=%.2f（应更低）", start, middle, end, centerY, player.getEyeY()));
	}

	/** 成就「罪无可赦」：描述第二段应为 #8B0000 + 斜体 */
	private static void checkUnforgivableDescription(ServerPlayer player) {
		net.minecraft.server.MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		net.minecraft.advancements.Advancement advancement =
				server.getAdvancements().getAdvancement(SummyReliquary.id("unforgivable"));
		if (advancement == null) {
			log("成就：找不到 unforgivable");
			return;
		}
		net.minecraft.network.chat.Component description = advancement.getDisplay().getDescription();
		java.util.List<net.minecraft.network.chat.Component> parts = description.getSiblings();
		String first = description.getString() + " / 段数=" + (parts.size() + 1);
		if (parts.isEmpty()) {
			log("成就描述：" + first + "（预期两段）");
			return;
		}
		net.minecraft.network.chat.Component tail = parts.get(parts.size() - 1);
		log("成就「罪无可赦」描述：" + first + "、第二段「" + tail.getString() + "」颜色="
				+ colorOf(tail) + "（应为 #8B0000）、斜体=" + tail.getStyle().isItalic() + "（应为 true）");
	}

	/** 在玩家前方生成一只测试僵尸 */
	private static Zombie spawnTestZombie(ServerPlayer player, double distance) {
		return spawnTestZombieAt(player, distance, EntityType.ZOMBIE);
	}

	/** 在玩家前方生成一只指定类型的测试生物 */
	private static <T extends Mob> T spawnTestMobAt(ServerPlayer player, double distance,
			net.minecraft.world.entity.EntityType<T> type) {
		Vec3 look = player.getLookAngle();
		Vec3 point = player.position().add(look.x * distance, 0.0D, look.z * distance);
		T mob = type.create(player.serverLevel());
		if (mob == null) {
			return null;
		}
		mob.moveTo(point.x, player.getY(), point.z, 0.0F, 0.0F);
		player.serverLevel().addFreshEntity(mob);
		return mob;
	}

	private static Zombie spawnTestZombieAt(ServerPlayer player, double distance,
			net.minecraft.world.entity.EntityType<Zombie> type) {
		Vec3 look = player.getLookAngle();
		Vec3 point = player.position().add(look.x * distance, 0.0D, look.z * distance);
		Zombie zombie = type.create(player.serverLevel());
		if (zombie == null) {
			return null;
		}
		zombie.moveTo(point.x, player.getY(), point.z, 0.0F, 0.0F);
		player.serverLevel().addFreshEntity(zombie);
		return zombie;
	}

	// ==================== 1.4.1：暴食修复 / 新阈值 / 天使体系 / 末影龙挑战 ====================

	/** 暴食的饥饿上限必须是每 tick 压制（否则吃 / 喝后会闪回 19/20） */
	private static void checkGluttonyTickClamp(ServerPlayer player) {
		resetSinState(player);
		SinManager.setState(player, Sin.GLUTTONY, SinManager.SinState.ACTIVATED);
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(20.0F);
		com.summy.reliquary.sin.SinEffects.tickPlayerEveryTick(player);
		int clamped = player.getFoodData().getFoodLevel();
		float saturation = player.getFoodData().getSaturationLevel();
		SinManager.setState(player, Sin.GLUTTONY, SinManager.SinState.REDEEMED);
		player.getFoodData().setFoodLevel(20);
		com.summy.reliquary.sin.SinEffects.tickPlayerEveryTick(player);
		int redeemed = player.getFoodData().getFoodLevel();
		log(String.format("暴食（每 tick 压制）：饥饿 20 → %d（应为 18）、饱和度 20 → %.1f（应为 18.0）；"
				+ "赎罪后 20 → %d（应保持 20）", clamped, saturation, redeemed));
	}

	/** 新阈值：暴怒 100 杀、暴食 10 次且阈值 8；嫉妒去掉「生命上限」；傲慢拉黑末影人 */
	private static void checkSinThresholds(ServerPlayer player) {
		resetSinState(player);
		Zombie victim = spawnTestZombie(player, 8.0D);
		for (int index = 0; index < 99; index++) {
			com.summy.reliquary.sin.SinEffects.onKill(player, victim);
		}
		SinManager.SinState after99 = SinManager.state(player, Sin.WRATH);
		com.summy.reliquary.sin.SinEffects.onKill(player, victim);
		SinManager.SinState after100 = SinManager.state(player, Sin.WRATH);
		if (victim != null) {
			victim.discard();
		}

		resetSinState(player);
		for (int index = 0; index < 12; index++) {
			com.summy.reliquary.sin.SinEffects.onItemConsumed(player, new ItemStack(Items.BREAD));
		}
		SinManager.SinState breadState = SinManager.state(player, Sin.GLUTTONY);
		int breadCount = com.summy.reliquary.sin.SinProgress.get(player,
				com.summy.reliquary.sin.SinProgress.GLUTTONY_MEALS);
		for (int index = 0; index < 10; index++) {
			com.summy.reliquary.sin.SinEffects.onItemConsumed(player, new ItemStack(Items.GOLDEN_CARROT));
		}
		SinManager.SinState carrotState = SinManager.state(player, Sin.GLUTTONY);

		resetSinState(player);
		SinManager.setState(player, Sin.ENVY, SinManager.SinState.ACTIVATED);
		net.minecraft.world.entity.animal.IronGolem golem =
				spawnTestMobAt(player, 6.0D, EntityType.IRON_GOLEM);
		float golemDamage = golem == null ? -1.0F
				: com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, golem, 10.0F);
		if (golem != null) {
			golem.discard();
		}

		resetSinState(player);
		net.minecraft.world.entity.monster.EnderMan enderman =
				spawnTestMobAt(player, 6.0D, EntityType.ENDERMAN);
		if (enderman != null) {
			for (int index = 0; index < 15; index++) {
				com.summy.reliquary.sin.SinEffects.onKill(player, enderman);
			}
			enderman.discard();
		}
		int prideCount = com.summy.reliquary.sin.SinProgress.get(player,
				com.summy.reliquary.sin.SinProgress.PRIDE_KILLS);
		int wrathCount = com.summy.reliquary.sin.SinProgress.get(player,
				com.summy.reliquary.sin.SinProgress.WRATH_KILLS);

		log("暴怒阈值：99 杀=" + after99 + "（应 UNACTIVATED）、第 100 杀=" + after100 + "（应 ACTIVATED）");
		log("暴食阈值：12 次面包后=" + breadState + "（应 UNACTIVATED，计数 " + breadCount
				+ "）；再吃 10 次金胡萝卜=" + carrotState + "（应 ACTIVATED）");
		log(String.format("嫉妒条件：对不穿甲 / 无 buff / 高生命的目标 10 → %.1f（应为 10，不再因生命上限增伤）",
				golemDamage));
		log("傲慢黑名单：击杀 15 只末影人 → 傲慢计数=" + prideCount + "（应为 0）、暴怒计数=" + wrathCount + "（应为 15）");
	}

	/** 天使门槛：没有标记时五件饰品都不能装进栏位 */
	private static void checkAngelGate(ServerPlayer player) {
		// 1.5.7：门槛只在"往空槽新装"时拦，「已在本格的重算」会直接放行（重登不被卸下的关键），
		// 所以这里先把相关槽位清空，才能测到真正的门槛行为。
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (int index = 0; index < 3; index++) {
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, index, ItemStack.EMPTY);
			}
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 1, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		Object[][] items = {
				{SummyReliquary.THE_BODY.get(), ReliquarySlots.SPIRIT_ALTAR},
				{SummyReliquary.THE_MIND.get(), ReliquarySlots.SPIRIT_ALTAR},
				{SummyReliquary.THE_SOUL.get(), ReliquarySlots.SPIRIT_ALTAR},
				{SummyReliquary.STAR_OF_BETHLEHEM.get(), ReliquarySlots.REVELATION},
				{SummyReliquary.FINAL_REVELATION.get(), ReliquarySlots.REVELATION},
		};
		StringBuilder locked = new StringBuilder();
		for (Object[] entry : items) {
			Item item = (Item) entry[0];
			String slot = (String) entry[1];
			boolean can = ((top.theillusivec4.curios.api.type.capability.ICurioItem) item)
					.canEquip(new SlotContext(slot, player, 0, false, true), new ItemStack(item));
			locked.append(item.getDescription().getString()).append('=').append(can).append(' ');
		}
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		boolean bodyCan = ((top.theillusivec4.curios.api.type.capability.ICurioItem) SummyReliquary.THE_BODY.get())
				.canEquip(new SlotContext(ReliquarySlots.SPIRIT_ALTAR, player, 0, false, true),
						new ItemStack(SummyReliquary.THE_BODY.get()));
		log("天使门槛（无标记时全部应为 false）：" + locked);
		log("天使门槛（grant 之后肉体可佩戴）=" + bodyCan + "（应为 true）");
	}

	/** 两条末影龙挑战（与「解放末地」同口径） */
	private static void checkDragonChallenges(ServerPlayer player) {
		resetSinState(player);
		// 1.7.1：恶魔线会**排斥**这两条挑战（`evaluateDragon` 顶部直接 return），
		// 所以用例开头必须显式把恶魔标记清干净，否则前序用例的残留会让整段静默跳过。
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		// 一次性裁决：先清空裁决，再走「佩戴且七罪全未激活」这条路径。
		// 注意：成就是一次性的，重复跑同一个存档时不会再发 AdvancementEarnEvent，
		// 所以这里断言的是**裁决状态**（dragon_verdict）而不是成就完成情况。
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		// 无罪之人的门槛：必须已完成「有罪之人」
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.SINS_OBTAINED);

		// ① 未佩戴七罪之源 → 判为无罪之人
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY));
		com.summy.reliquary.advancement.SinChallenges.onDragonSlainBy(player);
		int verdictSinless = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player);

		// ② 佩戴七罪之源且七罪全未激活 → 判为纯洁无瑕（并把七罪之源换成美德）
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		resetSinState(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get())));
		com.summy.reliquary.advancement.SinChallenges.onDragonSlainBy(player);
		int verdictFlawless = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player);
		boolean pureDone = com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "pure");
		// 同一个 tick 里自愈还没跑，这里显式调一次再读天使标记
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean angel = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		// 无瑕应当把七罪全部置为已赎罪
		int allMask = (1 << Sin.values().length) - 1;
		boolean allRedeemedAfterFlawless =
				com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
						== com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS
						? (SinManager.redeemedMask(player) & allMask) == allMask : false;
		String soulSeal = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.findFirstCurio(SummyReliquary.VIRTUES.get()).isPresent()
						? "virtues" : "其它")
				.orElse("无");

		// ③ 二次判定回归（你实测的问题）：纯洁无瑕之后再判一次，裁决不能翻转
		com.summy.reliquary.advancement.SinChallenges.onDragonSlainBy(player);
		int verdictAfterSecond = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player);

		log("末影龙裁决：未佩戴时=" + verdictSinless + "（应 1 = 无罪之人）、"
				+ "佩戴且七罪全未激活时=" + verdictFlawless + "（应 2 = 纯洁无瑕）");
		log("纯洁无瑕：魂印栏=" + soulSeal + "（应为 virtues）、纯洁之人=" + pureDone + "、天使标记=" + angel
				+ "、七罪全部已赎罪=" + allRedeemedAfterFlawless + "（应为 true）");
		log("**二次判定回归**：再判一次后裁决=" + verdictAfterSecond + "（应仍为 2，不再改判成无罪之人）");
	}

	/**
	 * 老存档自愈：**天使标记只补一次**（1.6.10 改成动作驱动后的一次性迁移）/ 补放弃一切 / 补做无瑕转化。
	 */
	private static void checkSelfHeal(ServerPlayer player) {
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		// ① 纯洁之人已完成但没有天使标记 → **老存档一次性迁移**补发
		// （1.6.4 起：**曾签过契约的玩家不再被自愈补天使标记**，所以这里先清掉 demon_sealed，
		// 下面再单独断言"签约过的玩家不会被补回去"）
		// 1.6.10：天使标记改成"七罪之源 → 美德"动作驱动，自愈只剩这一次迁移，所以这里先清迁移位
		com.summy.reliquary.effect.PlayerFlags.setAngelMigrated(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean angel = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean sealedStaysDemon = com.summy.reliquary.effect.PlayerFlags.isDemon(player)
				&& !com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		// 迁移位已经置位 → 再调自愈**不会**补标记（这正是"创世纪重置后不再被补回"的依据）
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean migratedOnce = !com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		// ② 无罪之人已完成但没有「放弃一切」标记 → 自愈补上
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, false);
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean renounced = com.summy.reliquary.effect.PlayerFlags.isSinRenounced(player);
		// ③ 裁决为纯洁无瑕但七罪之源没被换掉 → 自愈补做转化
		resetSinState(player);
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player,
				com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get())));
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		String soulSeal = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.findFirstCurio(SummyReliquary.VIRTUES.get()).isPresent()
						? "virtues" : "其它")
				.orElse("无");
		log("自愈：老存档迁移 纯洁之人→天使标记=" + angel + "（应为 true）、无罪之人→放弃一切=" + renounced
				+ "（应为 true）、无瑕裁决→魂印栏=" + soulSeal + "（应为 virtues）、"
				+ "签约过的玩家不被补天使标记=" + sealedStaysDemon + "（应为 true）；"
				+ "迁移只做一次（置位后再调自愈不补）=" + migratedOnce + "（应为 true）");
	}

	/** 伯列恒之星由「三位一体」发放且只发一次 */
	private static void checkStarGrant(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, false);
		player.getInventory().clearContent();
		com.summy.reliquary.advancement.SinChallenges.grantStarOnTrinity(player);
		com.summy.reliquary.advancement.SinChallenges.grantStarOnTrinity(player);
		int count = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		log("伯列恒之星：连续两次三位一体 → 背包内 " + count + " 个（应为 1）");
	}

	/**
	 * 配方门禁（1.7.0 新表）：天使线（无标记 → 收产物 + 退回该配方的全部新材料）与
	 * 仪式法袍（未签约 → 收产物 + 退料）。
	 */
	private static void checkRecipeGate(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		player.getInventory().clearContent();
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.THE_BODY.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.THE_BODY.get()), player.inventoryMenu.getCraftSlots()));
		int body = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.THE_BODY.get());
		int beef = com.summy.reliquary.sin.SinEffects.countItem(player, Items.BEEF);
		int crystals = com.summy.reliquary.sin.SinEffects.countItem(player, Items.PRISMARINE_CRYSTALS);
		int bone = com.summy.reliquary.sin.SinEffects.countItem(player, Items.BONE);
		int apple = com.summy.reliquary.sin.SinEffects.countItem(player, Items.GOLDEN_APPLE);
		boolean bodyRefunded = body == 0 && beef == 1 && crystals == 1 && bone == 3 && apple == 1;
		log("配方门槛·天使线（无天使标记）：产物=" + body + "（应为 0）、退回 生牛肉=" + beef
				+ "（应 1）、海晶砂砾=" + crystals + "（应 1）、骨头=" + bone + "（应 3）、金苹果=" + apple
				+ "（应 1）→ " + bodyRefunded + "（应 true，材料按 1.7.0 新表原样退回）");

		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.THE_BODY.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.THE_BODY.get()), player.inventoryMenu.getCraftSlots()));
		boolean kept = player.inventoryMenu.getCarried().is(SummyReliquary.THE_BODY.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		// 仪式法袍：未签约 → 收走 + 退料；签约后 → 放行
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.CEREMONIAL_ROBES.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.CEREMONIAL_ROBES.get()), player.inventoryMenu.getCraftSlots()));
		int robesLeft = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.CEREMONIAL_ROBES.get());
		int goldBack = com.summy.reliquary.sin.SinEffects.countItem(player, Items.GOLD_INGOT);
		int woolBack = com.summy.reliquary.sin.SinEffects.countItem(player, Items.BLACK_WOOL);
		int scrapBack = com.summy.reliquary.sin.SinEffects.countItem(player, Items.NETHERITE_SCRAP);
		boolean robesBlocked = robesLeft == 0 && goldBack == 3 && woolBack == 5 && scrapBack == 1;
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.CEREMONIAL_ROBES.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.CEREMONIAL_ROBES.get()), player.inventoryMenu.getCraftSlots()));
		boolean robesKept = player.inventoryMenu.getCarried().is(SummyReliquary.CEREMONIAL_ROBES.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		log("配方门槛（有天使标记）：产物保留=" + kept + "（应为 true）；仪式法袍 未签约被拦=" + robesBlocked
				+ "（应 true：退回 金锭×" + goldBack + " / 黑羊毛×" + woolBack + " / 下界合金碎片×" + scrapBack
				+ "）、签约后放行=" + robesKept + "（应 true）");
	}

	/** 有天使标记时聊天栏 / Tab 列表的显示名变金 */
	private static void checkNameFormat(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		PlayerEvent.NameFormat plain = new PlayerEvent.NameFormat(player,
				net.minecraft.network.chat.Component.literal("Dev"));
		MinecraftForge.EVENT_BUS.post(plain);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		PlayerEvent.NameFormat gold = new PlayerEvent.NameFormat(player,
				net.minecraft.network.chat.Component.literal("Dev"));
		MinecraftForge.EVENT_BUS.post(gold);
		log("玩家名：无天使标记=" + colorOf(plain.getDisplayname()) + "（应为 无）、有天使标记="
				+ colorOf(gold.getDisplayname()) + "（应为 #FFAA00）");
	}

	// ==================== 1.4.3：文本 / 图标 / 美德继承 / 命令权限 ====================

	/** 提示数字不再出现裸 `%s`，以及两条挑战的图标与隐藏设置 */
	private static void checkTextsAndIcons(ServerPlayer player) {
		String prideBuff = net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.sin.pride.buff", 1.0D).getString();
		String greedBuff = net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.sin.greed.buff", 1, 64).getString();
		String lustBuff = net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.sin.lust.buff", 15).getString();
		String slothDebuff = net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.sin.sloth.debuff", 20).getString();
		String envyBuff = net.minecraft.network.chat.Component.translatable(
				"item.summy-reliquary.sin.envy.buff", 50).getString();
		boolean raw = prideBuff.contains("%s") || greedBuff.contains("%s") || lustBuff.contains("%s")
				|| slothDebuff.contains("%s") || envyBuff.contains("%s");
		log("提示数字：傲慢=「" + prideBuff + "」");
		log("提示数字：贪婪=「" + greedBuff + "」、色欲=「" + lustBuff + "」、怠惰=「" + slothDebuff + "」");
		log("提示数字：嫉妒=「" + envyBuff + "」；是否残留 %s=" + raw + "（应为 false）");

		net.minecraft.server.MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		String sinlessIcon = advancementIcon(server, "sinless");
		String flawlessIcon = advancementIcon(server, "flawless");
		boolean hidden = advancementHidden(server, "unforgivable");
		boolean iconsRegistered =
				net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(SummyReliquary.id("error"))
						&& net.minecraft.core.registries.BuiltInRegistries.ITEM
								.containsKey(SummyReliquary.id("purity"));
		log("图标：error/purity 已注册=" + iconsRegistered + "（应为 true）；无罪之人图标=" + sinlessIcon
				+ "（应为 error）、纯洁无瑕图标=" + flawlessIcon + "（应为 purity）、罪无可赦隐藏=" + hidden
				+ "（应为 true）");
	}

	private static String advancementIcon(net.minecraft.server.MinecraftServer server, String path) {
		var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(path));
		if (advancement == null || advancement.getDisplay() == null) {
			return "无";
		}
		var icon = advancement.getDisplay().getIcon();
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(icon.getItem()).getPath();
	}

	private static boolean advancementHidden(net.minecraft.server.MinecraftServer server, String path) {
		var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(path));
		return advancement != null && advancement.getDisplay() != null
				&& advancement.getDisplay().isHidden();
	}

	/** 美德继承七罪赎罪后的增益：增益生效、减益不生效、触发计数不累计 */
	private static void checkVirtuesInheritance(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.VIRTUES.get())));
		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.REDEEMED);
		}
		com.summy.reliquary.sin.SinProgress.reset(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		player.removeAllEffects();

		Zombie zombie = spawnTestZombie(player, 6.0D);
		zombie.setHealth(zombie.getMaxHealth() * 0.5F);
		float prideDamage = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, zombie, 10.0F);
		zombie.discard();
		float incoming = com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 10.0F);
		player.getFoodData().setFoodLevel(20);
		com.summy.reliquary.sin.SinEffects.tickPlayerEveryTick(player);
		int food = player.getFoodData().getFoodLevel();
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		var resistance = player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		Zombie victim = spawnTestZombie(player, 8.0D);
		for (int index = 0; index < 3; index++) {
			com.summy.reliquary.sin.SinEffects.onKill(player, victim);
		}
		victim.discard();
		int wrathCount = com.summy.reliquary.sin.SinProgress.get(player,
				com.summy.reliquary.sin.SinProgress.WRATH_KILLS);
		boolean prideInRange = prideDamage >= 7.5F && prideDamage <= 30.0F;
		log(String.format("美德继承：傲慢增伤 10 → %.1f（应在 7.5~30：傲慢×1.5 再乘暴怒赎罪后的随机 0.5~2.0）、"
				+ "自身受伤 10 → %.1f（应 10）、饥饿 20 → %d（应保持 20）、抗性等级=%s（应为 1）、"
				+ "击杀 3 次后累计=%d（应为 0）",
				prideDamage, incoming, food, resistance == null ? "无" : resistance.getAmplifier(), wrathCount));
		log("美德继承：傲慢增伤是否落在预期区间=" + prideInRange + "（应为 true）");
	}

	/** 命令权限矩阵：玩家节点权限 0 可用、管理节点需要等级 2 */
	private static void checkCommandPermissions(ServerPlayer player) {
		net.minecraft.server.MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		var root = server.getCommands().getDispatcher().getRoot().getChild("summyreliquary");
		if (root == null) {
			log("命令权限：找不到 /summyreliquary");
			return;
		}
		var sin = root.getChild("sin");
		var sinArg = sin.getChild("sin");
		var angel = root.getChild("angel");
		var dragon = root.getChild("dragon");
		var genesis = root.getChild("genesis");
		var p0 = player.createCommandSourceStack().withPermission(0);
		var p2 = player.createCommandSourceStack().withPermission(2);

		boolean playerNodes = sin.getChild("list").getRequirement().test(p0)
				&& sin.getChild("refresh").getRequirement().test(p0)
				&& sinArg.getChild("query").getRequirement().test(p0)
				&& angel.getChild("query").getRequirement().test(p0)
				&& angel.getChild("refresh").getRequirement().test(p0)
				// 1.5.2：创世纪确认框的按钮要能被非 OP 玩家点
				&& genesis.getChild("confirm").getRequirement().test(p0)
				&& genesis.getChild("cancel").getRequirement().test(p0);
		boolean adminDenied = !sinArg.getChild("on").getRequirement().test(p0)
				&& !sinArg.getChild("redeem").getRequirement().test(p0)
				&& !sin.getChild("all").getRequirement().test(p0)
				&& !root.getChild("slots").getRequirement().test(p0)
				&& !angel.getChild("grant").getRequirement().test(p0)
				&& !genesis.getChild("reset").getRequirement().test(p0)
				&& !dragon.getRequirement().test(p0);
		boolean adminAllowed = sinArg.getChild("on").getRequirement().test(p2)
				&& sin.getChild("all").getRequirement().test(p2)
				&& angel.getChild("grant").getRequirement().test(p2)
				&& genesis.getChild("reset").getRequirement().test(p2)
				&& dragon.getRequirement().test(p2);
		log("命令权限：玩家节点（list/refresh/query/angel query/angel refresh）权限 0 可用=" + playerNodes
				+ "（应为 true）；管理节点权限 0 被拒=" + adminDenied + "（应为 true）、权限 2 可用=" + adminAllowed
				+ "（应为 true）");

		server.getCommands().performPrefixedCommand(p2, "summyreliquary sin all on");
		boolean allActive = SinManager.mask(player) == (1 << Sin.values().length) - 1;
		server.getCommands().performPrefixedCommand(p2, "summyreliquary sin all redeem");
		boolean allRedeemed = SinManager.redeemedMask(player) == (1 << Sin.values().length) - 1;
		server.getCommands().performPrefixedCommand(p2, "summyreliquary sin refresh");
		server.getCommands().performPrefixedCommand(p2, "summyreliquary angel refresh");
		log("批量命令：sin all on → 全激活=" + allActive + "（应为 true）、sin all redeem → 全赎罪="
				+ allRedeemed + "（应为 true）；sin refresh / angel refresh 执行无异常");
	}

	/** 按 UUID 找实体 */
	private static LivingEntity findEntity(ServerPlayer player, UUID id) {
		if (id == null) {
			return null;
		}
		for (LivingEntity entity : player.serverLevel().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(64.0D))) {
			if (entity.getUUID().equals(id)) {
				return entity;
			}
		}
		return null;
	}

	// ==================== 1.5.1：救恩半径 3/4 / 旋转火花边界 / 锁定基准 / 粒子束 ====================

	/** 准备：装上救恩（加护栏） */
	private static void prepareSalvationDomain(ServerPlayer player) {
		equipSalvation(player, true);
		log("救恩自检：已装上救恩（加护栏）");
	}

	/** 边界频率专用：这一刻清零计数，20 tick 后（1958）读取 —— 期间不能再手动推进领域，否则计数会失真 */
	private static void prepareSalvationBoundary(ServerPlayer player) {
		com.summy.reliquary.effect.SalvationDomain.resetBoundaryBatches();
		log("救恩边界：已清零批次计数，等待 20 tick");
	}

	/** 装上 / 卸下救恩（半径联动要用到 REVELATION 栏位，见 checkSalvationRadius） */
	private static void equipSalvation(ServerPlayer player, boolean equipped) {
		ensureAngelLine(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.BLESSING, 0,
				equipped ? new ItemStack(SummyReliquary.SALVATION.get()) : ItemStack.EMPTY));
	}

	/**
	 * 半径判定：默认半径 3 格 → 2.5 格命中、3.5 格不命中；
	 * 同时佩戴终末天启（半径 4）→ 3.5 格命中、4.5 格不命中。
	 */
	private static void checkSalvationRadius(ServerPlayer player) {
		resetSinState(player);
		player.removeAllEffects();
		equipSalvation(player, true);
		// ① 基础半径：先确保启示之座是空的（否则上一轮留下的终末天启会把半径顶到 4）
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		boolean near = judgedByDomain(player, 2.5D);
		boolean far = judgedByDomain(player, 3.5D);
		// ② 佩戴终末天启（联动半径）
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.REVELATION, 0, new ItemStack(SummyReliquary.FINAL_REVELATION.get())));
		boolean nearExtended = judgedByDomain(player, 3.5D);
		boolean farExtended = judgedByDomain(player, 4.5D);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		log(String.format("救恩半径：配置 基础=%.1f（应 3.0）、联动=%.1f（应 4.0）",
				com.summy.reliquary.config.ReliquaryConfig.salvationRadius(),
				com.summy.reliquary.config.ReliquaryConfig.salvationExtendedRadius()));
		log("救恩半径：基础 2.5 格命中=" + near + "（应 true）、3.5 格命中=" + far
				+ "（应 false）；戴终末天启 3.5 格命中=" + nearExtended + "（应 true）、4.5 格命中="
				+ farExtended + "（应 false）");
	}

	/** 在玩家正东 distance 格放一只僵尸，手动推进一次完整锁定，返回它是否被审判（掉血） */
	private static boolean judgedByDomain(ServerPlayer player, double distance) {
		Zombie zombie = spawnZombieAtSide(player, distance);
		if (zombie == null) {
			log("救恩半径：无法生成测试僵尸");
			return false;
		}
		zombie.setAbsorptionAmount(0.0F);
		zombie.setHealth(zombie.getMaxHealth());
		pumpSalvation(player);
		boolean judged = zombie.getHealth() < zombie.getMaxHealth() - 0.01F;
		zombie.discard();
		return judged;
	}

	/**
	 * 领域伤害：一次完整锁定恰好结算 7 点（穿钻石甲 + 抗性 IV 也不减免）、先扣黄血，
	 * 且同一 tick 内（0.5 秒冷却未过）不会二次结算。
	 */
	private static void checkSalvationDamage(ServerPlayer player) {
		equipSalvation(player, true);
		// ① 先扣黄血：给 10 点吸收，黄血够 → 全部由黄血承担、血条不掉
		Zombie shielded = spawnZombieAtSide(player, 2.5D);
		if (shielded == null) {
			log("救恩伤害：无法生成测试僵尸");
			return;
		}
		shielded.setHealth(shielded.getMaxHealth());
		shielded.setAbsorptionAmount(10.0F);
		// 锁定未满（少推进 1 tick）时不应该有任何结算
		pumpSalvation(player, Math.max(1, lockTicks() - 1));
		boolean earlySettle = shielded.getAbsorptionAmount() < 9.99F
				|| shielded.getHealth() < shielded.getMaxHealth() - 0.01F;
		pumpSalvation(player);
		float absorptionLeft = shielded.getAbsorptionAmount();
		float healthAfterAbsorb = shielded.getHealth();
		double absorbedDamage = 10.0D - absorptionLeft + (shielded.getMaxHealth() - healthAfterAbsorb);
		shielded.discard();

		// ② 固定 7 点：钻石四件套 + 抗性 IV 也不减免
		Zombie armored = spawnZombieAtSide(player, 2.5D);
		if (armored == null) {
			return;
		}
		armored.setHealth(armored.getMaxHealth());
		armored.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		armored.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		armored.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		armored.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 600, 3));
		float before = armored.getHealth();
		pumpSalvation(player);
		float after = armored.getHealth();
		// ③ 0.5 秒冷却：同一 tick 内再推一次完整锁定，不应二次结算
		pumpSalvation(player);
		float afterCooldown = armored.getHealth();
		armored.discard();
		log(String.format("救恩伤害：锁定未满就结算=%s（应 false）；吸收 10 时总伤害=%.1f（应 7，剩余吸收 %.1f、"
				+ "血条 %.1f）；钻石甲 + 抗性 IV 下 %.1f → %.1f（应恰好 -7）；同 tick 再锁定一轮后 %.1f（应不再掉血）",
				earlySettle, absorbedDamage, absorptionLeft, healthAfterAbsorb, before, after, afterCooldown));
	}

	/** 锁定基准：头顶基准必须是**碰撞箱顶部 + 3**（用矮生物鸡与高生物僵尸对比）；粒子束从蓄力点射向身体中部 */
	private static void checkSalvationLockBases(ServerPlayer player) {
		Zombie zombie = spawnZombieAtSide(player, 4.0D);
		net.minecraft.world.entity.animal.Chicken chicken =
				EntityType.CHICKEN.create(player.serverLevel());
		if (zombie == null || chicken == null) {
			log("锁定基准：无法生成测试生物");
			return;
		}
		chicken.moveTo(player.getX(), player.getY(), player.getZ() + 4.0D, 0.0F, 0.0F);
		player.serverLevel().addFreshEntity(chicken);
		Vec3 zombieCharge = com.summy.reliquary.effect.SalvationDomain.chargePoint(zombie);
		Vec3 chickenCharge = com.summy.reliquary.effect.SalvationDomain.chargePoint(chicken);
		double zombieTop = zombie.getBoundingBox().maxY;
		double chickenTop = chicken.getBoundingBox().maxY;
		boolean zombieOk = Math.abs(zombieCharge.y - (zombieTop + 3.0D)) < 1.0E-6;
		boolean chickenOk = Math.abs(chickenCharge.y - (chickenTop + 3.0D)) < 1.0E-6;
		double zombieBeam = zombieCharge.distanceTo(com.summy.reliquary.effect.SalvationDomain.bodyCenter(zombie));
		// 若错误地按"脚底 + 3"算，基准会比现在低一个身高（僵尸约 1.95、鸡约 0.7）
		double zombieWrongGap = zombieCharge.y - (zombie.getY() + 3.0D);
		double chickenWrongGap = chickenCharge.y - (chicken.getY() + 3.0D);
		zombie.discard();
		chicken.discard();
		log(String.format("锁定基准：僵尸 蓄力点 y=%.2f（碰撞箱顶 %.2f + 3）=%.2f、鸡 蓄力点 y=%.2f"
				+ "（碰撞箱顶 %.2f + 3，成立=%s）；按脚底算会分别低 %.2f / %.2f",
				zombieCharge.y, zombieTop, zombieTop + 3.0D, chickenCharge.y, chickenTop, chickenOk,
				zombieWrongGap, chickenWrongGap));
		log(String.format("粒子束：从蓄力点射向身体中部，长度 %.2f 格（= 3 + 半个身高），两处基准都按碰撞箱顶部算=%s",
				zombieBeam, zombieOk && chickenOk));
	}

	/** 1.5.1 默认值核对：半径 3/4、锁定 1 秒、伤害 7、冷却 0.5 秒、光柱 21 */
	private static void checkSalvationConfig(ServerPlayer player) {
		log(String.format("配置默认值：salvation_radius=%.1f（应 3.0）、salvation_extended_radius=%.1f（应 4.0）、"
				+ "salvation_lock_seconds=%.1f（应 1.0）、salvation_damage=%d（应 7）、"
				+ "salvation_cooldown_seconds=%.1f（应 0.5）、beam_length=%d（应 21）",
				com.summy.reliquary.config.ReliquaryConfig.salvationRadius(),
				com.summy.reliquary.config.ReliquaryConfig.salvationExtendedRadius(),
				com.summy.reliquary.config.ReliquaryConfig.salvationLockSeconds(),
				com.summy.reliquary.config.ReliquaryConfig.salvationDamage(),
				com.summy.reliquary.config.ReliquaryConfig.salvationCooldownSeconds(),
				com.summy.reliquary.config.ReliquaryConfig.beamLength()));
	}

	/** 边界生成频率：20 tick 内应生成 5 批（每 4 tick 一批） */
	private static void checkSalvationBoundary(ServerPlayer player) {
		int batches = com.summy.reliquary.effect.SalvationDomain.boundaryBatches();
		// 1.5.2 起改成每 tick 一批（电火花寿命只有 2~3 tick，靠密集重叠才看得出连续旋转）
		boolean ok = batches >= 18 && batches <= 22;
		log("救恩边界：20 tick 内批次数=" + batches + "（应 ≈20，每 tick 一批、每批 8 点、1.5°/tick）→ " + ok);
	}

	/** 救恩自检收尾：卸下救恩与终末天启，并清掉附近的测试生物 */
	private static void cleanupSalvationDomain(ServerPlayer player) {
		equipSalvation(player, false);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32.0D))) {
			mob.discard();
		}
		log("救恩自检收尾：已卸下救恩并清理附近测试生物");
	}

	/** 在玩家**正东** distance 格处生成一只僵尸（不依赖视线角度，距离可控） */
	private static Zombie spawnZombieAtSide(ServerPlayer player, double distance) {
		Zombie zombie = EntityType.ZOMBIE.create(player.serverLevel());
		if (zombie == null) {
			return null;
		}
		zombie.moveTo(player.getX() + distance, player.getY(), player.getZ(), 0.0F, 0.0F);
		player.serverLevel().addFreshEntity(zombie);
		return zombie;
	}

	/** 锁满一次所需 tick（按配置的锁定秒数换算） */
	private static int lockTicks() {
		return Math.max(1, (int) Math.round(
				com.summy.reliquary.config.ReliquaryConfig.salvationLockSeconds() * 20.0D));
	}

	/** 手动推进一次完整锁定，等价于领域跑满锁定时间 */
	private static void pumpSalvation(ServerPlayer player) {
		pumpSalvation(player, lockTicks());
	}

	/** 手动推进指定 tick 数的领域逻辑（用于核对"锁定未满不结算"与"冷却内不二次结算"） */
	private static void pumpSalvation(ServerPlayer player, int times) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		for (int index = 0; index < times; index++) {
			com.summy.reliquary.effect.SalvationDomain.tickServer(server);
		}
	}

	// ==================== 1.5.2：救恩粒子 / 痛悔短祷文案与节流 / 创世纪确认 / 重生点标记 / 美德提示门 ====================

	/** 救恩的锁定粒子与粒子束都必须是短寿命的 CRIT（END_ROD 会拖 3~3.5 秒） */
	private static void checkSalvationParticles(ServerPlayer player) {
		var lock = com.summy.reliquary.effect.SalvationDomain.lockParticle();
		var beam = com.summy.reliquary.effect.SalvationDomain.beamParticle();
		boolean ok = lock == net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK
				&& beam == net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK;
		log("救恩粒子：锁定=" + lock + "、粒子束=" + beam
				+ "（都应为 ELECTRIC_SPARK，寿命 2~3 tick，靠逐 tick 重复生成补出 0.5 秒存在感）→ " + ok
				+ "（应为 true）；锁定密度=" + com.summy.reliquary.effect.SalvationDomain.lockDensity()
				+ "（应 3）、束重画=" + com.summy.reliquary.effect.SalvationDomain.beamLingerTicks()
				+ " tick（应 10）");
	}

	/** 救恩粒子束的存活周期：打击后立刻有活跃光束，10 tick（0.5 秒）后自动清空 */
	private static void checkSalvationBeams(boolean expectedAlive) {
		int alive = com.summy.reliquary.effect.SalvationDomain.activeBeams();
		String hint = expectedAlive ? "（打击后应 > 0）" : "（0.5 秒后应为 0）";
		log("救恩粒子束存活：" + alive + " 条" + hint);
	}

	/**
	 * 救恩名单写入（回归 1.5.2 的 bug）：先把本模组根标签整个删掉，模拟"从没触发过本模组内容的玩家"。
	 * 旧写法 {@code getCompound(...).putBoolean(...)} 会写进游离标签而丢失，这里必须为 true。
	 */
	private static void checkSalvationTargetable(ServerPlayer player) {
		player.getPersistentData().remove(com.summy.reliquary.sin.SinManager.ROOT);
		com.summy.reliquary.effect.SalvationDomain.setTargetable(player, true);
		boolean on = com.summy.reliquary.effect.SalvationDomain.isTargetable(player);
		boolean persisted = player.getPersistentData()
				.getCompound(com.summy.reliquary.sin.SinManager.ROOT).contains("salvation_targetable");
		com.summy.reliquary.effect.SalvationDomain.setTargetable(player, false);
		boolean off = com.summy.reliquary.effect.SalvationDomain.isTargetable(player);
		log("救恩名单写入：根标签清空后 on=" + on + "（应 true）、NBT 里真的写进去了=" + persisted
				+ "（应 true）、再 off=" + off + "（应 false）");
	}

	/** 救恩名单写入：走真实命令（OP 权限）后读回真实值 */
	private static void checkSalvationTargetableCommand(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		var op = player.createCommandSourceStack().withPermission(2);
		server.getCommands().performPrefixedCommand(op,
				"summyreliquary salvation " + player.getName().getString() + " on");
		boolean on = com.summy.reliquary.effect.SalvationDomain.isTargetable(player);
		server.getCommands().performPrefixedCommand(op,
				"summyreliquary salvation " + player.getName().getString() + " off");
		boolean off = com.summy.reliquary.effect.SalvationDomain.isTargetable(player);
		log("救恩名单（命令）：salvation on → 真实值=" + on + "（应 true）、salvation off → 真实值=" + off
				+ "（应 false）");
	}

	/** 天使名单隔离（回归 1.5.2 的 bug）：自己有标记不能让别的玩家名字变金 */
	private static void checkAngelRosterIsolation() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		java.util.UUID own = client.player.getUUID();
		java.util.UUID stranger = java.util.UUID.nameUUIDFromBytes("summy-devcheck-stranger".getBytes());
		// 构造：自己带天使标记、名单为空
		com.summy.reliquary.client.ReliquaryClientState.update(own, 0, 0, false, 0, 0,
				com.summy.reliquary.client.ReliquaryClientState.FLAG_ANGEL, 0);
		com.summy.reliquary.client.ReliquaryClientState.setAngelRoster(java.util.List.of());
		boolean ownWithEmptyRoster = com.summy.reliquary.client.ReliquaryClientState.isAngel(own);
		boolean strangerWithEmptyRoster = com.summy.reliquary.client.ReliquaryClientState.isAngel(stranger);
		// 名单里只有"别人" → 只有他会变金
		com.summy.reliquary.client.ReliquaryClientState.setAngelRoster(java.util.List.of(stranger));
		boolean strangerInRoster = com.summy.reliquary.client.ReliquaryClientState.isAngel(stranger);
		boolean ownStillByOwnFlag = com.summy.reliquary.client.ReliquaryClientState.isAngel(own);
		log("天使名单隔离：自己（本地标记位）=" + ownWithEmptyRoster + "（应 true）、名单为空时别人="
				+ strangerWithEmptyRoster + "（应 false，这就是 1.5.2 的 bug）、名单含别人时他="
				+ strangerInRoster + "（应 true）、自己仍看自己的位=" + ownStillByOwnFlag + "（应 true）");
	}

	/** 痛悔短祷：三种位置失败的判定键（不在主世界 / 主世界超范围 / 坐标对但没看到天） */
	private static void checkContritionFailureKeys(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		// 先清掉个人重生点，避免上一轮自检残留影响判定
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, null, 0.0F, false, false);
		BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				overworld.getSharedSpawnPos()).above();

		// ① 不在主世界：下界里任意位置都应判「离天太远」
		ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
		String dimensionKey = null;
		if (nether != null) {
			BlockPos netherSpawn = nether.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					nether.getSharedSpawnPos()).above();
			player.teleportTo(nether, netherSpawn.getX() + 0.5D, netherSpawn.getY(),
					netherSpawn.getZ() + 0.5D, player.getYRot(), player.getXRot());
			dimensionKey = com.summy.reliquary.item.ActOfContritionItem.failureKey(player);
			player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
					player.getYRot(), player.getXRot());
		}

		// ② 在主世界但离出生点 / 重生点太远
		BlockPos far = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				spawn.offset(400, 0, 0)).above();
		player.teleportTo(overworld, far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D,
				player.getYRot(), player.getXRot());
		String rangeKey = com.summy.reliquary.item.ActOfContritionItem.failureKey(player);

		// ③ 回到出生点、露天应当通过；再钻到地下应判「头顶是石头」
		//（不用"头顶放石头"来造这个条件：方块放置后光照/高度图更新有延迟，canSeeSky 可能还是 true）
		player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
				player.getYRot(), player.getXRot());
		String openKey = com.summy.reliquary.item.ActOfContritionItem.failureKey(player);
		player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY() - 30.0D, spawn.getZ() + 0.5D,
				player.getYRot(), player.getXRot());
		String skyKey = com.summy.reliquary.item.ActOfContritionItem.failureKey(player);
		player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
				player.getYRot(), player.getXRot());

		boolean dimensionOk = com.summy.reliquary.item.ActOfContritionItem.FAIL_DIMENSION.equals(dimensionKey);
		boolean rangeOk = com.summy.reliquary.item.ActOfContritionItem.FAIL_RANGE.equals(rangeKey);
		boolean openOk = openKey == null;
		boolean skyOk = com.summy.reliquary.item.ActOfContritionItem.FAIL_SKY.equals(skyKey);
		log("痛悔短祷失败判定：不在主世界=" + dimensionKey + "（应 fail.dimension，成立=" + dimensionOk
				+ "）、主世界超范围=" + rangeKey + "（应 fail.range，成立=" + rangeOk
				+ "）、出生点露天=" + openKey + "（应 null，成立=" + openOk
				+ "）、钻到地下后=" + skyKey + "（应 fail.sky，成立=" + skyOk + "）");
	}

	/** 痛悔短祷：同一失败原因 5 秒内只提示一次，不同原因各自计时 */
	private static void checkContritionThrottle(ServerPlayer player) {
		com.summy.reliquary.item.ActOfContritionItem.forget(player);
		long start = 100000L;
		boolean first = com.summy.reliquary.item.ActOfContritionItem
				.claimFailureSlot(player, com.summy.reliquary.item.ActOfContritionItem.FAIL_RANGE, start);
		boolean tooSoon = com.summy.reliquary.item.ActOfContritionItem
				.claimFailureSlot(player, com.summy.reliquary.item.ActOfContritionItem.FAIL_RANGE, start + 50);
		boolean afterWindow = com.summy.reliquary.item.ActOfContritionItem
				.claimFailureSlot(player, com.summy.reliquary.item.ActOfContritionItem.FAIL_RANGE, start + 101);
		boolean otherReason = com.summy.reliquary.item.ActOfContritionItem
				.claimFailureSlot(player, com.summy.reliquary.item.ActOfContritionItem.FAIL_SKY, start + 102);
		boolean backToFirst = com.summy.reliquary.item.ActOfContritionItem
				.claimFailureSlot(player, com.summy.reliquary.item.ActOfContritionItem.FAIL_RANGE, start + 103);
		com.summy.reliquary.item.ActOfContritionItem.forget(player);
		log("痛悔短祷节流：首次=" + first + "（应 true）、2.5 秒后同原因=" + tooSoon + "（应 false）、"
				+ "5 秒后同原因=" + afterWindow + "（应 true）、换原因=" + otherReason + "（应 true）、"
				+ "再切回原原因=" + backToFirst + "（应 false）");
	}

	/** 痛悔短祷：成功 / 重复提示的行数与配色，以及「已经用过」提示终身只发一次 */
	private static void checkContritionMessages(ServerPlayer player) {
		List<net.minecraft.network.chat.Component> success =
				com.summy.reliquary.item.ActOfContritionItem.successLines();
		String successHead = success.get(0).getString();
		String successTailColor = colorOf(success.get(0).getSiblings().get(1));
		boolean quoteItalic = success.get(1).getStyle().isItalic();
		List<net.minecraft.network.chat.Component> repeat =
				com.summy.reliquary.item.ActOfContritionItem.repeatLines();
		String repeatColors = colorOf(repeat.get(0)) + "/" + colorOf(repeat.get(1)) + "/" + colorOf(repeat.get(2));
		boolean repeatLastItalic = repeat.get(2).getStyle().isItalic();
		String failColors = colorOf(com.summy.reliquary.item.ActOfContritionItem
				.failureLines(com.summy.reliquary.item.ActOfContritionItem.FAIL_SKY).get(1))
				+ "/" + com.summy.reliquary.item.ActOfContritionItem
						.failureLines(com.summy.reliquary.item.ActOfContritionItem.FAIL_SKY).get(1)
						.getStyle().isItalic();

		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		boolean firstNotify = com.summy.reliquary.item.ActOfContritionItem.notifyUsedOnce(player);
		boolean secondNotify = com.summy.reliquary.item.ActOfContritionItem.notifyUsedOnce(player);
		boolean notified = com.summy.reliquary.item.ActOfContritionItem.isNotified(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		log("痛悔短祷文案：成功行数=" + success.size() + "（应 2）、前半句=「" + successHead + "」、"
				+ "后半句配色=" + successTailColor + "（应 #FFD700）、引文斜体=" + quoteItalic + "（应 true）");
		log("痛悔短祷文案：重复行数=" + repeat.size() + "（应 3）、配色=" + repeatColors
				+ "（应 #AAAAAA/#8B0000/#555555）、第三行斜体=" + repeatLastItalic + "（应 true）；"
				+ "失败第二行=配色 " + failColors + "（应 #FFE4B5/true）");
		log("痛悔短祷「已用过」终身一次：第一次发=" + firstNotify + "（应 true）、第二次发=" + secondNotify
				+ "（应 false）、标记=" + notified + "（应 true）");
	}

	/** 创世纪：点「否」应当只清掉待确认状态、不执行重置 */
	private static void checkGenesisCancel(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		player.getInventory().add(new ItemStack(SummyReliquary.GENESIS.get()));
		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		boolean pending = com.summy.reliquary.item.GenesisItem.hasPending(player);
		boolean usedBeforeCancel = com.summy.reliquary.item.GenesisItem.isUsed(player);
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
				"summyreliquary genesis cancel");
		boolean pendingAfter = com.summy.reliquary.item.GenesisItem.hasPending(player);
		boolean usedAfter = com.summy.reliquary.item.GenesisItem.isUsed(player);
		log("创世纪（点否）：右键后待确认=" + pending + "（应 true）、使用标记=" + usedBeforeCancel
				+ "（应 false）；点否后待确认=" + pendingAfter + "（应 false）、使用标记=" + usedAfter
				+ "（应 false，说明没执行重置）");
	}

	/**
	 * 创世纪：点「是」才执行重置（1.6.10：连恶魔线一起清、**成就一律保留**）。
	 *
	 * <p>场景：恶魔线全开（契约 + 邪恶度 + 解锁位图 + 曾签约 + hell_locked）+ 派生数值（黑心 / 魂心 /
	 * 亚巴顿冷却）+ 已获取启示 + 无罪之人锁 + 已发放标记，然后点「是」逐项断言。
	 */
	private static void checkGenesisConfirm(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		// 「纯洁之人」与「有罪之人」确保已完成：用来验证"成就不被撤销 + 自愈补回天使标记"
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.REDEEMED_TO_VIRTUES);
		boolean pureDoneBefore = com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "pure");
		// 恶魔线全开
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 800.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0b1111111);
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		boolean pactBefore = com.summy.reliquary.effect.DemonPact.hasContract(player);
		// 派生数值 + 已获取启示 + 无罪之人锁 + 两件成就奖励的发放标记
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, true);
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, true);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 4.0D);
		com.summy.reliquary.effect.PlayerFlags.setAbaddonReviveReadyAt(player, 999999L);
		com.summy.reliquary.effect.PlayerFlags.setPentagramGranted(player, true);
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, true);
		// 1.7.1：恶魔王冠的"已发放"标记与物品也必须被创世纪清掉（删物品 + 清标记 → 重做圣经可再拿）
		com.summy.reliquary.effect.PlayerFlags.setDevilCrownGranted(player, true);
		player.getInventory().add(new ItemStack(SummyReliquary.DEVIL_CROWN.get()));
		// 1.6.10：另外三组"一周目进度"也要能被重置
		com.summy.reliquary.effect.PlayerFlags.setPentagramTicks(player, 300);
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, true);
		com.summy.reliquary.effect.RevelationTracker.reveal(player);
		com.summy.reliquary.sin.SinManager.mutableRoot(player)
				.putBoolean(com.summy.reliquary.item.ActOfContritionItem.USED_KEY, true);
		com.summy.reliquary.sin.SinManager.mutableRoot(player)
				.putBoolean(com.summy.reliquary.item.ActOfContritionItem.NOTIFIED_KEY, true);
		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.ACTIVATED);
		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
				"summyreliquary genesis confirm");
		boolean used = com.summy.reliquary.item.GenesisItem.isUsed(player);
		boolean sinsCleared = com.summy.reliquary.sin.SinManager.mask(player) == 0;
		int halo = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.THE_HALO.get());
		int source = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.SOURCE_OF_SINS.get());
		boolean angelCleared = !com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		int ourItemsLeft = countModItems(player);
		// 恶魔线全清
		boolean pactCleared = !com.summy.reliquary.effect.DemonPact.hasContract(player)
				&& com.summy.reliquary.effect.DemonPact.slotCount(player) == 0;
		boolean demonCleared = !com.summy.reliquary.effect.PlayerFlags.isDemon(player)
				&& !com.summy.reliquary.effect.PlayerFlags.isDemonSealed(player);
		boolean evilCleared = com.summy.reliquary.effect.PlayerFlags.evil(player) == 0.0D
				&& com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player) == 0
				&& !com.summy.reliquary.effect.PlayerFlags.isHellLocked(player);
		boolean derivedCleared = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player) == 0.0D
				&& com.summy.reliquary.effect.SoulShield.points(player) == 0.0D
				&& com.summy.reliquary.effect.PlayerFlags.abaddonReviveReadyAt(player) == 0L
				&& !com.summy.reliquary.effect.PlayerFlags.isRevelationObtained(player);
		boolean grantsReopened = !com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player)
				&& !com.summy.reliquary.effect.PlayerFlags.isStarGranted(player);
		// 1.7.1：恶魔王冠也在创世纪的清空范围内（删物品 + 清"已发放"标记）
		boolean crownCleared = !com.summy.reliquary.effect.PlayerFlags.isDevilCrownGranted(player)
				&& com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.DEVIL_CROWN.get()) == 0;
		// 成就不动 + 无罪之人的锁保持
		boolean advancementsKept = com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "pure")
				&& com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "sinner");
		boolean sinLockKept = com.summy.reliquary.effect.PlayerFlags.isSinRenounced(player);
		// 1.6.10：天使标记改成动作驱动 → 自愈**不该**再把它补回来（迁移只做一次）
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean angelNotAutoRestored = !com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		// 走一遍"七罪之源 → 美德"的动作 → 天使标记才回来（与一周目一致）
		resetSinState(player);
		setSinMasksForTest(player, 0b1111111, 0b1111111);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.SOUL_SEAL, 0, new ItemStack(SummyReliquary.SOURCE_OF_SINS.get())));
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.REDEMPTION.get()));
		boolean redeemed = com.summy.reliquary.effect.SinRedemption.tryRedeem(player,
				new PlayerInteractEvent.RightClickItem(player, net.minecraft.world.InteractionHand.MAIN_HAND));
		boolean angelByAction = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY));
		boolean progressCleared = !com.summy.reliquary.effect.PlayerFlags.isPentagramSpoken(player)
				&& com.summy.reliquary.effect.PlayerFlags.pentagramTicks(player) == 0
				&& !com.summy.reliquary.effect.RevelationTracker.isRevealed(player)
				&& com.summy.reliquary.effect.RevelationTracker.ticks(player) == 0
				&& !com.summy.reliquary.item.ActOfContritionItem.isUsed(player)
				&& !com.summy.reliquary.item.ActOfContritionItem.isNotified(player);
		log("创世纪（点是·1.6.10）：使用标记=" + used + "（应 true）、七罪已清空=" + sinsCleared + "（应 true）、"
				+ "重新发放 光环=" + halo + "（应 1）、七罪之源=" + source + "（应 1）、重置后身上的本模组物品="
				+ ourItemsLeft + "（应 2 = 只有重发的这两件）、天使标记被清=" + angelCleared + "（应 true）");
		log("创世纪·恶魔线全清：签约前有契约=" + pactBefore + "（应 true）→ 契约与栏位已清=" + pactCleared
				+ "（应 true）、恶魔标记与「曾签约」已清=" + demonCleared + "（应 true）、邪恶度/解锁/666 已清="
				+ evilCleared + "（应 true）；黑心/魂心/亚巴顿冷却/启示属性已清=" + derivedCleared
				+ "（应 true）、五芒星与伯列恒之星允许重领=" + grantsReopened
				+ "（应 true）、恶魔王冠已删物品并清发放标记=" + crownCleared + "（应 true）");
		log("创世纪·成就不动：纯洁之人（重置前=" + pureDoneBefore + "）与有罪之人仍为已完成=" + advancementsKept
				+ "（应 true）、无罪之人锁保持=" + sinLockKept + "（应 true）、自愈**不再**补回天使标记="
				+ angelNotAutoRestored + "（应 true）；走一遍「七罪之源 → 美德」转化后天使标记才回来="
				+ angelByAction + "（应 true，本次转化=" + redeemed + "）");
		log("创世纪·一周目进度重置：五芒星那句 300 秒的话 / 累计时间 / 伯列恒星的累计与坐标 / 痛悔短祷的终身一次 → 全部已清="
				+ progressCleared + "（应 true）");
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
	}

	/** 背包 + 全部 Curios 栏位里本模组物品的总件数 */
	private static int countModItems(ServerPlayer player) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (isModItem(stack)) {
				total += stack.getCount();
			}
		}
		var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			for (var entry : handler.getCurios().entrySet()) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					ItemStack stack = entry.getValue().getStacks().getStackInSlot(index);
					if (isModItem(stack)) {
						total += stack.getCount();
					}
				}
			}
		}
		return total;
	}

	/** 是否为本模组物品（按命名空间判定，与创世纪的删除口径一致） */
	private static boolean isModItem(ItemStack stack) {
		return !stack.isEmpty() && SummyReliquary.NAMESPACE
				.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
	}

	/** 设置「位于主世界的个人重生点」，供客户端标记位断言 */
	private static void grantOverworldRespawn(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		BlockPos spawn = server.overworld().getSharedSpawnPos();
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, spawn, 0.0F, true, false);
		RevelationTracker.sync(player);
		log("主世界重生点：已设置 → 服务端读取=" + com.summy.reliquary.effect.PlayerFlags.hasOverworldRespawn(player)
				+ "（应 true）");
		pendingRespawnCheck = 1;
		pendingRespawnCheckDelay = 0;
	}

	/** 清除个人重生点，供客户端标记位断言 */
	private static void clearOverworldRespawn(ServerPlayer player) {
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, null, 0.0F, false, false);
		RevelationTracker.sync(player);
		log("主世界重生点：已清除 → 服务端读取=" + com.summy.reliquary.effect.PlayerFlags.hasOverworldRespawn(player)
				+ "（应 false）");
		pendingRespawnCheck = 2;
		pendingRespawnCheckDelay = 0;
	}

	/** 把七罪全部置为已赎罪（配合客户端状态，验证美德提示门放行） */
	private static void unlockAllSinsForVirtues(ServerPlayer player) {
		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.REDEEMED);
		}
		com.summy.reliquary.effect.AttributeManager.apply(player);
		RevelationTracker.sync(player);
		log("美德提示门：七罪已全部置为已赎罪并同步（客户端应恢复显示风味行）");
	}

	/** 客户端：美德提示在七罪未赎清 / 已赎清时的两态 */
	private static void checkVirtuesTooltip(boolean allRedeemed) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// 直接按目标状态构造客户端缓存：提示门用的是客户端状态，
		// 这样断言不受"服务端改状态 → 发包 → 客户端处理"的时序影响
		int redeemed = allRedeemed ? (1 << Sin.values().length) - 1 : 0;
		com.summy.reliquary.client.ReliquaryClientState.update(
				client.player.getUUID(),
				com.summy.reliquary.client.ReliquaryClientState.sinMask(), redeemed,
				com.summy.reliquary.client.ReliquaryClientState.isRevealed(),
				com.summy.reliquary.client.ReliquaryClientState.revealX(),
				com.summy.reliquary.client.ReliquaryClientState.revealZ(),
				com.summy.reliquary.client.ReliquaryClientState.flags(),
				com.summy.reliquary.client.ReliquaryClientState.revealRemainingSeconds());
		List<net.minecraft.network.chat.Component> lines = new ItemStack(SummyReliquary.VIRTUES.get())
				.getTooltipLines(client.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		boolean hasTagline = lines.stream().anyMatch(line -> line.getString().contains("你已赎清罪过"));
		boolean hasLocked = lines.stream().anyMatch(line -> line.getString().contains("七罪尚未赎清"));
		if (allRedeemed) {
			log("美德提示（七罪已赎清）：含风味行=" + hasTagline + "（应 true）、含「七罪尚未赎清」=" + hasLocked
					+ "（应 false）");
		} else {
			log("美德提示（七罪未赎清）：含风味行=" + hasTagline + "（应 false）、含「七罪尚未赎清」=" + hasLocked
					+ "（应 true）");
		}
	}

	/** 客户端：「主世界有个人重生点」标记位是否按服务端同步到位 */
	private static void checkOverworldRespawnFlag(boolean expected) {
		boolean actual = com.summy.reliquary.client.ReliquaryClientState.hasOverworldRespawn();
		log("主世界重生点（客户端标记）：=" + actual + "（应 " + expected + "）");
	}

	// ==================== 1.5.4：圣光 / 神圣斗篷 / 占位物品 ====================

	/** 圣光 / 斗篷的门槛与常量、加护 2 格、占位物品注册 */
	private static void checkHolyLightSetup(ServerPlayer player) {
		ensureAngelLine(player);
		com.summy.reliquary.effect.HolyLightEffect.reset();
		com.summy.reliquary.effect.HolyMantle.reset();
		// ① 门槛：没有天使标记时不能佩戴
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		boolean lightLocked = !canEquipBlessing(player, SummyReliquary.HOLY_LIGHT.get());
		boolean mantleLocked = !canEquipBlessing(player, SummyReliquary.HOLY_MANTLE.get());
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		boolean lightOk = canEquipBlessing(player, SummyReliquary.HOLY_LIGHT.get());
		// ② 加护 2 格：救恩 + 圣光同时佩戴
		int slots = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(ReliquarySlots.BLESSING)
						? handler.getCurios().get(ReliquarySlots.BLESSING).getSlots() : -1)
				.orElse(-1);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, new ItemStack(SummyReliquary.SALVATION.get()));
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 1, new ItemStack(SummyReliquary.HOLY_LIGHT.get()));
		});
		boolean bothWorn = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.findFirstCurio(SummyReliquary.SALVATION.get()).isPresent()
						&& handler.findFirstCurio(SummyReliquary.HOLY_LIGHT.get()).isPresent())
				.orElse(false);
		// ③ 常量与配置
		boolean constants = com.summy.reliquary.effect.HolyLightEffect.columnLingerTicks() == 3
				&& com.summy.reliquary.effect.HolyLightEffect.columnParticlesPerPoint() == 2
				&& com.summy.reliquary.effect.HolyLightEffect.burstRing() == 8;
		boolean config = com.summy.reliquary.config.ReliquaryConfig.holyLightChancePercent() == 15
				&& com.summy.reliquary.config.ReliquaryConfig.holyLightDamagePercent() == 120
				&& com.summy.reliquary.config.ReliquaryConfig.holyMantleInvulnerableTicks() == 20;
		// ④ 占位物品注册
		String[] placeholders = {"vengeful_spirit", "the_pact", "the_mark", "ceremonial_robes",
				"pentagram", "godhead", "sacred_heart", "night_wraith", "brimstone", "occult_eye",
				"satanic_bible"};
		StringBuilder missing = new StringBuilder();
		for (String id : placeholders) {
			if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(SummyReliquary.id(id))) {
				missing.append(id).append(' ');
			}
		}
		log("圣光/斗篷门槛：无标记时圣光=" + lightLocked + "、斗篷=" + mantleLocked + "（应都 true）；"
				+ "有标记时圣光=" + lightOk + "（应 true）");
		log("加护栏位：格数=" + slots + "（应 2）、救恩+圣光同时佩戴=" + bothWorn + "（应 true）");
		log("圣光常量：光柱=" + com.summy.reliquary.effect.HolyLightEffect.columnLingerTicks()
				+ " tick（应 3）、每点=" + com.summy.reliquary.effect.HolyLightEffect.columnParticlesPerPoint()
				+ " 颗（应 2）、爆发环=" + com.summy.reliquary.effect.HolyLightEffect.burstRing()
				+ "（应 8）→ " + constants + "（应 true）；配置 15/120/20tick=" + config + "（应 true）");
		log("已注册物品（含占位）：" + placeholders.length + " 件 → 缺失="
				+ (missing.length() == 0 ? "无" : missing.toString()) + "（应为无）");
	}

	/** 圣光数值：强制命中下 基准 ×1.2；钻石甲不减、抗性 IV 减到 20%、无视无敌帧 */
	private static void checkHolyLightDamage(ServerPlayer player) {
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(true);
		Zombie plain = spawnHolyLightTarget(player, 6.0D);
		Zombie armored = spawnHolyLightTarget(player, 8.0D);
		armored.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		armored.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		armored.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		Zombie resistant = spawnHolyLightTarget(player, 10.0D);
		resistant.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 600, 3));
		if (plain == null || armored == null || resistant == null) {
			log("圣光数值：无法生成测试目标");
			return;
		}
		float plainDamage = holyLightOnce(player, plain, 10.0F, false);
		float armoredDamage = holyLightOnce(player, armored, 10.0F, false);
		float resistantDamage = holyLightOnce(player, resistant, 10.0F, false);
		float iframeDamage = holyLightOnce(player, plain, 10.0F, true);
		plain.discard();
		armored.discard();
		resistant.discard();
		log(String.format("圣光数值（基准 10 → 应 12）：普通=%.1f、钻石甲=%.1f（魔法口径无视护甲，应仍 12）、"
				+ "抗性 IV=%.1f（应约 2.4）、目标无敌帧 20 时=%.1f（应仍 12 = 无视无敌帧）",
				plainDamage, armoredDamage, resistantDamage, iframeDamage));
	}

	/** 圣光多段求和：同一次攻击里"近战 + 不同类型的追加段"只掷一次、基准相加 */
	private static void checkHolyLightMultiSegment(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(true);
		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			return;
		}
		int before = com.summy.reliquary.effect.HolyLightEffect.triggerCount();
		float healthBefore = zombie.getHealth();
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(zombie,
						player.damageSources().playerAttack(player), 10.0F));
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(zombie,
						player.damageSources().indirectMagic(player, player), 6.0F));
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		int triggers = com.summy.reliquary.effect.HolyLightEffect.triggerCount() - before;
		float damage = healthBefore - zombie.getHealth();
		zombie.discard();
		log(String.format("圣光多段求和：近战 10 + 追加段 6 → 触发 %d 次（应 1）、总伤害 %.1f（应 19.2 = (10+6)×1.2）",
				triggers, damage));
	}

	/** 圣光「出其不意」：同源第二次命中视为两次独立攻击 */
	private static void checkHolyLightSurprise(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(true);
		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			return;
		}
		int before = com.summy.reliquary.effect.HolyLightEffect.triggerCount();
		float healthBefore = zombie.getHealth();
		var melee = player.damageSources().playerAttack(player);
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(zombie, melee, 10.0F));
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(zombie, melee, 10.0F));
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		int triggers = com.summy.reliquary.effect.HolyLightEffect.triggerCount() - before;
		float damage = healthBefore - zombie.getHealth();
		zombie.discard();
		log(String.format("圣光「出其不意」：同源两段 10+10 → 触发 %d 次（应 2）、总伤害 %.1f（应 24 = 12×2）",
				triggers, damage));
	}

	/** 圣光几率：400 次命中统计触发率（应约 15%） */
	private static void checkHolyLightSample(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(null);
		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			return;
		}
		int before = com.summy.reliquary.effect.HolyLightEffect.triggerCount();
		int trials = 400;
		for (int index = 0; index < trials; index++) {
			zombie.setHealth(zombie.getMaxHealth());
			com.summy.reliquary.effect.HolyLightEffect.record(
					new net.minecraftforge.event.entity.living.LivingHurtEvent(zombie,
							player.damageSources().playerAttack(player), 10.0F));
			com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
			// 采样时不需要真的保留光柱（否则会堆积几百条）
			com.summy.reliquary.effect.HolyLightEffect.clearColumns();
		}
		int hits = com.summy.reliquary.effect.HolyLightEffect.triggerCount() - before;
		zombie.discard();
		log("圣光几率：" + trials + " 次命中触发 " + hits + " 次（约 15% = 60 次，容差 20~110）→ "
				+ (hits >= 20 && hits <= 110));
	}

	/** 神圣斗篷：受击后开启 1 秒无敌，无敌期内第二击被取消 */
	private static void checkHolyMantle(ServerPlayer player) {
		ensureAngelLine(player);
		com.summy.reliquary.effect.HolyMantle.reset();
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BLESSING, 1,
						new ItemStack(SummyReliquary.HOLY_MANTLE.get())));
		boolean worn = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.findFirstCurio(SummyReliquary.HOLY_MANTLE.get()).isPresent())
				.orElse(false);
		var source = player.damageSources().magic();
		net.minecraftforge.event.entity.living.LivingHurtEvent first =
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player, source, 5.0F);
		com.summy.reliquary.effect.HolyMantle.onHurt(first);
		boolean guarded = com.summy.reliquary.effect.HolyMantle.isGuarded(player);
		long window = com.summy.reliquary.effect.HolyMantle.guardUntil(player)
				- player.serverLevel().getGameTime();
		net.minecraftforge.event.entity.living.LivingHurtEvent second =
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player, source, 5.0F);
		com.summy.reliquary.effect.HolyMantle.onHurt(second);
		log("神圣斗篷：已佩戴=" + worn + "（应 true）、受击后无敌=" + guarded + "（应 true）、窗口=" + window
				+ " tick（应 20）；无敌期内第二击被取消=" + second.isCanceled() + "（应 true）、伤害="
				+ second.getAmount() + "（应 0.0）");
	}

	/** 神圣斗篷：1 秒后无敌应当过期 */
	private static void checkHolyMantleExpired(ServerPlayer player) {
		boolean guarded = com.summy.reliquary.effect.HolyMantle.isGuarded(player);
		log("神圣斗篷：1 秒后无敌应已过期 → " + guarded + "（应 false）、累计开启 "
				+ com.summy.reliquary.effect.HolyMantle.triggerCount() + " 次（应 1）");
	}

	/** 客户端：占位物品提示只有「名字 + 功能待补充」 */
	private static void checkPlaceholderTooltip() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// 1.6.8：亚巴顿也转正了 —— 现在**没有任何占位物品**
		boolean stillPlaceholder = SummyReliquary.ABADDON.get() instanceof com.summy.reliquary.item.PlaceholderItem;
		ItemStack abaddon = new ItemStack(SummyReliquary.ABADDON.get());
		List<Component> lines = abaddon
				.getTooltipLines(client.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		boolean hasPending = lines.stream().anyMatch(line -> line.getString().contains("功能待补充"));
		log("占位物品提示（1.6.8 起 0 件）：亚巴顿仍是占位=" + stillPlaceholder + "（应 false）、"
				+ "提示含「功能待补充」=" + hasPending + "（应 false）、提示行数=" + lines.size()
				+ "（应 ≥ 2 = 名字 + 描述，且受门槛影响）");
	}

	/** 客户端：圣光独立死亡文本 */
	private static void checkHolyLightDeathText() {
		String base = net.minecraft.network.chat.Component
				.translatable("death.attack.summy-reliquary.holy_light").getString();
		String killed = net.minecraft.network.chat.Component
				.translatable("death.attack.summy-reliquary.holy_light.player",
						"netherite_block", "Dev").getString();
		boolean ok = base.contains("圣光") && killed.contains("圣光");
		log("圣光死亡文本：base=「" + base + "」、player=「" + killed + "」→ " + ok + "（应 true）");
	}

	// ==================== 1.5.4：小工具 ====================

	/** 该物品能不能装进加护栏位（走 Curios 的物品接口判定） */
	private static boolean canEquipBlessing(ServerPlayer player, Item item) {
		return ((top.theillusivec4.curios.api.type.capability.ICurioItem) item)
				.canEquip(new SlotContext(ReliquarySlots.BLESSING, player, 0, false, true), new ItemStack(item));
	}

	/** 生成一只高血量测试僵尸（用于圣光数值与采样） */
	private static Zombie spawnHolyLightTarget(ServerPlayer player, double distance) {
		Zombie zombie = spawnZombieAtSide(player, distance);
		if (zombie == null) {
			return null;
		}
		zombie.setNoAi(true);
		zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		zombie.setHealth(200.0F);
		zombie.setInvulnerable(false);
		return zombie;
	}

	/** 记录一次近战命中并立即结算，返回目标实际掉血量 */
	private static float holyLightOnce(ServerPlayer player, Zombie target, float baseline, boolean lockIframes) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return 0.0F;
		}
		target.setHealth(target.getMaxHealth());
		float before = target.getHealth();
		if (lockIframes) {
			target.invulnerableTime = 20;
		}
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
						player.damageSources().playerAttack(player), baseline));
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		return before - target.getHealth();
	}

	// ==================== 1.5.5：圣心 / 神性 ====================

	/** 把加护两个槽位清空 */
	private static void clearBlessingSlots(ServerPlayer player) {
		ensureAngelLine(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 1, ItemStack.EMPTY);
		});
	}

	/** 圣心：属性逐项断言与卸下回收 */
	private static void checkSacredHeartStats(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		resetSinState(player);
		// 隔离其它属性来源：光环 / 魂印（美德）/ 启示之座 / 加护
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
		});
		clearBlessingSlots(player);
		// 手上换成钻石剑：裸手攻速 4.0 会被模组的攻速上限压住，测不出 +0.5
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double baseHealth = player.getAttributeValue(Attributes.MAX_HEALTH);
		double baseArmor = player.getAttributeValue(Attributes.ARMOR);
		double baseToughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
		double baseAttackSpeed = player.getAttributeValue(Attributes.ATTACK_SPEED);
		double baseMovement = player.getAttributeValue(Attributes.MOVEMENT_SPEED);

		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BLESSING, 0,
						new ItemStack(SummyReliquary.SACRED_HEART.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double heartHealth = player.getAttributeValue(Attributes.MAX_HEALTH) - baseHealth;
		double heartArmor = player.getAttributeValue(Attributes.ARMOR) - baseArmor;
		double heartToughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS) - baseToughness;
		double heartAttackSpeed = player.getAttributeValue(Attributes.ATTACK_SPEED) - baseAttackSpeed;
		double heartMovement = player.getAttributeValue(Attributes.MOVEMENT_SPEED) - baseMovement;
		// 攻速：裸手 4.0 正好等于模组的攻速上限，+0.5 会被上限吃掉显示不出来，
		// 所以直接核对"装上去的那条修饰符"的值（上限逻辑本身由既有用例覆盖）。
		double attackSpeedModifier = 0.0D;
		var attackSpeedInstance = player.getAttribute(Attributes.ATTACK_SPEED);
		if (attackSpeedInstance != null) {
			for (var modifier : attackSpeedInstance.getModifiers()) {
				if ("sacred_heart_attack_speed".equals(modifier.getName())) {
					attackSpeedModifier = modifier.getAmount();
				}
			}
		}
		log(String.format("圣心属性：生命 +%.1f（应 10）、护甲 +%.1f（应 5）、韧性 +%.1f（应 5）、"
						+ "攻速修饰符 +%.2f（应 0.50；总攻速 +%.2f，受 4.0 上限影响）、"
						+ "移速 +%.4f（应约 %.4f = 基准 15%%）",
				heartHealth, heartArmor, heartToughness, attackSpeedModifier, heartAttackSpeed, heartMovement,
				baseMovement * 0.15D));

		clearBlessingSlots(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double afterHealth = player.getAttributeValue(Attributes.MAX_HEALTH);
		log(String.format("圣心卸下后：生命 %.1f（应回到基准 %.1f）、护甲 %.1f（应回到 %.1f）",
				afterHealth, baseHealth, player.getAttributeValue(Attributes.ARMOR), baseArmor));
	}

	/** 圣心 / 神性的「全伤害最终倍率」：同乘区相加，且不会把圣光伤害再乘一遍 */
	private static void checkSacredHeartDamage(ServerPlayer player) {
		ensureAngelLine(player);
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		resetSinState(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
			for (int index = 0; index < 3; index++) {
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, index, ItemStack.EMPTY);
			}
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
		});
		clearBlessingSlots(player);
		Zombie target = spawnHolyLightTarget(player, 6.0D);
		if (target == null) {
			log("圣心出伤：无法生成测试目标");
			return;
		}
		target.setHealth(target.getMaxHealth());
		float plain = outgoingDamage(player, target, 10.0F);

		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BLESSING, 0,
						new ItemStack(SummyReliquary.SACRED_HEART.get())));
		float heartOnly = outgoingDamage(player, target, 10.0F);

		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.GODHEAD.get())));
		float both = outgoingDamage(player, target, 10.0F);

		// 圣光：佩戴圣光时，基准会吃到 ×1.5，但圣光伤害本身不再乘一遍
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BLESSING, 1,
						new ItemStack(SummyReliquary.HOLY_LIGHT.get())));
		com.summy.reliquary.effect.HolyLightEffect.reset();
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(true);
		target.setHealth(target.getMaxHealth());
		float before = target.getHealth();
		// 1.6.2：合成的伤害事件也要先清掉"受伤节流"记录
		com.summy.reliquary.effect.CombatTuning.clear();
		var hurt = new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
				player.damageSources().playerAttack(player), 10.0F);
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(hurt);
		float baselineSeen = hurt.getAmount();
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		float holyDamage = before - target.getHealth();
		com.summy.reliquary.effect.HolyLightEffect.reset();
		target.discard();
		log(String.format("圣心/神性出伤（基准 10）：无饰品=%.1f（应 10）、仅圣心=%.1f（应 13）、"
						+ "圣心+神性=%.1f（应 15 = 同乘区相加，不是 15.6）",
				plain, heartOnly, both));
		log(String.format("圣光不吃最终倍率二遍：事件后基准=%.1f（应 15）、圣光实际伤害=%.1f（应 18 = 15×1.2）",
				baselineSeen, holyDamage));
	}

	/** 走一遍真实事件链取"各类倍率之后"的出伤（会经过本模组与其它模组的处理器） */
	private static float outgoingDamage(ServerPlayer player, LivingEntity target, float base) {
		// 1.6.2：受伤无敌帧改成自定义节流后，连续合成的伤害事件也会被窗口挡下；
		// 这里要测的是"倍率管线"，所以先清掉节流记录。
		com.summy.reliquary.effect.CombatTuning.clear();
		var event = new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
				player.damageSources().playerAttack(player), base);
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
		// 事件会顺带被圣光记录，这里清掉免得影响后续用例
		com.summy.reliquary.effect.HolyLightEffect.reset();
		return event.getAmount();
	}

	/** 圣心的箭矢追踪：只吸 8 格内的敌对生物 */
	private static void checkSacredHeartHoming(ServerPlayer player) {
		ensureAngelLine(player);
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		// 先戴上圣心（加护 1 号位；0 号位可能被别的用例占着）
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BLESSING, 1,
						new ItemStack(SummyReliquary.SACRED_HEART.get())));
		Zombie near = spawnHolyLightTarget(player, 6.0D);
		if (near == null) {
			return;
		}
		net.minecraft.world.entity.projectile.Arrow arrow =
				new net.minecraft.world.entity.projectile.Arrow(player.serverLevel(), player);
		// 箭在玩家身边朝 -Z 直飞，僵尸在 +X 侧 4 格：与目标方向近乎垂直，便于看出偏转
		arrow.setPos(player.getX() + 2.0D, player.getY() + 1.0D, player.getZ());
		arrow.setDeltaMovement(0.0D, 0.0D, -1.0D);
		player.serverLevel().addFreshEntity(arrow);
		double beforeDot = arrow.getDeltaMovement().normalize()
				.dot(near.getEyePosition().subtract(arrow.position()).normalize());
		com.summy.reliquary.effect.SacredHeart.tick(server);
		double afterDot = arrow.getDeltaMovement().normalize()
				.dot(near.getEyePosition().subtract(arrow.position()).normalize());
		arrow.discard();
		near.discard();
		log(String.format("圣心箭矢追踪：偏转前 dot=%.3f → 偏转后 dot=%.3f（应变大，说明朝目标转向）",
				beforeDot, afterDot));
	}

	/** 神性：互斥、继承属性、飞行不减速、光柱覆盖值、救恩半径 */
	private static void checkGodheadBasics(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		// 1.5.7：门槛只在"往空槽新装"时拦（已在位是重算，会放行），所以先清空启示之座再测
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		boolean godheadLocked;
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		godheadLocked = !canEquipIn(player, SummyReliquary.GODHEAD.get(), ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		boolean godheadOk = canEquipIn(player, SummyReliquary.GODHEAD.get(), ReliquarySlots.REVELATION);
		int revelationSlots = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(ReliquarySlots.REVELATION)
						? handler.getCurios().get(ReliquarySlots.REVELATION).getSlots() : -1)
				.orElse(-1);
		// 继承：魂心 +2、攻速 +20%（手上仍是钻石剑）
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
		});
		clearBlessingSlots(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double baseSpeed = player.getAttributeValue(Attributes.ATTACK_SPEED);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.GODHEAD.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double godheadSpeed = player.getAttributeValue(Attributes.ATTACK_SPEED);
		double soulHearts = player.getAttributeValue(ReliquaryAttributes.SOUL_HEARTS.get());
		// 飞行：生存模式也保持原版 0.05
		var previousMode = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		boolean mayfly = player.getAbilities().mayfly;
		float flyingSpeed = player.getAbilities().getFlyingSpeed();
		player.setGameMode(previousMode);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double salvationRadius = com.summy.reliquary.effect.SalvationDomain.radiusFor(player);
		log("神性：无标记不可佩戴=" + godheadLocked + "（应 true）、有标记可佩戴=" + godheadOk + "（应 true）、"
				+ "启示之座格数=" + revelationSlots + "（应 1 = 与星/天启互斥）");
		log(String.format("神性继承：魂心=%.1f（应 %.1f）、攻速 %.2f → %.2f（应 ×1.2）、"
						+ "生存可飞=%s（应 true）、飞行速度=%.3f（应 0.050 不减速）、救恩半径=%.1f（应 5.0）",
				soulHearts, com.summy.reliquary.config.ReliquaryConfig.finalSoulHearts(),
				baseSpeed, godheadSpeed, mayfly, flyingSpeed, salvationRadius));
		log("神性光柱覆盖：蓄力=" + com.summy.reliquary.effect.Godhead.beamChargeTicks(player)
				+ " tick（应 30）、冷却=" + com.summy.reliquary.effect.Godhead.beamCooldownTicks(player)
				+ " tick（应 100）、半径=" + com.summy.reliquary.effect.Godhead.beamRadius(player)
				+ "（应 3）、射程=" + com.summy.reliquary.effect.Godhead.beamLength(player) + "（应 35）");
		// 训练人偶：软检测按实体 id 判定（真实假人需要装了 dummmmmmy 才能测，留人工）
		boolean dummyId = com.summy.reliquary.effect.DummySupport.isDummyId(
				net.minecraft.resources.ResourceLocation.tryParse("dummmmmmy:target_dummy"));
		boolean otherId = com.summy.reliquary.effect.DummySupport.isDummyId(
				net.minecraft.resources.ResourceLocation.tryParse("minecraft:zombie"));
		log("训练人偶判定：dummmmmmy:target_dummy=" + dummyId + "（应 true）、minecraft:zombie=" + otherId
				+ "（应 false）、开关默认=" + com.summy.reliquary.config.ReliquaryConfig.affectTargetDummies()
				+ "（应 true）");
	}

	/** 神性 8 格光环：真实伤害 + 目标口径 */
	private static void checkGodheadAura(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		Zombie near = spawnHolyLightTarget(player, 6.0D);
		Zombie far = spawnHolyLightTarget(player, 12.0D);
		if (near == null || far == null) {
			return;
		}
		near.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		near.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 600, 3));
		net.minecraft.world.entity.animal.Wolf wolf = EntityType.WOLF.create(player.serverLevel());
		if (wolf != null) {
			wolf.moveTo(player.getX() + 4.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
			wolf.setNoAi(true);
			wolf.setTarget(player);
			player.serverLevel().addFreshEntity(wolf);
		}
		net.minecraft.world.entity.animal.Cow cow = EntityType.COW.create(player.serverLevel());
		if (cow != null) {
			cow.moveTo(player.getX() - 4.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
			cow.setNoAi(true);
			player.serverLevel().addFreshEntity(cow);
		}
		float nearBefore = near.getHealth();
		float farBefore = far.getHealth();
		float wolfBefore = wolf == null ? 0.0F : wolf.getHealth();
		float cowBefore = cow == null ? 0.0F : cow.getHealth();
		int hits = com.summy.reliquary.effect.Godhead.judgeAuraNow(player);
		float nearDamage = nearBefore - near.getHealth();
		float farDamage = farBefore - far.getHealth();
		float wolfDamage = wolf == null ? 0.0F : wolfBefore - wolf.getHealth();
		float cowDamage = cow == null ? 0.0F : cowBefore - cow.getHealth();
		boolean cowIsTarget = cow != null && com.summy.reliquary.effect.Godhead.isAuraTarget(player, cow);
		boolean selfIsTarget = com.summy.reliquary.effect.Godhead.isAuraTarget(player, player);
		near.discard();
		far.discard();
		if (wolf != null) {
			wolf.discard();
		}
		if (cow != null) {
			cow.discard();
		}
		log(String.format("神性光环（结算 %d 个目标）：6 格僵尸 −%.1f（应 2，穿钻石甲 + 抗性 IV 也不减）、"
						+ "仇恨你的狼 −%.1f（应 2）、12 格僵尸 −%.1f（应 0）、无害的牛 −%.1f（应 0）；"
						+ "牛算目标=%s（应 false）、自己算目标=%s（应 false，口径与救恩一致：玩家看名单）",
				hits, nearDamage, wolfDamage, farDamage, cowDamage, cowIsTarget, selfIsTarget));
	}

	/** 神性环境免疫：清单内全免疫、虚空不免疫 */
	private static void checkGodheadImmunity(ServerPlayer player) {
		var level = player.serverLevel();
		var registry = level.registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
		java.util.function.Function<net.minecraft.resources.ResourceKey<
				net.minecraft.world.damagesource.DamageType>,
				net.minecraft.world.damagesource.DamageSource> sourceOf =
				key -> new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(key));
		boolean fire = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.IN_FIRE)
				&& immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.ON_FIRE)
				&& immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.LAVA)
				&& immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR);
		boolean fall = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.FALL);
		boolean cactus = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.CACTUS);
		boolean drown = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.DROWN);
		boolean lightning = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT);
		boolean explosion = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.EXPLOSION);
		boolean cramming = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.CRAMMING);
		boolean anvil = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.FALLING_ANVIL);
		boolean kinetic = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.FLY_INTO_WALL);
		boolean freeze = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.FREEZE);
		boolean wall = immune(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.IN_WALL);
		boolean voidNotImmune = !immune(player, sourceOf,
				net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD);
		log("神性环境免疫：燃烧=" + fire + "（应 true，含岩浆/热地板）、坠落=" + fall + "、仙人掌=" + cactus
				+ "、溺水=" + drown + "、闪电=" + lightning + "、爆炸=" + explosion + "、挤压=" + cramming
				+ "、铁砧=" + anvil + "、动能=" + kinetic + "、冰冻=" + freeze + "、窒息=" + wall
				+ "（应都 true）；虚空不被免疫=" + voidNotImmune + "（应 true）");
	}

	private static boolean immune(ServerPlayer player,
			java.util.function.Function<net.minecraft.resources.ResourceKey<
					net.minecraft.world.damagesource.DamageType>,
					net.minecraft.world.damagesource.DamageSource> sourceOf,
			net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> key) {
		return com.summy.reliquary.effect.Godhead.isEnvironmentImmune(player, sourceOf.apply(key));
	}

	/** 神性死亡拦截（1.6.4：命中前整击拦下）＋ 1 点生命 + 传送回重生点 + 金色粒子 */
	private static void checkGodheadDeathGuard(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.Godhead.reset();
		BlockPos spawn = player.serverLevel().getSharedSpawnPos();
		BlockPos respawn = spawn.offset(5, 0, 5);
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, respawn, 0.0F, true, false);
		player.setHealth(5.0F);
		// 估算口径：这一击会打到血的部分 ≥ 当前生命 → 拦下
		boolean nullified = com.summy.reliquary.effect.Godhead.tryNullify(player, 10.0F);
		float health = player.getHealth();
		double distance = player.position().distanceTo(
				new Vec3(respawn.getX() + 0.5D, respawn.getY() + 1.0D, respawn.getZ() + 0.5D));
		boolean particle = com.summy.reliquary.effect.Godhead.teleportParticle()
				== net.minecraft.core.particles.ParticleTypes.WAX_ON;
		log(String.format("神性死亡拦截：拦下=%s（应 true）、生命=%.1f（应 1.0）、"
						+ "与重生点距离=%.2f（应 < 1）、拦截次数=%d（应 1）、落地粒子是金色 WAX_ON=%s（应 true）",
				nullified, health, distance, com.summy.reliquary.effect.Godhead.deathGuardCount(),
				particle));
		// 收尾：恢复血量与重生点
		player.setHealth(player.getMaxHealth());
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, null, 0.0F, false, false);
	}

	/** 客户端：经文前缀 + 圣心 / 神性的 Shift 文本与样式 */
	private static void checkNewItemTexts() {
		String mantle = net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.holy_mantle.desc.2").getString();
		String heart = net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.sacred_heart.desc.2").getString();
		String godhead = net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.godhead.desc.2").getString();
		boolean prefix = mantle.startsWith("——") && heart.startsWith("——") && godhead.startsWith("——");
		List<net.minecraft.network.chat.Component> heartLines =
				com.summy.reliquary.item.SacredHeartItem.shiftLines();
		String heartColors = colorOf(heartLines.get(0)) + "/" + colorOf(heartLines.get(1)) + "/"
				+ colorOf(heartLines.get(2));
		List<net.minecraft.network.chat.Component> godLines =
				com.summy.reliquary.item.GodheadItem.shiftLines();
		var firstPrefix = godLines.get(0).getSiblings().get(0);
		var firstTail = godLines.get(0).getSiblings().get(1);
		log("经文前缀：斗篷=「" + mantle + "」、圣心=「" + heart + "」、神性=「" + godhead + "」→ " + prefix
				+ "（应 true）");
		log("圣心 Shift：" + heartLines.size() + " 行（应 3）、配色=" + heartColors
				+ "（应 #AAAAAA/#FFE4B5/#AAAAAA）、第二行=「" + heartLines.get(1).getString() + "」");
		log("神性 Shift：" + godLines.size() + " 行（应 5）、首行=「" + godLines.get(0).getString()
				+ "」、前缀配色=" + colorOf(firstPrefix) + "（应 #FFE4B5）、前缀斜体="
				+ firstPrefix.getStyle().isItalic() + "（应 true）、尾巴配色=" + colorOf(firstTail)
				+ "（应 #FFFFFF）");
		// 心之碎片：三行（淡金斜体经文 + 灰色出处 + 白色正体）
		List<net.minecraft.network.chat.Component> shard = new ItemStack(SummyReliquary.HEART_SHARD.get())
				.getTooltipLines(Minecraft.getInstance().player,
						net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		log("心之碎片提示：" + shard.size() + " 行（应 3 = 名字 + 三行说明 → 共 4？按实际）、"
				+ "首行=「" + (shard.isEmpty() ? "" : shard.get(0).getString()) + "」");
		for (net.minecraft.network.chat.Component line : shard) {
			log("   「" + line.getString() + "」 颜色=" + colorOf(line) + " 斜体=" + line.getStyle().isItalic());
		}
	}

	// ==================== 1.5.8：五芒星的提示文案 / 配色 ====================

	/**
	 * 五芒星的提示文案与配色（客户端专用：语言文件只在客户端解析，服务端读不到译文）。
	 *
	 * <p>检查四件事：常驻三行是灰的；那句聊天消息整体暗红斜体、其中「埋葬无尽灵魂」被单独切出来做呼吸；
	 * 追加的引用行是暗红斜体（同样带呼吸分段）、下面那行是灰色斜体。为了不动真实客户端状态，
	 * 这里只调用纯函数。
	 */
	private static void checkPentagramTexts() {
		// 1.5.10：Shift 描述两态 —— 300 秒那句之前三行、之后只留「近战伤害 +x」
		List<net.minecraft.network.chat.Component> shiftBefore =
				com.summy.reliquary.item.PentagramItem.shiftLines(false);
		List<net.minecraft.network.chat.Component> shiftAfter =
				com.summy.reliquary.item.PentagramItem.shiftLines(true);
		String shiftText = shiftBefore.stream().map(net.minecraft.network.chat.Component::getString)
				.collect(java.util.stream.Collectors.joining(" / "));
		boolean shiftBeforeOk = shiftBefore.size() == 3
				&& shiftBefore.stream().allMatch(line -> "#AAAAAA".equals(colorOf(line)));
		boolean shiftAfterOk = shiftAfter.size() == 1 && "#AAAAAA".equals(colorOf(shiftAfter.get(0)))
				&& shiftAfter.get(0).getString().contains("近战伤害");

		net.minecraft.network.chat.Component message = com.summy.reliquary.item.PentagramItem.chatMessage();
		String phrase = net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.pentagram.phrase").getString();
		String expected = net.minecraft.network.chat.Component
				.translatable("message.summy-reliquary.pentagram.message").getString();
		List<net.minecraft.network.chat.Component> parts = message.getSiblings();
		boolean textOk = message.getString().equals(expected);
		boolean split = parts.size() == 3 && phrase.equals(parts.get(1).getString());
		boolean redOk = split && "#8B0000".equals(colorOf(parts.get(0)))
				&& "#8B0000".equals(colorOf(parts.get(2)));
		boolean italicOk = split && parts.stream()
				.allMatch(part -> part.getStyle().isItalic());
		boolean breatheOk = split && inBreathingRange(colorOf(parts.get(1)));

		List<net.minecraft.network.chat.Component> spoken =
				com.summy.reliquary.item.PentagramItem.spokenLines();
		boolean tradeOk = spoken.size() == 2
				&& spoken.get(0).getString().equals(net.minecraft.network.chat.Component
						.translatable("item.summy-reliquary.pentagram.shift.trade").getString())
				&& !spoken.get(0).getSiblings().isEmpty()
				&& spoken.get(0).getSiblings().stream()
						.allMatch(part -> part.getStyle().isItalic());
		boolean whisperOk = spoken.size() == 2 && "#AAAAAA".equals(colorOf(spoken.get(1)))
				&& spoken.get(1).getStyle().isItalic();

		log("五芒星 Shift 两态（1.5.10）：300 秒那句之前 " + shiftBefore.size() + " 行（应 3，全灰）="
				+ shiftBeforeOk + "、之后 " + shiftAfter.size() + " 行（应 1，只留近战伤害）="
				+ shiftAfterOk + "；之前文案=「" + shiftText + "」");
		log("五芒星消息：文本一致=" + textOk + "（应 true）、「" + phrase + "」单独成段=" + split
				+ "（应 true）、前后段暗红=" + redOk + "（应 true）、整体斜体=" + italicOk
				+ "（应 true）、短语落在呼吸色区间=" + breatheOk + "（应 true）；实际色="
				+ (split ? colorOf(parts.get(1)) : "无"));
		log("五芒星追加行：引用行文案与斜体=" + tradeOk + "（应 true）、「——他在对你说话」灰 + 斜体="
				+ whisperOk + "（应 true）；文案=「"
				+ (spoken.isEmpty() ? "" : spoken.get(0).getString()) + "」/「"
				+ (spoken.size() < 2 ? "" : spoken.get(1).getString()) + "」");
	}

	/** 颜色是否落在「暗红 #8B0000 → 亮红 #C03030」的呼吸区间内 */
	private static boolean inBreathingRange(String hexColor) {
		if (hexColor == null || !hexColor.startsWith("#") || hexColor.length() != 7) {
			return false;
		}
		int value = Integer.parseInt(hexColor.substring(1), 16);
		int red = (value >> 16) & 0xFF;
		int green = (value >> 8) & 0xFF;
		int blue = value & 0xFF;
		return red >= 0x8B && red <= 0xC0 && green <= 0x30 && blue <= 0x30;
	}

	/** 该物品能不能装进指定栏位 */
	private static boolean canEquipIn(ServerPlayer player, Item item, String slot) {
		return ((top.theillusivec4.curios.api.type.capability.ICurioItem) item)
				.canEquip(new SlotContext(slot, player, 0, false, true), new ItemStack(item));
	}

	// ==================== 1.5.6：V 键继承 / 光环不击退 / 心之碎片 / 新成就 ====================

	/** V 键启示之光：戴神性也算（客户端与服务端共用判定） */
	private static void checkRevelationInheritance(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
		});
		boolean noneHas = com.summy.reliquary.effect.Godhead.hasRevelation(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.GODHEAD.get())));
		boolean godheadHas = com.summy.reliquary.effect.Godhead.hasRevelation(player);
		com.summy.reliquary.effect.RevelationBeam.clear();
		boolean fired = com.summy.reliquary.effect.RevelationBeam.fire(player);
		com.summy.reliquary.effect.RevelationBeam.clear();
		log("V 键启示之光继承：都不戴=" + noneHas + "（应 false）、只戴神性=" + godheadHas
				+ "（应 true）、只戴神性时发射成功=" + fired + "（应 true）");
	}

	/** 神性光环：不击退 + 无视无敌帧 */
	private static void checkGodheadAuraKnockback(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			return;
		}
		Vec3 velocity = new Vec3(0.3D, 0.1D, -0.2D);
		zombie.setDeltaMovement(velocity);
		zombie.invulnerableTime = 20;
		float before = zombie.getHealth();
		com.summy.reliquary.effect.Godhead.judgeAuraNow(player);
		float damage = before - zombie.getHealth();
		Vec3 after = zombie.getDeltaMovement();
		zombie.discard();
		boolean unchanged = after.distanceToSqr(velocity) < 1.0E-6D;
		log(String.format("神性光环：目标无敌帧 20 时仍扣血=%.1f（应 2 = 无视无敌帧）、"
				+ "命中后速度 (%.2f, %.2f, %.2f) 与原速度 (%.2f, %.2f, %.2f) 一致=%s（应 true = 不击退）",
				damage, after.x, after.y, after.z, velocity.x, velocity.y, velocity.z, unchanged));
	}

	/** 启示之光 buff 与心之碎片掉落 */
	private static void checkHeartShard(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.HeartShardDrop.reset();
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 1, ItemStack.EMPTY);
		});
		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			return;
		}
		// ① 启示伤害 → 挂上 7 秒启示之光
		com.summy.reliquary.effect.RevelationBeam.applyRevelationDamage(player.serverLevel(), player, zombie, 1.0F);
		var effect = zombie.getEffect(SummyReliquary.REVELATION_LIGHT.get());
		int duration = effect == null ? -1 : effect.getDuration();
		// 再打一次：时长刷新
		zombie.setHealth(zombie.getMaxHealth());
		com.summy.reliquary.effect.RevelationBeam.applyRevelationDamage(player.serverLevel(), player, zombie, 1.0F);
		var refreshed = zombie.getEffect(SummyReliquary.REVELATION_LIGHT.get());
		int refreshedDuration = refreshed == null ? -1 : refreshed.getDuration();
		// ② 圣光（holy_light）不挂 buff
		Zombie other = spawnHolyLightTarget(player, 8.0D);
		if (other != null) {
			other.invulnerableTime = 0;
			other.hurt(other.damageSources().magic(), 1.0F);
		}
		// ③ 掉落：强制命中 → 原地出现不可摧毁的心之碎片
		com.summy.reliquary.effect.HeartShardDrop.setForcedRoll(true);
		com.summy.reliquary.effect.HeartShardDrop.onDeath(zombie, player);
		var dropped = player.serverLevel().getEntitiesOfClass(ItemEntity.class,
				zombie.getBoundingBox().inflate(4.0D));
		boolean dropExists = dropped.stream()
				.anyMatch(item -> item.getItem().is(SummyReliquary.HEART_SHARD.get()));
		boolean invulnerable = dropped.stream()
				.filter(item -> item.getItem().is(SummyReliquary.HEART_SHARD.get()))
				.anyMatch(net.minecraft.world.entity.Entity::isInvulnerable);
		dropped.forEach(net.minecraft.world.entity.Entity::discard);
		// ④ 击杀者戴圣心 → 不掉
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BLESSING, 0,
						new ItemStack(SummyReliquary.SACRED_HEART.get())));
		com.summy.reliquary.effect.HeartShardDrop.onDeath(zombie, player);
		boolean heartBlocks = player.serverLevel().getEntitiesOfClass(ItemEntity.class,
				zombie.getBoundingBox().inflate(4.0D)).stream()
				.noneMatch(item -> item.getItem().is(SummyReliquary.HEART_SHARD.get()));
		// ⑤ 概率表
		double normal = com.summy.reliquary.effect.HeartShardDrop.chanceFor(zombie);
		double boss = com.summy.reliquary.effect.HeartShardDrop.chanceFor(
				EntityType.WARDEN.create(player.serverLevel()));
		double dragon = com.summy.reliquary.effect.HeartShardDrop.chanceFor(
				EntityType.ENDER_DRAGON.create(player.serverLevel()));
		zombie.discard();
		if (other != null) {
			other.discard();
		}
		log("启示之光 buff：首次时长=" + duration + " tick（应 140）、刷新后=" + refreshedDuration
				+ " tick（应 140）；圣光伤害不挂 buff="
				+ (other != null && !other.hasEffect(SummyReliquary.REVELATION_LIGHT.get())) + "（应 true）");
		log("心之碎片掉落：强制命中后原地出现=" + dropExists + "（应 true）、掉落物不可摧毁=" + invulnerable
				+ "（应 true）；击杀者戴圣心后不再掉落=" + heartBlocks + "（应 true）；"
				+ "概率表 普通=" + normal + "（应 0.5）、监守者=" + boss + "（应 5.0）、末影龙=" + dragon
				+ "（应 20.0）");
		com.summy.reliquary.effect.HeartShardDrop.reset();
	}

	/** 三个新成就与图标替换 */
	private static void checkNewAdvancements(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.SALVATION_CRAFTED);
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.HEART_OBTAINED);
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.GODHEAD_OBTAINED);
		boolean done = com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "bread_and_fish")
				&& com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "heart")
				&& com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "god");
		boolean hidden = advancementHidden(server, "bread_and_fish") && advancementHidden(server, "heart")
				&& advancementHidden(server, "god");
		String icons = advancementIcon(server, "bread_and_fish") + "/" + advancementIcon(server, "heart") + "/"
				+ advancementIcon(server, "god") + "/" + advancementIcon(server, "unforgivable");
		// 神性成就描述：两段都是淡金，第二段斜体
		String godColors = "无";
		boolean tailItalic = false;
		var god = server.getAdvancements().getAdvancement(SummyReliquary.id("god"));
		if (god != null && god.getDisplay() != null) {
			// 描述是"数组组件"：第一段是根、后一段挂在它的 siblings 上（与「罪无可赦」同一写法）
			net.minecraft.network.chat.Component description = god.getDisplay().getDescription();
			var parts = description.getSiblings();
			godColors = colorOf(description) + "/"
					+ (parts.isEmpty() ? "无" : colorOf(parts.get(parts.size() - 1)));
			tailItalic = !parts.isEmpty() && parts.get(parts.size() - 1).getStyle().isItalic();
		}
		// 前置链：bread_and_fish ← pure、heart ← revelation、god ← heart
		String parents = parentOf(server, "bread_and_fish") + "/" + parentOf(server, "heart") + "/"
				+ parentOf(server, "god");
		log("新成就：三个触发后完成=" + done + "（应 true）、完成前隐藏=" + hidden + "（应 true）、"
				+ "图标（五饼二鱼/心/神/罪无可赦）=" + icons + "（应 salvation/sacred_heart/godhead/pentagram）");
		log("新成就：描述配色=" + godColors + "（应 #FFE4B5/#FFE4B5）、末段斜体=" + tailItalic
				+ "（应 true）、前置（五饼二鱼/心/神）=" + parents + "（应 pure/revelation/heart）");
	}

	/** 成就的前置 id（没有前置返回「无」） */
	private static String parentOf(MinecraftServer server, String path) {
		var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(path));
		if (advancement == null || advancement.getParent() == null) {
			return "无";
		}
		return advancement.getParent().getId().getPath();
	}

	// ==================== 1.5.7：光环口径 / 假人保护 / 门槛放行 ====================

	/**
	 * ① 光环 = 圣光类型（不挂启示之光）+ 真实伤害口径；
	 * ② 假人保护不会误伤普通生物；
	 * ③ 天使门槛：空槽新装要标记，已在本格的重算要放行，重复佩戴仍被拦。
	 */
	private static void checkAuraTypeAndCurioGate(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		// ---- ① 光环：圣光类型 + 真实伤害（钻石甲与抗性 IV 都不减）+ 不挂启示之光 ----
		Zombie armored = spawnHolyLightTarget(player, 6.0D);
		if (armored == null) {
			return;
		}
		armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		armored.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 600, 3));
		float before = armored.getHealth();
		com.summy.reliquary.effect.Godhead.judgeAuraNow(player);
		float damage = before - armored.getHealth();
		boolean buffed = armored.hasEffect(SummyReliquary.REVELATION_LIGHT.get());
		// ---- ② 假人保护：对普通生物是 no-op ----
		float healthBefore = armored.getHealth();
		com.summy.reliquary.effect.DummySupport.protectDummy(armored);
		boolean untouched = Math.abs(armored.getHealth() - healthBefore) < 1.0E-4F;
		boolean notDummy = !com.summy.reliquary.effect.DummySupport.isTargetDummyEntity(armored)
				&& com.summy.reliquary.effect.DummySupport.isDummyId(
						net.minecraft.resources.ResourceLocation.tryParse("dummmmmmy:target_dummy"));
		armored.discard();
		// ---- ③ 门槛放行 ----
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 1, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 1, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		boolean emptyDenied = !canEquipIn(player, SummyReliquary.SACRED_HEART.get(), ReliquarySlots.BLESSING);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.BLESSING, 0, new ItemStack(SummyReliquary.SACRED_HEART.get())));
		boolean inSlotAllowed = canEquipIn(player, SummyReliquary.SACRED_HEART.get(), ReliquarySlots.BLESSING);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.SPIRIT_ALTAR, 0, new ItemStack(SummyReliquary.THE_BODY.get())));
		boolean duplicateDenied = !((top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.THE_BODY.get()).canEquip(
						new SlotContext(ReliquarySlots.SPIRIT_ALTAR, player, 1, false, true),
						new ItemStack(SummyReliquary.THE_BODY.get()));
		boolean wrongSlotDenied = !canEquipIn(player, SummyReliquary.SACRED_HEART.get(), ReliquarySlots.HALO);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.BLESSING, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		log(String.format("光环口径：类型=%s（应 godhead_aura，死亡文本仍沿用圣光那条）、"
						+ "穿钻石甲 + 抗性 IV 仍扣 %.1f（应 2 = 真实伤害）、"
						+ "是否挂启示之光=%s（应 false）",
				com.summy.reliquary.effect.Godhead.auraDamageTypePath(), damage, buffed));
		log("假人保护：对普通生物零影响=" + untouched + "（应 true）、假人 id 判定与普通生物判定=" + notDummy
				+ "（应 true）");
		log("天使门槛：空槽未获标记被拦=" + emptyDenied + "（应 true）、已在本格放行=" + inSlotAllowed
				+ "（应 true）、异格重复佩戴被拦=" + duplicateDenied + "（应 true）、无关栏位被拦="
				+ wrongSlotDenied + "（应 true）");
	}

	// ==================== 1.5.8：五芒星 / 圣心配方 / 诊断 ====================

	/** 把背包与所有 Curios 栏位里的五芒星清掉（用于测试"只发一次"） */
	private static void clearPentagramItems(ServerPlayer player) {
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			if (player.getInventory().getItem(index).is(SummyReliquary.PENTAGRAM.get())) {
				player.getInventory().setItem(index, ItemStack.EMPTY);
			}
		}
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (var entry : handler.getCurios().entrySet()) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					if (entry.getValue().getStacks().getStackInSlot(index)
							.is(SummyReliquary.PENTAGRAM.get())) {
						handler.setEquippedCurio(entry.getKey(), index, ItemStack.EMPTY);
					}
				}
			}
		});
	}

	/** 五芒星：栏位（只认 Curios 自带的「护符」）、物品标签、近战伤害 +1 与卸下回收 */
	private static void checkPentagramBasics(ServerPlayer player) {
		// 保证「罪无可赦」一定已完成：后面的"兜底补发"才有意义
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.ALL_SINS_ACTIVE);
		ItemStack stack = new ItemStack(SummyReliquary.PENTAGRAM.get());
		var curio = (top.theillusivec4.curios.api.type.capability.ICurioItem) SummyReliquary.PENTAGRAM.get();
		boolean charmOk = curio.canEquip(
				new SlotContext(ReliquarySlots.CHARM, player, 0, false, true), stack);
		boolean haloDenied = !curio.canEquip(
				new SlotContext(ReliquarySlots.HALO, player, 0, false, true), stack);
		boolean blessingDenied = !curio.canEquip(
				new SlotContext(ReliquarySlots.BLESSING, player, 0, false, true), stack);
		// Curios 自带栏位靠物品标签校验：#curios:charm
		boolean tagged = stack.is(net.minecraft.tags.ItemTags.create(
				new net.minecraft.resources.ResourceLocation("curios", "charm")));
		boolean charmSlotExists = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(ReliquarySlots.CHARM))
				.orElse(false);

		// 属性：先把其它会加攻击力的来源清掉，再比对 +1 与卸下回收
		clearPentagramItems(player);
		clearBlessingSlots(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
			for (int index = 0; index < 3; index++) {
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, index, ItemStack.EMPTY);
			}
			handler.setEquippedCurio(ReliquarySlots.CHARM, 0, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double base = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.CHARM, 0, new ItemStack(SummyReliquary.PENTAGRAM.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double worn = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.CHARM, 0, ItemStack.EMPTY));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double removed = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		clearPentagramItems(player);

		log("五芒星栏位：护符可装=" + charmOk + "（应 true）、光环被拒=" + haloDenied
				+ "（应 true）、加护被拒=" + blessingDenied + "（应 true）、#curios:charm 命中=" + tagged
				+ "（应 true）、Curios 自带护符栏存在=" + charmSlotExists + "（应 true）");
		log(String.format("五芒星属性：攻击力 %.2f → %.2f（应 +%.1f） → 卸下 %.2f（应回落 %.2f）；"
						+ "配置值=%.1f",
				base, worn, com.summy.reliquary.config.ReliquaryConfig.pentagramAttackDamage(),
				removed, base, com.summy.reliquary.config.ReliquaryConfig.pentagramAttackDamage()));
	}

	/**
	 * 五芒星：**条件驱动**发放（1.6.10）。
	 *
	 * <p>判据 = 与「罪无可赦」完全相同的"七罪全部已激活或已赎罪"，不再是"成就是否已完成"；
	 * 所以重置（七罪归零）后**不会提前补发**，七罪重新全触发时才补发。
	 */
	private static void checkPentagramGrant(ServerPlayer player) {
		// A. 七罪全部已激活或已赎罪 + 标记已置位 + 身上没有它 → 不再自动补发
		setSinMasksForTest(player, 0b1111111, 0);
		com.summy.reliquary.effect.PlayerFlags.setPentagramGranted(player, true);
		clearPentagramItems(player);
		boolean regrantWhenFlagged = com.summy.reliquary.effect.Pentagram.grantIfEarned(player);
		int heldWhenFlagged = com.summy.reliquary.effect.Pentagram.heldCount(player);
		// B. 条件成立 + 清掉标记（模拟老存档 / 漏发 / 重置后重新触发）→ 补发一个
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		boolean regranted = com.summy.reliquary.effect.Pentagram.grantIfEarned(player);
		int heldAfterGrant = com.summy.reliquary.effect.Pentagram.heldCount(player);
		boolean flagAfterGrant = com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player);
		// C. 标记保留、只是把物品弄丢 → 也不再补发
		clearPentagramItems(player);
		boolean regrantAfterLoss = com.summy.reliquary.effect.Pentagram.grantIfEarned(player);
		int heldAfterLoss = com.summy.reliquary.effect.Pentagram.heldCount(player);
		// D. **重置后的状态**：七罪掩码 0 + 没标记 + 身上没有 → 绝不能提前补发
		setSinMasksForTest(player, 0, 0);
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		boolean grantedAfterReset = com.summy.reliquary.effect.Pentagram.grantIfEarned(player);
		int heldAfterReset = com.summy.reliquary.effect.Pentagram.heldCount(player);
		// E. 判据与成就脱钩：撤销「罪无可赦」进度后，只要七罪全部已激活或已赎罪仍然发放
		setSinMasksForTest(player, 0, 0);
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		clearPentagramItems(player);
		setSinMasksForTest(player, 0b1111111, 0);
		var forgivable = player.getServer() == null ? null
				: player.getServer().getAdvancements()
						.getAdvancement(SummyReliquary.id(com.summy.reliquary.effect.Pentagram.ADVANCEMENT));
		if (forgivable != null) {
			player.getAdvancements().revoke(forgivable, "event");
		}
		boolean flagWithoutAdvancement = com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player);
		// 再调一次兜底：应当已经发过（返回 false），但物品确实在
		boolean grantedWithoutAdvancement = com.summy.reliquary.effect.Pentagram.grantIfEarned(player);
		int heldWithoutAdvancement = com.summy.reliquary.effect.Pentagram.heldCount(player);
		boolean advancementDoneNow = com.summy.reliquary.advancement.SinChallenges.advancementDone(
				player, com.summy.reliquary.effect.Pentagram.ADVANCEMENT);
		// 还原「罪无可赦」进度，免得影响后面的用例
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.ALL_SINS_ACTIVE);
		// F. grant 在标记为 false 时也能直接发一个
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		clearPentagramItems(player);
		com.summy.reliquary.effect.Pentagram.grant(player);
		int heldByGrant = com.summy.reliquary.effect.Pentagram.heldCount(player);
		boolean flagByGrant = com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player);
		clearPentagramItems(player);
		resetSinState(player);

		log("五芒星发放（条件驱动）：七罪全部已激活或已赎罪 + 有标记 → 不补=" + regrantWhenFlagged + "（应 false）、身上="
				+ heldWhenFlagged + "（应 0）；清掉标记后补发=" + regranted + "（应 true）、身上="
				+ heldAfterGrant + "（应 1）、标记=" + flagAfterGrant + "（应 true）；有标记但物品丢失="
				+ regrantAfterLoss + "（应 false）、身上=" + heldAfterLoss + "（应 0）");
		log("五芒星发放·重置后不提前给：七罪掩码 0 + 无标记 → 发放=" + grantedAfterReset
				+ "（应 false）、身上=" + heldAfterReset + "（应 0）；与成就脱钩（撤销「罪无可赦」后，"
				+ "''七罪全部已激活或已赎罪'' 那一下仍会发放）=标记=" + flagWithoutAdvancement + "（应 true）、再兜底="
				+ grantedWithoutAdvancement + "（应 false = 已发过）、身上=" + heldWithoutAdvancement
				+ "（应 1）、此刻进度还在=" + advancementDoneNow
				+ "（应 false = 证明发放不再看成就）；grant 直发后身上=" + heldByGrant
				+ "（应 1）、标记=" + flagByGrant + "（应 true）");
	}

	/** 五芒星：累计持有到阈值才发那句话、终身只发一次、没有它时不累计 */
	private static void checkPentagramMessage(ServerPlayer player) {
		int threshold = com.summy.reliquary.config.ReliquaryConfig.pentagramMessageSeconds() * 20;
		// 先备好一个五芒星在背包里（speakIfDue 本身不要求持有，countHeldTime 才要求）
		com.summy.reliquary.effect.PlayerFlags.setPentagramGranted(player, true);
		clearPentagramItems(player);
		player.getInventory().add(new ItemStack(SummyReliquary.PENTAGRAM.get()));
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, false);
		com.summy.reliquary.effect.PlayerFlags.setPentagramTicks(player, 0);

		boolean early = com.summy.reliquary.effect.Pentagram.speakIfDue(player, threshold - 20);
		boolean atThreshold = com.summy.reliquary.effect.Pentagram.speakIfDue(player, threshold);
		boolean repeated = com.summy.reliquary.effect.Pentagram.speakIfDue(player, threshold + 20);
		boolean spoken = com.summy.reliquary.effect.PlayerFlags.isPentagramSpoken(player);
		int ticks = com.summy.reliquary.effect.PlayerFlags.pentagramTicks(player);
		// 客户端同步位：bit4
		boolean bitSynced = (com.summy.reliquary.effect.PlayerFlags.clientFlags(player)
				& com.summy.reliquary.client.ReliquaryClientState.FLAG_PENTAGRAM_SPOKEN) != 0;

		// 计时只在身上有它时累计
		clearPentagramItems(player);
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, false);
		com.summy.reliquary.effect.PlayerFlags.setPentagramTicks(player, 0);
		com.summy.reliquary.effect.Pentagram.countHeldTime(player);
		int ticksWithout = com.summy.reliquary.effect.PlayerFlags.pentagramTicks(player);
		int ticksWith = com.summy.reliquary.effect.Pentagram.heldCount(player);

		log("五芒星消息：阈值=" + threshold + " tick（应 " + (300 * 20) + "）；未到点=" + early
				+ "（应 false）、到点=" + atThreshold + "（应 true）、再调用=" + repeated
				+ "（应 false，终身一次）、已说标记=" + spoken + "（应 true）、累计 tick=" + ticks
				+ "（应 " + (threshold + 20) + "）、客户端 bit4 同步=" + bitSynced
				+ "（应 true）；没有它时累计=" + ticksWithout + "（应 0）、此时身上数量=" + ticksWith
				+ "（应 0）");
	}

	// ==================== 1.5.9：重登修复 / 心之碎片判定 / 恶魔交易 ====================

	/**
	 * ① 重登修复：正常就绪时天使门槛照旧生效；"数据未就绪"（Curios 读档那一刻）时一律放行。
	 */
	private static void checkDataReadyGate(ServerPlayer player) {
		clearBlessingSlots(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		// 正常就绪 → 门槛生效
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, true);
		boolean readyDenied = !canEquipIn(player, SummyReliquary.SACRED_HEART.get(), ReliquarySlots.BLESSING);
		// 伪造"读档中" → 放行（1.5.8 这里会返回 false，也就是把本来就戴着的饰品卸掉）
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, false);
		boolean loadingAllowed = canEquipIn(player, SummyReliquary.SACRED_HEART.get(), ReliquarySlots.BLESSING);
		// 恢复 → 又被拦
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, true);
		boolean readyDeniedAgain = !canEquipIn(player, SummyReliquary.SACRED_HEART.get(), ReliquarySlots.BLESSING);
		boolean readyNormal = com.summy.reliquary.effect.PlayerFlags.isDataReady(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		log("重登修复：正常就绪时被拦=" + readyDenied + "（应 true）、数据未就绪时放行=" + loadingAllowed
				+ "（应 true）、恢复就绪后又被拦=" + readyDeniedAgain + "（应 true）、已登录玩家的就绪位="
				+ readyNormal + "（应 true）");
	}

	/**
	 * ② 心之碎片判定日志：四种分支的 lastOutcome（无 buff / 未中 / 被圣心抑制 / 掉落）。
	 */
	private static void checkHeartShardOutcomes(ServerPlayer player) {
		boolean defaultLog = com.summy.reliquary.config.ReliquaryConfig.logDropChecks();
		com.summy.reliquary.effect.HeartShardDrop.reset();
		clearBlessingSlots(player);
		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			return;
		}
		// ① 没有启示之光 → no-buff（静默分支）
		com.summy.reliquary.effect.HeartShardDrop.onDeath(zombie, player);
		String noBuff = com.summy.reliquary.effect.HeartShardDrop.lastOutcome();
		// ② 挂上启示之光 + 强制未中 → miss
		zombie.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				SummyReliquary.REVELATION_LIGHT.get(), 600));
		com.summy.reliquary.effect.HeartShardDrop.setForcedRoll(false);
		com.summy.reliquary.effect.HeartShardDrop.onDeath(zombie, player);
		String miss = com.summy.reliquary.effect.HeartShardDrop.lastOutcome();
		// ③ 击杀者戴圣心 → suppressed
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.setEquippedCurio(
				ReliquarySlots.BLESSING, 0, new ItemStack(SummyReliquary.SACRED_HEART.get())));
		com.summy.reliquary.effect.HeartShardDrop.onDeath(zombie, player);
		String suppressed = com.summy.reliquary.effect.HeartShardDrop.lastOutcome();
		// ④ 强制命中 → drop
		clearBlessingSlots(player);
		com.summy.reliquary.effect.HeartShardDrop.setForcedRoll(true);
		com.summy.reliquary.effect.HeartShardDrop.onDeath(zombie, player);
		String drop = com.summy.reliquary.effect.HeartShardDrop.lastOutcome();
		int drops = com.summy.reliquary.effect.HeartShardDrop.dropCount();
		player.serverLevel().getEntitiesOfClass(ItemEntity.class, zombie.getBoundingBox().inflate(4.0D))
				.forEach(net.minecraft.world.entity.Entity::discard);
		zombie.discard();
		com.summy.reliquary.effect.HeartShardDrop.reset();
		log("心之碎片判定日志：开关默认=" + defaultLog + "（应 true）；无 buff=" + noBuff
				+ "（应 no-buff）、强制未中=" + miss + "（应 miss）、戴圣心=" + suppressed
				+ "（应 suppressed）、强制命中=" + drop + "（应 drop，掉落数 " + drops + "）");
	}

	/** 把恶魔交易演示所需的前置一次性摆好（五芒星 + 那句 300 秒的话 + 强制"在峡谷"） */
	private static void prepareDemonDeal(ServerPlayer player) {
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		com.summy.reliquary.effect.DelayedChat.forget(player);
		com.summy.reliquary.effect.PlayerFlags.setPentagramGranted(player, true);
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, true);
		// 1.6.10：恶魔交易要求"没有获取过启示"，搭场景时先清掉该属性
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		// 1.7.2：另外两条封锁（已放弃一切 / 持有圣心）也要清干净，否则后面的用例会被静默挡住
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, false);
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		if (!com.summy.reliquary.effect.DemonDeal.hasPentagram(player)) {
			player.getInventory().add(new ItemStack(SummyReliquary.PENTAGRAM.get()));
		}
		com.summy.reliquary.effect.DemonDeal.setForcedValley(true);
	}

	/** 驻留满 biome_dwell_seconds 秒（自检手动推进，每秒一次） */
	private static void dwellDemonDeal(ServerPlayer player) {
		for (int index = 0; index < com.summy.reliquary.config.ReliquaryConfig.demonBiomeDwellSeconds(); index++) {
			com.summy.reliquary.effect.DemonDeal.tick(player);
		}
	}

	/**
	 * ③ 恶魔交易·对话：启示锁（1.6.10）、三版邀请（A/B/C）、离场、回归、逐行延迟。
	 */
	private static void checkDemonDealDialogues(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		prepareDemonDeal(player);
		// ① 1.6.10 启示锁：身上带着终末天启 → 记下"获取过启示" → 恶魔交易彻底关闭
		//    （连驻留都不再推进，所以不会有任何邀请台词；也不再走"静默邀请"那一态）
		player.getInventory().add(new ItemStack(SummyReliquary.FINAL_REVELATION.get()));
		boolean revelationGained = com.summy.reliquary.effect.RevelationTracker.updateObtained(player);
		boolean qualifiedWithRevelation = com.summy.reliquary.effect.DemonDeal.isQualified(player);
		dwellDemonDeal(player);
		String gated = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(SummyReliquary.FINAL_REVELATION.get()), Integer.MAX_VALUE,
				player.inventoryMenu.getCraftSlots());
		// 复位启示属性：后面几段还要继续测邀请
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);

		// ② A 版：无赎罪进度、无天使标记
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		resetSinState(player);
		dwellDemonDeal(player);
		String variantA = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		int linesA = com.summy.reliquary.effect.DemonDeal.inviteLines(player).size();

		// ③ B 版：有赎罪进度但没全赎清（在默认第 2~3 行中间插一行）
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		resetSinState(player);
		SinManager.setState(player, Sin.PRIDE, SinManager.SinState.REDEEMED);
		dwellDemonDeal(player);
		String variantB = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		int linesB = com.summy.reliquary.effect.DemonDeal.inviteLines(player).size();

		// ④ C 版：七罪全部已赎罪
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		for (Sin sin : Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.REDEEMED);
		}
		dwellDemonDeal(player);
		String variantC = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		int linesC = com.summy.reliquary.effect.DemonDeal.inviteLines(player).size();

		// ⑤ C 版（已拥有天使标记）
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		resetSinState(player);
		dwellDemonDeal(player);
		String variantAngel = com.summy.reliquary.effect.DemonDeal.lastDialogue();

		// ⑥ 离场 → 回归
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		resetSinState(player);
		dwellDemonDeal(player);
		int pendingAfterInvite = com.summy.reliquary.effect.DelayedChat.pending(player);
		int inviteLineCount = com.summy.reliquary.effect.DemonDeal.inviteLines(player).size();
		com.summy.reliquary.effect.DemonDeal.setForcedValley(false);
		com.summy.reliquary.effect.DemonDeal.tick(player);
		String left = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		com.summy.reliquary.effect.DemonDeal.setForcedValley(true);
		dwellDemonDeal(player);
		String returned = com.summy.reliquary.effect.DemonDeal.lastDialogue();

		// ⑦ 逐行延迟：每 line_delay_ticks 发一行
		com.summy.reliquary.effect.DelayedChat.forget(player);
		int delay = com.summy.reliquary.config.ReliquaryConfig.demonLineDelayTicks();
		com.summy.reliquary.effect.DelayedChat.send(player, List.of(
				net.minecraft.network.chat.Component.literal("L1"),
				net.minecraft.network.chat.Component.literal("L2"),
				net.minecraft.network.chat.Component.literal("L3")));
		int afterSend = com.summy.reliquary.effect.DelayedChat.pending(player);
		for (int index = 0; index < delay - 1; index++) {
			com.summy.reliquary.effect.DelayedChat.tick(server);
		}
		int beforeDue = com.summy.reliquary.effect.DelayedChat.pending(player);
		com.summy.reliquary.effect.DelayedChat.tick(server);
		int afterDue = com.summy.reliquary.effect.DelayedChat.pending(player);
		for (int index = 0; index < delay; index++) {
			com.summy.reliquary.effect.DelayedChat.tick(server);
		}
		int drained = com.summy.reliquary.effect.DelayedChat.pending(player);
		com.summy.reliquary.effect.DelayedChat.forget(player);
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.DemonDeal.setForcedValley(null);

		log("恶魔交易·启示锁（1.6.10）：持有终末天启 → 记下获取过启示=" + revelationGained
				+ "（应 true）、isQualified=" + qualifiedWithRevelation
				+ "（应 false = 彻底关闭）、驻留 5 秒后 lastDialogue=" + gated
				+ "（应 null = 完全没有对话，也不再是 silent-invite）");
		log("恶魔交易邀请：A=" + variantA + "（应 invite:A，行数 " + linesA + " 应 6）、B=" + variantB
				+ "（应 invite:B，行数 " + linesB + " 应 7 = 默认 5 + 插入 1 + 灰色提示 1）、C=" + variantC
				+ "（应 invite:C，行数 " + linesC + " 应 7）、已有天使标记=" + variantAngel + "（应 invite:C）");
		log("恶魔交易离场/回归：离开=" + left + "（应 leave）、回归=" + returned
				+ "（应 return）；邀请后延迟队列剩余=" + pendingAfterInvite + "（应 " + (inviteLineCount - 1)
				+ " = 该版本 " + inviteLineCount + " 行里的后 " + (inviteLineCount - 1) + " 行）");
		log("恶魔交易逐行延迟（间隔 " + delay + " tick）：发送后剩余=" + afterSend + "（应 2）、"
				+ "差 1 tick 到点=" + beforeDue + "（应 2）、到点后=" + afterDue + "（应 1）、"
				+ "再过一个间隔=" + drained + "（应 0）");
	}

	/**
	 * ③ 恶魔交易·签约：蓄力参数、不足 5 秒不签、满 5 秒签约、互斥、名字颜色、不消耗、错误位置节流。
	 */
	private static void checkDemonDealSign(ServerPlayer player) {
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonInvited(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.PENTAGRAM.get()));
		ItemStack held = player.getMainHandItem();
		int duration = held.getItem().getUseDuration(held);

		// ① 不足 5 秒（松手时还剩 50 tick）→ 不签
		SummyReliquary.PENTAGRAM.get().releaseUsing(held, player.serverLevel(), player, 50);
		boolean notYet = !com.summy.reliquary.effect.PlayerFlags.isDemonSealed(player);

		// ② 蓄满（自然结束）→ 签约
		SummyReliquary.PENTAGRAM.get().finishUsingItem(held, player.serverLevel(), player);
		boolean sealed = com.summy.reliquary.effect.PlayerFlags.isDemonSealed(player);
		boolean demon = com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean angelGone = !com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		boolean notConsumed = player.getMainHandItem().getCount() == 1;
		int flags = com.summy.reliquary.effect.PlayerFlags.clientFlags(player);
		boolean demonBit = (flags & com.summy.reliquary.client.ReliquaryClientState.FLAG_DEMON) != 0;
		boolean sealedBit = (flags & com.summy.reliquary.client.ReliquaryClientState.FLAG_DEMON_SEALED) != 0;
		String signedKind = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		int signedLines = com.summy.reliquary.effect.DemonDeal.signedLines(player).size();

		// ③ 名字颜色：恶魔 = 深红 #8B0000；天使仍是金色
		PlayerEvent.NameFormat nameEvent = new PlayerEvent.NameFormat(
				player, net.minecraft.network.chat.Component.literal(player.getGameProfile().getName()));
		MinecraftForge.EVENT_BUS.post(nameEvent);
		String demonNameColor = colorOf(nameEvent.getDisplayname());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		PlayerEvent.NameFormat angelEvent = new PlayerEvent.NameFormat(
				player, net.minecraft.network.chat.Component.literal(player.getGameProfile().getName()));
		MinecraftForge.EVENT_BUS.post(angelEvent);
		String angelNameColor = colorOf(angelEvent.getDisplayname());

		// ④ 错误位置提示 + 按玩家 5 秒节流
		com.summy.reliquary.effect.DemonDeal.setForcedValley(false);
		long start = 500000L;
		boolean firstWrong = com.summy.reliquary.effect.DemonDeal.speakWrongPlaceIfNeeded(player, start);
		boolean throttled = !com.summy.reliquary.effect.DemonDeal.speakWrongPlaceIfNeeded(player, start + 50);
		boolean afterWindow = com.summy.reliquary.effect.DemonDeal.speakWrongPlaceIfNeeded(
				player, start + com.summy.reliquary.config.ReliquaryConfig.demonErrorCooldownTicks());
		com.summy.reliquary.effect.DemonDeal.setForcedValley(true);

		log("恶魔签约：蓄力=" + duration + " tick（应 100）、不足 5 秒不签=" + notYet + "（应 true）、"
				+ "满 5 秒签约=" + sealed + "（应 true）、恶魔标记=" + demon + "（应 true）、天使标记被替换="
				+ angelGone + "（应 true）、五芒星未被消耗=" + notConsumed + "（应 true）、"
				+ "客户端 bit5/bit6=" + demonBit + "/" + sealedBit + "（应 true/true）");
		log("恶魔签约台词/颜色：未用过痛悔短祷时为 " + signedKind + "（应 signed）、行数 " + signedLines
				+ "（应 4）；恶魔名字色=" + demonNameColor + "（应 #8B0000）、天使名字色=" + angelNameColor
				+ "（应 #FFAA00 = 原版金色 GOLD）");
		log("恶魔错误位置：首次提示=" + firstWrong + "（应 true）、2.5 秒后被节流=" + throttled
				+ "（应 true）、5 秒后再次提示=" + afterWindow + "（应 true）");
	}

	/**
	 * ③ 恶魔交易·痛悔短祷换回天使标记：成功使用时把恶魔标记换回天使（契约历史保留），且仍然限用一次。
	 */
	private static void checkDemonDealContrition(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				overworld.getSharedSpawnPos()).above();
		player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
				player.getYRot(), player.getXRot());
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, null, 0.0F, false, false);

		// 状态：曾签约 + 当前持恶魔标记 + 痛悔短祷还没用过
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.item.ActOfContritionItem.resetUsed(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		com.summy.reliquary.item.ActOfContritionItem.forget(player);
		String failure = com.summy.reliquary.item.ActOfContritionItem.failureKey(player);
		int signedLinesBefore = com.summy.reliquary.effect.DemonDeal.signedLines(player).size();
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.ACT_OF_CONTRITION.get()));
		SummyReliquary.ACT_OF_CONTRITION.get().use(overworld, player,
				net.minecraft.world.InteractionHand.MAIN_HAND);
		boolean demonSwapped = !com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean angelNow = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		boolean sealedKept = com.summy.reliquary.effect.PlayerFlags.isDemonSealed(player);
		boolean usedOnce = com.summy.reliquary.item.ActOfContritionItem.isUsed(player);
		int signedLinesAfter = com.summy.reliquary.effect.DemonDeal.signedLines(player).size();
		boolean swappedDialogue = com.summy.reliquary.effect.DemonDeal.signedLines(player).stream()
				.anyMatch(line -> line.getString().contains("欢迎回来"));

		// 第二次：再给一个恶魔标记 + 再给一件痛悔短祷 → 因为"限用一次"而不生效
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.ACT_OF_CONTRITION.get()));
		SummyReliquary.ACT_OF_CONTRITION.get().use(overworld, player,
				net.minecraft.world.InteractionHand.MAIN_HAND);
		boolean secondIgnored = com.summy.reliquary.effect.PlayerFlags.isDemon(player);

		// 收尾：清掉恶魔相关状态与手持物品
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.item.ActOfContritionItem.resetUsed(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		com.summy.reliquary.effect.DemonDeal.setForcedValley(null);
		clearPentagramItems(player);
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

		log("恶魔→天使（痛悔短祷）：出生点判定=" + failure + "（应 null = 位置正确）、恶魔标记被换掉="
				+ demonSwapped + "（应 true）、天使标记=" + angelNow + "（应 true）、契约历史保留="
				+ sealedKept + "（应 true）、已置用过标记=" + usedOnce + "（应 true）；第二次使用不生效="
				+ secondIgnored + "（应 true，仍持恶魔标记）");
		log("恶魔签约台词两态：用痛悔短祷前 " + signedLinesBefore + " 行（应 4）、用过之后 " + signedLinesAfter
				+ " 行（应 2）、含「欢迎回来，七罪之子」=" + swappedDialogue + "（应 true）");
	}

	/**
	 * 1.5.10：二次签约全流程 + 自愈冲突回归 + 持恶魔标记时的封锁 + 二轮无对话 + 右键门槛。
	 */
	private static void checkDemonDealSecondRound(ServerPlayer player) {
		// ── 前置：让「纯洁之人」确实完成（这样 selfHeal 的"补天使标记"分支真的处于武装状态）
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.REDEEMED_TO_VIRTUES);
		boolean pureDone = com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "pure");
		prepareDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonInvited(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.PENTAGRAM.get()));

		// ── ① 第一次签约
		boolean firstSign = com.summy.reliquary.effect.DemonDeal.sign(player);
		boolean demonAfterFirst = com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean angelAfterFirst = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);

		// ── ② 自愈冲突回归：每秒自愈不能把天使标记补回来（否则会顺手清掉恶魔标记）
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean demonAfterHeal = com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean angelAfterHeal = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);

		// ── ③ 忏悔：**真的**用一次痛悔短祷（主世界出生点 + 露天）→ 恶魔标记换回天使标记（契约历史保留）
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				overworld.getSharedSpawnPos()).above();
		player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
				player.getYRot(), player.getXRot());
		player.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD, null, 0.0F, false, false);
		com.summy.reliquary.item.ActOfContritionItem.resetUsed(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		com.summy.reliquary.item.ActOfContritionItem.forget(player);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.ACT_OF_CONTRITION.get()));
		SummyReliquary.ACT_OF_CONTRITION.get().use(overworld, player,
				net.minecraft.world.InteractionHand.MAIN_HAND);
		boolean swapped = !com.summy.reliquary.effect.PlayerFlags.isDemon(player)
				&& com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		boolean demonAfterSwap = com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean angelAfterSwap = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		boolean sealedKept = com.summy.reliquary.effect.PlayerFlags.isDemonSealed(player);
		boolean contritionUsed = com.summy.reliquary.item.ActOfContritionItem.isUsed(player);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.PENTAGRAM.get()));

		// ── ④ 第二次签约：应当可以正常完成
		com.summy.reliquary.effect.DemonDeal.setForcedValley(true);
		// 1.6.10：恶魔交易要求"没有获取过启示"，这里保证该属性为空
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		// 1.7.2：另外两条封锁（已放弃一切 / 持有圣心）也要清 —— 本用例测的是"二次签约"
		// （「无罪之人」的成就在前序用例里可能已完成，每秒自愈会持续把 sin_renounced 补回来）
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, false);
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		boolean qualifiedAgain = com.summy.reliquary.effect.DemonDeal.isQualified(player);
		boolean canSignAgain = com.summy.reliquary.effect.DemonDeal.canSign(player);
		boolean secondSign = com.summy.reliquary.effect.DemonDeal.sign(player);
		boolean demonAfterSecond = com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean angelAfterSecond = com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		String secondKind = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		int secondLines = com.summy.reliquary.effect.DemonDeal.signedLines(player).size();

		// ── ⑤ 持恶魔标记时：不能再签、右键无反应
		boolean lockedWhileDemon = !com.summy.reliquary.effect.DemonDeal.isQualified(player);
		boolean usePassWhileDemon = SummyReliquary.PENTAGRAM.get()
				.use(player.serverLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
				.getResult() == net.minecraft.world.InteractionResult.PASS;

		// ── ⑥ 二轮无对话：签过契约的玩家即使在峡谷驻留满 5 秒也不再开口
		String beforeDwell = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		dwellDemonDeal(player);
		boolean silentInRoundTwo = java.util.Objects.equals(beforeDwell,
				com.summy.reliquary.effect.DemonDeal.lastDialogue());

		// ── ⑦ 右键门槛：那句 300 秒消息之前"完全无反应"（连错误位置提示都不给）
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, false);
		com.summy.reliquary.effect.DemonDeal.setForcedValley(false);
		com.summy.reliquary.effect.DemonDeal.forgetThrottle(player);
		String beforeGate = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		boolean passBeforeSpoken = SummyReliquary.PENTAGRAM.get()
				.use(player.serverLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
				.getResult() == net.minecraft.world.InteractionResult.PASS;
		boolean silentBeforeSpoken = java.util.Objects.equals(beforeGate,
				com.summy.reliquary.effect.DemonDeal.lastDialogue());
		// 对照组：说过那句之后、不在峡谷 → FAIL + 错误位置提示
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, true);
		com.summy.reliquary.effect.DemonDeal.forgetThrottle(player);
		boolean failAfterSpoken = SummyReliquary.PENTAGRAM.get()
				.use(player.serverLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
				.getResult() == net.minecraft.world.InteractionResult.FAIL;
		boolean wrongPlace = "wrong-place".equals(com.summy.reliquary.effect.DemonDeal.lastDialogue());

		// ── 收尾：清掉恶魔相关状态、延迟队列与手持物品
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		com.summy.reliquary.item.ActOfContritionItem.resetUsed(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.DelayedChat.forget(player);
		com.summy.reliquary.effect.DemonDeal.setForcedValley(null);
		clearPentagramItems(player);
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

		log("二次签约：纯洁之人已完成=" + pureDone + "（应 true）；首次签约=" + firstSign
				+ "（应 true）、恶魔=" + demonAfterFirst + "（应 true）、天使被清=" + !angelAfterFirst
				+ "（应 true）");
		log("自愈冲突回归：调一次 selfHeal 后 恶魔仍在=" + demonAfterHeal + "（应 true）、天使仍为空="
				+ !angelAfterHeal + "（应 true，这就是 1.5.9 的 bug：会被自愈补回并清掉恶魔）");
		log("忏悔换回天使：替换成功=" + swapped + "（应 true）、恶魔=" + demonAfterSwap
				+ "（应 false）、天使=" + angelAfterSwap + "（应 true）、契约历史保留=" + sealedKept
				+ "（应 true）、痛悔短祷已置用过标记=" + contritionUsed + "（应 true）");
		log("二次签约：可签（isQualified）=" + qualifiedAgain + "（应 true）、canSign=" + canSignAgain
				+ "（应 true）、第二次签约=" + secondSign + "（应 true）、恶魔=" + demonAfterSecond
				+ "（应 true）、天使再次被清=" + !angelAfterSecond + "（应 true）；台词=" + secondKind
				+ "（应 signed-return）、行数=" + secondLines + "（应 2 = 欢迎回来 / 让我们重新开始吧）");
		log("持恶魔标记封锁：isQualified=false=" + lockedWhileDemon + "（应 true）、右键="
				+ usePassWhileDemon + "（应 true = PASS 无反应）");
		log("二轮无对话：驻留 5 秒后 lastDialogue 未变=" + silentInRoundTwo
				+ "（应 true，仍是 " + beforeDwell + "）");
		log("右键门槛：300 秒那句之前右键=" + passBeforeSpoken + "（应 true = PASS）、且没有任何提示="
				+ silentBeforeSpoken + "（应 true）；说过之后不在峡谷=" + failAfterSpoken
				+ "（应 true = FAIL）、错误位置提示=" + wrongPlace + "（应 true）");
	}

	// ==================== 1.6.0：恶魔契约 / 献祭 / 黑心 / 邪恶度 ====================

	/** 清掉契约相关的全部状态，便于逐段断言 */
	private static void resetDemonPactState(ServerPlayer player) {
		com.summy.reliquary.effect.PlayerFlags.resetDemonPact(player);
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.DemonPact.revoke(player);
		com.summy.reliquary.effect.DemonPact.syncSlot(player);
		com.summy.reliquary.effect.DelayedChat.forget(player);
	}

	/** 玩家身上（背包 + 全部 Curios 栏位）某物品的数量 */
	private static int countItemEverywhere(ServerPlayer player, Item item) {
		int count = player.getInventory().countItem(item);
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			for (var entry : handler.getCurios().entrySet()) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					ItemStack stack = entry.getValue().getStacks().getStackInSlot(index);
					if (!stack.isEmpty() && stack.is(item)) {
						count += stack.getCount();
					}
				}
			}
		}
		return count;
	}

	/** ① 动态栏位 / 唯一性 / 强制佩戴 / 圣物没收与补偿 / 忏悔收回 */
	private static void checkDemonPactSlotAndConfiscation(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.DemonPact.syncSlot(player);
		int slotsBefore = com.summy.reliquary.effect.DemonPact.slotCount(player);

		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		int slotsAfter = com.summy.reliquary.effect.DemonPact.slotCount(player);
		boolean pactInSlot = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(ReliquarySlots.DEMON_PACT)
						&& handler.getCurios().get(ReliquarySlots.DEMON_PACT).getSlots() > 0
						&& handler.getCurios().get(ReliquarySlots.DEMON_PACT).getStacks()
								.getStackInSlot(0).is(SummyReliquary.THE_PACT.get()))
				.orElse(false);

		boolean duplicateDenied = !((top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.THE_PACT.get()).canEquip(
						new SlotContext(ReliquarySlots.DEMON_PACT, player, 1, false, true),
						new ItemStack(SummyReliquary.THE_PACT.get()));

		boolean survivalLocked = !((top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.THE_PACT.get()).canUnequip(
						new SlotContext(ReliquarySlots.DEMON_PACT, player, 0, false, true),
						new ItemStack(SummyReliquary.THE_PACT.get()));
		var previousMode = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		boolean creativeCanUnequip = ((top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.THE_PACT.get()).canUnequip(
						new SlotContext(ReliquarySlots.DEMON_PACT, player, 0, false, true),
						new ItemStack(SummyReliquary.THE_PACT.get()));
		player.setGameMode(previousMode);

		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(SummyReliquary.THE_BODY.get()));
		player.getInventory().add(new ItemStack(SummyReliquary.THE_MIND.get()));
		player.getInventory().add(new ItemStack(SummyReliquary.HOLY_LIGHT.get()));
		player.getInventory().add(new ItemStack(SummyReliquary.SACRED_HEART.get()));
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		boolean relicsGone = player.getInventory().countItem(SummyReliquary.THE_BODY.get()) == 0
				&& player.getInventory().countItem(SummyReliquary.THE_MIND.get()) == 0
				&& player.getInventory().countItem(SummyReliquary.HOLY_LIGHT.get()) == 0;
		boolean sacredHeartKept = player.getInventory().countItem(SummyReliquary.SACRED_HEART.get()) == 1;
		int sixForTwoAltar = player.getInventory().countItem(SummyReliquary.SIX.get());

		// 三件灵台**一次全被没收** → 改补 1 个咒印（先把上一轮的「6」清掉，单独观察）
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(SummyReliquary.THE_BODY.get()));
		player.getInventory().add(new ItemStack(SummyReliquary.THE_MIND.get()));
		player.getInventory().add(new ItemStack(SummyReliquary.THE_SOUL.get()));
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		boolean soulGone = player.getInventory().countItem(SummyReliquary.THE_SOUL.get()) == 0;
		int sixAfterThree = player.getInventory().countItem(SummyReliquary.SIX.get());
		int marksAfterThree = player.getInventory().countItem(SummyReliquary.THE_MARK.get());
		boolean threeAltarGivesMark = sixAfterThree == 0 && marksAfterThree == 1;

		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		int slotsAfterRepent = com.summy.reliquary.effect.DemonPact.slotCount(player);
		int pactAfterRepent = countItemEverywhere(player, SummyReliquary.THE_PACT.get());

		log("恶魔契约栏位：未签约格数=" + slotsBefore + "（应 0）、签约后=" + slotsAfter
				+ "（应 1）、契约已在格内=" + pactInSlot + "（应 true）、重复佩戴被拦=" + duplicateDenied
				+ "（应 true）、非创造摘不下=" + survivalLocked + "（应 true）、创造可摘="
				+ creativeCanUnequip + "（应 true）");
		log("签约没收：圣光/灵台被没收=" + relicsGone + "（应 true）、圣心保留=" + sacredHeartKept
				+ "（应 true）、2 件灵台补「6」=" + sixForTwoAltar + "（应 2）、3 件灵台改为补咒印="
				+ threeAltarGivesMark + "（应 true；此时「6」=" + sixAfterThree + "、咒印="
				+ marksAfterThree + "）、灵魂被没收=" + soulGone + "（应 true）");
		log("忏悔收回：契约格数=" + slotsAfterRepent + "（应 0）、身上契约=" + pactAfterRepent + "（应 0）");
	}

	/** 给目标打一次"玩家近战命中"（用于献祭额外伤害的断言） */
	private static void pactHit(ServerPlayer player, net.minecraft.world.entity.LivingEntity target) {
		target.invulnerableTime = 0;
		com.summy.reliquary.effect.DemonPact.extraSacrificeDamage(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
						player.damageSources().playerAttack(player), 1.0F));
	}

	/** ② 两轮献祭：一击必杀、台词两态、黑心只在首轮给 */
	private static void checkDemonPactSacrifice(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.PlayerFlags.setPactSigns(player, 1);
		com.summy.reliquary.effect.PlayerFlags.setSacrificeDone(player, false);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		com.summy.reliquary.effect.DelayedChat.forget(player);

		net.minecraft.world.entity.npc.Villager first =
				EntityType.VILLAGER.create(player.serverLevel());
		if (first == null) {
			log("恶魔献祭：无法创建村民，跳过");
			return;
		}
		first.moveTo(player.getX() + 2.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
		player.serverLevel().addFreshEntity(first);
		pactHit(player, first);
		boolean instantlyKilled = first.isDeadOrDying() || first.getHealth() <= 0.0F;

		com.summy.reliquary.effect.DemonPact.onDeath(first, player);
		boolean done = com.summy.reliquary.effect.PlayerFlags.isSacrificeDone(player);
		int hearts = (int) Math.round(com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player));
		int pendingFirst = com.summy.reliquary.effect.DelayedChat.pending(player);

		net.minecraft.world.entity.npc.Villager second =
				EntityType.VILLAGER.create(player.serverLevel());
		float healthBefore = second == null ? 0.0F : second.getHealth();
		if (second != null) {
			second.moveTo(player.getX() + 2.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
			player.serverLevel().addFreshEntity(second);
			pactHit(player, second);
		}
		boolean noExtraAfterDone = second != null && second.getHealth() == healthBefore;

		com.summy.reliquary.effect.PlayerFlags.setPactSigns(player, 2);
		com.summy.reliquary.effect.PlayerFlags.setSacrificeDone(player, false);
		com.summy.reliquary.effect.DelayedChat.forget(player);
		int heartsBeforeSecond = (int) Math.round(
				com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player));
		if (second != null) {
			com.summy.reliquary.effect.DemonPact.onDeath(second, player);
		}
		int heartsAfterSecond = (int) Math.round(
				com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player));
		int pendingSecond = com.summy.reliquary.effect.DelayedChat.pending(player);
		boolean secondDone = com.summy.reliquary.effect.PlayerFlags.isSacrificeDone(player);

		first.discard();
		if (second != null) {
			second.discard();
		}
		com.summy.reliquary.effect.DelayedChat.forget(player);
		log("恶魔献祭：对村民一击必杀=" + instantlyKilled + "（应 true）、完成标记=" + done
				+ "（应 true）、首轮黑心=" + hearts + " 点（应 4 = 2 心）、首轮台词队列剩余=" + pendingFirst
				+ "（应 3 = 4 行里的后 3 行）；完成后再打村民不再追加 10000=" + noExtraAfterDone
				+ "（应 true）");
		log("恶魔献祭·二轮：重新击杀完成=" + secondDone + "（应 true）、黑心 " + heartsBeforeSecond + " → "
				+ heartsAfterSecond + "（应不变）、台词队列剩余=" + pendingSecond
				+ "（应 1 = 2 行里的后 1 行）");
	}

	/** ③ 黑心池：最后一道防线（黄血 → 黑心 → 红血） */
	private static void checkDemonPactBlackHearts(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);

		prepareBarePlayer(player);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 3.0F);
		int poolAfterFirst = (int) Math.round(com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player));
		float healthAfterFirst = player.getHealth();

		player.setHealth(player.getMaxHealth());
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 5.0F);
		int poolAfterSecond = (int) Math.round(com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player));
		float healthLossSecond = player.getMaxHealth() - player.getHealth();

		// 顺序（1.6.4：临时并入原版吸收 → 结算后拆分回收）：黄血 6 + 黑心 4，受 10 点 → 黄血 −6 / 黑心 −4 / 红血不减
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(6.0F);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 10.0F);
		int poolAfterThird = (int) Math.round(com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player));
		double absorptionAfterThird = player.getAbsorptionAmount();
		float healthLossThird = player.getMaxHealth() - player.getHealth();

		// 未佩戴契约：不吸收（池子留着、红血照掉）
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, 0, ItemStack.EMPTY));
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 4.0F);
		boolean noAbsorbWhenIdle = Math.abs(4.0F - (player.getMaxHealth() - player.getHealth())) < 1.0E-4F
				&& Math.round(com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player)) == 4;
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, 0,
						new ItemStack(SummyReliquary.THE_PACT.get())));

		log("黑心池（真实命中）：4 点受 3 点 → 池 " + poolAfterFirst + "（应 1）、红血 " + healthAfterFirst
				+ "（应 20 = 不掉血）；池 1 再受 5 点 → 池 " + poolAfterSecond + "（应 0）、红血 −"
				+ healthLossSecond + "（应 4 = 黑心只扛 1 点）");
		log("黑心顺序（黄血 6 + 黑心 4，受 10 点）= 池 " + poolAfterThird
				+ "（应 0）、护盾剩 " + absorptionAfterThird + "（应 0）、红血 −" + healthLossThird
				+ "（应 0 = 黄血 −6 / 黑心 −4 / 红血不减）；"
				+ "未佩戴契约时不吸收=" + noAbsorbWhenIdle + "（应 true）");
	}

	/** ④ 邪恶度：分档、每日上限、衰减、奇偶交替加成、同乘区 */
	private static void checkDemonPactEvil(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilToday(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilFriendlyToday(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);

		double friendlyGain = com.summy.reliquary.effect.DemonPact.gainFor(
				EntityType.COW.create(player.serverLevel()));
		double neutralGain = com.summy.reliquary.effect.DemonPact.gainFor(
				EntityType.IRON_GOLEM.create(player.serverLevel()));
		double hostileGain = com.summy.reliquary.effect.DemonPact.gainFor(
				EntityType.ZOMBIE.create(player.serverLevel()));
		double wardenGain = com.summy.reliquary.effect.DemonPact.gainFor(
				EntityType.WARDEN.create(player.serverLevel()));
		double dragonGain = com.summy.reliquary.effect.DemonPact.gainFor(
				EntityType.ENDER_DRAGON.create(player.serverLevel()));

		Zombie zombie = EntityType.ZOMBIE.create(player.serverLevel());
		com.summy.reliquary.effect.DemonPact.gainEvil(player, zombie);
		double afterHostile = com.summy.reliquary.effect.PlayerFlags.evil(player);
		net.minecraft.world.entity.animal.Cow cow = EntityType.COW.create(player.serverLevel());
		com.summy.reliquary.effect.DemonPact.gainEvil(player, cow);
		double afterFriendly = com.summy.reliquary.effect.PlayerFlags.evil(player);
		for (int index = 0; index < 30; index++) {
			com.summy.reliquary.effect.DemonPact.gainEvil(player, cow);
		}
		double friendlyToday = com.summy.reliquary.effect.PlayerFlags.evilFriendlyToday(player);

		com.summy.reliquary.effect.PlayerFlags.setEvilToday(player, 100.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 500.0D);
		com.summy.reliquary.effect.DemonPact.gainEvil(player, zombie);
		double todayCapped = com.summy.reliquary.effect.PlayerFlags.evilToday(player);
		double evilCapped = com.summy.reliquary.effect.PlayerFlags.evil(player);
		com.summy.reliquary.effect.DemonPact.gainEvil(player, zombie);
		double evilStillCapped = com.summy.reliquary.effect.PlayerFlags.evil(player);

		long day = player.level().getDayTime() / 24000L;
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 100.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilToday(player, 50.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, day - 1L);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterDecay = com.summy.reliquary.effect.PlayerFlags.evil(player);
		double todayAfterDecay = com.summy.reliquary.effect.PlayerFlags.evilToday(player);

		boolean oddAttack = Math.abs(com.summy.reliquary.effect.DemonPact.evilAttackPercent(1) - 0.1D) < 1.0E-6D
				&& Math.abs(com.summy.reliquary.effect.DemonPact.evilSpeedPercent(1)) < 1.0E-6D;
		boolean evenSpeed = Math.abs(com.summy.reliquary.effect.DemonPact.evilAttackPercent(2) - 0.1D) < 1.0E-6D
				&& Math.abs(com.summy.reliquary.effect.DemonPact.evilSpeedPercent(2) - 0.1D) < 1.0E-6D;
		boolean maxed = Math.abs(com.summy.reliquary.effect.DemonPact.evilAttackPercent(1000) - 50.0D) < 1.0E-6D
				&& Math.abs(com.summy.reliquary.effect.DemonPact.evilSpeedPercent(1000) - 50.0D) < 1.0E-6D;

		clearBlessingSlots(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		Zombie target = spawnHolyLightTarget(player, 6.0D);
		float pactOnly = target == null ? 0.0F : outgoingDamage(player, target, 10.0F);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		float pactAndEvil = target == null ? 0.0F : outgoingDamage(player, target, 10.0F);
		if (target != null) {
			target.discard();
		}

		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, 0, ItemStack.EMPTY));
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 10.0D);
		com.summy.reliquary.effect.DemonPact.gainEvil(player, zombie);
		boolean idleNoGain = Math.abs(com.summy.reliquary.effect.PlayerFlags.evil(player) - 10.0D) < 1.0E-6D;
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, 0,
						new ItemStack(SummyReliquary.THE_PACT.get())));

		if (zombie != null) {
			zombie.discard();
		}
		if (cow != null) {
			cow.discard();
		}
		log(String.format("邪恶度分档：友善=%.1f（应 1.0）、中立=%.1f（应 0.5）、敌对=%.1f（应 0.1）、"
						+ "监守者=%.1f（应 10.0）、末影龙=%.1f（应 20.0）",
				friendlyGain, neutralGain, hostileGain, wardenGain, dragonGain));
		log(String.format("邪恶度积攒：敌对后=%.1f（应 0.1）、友善后=%.1f（应 1.1）、友善当日累计=%.1f"
						+ "（应 20.0 = 上限）", afterHostile, afterFriendly, friendlyToday));
		log(String.format("邪恶度上限/衰减：当日总量=%.1f（应 100.0 = 已在当日上限）、此时击杀 evil=%.1f"
						+ "（应 500.0 不加）、再加=%.1f（应仍 500.0）、跨日衰减后=%.1f（应 95.0）、"
						+ "当日计数重置=%.1f（应 0.0）",
				todayCapped, evilCapped, evilStillCapped, afterDecay, todayAfterDecay));
		log("邪恶度加成（奇偶交替）：1 点→攻击+0.1/攻速 0=" + oddAttack + "（应 true）、2 点→攻速+0.1="
				+ evenSpeed + "（应 true）、1000 点→各 +50%=" + maxed + "（应 true）");
		log(String.format("出伤（基准 10）：仅契约=%.1f（应 11.6）、契约+1000 邪恶=%.1f（应 16.6）；"
						+ "不佩戴契约不积攒=%s（应 true）", pactOnly, pactAndEvil, idleNoGain));
	}

	/** ⑤ 邪恶度里程碑 / 配方门禁 / 666 锁定天使线 */
	private static void checkDemonPactUnlocks(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		com.summy.reliquary.effect.DelayedChat.forget(player);

		// 100 → 复仇之魂：只发台词、不发物品；重复到达不重发
		com.summy.reliquary.effect.DemonPact.setEvilForTest(player, 100.0D);
		boolean first = com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT
				.unlocked(com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player));
		int pendingFirst = com.summy.reliquary.effect.DelayedChat.pending(player);
		int itemsGiven = player.getInventory().countItem(SummyReliquary.VENGEFUL_SPIRIT.get());
		com.summy.reliquary.effect.DelayedChat.forget(player);
		com.summy.reliquary.effect.DemonPact.setEvilForTest(player, 150.0D);
		int pendingAgain = com.summy.reliquary.effect.DelayedChat.pending(player);

		// 300 → 咒印
		com.summy.reliquary.effect.DemonPact.setEvilForTest(player, 300.0D);
		boolean mark = com.summy.reliquary.effect.EvilUnlock.THE_MARK
				.unlocked(com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player));

		// 门禁：未解锁时合成咒印 → 收走产物 + 退回 3 个「6」
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		int unlocksAtGate = com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player);
		// ① 直接调用门禁（排除事件总线的干扰）
		player.getInventory().clearContent();
		// 注意：事件里要传"手上那份"产物、背包里放副本 —— 因为 Inventory#add 会把传入的 stack 清空
		ItemStack craftedDirect = new ItemStack(SummyReliquary.THE_MARK.get());
		player.getInventory().add(craftedDirect.copy());
		com.summy.reliquary.effect.EvilRecipeGate.onCrafted(new PlayerEvent.ItemCraftedEvent(
				player, craftedDirect, player.inventoryMenu.getCraftSlots()));
		int marksAfterDirect = player.getInventory().countItem(SummyReliquary.THE_MARK.get());
		int sixAfterDirect = player.getInventory().countItem(SummyReliquary.SIX.get());
		boolean gateDirect = marksAfterDirect == 0 && sixAfterDirect == 3;
		// ② 通过事件总线（游戏内的真实路径）
		player.getInventory().clearContent();
		ItemStack crafted = new ItemStack(SummyReliquary.THE_MARK.get());
		player.getInventory().add(crafted.copy());
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
				player, crafted, player.inventoryMenu.getCraftSlots()));
		int marksAfterGate = player.getInventory().countItem(SummyReliquary.THE_MARK.get());
		int sixAfterGate = player.getInventory().countItem(SummyReliquary.SIX.get());
		boolean gateBlocks = marksAfterGate == 0 && sixAfterGate == 3;

		// 解锁后再合成 → 放行
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player)
						| com.summy.reliquary.effect.EvilUnlock.THE_MARK.bit());
		player.getInventory().clearContent();
		ItemStack crafted2 = new ItemStack(SummyReliquary.THE_MARK.get());
		player.getInventory().add(crafted2.copy());
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
				player, crafted2, player.inventoryMenu.getCraftSlots()));
		boolean gateAllows = player.getInventory().countItem(SummyReliquary.THE_MARK.get()) == 1;

		// 666 → 永久锁定天使线（掉回 600 也不解除）
		com.summy.reliquary.effect.DemonPact.setEvilForTest(player, 666.0D);
		boolean locked = com.summy.reliquary.effect.PlayerFlags.isHellLocked(player);
		int unlocksAt666 = com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 600.0D);
		com.summy.reliquary.effect.DemonPact.checkMilestones(player);
		boolean stillLocked = com.summy.reliquary.effect.PlayerFlags.isHellLocked(player);
		int unlocksAfterDrop = com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player);

		// 1000 → 亚巴顿
		com.summy.reliquary.effect.DemonPact.setEvilForTest(player, 1000.0D);
		boolean abaddon = com.summy.reliquary.effect.EvilUnlock.ABADDON
				.unlocked(com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player));

		// 666 后痛悔短祷被拒绝且不消耗
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, true);
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				overworld.getSharedSpawnPos()).above();
		player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
				player.getYRot(), player.getXRot());
		com.summy.reliquary.item.ActOfContritionItem.resetUsed(player);
		com.summy.reliquary.item.ActOfContritionItem.resetNotified(player);
		com.summy.reliquary.item.ActOfContritionItem.forget(player);
		com.summy.reliquary.effect.DelayedChat.forget(player);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.ACT_OF_CONTRITION.get()));
		int countBefore = player.getMainHandItem().getCount();
		var result = SummyReliquary.ACT_OF_CONTRITION.get().use(overworld, player,
				net.minecraft.world.InteractionHand.MAIN_HAND);
		boolean refused = result.getResult() == net.minecraft.world.InteractionResult.FAIL;
		boolean notConsumed = player.getMainHandItem().getCount() == countBefore;
		boolean stillUnused = !com.summy.reliquary.item.ActOfContritionItem.isUsed(player);
		int pendingHell = com.summy.reliquary.effect.DelayedChat.pending(player);
		com.summy.reliquary.effect.DelayedChat.forget(player);

		log("邪恶解锁：100→复仇之魂=" + first + "（应 true）、台词队列剩余=" + pendingFirst + "（应 "
				+ com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT.lineCount() + " = 台词行数）、"
				+ "未发放物品=" + (itemsGiven == 0) + "（应 true）、重复到达不重发=" + (pendingAgain == 0)
				+ "（应 true）、300→咒印=" + mark + "（应 true）");
		log("邪恶门禁：未解锁合成被拦（咒印消失 + 退回 3 个「6」）=" + gateBlocks + "（应 true）、"
				+ "解锁后合成放行=" + gateAllows + "（应 true）；诊断：拦截后 咒印="
				+ marksAfterGate + "（应 0）、「6」=" + sixAfterGate + "（应 3）、gate 时的解锁位图="
				+ unlocksAtGate + "（应 0）、直接调用门禁=" + gateDirect + "（应 true；咒印="
				+ marksAfterDirect + "、「6」=" + sixAfterDirect + "）");
		log("666：锁定天使线=" + locked + "（应 true）、掉回 600 仍锁定=" + stillLocked + "（应 true）、"
				+ "解锁位图不回收=" + (unlocksAfterDrop == unlocksAt666) + "（应 true）、1000→亚巴顿="
				+ abaddon + "（应 true）");
		log("666 忏悔：被拒绝=" + refused + "（应 true）、不消耗=" + notConsumed + "（应 true）、"
				+ "仍未使用=" + stillUnused + "（应 true）、四行提示为即时发送（不排队）="
				+ (pendingHell == 0) + "（应 true）、行数="
				+ com.summy.reliquary.item.ActOfContritionItem.hellLockedLines().size() + "（应 4）");
	}

	/** 1.6.0：契约 / 黑心 HUD / 献祭与解锁文案（客户端专用 —— 语言与贴图只在客户端可读） */
	private static void checkDemonPactClient() {
		// ① 黑心 HUD 的计数与两张自绘贴图
		boolean counts = com.summy.reliquary.effect.DemonPact.blackHeartFullCount(4) == 2
				&& !com.summy.reliquary.effect.DemonPact.blackHeartHasHalf(4)
				&& com.summy.reliquary.effect.DemonPact.blackHeartFullCount(3) == 1
				&& com.summy.reliquary.effect.DemonPact.blackHeartHasHalf(3);
		var resources = Minecraft.getInstance().getResourceManager();
		boolean textures = resources.getResource(new net.minecraft.resources.ResourceLocation(
						"summy-reliquary", "textures/gui/demon_black_heart/full.png")).isPresent()
				&& resources.getResource(new net.minecraft.resources.ResourceLocation(
						"summy-reliquary", "textures/gui/demon_black_heart/half.png")).isPresent();
		boolean sixModel = resources.getResource(new net.minecraft.resources.ResourceLocation(
				"summy-reliquary", "models/item/six.json")).isPresent();

		// ② 666 四行的配色：前三行灰正体、末行暗红斜体
		List<net.minecraft.network.chat.Component> hell = com.summy.reliquary.item.ActOfContritionItem
				.hellLockedLines();
		boolean hellStyle = hell.size() == 4
				&& "#AAAAAA".equals(colorOf(hell.get(0))) && !hell.get(0).getStyle().isItalic()
				&& "#AAAAAA".equals(colorOf(hell.get(1))) && "#AAAAAA".equals(colorOf(hell.get(2)))
				&& "#8B0000".equals(colorOf(hell.get(3))) && hell.get(3).getStyle().isItalic();

		// ③ 献祭台词两态 + 解锁台词（含结尾灰行）
		boolean sacrifice = com.summy.reliquary.effect.DemonPact.sacrificeLines(false).size() == 4
				&& com.summy.reliquary.effect.DemonPact.sacrificeLines(true).size() == 2;
		List<net.minecraft.network.chat.Component> unlock = com.summy.reliquary.effect.DemonPact
				.unlockLines(com.summy.reliquary.effect.EvilUnlock.BRIMSTONE);
			boolean unlockStyle = unlock.size() == 4 && "#8B0000".equals(colorOf(unlock.get(0)))
					&& "#AAAAAA".equals(colorOf(unlock.get(3)));
			// 1.7.10 修订：700 档的「（你腰间的匕首震动了一下）」改成**灰色正体尾行**、排在「（你解锁了玄秘魔眼）」之后
			List<net.minecraft.network.chat.Component> eyeUnlock = com.summy.reliquary.effect.DemonPact
					.unlockLines(com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE);
			String eyeTail = com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.tailKey();
			boolean eyeOrder = eyeUnlock.size() == 4
					&& "#8B0000".equals(colorOf(eyeUnlock.get(0))) && eyeUnlock.get(0).getStyle().isItalic()
					&& "#8B0000".equals(colorOf(eyeUnlock.get(1))) && eyeUnlock.get(1).getStyle().isItalic()
					&& "#AAAAAA".equals(colorOf(eyeUnlock.get(2))) && !eyeUnlock.get(2).getStyle().isItalic()
					&& "#AAAAAA".equals(colorOf(eyeUnlock.get(3))) && !eyeUnlock.get(3).getStyle().isItalic()
					&& eyeTail != null && eyeUnlock.get(3).getString()
							.equals(net.minecraft.network.chat.Component.translatable(eyeTail).getString());
			boolean lineCounts = com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT.lineCount() == 2
					&& com.summy.reliquary.effect.EvilUnlock.THE_MARK.lineCount() == 2
					&& com.summy.reliquary.effect.EvilUnlock.NIGHT_WRAITH.lineCount() == 3
					&& com.summy.reliquary.effect.EvilUnlock.BRIMSTONE.lineCount() == 3
					// 1.7.10：700 档的「（你腰间的匕首震动了一下）」从"暗红台词行"改成"灰色尾行"（tailKey），
					// 所以暗红台词行回到 2 条，总行数仍是 4（2 台词 + 「你解锁了」 + 尾行）
					&& com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.lineCount() == 2
					&& com.summy.reliquary.effect.EvilUnlock.ABYSS_LORD.lineCount() == 2
					&& com.summy.reliquary.effect.EvilUnlock.ABADDON.lineCount() == 3;

		// ④ 关键文案确实有译文（不写死中文，换语言也不会误报）
		boolean translated = translated("item.summy-reliquary.the_pact.tagline")
				&& translated("item.summy-reliquary.the_pact.quote")
				&& translated("item.summy-reliquary.the_pact.shift.bound")
				&& translated("item.summy-reliquary.the_pact.shift.evil")
				&& translated("message.summy-reliquary.recipe.evil_locked")
				&& translated("message.summy-reliquary.pact.sacrifice.again.1")
				&& translated("message.summy-reliquary.pact.hell_locked.4")
				&& translated("item.summy-reliquary.six");

		log("恶魔契约 HUD：满心/半心计数=" + counts + "（应 true）、黑心贴图存在=" + textures
				+ "（应 true）、six 模型存在=" + sixModel + "（应 true）");
		log("666 四行配色：前三行灰正体 + 末行暗红斜体=" + hellStyle + "（应 true）；"
				+ "献祭台词两态 4/2 行=" + sacrifice + "（应 true）");
			log("解锁台词：行数表=" + lineCounts + "（应 true）、台词暗红 + 结尾灰行=" + unlockStyle
					+ "（应 true）、700 档 4 行顺序与配色（2 暗红斜体 + 灰「你解锁了」 + 灰尾行，尾行文案=「"
					+ (eyeTail == null ? "无" : net.minecraft.network.chat.Component.translatable(eyeTail).getString())
					+ "」）=" + eyeOrder + "（应 true）、关键语言键均有译文=" + translated + "（应 true）");
	}

	/** 某个语言键在客户端当前语言下确实有译文（不写死中文） */
	private static boolean translated(String key) {
		String text = net.minecraft.network.chat.Component.translatable(key).getString();
		return !text.isEmpty() && !text.equals(key);
	}

	/** 1.5.9：恶魔交易的文案与配色（客户端专用 —— 语言文件只在客户端解析） */
	private static void checkDemonDealTexts() {
		net.minecraft.network.chat.Component invite = com.summy.reliquary.effect.DemonDeal
				.demon("message.summy-reliquary.demon.invite.1");
		boolean inviteText = invite.getString().equals(net.minecraft.network.chat.Component
				.translatable("message.summy-reliquary.demon.invite.1").getString());
		boolean inviteRed = "#8B0000".equals(colorOf(invite)) && invite.getStyle().isItalic();
		net.minecraft.network.chat.Component hint = com.summy.reliquary.effect.DemonDeal
				.hint("message.summy-reliquary.demon.invite.hint");
		boolean hintGray = "#AAAAAA".equals(colorOf(hint)) && !hint.getStyle().isItalic();
		net.minecraft.network.chat.Component wrong = com.summy.reliquary.effect.DemonDeal.wrongPlaceLine();
		boolean wrongRed = "#8B0000".equals(colorOf(wrong)) && wrong.getStyle().isItalic()
				&& wrong.getString().contains("埋葬无尽灵魂");
		// 1.5.10：文字进度已删除，改为视野收缩反馈；1.6.2 起**手持贴图拉伸已按需求移除**，只保留 FOV 缩放
		boolean stretchRemoved = true;
		double fovScale = com.summy.reliquary.config.ReliquaryConfig.demonChargeFovDefaultScale();
		int lineDelayDefault = com.summy.reliquary.config.ReliquaryConfig.demonLineDelayDefaultTicks();
		int lineDelayLoaded = com.summy.reliquary.config.ReliquaryConfig.demonLineDelayTicks();
		List<net.minecraft.network.chat.Component> sealed = com.summy.reliquary.item.PentagramItem.sealedLines();
		boolean sealedOk = sealed.size() == 2 && "#B22222".equals(colorOf(sealed.get(0)))
				&& sealed.get(0).getStyle().isItalic();
		// 五芒星提示的"恶魔话语"四态（纯函数：不碰真实客户端状态）
		// 1.7.2：第 4 个参数从 revelation 泛化成 tradeClosed（已启示 / 已放弃一切 / 持有圣心）
		int modeNone = com.summy.reliquary.item.PentagramItem.spokenMode(false, false, false, false);
		int modeTrade = com.summy.reliquary.item.PentagramItem.spokenMode(true, false, false, false);
		int modeDemon = com.summy.reliquary.item.PentagramItem.spokenMode(true, true, false, false);
		int modeSealed = com.summy.reliquary.item.PentagramItem.spokenMode(true, false, true, false);
		// 恶魔交易已关闭（已启示 / 已放弃一切 / 持有圣心）→ 那两行不再显示
		int modeTradeClosed = com.summy.reliquary.item.PentagramItem.spokenMode(true, false, false, true);
		log("恶魔文案：邀请首行一致=" + inviteText + "（应 true）、暗红+斜体=" + inviteRed
				+ "（应 true）、灰色提示行=" + hintGray + "（应 true）、错误行暗红斜体+含「埋葬无尽灵魂」="
				+ wrongRed + "（应 true）");
		log("五芒星蓄力反馈：手持贴图拉伸已于 1.6.2 移除=" + stretchRemoved
				+ "（应 true）、FOV 收缩默认=" + fovScale + "（应 0.2）、台词间隔默认=" + lineDelayDefault
				+ " tick（应 40；本档实际读取=" + lineDelayLoaded
				+ "，旧配置文件不会被自动改写）、进度文字已删除（无 chargingLine 键）");
		log("恶魔文案：五芒星「契约依然作数」两行=" + sealed.size() + "（应 2）、首行深红+斜体=" + sealedOk
				+ "（应 true，色应 #B22222）；文案=「" + sealed.get(0).getString() + "」/「"
				+ sealed.get(1).getString() + "」");
		log("五芒星提示四态：未说过=" + modeNone + "（应 0）、说过未签约=" + modeTrade + "（应 1 原来的两行）、"
				+ "持恶魔标记=" + modeDemon + "（应 0 = 移除且不补）、换回天使后=" + modeSealed
				+ "（应 2 契约依然作数）、交易已关闭（启示 / 放弃一切 / 持有圣心任一）=" + modeTradeClosed
				+ "（应 0 = 不再提示）");
	}

	/** 圣心配方（普通、无门槛）+ 1.5.8 诊断入口不抛异常 */
	private static void checkSacredHeartRecipeAndDiagnostics(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		var recipe = server.getRecipeManager().byKey(SummyReliquary.id("sacred_heart")).orElse(null);
		boolean exists = recipe != null && recipe.getResultItem(player.level().registryAccess())
				.is(SummyReliquary.SACRED_HEART.get());
		// 无门槛：构造一次合成事件，产物必须原样留在背包里（不被退回、不被收走）
		player.getInventory().clearContent();
		ItemStack crafted = new ItemStack(SummyReliquary.SACRED_HEART.get());
		player.getInventory().add(crafted);
		int before = player.getInventory().countItem(SummyReliquary.SACRED_HEART.get());
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
				player, crafted, player.inventoryMenu.getCraftSlots()));
		int after = player.getInventory().countItem(SummyReliquary.SACRED_HEART.get());
		boolean noGate = before == 1 && after == 1;
		// 诊断：三个入口的快照方法都不能抛异常（写盘入口的监听在 ReliquaryEvents 里）
		boolean snapshotOk = true;
		try {
			com.summy.reliquary.ReliquaryEvents.logSlotSnapshot(player, "自检");
			// 真的发一次 SaveToFile 事件，验证"写盘快照"这条监听确实挂着
			MinecraftForge.EVENT_BUS.post(new PlayerEvent.SaveToFile(
					player, null, player.getStringUUID()));
		} catch (Throwable throwable) {
			snapshotOk = false;
			log("诊断入口抛异常：" + throwable);
		}
		log("圣心配方：sacred_heart 存在=" + exists + "（应 true）、结果=圣心；无门槛（合成事件后产物保留）="
				+ noGate + "（应 true，产物 " + before + " → " + after + "）");
		log("1.5.8 诊断：栏位快照入口不抛异常=" + snapshotOk + "（应 true）、保存监听（SaveToFile）已注册"
				+ "（编译期绑定）；心之碎片真正掉落时的 INFO 日志已在上方用例触发");
		player.getInventory().clearContent();
	}

	// ==================== 1.6.1：素材落库 / 仪式法袍 / 撒旦圣经 / 签约快照 / 魂心 HUD ====================

	/** 清掉背饰与魂印栏（让每个用例的起点干净） */
	private static void clearRobeAndSeal(ServerPlayer player) {
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			if (handler.getCurios().containsKey(ReliquarySlots.BACK)) {
				handler.setEquippedCurio(ReliquarySlots.BACK, 0, ItemStack.EMPTY);
			}
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
		});
	}

	/** 直接往某栏位塞一件物品（绕过 canEquip，用于搭场景） */
	/** 把某物品从背包 + 全部 Curios 栏位里彻底删掉（1.7.2 搭场景用，例如清掉圣心以解除交易封锁） */
	private static void clearItemEverywhere(ServerPlayer player, net.minecraft.world.item.Item item) {
		player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item), Integer.MAX_VALUE,
				player.inventoryMenu.getCraftSlots());
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (var entry : new java.util.ArrayList<>(handler.getCurios().entrySet())) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					if (entry.getValue().getStacks().getStackInSlot(index).is(item)) {
						handler.setEquippedCurio(entry.getKey(), index, ItemStack.EMPTY);
					}
				}
			}
		});
	}

	private static void equip(ServerPlayer player, String slot, net.minecraft.world.item.Item item) {
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(slot, 0, new ItemStack(item)));
	}

	/** 清空某栏位的 0 号格（1.6.7：搭场景时用来卸下饰品） */
	private static void unequip(ServerPlayer player, String slot) {
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(slot, 0, ItemStack.EMPTY));
	}

	/** 该玩家是否戴着某件物品 */
	private static boolean wears(ServerPlayer player, net.minecraft.world.item.Item item) {
		return com.summy.reliquary.util.CurioHelper.wears(player, item);
	}

	/** ① 仪式法袍门槛：未签约被拦、装错栏位被拦、签约后放行 */
	private static void checkRobesGate(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, true);
		boolean lockedDenied = !canEquipIn(player, SummyReliquary.CEREMONIAL_ROBES.get(), ReliquarySlots.BACK);
		boolean wrongSlot = !canEquipIn(player, SummyReliquary.CEREMONIAL_ROBES.get(), ReliquarySlots.HALO);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		boolean unlockedAllowed = canEquipIn(player, SummyReliquary.CEREMONIAL_ROBES.get(), ReliquarySlots.BACK);
		// 背饰栏必须真的挂在玩家身上（Curios 自带栏位，靠数据包 slots + #curios:back 标签）
		boolean backSlotExists = CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(ReliquarySlots.BACK)
						&& handler.getCurios().get(ReliquarySlots.BACK).getSlots() == 1)
				.orElse(false);
		boolean backTag = new ItemStack(SummyReliquary.CEREMONIAL_ROBES.get()).is(
				net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
						new net.minecraft.resources.ResourceLocation("curios", "back")));
		log("仪式法袍门槛：未签约被拦=" + lockedDenied + "（应 true）、装错栏位被拦=" + wrongSlot
				+ "（应 true）、签约后放行=" + unlockedAllowed + "（应 true）、背饰栏存在=" + backSlotExists
				+ "（应 true）、#curios:back 标签命中=" + backTag + "（应 true）");
	}

	/** ② 仪式法袍：+2 攻击力（加法、卸下回收）+ 邪恶每日衰减 5 → 3 */
	private static void checkRobesStatsAndDecay(ServerPlayer player) {
		resetDemonPactState(player);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, true);
		player.getInventory().clearContent();
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		clearRobeAndSeal(player);
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler ->
		{
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.CHARM, 0, ItemStack.EMPTY);
		});
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double base = player.getAttributeValue(Attributes.ATTACK_DAMAGE);

		equip(player, ReliquarySlots.BACK, SummyReliquary.CEREMONIAL_ROBES.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double withRobe = player.getAttributeValue(Attributes.ATTACK_DAMAGE);

		long day = player.level().getDayTime() / 24000L;
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 100.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, day - 1L);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double decayWithRobe = 100.0D - com.summy.reliquary.effect.PlayerFlags.evil(player);

		// 卸下法袍（对照）
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BACK, 0, ItemStack.EMPTY));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double withoutRobe = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 100.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, day - 1L);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double decayWithout = 100.0D - com.summy.reliquary.effect.PlayerFlags.evil(player);

		log("仪式法袍：攻击力 " + base + " → " + withRobe + "（应 +2 = " + (base + 2.0D) + "）→ 卸下 "
				+ withoutRobe + "（应回落 " + base + "）；邪恶衰减 戴=" + decayWithRobe + "（应 3）、不戴="
				+ decayWithout + "（应 5）");
	}

	/** ③ 黑心上限（动态 4 → 8 → 10）与撒旦圣经的周期补满 */
	private static void checkBlackHeartCap(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);

		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		com.summy.reliquary.effect.DemonPact.addBlackHearts(player, 100.0D);
		double baseMax = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		equip(player, ReliquarySlots.BACK, SummyReliquary.CEREMONIAL_ROBES.get());
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		com.summy.reliquary.effect.DemonPact.addBlackHearts(player, 100.0D);
		double robeMax = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		com.summy.reliquary.effect.DemonPact.addBlackHearts(player, 100.0D);
		double bothMax = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		// 卸下法袍 → 上限降回 6（2 基础 + 1 撒旦圣经），tick 时把超出的部分夹回
		top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.BACK, 0, ItemStack.EMPTY));
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double clamped = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		// 30 秒补满：把计时器压到下一秒再跑一次 tick
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		com.summy.reliquary.effect.DemonPact.primeBlackHeartRefillForTest(player);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double refilled = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		log("黑心上限：基础=" + baseMax + "（应 4）、+法袍=" + robeMax + "（应 8）、+撒旦圣经=" + bothMax
				+ "（应 10）、卸下法袍后夹回=" + clamped + "（应 6）；撒旦圣经周期补满=" + refilled + "（应 6）");
	}

	/** ④ 签约快照 A：带着美德签约 → 重置为七罪之源全未激活 → 全部激活自动变撒旦圣经 → 忏悔拿回美德 */
	private static void checkSatanicSnapshotVirtues(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		// 1.7.2：真·美德态 = 七罪**全部已赎罪**（旧用例用"3 赎 + 4 激活"配美德，那是作弊态；
		// 新口径下签约只重置已赎罪项，所以这里改成真正的全赎，才能继续断言"全未激活"）
		setSinMasksForTest(player, 0b1111111, 0b1111111);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.VIRTUES.get());
		int sinsBefore = com.summy.reliquary.sin.SinManager.mask(player);
		int redeemedBefore = com.summy.reliquary.sin.SinManager.redeemedMask(player);

		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);

		var snapshot = com.summy.reliquary.effect.PlayerFlags.sinSnapshot(player);
		boolean snapshotSaved = snapshot != null && "virtues".equals(snapshot.getString("item"))
				&& snapshot.getInt("sins") == sinsBefore && snapshot.getInt("redeemed") == redeemedBefore;
		boolean swappedToSource = wears(player, SummyReliquary.SOURCE_OF_SINS.get());
		boolean allReset = com.summy.reliquary.sin.SinManager.mask(player) == 0
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == 0;

		// 七罪全部重新激活 → 每秒兜底自动转化
		setSinMasksForTest(player, 0b1111111, 0);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		boolean converted = wears(player, SummyReliquary.SATANIC_BIBLE.get());

		// 忏悔 → 按快照恢复（拿回美德 + 原来的 3 赎 + 4 激活）
		boolean swapped = com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		boolean gotVirtuesBack = wears(player, SummyReliquary.VIRTUES.get());
		boolean masksRestored = com.summy.reliquary.sin.SinManager.mask(player) == sinsBefore
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == redeemedBefore;
		boolean snapshotCleared = !com.summy.reliquary.effect.PlayerFlags.hasSinSnapshot(player);

		log("签约快照（美德态）：快照已存=" + snapshotSaved + "（应 true，item=virtues、sins=" + sinsBefore
				+ "、redeemed=" + redeemedBefore + "）、重置为七罪之源=" + swappedToSource + "（应 true）、七罪全未激活="
				+ allReset + "（应 true）；全激活后自动转化撒旦圣经=" + converted + "（应 true）");
		log("忏悔恢复（美德态）：替换成功=" + swapped + "（应 true）、拿回美德=" + gotVirtuesBack
				+ "（应 true）、七罪状态还原=" + masksRestored + "（应 true）、快照已清除=" + snapshotCleared
				+ "（应 true）");
	}

	/** ⑤ 签约快照 B：3 罪已赎 + 4 罪激活 → 签约重置 → 忏悔后仍是 3+4 */
	private static void checkSatanicSnapshotRedeemed(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		setSinMasksForTest(player, 0b1111000, 0b0000111);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		int sinsBefore = com.summy.reliquary.sin.SinManager.mask(player);
		int redeemedBefore = com.summy.reliquary.sin.SinManager.redeemedMask(player);

		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		// 1.7.2：只重置"已赎罪"的项 —— 3 赎（低三位）+ 4 激活（高四位）→ 3 项变未激活、4 项仍激活
		int sinsAfterPact = com.summy.reliquary.sin.SinManager.mask(player);
		int redeemedAfterPact = com.summy.reliquary.sin.SinManager.redeemedMask(player);
		boolean onlyRedeemedReset = sinsAfterPact == 0b1111000 && redeemedAfterPact == 0;
		boolean stayedSource = wears(player, SummyReliquary.SOURCE_OF_SINS.get());

		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		int sinsAfter = com.summy.reliquary.sin.SinManager.mask(player);
		int redeemedAfter = com.summy.reliquary.sin.SinManager.redeemedMask(player);

		log("签约快照（3 赎 + 4 激活）：签约后掩码=" + Integer.toBinaryString(sinsAfterPact) + "/"
				+ Integer.toBinaryString(redeemedAfterPact) + "（应 1111000/0 = 只把已赎罪的 3 项置未激活，"
				+ "4 项保持激活）=" + onlyRedeemedReset + "（应 true）、魂印栏仍是七罪之源="
				+ stayedSource + "（应 true）；忏悔后 sins=" + sinsAfter + "（应 " + sinsBefore + "）、redeemed="
				+ redeemedAfter + "（应 " + redeemedBefore + "）");
	}

	/** ⑥ 撒旦圣经：继承七罪加成、屏蔽全部负面、光环归零、非创造摘不下 + 死亡不掉 */
	private static void checkSatanicBibleEffects(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, true);

		SlotContext ctx = new SlotContext(ReliquarySlots.SOUL_SEAL, player, 0, false, true);
		var bible = (top.theillusivec4.curios.api.type.capability.ICurioItem) SummyReliquary.SATANIC_BIBLE.get();
		var virtues = (top.theillusivec4.curios.api.type.capability.ICurioItem) SummyReliquary.VIRTUES.get();
		boolean sealOnly = canEquipIn(player, SummyReliquary.SATANIC_BIBLE.get(), ReliquarySlots.SOUL_SEAL)
				&& !canEquipIn(player, SummyReliquary.SATANIC_BIBLE.get(), ReliquarySlots.BACK);
		boolean noRightClick = !bible.canEquipFromUse(ctx, new ItemStack(SummyReliquary.SATANIC_BIBLE.get()));
		// 自检玩家默认是创造模式：切到生存再验证"摘不下来"
		var previousMode = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		boolean bibleLocked = !bible.canUnequip(ctx, new ItemStack(SummyReliquary.SATANIC_BIBLE.get()))
				&& bible.getDropRule(ctx, player.damageSources().generic(), 0, false,
						new ItemStack(SummyReliquary.SATANIC_BIBLE.get()))
						== top.theillusivec4.curios.api.type.capability.ICurio.DropRule.ALWAYS_KEEP;
		boolean virtuesLocked = !virtues.canUnequip(ctx, new ItemStack(SummyReliquary.VIRTUES.get()))
				&& virtues.getDropRule(ctx, player.damageSources().generic(), 0, false,
						new ItemStack(SummyReliquary.VIRTUES.get()))
						== top.theillusivec4.curios.api.type.capability.ICurio.DropRule.ALWAYS_KEEP;
		player.setGameMode(previousMode);

		// 七罪全部激活：戴撒旦圣经时"增益全生效、负面全失效"
		setSinMasksForTest(player, 0b1111111, 0);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		boolean awakenedAll = true;
		boolean activeNone = true;
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			awakenedAll &= com.summy.reliquary.sin.SinEffects.awakened(player, sin);
			activeNone &= !com.summy.reliquary.sin.SinEffects.active(player, sin);
		}
		float incomingWithBible = com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 10.0F);
		double haloWithBible = com.summy.reliquary.effect.HaloState.multiplier(player);

		// 换回七罪之源做对照：傲慢的受伤 +50% 生效、光环减半
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		float incomingWithSource = com.summy.reliquary.sin.SinEffects.modifyIncomingDamage(player, 10.0F);
		double haloWithSource = com.summy.reliquary.effect.HaloState.multiplier(player);

		log("撒旦圣经门槛：只认魂印栏=" + sealOnly + "（应 true）、禁止右键佩戴=" + noRightClick
				+ "（应 true）、非创造摘不下 + 死亡不掉=" + bibleLocked + "（应 true）；美德同款=" + virtuesLocked
				+ "（应 true）");
		log("撒旦圣经继承：全加成生效=" + awakenedAll + "（应 true）、无任何负面=" + activeNone
				+ "（应 true）；受到伤害 10 → 戴撒旦圣经 " + incomingWithBible + "（应 10 = 傲慢不生效）、戴七罪之源 "
				+ incomingWithSource + "（应 15 = 傲慢 +50%）；光环倍率 戴撒旦圣经 " + haloWithBible
				+ "（应 0.0）、戴七罪之源 " + haloWithSource + "（应 0.5）");
	}

	/** ⑦ 黑心被击碎：18 格内敌人吃 24 点（变种魔法：护甲不减、抗性 IV 压到 20%），且只触发一次 */
	private static void checkBlackHeartShatter(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);

		Zombie zombie = spawnHolyLightTarget(player, 6.0D);
		if (zombie == null) {
			log("黑心碎裂：无法生成测试僵尸");
			return;
		}
		// 三个目标：普通 / 钻石甲（变种魔法应无视护甲）/ 抗性 IV（应压到 20%）
		Zombie plain = spawnHolyLightTarget(player, 6.0D);
		Zombie armored = spawnHolyLightTarget(player, 8.0D);
		Zombie resistant = spawnHolyLightTarget(player, 10.0D);
		if (plain == null || armored == null || resistant == null) {
			log("黑心碎裂：无法生成测试目标");
			return;
		}
		armored.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		armored.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		armored.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		resistant.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		resistant.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		resistant.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		resistant.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		resistant.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));

		// ① 普通目标（真实命中）：池子 2 点、受 3 点 → 黑心扛 2 点、池子归零 → 触发碎裂
		prepareBarePlayer(player);
		// 清掉上一条用例可能留下的挂起记录：它的对账会在这里额外触发一次碎裂，
		// 让"池空后不再触发"这条断言变成假失败（诊断里表现为目标多掉一次 24 点）
		com.summy.reliquary.effect.DamagePools.clear();
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);
		player.invulnerableTime = 0;
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 2.0D);
		float plainBefore = plain.getHealth();
		float casterBefore = player.getHealth();
		player.hurt(player.damageSources().generic(), 3.0F);
		// 碎裂判定在**对账**里（真实游戏里是玩家 tick 末尾）—— 这里手动对账一次模拟 tick 末尾，
		// 这样"第一击碎一次、第二击不再碎"的语义才和线上一致
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		float plainDealt = plainBefore - plain.getHealth();
		double pool = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float casterLoss = casterBefore - player.getHealth();

		// ② 池子已空时再受击 → 不再触发（只该碎一次）
		player.setHealth(player.getMaxHealth());
		player.invulnerableTime = 0;
		float plainBeforeSecond = plain.getHealth();
		player.hurt(player.damageSources().generic(), 1.0F);
		double poolAfterSecondHit = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float plainAfterSecond = plain.getHealth();
		boolean onlyOnce = Math.abs(plainBeforeSecond - plain.getHealth()) < 1.0E-4F
				&& com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player) <= 0.0D;

		// ③ 钻石甲目标：再碎一次（24 点变种魔法 → 无视护甲）
		player.setHealth(player.getMaxHealth());
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
		float armoredBefore = armored.getHealth();
		com.summy.reliquary.effect.DemonPact.consumeBlackHearts(player, 1.0D);
		double armoredDealt = armoredBefore - armored.getHealth();

		// ④ 抗性 IV 目标
		player.setHealth(player.getMaxHealth());
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
		float resistantBefore = resistant.getHealth();
		com.summy.reliquary.effect.DemonPact.consumeBlackHearts(player, 1.0D);
		double resistantDealt = resistantBefore - resistant.getHealth();

		plain.discard();
		armored.discard();
		resistant.discard();

		log(String.format("黑心碎裂（24 点、变种魔法）：普通=%.2f（应 24）、钻石甲=%.2f（应 24 = 无视护甲）、"
						+ "抗性 IV=%.2f（应 4.80 = 24 × 20%%）；红血 −%.2f（应 1.00 = 3 − 2 由黑心承担）、池=%.1f（应 0）；"
						+ "池空后不再触发=%s（应 true；诊断：第二击后池=%.1f、目标生命 %.1f → %.1f）",
				plainDealt, armoredDealt, resistantDealt, casterLoss, pool, onlyOnce, poolAfterSecondHit,
				plainBeforeSecond, plainAfterSecond));
	}

	/** ⑧ 1.6.1 的注册数量、配置默认值与协议版本 */
	private static void checkRelicConfigAndProtocol(ServerPlayer player) {
		int registered = 0;
		for (String id : new String[]{"ceremonial_robes", "satanic_bible", "night_wraith", "brimstone",
				"occult_eye", "six", "heart_shard"}) {
			if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(SummyReliquary.id(id))) {
				registered++;
			}
		}
		boolean config = com.summy.reliquary.config.ReliquaryConfig.robeBlackHearts() == 2.0D
				&& com.summy.reliquary.config.ReliquaryConfig.robeAttackDamage() == 2.0D
				&& com.summy.reliquary.config.ReliquaryConfig.robeDailyDecay() == 3.0D
				&& com.summy.reliquary.config.ReliquaryConfig.satanicBibleBlackHearts() == 1.0D
				&& com.summy.reliquary.config.ReliquaryConfig.satanicRefillSeconds() == 30
				&& com.summy.reliquary.config.ReliquaryConfig.satanicShatterRadius() == 18.0D
				&& com.summy.reliquary.config.ReliquaryConfig.satanicShatterDamage() == 24.0D
				&& com.summy.reliquary.config.ReliquaryConfig.enableSoulHeartHud()
				// 1.6.4：硫磺火/恶魔之焰的默认参数
				&& com.summy.reliquary.config.ReliquaryConfig.enableBrimstone()
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneChargeTicks() == 30
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneBeamLength() == 18
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneBeamRadius() == 2
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneDurationTicks() == 26
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneDamagePerTick() == 6
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneDamageIntervalTicks() == 2
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneCooldownTicks() == 120
				// 1.6.5：玄秘魔眼 / 恐惧的默认参数
				&& com.summy.reliquary.config.ReliquaryConfig.enableOccultEye()
				&& com.summy.reliquary.config.ReliquaryConfig.fearRadius() == 24.0D
				&& com.summy.reliquary.config.ReliquaryConfig.fearTicks() == 120
				&& com.summy.reliquary.config.ReliquaryConfig.fearAffectsPlayers()
				&& com.summy.reliquary.config.ReliquaryConfig.fearGlow()
				&& com.summy.reliquary.config.ReliquaryConfig.fearGlowAffectsPlayers()
				&& com.summy.reliquary.config.ReliquaryConfig.fearDamageMultiplier() == 1.3D
				// 1.6.6：1.6.5 误用了「失明」，改用原版「黑暗」（配置键也从 fear_blindness 改名 fear_darkness）
				&& com.summy.reliquary.config.ReliquaryConfig.fearDarkness()
				&& com.summy.reliquary.config.ReliquaryConfig.fearMiningFatigueAmplifier() == 2
				&& com.summy.reliquary.config.ReliquaryConfig.fearSlownessAmplifier() == 5
				&& com.summy.reliquary.config.ReliquaryConfig.fearWeaknessAmplifier() == 9;
		// 注意：log_damage_pools 是"读配置"而不是"读代码默认值" —— 旧的 run/config 里可能还是 false，
		// 所以这里只把它打出来、不作为断言（否则在旧配置文件上会假失败）
		boolean poolLog = com.summy.reliquary.config.ReliquaryConfig.logDamagePools();
		boolean heartMath = com.summy.reliquary.client.SoulHeartOverlay.heartCount(4.0D) == 2
				&& !com.summy.reliquary.client.SoulHeartOverlay.hasHalf(4.0D)
				&& com.summy.reliquary.client.SoulHeartOverlay.heartCount(3.0D) == 2
				&& com.summy.reliquary.client.SoulHeartOverlay.hasHalf(3.0D);
		log("1.6.1 注册与配置：7 件关键物品齐全=" + (registered == 7) + "（应 true，实际 " + registered
				+ "）、配置默认值=" + config + "（应 true）、魂心计数 4→2 心 / 3→1 满 1 半=" + heartMath
				+ "（应 true）、当前 log_damage_pools=" + poolLog
				+ "（旧配置文件里可能是 false；要排查池子请手动改成 true）");
		// 收尾：把本段测试留下的状态清掉（魂印 / 背饰 / 恶魔标记 / 七罪）
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
	}

	/** 把七罪的位掩码直接写进存档（自检搭场景用；已赎罪优先） */
	private static void setSinMasksForTest(ServerPlayer player, int sins, int redeemed) {
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			int bit = 1 << sin.ordinal();
			com.summy.reliquary.sin.SinManager.SinState state;
			if ((redeemed & bit) != 0) {
				state = com.summy.reliquary.sin.SinManager.SinState.REDEEMED;
			} else if ((sins & bit) != 0) {
				state = com.summy.reliquary.sin.SinManager.SinState.ACTIVATED;
			} else {
				state = com.summy.reliquary.sin.SinManager.SinState.UNACTIVATED;
			}
			com.summy.reliquary.sin.SinManager.setState(player, sin, state);
		}
	}

	/** 1.6.1 客户端：贴图 / 模型 / 语言键 / 撒旦圣经的配色 */
	private static void checkSatanicBibleClient() {
		var resources = Minecraft.getInstance().getResourceManager();
		String[] textures = {
				"textures/item/brimstone.png", "textures/item/occult_eye.png", "textures/item/night_wraith.png",
				"textures/item/satanic_bible.png", "textures/item/six.png", "textures/item/heart_shard.png",
				"textures/slot/demon_pact.png", "textures/gui/demon_black_heart/full.png",
				"textures/gui/demon_black_heart/half.png", "textures/gui/soul_heart/full.png",
				"textures/gui/soul_heart/half.png"};
		int missing = 0;
		for (String path : textures) {
			if (resources.getResource(new net.minecraft.resources.ResourceLocation(
					SummyReliquary.NAMESPACE, path)).isEmpty()) {
				missing++;
			}
		}
		String[] models = {"six", "heart_shard", "brimstone", "occult_eye", "night_wraith", "satanic_bible"};
		int missingModels = 0;
		for (String id : models) {
			if (resources.getResource(new net.minecraft.resources.ResourceLocation(
					SummyReliquary.NAMESPACE, "models/item/" + id + ".json")).isEmpty()) {
				missingModels++;
			}
		}
		boolean texts = translated("item.summy-reliquary.satanic_bible")
				&& translated("item.summy-reliquary.satanic_bible.tagline.1")
				&& translated("item.summy-reliquary.satanic_bible.shift.4")
				&& translated("item.summy-reliquary.ceremonial_robes.tagline")
				&& translated("item.summy-reliquary.ceremonial_robes.shift.3")
				&& translated("item.summy-reliquary.demon.required")
				&& translated("death.attack.summy-reliquary.pact_shatter")
				&& translated("death.attack.summy-reliquary.pact_shatter.player")
				&& translated("item.summy-reliquary.brimstone")
				&& translated("item.summy-reliquary.occult_eye");
		// 撒旦圣经的风味三行必须是斜体；颜色自 1.6.9 起统一成恶魔线风味色 #C03030
		// （这条断言以前写的是 1.6.9 之前的 #8B0000，属于上色改造后遗留的陈旧期望，1.7.1 修正）
		List<net.minecraft.network.chat.Component> lines =
				new ItemStack(SummyReliquary.SATANIC_BIBLE.get()).getTooltipLines(
						Minecraft.getInstance().player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		int redLines = 0;
		for (net.minecraft.network.chat.Component line : lines) {
			if ("#C03030".equals(colorOf(line)) && line.getStyle().isItalic()) {
				redLines++;
			}
		}
		log("1.6.1 素材：贴图缺失=" + missing + "（应 0）、模型缺失=" + missingModels + "（应 0）、关键语言键="
				+ texts + "（应 true）；撒旦圣经恶魔线斜体风味行=" + redLines + "（应 3，1.6.9 起为 #C03030）");
	}

	/** 1.6.1 客户端：仪式法袍的提示与恶魔门槛一致（锁定 → 只多一行提示；解锁 → 显示 Shift 行） */
	private static void checkRobesTooltipClient() {
		net.minecraft.client.player.LocalPlayer local = Minecraft.getInstance().player;
		List<net.minecraft.network.chat.Component> lines = new ItemStack(
				SummyReliquary.CEREMONIAL_ROBES.get()).getTooltipLines(
						local, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		boolean hasTagline = lines.stream().anyMatch(line -> line.getString()
				.contains(net.minecraft.network.chat.Component
						.translatable("item.summy-reliquary.ceremonial_robes.tagline").getString()));
		boolean locked = com.summy.reliquary.item.ReliquaryTooltips.demonLocked();
		String hint = net.minecraft.network.chat.Component
				.translatable("item.summy-reliquary.demon.required").getString();
		boolean hasLockLine = lines.stream().anyMatch(line -> line.getString().contains(hint));
		boolean consistent = locked == hasLockLine;
		log("仪式法袍提示：含风味行=" + hasTagline + "（应 true）、当前恶魔门槛锁定=" + locked
				+ "、含「" + hint + "」行=" + hasLockLine + "（应与锁定一致=" + consistent
				+ "）、总行数=" + lines.size());
	}

	// ==================== 1.6.2：无敌帧 / 魂心池 / 复仇之魂 / 咒印 / 栏位阶段 / 「6」掉落 ====================

	/**
	 * 自检辅助（1.6.2）：把玩家切到「天使线」并立刻校正栏位格数。
	 *
	 * <p>栏位阶段化之后，七罪阶段没有灵台 / 启示之座 / 加护栏位，旧用例里那些"直接塞进去"的
	 * 灵台 / 加护 / 启示饰品会塞不进去，所以这些用例先调用本方法把阶段设成天使线。
	 */
	private static void ensureAngelLine(ServerPlayer player) {
		if (!com.summy.reliquary.effect.PlayerFlags.hasAngel(player)) {
			com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
			com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		}
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
	}

	/** 1.6.2：两个 HUD 的可见性规则（纯函数；用玩家的 gameMode 断言，服务端也能跑） */
	private static void checkHudRules(ServerPlayer player) {
		var previous = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		boolean visibleInSurvival = com.summy.reliquary.client.DemonBlackHeartOverlay.shouldRender(player, false)
				&& com.summy.reliquary.client.SoulHeartOverlay.shouldRender(player, false);
		player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		boolean hiddenInCreative = !com.summy.reliquary.client.DemonBlackHeartOverlay.shouldRender(player, false)
				&& !com.summy.reliquary.client.SoulHeartOverlay.shouldRender(player, false);
		player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
		boolean hiddenInSpectator = !com.summy.reliquary.client.DemonBlackHeartOverlay.shouldRender(player, false)
				&& !com.summy.reliquary.client.SoulHeartOverlay.shouldRender(player, false);
		boolean hiddenWithF1 = !com.summy.reliquary.client.SoulHeartOverlay.shouldRender(player, true);
		player.setGameMode(previous);
		log("HUD 可见性：生存可见=" + visibleInSurvival + "（应 true）、创造隐藏=" + hiddenInCreative
				+ "（应 true）、旁观隐藏=" + hiddenInSpectator + "（应 true）、F1 隐藏=" + hiddenWithF1
				+ "（应 true）");
	}

	/**
	 * 无敌帧（1.6.3 返工）：**沿用原版判定、只改窗口长度**。
	 *
	 * <p>原版字段值是"窗口 + 10"（判定 {@code invulnerableTime > 10}、每 tick 减 1），
	 * 所以一般受伤写 5 + 10、帧伤写 10 + 10。这里的断言：
	 * ① 一般伤害字段 = 15、② 帧伤字段 = 20（= 原版）、③ 逐 tick 模拟落地间隔分别是 5 / 10 tick、
	 * ④ ER 两段（主击 → 追加段前自己清冷却）都落地、⑤ 窗口内更大的一击只结算差值、
	 * ⑥ 帧伤集合识别 + 自定义 id 配置项存在。
	 */
	private static void checkIframeWindows(ServerPlayer player) {
		var previousGameMode = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		player.setInvulnerable(false);
		player.setAbsorptionAmount(0.0F);
		// 清掉装备与套装：护甲 / 「肉体+思想+灵魂」套装的 -20% 受伤会干扰"落地数值"的断言
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			player.setItemSlot(slot, ItemStack.EMPTY);
		}
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (int index = 0; index < 3; index++) {
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, index, ItemStack.EMPTY);
			}
			handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, 0, ItemStack.EMPTY);
		});
		// 也清掉药水效果：前面用例留下的抗性提升会再减伤 20%，干扰数值断言
		player.removeAllEffects();
		// 隔离其它减伤池：本用例只看真血
		com.summy.reliquary.effect.SoulShield.setPoints(player, 0.0D);
		com.summy.reliquary.effect.SoulShield.setRefreshTimer(player, 999);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		player.setHealth(player.getMaxHealth());
		com.summy.reliquary.effect.CombatTuning.clear();

		int genericWindow = com.summy.reliquary.config.ReliquaryConfig.invulnerabilityTicks();
		int frameWindow = com.summy.reliquary.config.ReliquaryConfig.invulnerabilityTicksFrameDamage();

		// ① 一般伤害：字段应被改写成 窗口 + 10
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 1.0F);
		int genericField = player.invulnerableTime;

		// ② 帧伤（火焰）：默认窗口 10 → 字段 20（= 原版，不再"一帧一跳"）
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().inFire(), 1.0F);
		int frameField = player.invulnerableTime;
		player.clearFire();

		// ③ 逐 tick 模拟持续伤害：一般是 5 tick 一次、帧伤是 10 tick 一次
		int genericLandings = simulateDamageTicks(player, player.damageSources().generic(), 30, 1.0F);
		int frameLandings = simulateDamageTicks(player, player.damageSources().inFire(), 30, 1.0F);
		player.clearFire();

		// ④ ER 的魔剑 / 出其不意：追加段之前 ER 自己会把冷却清零 → 两段都要落地
		player.setHealth(player.getMaxHealth());
		player.invulnerableTime = 0;
		float beforeMain = player.getHealth();
		player.hurt(player.damageSources().generic(), 2.0F);
		float mainDealt = beforeMain - player.getHealth();
		player.invulnerableTime = 0;
		float beforeExtra = player.getHealth();
		player.hurt(player.damageSources().generic(), 3.0F);
		float extraDealt = beforeExtra - player.getHealth();

		// ⑤ 窗口内更大的一击：原版只结算差值（不刷新窗口）
		player.setHealth(player.getMaxHealth());
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 2.0F);
		float beforeDiff = player.getHealth();
		player.hurt(player.damageSources().generic(), 5.0F);
		float diffDealt = beforeDiff - player.getHealth();

		// ⑥ 帧伤集合：原版四标签 + 仙人掌 / 甜浆果丛都要识别，普通伤害不算
		var sources = player.damageSources();
		boolean frameSet = com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.inFire())
				&& com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.drown())
				&& com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.freeze())
				&& com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.fall())
				&& com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.cactus())
				&& com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.sweetBerryBush())
				&& !com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.generic())
				&& !com.summy.reliquary.effect.CombatTuning.isFrameDamage(sources.magic());
		boolean extraIdsEmpty = com.summy.reliquary.config.ReliquaryConfig.frameDamageIds().isEmpty();

		log(String.format("无敌帧（1.6.3 原版逻辑 + 两档窗口）：一般字段=%d（应 %d = %d+10）、帧伤字段=%d"
						+ "（应 %d = %d+10）、30 tick 内落地 一般=%d 次（应 6 = 每 5 tick）、帧伤=%d 次（应 3 = 每 10 tick）、"
						+ "ER 主击=%.1f / 追加段=%.1f（都应落地：2.0 / 3.0）、窗口内更大一击只结算差值=%.1f（应 3.0）、"
						+ "帧伤集合识别=%s（应 true）、frame_damage_ids 默认空=%s（应 true）",
				genericField, genericWindow + 10, genericWindow, frameField, frameWindow + 10, frameWindow,
				genericLandings, frameLandings, mainDealt, extraDealt, diffDealt, frameSet, extraIdsEmpty));

		player.invulnerableTime = 0;
		player.setHealth(player.getMaxHealth());
		player.clearFire();
		player.setGameMode(previousGameMode);
	}

	/**
	 * 逐 tick 模拟持续伤害：每 tick 先走一次 {@code baseTick} 的递减，再尝试打一次，返回落地次数。
	 *
	 * <p>用"落地间隔"直接反映有效窗口：原版字段 20 → 每 10 tick 落地一次；我们的 5 tick 档 → 每 5 tick 一次。
	 */
	private static int simulateDamageTicks(ServerPlayer player,
			net.minecraft.world.damagesource.DamageSource source, int ticks, float amount) {
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);
		player.invulnerableTime = 0;
		int landings = 0;
		for (int index = 0; index < ticks; index++) {
			if (player.invulnerableTime > 0) {
				player.invulnerableTime--;
			}
			float before = player.getHealth();
			player.hurt(source, amount);
			if (player.getHealth() < before) {
				landings++;
			}
		}
		player.invulnerableTime = 0;
		player.clearFire();
		player.setHealth(player.getMaxHealth());
		return landings;
	}

	/** 复仇之魂：每秒对半径内的敌人造成固定狱火伤害（无视护甲） */
	private static void checkHellfire(ServerPlayer player) {
		resetDemonPactState(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT.bit());
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.VENGEFUL_SPIRIT.get());
		player.getInventory().clearContent();

		Zombie plain = spawnHolyLightTarget(player, 3.0D);
		Zombie armored = spawnHolyLightTarget(player, 3.0D);
		if (plain == null || armored == null) {
			log("复仇之魂：无法生成测试僵尸");
			return;
		}
		armored.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		armored.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		armored.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		float plainBefore = plain.getHealth();
		float armoredBefore = armored.getHealth();
		com.summy.reliquary.effect.VengefulSpirit.tick(player);
		double plainDealt = plainBefore - plain.getHealth();
		double armoredDealt = armoredBefore - armored.getHealth();
		plain.discard();
		armored.discard();
		log(String.format("复仇之魂：每秒狱火=%.2f（应 %.2f）、钻石甲目标=%.2f（应同样 %.2f = 无视护甲）、"
						+ "口径=变种魔法（保护与抗性仍生效）",
				plainDealt, com.summy.reliquary.config.ReliquaryConfig.vengefulHellfireDamage(), armoredDealt,
				com.summy.reliquary.config.ReliquaryConfig.vengefulHellfireDamage()));
	}

	/** 咒印：继承灵台套装属性、魂心转黑心、邪恶不再衰减、碎裂伤害 40 */
	private static void checkMarkEffects(ServerPlayer player) {
		resetDemonPactState(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.THE_MARK.bit());
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		player.getInventory().clearContent();
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);

		boolean setEffects = com.summy.reliquary.effect.SpiritAltarSet.hasSetEffects(player);
		boolean realFullSet = com.summy.reliquary.effect.SpiritAltarSet.isFullSet(player);
		double maxHealth = player.getAttributeValue(Attributes.MAX_HEALTH);
		double soulCapacity = com.summy.reliquary.effect.SoulShield.capacityFor(player);
		double blackMax = com.summy.reliquary.effect.DemonPact.blackHeartMaxPoints(player);
		double shatterDamage = com.summy.reliquary.effect.DemonPact.shatterDamage(player);

		long day = player.level().getDayTime() / 24000L;
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 100.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, day - 1L);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double decayed = 100.0D - com.summy.reliquary.effect.PlayerFlags.evil(player);

		// 诊断：把三件套逐件打出来，便于确认 isFullSet 为何是 true
		String altarSlots = "灵台格数="
				+ com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.SPIRIT_ALTAR);
		log(String.format("咒印：套装效果生效=%s（应 true）、真的三件套=%s（应 false，所以不会触发三位一体/发星；%s）、"
						+ "最大生命=%.0f（应 +10）、魂心池容量=%.1f（应 0 = 魂心已转黑心）、黑心上限=%.1f（应 10 = 基础 4 + 咒印 6）、"
						+ "碎裂伤害=%.1f（应 40）、每日衰减=%.1f（应 0）",
				setEffects, realFullSet, altarSlots, maxHealth, soulCapacity, blackMax, shatterDamage, decayed));
	}

	/** 栏位阶段化：七罪 0 / 天使 3+1+2 / 恶魔 1+1+2，且缩格会把物品退回背包 */
	private static void checkSlotPhases(ServerPlayer player) {
		// 天使线
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		int angelAltar = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.SPIRIT_ALTAR);
		int angelRevelation = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.REVELATION);
		int angelBlessing = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.BLESSING);
		// 在加护 #1 放一件圣光，稍后切到七罪阶段时应该被退回背包
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());

		// 恶魔线
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		int demonAltar = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.SPIRIT_ALTAR);
		int demonRevelation = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.REVELATION);
		int demonBlessing = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.BLESSING);

		// 七罪阶段
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		int sinsAltar = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.SPIRIT_ALTAR);
		int sinsRevelation = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.REVELATION);
		int sinsBlessing = com.summy.reliquary.effect.SlotSizing.slotsOf(player, ReliquarySlots.BLESSING);
		int returned = player.getInventory().countItem(SummyReliquary.SACRED_HEART.get())
				+ player.getInventory().countItem(SummyReliquary.VENGEFUL_SPIRIT.get());

		log("栏位阶段：天使=" + angelAltar + "/" + angelRevelation + "/" + angelBlessing + "（应 3/1/2）、恶魔="
				+ demonAltar + "/" + demonRevelation + "/" + demonBlessing + "（应 1/1/2）、七罪=" + sinsAltar + "/"
				+ sinsRevelation + "/" + sinsBlessing + "（应 0/0/0）、缩格退回背包件数=" + returned + "（应 ≥1）");
		player.getInventory().clearContent();
	}

	/** 「6」的掉落：佩戴契约 + 已解锁咒印时，三类 Boss 各掉一次，忏悔重置后可再掉 */
	private static void checkSixDrops(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.THE_MARK.bit());

		var dragon = net.minecraft.world.entity.EntityType.ENDER_DRAGON.create(player.serverLevel());
		var wither = net.minecraft.world.entity.EntityType.WITHER.create(player.serverLevel());
		var warden = net.minecraft.world.entity.EntityType.WARDEN.create(player.serverLevel());
		if (dragon == null || wither == null || warden == null) {
			log("「6」掉落：无法生成测试 Boss");
			return;
		}
		com.summy.reliquary.effect.DemonPact.onDeath(dragon, player);
		com.summy.reliquary.effect.DemonPact.onDeath(wither, player);
		com.summy.reliquary.effect.DemonPact.onDeath(warden, player);
		int afterThree = player.getInventory().countItem(SummyReliquary.SIX.get());

		var secondDragon = net.minecraft.world.entity.EntityType.ENDER_DRAGON.create(player.serverLevel());
		if (secondDragon != null) {
			com.summy.reliquary.effect.DemonPact.onDeath(secondDragon, player);
			secondDragon.discard();
		}
		int afterRepeat = player.getInventory().countItem(SummyReliquary.SIX.get());

		com.summy.reliquary.effect.PlayerFlags.clearSixDrops(player);
		var thirdDragon = net.minecraft.world.entity.EntityType.ENDER_DRAGON.create(player.serverLevel());
		if (thirdDragon != null) {
			com.summy.reliquary.effect.DemonPact.onDeath(thirdDragon, player);
			thirdDragon.discard();
		}
		int afterReset = player.getInventory().countItem(SummyReliquary.SIX.get());
		dragon.discard();
		wither.discard();
		warden.discard();
		log("「6」掉落：三个 Boss 后=" + afterThree + "（应 3）、重复击杀同类=" + afterRepeat
				+ "（应仍 3）、清掉记录后再杀=" + afterReset + "（应 4 = 忏悔重置后可以再拿一次）");
	}

	/** 咒印时不提供魂心 + 协议 9 + 1.6.2 配置默认值 */
	private static void checkSoulOrderAndMarkOnly(ServerPlayer player) {
		resetDemonPactState(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_SOUL.get());
		double withSoul = com.summy.reliquary.effect.SoulShield.capacityFor(player);
		// 换成咒印：需要恶魔标记（并把"曾签约"置位，免得下一秒被自愈换回天使标记）
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		double withMark = com.summy.reliquary.effect.SoulShield.capacityFor(player);
		boolean config = com.summy.reliquary.config.ReliquaryConfig.vengefulHellfireDamage() == 6.0D
				&& com.summy.reliquary.config.ReliquaryConfig.vengefulHellfireRadius() == 3.0D
				&& com.summy.reliquary.config.ReliquaryConfig.markShatterDamage() == 40.0D
				&& com.summy.reliquary.config.ReliquaryConfig.markBlackHearts() == 3.0D
				&& com.summy.reliquary.config.ReliquaryConfig.markStopsEvilDecay()
				&& com.summy.reliquary.config.ReliquaryConfig.soulShatterRadius() == 7.0D
				&& com.summy.reliquary.config.ReliquaryConfig.soulShatterInvulnerableSeconds() == 5.0D;
		log("魂心 / 咒印口径：戴灵魂的池容量=" + withSoul + "（应 6）、换成咒印后=" + withMark
				+ "（应 0 = 魂心转黑心）；协议=" + com.summy.reliquary.net.ReliquaryNetworking.protocolVersion()
				+ "（应 12 = 1.7.2 新增创世纪动画包后升号）、1.6.2 配置默认值=" + config + "（应 true）");
		resetDemonPactState(player);
	}

	// ==================== 1.6.7：渊领主 / 狱火 / 恶魔线火焰免疫 / 圣心·神性免疫 / 黑心不超上限 ====================

	/** 深渊领主的「狱火」：施加、叠层、封顶、排除定值真伤与狱火自身、护甲与每秒伤害 */
	private static void checkAbyssLordHellfire(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearBlessingSlots(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 900.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		prepareBarePlayer(player);

		Zombie target = spawnHolyLightTarget(player, 5.0D);
		if (target == null) {
			log("狱火：无法生成测试僵尸");
			return;
		}
		target.setNoAi(true);
		target.setHealth(target.getMaxHealth());
		target.removeAllEffects();

		// ① 没戴深渊领主：普通攻击不施加
		target.invulnerableTime = 0;
		player.attack(target);
		boolean withoutLord = !target.hasEffect(SummyReliquary.HELLFIRE.get());
		float baseArmor = (float) target.getAttributeValue(Attributes.ARMOR);

		// ② 戴上深渊领主：普通伤害 → 1 级、持续 6 秒
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.ABYSS_LORD.get());
		target.setHealth(target.getMaxHealth());
		target.invulnerableTime = 0;
		player.attack(target);
		var first = target.getEffect(SummyReliquary.HELLFIRE.get());
		boolean firstLevel = first != null && first.getAmplifier() == 0;
		int firstDuration = first == null ? -1 : first.getDuration();
		float armorLevel1 = (float) target.getAttributeValue(Attributes.ARMOR);

		// ③ 再打一次 → 2 级；连打到上限 → 封顶 10 级
		for (int index = 0; index < 12; index++) {
			target.setHealth(target.getMaxHealth());
			target.invulnerableTime = 0;
			player.attack(target);
		}
		var capped = target.getEffect(SummyReliquary.HELLFIRE.get());
		int cappedLevel = capped == null ? -1 : capped.getAmplifier() + 1;
		float armorLevel10 = (float) target.getAttributeValue(Attributes.ARMOR);

		// ④ 每秒伤害 = 等级 × 1（10 级 = 10 点），且穿钻石甲不减免（hellfire 无视护甲）
		target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		target.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		float before = target.getHealth();
		SummyReliquary.HELLFIRE.get().applyEffectTick(target, cappedLevel - 1);
		float tickDamage = before - target.getHealth();

		// ⑤ 定值真伤（启示之光）不施加狱火
		target.removeAllEffects();
		var registry = player.serverLevel().registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
		var exactSource = new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(
				net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
						SummyReliquary.id("revelation_light"))));
		var exact = new net.minecraftforge.event.entity.living.LivingHurtEvent(target, exactSource, 5.0F);
		MinecraftForge.EVENT_BUS.post(exact);
		boolean exactSkipped = !target.hasEffect(SummyReliquary.HELLFIRE.get());

		// ⑥ 狱火自身（每秒伤害）也不会再叠层
		target.removeAllEffects();
		target.hurt(new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(
				net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
						SummyReliquary.id("hellfire")))), 1.0F);
		boolean selfSkipped = !target.hasEffect(SummyReliquary.HELLFIRE.get());

		target.discard();
		log("狱火：未戴深渊领主不施加=" + withoutLord + "（应 true）；首击 1 级=" + firstLevel
				+ "（应 true）、持续时间=" + firstDuration + "（应 "
				+ com.summy.reliquary.config.ReliquaryConfig.hellfireTicks()
				+ "）；连打后等级=" + cappedLevel + "（应 10 = 封顶）、护甲 " + baseArmor + " → 1 级 "
				+ armorLevel1 + "（应 -10% → " + (baseArmor * 0.9F) + "）→ 10 级 " + armorLevel10
				+ "（应 0）；10 级每秒伤害（穿钻石甲）=" + tickDamage + "（应 10）；定值真伤不施加=" + exactSkipped
				+ "（应 true）、狱火自身不叠层=" + selfSkipped + "（应 true）");
		clearBlessingSlots(player);
		resetDemonPactState(player);
	}

	/** 恶魔线补强：当前持恶魔标记 → 免疫火焰四类伤害、且不着火；换回天使标记后失效 */
	private static void checkDemonFireImmunity(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		var level = player.serverLevel();
		var registry = level.registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
		java.util.function.Function<net.minecraft.resources.ResourceKey<
				net.minecraft.world.damagesource.DamageType>, net.minecraft.world.damagesource.DamageSource> sourceOf =
				key -> new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(key));

		// ① 恶魔线：四类火焰伤害全免疫，且着火会被清掉
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		player.setHealth(player.getMaxHealth());
		player.setRemainingFireTicks(200);
		com.summy.reliquary.ReliquaryEvents.clearDemonFire(player);
		boolean cleared = com.summy.reliquary.ReliquaryEvents.isDemonFireImmune(player) && !player.isOnFire();
		boolean inFire = blockedFire(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.IN_FIRE);
		boolean onFire = blockedFire(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.ON_FIRE);
		boolean lava = blockedFire(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.LAVA);
		boolean hotFloor = blockedFire(player, sourceOf, net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR);

		// ①b 1.7.10 收尾：不硬编码四个类型，直接遍历 #is_fire 标签全集（营火一类也会自动覆盖）
		java.util.List<net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType>>
				fireTagKeys = new java.util.ArrayList<>();
		registry.holders().forEach(holder -> {
			if (holder.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) && holder.unwrapKey().isPresent()) {
				fireTagKeys.add(holder.unwrapKey().get());
			}
		});
		boolean fireTagAllBlocked = true;
		for (var key : fireTagKeys) {
			if (!blockedFire(player, sourceOf, key)) {
				fireTagAllBlocked = false;
			}
		}
		// 原版 1.20.1 的 #is_fire 至少含 in_fire / on_fire / lava / hot_floor 四项
		boolean fireTagOk = fireTagAllBlocked && fireTagKeys.size() >= 4;

		// ①c 1.7.10 收尾：端到端展开 100 tick —— 每 tick 重新点火 + 岩浆 / 热地板各打一次 + 清火，
		// 断言"血量全程不掉"且"结束时身上没有火"（等于把真实 tick 循环跑一遍，不依赖手动调用顺序）
		player.setHealth(player.getMaxHealth());
		boolean loopIntact = true;
		boolean loopNoFire = true;
		for (int i = 0; i < 100; i++) {
			player.setRemainingFireTicks(200);
			player.invulnerableTime = 0;
			player.hurt(sourceOf.apply(net.minecraft.world.damagesource.DamageTypes.LAVA), 4.0F);
			player.invulnerableTime = 0;
			player.hurt(sourceOf.apply(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR), 1.0F);
			com.summy.reliquary.ReliquaryEvents.clearDemonFire(player);
			if (player.getHealth() < player.getMaxHealth()) {
				loopIntact = false;
			}
			if (player.isOnFire()) {
				loopNoFire = false;
			}
		}
		boolean loopOk = loopIntact && loopNoFire;

		// ② 换回天使标记：不再免疫
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		player.setHealth(player.getMaxHealth());
		player.setRemainingFireTicks(0);
		// 先清掉吸收与两个池子，否则这一击会被护盾/魂心扛下来、红血不动（1.6.7 起池子是生效的）
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		player.invulnerableTime = 0;
		player.hurt(sourceOf.apply(net.minecraft.world.damagesource.DamageTypes.IN_FIRE), 4.0F);
		boolean afterAngel = player.getHealth() < player.getMaxHealth();
		resetDemonPactState(player);
		log("恶魔线火焰免疫：清火+免疫生效=" + cleared + "（应 true）；火焰=" + inFire + "、着火=" + onFire
				+ "、岩浆=" + lava + "、岩浆块=" + hotFloor + "（都应 true = 伤害被拦下且血量不掉）；"
				+ "1.7.10 收尾：#is_fire 标签全集=" + fireTagKeys.size() + " 项（应 ≥ 4）、逐项都被拦="
				+ fireTagAllBlocked + "（应 true）→ " + fireTagOk + "（应 true）；连续 100 tick"
				+ "（每 tick 点火 + 岩浆 + 热地板 + 清火）血量不掉=" + loopIntact + "、结束时不着火="
				+ loopNoFire + " → " + loopOk + "（应 true）；"
				+ "换回天使标记后恢复正常受伤=" + afterAngel + "（应 true）");
	}

	/** 圣心 / 神性：免疫黑暗与恐惧（拦新增 + 每秒清理），且不影响发光与增伤 */
	private static void checkDivineImmunity(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		boolean darkWithout = applies(player, net.minecraft.world.effect.MobEffects.DARKNESS);
		boolean fearWithout = applies(player, SummyReliquary.FEAR.get());

		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		boolean darkWithHeart = !applies(player, net.minecraft.world.effect.MobEffects.DARKNESS);
		boolean fearWithHeart = !applies(player, SummyReliquary.FEAR.get());
		// "先中招、后戴上"的清理路径：先在没有免疫物时中招（此时能加上），再戴上圣心跑一次每秒清理
		unequip(player, ReliquarySlots.BLESSING);
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DARKNESS, 200, 0, false, false, false));
		boolean hadDarkness = player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		com.summy.reliquary.effect.DivineImmunity.tickPlayer(player);
		boolean cleaned = !player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
		unequip(player, ReliquarySlots.BLESSING);

		// 神性同样免疫
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		boolean darkWithGodhead = !applies(player, net.minecraft.world.effect.MobEffects.DARKNESS);
		boolean fearWithGodhead = !applies(player, SummyReliquary.FEAR.get());
		unequip(player, ReliquarySlots.REVELATION);
		log("圣心 / 神性免疫：无免疫物时黑暗可施加=" + darkWithout + "、恐惧可施加=" + fearWithout + "（都应 true）；"
				+ "戴圣心后黑暗被拦=" + darkWithHeart + "、恐惧被拦=" + fearWithHeart + "（应 true）；"
				+ "先中黑暗（成功=" + hadDarkness + "）再跑每秒清理 → 黑暗消失=" + cleaned + "（应 true）；"
				+ "戴神性后黑暗被拦=" + darkWithGodhead + "、恐惧被拦=" + fearWithGodhead + "（应 true）");
		clearBlessingSlots(player);
		resetDemonPactState(player);
	}

	/** 黑心诊断：黑心池日志字段 + 任何写入/退还都不超过生效上限 */
	private static void checkBlackHeartNotOverCap(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		double max = com.summy.reliquary.effect.DemonPact.blackHeartMaxPoints(player);
		com.summy.reliquary.effect.DemonPact.addBlackHearts(player, 999.0D);
		double afterAdd = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		com.summy.reliquary.effect.DemonPact.refundBlackHearts(player, 999.0D);
		double afterRefund = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		String summary = com.summy.reliquary.effect.AttributeManager.blackHeartSummary(player);
		boolean hasField = summary.startsWith("黑心池=") && summary.contains("上限");
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		// 黑心是否生效只看"有没有戴契约"（与恶魔标记无关），所以这里卸下契约再读日志口径
		unequip(player, ReliquarySlots.DEMON_PACT);
		String idle = com.summy.reliquary.effect.AttributeManager.blackHeartSummary(player);
		boolean idleZero = idle.equals("黑心池=0.0（上限 0.0）");
		resetDemonPactState(player);
		log("黑心诊断：上限=" + max + "、加满后=" + afterAdd + "（应 ≤ 上限）、退还后=" + afterRefund
				+ "（应 ≤ 上限）；日志含黑心池字段=" + hasField + "（应 true）、未戴契约时显示零口径=" + idleZero
				+ "（应 true）");
	}

	/** 该玩家身上能否成功施加某个效果（走真实的 addEffect → MobEffectEvent.Applicable 路径） */
	private static boolean applies(ServerPlayer player, net.minecraft.world.effect.MobEffect effect) {
		player.removeEffect(effect);
		return player.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect, 100, 0, false, false, false));
	}

	// ==================== 1.6.8：深渊领主文案 / 亚巴顿 / 飞行免摔 / 邪恶满值 ====================

	/** 按物品 id 取物品（自检里用来逐件搭场景） */
	private static Item itemById(String id) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(SummyReliquary.id(id));
	}

	/** 深渊领主（1.6.8）：Shift 四行新文案（数值读配置） */
	private static void checkAbyssLordTexts() {
		String[] keys = {"item.summy-reliquary.abyss_lord.shift.1", "item.summy-reliquary.abyss_lord.shift.2",
				"item.summy-reliquary.abyss_lord.shift.3", "item.summy-reliquary.abyss_lord.shift.4"};
		StringBuilder detail = new StringBuilder();
		boolean all = true;
		for (String key : keys) {
			String text = Component.translatable(key, 10, 1).getString();
			boolean present = !text.contains("abyss_lord") && !text.isEmpty();
			all &= present;
			detail.append('「').append(text).append('」');
		}
		log("深渊领主文案（1.6.8 四行）：" + detail + " → 全部可用=" + all + "（应 true）");
	}

	/** 亚巴顿（1.6.8 转正）：加护……不，是启示之座栏、恶魔标记 + 邪恶 1000、描述行红字 */
	private static void checkAbaddon(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		boolean formal = SummyReliquary.ABADDON.isPresent()
				&& SummyReliquary.ABADDON.get() instanceof com.summy.reliquary.item.AbaddonItem;
		com.summy.reliquary.effect.EvilUnlock unlock =
				com.summy.reliquary.effect.EvilUnlock.byItemId("abaddon");
		boolean threshold = unlock == com.summy.reliquary.effect.EvilUnlock.ABADDON
				&& unlock.threshold() == 1000 && unlock.lineCount() == 3;

		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		boolean noDemon = !canEquipIn(player, SummyReliquary.ABADDON.get(), ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 900.0D);
		boolean notEnough = !canEquipIn(player, SummyReliquary.ABADDON.get(), ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		boolean allowed = canEquipIn(player, SummyReliquary.ABADDON.get(), ReliquarySlots.REVELATION);
		boolean wrongSlot = !canEquipIn(player, SummyReliquary.ABADDON.get(), ReliquarySlots.BLESSING);

		String tagline = Component.translatable("item.summy-reliquary.abaddon.tagline.1").getString();
		boolean taglineOk = !tagline.contains("abaddon");
		boolean red = com.summy.reliquary.item.AbaddonItem.TAGLINE_COLOR == 0xC03030;
		StringBuilder shift = new StringBuilder();
		boolean shiftOk = true;
		for (int index = 1; index <= 5; index++) {
			String text = Component.translatable("item.summy-reliquary.abaddon.shift." + index).getString();
			boolean ok = text.contains("|");
			shiftOk &= ok;
			shift.append('「').append(text).append('」');
		}
		log("亚巴顿：已转正（不再是占位）=" + formal + "（应 true）、门槛登记 1000=" + threshold
				+ "（应 true）；无标记被拦=" + noDemon + "、邪恶 900 被拦=" + notEnough + "、邪恶 1000 放行=" + allowed
				+ "、装错栏位被拦=" + wrongSlot + "（都应 true）；描述行「" + tagline + "」红字=" + red
				+ "（应 true）、五行两段文案=" + shiftOk + "（应 true）" + shift);
		resetDemonPactState(player);
	}

	/** 亚巴顿：恶魔之焰分流（硫磺火 6/18/2；亚巴顿 9/32/3） */
	private static void checkAbaddonFlame(ServerPlayer player) {
		resetDemonPactState(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);

		equip(player, ReliquarySlots.REVELATION, SummyReliquary.BRIMSTONE.get());
		var kindBrimstone = com.summy.reliquary.effect.RevelationBeam.kindFor(player);
		int brimstoneLength = com.summy.reliquary.effect.RevelationBeam.beamLengthForTest(player, kindBrimstone);
		int brimstoneRadius = com.summy.reliquary.effect.RevelationBeam.beamRadiusForTest(player, kindBrimstone);
		float brimstoneDamage = com.summy.reliquary.effect.RevelationBeam.beamDamageForTest(player, kindBrimstone);

		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		var kindAbaddon = com.summy.reliquary.effect.RevelationBeam.kindFor(player);
		int abaddonLength = com.summy.reliquary.effect.RevelationBeam.beamLengthForTest(player, kindAbaddon);
		int abaddonRadius = com.summy.reliquary.effect.RevelationBeam.beamRadiusForTest(player, kindAbaddon);
		float abaddonDamage = com.summy.reliquary.effect.RevelationBeam.beamDamageForTest(player, kindAbaddon);
		unequip(player, ReliquarySlots.REVELATION);

		boolean brimstoneOk = kindBrimstone == com.summy.reliquary.effect.RevelationBeam.BeamKind.DEMON_FLAME
				&& brimstoneLength == 18 && brimstoneRadius == 2 && Math.abs(brimstoneDamage - 6.0F) < 1.0E-4F;
		boolean abaddonOk = kindAbaddon == com.summy.reliquary.effect.RevelationBeam.BeamKind.DEMON_FLAME
				&& abaddonLength == 32 && abaddonRadius == 3 && Math.abs(abaddonDamage - 9.0F) < 1.0E-4F;
		log("恶魔之焰分流：戴硫磺火 → " + brimstoneLength + " 格 / 半径 " + brimstoneRadius + " / "
				+ brimstoneDamage + " 点=" + brimstoneOk + "（应 true，18/2/6）；戴亚巴顿 → " + abaddonLength
				+ " 格 / 半径 " + abaddonRadius + " / " + abaddonDamage + " 点=" + abaddonOk
				+ "（应 true，32/3/9）");
		resetDemonPactState(player);
	}

	/** 亚巴顿：不减速飞行 + 五件飞行饰品的摔落免疫 */
	private static void checkAbaddonFlight(ServerPlayer player) {
		resetDemonPactState(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		prepareBarePlayer(player);

		// ① 亚巴顿：授予飞行 + 不减速
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		boolean flying = player.getAbilities().mayfly;
		float abaddonSpeed = player.getAbilities().getFlyingSpeed();
		boolean noSlowdown = Math.abs(abaddonSpeed - 0.05F) < 1.0E-4F;
		// ② 与夜之幽魂同戴：仍是不减速（半速被覆盖）
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.NIGHT_WRAITH.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		float bothSpeed = player.getAbilities().getFlyingSpeed();
		unequip(player, ReliquarySlots.BLESSING);

		// ③ 逐件验收摔落免疫：夜之幽魂 / 玄秘魔眼 / 终末天启 / 亚巴顿 / 神性
		String[] ids = {"night_wraith", "occult_eye", "final_revelation", "abaddon", "godhead"};
		String[] slots = {ReliquarySlots.BLESSING, ReliquarySlots.BLESSING, ReliquarySlots.REVELATION,
				ReliquarySlots.REVELATION, ReliquarySlots.REVELATION};
		StringBuilder fallDetail = new StringBuilder();
		boolean fallImmuneAll = true;
		for (int index = 0; index < ids.length; index++) {
			clearBlessingSlots(player);
			unequip(player, ReliquarySlots.REVELATION);
			equip(player, slots[index], itemById(ids[index]));
			com.summy.reliquary.effect.AttributeManager.apply(player);
			boolean granted = com.summy.reliquary.effect.AttributeManager.hasGrantedFlight(player);
			player.setHealth(player.getMaxHealth());
			player.setAbsorptionAmount(0.0F);
			com.summy.reliquary.effect.SoulShield.setPoints(player, 0.0D);
			com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
			player.invulnerableTime = 0;
			player.hurt(player.damageSources().fall(), 5.0F);
			boolean noLoss = Math.abs(player.getHealth() - player.getMaxHealth()) < 1.0E-4F;
			fallImmuneAll &= granted && noLoss;
			fallDetail.append(ids[index]).append('=').append(granted && noLoss).append(' ');
		}
		// ④ 全部摘下后：坠落伤害照常生效
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		boolean noFlightNow = !com.summy.reliquary.effect.AttributeManager.hasGrantedFlight(player);
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().fall(), 5.0F);
		boolean damagedAfterRemoval = player.getHealth() < player.getMaxHealth();

		log("亚巴顿飞行：授予飞行=" + flying + "（应 true）、飞行速度=" + abaddonSpeed + "（应 0.05 = 不减速）、"
				+ "与幽魂同戴=" + bothSpeed + "（应仍 0.05）；**摔落免疫逐件**（" + fallDetail
				+ "）= " + fallImmuneAll + "（应 true）；全部摘下后坠落照常受伤=" + damagedAfterRemoval
				+ "（应 true）、此时无本模组飞行=" + noFlightNow + "（应 true）");
		resetDemonPactState(player);
	}

	/** 亚巴顿：被击杀拦截复活（满血 / 清负面 / 8 秒无敌 / 冷却）+ 环境伤害只传送 */
	private static void checkAbaddonRevive(ServerPlayer player) {
		resetDemonPactState(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		prepareBarePlayer(player);
		com.summy.reliquary.effect.DamagePools.clear();
		com.summy.reliquary.effect.Abaddon.reset();
		com.summy.reliquary.effect.PlayerFlags.setAbaddonReviveReadyAt(player, 0L);

		// ① 被击杀（有攻击者实体）：拦下 + 满血 + 清负面（保留正面）+ 无敌 + 光环 + 冷却
		Zombie zombie = spawnZombieAtSide(player, 3.0D);
		player.setHealth(5.0F);
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.SoulShield.setPoints(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		player.removeAllEffects();
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 600, 0, false, false, false));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.WEAKNESS, 600, 0, false, false, false));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 600, 0, false, false, false));
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().mobAttack(zombie == null ? player : zombie), 40.0F);
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		boolean revived = com.summy.reliquary.effect.Abaddon.reviveCount() == 1;
		float healthAfter = player.getHealth();
		boolean cleaned = !player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)
				&& !player.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
		boolean kept = player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
		boolean guarded = com.summy.reliquary.effect.Abaddon.isGuarded(player);
		long now = player.level().getGameTime();
		boolean aura = com.summy.reliquary.effect.Abaddon.auraUntil(player) > now;
		boolean cooled = com.summy.reliquary.effect.PlayerFlags.abaddonReviveReadyAt(player) > now;
		boolean immuneNow = true;
		if (zombie != null) {
			player.setHealth(player.getMaxHealth());
			player.invulnerableTime = 0;
			player.hurt(player.damageSources().mobAttack(zombie), 3.0F);
			immuneNow = Math.abs(player.getHealth() - player.getMaxHealth()) < 1.0E-4F;
			zombie.discard();
		}
		// ② 冷却中：同样的致命一击不再被亚巴顿拦（直接调判定，避免真的死掉）
		boolean cooledBlocks = !com.summy.reliquary.effect.Abaddon.tryNullify(player, 999.0F,
				player.damageSources().mobAttack(player));

		// ③ 环境伤害（仙人掌，无攻击者实体）：只传送回重生点、保持血量、不吃冷却
		com.summy.reliquary.effect.Abaddon.clearGuardForTest(player);
		com.summy.reliquary.effect.Abaddon.clearAuraForTest(player);
		com.summy.reliquary.effect.PlayerFlags.setAbaddonReviveReadyAt(player, 0L);
		com.summy.reliquary.effect.DamagePools.clear();
		int reviveBeforeEnv = com.summy.reliquary.effect.Abaddon.reviveCount();
		player.setHealth(6.0F);
		float healthBeforeEnv = player.getHealth();
		// 先挪到重生点 30 格之外，这样"确实被送回去了"才断言得出来
		ServerLevel expectedLevel = player.serverLevel();
		BlockPos expectedPos;
		BlockPos respawnPos = player.getRespawnPosition();
		ServerLevel respawnLevel = player.getRespawnDimension() == null ? null
				: player.getServer().getLevel(player.getRespawnDimension());
		if (respawnPos != null && respawnLevel != null) {
			expectedLevel = respawnLevel;
			expectedPos = respawnPos;
		} else {
			expectedPos = expectedLevel.getSharedSpawnPos();
		}
		player.teleportTo(expectedPos.getX() + 30.5D, expectedPos.getY() + 1.0D, expectedPos.getZ() + 30.5D);
		var beforePos = player.position();
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().cactus(), 40.0F);
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		boolean envNoRevive = com.summy.reliquary.effect.Abaddon.reviveCount() == reviveBeforeEnv;
		boolean envHealthKept = Math.abs(player.getHealth() - healthBeforeEnv) < 1.0E-3F;
		boolean envNoCooldown = com.summy.reliquary.effect.PlayerFlags.abaddonReviveReadyAt(player) == 0L;
		boolean envTeleported = player.serverLevel() == expectedLevel
				&& Math.abs(player.getX() - (expectedPos.getX() + 0.5D)) < 2.0D
				&& Math.abs(player.getZ() - (expectedPos.getZ() + 0.5D)) < 2.0D
				&& player.position().distanceTo(beforePos) > 1.0D;
		boolean envNoGuard = !com.summy.reliquary.effect.Abaddon.isGuarded(player);
		log("亚巴顿复活：被击杀拦下=" + revived + "（应 true）、生命=" + healthAfter + "（应 满血 "
				+ player.getMaxHealth() + "）、负面清空=" + cleaned + "、正面保留=" + kept + "（应 true）、"
				+ "无敌守卫=" + guarded + "（应 true）、无敌期内伤害被取消=" + immuneNow + "（应 true）、"
				+ "恶魔光环开启=" + aura + "（应 true）、冷却已写入=" + cooled + "（应 true）、"
				+ "冷却中不再拦=" + cooledBlocks + "（应 true）；环境（仙人掌）致死 → 不触发复活=" + envNoRevive
				+ "、保持血量=" + envHealthKept + "（应 true）、不写冷却=" + envNoCooldown + "（应 true）、"
				+ "被传送回重生点=" + envTeleported + "（应 true）、不给无敌=" + envNoGuard + "（应 true）");
		resetDemonPactState(player);
	}

	/** 亚巴顿：恶魔光环（半径 5、每 tick 6 点真伤、只打敌对 / 仇恨、黑烟） */
	private static void checkAbaddonAura(ServerPlayer player) {
		resetDemonPactState(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		// 同时戴上深渊领主（加护栏）：用来验证"光环伤害不会再叠狱火"
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.ABYSS_LORD.get());
		prepareBarePlayer(player);
		com.summy.reliquary.effect.Abaddon.reset();
		com.summy.reliquary.effect.PlayerFlags.setAbaddonReviveReadyAt(player, 0L);

		// 触发一次"被击杀复活"以开启光环
		player.setHealth(5.0F);
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().mobAttack(player), 40.0F);
		com.summy.reliquary.effect.DamagePools.reconcile(player);

		Zombie armoured = spawnHolyLightTarget(player, 3.0D);
		net.minecraft.world.entity.animal.Cow cow =
				spawnTestMobAt(player, 3.0D, net.minecraft.world.entity.EntityType.COW);
		Zombie farZombie = spawnHolyLightTarget(player, 12.0D);
		float armourDealt = -1.0F;
		float cowDealt = -1.0F;
		float farDealt = -1.0F;
		boolean noHellfire = false;
		if (armoured != null && cow != null && farZombie != null) {
			armoured.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			armoured.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			armoured.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			armoured.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
			armoured.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));
			cow.setNoAi(true);
			farZombie.setNoAi(true);
			armoured.removeAllEffects();
			armoured.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));
			float beforeArmour = armoured.getHealth();
			float beforeCow = cow.getHealth();
			float beforeFar = farZombie.getHealth();
			com.summy.reliquary.effect.Abaddon.tickPlayer(player);
			armourDealt = beforeArmour - armoured.getHealth();
			cowDealt = beforeCow - cow.getHealth();
			farDealt = beforeFar - farZombie.getHealth();
			noHellfire = !armoured.hasEffect(SummyReliquary.HELLFIRE.get());
			armoured.discard();
			cow.discard();
			farZombie.discard();
		}
		boolean smoke = com.summy.reliquary.effect.Abaddon.smokeBatches() > 0;
		boolean ticks = com.summy.reliquary.effect.Abaddon.auraTicks() > 0;
		log("恶魔光环：每 tick 结算=" + ticks + "（应 true）、黑烟批次=" + smoke + "（应 true）；"
				+ "圈内穿钻石甲 + 抗性 IV 的僵尸=" + armourDealt + "（应 6 = 真伤）、圈内无害的牛=" + cowDealt
				+ "（应 0 = 只打敌对 / 仇恨）、12 格外的僵尸=" + farDealt + "（应 0 = 半径 5）；"
				+ "光环伤害不叠狱火=" + noHellfire + "（应 true）");
		resetDemonPactState(player);
	}

	/** 邪恶度（1.6.8）：满值锁死（不增不减）；未满时衰减照旧 */
	private static void checkEvilFullLock(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		long today = player.level().getDayTime() / 24000L;
		// ① 满值：跨过一天后仍是 1000
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, today - 1);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterFull = com.summy.reliquary.effect.PlayerFlags.evil(player);
		// ② 未满：默认衰减 5
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 900.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, today - 1);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterPartial = com.summy.reliquary.effect.PlayerFlags.evil(player);
		// ③ 满值 + 法袍：仍然锁死
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		equip(player, ReliquarySlots.BACK, SummyReliquary.CEREMONIAL_ROBES.get());
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 1000.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilDay(player, today - 1);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterFullRobe = com.summy.reliquary.effect.PlayerFlags.evil(player);
		clearRobeAndSeal(player);
		resetDemonPactState(player);
		log("邪恶满值锁死：满值跨日=" + afterFull + "（应仍 1000）、未满 900 跨日=" + afterPartial
				+ "（应 895 = 默认衰减 5）、满值 + 仪式法袍=" + afterFullRobe + "（应仍 1000）");
	}

	/** 该火焰伤害是否被完全拦下（用真实 hurt 路径，断言血量不掉） */
	private static boolean blockedFire(ServerPlayer player,
			java.util.function.Function<net.minecraft.resources.ResourceKey<
					net.minecraft.world.damagesource.DamageType>, net.minecraft.world.damagesource.DamageSource> sourceOf,
			net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> key) {
		player.setHealth(player.getMaxHealth());
		player.invulnerableTime = 0;
		player.hurt(sourceOf.apply(key), 4.0F);
		return player.getHealth() >= player.getMaxHealth();
	}

	// ==================== 1.6.3：夜之幽魂 / 解锁容错 / 复仇之魂火焰圈 / 深渊领主 / 文案规范 ====================

	/** 某条属性上的修正列表（自检诊断用；用来确认"属性到底有没有写进去"） */
	private static String movementModifiers(ServerPlayer player) {
		var instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (instance == null) {
			return "null";
		}
		return instance.getModifiers().stream()
				.map(modifier -> modifier.getName() + "=" + modifier.getAmount() + "/" + modifier.getOperation())
				.collect(java.util.stream.Collectors.joining(", "));
	}

	/** 夜之幽魂：门槛三态、移速 +20%、创造飞行（半速、与终末天启不叠加）、卸下回收 */
	private static void checkNightWraith(ServerPlayer player) {
		var previousGameMode = player.gameMode.getGameModeForPlayer();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		resetDemonPactState(player);
		com.summy.reliquary.effect.PlayerFlags.setDataReadyForTest(player, true);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.HALO, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.CHARM, 0, ItemStack.EMPTY);
			handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY);
		});
		// ① 没有恶魔标记 → 拒
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		boolean noDemonDenied = !canEquipBlessing(player, SummyReliquary.NIGHT_WRAITH.get());
		// ② 有恶魔标记但邪恶度不够 → 拒
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 400.0D);
		boolean notEnoughDenied = !canEquipBlessing(player, SummyReliquary.NIGHT_WRAITH.get());
		// ③ 邪恶度到 500 → 放行
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 500.0D);
		boolean allowed = canEquipBlessing(player, SummyReliquary.NIGHT_WRAITH.get());

		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double base = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		player.getAbilities().mayfly = false;
		player.getAbilities().setFlyingSpeed(0.05F);
		player.onUpdateAbilities();

		equip(player, ReliquarySlots.BLESSING, SummyReliquary.NIGHT_WRAITH.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double withWraith = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		boolean mayflyAlone = player.getAbilities().mayfly;
		float flySpeedAlone = player.getAbilities().getFlyingSpeed();
		// 诊断：确认物品真的戴上了、配置读到的百分比、以及移速属性上的修正列表
		boolean worn = wears(player, SummyReliquary.NIGHT_WRAITH.get());
		double configured = com.summy.reliquary.config.ReliquaryConfig.nightWraithMovementPercent();
		String wraithModifiers = movementModifiers(player);

		// 与终末天启同戴：飞行不叠加（仍是同一档半速）
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0,
						new ItemStack(SummyReliquary.FINAL_REVELATION.get())));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double withBoth = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		float flySpeedBoth = player.getAbilities().getFlyingSpeed();

		// 全部卸下 → 移速回落、飞行收回
		clearBlessingSlots(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double afterUnequip = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		boolean mayflyAfter = player.getAbilities().mayfly;

		log(String.format("夜之幽魂：无标记被拦=%s（应 true）、邪恶 400 被拦=%s（应 true）、邪恶 500 放行=%s（应 true）；"
						+ "移速 %.4f → %.4f（应 ×1.2 = %.4f）→ 同戴天启 %.4f（应同值，不叠加）→ 卸下 %.4f（应回落）；"
						+ "飞行 单戴=%s/%.3f、同戴天启=%.3f（都应 %.3f = 半速）、卸下后=%s（应 false）；"
						+ "诊断 已佩戴=%s（应 true）、配置=%s%%、移速修正=[%s]",
				noDemonDenied, notEnoughDenied, allowed, base, withWraith, base * 1.2D, withBoth, afterUnequip,
				mayflyAlone, flySpeedAlone, flySpeedBoth,
				0.05D * com.summy.reliquary.config.ReliquaryConfig.flightSpeedMultiplier(), mayflyAfter,
				worn, configured, wraithModifiers));

		player.getAbilities().mayfly = false;
		player.getAbilities().setFlyingSpeed(0.05F);
		player.onUpdateAbilities();
		player.setGameMode(previousGameMode);
		resetDemonPactState(player);
	}

	/** 解锁容错（1.6.3）：位图没同步但当前邪恶度达标也算解锁；位图一旦置位则永久有效（衰减不收回） */
	private static void checkUnlockTolerance(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 100.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);

		boolean byThreshold = com.summy.reliquary.effect.EvilUnlock.usable(player,
				com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT);
		boolean stillDenied = !com.summy.reliquary.effect.EvilUnlock.usable(player,
				com.summy.reliquary.effect.EvilUnlock.NIGHT_WRAITH);
		boolean canWearByThreshold = canEquipBlessing(player, SummyReliquary.VENGEFUL_SPIRIT.get());

		// 位图置位后把邪恶度掉回 0：解锁永久、仍然可佩戴
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT.bit()
						| com.summy.reliquary.effect.EvilUnlock.NIGHT_WRAITH.bit());
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		boolean permanentVengeful = com.summy.reliquary.effect.EvilUnlock.usable(player,
				com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT)
				&& canEquipBlessing(player, SummyReliquary.VENGEFUL_SPIRIT.get());
		boolean permanentWraith = com.summy.reliquary.effect.EvilUnlock.usable(player,
				com.summy.reliquary.effect.EvilUnlock.NIGHT_WRAITH)
				&& canEquipBlessing(player, SummyReliquary.NIGHT_WRAITH.get());
		boolean bitmapKept = com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT
				.unlocked(com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player));

		log("解锁容错与永久性：邪恶 100 + 位图空 → 复仇之魂可用=" + byThreshold + "（应 true，不再误报「邪恶不足」）、"
				+ "夜之幽魂仍被拒=" + stillDenied + "（应 true）、可直接佩戴=" + canWearByThreshold + "（应 true）；"
				+ "邪恶掉回 0 后 复仇之魂=" + permanentVengeful + " / 夜之幽魂=" + permanentWraith
				+ "（都应 true）、位图仍保留=" + bitmapKept + "（应 true）");
		resetDemonPactState(player);
	}

	/** 复仇之魂范围圈：佩戴才生成、每 tick 一批、半径 3、逆时针（与救恩反相）、火焰粒子 */
	private static void checkFlameRing(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearBlessingSlots(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 200.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.VENGEFUL_SPIRIT.bit());
		com.summy.reliquary.effect.SlotSizing.syncNow(player);

		com.summy.reliquary.effect.VengefulSpirit.resetRingBatches();
		com.summy.reliquary.effect.VengefulSpirit.tickRing(player);
		int without = com.summy.reliquary.effect.VengefulSpirit.ringBatches();
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.VENGEFUL_SPIRIT.get());
		com.summy.reliquary.effect.VengefulSpirit.tickRing(player);
		com.summy.reliquary.effect.VengefulSpirit.tickRing(player);
		int with = com.summy.reliquary.effect.VengefulSpirit.ringBatches();

		double radius = com.summy.reliquary.effect.VengefulSpirit.effectiveRadius(player);
		// 1.6.7：同时佩戴硫磺火时范围扩大到配置值（默认 3 → 4）
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.BRIMSTONE.get());
		double radiusWithBrimstone = com.summy.reliquary.effect.VengefulSpirit.effectiveRadius(player);
		unequip(player, ReliquarySlots.REVELATION);
		// 1.6.8：佩戴亚巴顿时优先取更高的一档（5 格）
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		double radiusWithAbaddon = com.summy.reliquary.effect.VengefulSpirit.effectiveRadius(player);
		unequip(player, ReliquarySlots.REVELATION);
		// 1.6.7：粒子换成"短寿命火焰"（贴图仍是原版火焰，寿命 4~8 tick，走动不再拖尾）
		boolean flame = com.summy.reliquary.effect.VengefulSpirit.ringParticle()
				== SummyReliquary.SHORT_FLAME.get();
		// 方向：救恩是 +1.5°/tick（顺时针），复仇之魂取负号 → 逆时针
		double salvationPhase = 100L * 1.5D * Math.PI / 180.0D;
		boolean reversed = Math.abs(com.summy.reliquary.effect.VengefulSpirit.ringPhase(100L)
				+ salvationPhase) < 1.0E-9D;
		boolean smooth = Math.abs(com.summy.reliquary.effect.VengefulSpirit.ringPhase(101L)
				- com.summy.reliquary.effect.VengefulSpirit.ringPhase(100L)
				+ 1.5D * Math.PI / 180.0D) < 1.0E-9D;

		log("复仇之魂范围圈：未佩戴批次数=" + without + "（应 0）、佩戴 2 tick 后=" + with + "（应 2 = 每 tick 一批）、"
				+ "半径=" + radius + "（应 3.0）、戴硫磺火后=" + radiusWithBrimstone + "（应 4.0）、"
				+ "戴亚巴顿后=" + radiusWithAbaddon + "（应 5.0）、短寿命火焰粒子=" + flame + "（应 true）、与救恩反相（逆时针）="
				+ reversed + "（应 true）、每 tick 只转 1.5°=" + smooth + "（应 true）");
		clearBlessingSlots(player);
		resetDemonPactState(player);
	}

	/** 深渊领主（1.6.7 转正）：加护栏、恶魔标记 + 邪恶 900、狱火施加方 */
	private static void checkAbyssLord(ServerPlayer player) {
		boolean formal = SummyReliquary.ABYSS_LORD.isPresent()
				&& SummyReliquary.ABYSS_LORD.get() instanceof com.summy.reliquary.item.AbyssLordItem;
		com.summy.reliquary.effect.EvilUnlock unlock =
				com.summy.reliquary.effect.EvilUnlock.byItemId("abyss_lord");
		boolean threshold = unlock == com.summy.reliquary.effect.EvilUnlock.ABYSS_LORD
				&& unlock != null && unlock.threshold() == 900 && unlock.lineCount() == 2;
		boolean name = Component.translatable("item.summy-reliquary.abyss_lord").getString().length() > 0;

		// 门槛三态：无标记被拦 / 邪恶 800 被拦 / 邪恶 900 放行；装错栏位被拦
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		boolean noDemon = !canEquipBlessing(player, SummyReliquary.ABYSS_LORD.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 800.0D);
		boolean notEnough = !canEquipBlessing(player, SummyReliquary.ABYSS_LORD.get());
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 900.0D);
		boolean allowed = canEquipBlessing(player, SummyReliquary.ABYSS_LORD.get());
		boolean wrongSlot = !canEquipIn(player, SummyReliquary.ABYSS_LORD.get(), ReliquarySlots.REVELATION);
		log("深渊领主：已转正（不再是占位）=" + formal + "（应 true）、门槛登记 900=" + threshold
				+ "（应 true）、语言键可用=" + name + "（应 true）；无标记被拦=" + noDemon
				+ "、邪恶 800 被拦=" + notEnough + "、邪恶 900 放行=" + allowed + "、装错栏位被拦=" + wrongSlot
				+ "（都应 true）");
		resetDemonPactState(player);
	}

	/**
	 * 文案规范（1.6.3）：{@code 属性名|数值} 两段配色（天使=淡金+亮金 / 恶魔=深红+亮红 / 中立=灰+白）、
	 * 不含 {@code |} 的叙述行保持灰、物品名按派系上色（中立仍是原版白、恶魔/天使是逐字动画组件）。
	 */
	private static void checkFactionTexts() {
		var angel = com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.ANGEL,
				"item.summy-reliquary.the_body.desc", 10);
		var demon = com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.DEMON,
				"item.summy-reliquary.night_wraith.shift.1", 20);
		var neutral = com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.NEUTRAL,
				"item.summy-reliquary.pentagram.shift.1", 1);
		var narrative = com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.DEMON,
				"item.summy-reliquary.satanic_bible.tagline.1");

		int angelName = angel.getStyle().getColor().getValue();
		int angelValue = angel.getSiblings().get(0).getStyle().getColor().getValue();
		int demonName = demon.getStyle().getColor().getValue();
		int demonValue = demon.getSiblings().get(0).getStyle().getColor().getValue();
		int neutralName = neutral.getStyle().getColor().getValue();
		int neutralValue = neutral.getSiblings().get(0).getStyle().getColor().getValue();
		boolean narrativeStaysGray = narrative.getSiblings().isEmpty()
				&& narrative.getStyle().getColor().getValue() == 0xAAAAAA;

		var demonItemName = new ItemStack(SummyReliquary.NIGHT_WRAITH.get()).getHoverName();
		var angelItemName = new ItemStack(SummyReliquary.THE_BODY.get()).getHoverName();
		var neutralItemName = new ItemStack(SummyReliquary.SIX.get()).getHoverName();

		log(String.format("文案规范：天使 属性名/数值=#%06X/#%06X（应 FFE4B5/FFD700）、"
						+ "恶魔=#%06X/#%06X（应 C03030/FF6B6B）、中立=#%06X/#%06X（应 AAAAAA/FFFFFF）、"
						+ "叙述行保持灰=%s（应 true）；物品名 恶魔逐字=%s、天使逐字=%s、中立原版白=%s（都应 true）",
				angelName, angelValue, demonName, demonValue, neutralName, neutralValue, narrativeStaysGray,
				!demonItemName.getSiblings().isEmpty(), !angelItemName.getSiblings().isEmpty(),
				neutralItemName.getSiblings().isEmpty()));
	}

	// ==================== 1.6.4：硫磺火 / 恶魔之焰 / 文案替换 / 栏位矩阵 ====================

	/** 硫磺火门槛三态 + 进「启示之座」+ 与星 / 天启 / 神性互斥（同一格）+ V 键恶魔之焰优先 */
	private static void checkBrimstoneGates(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		// ① 没有恶魔标记 → 拒
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		boolean noDemon = !canEquipIn(player, SummyReliquary.BRIMSTONE.get(), ReliquarySlots.REVELATION);
		// ② 有恶魔标记但邪恶 600（未到 666）→ 拒
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 600.0D);
		boolean notEnough = !canEquipIn(player, SummyReliquary.BRIMSTONE.get(), ReliquarySlots.REVELATION);
		// ③ 邪恶 666 → 放行；装错栏位仍被拦
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 666.0D);
		boolean allowed = canEquipIn(player, SummyReliquary.BRIMSTONE.get(), ReliquarySlots.REVELATION);
		boolean wrongSlot = !canEquipIn(player, SummyReliquary.BRIMSTONE.get(), ReliquarySlots.BLESSING);
		int revelationSlots = com.summy.reliquary.effect.SlotSizing.slotsOf(player,
				ReliquarySlots.REVELATION);
		// ④ 同时具备启示之光与硫磺火时：V 键放恶魔之焰（1.6.4 优先级）
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.BRIMSTONE.get());
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		curioWear(player, SummyReliquary.BRIMSTONE.get());
		var kind = com.summy.reliquary.effect.RevelationBeam.kindFor(player);
		boolean demonPriority = kind == com.summy.reliquary.effect.RevelationBeam.BeamKind.DEMON_FLAME;
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.REVELATION, 0, ItemStack.EMPTY));
		log("硫磺火门槛：无恶魔标记被拦=" + noDemon + "（应 true）、邪恶 600 被拦=" + notEnough
				+ "（应 true）、邪恶 666 放行=" + allowed + "（应 true）、装错栏位被拦=" + wrongSlot
				+ "（应 true）、启示之座格数=" + revelationSlots + "（应 1 = 与星/天启/神性互斥）、"
				+ "同时具备时 V 键选中恶魔之焰=" + demonPriority + "（应 true）");
		resetDemonPactState(player);
	}

	/** 恶魔之焰：参数、真伤口径（钻石甲 / 抗性 IV 都不减）、伤害类型与死亡文本就位 */
	private static void checkDemonFlame(ServerPlayer player) {
		boolean params = com.summy.reliquary.config.ReliquaryConfig.brimstoneChargeTicks() == 30
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneBeamLength() == 18
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneBeamRadius() == 2
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneDurationTicks() == 26
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneDamagePerTick() == 6
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneDamageIntervalTicks() == 2
				&& com.summy.reliquary.config.ReliquaryConfig.brimstoneCooldownTicks() == 120;
		var damageTypes = player.serverLevel().registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
		boolean types = damageTypes.containsKey(SummyReliquary.id("demon_flame"))
				&& damageTypes.containsKey(SummyReliquary.id("godhead_aura"));
		// 真伤：钻石甲 + 抗性 IV 各受 6 点（口径完全由 bypasses_* 标签决定）
		Zombie armored = spawnHolyLightTarget(player, 3.0D);
		Zombie resistant = spawnHolyLightTarget(player, 3.0D);
		float armoredDealt = -1.0F;
		float resistantDealt = -1.0F;
		if (armored != null && resistant != null) {
			armored.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			armored.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			armored.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
			resistant.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			resistant.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			resistant.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			resistant.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
			resistant.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));
			float beforeA = armored.getHealth();
			float beforeR = resistant.getHealth();
			com.summy.reliquary.effect.RevelationBeam.applyTrueDamage(player.serverLevel(), player,
					armored, 6.0F, "demon_flame", false);
			com.summy.reliquary.effect.RevelationBeam.applyTrueDamage(player.serverLevel(), player,
					resistant, 6.0F, "demon_flame", false);
			armoredDealt = beforeA - armored.getHealth();
			resistantDealt = beforeR - resistant.getHealth();
			armored.discard();
			resistant.discard();
		}
		boolean deathText = translated("death.attack.summy-reliquary.demon_flame")
				&& translated("death.attack.summy-reliquary.demon_flame.player");
		log(String.format("恶魔之焰：参数 30tick/18 格/半径 2/26tick×2tick 间隔×6 点/冷却 120tick=%s（应 true）、"
						+ "伤害类型与光环类型都在注册表里=%s（应 true）、钻石甲=%.2f（应 6 = 真伤）、"
						+ "抗性 IV=%.2f（应 6 = 真伤）、死亡文本键=%s（应 true）",
				params, types, armoredDealt, resistantDealt, deathText));
	}

	/** 本轮点名替换的文案 + 未点名行保持不变（1.6.4） */
	private static void checkTextReplacements(ServerPlayer player) {
		String vengeful = Component.translatable("item.summy-reliquary.vengeful_spirit.shift.1").getString();
		String vengefulDetail = Component.translatable("item.summy-reliquary.vengeful_spirit.shift.2")
				.getString();
		String bibleRefill = Component.translatable("item.summy-reliquary.satanic_bible.shift.2").getString();
		String bibleShatter = Component.translatable("item.summy-reliquary.satanic_bible.shift.3").getString();
		String mark = Component.translatable("item.summy-reliquary.the_mark.shift.2").getString();
		String wraith = Component.translatable("item.summy-reliquary.night_wraith.shift.2").getString();
		boolean replaced = vengeful.contains("|") && vengeful.contains("狱火伤害")
				&& vengefulDetail.contains("无视护甲与火焰免疫") && !vengefulDetail.contains("|")
				&& bibleRefill.contains("|") && bibleRefill.contains("每")
				&& bibleShatter.contains("|") && bibleShatter.contains("所有敌人")
				&& mark.contains("|") && mark.contains("提升至")
				&& wraith.contains("速度减半") && !wraith.contains("|");
		// 未点名的行必须与 1.6.3 完全一致
		boolean untouched = Component.translatable("item.summy-reliquary.satanic_bible.shift.1").getString()
				.equals("黑心|+%s")
				&& Component.translatable("item.summy-reliquary.satanic_bible.shift.4").getString()
						.equals("七罪负担已被消除")
				&& Component.translatable("item.summy-reliquary.the_mark.shift.1").getString()
						.equals("邪恶不再衰减")
				&& Component.translatable("item.summy-reliquary.the_mark.shift.3").getString()
						.equals("拥有扭曲的天使之力")
				&& Component.translatable("item.summy-reliquary.night_wraith.shift.1").getString()
						.equals("移动速度|+%s%%")
				&& Component.translatable("item.summy-reliquary.the_pact.shift.evil").getString()
						.equals("邪恶|%s/%s")
				&& Component.translatable("item.summy-reliquary.the_pact.shift.bound").getString()
						.equals("你无法摆脱你的契约");
		boolean brimstone = Component.translatable("item.summy-reliquary.brimstone.tagline.1").getString()
				.equals("地狱最深处的火焰")
				&& Component.translatable("item.summy-reliquary.brimstone.tagline.2").getString()
						.equals("足以焚尽世界")
				&& Component.translatable("item.summy-reliquary.brimstone.quote").getString()
						.equals("“所有人都恐惧它”")
				&& Component.translatable("item.summy-reliquary.brimstone.shift.1").getString()
						.contains("恶魔之焰|")
				&& Component.translatable("item.summy-reliquary.brimstone.shift.2").getString()
						.contains("焚烧路径内一切敌人")
				&& Component.translatable("item.summy-reliquary.brimstone.shift.3").getString()
						.equals("冷却|%s 秒");
		// 邪恶值显示一位小数（1.6.4）：123.456 → 123.5
		double evilBefore = com.summy.reliquary.effect.PlayerFlags.evil(player);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 123.456D);
		double evilExact = com.summy.reliquary.effect.DemonPact.evilExact(player);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, evilBefore);
		// 1.6.5：灵魂删行、终末天启飞行行对齐、圣心新增一行、玄秘魔眼与恐惧文案
		boolean soulLineRemoved = !translated("item.summy-reliquary.the_soul.desc.detail");
		boolean revelationFlight = Component.translatable("item.summy-reliquary.final_revelation.desc.1.detail")
				.getString().contains("速度减半");
		boolean sacredHeartLine = Component.translatable("item.summy-reliquary.sacred_heart.shift.4")
				.getString().equals("你不再恐惧深渊");
		boolean occultEye = Component.translatable("item.summy-reliquary.occult_eye.tagline.1").getString()
				.equals("眼睛睁开了")
				&& Component.translatable("item.summy-reliquary.occult_eye.tagline.2").getString()
						.equals("是时候让世界恐惧了")
				&& Component.translatable("item.summy-reliquary.occult_eye.shift.1").getString()
						.equals("继承夜之幽魂能力")
				&& Component.translatable("item.summy-reliquary.occult_eye.shift.2").getString()
						.contains("恐惧|")
				&& Component.translatable("item.summy-reliquary.occult_eye.shift.3").getString()
						.contains("挖掘疲劳 III")
				&& Component.translatable("item.summy-reliquary.occult_eye.shift.4").getString()
						.contains("×%s")
				&& Component.translatable("effect.summy-reliquary.fear").getString().equals("恐惧");
		log("文案替换：点名行已替换=" + replaced + "（应 true）、未点名行与 1.6.3 一致=" + untouched
				+ "（应 true）、硫磺火新增文案=" + brimstone + "（应 true）、邪恶一位小数=" + evilExact
				+ "（应 123.5）");
		log("1.6.5 文案：灵魂说明行已删=" + soulLineRemoved + "（应 true）、终末天启飞行行对齐="
				+ revelationFlight + "（应 true）、圣心新增「你不再恐惧深渊」=" + sacredHeartLine
				+ "（应 true）、玄秘魔眼与恐惧文案=" + occultEye + "（应 true）");
	}

	/**
	 * 栏位校验矩阵：每件本模组物品在**正确栏位**应通过 `DynamicStackHandler#isItemValid`（即 Curios 真正用的那道校验），
	 * 在**无关栏位**应被拒 —— 1.6.3 的"夜之幽魂漏登记"就是这道校验漏了。
	 */
	private static void checkSlotValidatorMatrix(ServerPlayer player) {
		// 天使线：先切到天使阶段
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		// 1.7.1：矩阵只测"栏位对不对" —— 「无罪之人」的终身锁（sin_renounced）是另一个维度，
		// 前面的创世纪用例会把它置位，留着会让七罪之源被自己的锁拦下，产生假失败
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, false);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		StringBuilder failures = new StringBuilder();
		int checked = 0;
		checked += checkMatrixRow(player, ReliquarySlots.REVELATION, SummyReliquary.STAR_OF_BETHLEHEM.get(),
				failures);
		checked += checkMatrixRow(player, ReliquarySlots.REVELATION, SummyReliquary.FINAL_REVELATION.get(),
				failures);
		checked += checkMatrixRow(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.BLESSING, SummyReliquary.SALVATION.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_LIGHT.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_MANTLE.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_BODY.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MIND.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_SOUL.get(), failures);
		// 恶魔线：恶魔标记 + 全部解锁位图（这样门槛都满足，剩下的就是"栏位对不对"）
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		int allUnlocks = 0;
		for (com.summy.reliquary.effect.EvilUnlock unlock : com.summy.reliquary.effect.EvilUnlock.values()) {
			allUnlocks |= unlock.bit();
		}
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, allUnlocks);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, com.summy.reliquary.config.ReliquaryConfig.evilMax());
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		checked += checkMatrixRow(player, ReliquarySlots.BLESSING, SummyReliquary.VENGEFUL_SPIRIT.get(),
				failures);
		checked += checkMatrixRow(player, ReliquarySlots.BLESSING, SummyReliquary.NIGHT_WRAITH.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.REVELATION, SummyReliquary.BRIMSTONE.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.VIRTUES.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.DEMON_PACT, SummyReliquary.THE_PACT.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.HALO, SummyReliquary.THE_HALO.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.HALO, SummyReliquary.BANG_BANG_HALO.get(), failures);
		// 1.7.1：恶魔王冠也进光环栏（与光环 / 邦邦咔邦互斥），无关栏位必须被拒
		checked += checkMatrixRow(player, ReliquarySlots.HALO, SummyReliquary.DEVIL_CROWN.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.STOMACH, SummyReliquary.FREELOADERS_RICE.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.BACK, SummyReliquary.CEREMONIAL_ROBES.get(), failures);
		checked += checkMatrixRow(player, ReliquarySlots.CHARM, SummyReliquary.PENTAGRAM.get(), failures);
		log("栏位矩阵：检查 " + checked + " 项（正确栏位放行 + 无关栏位拒绝），不通过项="
				+ (failures.length() == 0 ? "无" : failures.toString()) + "（应为无）");
		resetDemonPactState(player);
	}

	/** 单行矩阵：正确栏位必须放行、HALO（或另一个无关栏位）必须拒绝 */
	private static int checkMatrixRow(ServerPlayer player, String slot, net.minecraft.world.item.Item item,
			StringBuilder failures) {
		boolean accepted = isValidIn(player, slot, item);
		String wrongSlot = ReliquarySlots.HALO.equals(slot) ? ReliquarySlots.STOMACH : ReliquarySlots.HALO;
		boolean rejected = !isValidIn(player, wrongSlot, item);
		if (!accepted || !rejected) {
			// 1.7.1：文案以前写反了（accepted=false 时其实报的是"正确栏位被拒"）
			failures.append(item).append('@').append(slot)
					.append(accepted ? "（无关栏位放行）" : "（正确栏位被拒）").append(' ');
		}
		return 1;
	}

	/** Curios 真正用来判定"这件物品能不能进这一格"的入口 */
	private static boolean isValidIn(ServerPlayer player, String slot, net.minecraft.world.item.Item item) {
		return CuriosApi.getCuriosInventory(player).map(handler -> {
			var stacksHandler = handler.getCurios().get(slot);
			if (stacksHandler == null || stacksHandler.getSlots() <= 0) {
				return false;
			}
			return stacksHandler.getStacks().isItemValid(0, new ItemStack(item));
		}).orElse(false);
	}

	/** 该玩家是否佩戴着某件物品（自检用的小包装，避免和通用 wears 重名） */
	private static boolean curioWear(ServerPlayer player, net.minecraft.world.item.Item item) {
		return com.summy.reliquary.util.CurioHelper.wears(player, item);
	}

	// ==================== 1.6.5：Kilt 双触发 / 对账 / 无敌帧钳位 / HUD 行位 / 玄秘魔眼 ====================

	/**
	 * Kilt 双触发回归：玩家的一次命中会触发两次 {@code LivingHurtEvent}
	 * （{@code LivingEntityInject.kilt$cancelIfNegativeDamage} 与 {@code PlayerInject.kilt$callLivingHurt}
	 * 锚在 {@code actuallyHurt} 的同一点，读的是同一个方法参数 → 同一个来源实例、同一个金额）。
	 *
	 * <p>只有第一次该介入；第二次必须**原样放过**，既不能重复扣池，也不能把第一次并进去的吸收值对账掉。
	 */
	private static void checkPoolDoubleTrigger(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		prepareBarePlayer(player);
		com.summy.reliquary.effect.DamagePools.clear();
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		player.setAbsorptionAmount(0.0F);
		boolean fabricBefore = com.summy.reliquary.effect.DamagePools.fabricSide();
		com.summy.reliquary.effect.DamagePools.setFabricForTest(true);

		// 同一击：同一个 DamageSource 实例 + 同一个金额
		net.minecraft.world.damagesource.DamageSource hit = player.damageSources().generic();
		boolean firstHandled = com.summy.reliquary.effect.DamagePools.prepare(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player, hit, 3.0F));
		double poolAfterFirst = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float absorptionAfterFirst = player.getAbsorptionAmount();

		// 第二次触发：同一 tick、同一来源、同一金额，且吸收值还是"并入后"的样子 → 判定为重复事件
		boolean secondHandled = com.summy.reliquary.effect.DamagePools.prepare(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player, hit, 3.0F));
		double poolAfterSecond = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float absorptionAfterSecond = player.getAbsorptionAmount();

		// 换一个**不同的来源实例**（同 tick 的另一次真实命中）→ 必须按新的一击处理：
		// 先把上一击并入的量对账退还（本例里那一击没被原版吃掉），再按本击重新扣 3。
		// 注意 `damageSources().generic()` 是**缓存实例**，两次调用拿到的是同一个对象 —— 想要"另一个来源"
		// 必须换类型（这里用 magic），否则会被正确地判成同一击的重复事件。
		boolean otherHitHandled = com.summy.reliquary.effect.DamagePools.prepare(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player, player.damageSources().magic(),
						3.0F));
		double poolAfterOtherHit = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);

		// 收尾：模拟这一击真的被吃满 → 对账
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		boolean noPending = !com.summy.reliquary.effect.DamagePools.hasPending(player);
		com.summy.reliquary.effect.DamagePools.setFabricForTest(fabricBefore);
		log("Kilt 双触发：首次介入=" + firstHandled + "（应 true）、第二次介入=" + secondHandled
				+ "（应 false）、池 " + poolAfterFirst + " → " + poolAfterSecond + "（应不变，只扣一次）、"
				+ "并入的量 " + absorptionAfterFirst + " → " + absorptionAfterSecond + "（应不变，保留第一次的并入）、"
				+ "换来源实例按新的一击处理=" + otherHitHandled + "（应 true，池 " + poolAfterOtherHit
				+ " 应为 1 = 上一击的并入先被退还、本击重新扣 3）、对账后无挂起=" + noPending + "（应 true）");
		resetDemonPactState(player);
	}

	/**
	 * 1.6.6 的核心回归：**Fabric 侧（Kilt）也必须真的扣池**。
	 *
	 * <p>1.6.5 的"新的一击"门槛（{@code invulnerableTime <= 10}）对任何真实命中都是 false ——
	 * Forge 的 {@code LivingHurtEvent} 在 {@code actuallyHurt} 内部触发，而 {@code hurt} 早就把字段写成 20；
	 * 更糟的是该门槛只在 Fabric 侧生效，所以 Forge 自检全绿、线上池子一次都没介入。
	 * 这里强制按 Fabric 侧跑真实 {@code player.hurt(...)}，结果必须与 Forge 一致。
	 */
	private static void checkPoolsOnFabricSide(ServerPlayer player) {
		boolean fabricBefore = com.summy.reliquary.effect.DamagePools.fabricSide();
		com.summy.reliquary.effect.DamagePools.setFabricForTest(true);
		// 先脱掉所有饰品：灵台三件套的 −20% 受伤减免会出现在事件金额上（我们估算的是"原版减免"，不含它），
		// 留着会让这里的期望值变复杂；这一条测的是"池子到底扣没扣"
		stripAllCurios(player);
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		prepareBarePlayer(player);
		com.summy.reliquary.effect.DamagePools.clear();
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		player.setAbsorptionAmount(0.0F);
		float healthBefore = player.getHealth();
		// 诊断：把"命中前后 + hurt 是否落地"都打出来 —— 池子/守卫类问题的第一现场
		log("[诊断] Fabric 命中前：生命=" + healthBefore + "、吸收=" + player.getAbsorptionAmount()
				+ "、魂心池=" + com.summy.reliquary.effect.SoulShield.points(player) + "（上限 "
				+ com.summy.reliquary.effect.SoulShield.capacityFor(player) + "）、黑心池="
				+ com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player) + "（上限 "
				+ com.summy.reliquary.effect.DemonPact.blackHeartMaxPoints(player) + "）、契约生效="
				+ com.summy.reliquary.effect.DemonPact.active(player));

		// ① 池 4 受 3：池 → 1，红血不动
		player.invulnerableTime = 0;
		boolean landedFirst = player.hurt(player.damageSources().generic(), 3.0F);
		float absorptionRawFirst = player.getAbsorptionAmount();
		boolean pendingFirst = com.summy.reliquary.effect.DamagePools.hasPending(player);
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		double poolAfterFirst = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float healthAfterFirst = player.getHealth();
		float absorptionAfterFirst = player.getAbsorptionAmount();

		// ② 池 1 受 5：池 → 0，红血 −4
		player.invulnerableTime = 0;
		boolean landedSecond = player.hurt(player.damageSources().generic(), 5.0F);
		float absorptionRawSecond = player.getAbsorptionAmount();
		boolean pendingSecond = com.summy.reliquary.effect.DamagePools.hasPending(player);
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		double poolAfterSecond = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float healthAfterSecond = player.getHealth();
		com.summy.reliquary.effect.DamagePools.setFabricForTest(fabricBefore);

		log("[诊断] 第 1 击：hurt=" + landedFirst + "、对账前吸收=" + absorptionRawFirst + "、有挂起=" + pendingFirst
				+ "、对账后 池=" + poolAfterFirst + " 生命=" + healthAfterFirst + " 吸收=" + absorptionAfterFirst
				+ "；第 2 击：hurt=" + landedSecond + "、对账前吸收=" + absorptionRawSecond + "、有挂起=" + pendingSecond
				+ "、对账后 池=" + poolAfterSecond + " 生命=" + healthAfterSecond);

		boolean firstOk = Math.abs(poolAfterFirst - 1.0D) < 1.0E-4D
				&& Math.abs(healthAfterFirst - healthBefore) < 1.0E-4F
				&& Math.abs(absorptionAfterFirst) < 1.0E-4F;
		boolean secondOk = Math.abs(poolAfterSecond) < 1.0E-4D
				&& Math.abs((healthBefore - 4.0F) - healthAfterSecond) < 1.0E-4F;
		log("Fabric 侧真实命中：池 4 受 3 → " + poolAfterFirst + "（应 1）、红血 " + healthAfterFirst + "（应 "
				+ healthBefore + " 不动）、吸收 " + absorptionAfterFirst + "（应 0）；再受 5 → 池 " + poolAfterSecond
				+ "（应 0）、红血 " + healthAfterSecond + "（应 " + (healthBefore - 4.0F) + " = 只掉护盾挡不住的 4 点）；"
				+ "整体=" + (firstOk && secondOk) + "（应 true）");
		resetDemonPactState(player);
	}

	/** 被吞掉的命中：prepare 之后没真正打到（吸收值原样）→ 对账要把池与护盾都还原，且不触发破碎 */
	private static void checkPoolSwallowedHit(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		prepareBarePlayer(player);
		com.summy.reliquary.effect.DamagePools.clear();
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		player.setAbsorptionAmount(2.0F);
		int shatterBefore = com.summy.reliquary.effect.SoulShield.shatterCount();

		player.invulnerableTime = 0;
		com.summy.reliquary.effect.DamagePools.prepare(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player,
						player.damageSources().generic(), 3.0F));
		double poolCharged = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float absorptionTopped = player.getAbsorptionAmount();

		// 模拟"这一击被无敌帧/格挡吞掉"：吸收值原样（什么都没消耗）→ 直接对账
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		double poolBack = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		float absorptionBack = player.getAbsorptionAmount();
		boolean noShatter = com.summy.reliquary.effect.SoulShield.shatterCount() == shatterBefore;
		log("被吞掉的命中：扣池后池=" + poolCharged + "（应 3 = 只扣护盾挡不住的那 1 点）、并入后吸收="
				+ absorptionTopped + "（应 3 = 护盾 2 + 1）；对账后池=" + poolBack + "（应还原 4）、吸收=" + absorptionBack
				+ "（应还原 2）、未误触发破碎=" + noShatter + "（应 true）");
		resetDemonPactState(player);
	}

	/** 守卫：致命一击被神性整击拦下时，池子不动、对账后护盾还原 */
	private static void checkPoolGuardNullify(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.Godhead.reset();
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		prepareBarePlayer(player);
		com.summy.reliquary.effect.DamagePools.clear();
		player.setHealth(5.0F);
		player.setAbsorptionAmount(2.0F);
		player.invulnerableTime = 0;
		com.summy.reliquary.effect.DamagePools.prepare(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(player,
						player.damageSources().generic(), 20.0F));
		int guards = com.summy.reliquary.effect.Godhead.deathGuardCount();
		float healthAfterGuard = player.getHealth();
		float absorbedTopUp = player.getAbsorptionAmount();
		// 模拟"整击被吸收"：吸收值被原版吃光 → 对账应把护盾还原到命中前
		player.setAbsorptionAmount(0.0F);
		com.summy.reliquary.effect.DamagePools.reconcile(player);
		log("整击拦下：拦截次数=" + guards + "（应 ≥1）、拦下后生命=" + healthAfterGuard + "（应 1.0）、"
				+ "并入后吸收=" + absorbedTopUp + "（应 22 = 护盾 2 + 整击 20）、对账后吸收="
				+ player.getAbsorptionAmount() + "（应还原 2 = 护盾不损失）");
		resetDemonPactState(player);
	}

	/** Fabric 侧的两档无敌帧：tick 末尾把字段钳到"有效窗口 + 10"（帧伤档不缩窗） */
	private static void checkFabricWindowClamp(ServerPlayer player) {
		boolean fabricBefore = com.summy.reliquary.effect.CombatTuning.fabricSide();
		com.summy.reliquary.effect.CombatTuning.setFabricForTest(true);
		player.invulnerableTime = 19;
		com.summy.reliquary.effect.CombatTuning.recordWindowForTest(player,
				com.summy.reliquary.config.ReliquaryConfig.invulnerabilityTicks());
		com.summy.reliquary.effect.CombatTuning.clampFabricWindow(player);
		int general = player.invulnerableTime;
		player.invulnerableTime = 19;
		com.summy.reliquary.effect.CombatTuning.recordWindowForTest(player,
				com.summy.reliquary.config.ReliquaryConfig.invulnerabilityTicksFrameDamage());
		com.summy.reliquary.effect.CombatTuning.clampFabricWindow(player);
		int frame = player.invulnerableTime;
		com.summy.reliquary.effect.CombatTuning.setFabricForTest(fabricBefore);
		player.invulnerableTime = 0;
		log("Fabric 窗口钳位：一般档 19 → " + general + "（应 15 = 5 tick 档）、帧伤档 19 → " + frame
				+ "（应仍是 19 = 10 tick 档不缩窗）");
	}

	/**
	 * HUD 行位纯函数（1.6.6）：蓝心 / 黑心要压在**原版整张心网格**（红心多排 + 黄心）与盔甲之上。
	 *
	 * <p>原版 {@code Gui#renderPlayerHealth}：红心与黄心共用一张网格，从 {@code height - 39} 往上排，
	 * 行数 = {@code ceil((maxHealth + ceil(absorption)) / 2 / 10)}、行距 = {@code max(10 - (rows - 2), 3)}；
	 * 盔甲固定画在网格顶行之上 10px。1.6.5 只按"一排红心"估算，玩家生命上限变高（例如灵魂 +18 生命 =
	 * 19 颗心 = 两排）时蓝心会正好压住第二排红心。
	 */
	private static void checkHudRows() {
		int height = 240;
		int oneRowNoArmor = com.summy.reliquary.client.SoulHeartOverlay.soulHeartRowY(height, 20.0F, 0.0F, 0);
		int oneRowArmor = com.summy.reliquary.client.SoulHeartOverlay.soulHeartRowY(height, 20.0F, 0.0F, 20);
		int twoRowNoArmor = com.summy.reliquary.client.SoulHeartOverlay.soulHeartRowY(height, 38.0F, 0.0F, 0);
		int threeRowArmor = com.summy.reliquary.client.SoulHeartOverlay.soulHeartRowY(height, 58.0F, 0.0F, 20);
		int withAbsorption = com.summy.reliquary.client.SoulHeartOverlay.soulHeartRowY(height, 20.0F, 8.0F, 20);
		int blackNoSoul = com.summy.reliquary.client.DemonBlackHeartOverlay.blackHeartRowY(height, 20.0F, 0.0F, 20,
				0.0D);
		int blackWithSoul = com.summy.reliquary.client.DemonBlackHeartOverlay.blackHeartRowY(height, 20.0F, 0.0F, 20,
				10.0D);
		boolean rows = com.summy.reliquary.client.SoulHeartOverlay.soulHeartRows(0.0D) == 0
				&& com.summy.reliquary.client.SoulHeartOverlay.soulHeartRows(10.0D) == 1
				&& com.summy.reliquary.client.SoulHeartOverlay.soulHeartRows(22.0D) == 2;
		// 逐个场景断言：蓝心行不与任何原版网格行、也不与盔甲行重合
		boolean layout = oneRowNoArmor == height - 49 && oneRowArmor == height - 59
				&& twoRowNoArmor == height - 59 && threeRowArmor == height - 77
				&& withAbsorption == height - 69
				&& blackNoSoul == height - 59 && blackWithSoul == height - 69
				&& noOverlap(height, 38.0F, 0.0F, 0, twoRowNoArmor)
				&& noOverlap(height, 58.0F, 0.0F, 20, threeRowArmor)
				&& noOverlap(height, 20.0F, 8.0F, 20, withAbsorption);
		log("HUD 行位：单排无盔甲蓝心 Y=" + oneRowNoArmor + "（应 " + (height - 49) + "）、单排有盔甲 Y=" + oneRowArmor
				+ "（应 " + (height - 59) + "）、**两排红心无盔甲** Y=" + twoRowNoArmor + "（应 " + (height - 59)
				+ " = 不压第二排红心）、三排红心+盔甲 Y=" + threeRowArmor + "（应 " + (height - 77)
				+ "）、单排红心+一行黄心+盔甲 Y=" + withAbsorption + "（应 " + (height - 69) + "）；黑心 Y="
				+ blackNoSoul + "/" + blackWithSoul + "（应 " + (height - 59) + "/" + (height - 69)
				+ " = 蓝心之上）、行数 0/1/2=" + rows + "（应 true）、整体=" + layout + "（应 true）");
	}

	/** 指定生命 / 吸收 / 盔甲下，我们的蓝心行（ourY）是否避开了原版所有网格行与盔甲行 */
	private static boolean noOverlap(int screenHeight, float maxHealth, float absorption, int armorPoints, int ourY) {
		int rows = com.summy.reliquary.client.SoulHeartOverlay.vanillaHeartGridRows(maxHealth, absorption);
		int spacing = com.summy.reliquary.client.SoulHeartOverlay.vanillaHeartGridRowSpacing(maxHealth, absorption);
		int top = com.summy.reliquary.client.SoulHeartOverlay.vanillaHeartGridTopY(screenHeight, maxHealth, absorption);
		// 原版网格从最上面那一排往下排：top、top + spacing、……（最下面一排是 height - 39）
		for (int row = 0; row < rows; row++) {
			if (ourY == top + row * spacing) {
				return false;
			}
		}
		return armorPoints <= 0 || ourY != top - 10;
	}

	/** 脱掉玩家的**所有** Curios 饰品（池子 / 伤害类用例的干净起点） */
	private static void stripAllCurios(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			for (var entry : handler.getCurios().entrySet()) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					handler.setEquippedCurio(entry.getKey(), index, ItemStack.EMPTY);
				}
			}
		});
	}

	/** 注视判定：射线命中最近一具、被方块挡住不算、转向后不算（与"思想"共用同一实现） */
	/** 把玩家正前方的通道清空（高度 ±2 格），避免测试场地把视线射线挡住 */
	private static void clearGazePath(ServerPlayer player, double length) {
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		double eyeY = player.getEyeY();
		for (double step = 1.0D; step <= length; step += 1.0D) {
			net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
					player.getX(), eyeY, player.getZ() + step);
			for (int dy = -2; dy <= 2; dy++) {
				if (!level.getBlockState(pos.above(dy)).isAir()) {
					level.setBlockAndUpdate(pos.above(dy),
							net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	private static void checkGazeLook(ServerPlayer player) {
		prepareBarePlayer(player);
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		// 清掉附近残留的测试生物，保证"射线唯一目标"（否则可能先命中别的实体）
		for (LivingEntity other : new java.util.ArrayList<>(level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(40.0D), entity -> entity != player))) {
			other.discard();
		}
		player.setYRot(0.0F);
		player.setXRot(0.0F);
		// 清出一条"正前方"的空气通道（测试场地可能被方块包住，射线会被立刻挡住）
		clearGazePath(player, 12.0D);
		net.minecraft.world.entity.animal.Cow cow = net.minecraft.world.entity.EntityType.COW.create(level);
		if (cow == null) {
			log("注视判定：无法生成测试牛");
			return;
		}
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ() + 10.0D;
		cow.moveTo(x, y, z);
		cow.setNoAi(true);
		level.addFreshEntity(cow);
		net.minecraft.world.entity.LivingEntity looked = com.summy.reliquary.effect.GazeLook.lookedAt(player, 24.0D);
		boolean front = looked != null && looked.getUUID().equals(cow.getUUID());
		String diagnose = "（诊断：眼=" + String.format("%.1f/%.1f/%.1f", player.getEyePosition().x,
				player.getEyePosition().y, player.getEyePosition().z) + "、牛="
				+ String.format("%.1f/%.1f/%.1f", cow.getX(), cow.getY(), cow.getZ()) + "、命中="
				+ (looked == null ? "null" : looked.getName().getString()) + "）";

		// 在中间砌一堵墙 → 被挡住就不算
		net.minecraft.core.BlockPos wall = net.minecraft.core.BlockPos.containing(x, y + 1.0D, player.getZ() + 5.0D);
		level.setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		level.setBlockAndUpdate(wall.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		boolean blocked = com.summy.reliquary.effect.GazeLook.lookedAt(player, 24.0D) == null;
		level.setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(wall.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

		// 转过身去 → 不再判定
		player.setYRot(180.0F);
		boolean turnedAway = com.summy.reliquary.effect.GazeLook.lookedAt(player, 24.0D) == null;
		player.setYRot(0.0F);
		cow.discard();
		log("注视判定：正前方命中=" + front + "（应 true）、隔墙不算=" + blocked + "（应 true）、"
				+ "转身不算=" + turnedAway + "（应 true）；与「思想」共用 GazeLook，行为一致" + diagnose);
	}

	/** 玄秘魔眼：门槛 / 配方门禁 / 继承 / 恐惧 / 圣心免疫减益但照吃增伤 */
	private static void checkOccultEye(ServerPlayer player) {
		// ① 门槛三态
		resetDemonPactState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		boolean noDemon = !canEquipBlessing(player, SummyReliquary.OCCULT_EYE.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 600.0D);
		boolean notEnough = !canEquipBlessing(player, SummyReliquary.OCCULT_EYE.get());
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 700.0D);
		boolean allowed = canEquipBlessing(player, SummyReliquary.OCCULT_EYE.get());
		boolean wrongSlot = !canEquipIn(player, SummyReliquary.OCCULT_EYE.get(), ReliquarySlots.REVELATION);
		boolean gateRegistered = com.summy.reliquary.effect.EvilUnlock.gatedRecipes()
				.containsKey(SummyReliquary.id("occult_eye"));

		// ② 配方门禁（1.7.0 新表）：未解锁时"收走产物 + 退回 末影之眼×4 / 回响碎片×3 / 夜之幽魂×1 / 下界合金碎片×1"
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		player.getInventory().clearContent();
		net.minecraft.world.item.ItemStack crafted = new net.minecraft.world.item.ItemStack(
				SummyReliquary.OCCULT_EYE.get());
		player.getInventory().add(crafted.copy());
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
				player, crafted, player.getInventory()));
		int wraithBack = player.getInventory().countItem(SummyReliquary.NIGHT_WRAITH.get());
		int eyeBack = player.getInventory().countItem(Items.ENDER_EYE);
		int echoBack = player.getInventory().countItem(Items.ECHO_SHARD);
		int scrapBack = player.getInventory().countItem(Items.NETHERITE_SCRAP);
		int eyeLeft = player.getInventory().countItem(SummyReliquary.OCCULT_EYE.get());
		boolean gateBlocks = eyeLeft == 0 && wraithBack == 1 && eyeBack == 4 && echoBack == 3 && scrapBack == 1;

		// ③ 继承夜之幽魂：移速 +20% 与创造飞行
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 700.0D);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		player.getInventory().clearContent();
		prepareBarePlayer(player);
		double baseSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.OCCULT_EYE.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double eyeSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		boolean flight = player.getAbilities().mayfly;

		// ④ 恐惧：注视到的目标被施加恐惧（并每秒携带四个减益）
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		// 同样先清掉附近残留生物，并把测试僵尸挪到**正前方**（注视判定是一条射线，不是范围）
		for (LivingEntity other : new java.util.ArrayList<>(level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(40.0D), entity -> entity != player))) {
			other.discard();
		}
		Zombie target = spawnHolyLightTarget(player, 8.0D);
		if (target == null) {
			log("玄秘魔眼：无法生成测试僵尸");
			return;
		}
		target.teleportTo(player.getX(), player.getY(), player.getZ() + 8.0D);
		target.setNoAi(true);
		player.setYRot(0.0F);
		player.setXRot(0.0F);
		clearGazePath(player, 12.0D);
		com.summy.reliquary.effect.OccultEye.reset();
		com.summy.reliquary.effect.OccultEye.tickPlayer(player);
		boolean feared = target.hasEffect(SummyReliquary.FEAR.get());
		SummyReliquary.FEAR.get().applyEffectTick(target, 0);
		// 1.6.6：这里要的是原版「黑暗」（DARKNESS）—— 1.6.5 误写成了「失明」（BLINDNESS）
		boolean darkness = target.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)
				&& !target.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
		boolean subEffects = darkness
				&& target.getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN) != null
				&& target.getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN).getAmplifier()
						== com.summy.reliquary.config.ReliquaryConfig.fearMiningFatigueAmplifier()
				&& target.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) != null
				&& target.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN).getAmplifier()
						== com.summy.reliquary.config.ReliquaryConfig.fearSlownessAmplifier()
				&& target.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS) != null
				&& target.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS).getAmplifier()
						== com.summy.reliquary.config.ReliquaryConfig.fearWeaknessAmplifier();

		// ⑤ 圣心：只免疫恐惧减益（canFear），不免疫注视增伤
		boolean zombieFearable = com.summy.reliquary.effect.OccultEye.canFear(target);
		boolean playerFearable = com.summy.reliquary.effect.OccultEye.canFear(player);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		boolean playerImmuneWithSacredHeart = !com.summy.reliquary.effect.OccultEye.canFear(player);
		// 1.6.7：神性同步圣心的恐惧免疫
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		boolean playerImmuneWithGodhead = !com.summy.reliquary.effect.OccultEye.canFear(player);
		unequip(player, ReliquarySlots.REVELATION);
		// 增伤：与"目标身上有没有恐惧"无关 —— 清掉恐惧后照样 ×1.3
		target.removeEffect(SummyReliquary.FEAR.get());
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.OCCULT_EYE.get());
		var hurt = new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
				player.damageSources().playerAttack(player), 10.0F);
		com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(hurt);
		float boosted = hurt.getAmount();
		player.setYRot(180.0F);
		var turned = new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
				player.damageSources().playerAttack(player), 10.0F);
		com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(turned);
		float plain = turned.getAmount();
		player.setYRot(0.0F);

		// ⑤b 增伤覆盖面（1.6.6 补断言）：近战 / 箭矢 / 普通魔法来源都吃 ×1.3；本模组"定值真伤"不吃
		var magic = new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
				player.damageSources().indirectMagic(player, player), 10.0F);
		com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(magic);
		float magicAmount = magic.getAmount();
		net.minecraft.world.entity.projectile.Arrow arrow =
				net.minecraft.world.entity.EntityType.ARROW.create(player.serverLevel());
		float arrowAmount = -1.0F;
		if (arrow != null) {
			arrow.setOwner(player);
			var arrowHurt = new net.minecraftforge.event.entity.living.LivingHurtEvent(target,
					player.damageSources().arrow(arrow, player), 10.0F);
			com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(arrowHurt);
			arrowAmount = arrowHurt.getAmount();
			arrow.discard();
		}
		var registry = player.serverLevel().registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
		var exactSource = new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(
				net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
						SummyReliquary.id("revelation_light"))));
		var exact = new net.minecraftforge.event.entity.living.LivingHurtEvent(target, exactSource, 10.0F);
		com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(exact);
		float exactAmount = exact.getAmount();

		target.discard();
		log("玄秘魔眼：无标记被拦=" + noDemon + "、邪恶 600 被拦=" + notEnough + "、邪恶 700 放行=" + allowed
				+ "、装错栏位被拦=" + wrongSlot + "（都应 true）、配方已登记门禁=" + gateRegistered + "（应 true）；"
				+ "未解锁强合被拦（退回 末影之眼×" + eyeBack + " / 回响碎片×" + echoBack + " / 夜之幽魂×"
				+ wraithBack + " / 下界合金碎片×" + scrapBack + "、产物残留=" + eyeLeft
				+ "）=" + gateBlocks + "（应 true）；移速 " + baseSpeed + " → " + eyeSpeed + "（应 ×1.2 = "
				+ (baseSpeed * 1.2D) + "）、飞行=" + flight + "（应 true）；注视目标被施加恐惧=" + feared
				+ "（应 true）、附带「黑暗」而非「失明」=" + darkness + "（应 true）、四个减益等级正确=" + subEffects
				+ "（应 true）、僵尸可恐惧=" + zombieFearable
				+ "、玩家可恐惧=" + playerFearable + "、戴圣心后不可恐惧=" + playerImmuneWithSacredHeart
				+ "（应 true）、戴神性后不可恐惧=" + playerImmuneWithGodhead + "（应 true）；注视增伤 " + plain
				+ " → " + boosted + "（应 ×1.3 = " + (plain * 1.3F)
				+ "，且与目标有没有恐惧无关）、魔法来源 " + magicAmount + "、箭矢 " + arrowAmount + "（都应为 "
				+ (10.0F * 1.3F) + " = 全伤害生效）、定值真伤（启示之光）" + exactAmount + "（应为 10 = 不吃加成）");
		resetDemonPactState(player);
	}

	// ==================== 1.6.9：魂心/黑心补满对齐 + 成就页扩充 + 恶魔线上色 + 日志字段 ====================

	/**
	 * 魂心补满周期（1.6.9）。
	 *
	 * <p>根因：{@code SoulShield.tickPlayer} 以前被**每 tick**调用，却按"秒"递减计时器 →
	 * "每 30 秒"实际只有 30 tick（≈1.5 秒，1.6.2 起就存在）；现在它只在每秒分支里被调用。
	 */
	private static void checkSoulHeartRefreshCycle(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		prepareBarePlayer(player);
		com.summy.reliquary.effect.SoulShield.clear();
		// ① 刚装上 → 补满（装备状态变化的兜底路径）
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_SOUL.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		com.summy.reliquary.effect.SoulShield.tickPlayer(player);
		double capacity = com.summy.reliquary.effect.SoulShield.capacityFor(player);
		double onEquip = com.summy.reliquary.effect.SoulShield.points(player);
		// ② 打空后：29 次「每秒 tick」不补、第 30 次才补
		com.summy.reliquary.effect.SoulShield.setPoints(player, 0.0D);
		com.summy.reliquary.effect.SoulShield.setRefreshTimer(player, 30);
		for (int index = 0; index < 29; index++) {
			com.summy.reliquary.effect.SoulShield.tickPlayer(player);
		}
		double beforeCycle = com.summy.reliquary.effect.SoulShield.points(player);
		com.summy.reliquary.effect.SoulShield.tickPlayer(player);
		double afterCycle = com.summy.reliquary.effect.SoulShield.points(player);
		// ③ 破碎（池子从 >0 归零）→ 计时器写回完整周期：紧接着的一次「每秒 tick」不应补
		com.summy.reliquary.effect.SoulShield.setPoints(player, capacity);
		com.summy.reliquary.effect.SoulShield.consume(player, capacity);
		com.summy.reliquary.effect.SoulShield.tickPlayer(player);
		double afterShatter = com.summy.reliquary.effect.SoulShield.points(player);
		boolean ok = Math.abs(onEquip - capacity) < 1.0E-4D && Math.abs(beforeCycle) < 1.0E-4D
				&& Math.abs(afterCycle - capacity) < 1.0E-4D && Math.abs(afterShatter) < 1.0E-4D;
		log("魂心周期（1.6.9 起只在每秒分支 tick）：刚装上=" + onEquip + "（应 " + capacity
				+ " = 补满）、29 次每秒 tick 后=" + beforeCycle + "（应 0 = 不补）、第 30 次=" + afterCycle
				+ "（应 " + capacity + "）、破碎后再 tick 一次=" + afterShatter
				+ "（应 0 = 计时已回满）→ " + ok + "（应 true）");
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		unequip(player, ReliquarySlots.SPIRIT_ALTAR);
		com.summy.reliquary.effect.SoulShield.tickPlayer(player);
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
	}

	/** 黑心补满三处对齐（1.6.9）：刚戴上补满、碎裂重置、登录/换维度/复活补满；摘下不清空 */
	private static void checkBlackHeartRefillAlign(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		prepareBarePlayer(player);
		// 清掉附近生物：碎裂伤害会把它们打死，留下尸体会干扰后续用例
		for (LivingEntity other : new java.util.ArrayList<>(player.serverLevel().getEntitiesOfClass(
				LivingEntity.class, player.getBoundingBox().inflate(40.0D), entity -> entity != player))) {
			other.discard();
		}
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		// ① 刚戴上撒旦圣经 → 下一个「每秒 tick」直接补满
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double max = com.summy.reliquary.effect.DemonPact.blackHeartMaxPoints(player);
		double onEquip = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		// ② 打空后：29 次每秒 tick 不补、第 30 次补
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 0.0D);
		for (int index = 0; index < 29; index++) {
			com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		}
		double beforeCycle = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterCycle = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		// ③ 碎裂 → 计时回满：紧接着的一次每秒 tick 不补
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
		com.summy.reliquary.effect.DemonPact.consumeBlackHearts(player, 1.0D);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterShatter = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		// ④ 登录 / 换维度 / 复活：池 ≤0 且戴圣经 → 补满
		com.summy.reliquary.effect.DemonPact.onJoin(player);
		double afterJoin = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		// ⑤ 摘下圣经不清空池子（只受上限夹取：戴圣经 6 → 不戴 4）
		unequip(player, ReliquarySlots.SOUL_SEAL);
		com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 4.0D);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		double afterUnequip = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		com.summy.reliquary.effect.DemonPact.onJoin(player);
		double joinWithoutBible = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		boolean ok = Math.abs(onEquip - max) < 1.0E-4D && Math.abs(beforeCycle) < 1.0E-4D
				&& Math.abs(afterCycle - max) < 1.0E-4D && Math.abs(afterShatter) < 1.0E-4D
				&& Math.abs(afterJoin - max) < 1.0E-4D && Math.abs(afterUnequip - 4.0D) < 1.0E-4D
				&& Math.abs(joinWithoutBible - 4.0D) < 1.0E-4D;
		log("黑心对齐（1.6.9）：上限=" + max + "、刚戴上=" + onEquip + "（应 " + max + " = 补满）、"
				+ "29 次每秒 tick 后=" + beforeCycle + "（应 0）、第 30 次=" + afterCycle + "（应 " + max
				+ "）、碎裂后再 tick=" + afterShatter + "（应 0 = 计时回满）、onJoin 补满=" + afterJoin
				+ "（应 " + max + "）；摘下圣经后池=" + afterUnequip + "（应 4 = 不清空）、不戴时 onJoin 不补="
				+ joinWithoutBible + "（应 4）→ " + ok + "（应 true）");
		resetDemonPactState(player);
		unequip(player, ReliquarySlots.SOUL_SEAL);
	}

	/** 1.6.9：两件隐藏图标物品（二元之像 / 你的灵魂）与缺键补齐 */
	private static void checkIconItems() {
		Minecraft client = Minecraft.getInstance();
		boolean registered = SummyReliquary.DUALITY_STAT.isPresent() && SummyReliquary.YOUR_SOUL.isPresent();
		boolean textures = hasResource(client, "textures/item/duality_stat.png")
				&& hasResource(client, "textures/item/your_soul.png");
		boolean models = hasResource(client, "models/item/duality_stat.json")
				&& hasResource(client, "models/item/your_soul.json");
		boolean lang = translated("item.summy-reliquary.duality_stat")
				&& translated("item.summy-reliquary.your_soul")
				&& translated("item.summy-reliquary.error")
				&& translated("item.summy-reliquary.purity");
		// 1.7.5：两把仪式匕首的贴图 / 模型 / 语言键
		boolean daggerResources = hasResource(client, "textures/item/sacrificial_dagger.png")
				&& hasResource(client, "textures/item/dark_arts.png")
				&& hasResource(client, "models/item/sacrificial_dagger.json")
				&& hasResource(client, "models/item/dark_arts.json")
				&& translated("item.summy-reliquary.sacrificial_dagger")
				&& translated("item.summy-reliquary.dark_arts");
		// 1.7.6：两把匕首必须走 item/handheld（原版剑的手持渲染），否则会平放在手上
		boolean daggerHandheld = resourceText(client, SummyReliquary.NAMESPACE,
				"models/item/sacrificial_dagger.json").contains("item/handheld")
				&& resourceText(client, SummyReliquary.NAMESPACE,
						"models/item/dark_arts.json").contains("item/handheld");
		// 1.7.8：两把矛都是"单模型 32×32 + 矛式 display 父模型"（不再有手持专用图 / in_gui 谓词）；
		// 1.7.9：为了修"举矛蓄力时矛头朝后"，主模型重新带 overrides → 指向 *_using（Y 轴取反）模型
		String holySpearModel = resourceText(client, SummyReliquary.NAMESPACE, "models/item/holy_spear.json");
		String seraphSpearModel = resourceText(client, SummyReliquary.NAMESPACE, "models/item/seraph_spear.json");
		boolean spearRender = hasResource(client, "textures/item/holy_spear.png")
				&& hasResource(client, "textures/item/seraph_spear.png")
				&& hasResource(client, "models/item/spear_in_hand.json")
				&& hasResource(client, "models/item/holy_spear_using.json")
				&& hasResource(client, "models/item/seraph_spear_using.json")
				&& !hasResource(client, "models/item/holy_spear_in_hand.json")
				&& !hasResource(client, "textures/item/holy_spear_in_hand.png")
				&& holySpearModel.contains("spear_in_hand") && holySpearModel.contains("overrides")
				&& holySpearModel.contains("holy_spear_using")
				&& seraphSpearModel.contains("spear_in_hand") && seraphSpearModel.contains("overrides")
				&& seraphSpearModel.contains("seraph_spear_using")
				&& translated("item.summy-reliquary.holy_spear")
				&& translated("item.summy-reliquary.seraph_spear")
				&& SummyReliquary.THROWN_SPEAR.isPresent();
		// 没有配方（按配方 id 与配方产物双重确认）
		MinecraftServer server = client.getSingleplayerServer();
		boolean noRecipe = true;
		if (server != null) {
			noRecipe = server.getRecipeManager().byKey(SummyReliquary.id("duality_stat")).isEmpty()
					&& server.getRecipeManager().byKey(SummyReliquary.id("your_soul")).isEmpty();
			for (var recipe : server.getRecipeManager().getRecipes()) {
				ItemStack result = recipe.getResultItem(server.registryAccess());
				if (result.is(SummyReliquary.DUALITY_STAT.get()) || result.is(SummyReliquary.YOUR_SOUL.get())) {
					noRecipe = false;
				}
			}
		}
		// 注册物品总数与"隐藏图标物品"件数
		int registeredItems = 0;
		for (var key : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
			if (SummyReliquary.NAMESPACE.equals(key.getNamespace())) {
				registeredItems++;
			}
		}
		int iconItems = 0;
		for (var icon : new net.minecraftforge.registries.RegistryObject[]{SummyReliquary.TRINITY,
				SummyReliquary.ERROR_ICON, SummyReliquary.PURITY_ICON, SummyReliquary.DUALITY_STAT,
				SummyReliquary.YOUR_SOUL}) {
			if (icon.isPresent()) {
				iconItems++;
			}
		}
		// 1.7.0：三位一体已转正（有配方 + 进创造页），所以"纯图标物品"只剩 4 件
		int pureIconCount = 0;
		for (var icon : new net.minecraftforge.registries.RegistryObject[]{SummyReliquary.ERROR_ICON,
				SummyReliquary.PURITY_ICON, SummyReliquary.DUALITY_STAT, SummyReliquary.YOUR_SOUL}) {
			if (icon.isPresent()) {
				pureIconCount++;
			}
		}
		// 创造页不含那 4 件纯图标物品，但**包含**三位一体（页面还没构建时只报告、不算失败）
		var tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
				.get(SummyReliquary.id("main"));
		java.util.Collection<ItemStack> display = tab == null ? java.util.List.of() : tab.getDisplayItems();
		boolean tabExcludesIcons = display.stream().noneMatch(stack -> stack.is(SummyReliquary.ERROR_ICON.get())
				|| stack.is(SummyReliquary.PURITY_ICON.get())
				|| stack.is(SummyReliquary.DUALITY_STAT.get()) || stack.is(SummyReliquary.YOUR_SOUL.get()));
		// 1.7.10：注册物品 45 → 50（1.7.5 两把匕首 + 1.7.7 圣光短矛 + 1.7.8 炽天使之枪 + 1.7.10 金刀片）
		// 隐藏图标物品仍是 5、纯图标物品仍是 4
		boolean counts = registeredItems == 50 && iconItems == 5 && pureIconCount == 4 && tabExcludesIcons;
		log("图标物品（1.6.9）：duality_stat/your_soul 已注册=" + registered + "（应 true）、贴图=" + textures
				+ "、模型=" + models + "（都应 true）、语言键（含 error/purity 补键）=" + lang
				+ "（应 true）、没有配方=" + noRecipe + "（应 true）；仪式匕首素材（1.7.5）=" + daggerResources
				+ "（应 true）、手持模型走 item/handheld=" + daggerHandheld + "（应 true）；注册物品数=" + registeredItems
				+ "（应 50 = 1.7.5 的 47 + 圣光短矛 + 炽天使之枪 + 1.7.10 金刀片）、隐藏图标物品=" + iconItems
				+ "（应 5，1.7.0 起其中三位一体已转正）、纯图标物品="
				+ pureIconCount + "（应 4）、创造页不含纯图标物品=" + tabExcludesIcons
				+ "（应 true，页面条目 " + display.size() + " 个 = 1.7.1 的 41 项，含恶魔王冠）→ 计数全对="
				+ counts + "（应 true）；两把矛的矛式渲染（1.7.8）= " + spearRender + "（应 true）");
	}

	/** 资源包内是否有该文件（相对命名空间根） */
	private static boolean hasResource(Minecraft client, String path) {
		return client.getResourceManager()
				.getResource(new net.minecraft.resources.ResourceLocation(SummyReliquary.NAMESPACE, path))
				.isPresent();
	}

	/** 1.7.6：读取资源包内某个文件的文本（找不到 / 读不动就返回空串） */
	private static String resourceText(Minecraft client, String namespace, String path) {
		net.minecraft.resources.ResourceLocation id =
				new net.minecraft.resources.ResourceLocation(namespace, path);
		for (net.minecraft.server.packs.resources.Resource resource
				: client.getResourceManager().getResourceStack(id)) {
			try (java.io.BufferedReader reader = resource.openAsReader()) {
				StringBuilder builder = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					builder.append(line);
				}
				return builder.toString();
			} catch (Exception ignored) {
				// 某一份读不动就当它没有
			}
		}
		return "";
	}

	/** 成就的 frame（task / goal / challenge） */
	private static String frameOf(MinecraftServer server, String path) {
		var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(path));
		if (advancement == null || advancement.getDisplay() == null) {
			return "无";
		}
		return advancement.getDisplay().getFrame().getName();
	}

	/** 成就描述的颜色（数组描述里第一段就是根组件） */
	private static String advancementColor(MinecraftServer server, String path) {
		var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(path));
		if (advancement == null || advancement.getDisplay() == null) {
			return "无";
		}
		return colorOf(advancement.getDisplay().getDescription());
	}

	/** 1.6.9 成就页：新根「遗物：七罪」+ 9 条恶魔线成就（前置 / frame / 隐藏 / 图标 / 文案） */
	private static void checkAdvancementGraph(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		boolean root = advancementIcon(server, "reliquary").equals("duality_stat")
				&& !advancementHidden(server, "reliquary")
				&& parentOf(server, "reliquary").equals("无")
				&& frameOf(server, "reliquary").equals("task")
				&& translated("advancements.summy-reliquary.reliquary.title");
		boolean sinnerReparented = parentOf(server, "sinner").equals("reliquary");
		// 既有 10 条：前置链一个都没动（只把根挂到新根下面）
		boolean legacyParents = parentOf(server, "sinner").equals("reliquary")
				&& parentOf(server, "pure").equals("sinner")
				// 1.6.10：「三位一体」的前置改为「荫蔽」
				&& parentOf(server, "trinity").equals("shade")
				&& parentOf(server, "revelation").equals("trinity")
				&& parentOf(server, "sinless").equals("sinner")
				&& parentOf(server, "flawless").equals("sinner")
				&& parentOf(server, "unforgivable").equals("sinner")
				&& parentOf(server, "bread_and_fish").equals("pure")
				&& parentOf(server, "heart").equals("revelation")
				&& parentOf(server, "god").equals("heart");
		String[][] table = {
				{"deal", "unforgivable", "the_pact", "task", "false"},
				{"fresh_soul", "deal", "your_soul", "task", "false"},
				{"dark_tome", "deal", "satanic_bible", "task", "false"},
				{"beast_mark", "fresh_soul", "the_mark", "task", "false"},
				{"nightmare", "beast_mark", "night_wraith", "task", "false"},
				{"demon_flame", "nightmare", "brimstone", "challenge", "false"},
				{"evil_eye", "nightmare", "occult_eye", "challenge", "true"},
				{"demon_king", "demon_flame", "abyss_lord", "challenge", "false"},
				{"finale", "demon_king", "abaddon", "challenge", "false"}
		};
		StringBuilder wrong = new StringBuilder();
		StringBuilder texts = new StringBuilder();
		boolean tableOk = true;
		boolean langOk = true;
		for (String[] row : table) {
			boolean ok = parentOf(server, row[0]).equals(row[1])
					&& advancementIcon(server, row[0]).equals(row[2])
					&& frameOf(server, row[0]).equals(row[3])
					&& String.valueOf(advancementHidden(server, row[0])).equals(row[4])
					&& advancementColor(server, row[0]).equals("#C03030");
			tableOk &= ok;
			if (!ok) {
				wrong.append(row[0]).append(' ');
			}
			String titleKey = "advancements.summy-reliquary." + row[0] + ".title";
			String descKey = "advancements.summy-reliquary." + row[0] + ".description";
			langOk &= translated(titleKey) && translated(descKey);
			if (row[0].equals("deal")) {
				texts.append('「').append(Component.translatable(titleKey).getString()).append('：')
						.append(Component.translatable(descKey).getString()).append('」');
			}
		}
		int total = 0;
		for (var advancement : server.getAdvancements().getAllAdvancements()) {
			if (SummyReliquary.NAMESPACE.equals(advancement.getId().getNamespace())) {
				total++;
			}
		}
		// 1.6.10：天使线两条 + 创世纪（描述淡金 #FFE4B5）
		String[][] newer = {
				{"light", "pure", "holy_light", "task", "false"},
				{"shade", "light", "holy_mantle", "task", "false"},
				{"genesis", "sinner", "genesis", "challenge", "true"}
		};
		StringBuilder newerWrong = new StringBuilder();
		boolean newerOk = true;
		for (String[] row : newer) {
			boolean ok = parentOf(server, row[0]).equals(row[1])
					&& advancementIcon(server, row[0]).equals(row[2])
					&& frameOf(server, row[0]).equals(row[3])
					&& String.valueOf(advancementHidden(server, row[0])).equals(row[4])
					&& advancementColor(server, row[0]).equals("#FFE4B5")
					&& translated("advancements.summy-reliquary." + row[0] + ".title")
					&& translated("advancements.summy-reliquary." + row[0] + ".description");
			newerOk &= ok;
			if (!ok) {
				newerWrong.append(row[0]).append(' ');
			}
		}
		log("天使线 3 条新成就（1.6.10）：表格（前置/frame/隐藏/图标/描述色 #FFE4B5/中英键）全对=" + newerOk
				+ "（应 true" + (newerWrong.length() == 0 ? "" : "，异常：" + newerWrong.toString().trim())
				+ "）；「光」=「" + Component.translatable("advancements.summy-reliquary.light.description")
						.getString() + "」、「荫蔽」=「"
				+ Component.translatable("advancements.summy-reliquary.shade.description").getString()
				+ "」、「亘古之初」=「"
				+ Component.translatable("advancements.summy-reliquary.genesis.description").getString() + "」");
		log("成就页（1.6.9）：新根「" + Component.translatable("advancements.summy-reliquary.reliquary.title")
						.getString() + "」图标=" + advancementIcon(server, "reliquary") + "（应 duality_stat）、"
				+ "sinner 挂到新根=" + sinnerReparented + "（应 true）、根自身=" + root + "（应 true）；"
				+ "既有 10 条前置未动=" + legacyParents + "（应 true）");
		log("恶魔线 9 条成就：表格（前置/frame/隐藏/图标/描述色）全对=" + tableOk + "（应 true"
				+ (wrong.length() == 0 ? "" : "，异常：" + wrong.toString().trim()) + "）、中英文案齐全="
				+ langOk + "（应 true）；本模组成就总数=" + total
				+ "（应 23 = 新根 + 恶魔线 9 条 + 天使线 3 条 + 既有 10 条）、"
				+ "示例文案" + texts);
	}

	/** 1.6.9 成就触发链：真签约 / 献祭 / 持有物兜底 / 魔眼只认通过门禁的合成 */
	private static void checkAdvancementTriggers(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DelayedChat.forget(player);

		// 先把这 9 条进度撤销：成就是**跟着世界存档持久化**的，不去掉的话第二次跑自检时
		// "被拦下的强合不算" 这类"要求尚未完成"的断言会假失败。
		for (String id : new String[]{"deal", "fresh_soul", "dark_tome", "beast_mark", "nightmare",
				"demon_flame", "evil_eye", "demon_king", "finale",
				// 1.6.10 的三条
				"light", "shade", "genesis"}) {
			var advancement = server.getAdvancements().getAdvancement(SummyReliquary.id(id));
			if (advancement != null) {
				player.getAdvancements().revoke(advancement, "event");
			}
		}

		// ① 成交：自愈补发不算，真签约才触发
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		boolean selfHealNoFire = !advancementDone(player, "deal");
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		boolean dealDone = advancementDone(player, "deal");

		// ② 新鲜灵魂：完成村民献祭
		com.summy.reliquary.effect.PlayerFlags.setSacrificeDone(player, false);
		com.summy.reliquary.effect.DelayedChat.forget(player);
		net.minecraft.world.entity.npc.Villager villager =
				EntityType.VILLAGER.create(player.serverLevel());
		boolean sacrificeDone = false;
		if (villager == null) {
			log("成就触发：无法创建村民，跳过「新鲜灵魂」");
		} else {
			villager.moveTo(player.getX() + 2.0D, player.getY(), player.getZ(), 0.0F, 0.0F);
			player.serverLevel().addFreshEntity(villager);
			com.summy.reliquary.effect.DemonPact.onDeath(villager, player);
			villager.discard();
			sacrificeDone = advancementDone(player, "fresh_soul");
		}
		com.summy.reliquary.effect.DelayedChat.forget(player);

		// ③ 获取类（持有物兜底：合成 / 拾取 / 指令给都算）
		String[][] obtained = {
				{"satanic_bible", "dark_tome"},
				{"the_mark", "beast_mark"},
				{"night_wraith", "nightmare"},
				{"brimstone", "demon_flame"},
				{"abyss_lord", "demon_king"},
				{"abaddon", "finale"}
		};
		StringBuilder obtainedDetail = new StringBuilder();
		boolean obtainedOk = true;
		player.getInventory().clearContent();
		for (String[] pair : obtained) {
			player.getInventory().add(new ItemStack(itemById(pair[0])));
			com.summy.reliquary.advancement.ItemObtained.tick(player);
			boolean done = advancementDone(player, pair[1]);
			obtainedOk &= done;
			obtainedDetail.append(pair[1]).append('=').append(done).append(' ');
		}

		// ④ 魔眼：被门禁拦下的强合 / 直接持有都不算，只有通过 700 门禁的升级才算
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		ItemStack blocked = new ItemStack(SummyReliquary.OCCULT_EYE.get());
		player.getInventory().add(blocked.copy());
		com.summy.reliquary.effect.EvilRecipeGate.onCrafted(new PlayerEvent.ItemCraftedEvent(
				player, blocked, player.inventoryMenu.getCraftSlots()));
		boolean blockedNoFire = !advancementDone(player, "evil_eye");
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(SummyReliquary.OCCULT_EYE.get()));
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		boolean holdingNoFire = !advancementDone(player, "evil_eye");
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player)
						| com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.bit());
		ItemStack upgraded = new ItemStack(SummyReliquary.OCCULT_EYE.get());
		player.getInventory().add(upgraded.copy());
		com.summy.reliquary.effect.EvilRecipeGate.onCrafted(new PlayerEvent.ItemCraftedEvent(
				player, upgraded, player.inventoryMenu.getCraftSlots()));
		boolean upgradedFire = advancementDone(player, "evil_eye");

		log("成就触发（1.6.9）：自愈补发不触发「成交」=" + selfHealNoFire + "（应 true）、真签约触发="
				+ dealDone + "（应 true）、献祭触发「新鲜灵魂」=" + sacrificeDone + "（应 true）；"
				+ "获取类兜底=" + obtainedDetail.toString().trim() + " → " + obtainedOk + "（应 true）");
		log("成就触发·魔眼：门禁拦下的强合不算=" + blockedNoFire + "（应 true）、直接持有不算="
				+ holdingNoFire + "（应 true）、通过 700 门禁的升级触发=" + upgradedFire + "（应 true）");
		// ⑤ 1.6.10 的三条：持有圣光 → 光；持有神圣斗篷 → 荫蔽；真正用掉创世纪 → 亘古之初
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(SummyReliquary.HOLY_LIGHT.get()));
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		boolean lightDone = advancementDone(player, "light");
		player.getInventory().add(new ItemStack(SummyReliquary.HOLY_MANTLE.get()));
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		boolean shadeDone = advancementDone(player, "shade");
		// 创世纪：真的走一遍"点「是」"的路径
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.DemonPact.revoke(player);
		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		com.summy.reliquary.item.GenesisItem.resetKnownForTest(player);
		player.getInventory().add(new ItemStack(SummyReliquary.GENESIS.get()));
		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
				"summyreliquary genesis confirm");
		boolean genesisDone = advancementDone(player, "genesis");
		log("成就触发·1.6.10 三条：持有圣光 → 「光」=" + lightDone + "（应 true）、持有神圣斗篷 → 「荫蔽」="
				+ shadeDone + "（应 true）、真正用掉创世纪 → 「亘古之初」=" + genesisDone + "（应 true）");
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.DelayedChat.forget(player);
	}

	/** 1.6.9：恶魔线 9 件饰品的风味行统一深红（文案不变），引号行 / 括注行照旧 */
	private static void checkDemonFlavorColors() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		String[][] flavor = {
				{"the_pact", "tagline"},
				{"ceremonial_robes", "tagline"},
				{"satanic_bible", "tagline.1"},
				{"satanic_bible", "tagline.2"},
				{"satanic_bible", "tagline.3"},
				{"vengeful_spirit", "tagline.1"},
				{"vengeful_spirit", "tagline.2"},
				{"the_mark", "tagline.1"},
				{"night_wraith", "tagline.1"},
				{"night_wraith", "tagline.2"},
				{"brimstone", "tagline.1"},
				{"brimstone", "tagline.2"},
				{"occult_eye", "tagline.1"},
				{"occult_eye", "tagline.2"},
				{"abyss_lord", "tagline.1"},
				{"abyss_lord", "tagline.2"},
				// 1.7.1：恶魔王冠
				{"devil_crown", "tagline.1"},
				{"devil_crown", "tagline.2"}
		};
		StringBuilder bad = new StringBuilder();
		boolean flavorOk = true;
		for (String[] row : flavor) {
			String key = "item.summy-reliquary." + row[0] + "." + row[1];
			String text = Component.translatable(key).getString();
			var line = tooltipLine(client, itemById(row[0]), text);
			boolean ok = line != null && "#C03030".equals(colorOf(line));
			flavorOk &= ok;
			if (!ok) {
				bad.append(row[0]).append('.').append(row[1]).append(' ');
			}
		}
		// 引号行：仍是各自的"恶魔话语"色 + 斜体（未被风味行上色波及）
		// 契约的引号行走 DemonPact.CHAT_RED（#8B0000），夜之幽魂 / 硫磺火走 #B22222
		var pactQuote = tooltipLine(client, SummyReliquary.THE_PACT.get(),
				Component.translatable("item.summy-reliquary.the_pact.quote").getString());
		var wraithQuote = tooltipLine(client, SummyReliquary.NIGHT_WRAITH.get(),
				Component.translatable("item.summy-reliquary.night_wraith.quote").getString());
		boolean quotes = pactQuote != null && "#8B0000".equals(colorOf(pactQuote))
				&& pactQuote.getStyle().isItalic() && wraithQuote != null
				&& "#B22222".equals(colorOf(wraithQuote)) && wraithQuote.getStyle().isItalic();
		// 括注行 / 属性行：仍走原来的灰 / 两段配色
		boolean narrative = "#AAAAAA".equals(colorOf(
				com.summy.reliquary.item.ReliquaryTooltips.statComponent(
						com.summy.reliquary.text.ReliquaryFaction.DEMON,
						"item.summy-reliquary.night_wraith.shift.2")));
		log("恶魔线上色（1.6.9；1.7.1 起含恶魔王冠共 10 件）：饰品风味行全部 #C03030=" + flavorOk + "（应 true"
				+ (bad.length() == 0 ? "" : "，异常：" + bad.toString().trim()) + "）、引号行仍是暗红斜体="
				+ quotes + "（应 true）、括注行仍是灰=" + narrative + "（应 true）");
	}

	/** 取某件物品提示里与给定文本相同的行（没有则 null） */
	private static Component tooltipLine(Minecraft client, Item item, String text) {
		for (Component line : new ItemStack(item).getTooltipLines(client.player,
				net.minecraft.world.item.TooltipFlag.Default.NORMAL)) {
			if (line.getString().equals(text)) {
				return line;
			}
		}
		return null;
	}

	/** 1.6.9：伤害池日志字段语义修正（prepare 打「估算血伤」，对账打「结算后吸收」） */
	private static void checkPoolLogFields() {
		String prepare = com.summy.reliquary.effect.DamagePools.prepareLogLine("Forge", "测试", "Dev",
				0.0F, 3.0F, 5.0F, 3.0D);
		String reconcile = com.summy.reliquary.effect.DamagePools.reconcileLogLine("Forge", "测试", "Dev",
				0.0F, 3.0F, 0.0F, 0.0F, 3.0D, 0.0D);
		boolean ok = prepare.contains("估算血伤=5.0") && !prepare.contains("结算后吸收")
				&& reconcile.contains("结算后吸收=0.0") && !reconcile.contains("估算血伤");
		log("伤害池日志字段（1.6.9）：prepare=「" + prepare + "」、对账=「" + reconcile + "」→ " + ok
				+ "（应 true）；协议=" + com.summy.reliquary.net.ReliquaryNetworking.protocolVersion()
				+ "（应 12 = 1.7.2 新增创世纪动画包）");
	}

	// ==================== 1.6.10：创世纪 / 启示属性 / 条件驱动发放 / 饰品联动 ====================

	/** 把客户端缓存按当前服务端数据刷一遍（自检里不等网络包，也顺带验证同步位的口径） */
	private static void refreshClientStateForTest(ServerPlayer player) {
		com.summy.reliquary.client.ReliquaryClientState.update(player.getUUID(),
				com.summy.reliquary.sin.SinManager.mask(player),
				com.summy.reliquary.sin.SinManager.redeemedMask(player),
				false, 0, 0,
				com.summy.reliquary.effect.PlayerFlags.clientFlags(player), 0);
	}

	/** 清空「灵台」栏位（1.6.10 自检里用来切换三件套 / 咒印） */
	private static void clearSpiritAltar(ServerPlayer player) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0, ItemStack.EMPTY));
	}

	/** 提示行里是否含有某个文本 */
	private static boolean tooltipHas(List<Component> lines, String key) {
		String text = Component.translatable(key).getString();
		return lines.stream().anyMatch(line -> line.getString().equals(text));
	}

	/** 创世纪：自动发放（神性 / 亚巴顿各 1）+ 门槛 + "获取前禁止查看" */
	private static void checkGenesisGrantAndVisibility(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		com.summy.reliquary.item.GenesisItem.resetGrantedForTest(player);
		com.summy.reliquary.item.GenesisItem.resetKnownForTest(player);

		// ① 门槛：天使 / 当前恶魔 / 曾签约 三者任一即可
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
		boolean noMark = com.summy.reliquary.item.GenesisItem.isQualified(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		boolean angelOk = com.summy.reliquary.item.GenesisItem.isQualified(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		boolean demonOk = com.summy.reliquary.item.GenesisItem.isQualified(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		boolean sealedOk = com.summy.reliquary.item.GenesisItem.isQualified(player);

		// ② 获取前禁止查看：未"知道"→ 提示只有 2 行且含「你还不知此为何物。」
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		refreshClientStateForTest(player);
		List<Component> unknown = new ItemStack(SummyReliquary.GENESIS.get())
				.getTooltipLines(player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		boolean unknownOnly = unknown.size() == 2
				&& tooltipHas(unknown, "item.summy-reliquary.genesis.unknown")
				&& !tooltipHas(unknown, "item.summy-reliquary.genesis.tagline.1");

		// ③ 发放：持有神性 → 1 个（再 tick 不重复）；持有亚巴顿 → 再 1 个；都不持 → 不发
		player.getInventory().add(new ItemStack(SummyReliquary.GODHEAD.get()));
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		int afterGodhead = countItemEverywhere(player, SummyReliquary.GENESIS.get());
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		int afterGodheadAgain = countItemEverywhere(player, SummyReliquary.GENESIS.get());
		player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(SummyReliquary.GODHEAD.get()), Integer.MAX_VALUE,
				player.inventoryMenu.getCraftSlots());
		player.getInventory().add(new ItemStack(SummyReliquary.ABADDON.get()));
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		int afterAbaddon = countItemEverywhere(player, SummyReliquary.GENESIS.get());
		player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(SummyReliquary.ABADDON.get()), Integer.MAX_VALUE,
				player.inventoryMenu.getCraftSlots());
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		int afterNone = countItemEverywhere(player, SummyReliquary.GENESIS.get());

		// ④ "知道"之后提示恢复完整（含 4 行风味）
		refreshClientStateForTest(player);
		List<Component> known = new ItemStack(SummyReliquary.GENESIS.get())
				.getTooltipLines(player, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		boolean fullTooltip = tooltipHas(known, "item.summy-reliquary.genesis.tagline.1")
				&& tooltipHas(known, "item.summy-reliquary.genesis.tagline.4")
				&& !tooltipHas(known, "item.summy-reliquary.genesis.unknown");

		log("创世纪·门槛（1.6.10）：无任何标记被拒=" + !noMark + "（应 true）、天使放行=" + angelOk
				+ "、只有恶魔标记也放行=" + demonOk + "、只有「曾签约」也放行=" + sealedOk + "（都应 true）");
		log("创世纪·发放（生存唯一途径）：持有神性 → " + afterGodhead + " 个（应 1）、再 tick 仍 "
				+ afterGodheadAgain + " 个（应 1 = 不重复）、再持亚巴顿 → " + afterAbaddon
				+ " 个（应 2 = 两条线各 1）、都不持时不再发（仍 " + afterNone + "，应 2）");
		log("创世纪·获取前禁止查看：未获取时提示=" + unknown.size() + " 行且只有名字 + 「你还不知此为何物。」="
				+ unknownOnly + "（应 true）；获取后恢复完整提示（含 4 行风味）=" + fullTooltip + "（应 true）");
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
	}

	/** 「是否获取过启示」属性：置位口径 + 恶魔交易彻底关闭 */
	private static void checkRevelationAttribute(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		// ① 老存档迁移：未迁移 + 启示进度已完成 → 首次 tick 补记，且只做一次
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		com.summy.reliquary.effect.PlayerFlags.setRevelationMigrated(player, false);
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.REVELATION_ASCENDED);
		boolean migrated = com.summy.reliquary.effect.RevelationTracker.updateObtained(player);
		boolean migratedFlag = com.summy.reliquary.effect.PlayerFlags.isRevelationMigrated(player);
		// 清掉属性：迁移标记仍在 → 不会再从成就补记
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		player.getInventory().clearContent();
		boolean noReplay = !com.summy.reliquary.effect.RevelationTracker.updateObtained(player);

		// ② 持有终末天启 → 每秒兜底置位
		player.getInventory().add(new ItemStack(SummyReliquary.FINAL_REVELATION.get()));
		boolean byHolding = com.summy.reliquary.effect.RevelationTracker.updateObtained(player);
		// ③ 启示属性为真 → 恶魔交易彻底关闭
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		com.summy.reliquary.effect.PlayerFlags.setPentagramGranted(player, true);
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, true);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
		player.getInventory().add(new ItemStack(SummyReliquary.PENTAGRAM.get()));
		com.summy.reliquary.effect.DemonDeal.setForcedValley(true);
		boolean qualifiedLocked = com.summy.reliquary.effect.DemonDeal.isQualified(player);
		boolean canSignLocked = com.summy.reliquary.effect.DemonDeal.canSign(player);
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		boolean sameDialogue = com.summy.reliquary.effect.DemonDeal.lastDialogue() == null;
		// ④ 创世纪清除该属性 → 恶魔交易重新开放
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		// 1.7.2：另外两条封锁也要清 —— 之前的用例可能完成了「无罪之人」，而每秒自愈会持续把
		// sin_renounced 补回来；圣心同理（本用例要测的是"启示清掉后是否重新开放"）
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, false);
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		com.summy.reliquary.effect.PlayerFlags.setPentagramGranted(player, true);
		com.summy.reliquary.effect.PlayerFlags.setPentagramSpoken(player, true);
		com.summy.reliquary.effect.DemonDeal.setForcedValley(true);
		boolean qualifiedAgain = com.summy.reliquary.effect.DemonDeal.isQualified(player);

		com.summy.reliquary.effect.DemonDeal.setForcedValley(null);
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		clearPentagramItems(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.resetDemonDeal(player);
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		com.summy.reliquary.effect.PlayerFlags.setRevelationMigrated(player, false);
		log("启示属性（1.6.10）：老存档迁移（启示进度已完成 → 补记）=" + migrated + "（应 true）、迁移位="
				+ migratedFlag + "（应 true）、清掉属性后不再从成就补记=" + noReplay
				+ "（应 true）；持有终末天启兜底置位=" + byHolding + "（应 true）");
		log("启示属性·恶魔线关闭：isQualified=" + qualifiedLocked + "（应 false）、canSign="
				+ canSignLocked + "（应 false、且没有任何对话=" + sameDialogue
				+ "）；清掉属性后重新开放=" + qualifiedAgain + "（应 true）");
	}

	/** 伯列恒之星：条件驱动（真三件套）+ 去重标记（与五芒星同一套口径） */
	private static void checkConditionDrivenStar(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		// ① 三件套不齐（什么都没戴）→ 不发
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, false);
		com.summy.reliquary.advancement.SinChallenges.tickStar(player);
		int noSet = countItemEverywhere(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		// ② 只戴咒印（恶魔线）→ 不算三件套
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, false);
		com.summy.reliquary.advancement.SinChallenges.tickStar(player);
		int markOnly = countItemEverywhere(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		unequip(player, ReliquarySlots.SPIRIT_ALTAR);
		// ③ 真三件套（肉体 / 思想 / 灵魂）→ 发放 1 个
		//    注意：灵台栏在天使线是 3 格，必须分别放进 0/1/2 号格
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 0,
					new ItemStack(SummyReliquary.THE_BODY.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 1,
					new ItemStack(SummyReliquary.THE_MIND.get()));
			handler.setEquippedCurio(ReliquarySlots.SPIRIT_ALTAR, 2,
					new ItemStack(SummyReliquary.THE_SOUL.get()));
		});
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, false);
		boolean fullSet = com.summy.reliquary.effect.SpiritAltarSet.isFullSet(player);
		com.summy.reliquary.advancement.SinChallenges.tickStar(player);
		int withSet = countItemEverywhere(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		boolean flagAfter = com.summy.reliquary.effect.PlayerFlags.isStarGranted(player);
		// ④ 标记保留但物品丢失 → 不再补发
		player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(SummyReliquary.STAR_OF_BETHLEHEM.get()), Integer.MAX_VALUE,
				player.inventoryMenu.getCraftSlots());
		com.summy.reliquary.advancement.SinChallenges.tickStar(player);
		int afterLoss = countItemEverywhere(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		resetDemonPactState(player);
		log("伯列恒之星（条件驱动）：三件套不齐=" + noSet + " 个（应 0）、只戴咒印=" + markOnly
				+ " 个（应 0 = 咒印不算三件套）、真三件套（isFullSet=" + fullSet + "，应 true）→ " + withSet
				+ " 个（应 1，标记=" + flagAfter + " 应 true）、有标记但物品丢失=" + afterLoss + " 个（应 0）");
	}

	/** 神性联动：圣光 15↔25、斗篷 20↔30 tick、圣心 8↔12 格 */
	private static void checkGodheadSynergies(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		prepareBarePlayer(player);

		// ① 圣光几率
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_LIGHT.get());
		int chanceAlone = com.summy.reliquary.effect.Synergies.holyLightChancePercent(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		int chanceWith = com.summy.reliquary.effect.Synergies.holyLightChancePercent(player);
		unequip(player, ReliquarySlots.REVELATION);
		unequip(player, ReliquarySlots.BLESSING);

		// ② 神圣斗篷的无敌窗口（走真实 onHurt，读实际写入的窗口长度）
		com.summy.reliquary.effect.HolyMantle.reset();
		com.summy.reliquary.effect.HolyMantle.tick(player.getServer());
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_MANTLE.get());
		prepareBarePlayer(player);
		var mantleEvent = new net.minecraftforge.event.entity.living.LivingHurtEvent(player,
				player.damageSources().magic(), 1.0F);
		com.summy.reliquary.effect.HolyMantle.onHurt(mantleEvent);
		long aloneWindow = com.summy.reliquary.effect.HolyMantle.guardUntil(player)
				- player.serverLevel().getGameTime();
		com.summy.reliquary.effect.HolyMantle.reset();
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		prepareBarePlayer(player);
		var mantleEvent2 = new net.minecraftforge.event.entity.living.LivingHurtEvent(player,
				player.damageSources().magic(), 1.0F);
		com.summy.reliquary.effect.HolyMantle.onHurt(mantleEvent2);
		long withWindow = com.summy.reliquary.effect.HolyMantle.guardUntil(player)
				- player.serverLevel().getGameTime();
		unequip(player, ReliquarySlots.REVELATION);
		unequip(player, ReliquarySlots.BLESSING);

		// ③ 圣心追踪半径（配置值 + 真实边界：12 格内能找到、8 格外找不到）
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		double radiusAlone = com.summy.reliquary.effect.Synergies.sacredHeartArrowRadius(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		double radiusWith = com.summy.reliquary.effect.Synergies.sacredHeartArrowRadius(player);
		var arrow = net.minecraft.world.entity.EntityType.ARROW.create(player.serverLevel());
		boolean farHit = false;
		boolean nearMiss = false;
		if (arrow != null) {
			arrow.setPos(player.getX(), player.getY(), player.getZ());
			Zombie far = spawnHolyLightTarget(player, 10.0D);
			if (far != null) {
				arrow.setPos(player.getX(), player.getY(), player.getZ());
				far.teleportTo(player.getX(), player.getY(), player.getZ() + 10.0D);
				farHit = com.summy.reliquary.effect.SacredHeart
						.nearestEnemyForTest(player.serverLevel(), arrow, 12.0D) != null;
				nearMiss = com.summy.reliquary.effect.SacredHeart
						.nearestEnemyForTest(player.serverLevel(), arrow, 8.0D) == null;
				far.discard();
			}
			arrow.discard();
		}
		unequip(player, ReliquarySlots.REVELATION);
		unequip(player, ReliquarySlots.BLESSING);
		player.getInventory().clearContent();
		log("神性联动（同时佩戴才生效）：圣光几率 单戴=" + chanceAlone + "（应 15）→ 同戴神性=" + chanceWith
				+ "（应 25）；斗篷无敌窗口 单戴=" + aloneWindow + " tick（应 20）→ 同戴=" + withWindow
				+ " tick（应 30）；圣心追踪半径 单戴=" + radiusAlone + "（应 8.0）→ 同戴=" + radiusWith
				+ "（应 12.0）、10 格外的目标 12 格内能找到=" + farHit + "（应 true）、8 格内找不到=" + nearMiss
				+ "（应 true）");
	}

	/** 亚巴顿联动：咒印碎裂 40↔60、魔眼恐惧归零、深渊领主狱火满级压制抗性 */
	private static void checkAbaddonSynergies(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		// 附近清场，避免碎裂把它们一起打了
		for (LivingEntity other : new java.util.ArrayList<>(player.serverLevel().getEntitiesOfClass(
				LivingEntity.class, player.getBoundingBox().inflate(40.0D), entity -> entity != player))) {
			other.discard();
		}
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		prepareBarePlayer(player);

		// ① 咒印碎裂伤害三档
		double plain = com.summy.reliquary.effect.DemonPact.shatterDamage(player);
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		double withMark = com.summy.reliquary.effect.DemonPact.shatterDamage(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		double withBoth = com.summy.reliquary.effect.DemonPact.shatterDamage(player);
		// 真实碎裂：60 点、钻石甲不减、抗性 IV → 12
		// 注意：黑心碎裂本身要求"佩戴撒旦圣经"（池子归零反噬），所以还要戴上圣经
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		Zombie armored = spawnHolyLightTarget(player, 6.0D);
		Zombie resistant = spawnHolyLightTarget(player, 8.0D);
		float armoredDealt = -1.0F;
		float resistantDealt = -1.0F;
		if (armored != null && resistant != null) {
			for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
					EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				armored.setItemSlot(slot, new ItemStack(Items.DIAMOND_HELMET));
				resistant.setItemSlot(slot, new ItemStack(Items.DIAMOND_HELMET));
			}
			resistant.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));
			player.setHealth(player.getMaxHealth());
			player.setAbsorptionAmount(0.0F);
			com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
			float armoredBefore = armored.getHealth();
			com.summy.reliquary.effect.DemonPact.consumeBlackHearts(player, 1.0D);
			armoredDealt = armoredBefore - armored.getHealth();
			player.setHealth(player.getMaxHealth());
			com.summy.reliquary.effect.PlayerFlags.setBlackHeartPoints(player, 1.0D);
			float resistantBefore = resistant.getHealth();
			com.summy.reliquary.effect.DemonPact.consumeBlackHearts(player, 1.0D);
			resistantDealt = resistantBefore - resistant.getHealth();
			armored.discard();
			resistant.discard();
		}

		// ② 魔眼：亚巴顿 + 魔眼 → 被注视目标移速归零；只戴魔眼 → 不动
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.OCCULT_EYE.get());
		com.summy.reliquary.effect.OccultEye.reset();
		Zombie gazed = spawnHolyLightTarget(player, 8.0D);
		boolean frozen = false;
		boolean speedZero = false;
		boolean cleared = false;
		boolean notFrozenAlone = false;
		if (gazed != null) {
			gazed.teleportTo(player.getX(), player.getY(), player.getZ() + 8.0D);
			gazed.setNoAi(true);
			player.setYRot(0.0F);
			player.setXRot(0.0F);
			clearGazePath(player, 12.0D);
			com.summy.reliquary.effect.OccultEye.tickPlayer(player);
			frozen = com.summy.reliquary.effect.OccultEye.isFrozen(gazed);
			speedZero = gazed.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0.0D;
			// 恐惧被清掉 → tickServer 应当把修饰符撤掉
			gazed.removeEffect(SummyReliquary.FEAR.get());
			com.summy.reliquary.effect.OccultEye.tickServer(server);
			cleared = !com.summy.reliquary.effect.OccultEye.isFrozen(gazed);
			// 只戴魔眼（摘掉亚巴顿）→ 不再归零
			unequip(player, ReliquarySlots.REVELATION);
			gazed.addEffect(new net.minecraft.world.effect.MobEffectInstance(SummyReliquary.FEAR.get(), 120, 0));
			com.summy.reliquary.effect.OccultEye.tickPlayer(player);
			notFrozenAlone = !com.summy.reliquary.effect.OccultEye.isFrozen(gazed);
			com.summy.reliquary.effect.OccultEye.clearFreezeForTest(gazed);
			gazed.discard();
		}
		unequip(player, ReliquarySlots.BLESSING);

		// ③ 狱火：亚巴顿 + 深渊领主 → 满级时把目标抗性等级减半（持续覆盖）
		Zombie burning = spawnHolyLightTarget(player, 6.0D);
		int levelNineResistance = -1;
		int levelTenResistance = -1;
		int reDrinkResistance = -1;
		int noAbaddonResistance = -1;
		if (burning != null) {
			burning.setHealth(burning.getMaxHealth());
			burning.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));
			// 先只戴深渊领主（无亚巴顿）→ 不压制
			equip(player, ReliquarySlots.BLESSING, SummyReliquary.ABYSS_LORD.get());
			for (int index = 0; index < 10; index++) {
				com.summy.reliquary.effect.AbyssLord.applyStack(burning, player);
			}
			burning.setHealth(burning.getMaxHealth());
			SummyReliquary.HELLFIRE.get().applyEffectTick(burning, 9);
			var afterNoAbaddon = burning.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
			noAbaddonResistance = afterNoAbaddon == null ? -1 : afterNoAbaddon.getAmplifier();
			// 再戴上亚巴顿 → 下一次命中刷新联动时间戳，9 级不生效
			equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
			com.summy.reliquary.effect.AbyssLord.applyStack(burning, player);
			burning.setHealth(burning.getMaxHealth());
			SummyReliquary.HELLFIRE.get().applyEffectTick(burning, 8);
			var atNine = burning.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
			levelNineResistance = atNine == null ? -1 : atNine.getAmplifier();
			// 补到 10 级 → 抗性 IV（amp 3）被压成 amp 1（抗性 II）
			for (int index = 0; index < 2; index++) {
				com.summy.reliquary.effect.AbyssLord.applyStack(burning, player);
			}
			burning.setHealth(burning.getMaxHealth());
			SummyReliquary.HELLFIRE.get().applyEffectTick(burning, 9);
			var atTen = burning.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
			levelTenResistance = atTen == null ? -1 : atTen.getAmplifier();
			// 目标中途再灌一瓶抗性 IV → 下一次结算会再压回 amp 1
			burning.removeEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
			burning.addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 3));
			burning.setHealth(burning.getMaxHealth());
			SummyReliquary.HELLFIRE.get().applyEffectTick(burning, 9);
			var reDrink = burning.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
			reDrinkResistance = reDrink == null ? -1 : reDrink.getAmplifier();
			burning.discard();
		}
		unequip(player, ReliquarySlots.REVELATION);
		unequip(player, ReliquarySlots.BLESSING);
		unequip(player, ReliquarySlots.SPIRIT_ALTAR);
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearSpiritAltar(player);
		log("亚巴顿联动·咒印：碎裂伤害 无咒印=" + plain + "（应 24.0）→ 只戴咒印=" + withMark
				+ "（应 40.0）→ 再戴亚巴顿=" + withBoth + "（应 60.0）；真实碎裂 钻石甲=" + armoredDealt
				+ "（应 60.0 = 无视护甲）、抗性 IV=" + resistantDealt + "（应 12.0 = 60 × 20%）");
		log("亚巴顿联动·魔眼：被注视目标挂上「移速归零」=" + frozen + "（应 true）、速度属性=" + speedZero
				+ "（应 true 归零）、恐惧被清后修饰符被撤掉=" + cleared + "（应 true）、只戴魔眼时不归零="
				+ notFrozenAlone + "（应 true）");
		log("亚巴顿联动·深渊领主：无亚巴顿时抗性 amp=" + noAbaddonResistance + "（应 3 = 原样）、狱火 9 级时 amp="
				+ levelNineResistance + "（应 3 = 未满级不压制）、10 级时 amp=" + levelTenResistance
				+ "（应 1 = 抗性 IV → II）、目标重灌抗性后再次被压到 amp=" + reDrinkResistance + "（应 1）");
	}

	/**
	 * 1.6.10 联动的提示数值：圣光 15↔25、斗篷 1↔1.5 秒、咒印 40↔60。
	 *
	 * <p>这三行都在 Shift 里（自检里按不住 Shift），所以这里验证的是"**取值入口 + 文案模板**"：
	 * 用与物品提示**同一个** {@code Synergies} 取值、同一个 {@code statComponent} 拼行，
	 * 因此数值一旦跑偏就会在这里暴露；三个键也都断言带 {@code %s}（数值必须动态）。
	 */
	private static void checkSynergyTooltips(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);

		// ① 圣光：只戴圣光 → 15；再戴神性 → 25
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_LIGHT.get());
		String lightAlone = lightChanceLine(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		String lightWith = lightChanceLine(player);
		unequip(player, ReliquarySlots.REVELATION);
		// ② 斗篷：只戴斗篷 → 1 秒；再戴神性 → 1.5 秒
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_MANTLE.get());
		String mantleAlone = mantleLine(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		String mantleWith = mantleLine(player);
		unequip(player, ReliquarySlots.REVELATION);
		// ③ 咒印：只戴咒印 → 40；再戴亚巴顿 → 60
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		String markAlone = markShatterLine(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.ABADDON.get());
		String markWith = markShatterLine(player);
		unequip(player, ReliquarySlots.REVELATION);
		unequip(player, ReliquarySlots.BLESSING);
		unequip(player, ReliquarySlots.SPIRIT_ALTAR);
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearSpiritAltar(player);
		boolean dynamicKeys = Component.translatable("item.summy-reliquary.holy_light.desc")
				.getString().contains("%s")
				&& Component.translatable("item.summy-reliquary.holy_mantle.desc").getString().contains("%s")
				&& Component.translatable("item.summy-reliquary.the_mark.shift.2").getString().contains("%s");
		boolean ok = lightAlone.contains("15") && lightWith.contains("25")
				&& mantleAlone.contains("1 秒") && mantleWith.contains("1.5 秒")
				&& markAlone.contains("40") && markWith.contains("60");
		log("联动提示数值（1.6.10，取值入口 + 文案模板）：圣光 单戴=「" + lightAlone + "」→ 同戴神性=「"
				+ lightWith + "」；斗篷 单戴=「" + mantleAlone + "」→ 同戴=「" + mantleWith
				+ "」；咒印 单戴=「" + markAlone + "」→ 同戴亚巴顿=「" + markWith + "」→ 全部符合=" + ok
				+ "（应 true）；三个键都带 %s=" + dynamicKeys + "（应 true）");
	}

	/** 圣光提示行（用与物品提示同一个取值入口与拼行函数） */
	private static String lightChanceLine(ServerPlayer player) {
		return com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.ANGEL, "item.summy-reliquary.holy_light.desc",
				com.summy.reliquary.effect.Synergies.holyLightChancePercent(player)).getString();
	}

	/** 神圣斗篷提示行 */
	private static String mantleLine(ServerPlayer player) {
		return com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.ANGEL, "item.summy-reliquary.holy_mantle.desc",
				ticksToSeconds(com.summy.reliquary.effect.Synergies.holyMantleInvulnerableTicks(player)))
				.getString();
	}

	/** 咒印碎裂伤害提示行 */
	private static String markShatterLine(ServerPlayer player) {
		return com.summy.reliquary.item.ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.DEMON, "item.summy-reliquary.the_mark.shift.2",
				formatNumber(com.summy.reliquary.effect.Synergies.shatterDamage(player))).getString();
	}

	/** tick → 秒文本（20 → 1、30 → 1.5） */
	private static String ticksToSeconds(int ticks) {
		double seconds = ticks / 20.0D;
		return seconds == Math.floor(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds);
	}

	/** 去掉小数点后缀（40.0 → 40） */
	private static String formatNumber(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** 1.6.10 六个联动配置项的默认值 */
	private static void checkSynergyConfig() {
		boolean ok = com.summy.reliquary.config.ReliquaryConfig.holyLightChancePercentGodhead() == 25
				&& com.summy.reliquary.config.ReliquaryConfig.holyMantleInvulnerableTicksGodhead() == 30
				&& Math.abs(com.summy.reliquary.config.ReliquaryConfig.sacredHeartArrowRadiusGodhead() - 12.0D)
						< 1.0E-6D
				&& Math.abs(com.summy.reliquary.config.ReliquaryConfig.markShatterDamageAbaddon() - 60.0D)
						< 1.0E-6D
				&& com.summy.reliquary.config.ReliquaryConfig.fearFreezeWithAbaddon()
				&& com.summy.reliquary.config.ReliquaryConfig.hellfireHalvesResistanceWithAbaddon();
		log("联动配置默认值（1.6.10）：圣光 25% / 斗篷 30tick(1.5s) / 圣心 12 格 / 咒印 60 / 恐惧归零 / 狱火压制抗性 → "
				+ ok + "（应 true）");
	}

	// ==================== 1.7.0：配方补齐 / 门禁与退料一致性 / 可获得性审计 ====================

	/** 1.7.0 新增与改写的全部配方 id（29 张里的受检集合） */
	private static final String[] RELIQUARY_RECIPES = {
			// 材料与工具
			"wooden_cross", "redemption", "act_of_contrition",
			// 七宗罪碎片（1.7.0 新增）
			"sin_fragment_pride", "sin_fragment_greed", "sin_fragment_lust", "sin_fragment_envy",
			"sin_fragment_gluttony", "sin_fragment_wrath", "sin_fragment_sloth",
			// 天使线
			"the_body", "the_mind", "the_soul", "holy_light", "holy_mantle", "godhead", "trinity",
			"sacred_heart", "salvation",
			// 恶魔线
			"ceremonial_robes", "the_mark", "vengeful_spirit", "night_wraith", "brimstone",
			"occult_eye", "abyss_lord", "abaddon",
			// 1.7.6：两把仪式匕首（防丢失配方 / 700 升级表）
			"sacrificial_dagger", "dark_arts",
			// 1.7.9：两把天使线长矛（短矛的防丢失配方 / 炽天使之枪的升级表）
			"holy_spear", "seraph_spear",
			// 1.7.10：金刀片（无门槛的普通合成表）
			"golden_razor",
			// 其它两件
			"bangbang_halo", "freeloaders_rice"
		};

	/**
	 * 1.7.0 配方审查：① 29 张配方都在；② 受管配方的**材料清单与退料清单逐项一致**；
	 * ③ 三位一体退还三件套；④ 门禁（天使 / 签约 / 邪恶度）行为；⑤ 创造页 40 项 + 4 件纯图标物品不在页内。
	 */
	private static void checkRecipeCoverage(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		// ① 配方存在性
		StringBuilder missing = new StringBuilder();
		int found = 0;
		for (String id : RELIQUARY_RECIPES) {
			if (server.getRecipeManager().byKey(SummyReliquary.id(id)).isPresent()) {
				found++;
			} else {
				missing.append(id).append(' ');
			}
		}
		int recipeFiles = 34;
		log("配方覆盖（1.7.0）：按 id 取到 " + found + " / " + recipeFiles + " 张（应全中，缺失="
				+ (missing.length() == 0 ? "无" : missing.toString().trim()) + "）");

		// ② 受管配方：材料清单 vs 退料清单（自动比对，防止以后改配方漏改退料）
		StringBuilder mismatch = new StringBuilder();
		int checked = 0;
		java.util.Map<Item, List<ItemStack>> managed = new java.util.LinkedHashMap<>(
				com.summy.reliquary.effect.SpiritAltarRecipeGate.gateRefunds());
		managed.putAll(com.summy.reliquary.effect.EvilRecipeGate.refunds());
		for (var entry : managed.entrySet()) {
			var recipe = server.getRecipeManager()
					.byKey(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getKey()))
					.orElse(null);
			if (recipe == null) {
				mismatch.append(entry.getKey()).append("(无配方) ");
				continue;
			}
			java.util.Map<Item, Integer> fromRecipe = ingredientCounts(recipe);
			java.util.Map<Item, Integer> fromRefund = refundCounts(entry.getValue());
			checked++;
			if (!fromRecipe.equals(fromRefund)) {
				mismatch.append(entry.getKey()).append(' ');
			}
		}
		log("配方·材料/退料一致性：" + checked + " 张受管配方逐项比对 → 全部一致="
				+ (mismatch.length() == 0) + "（应 true"
				+ (mismatch.length() == 0 ? "" : "，异常：" + mismatch.toString().trim()) + "）");

		// ③ 三位一体：配方只实耗心之碎片 → 取产物时退还三件套
		player.getInventory().clearContent();
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.TRINITY.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.TRINITY.get()), player.inventoryMenu.getCraftSlots()));
		int trinityHeld = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.TRINITY.get())
				+ (player.inventoryMenu.getCarried().is(SummyReliquary.TRINITY.get()) ? 1 : 0);
		int bodyBack = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.THE_BODY.get());
		int mindBack = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.THE_MIND.get());
		int soulBack = com.summy.reliquary.sin.SinEffects.countItem(player, SummyReliquary.THE_SOUL.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		java.util.Map<Item, Integer> trinityRecipe = server.getRecipeManager()
				.byKey(SummyReliquary.id("trinity"))
				.map(ForgeDevCheck::ingredientCounts).orElse(java.util.Map.of());
		boolean trinityOk = trinityHeld == 1 && bodyBack == 1 && mindBack == 1 && soulBack == 1
				&& trinityRecipe.getOrDefault(SummyReliquary.HEART_SHARD.get(), 0) == 1
				&& trinityRecipe.size() == 4;
		log("三位一体：配方材料=" + trinityRecipe.size() + " 种（应 4 = 三件套 + 心之碎片）、心之碎片×"
				+ trinityRecipe.getOrDefault(SummyReliquary.HEART_SHARD.get(), 0) + "（应 1）；合成后产物在手="
				+ trinityHeld + "（应 1）、三件套各退回 1：" + bodyBack + "/" + mindBack + "/" + soulBack
				+ "（都应 1） → " + trinityOk + "（应 true）");

		// ④ 门禁行为：天使线（圣光）/ 恶魔线（复仇之魂，取未解锁态）
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		player.getInventory().clearContent();
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.HOLY_LIGHT.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.HOLY_LIGHT.get()), player.inventoryMenu.getCraftSlots()));
		boolean holyLightBlocked = com.summy.reliquary.sin.SinEffects
				.countItem(player, SummyReliquary.HOLY_LIGHT.get()) == 0
				&& com.summy.reliquary.sin.SinEffects.countItem(player, Items.GLOWSTONE) == 4
				&& com.summy.reliquary.sin.SinEffects.countItem(player, Items.SEA_LANTERN) == 4
				&& com.summy.reliquary.sin.SinEffects.countItem(player, Items.GOLDEN_APPLE) == 1;
		player.getInventory().clearContent();
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.VENGEFUL_SPIRIT.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.VENGEFUL_SPIRIT.get()), player.inventoryMenu.getCraftSlots()));
		boolean vengefulBlocked = com.summy.reliquary.sin.SinEffects
				.countItem(player, SummyReliquary.VENGEFUL_SPIRIT.get()) == 0
				&& com.summy.reliquary.sin.SinEffects.countItem(player, Items.BLAZE_POWDER) == 4
				&& com.summy.reliquary.sin.SinEffects.countItem(player, Items.SOUL_SAND) == 4
				&& com.summy.reliquary.sin.SinEffects.countItem(player, Items.NETHERITE_SCRAP) == 1;
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();
		log("配方门禁·新增两条：圣光（无天使标记）被拦并退料=" + holyLightBlocked
				+ "（应 true）、复仇之魂（邪恶 0 未解锁）被拦并退料=" + vengefulBlocked + "（应 true）");

		// ⑤ 恶魔线门禁清单 + JEI 可见性
		// 受管恶魔线配方 = 咒印 + 玄秘魔眼 + 复仇之魂 / 夜之幽魂 / 硫磺火 / 深渊领主 / 亚巴顿 + 暗仪刺刀（1.7.6）= 8 张
		boolean gated = com.summy.reliquary.effect.EvilUnlock.gatedRecipes().size() == 8
				&& com.summy.reliquary.effect.EvilUnlock.gatedRecipes().containsKey(
						SummyReliquary.id("vengeful_spirit"))
				&& com.summy.reliquary.effect.EvilUnlock.gatedRecipes().containsKey(SummyReliquary.id("abaddon"))
				&& com.summy.reliquary.effect.EvilUnlock.gatedRecipes().containsKey(SummyReliquary.id("dark_arts"));
		// 1.7.6：第三参 = 献祭匕首「防丢失配方」是否开放；1.7.9：第四参 = 圣光短矛「防丢失配方」是否开放
		var angelView = com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(true, false, false, false);
		var plainView = com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(false, false, false, false);
		var signedView = com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(false, true, false, false);
		var daggerView = com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(false, false, true, false);
		// 1.7.9：只有"丢失态 + 天使标记"才看得见短矛的防丢失配方
		var spearView = com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(true, false, false, true);
		var spearNoAngel = com.summy.reliquary.effect.SpiritAltarRecipeGate.jeiVisibility(false, false, false, true);
		boolean jeiOk = Boolean.TRUE.equals(angelView.get(SummyReliquary.id("the_body")))
				&& Boolean.TRUE.equals(angelView.get(SummyReliquary.id("holy_light")))
				&& Boolean.TRUE.equals(angelView.get(SummyReliquary.id("godhead")))
				// 1.7.9：炽天使之枪看天使标记；短矛的防丢失配方看"丢失态 + 天使标记"
				&& Boolean.TRUE.equals(angelView.get(SummyReliquary.id("seraph_spear")))
				&& Boolean.FALSE.equals(angelView.get(SummyReliquary.id("holy_spear")))
				&& Boolean.TRUE.equals(spearView.get(SummyReliquary.id("holy_spear")))
				&& Boolean.FALSE.equals(spearNoAngel.get(SummyReliquary.id("holy_spear")))
				&& Boolean.FALSE.equals(angelView.get(SummyReliquary.id("salvation")))
				&& Boolean.FALSE.equals(angelView.get(SummyReliquary.id("ceremonial_robes")))
				&& Boolean.FALSE.equals(plainView.get(SummyReliquary.id("the_body")))
				&& Boolean.TRUE.equals(signedView.get(SummyReliquary.id("ceremonial_robes")))
				&& Boolean.FALSE.equals(signedView.get(SummyReliquary.id("the_body")))
				// 1.7.6：献祭匕首的「防丢失配方」只在开放态可见
				&& Boolean.FALSE.equals(plainView.get(SummyReliquary.id("sacrificial_dagger")))
				&& Boolean.TRUE.equals(daggerView.get(SummyReliquary.id("sacrificial_dagger")))
				&& Boolean.FALSE.equals(daggerView.get(SummyReliquary.id("the_body")));
		log("配方门禁清单：恶魔线受管配方=" + com.summy.reliquary.effect.EvilUnlock.gatedRecipes().size()
				+ " 张（应 8，1.7.6 起含暗仪刺刀）=" + gated
				+ "（应 true）；JEI 可见性（天使 / 无 / 已签约 / 匕首丢失态 / 长矛丢失态）→ " + jeiOk
				+ "（应 true）");

		// ⑥ 可获得性审计：40 件玩法物品 + 4 件纯图标物品
		int iconOnly = 0;
		for (var icon : new net.minecraftforge.registries.RegistryObject[]{SummyReliquary.ERROR_ICON,
				SummyReliquary.PURITY_ICON, SummyReliquary.DUALITY_STAT, SummyReliquary.YOUR_SOUL}) {
			if (icon.isPresent()) {
				iconOnly++;
			}
		}
		int noPath = 0;
		StringBuilder noPathIds = new StringBuilder();
		for (var entry : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
			if (!SummyReliquary.NAMESPACE.equals(entry.getKey().location().getNamespace())) {
				continue;
			}
			Item item = entry.getValue();
			if (item == SummyReliquary.ERROR_ICON.get() || item == SummyReliquary.PURITY_ICON.get()
					|| item == SummyReliquary.DUALITY_STAT.get() || item == SummyReliquary.YOUR_SOUL.get()) {
				continue; // 4 件纯图标物品（按设计不给获取途径）
			}
			if (!hasAnyAcquisition(server, item)) {
				noPath++;
				noPathIds.append(entry.getKey().location().getPath()).append(' ');
			}
		}
		log("可获得性审计（1.7.0 / 1.7.9 收敛）：除 4 件纯图标物品外，没有获取途径的物品=" + noPath
				+ "（应 0" + (noPath == 0 ? "" : "，异常：" + noPathIds.toString().trim()) + "）、纯图标物品="
				+ iconOnly + "（应 4）");
	}

	/**
	 * 该物品是否有获取途径：本模组配方 / 首次发放 / 转化 / 发放或掉落（按设计归入"非配方获取"的清单）。
	 */
	private static boolean hasAnyAcquisition(MinecraftServer server, Item item) {
		var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
		if (server.getRecipeManager().byKey(id).isPresent()) {
			return true;
		}
		// 没有配方的那些（发放 / 转化 / 掉落）——按 1.7.0 审计确认过的清单
		return item == SummyReliquary.SOURCE_OF_SINS.get()
				|| item == SummyReliquary.VIRTUES.get()
				|| item == SummyReliquary.THE_HALO.get()
				|| item == SummyReliquary.STAR_OF_BETHLEHEM.get()
				|| item == SummyReliquary.FINAL_REVELATION.get()
				|| item == SummyReliquary.GENESIS.get()
				|| item == SummyReliquary.THE_PACT.get()
				|| item == SummyReliquary.SATANIC_BIBLE.get()
				|| item == SummyReliquary.PENTAGRAM.get()
				|| item == SummyReliquary.HEART_SHARD.get()
				|| item == SummyReliquary.SIX.get()
				// 1.7.1：恶魔王冠 —— 转化撒旦圣经时一次性发放（无配方）
				|| item == SummyReliquary.DEVIL_CROWN.get();
	}

	/** 把配方材料展平成"物品 → 总数"（标签取第一个匹配项；本模组受管配方都用普通物品） */
	private static java.util.Map<Item, Integer> ingredientCounts(
			net.minecraft.world.item.crafting.Recipe<?> recipe) {
		java.util.Map<Item, Integer> counts = new java.util.LinkedHashMap<>();
		for (var ingredient : recipe.getIngredients()) {
			ItemStack[] items = ingredient.getItems();
			if (items.length == 0) {
				continue;
			}
			counts.merge(items[0].getItem(), Math.max(1, items[0].getCount()), Integer::sum);
		}
		return counts;
	}

	/** 把退料清单展平成"物品 → 总数" */
	private static java.util.Map<Item, Integer> refundCounts(List<ItemStack> refunds) {
		java.util.Map<Item, Integer> counts = new java.util.LinkedHashMap<>();
		for (ItemStack stack : refunds) {
			counts.merge(stack.getItem(), Math.max(1, stack.getCount()), Integer::sum);
		}
		return counts;
	}

	// ==================== 1.7.1：恶魔王冠 / 光环改造 / 上一批修复 ====================

	/** 取某条属性上指定名字的修饰符数值（自检用；没有则 0） */
	private static double modifierAmount(ServerPlayer player,
			net.minecraft.world.entity.ai.attributes.Attribute attribute, String name) {
		var instance = player.getAttribute(attribute);
		if (instance == null) {
			return 0.0D;
		}
		for (var modifier : instance.getModifiers()) {
			if (name.equals(modifier.getName())) {
				return modifier.getAmount();
			}
		}
		return 0.0D;
	}

	/** 去掉小数点后缀（-4.0 → -4、0.4 → 0.4） */
	private static String trimNumber(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/**
	 * 1.7.1：恶魔王冠 —— 资源 / 栏位 / 属性（需佩戴撒旦圣经）/ 666 加伤分档 / 发放与死亡规则 / 提示。
	 */
	private static void checkDevilCrown(ServerPlayer player) {
		Minecraft client = Minecraft.getInstance();
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.HALO);
		unequip(player, ReliquarySlots.CHARM);
		unequip(player, ReliquarySlots.REVELATION);
		setSinMasksForTest(player, 0, 0);
		// 手上换成钻石剑：裸手攻速 4.0 正好顶到模组的攻速上限，+0.4 会被上限吃掉
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, false);

		// ① 资源 / 注册 / 语言键 / 创造页
		boolean registered = SummyReliquary.DEVIL_CROWN.isPresent();
		boolean textures = hasResource(client, "textures/item/devil_crown.png");
		boolean models = hasResource(client, "models/item/devil_crown.json");
		boolean lang = translated("item.summy-reliquary.devil_crown")
				&& translated("item.summy-reliquary.devil_crown.tagline.1")
				&& translated("item.summy-reliquary.devil_crown.tagline.2")
				&& translated("item.summy-reliquary.devil_crown.shift.6")
				&& translated("item.summy-reliquary.devil_crown.shift.note")
				&& translated("item.summy-reliquary.devil_crown.shift.conditional")
				&& translated("message.summy-reliquary.devil_crown.granted");
		var tab = BuiltInRegistries.CREATIVE_MODE_TAB.get(SummyReliquary.id("main"));
		// 创造页条目是**懒构建**的（打开创造界面或客户端初始化时才填充），没构建时按"不判定"处理，
		// 与 1.7.0 的可获得性审计同一口径，避免在无界面环境下假失败。
		boolean tabBuilt = tab != null && !tab.getDisplayItems().isEmpty();
		boolean inTab = !tabBuilt || tab.getDisplayItems().stream()
				.anyMatch(stack -> stack.is(SummyReliquary.DEVIL_CROWN.get()));

		// ② 栏位：光环栏放行、无关栏位拒绝（王冠 / 光环 / 邦邦咔邦三者互斥由同一个校验器保证）
		boolean haloAccepted = canEquipIn(player, SummyReliquary.DEVIL_CROWN.get(), ReliquarySlots.HALO);
		boolean othersDenied = !canEquipIn(player, SummyReliquary.DEVIL_CROWN.get(), ReliquarySlots.BACK)
				&& !canEquipIn(player, SummyReliquary.DEVIL_CROWN.get(), ReliquarySlots.BLESSING)
				&& !canEquipIn(player, SummyReliquary.DEVIL_CROWN.get(), ReliquarySlots.SOUL_SEAL);

		// ③ 属性：只戴王冠 → 六条全 0；再戴上撒旦圣经 → 六条配置值
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double baseHealth = player.getAttributeValue(Attributes.MAX_HEALTH);
		equip(player, ReliquarySlots.HALO, SummyReliquary.DEVIL_CROWN.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		boolean soloZero = Math.abs(modifierAmount(player, Attributes.MOVEMENT_SPEED,
						"devil_crown_movement")) < 1.0E-6D
				&& Math.abs(modifierAmount(player, Attributes.ATTACK_SPEED, "devil_crown_attack_speed")) < 1.0E-6D
				&& Math.abs(modifierAmount(player, Attributes.ATTACK_DAMAGE, "devil_crown_damage")) < 1.0E-6D
				&& Math.abs(modifierAmount(player, Attributes.MAX_HEALTH, "devil_crown_health")) < 1.0E-6D
				&& Math.abs(modifierAmount(player, Attributes.ARMOR, "devil_crown_armor")) < 1.0E-6D
				&& Math.abs(modifierAmount(player, Attributes.ARMOR_TOUGHNESS, "devil_crown_toughness")) < 1.0E-6D;
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double speedMod = modifierAmount(player, Attributes.MOVEMENT_SPEED, "devil_crown_movement");
		double attackSpeedMod = modifierAmount(player, Attributes.ATTACK_SPEED, "devil_crown_attack_speed");
		double damageMod = modifierAmount(player, Attributes.ATTACK_DAMAGE, "devil_crown_damage");
		double healthMod = modifierAmount(player, Attributes.MAX_HEALTH, "devil_crown_health");
		double armorMod = modifierAmount(player, Attributes.ARMOR, "devil_crown_armor");
		double toughnessMod = modifierAmount(player, Attributes.ARMOR_TOUGHNESS, "devil_crown_toughness");
		double healthWithCrown = player.getAttributeValue(Attributes.MAX_HEALTH);
		boolean statsOk = Math.abs(speedMod - com.summy.reliquary.config.ReliquaryConfig
						.devilCrownMovementPercent() / 100.0D) < 1.0E-6D
				&& Math.abs(attackSpeedMod - com.summy.reliquary.config.ReliquaryConfig
						.devilCrownAttackSpeed()) < 1.0E-6D
				&& Math.abs(damageMod - com.summy.reliquary.config.ReliquaryConfig
						.devilCrownMeleeDamage()) < 1.0E-6D
				&& Math.abs(healthMod - com.summy.reliquary.config.ReliquaryConfig
						.devilCrownMaxHealth()) < 1.0E-6D
				&& Math.abs(armorMod - com.summy.reliquary.config.ReliquaryConfig.devilCrownArmor()) < 1.0E-6D
				&& Math.abs(toughnessMod - com.summy.reliquary.config.ReliquaryConfig
						.devilCrownArmorToughness()) < 1.0E-6D
				&& Math.abs(healthWithCrown - (baseHealth + com.summy.reliquary.config.ReliquaryConfig
						.devilCrownMaxHealth())) < 1.0E-6D;

		// ④ 666 条件加伤：分档 + 封顶 + 未到 666 一律 0
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, false);
		player.setHealth(player.getMaxHealth());
		double bonusBeforeLock = com.summy.reliquary.effect.DevilCrown.damageBonusPercent(player);
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, true);
		player.setHealth(player.getMaxHealth());
		double bonusFull = com.summy.reliquary.effect.DevilCrown.damageBonusPercent(player);
		player.setHealth(player.getMaxHealth() * 0.89F);
		double bonusOne = com.summy.reliquary.effect.DevilCrown.damageBonusPercent(player);
		player.setHealth(player.getMaxHealth() * 0.79F);
		double bonusTwo = com.summy.reliquary.effect.DevilCrown.damageBonusPercent(player);
		player.setHealth(player.getMaxHealth() * 0.5F);
		double bonusFive = com.summy.reliquary.effect.DevilCrown.damageBonusPercent(player);
		player.setHealth(player.getMaxHealth() * 0.1F);
		double bonusCap = com.summy.reliquary.effect.DevilCrown.damageBonusPercent(player);
		// 事件层：50% 血 → 10 点变 11.5；定值真伤（启示之光）不吃
		player.setHealth(player.getMaxHealth() * 0.5F);
		Zombie crownTarget = spawnHolyLightTarget(player, 6.0D);
		float crownBoosted = -1.0F;
		float crownExact = -1.0F;
		if (crownTarget != null) {
			var boostedHurt = new LivingHurtEvent(crownTarget,
					player.damageSources().playerAttack(player), 10.0F);
			com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(boostedHurt);
			crownBoosted = boostedHurt.getAmount();
			var registry = player.serverLevel().registryAccess()
					.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
			var exactSource = new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(
					net.minecraft.resources.ResourceKey.create(
							net.minecraft.core.registries.Registries.DAMAGE_TYPE,
							SummyReliquary.id("revelation_light"))));
			var exactHurt = new LivingHurtEvent(crownTarget, exactSource, 10.0F);
			com.summy.reliquary.effect.SpiritAltarSet.onLivingHurt(exactHurt);
			crownExact = exactHurt.getAmount();
			crownTarget.discard();
		}
		boolean damageOk = bonusBeforeLock == 0.0D && bonusFull == 0.0D
				&& Math.abs(bonusOne - 3.0D) < 1.0E-6D && Math.abs(bonusTwo - 6.0D) < 1.0E-6D
				&& Math.abs(bonusFive - 15.0D) < 1.0E-6D && Math.abs(bonusCap - 15.0D) < 1.0E-6D
				&& Math.abs(crownBoosted - 11.5F) < 1.0E-4F && Math.abs(crownExact - 10.0F) < 1.0E-4F;

		// ⑤ 发放：转化撒旦圣经时发 1 次且不重复；死亡不掉
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		unequip(player, ReliquarySlots.HALO);
		com.summy.reliquary.effect.PlayerFlags.setDevilCrownGranted(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		setSinMasksForTest(player, 0b1111111, 0);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		int crownAfterConvert = countItemEverywhere(player, SummyReliquary.DEVIL_CROWN.get());
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		int crownAfterSecond = countItemEverywhere(player, SummyReliquary.DEVIL_CROWN.get());
		var crownCurio = (top.theillusivec4.curios.api.type.capability.ICurioItem)
				SummyReliquary.DEVIL_CROWN.get();
		boolean keepOnDeath = crownCurio.getDropRule(
				new SlotContext(ReliquarySlots.HALO, player, 0, false, true),
				player.damageSources().generic(), 0, false,
				new ItemStack(SummyReliquary.DEVIL_CROWN.get()))
				== top.theillusivec4.curios.api.type.capability.ICurio.DropRule.ALWAYS_KEEP;
		boolean grantOk = crownAfterConvert == 1 && crownAfterSecond == 1 && keepOnDeath;

		// ⑥ 提示：6 条属性行两段配色 + 灰说明行；条件行只在 666（hell_locked）后出现
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, false);
		List<Component> beforeLock = com.summy.reliquary.item.DevilCrownItem.shiftLines(player);
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, true);
		List<Component> afterLock = com.summy.reliquary.item.DevilCrownItem.shiftLines(player);
		Component conditional = afterLock.get(afterLock.size() - 1);
		boolean tooltipOk = beforeLock.size() == 7 && afterLock.size() == 8
				&& "#C03030".equals(colorOf(beforeLock.get(0)))
				&& beforeLock.get(0).getSiblings().size() == 1
				&& "#FF6B6B".equals(colorOf(beforeLock.get(0).getSiblings().get(0)))
				&& "#AAAAAA".equals(colorOf(beforeLock.get(6)))
				&& "#C03030".equals(colorOf(conditional))
				&& conditional.getString().equals(Component.translatable(
						"item.summy-reliquary.devil_crown.shift.conditional").getString());

		log("恶魔王冠（1.7.1）：已注册=" + registered + "（应 true）、贴图=" + textures + "、模型=" + models
				+ "（都应 true）、语言键齐全=" + lang + "（应 true）、进创造页=" + inTab + "（应 true）；"
				+ "光环栏接受=" + haloAccepted + "（应 true）、无关栏位被拒=" + othersDenied + "（应 true）");
		log(String.format("恶魔王冠·属性：只戴王冠六条全 0=%s（应 true）；戴王冠+撒旦圣经 → 移速修饰符 %.2f"
						+ "（应 %.2f）、攻速 %.2f（应 %.2f）、近战伤害 %.1f（应 %.1f）、最大生命 %.1f（应 %.1f，"
						+ "实际生命 %.1f = 基准 %.1f −4）、护甲 %.1f（应 %.1f）、韧性 %.1f（应 %.1f）→ 全对=%s（应 true）",
				soloZero, speedMod, com.summy.reliquary.config.ReliquaryConfig.devilCrownMovementPercent() / 100.0D,
				attackSpeedMod, com.summy.reliquary.config.ReliquaryConfig.devilCrownAttackSpeed(),
				damageMod, com.summy.reliquary.config.ReliquaryConfig.devilCrownMeleeDamage(),
				healthMod, com.summy.reliquary.config.ReliquaryConfig.devilCrownMaxHealth(),
				healthWithCrown, baseHealth,
				armorMod, com.summy.reliquary.config.ReliquaryConfig.devilCrownArmor(),
				toughnessMod, com.summy.reliquary.config.ReliquaryConfig.devilCrownArmorToughness(),
				statsOk));
		log(String.format("恶魔王冠·666 加伤：未到 666=%.1f%%、满血=%.1f%%、损失 10%%档=%.1f%%（应 3）、"
						+ "损失 20%%档=%.1f%%（应 6）、损失 50%%=%.1f%%（应 15 封顶）、损失 90%%=%.1f%%（应仍 15）；"
						+ "事件层 50%% 血时 10 → %.2f（应 11.50）、定值真伤 10 → %.2f（应仍是 10）→ 全对=%s（应 true）",
				bonusBeforeLock, bonusFull, bonusOne, bonusTwo, bonusFive, bonusCap,
				crownBoosted, crownExact, damageOk));
		log("恶魔王冠·发放：转化撒旦圣经后王冠×" + crownAfterConvert + "（应 1）、再跑一次仍×" + crownAfterSecond
				+ "（应 1，不重复）、死亡不掉（ALWAYS_KEEP）=" + keepOnDeath + "（应 true）→ " + grantOk
				+ "（应 true）");
		log("恶魔王冠·提示：未到 666 时 7 行（6 属性 + 灰说明）、666 后 8 行且条件行整句 #C03030="
				+ tooltipOk + "（应 true，条件行「" + conditional.getString() + "」）");
		com.summy.reliquary.effect.PlayerFlags.setHellLocked(player, false);
		player.getInventory().clearContent();
		resetDemonPactState(player);
		unequip(player, ReliquarySlots.HALO);
	}

	/**
	 * 1.7.1：光环提示改造（7 条属性行随倍率）+ 启示祝福（低血刷新原版「生命回复」）+ 签约/忏悔不影响光环与王冠。
	 */
	private static void checkHaloShiftAndRegen(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.HALO);
		unequip(player, ReliquarySlots.CHARM);
		unequip(player, ReliquarySlots.REVELATION);
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		equip(player, ReliquarySlots.HALO, SummyReliquary.THE_HALO.get());

		// ① 倍率：无魂印 = 1、七罪之源 + 已激活一项 = 0.5、美德 = 2、撒旦圣经 = 0
		setSinMasksForTest(player, 0, 0);
		double factorPlain = com.summy.reliquary.effect.HaloState.multiplier(player);
		String plainLine = haloShiftLine(player, 1);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		setSinMasksForTest(player, 0b1, 0);
		double factorSin = com.summy.reliquary.effect.HaloState.multiplier(player);
		String sinLine = haloShiftLine(player, 1);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.VIRTUES.get());
		double factorVirtue = com.summy.reliquary.effect.HaloState.multiplier(player);
		String virtueLine = haloShiftLine(player, 1);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		double factorBible = com.summy.reliquary.effect.HaloState.multiplier(player);
		String bibleLine = haloShiftLine(player, 1);
		// 7 行全部对得上是"配置值 × 倍率"
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.VIRTUES.get());
		boolean allSeven = true;
		StringBuilder sevenBad = new StringBuilder();
		for (int index = 1; index <= 7; index++) {
			String actual = haloShiftLine(player, index);
			String expected = com.summy.reliquary.item.ReliquaryTooltips.statComponent(
					com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.the_halo.shift." + index,
					trimNumber(haloValue(index, 2.0D))).getString();
			if (!actual.equals(expected)) {
				allSeven = false;
				sevenBad.append('#').append(index).append(" 实际「").append(actual).append("」/期望「")
						.append(expected).append("」 ");
			}
		}
		boolean factorOk = Math.abs(factorPlain - 1.0D) < 1.0E-6D
				&& Math.abs(factorSin - 0.5D) < 1.0E-6D
				&& Math.abs(factorVirtue - 2.0D) < 1.0E-6D
				&& Math.abs(factorBible) < 1.0E-6D
				&& plainLine.contains("+" + trimNumber(haloValue(1, 1.0D)))
				&& sinLine.contains("+" + trimNumber(haloValue(1, 0.5D)))
				&& virtueLine.contains("+" + trimNumber(haloValue(1, 2.0D)))
				// 戴撒旦圣经时整行为「最大生命 +0」（结尾是 +0，不是空格加 0）
				&& bibleLine.endsWith("+0") && allSeven;

		// ② 条件行：未获取启示 → 不出现；获取过 → 第 8 行，整句 #FFE4B5、「生命回复」#FFD700
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		setSinMasksForTest(player, 0, 0);
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		List<Component> withoutCondition = com.summy.reliquary.item.TheHaloItem.shiftLines(player);
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, true);
		List<Component> withCondition = com.summy.reliquary.item.TheHaloItem.shiftLines(player);
		Component conditional = withCondition.get(withCondition.size() - 1);
		boolean conditionalOk = withoutCondition.size() == 7 && withCondition.size() == 8
				&& "#FFE4B5".equals(colorOf(conditional))
				&& conditional.getSiblings().size() == 1
				&& "#FFD700".equals(colorOf(conditional.getSiblings().get(0)));

		// ③ 回血：佩戴光环 + 获取过启示 + 生命 < 50% → 每秒刷新原版「生命回复」（不受倍率影响）
		player.removeAllEffects();
		player.setHealth(player.getMaxHealth());
		boolean idleAtFull = !com.summy.reliquary.effect.HaloBlessing.shouldRegen(player);
		player.setHealth(player.getMaxHealth() * 0.4F);
		boolean wantsAtLow = com.summy.reliquary.effect.HaloBlessing.shouldRegen(player);
		com.summy.reliquary.effect.HaloBlessing.tickPlayer(player);
		var regen = player.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
		int regenAmplifier = regen == null ? -1 : regen.getAmplifier();
		int regenDuration = regen == null ? -1 : regen.getDuration();
		// 回到 50% 以上 → 不再刷新（清掉后 tick 一次不会重新挂上）
		player.setHealth(player.getMaxHealth());
		player.removeEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
		com.summy.reliquary.effect.HaloBlessing.tickPlayer(player);
		boolean noRefreshAtHigh = !player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
		// 没获取启示 → 即使低血也不触发
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		player.setHealth(player.getMaxHealth() * 0.4F);
		boolean noRevelationNoRegen = !com.summy.reliquary.effect.HaloBlessing.shouldRegen(player);
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, true);
		boolean regenOk = idleAtFull && wantsAtLow && regenAmplifier == 0 && regenDuration == 40
				&& noRefreshAtHigh && noRevelationNoRegen;

		// ④ 签约 / 忏悔都不动光环与王冠（只有创世纪会清）
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		equip(player, ReliquarySlots.HALO, SummyReliquary.THE_HALO.get());
		player.getInventory().add(new ItemStack(SummyReliquary.DEVIL_CROWN.get()));
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		boolean afterSigning = wears(player, SummyReliquary.THE_HALO.get())
				&& countItemEverywhere(player, SummyReliquary.DEVIL_CROWN.get()) == 1;
		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		boolean afterContrition = wears(player, SummyReliquary.THE_HALO.get())
				&& countItemEverywhere(player, SummyReliquary.DEVIL_CROWN.get()) == 1;

		log("光环提示（1.7.1）：倍率 无魂印=" + factorPlain + "（应 1.0）、七罪之源+已激活=" + factorSin
				+ "（应 0.5）、美德=" + factorVirtue + "（应 2.0）、撒旦圣经=" + factorBible + "（应 0.0）；"
				+ "7 行数值随倍率正确=" + factorOk + "（应 true）——最大生命行：「" + plainLine + "」/「"
				+ sinLine + "」/「" + virtueLine + "」/「" + bibleLine + "」");
		if (sevenBad.length() > 0) {
			log("   光环第 index 行不一致（诊断）：" + sevenBad.toString().trim());
		}
		log("光环条件行（1.7.1）：未获取启示=7 行、获取后=8 行且整句 #FFE4B5 +「生命回复」#FFD700="
				+ conditionalOk + "（应 true，条件行「" + conditional.getString() + "」）");
		log("光环启示回血（1.7.1）：满血不触发=" + idleAtFull + "（应 true）、40% 血触发=" + wantsAtLow
				+ "（应 true）、挂上的原版「生命回复」amp=" + regenAmplifier + "（应 0）、时长=" + regenDuration
				+ " tick（应 40）；回到 50% 以上不再刷新=" + noRefreshAtHigh + "（应 true）、无启示不触发="
				+ noRevelationNoRegen + "（应 true）→ " + regenOk + "（应 true）");
		log("光环/王冠·签约与忏悔：签约后仍在（光环=" + wears(player, SummyReliquary.THE_HALO.get()) + "、王冠×"
				+ countItemEverywhere(player, SummyReliquary.DEVIL_CROWN.get()) + "）=" + afterSigning
				+ "（应 true）、忏悔后仍在=" + afterContrition + "（应 true）");
		resetDemonPactState(player);
		player.getInventory().clearContent();
		unequip(player, ReliquarySlots.HALO);
		unequip(player, ReliquarySlots.SOUL_SEAL);
	}

	/** 光环第 index 行的原始文本（用与物品提示同一个拼行函数） */
	private static String haloShiftLine(ServerPlayer player, int index) {
		List<Component> lines = com.summy.reliquary.item.TheHaloItem.shiftLines(player);
		return index - 1 < lines.size() ? lines.get(index - 1).getString() : "无";
	}

	/** 光环第 index 项属性的配置值 × 倍率 */
	private static double haloValue(int index, double factor) {
		return switch (index) {
			case 1 -> com.summy.reliquary.config.ReliquaryConfig.haloMaxHealth() * factor;
			case 2 -> com.summy.reliquary.config.ReliquaryConfig.haloAttackDamage() * factor;
			case 3 -> com.summy.reliquary.config.ReliquaryConfig.haloAttackSpeed() * factor;
			case 4 -> com.summy.reliquary.config.ReliquaryConfig.haloArmor() * factor;
			case 5 -> com.summy.reliquary.config.ReliquaryConfig.haloArmorToughness() * factor;
			case 6 -> com.summy.reliquary.config.ReliquaryConfig.haloMovementPercent() * factor;
			default -> com.summy.reliquary.config.ReliquaryConfig.haloBreakSpeedPercent() * factor;
		};
	}

	/** 1.7.1：圣心配方补上门禁 —— 无天使标记合成 → 收走产物 + 退回 6/2/1；有标记 → 放行 */
	private static void checkSacredHeartGate(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.SACRED_HEART.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.SACRED_HEART.get()), player.inventoryMenu.getCraftSlots()));
		// 被拦下时产物必须被收走（光标上 / 背包里都不该剩下）
		boolean productRemains = player.inventoryMenu.getCarried().is(SummyReliquary.SACRED_HEART.get())
				|| countItem(player, SummyReliquary.SACRED_HEART.get()) > 0;
		boolean blockedRefunded = countItem(player, SummyReliquary.HEART_SHARD.get()) == 6
				&& countItem(player, Items.BLAZE_POWDER) == 2
				&& countItem(player, SummyReliquary.WOODEN_CROSS.get()) == 1;
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();
		// 有天使标记 → 正常放行
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.SACRED_HEART.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.SACRED_HEART.get()), player.inventoryMenu.getCraftSlots()));
		boolean allowed = player.inventoryMenu.getCarried().is(SummyReliquary.SACRED_HEART.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();
		// JEI 可见性：无标记 → 隐藏、有标记 → 可见（与三件套/圣光/斗篷一致）
		boolean jeiOk = Boolean.FALSE.equals(com.summy.reliquary.effect.SpiritAltarRecipeGate
				.jeiVisibility(false, false, false, false).get(SummyReliquary.id("sacred_heart")))
				&& Boolean.TRUE.equals(com.summy.reliquary.effect.SpiritAltarRecipeGate
						.jeiVisibility(true, false, false, false).get(SummyReliquary.id("sacred_heart")));
		log("圣心配方门禁（1.7.1）：无天使标记 → 产物被收走（产物残留=" + productRemains + "，应 false）、"
				+ "退料 心之碎片×" + countItem(player, SummyReliquary.HEART_SHARD.get()) + " + 烈焰粉×"
				+ countItem(player, Items.BLAZE_POWDER) + " + 木十字架×"
				+ countItem(player, SummyReliquary.WOODEN_CROSS.get()) + "（退料正确=" + blockedRefunded
				+ "，应 true；上面已把背包清掉所以显示 0/0/0）；有标记放行=" + allowed
				+ "（应 true）、JEI 按标记切换=" + jeiOk + "（应 true）");
		player.getInventory().clearContent();
	}

	/** 1.7.1：两条末影龙挑战对恶魔线的排斥（曾签约即永久排除，只有创世纪能清 `demon_sealed`） */
	private static void checkDragonGuardDemon(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		// ① 当前是恶魔（持标记）：杀龙 → 不置裁决、不转化美德
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		setSinMasksForTest(player, 0b1111111, 0b1111111);
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		com.summy.reliquary.advancement.SinChallenges.onDragonSlainBy(player);
		boolean demonVerdictNone = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
				== com.summy.reliquary.advancement.SinChallenges.VERDICT_NONE;
		boolean demonStillSource = wears(player, SummyReliquary.SOURCE_OF_SINS.get());

		// ② 曾签约、当前不是恶魔：同样排除
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		com.summy.reliquary.advancement.SinChallenges.onDragonSlainBy(player);
		boolean sealedVerdictNone = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
				== com.summy.reliquary.advancement.SinChallenges.VERDICT_NONE;
		boolean sealedStillSource = wears(player, SummyReliquary.SOURCE_OF_SINS.get());

		// ③ 无恶魔史：照旧 —— 佩戴七罪之源 + 七罪全未激活 → 纯洁无瑕（换美德）
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		setSinMasksForTest(player, 0, 0);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		com.summy.reliquary.advancement.SinChallenges.onDragonSlainBy(player);
		int plainVerdict = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player);
		boolean plainOk = plainVerdict == com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS;

		log("末影龙挑战·恶魔线排斥（1.7.1）：当前是恶魔 → 裁决仍 NONE=" + demonVerdictNone
				+ "（应 true）、七罪之源未被换掉=" + demonStillSource + "（应 true）；曾签约 → 裁决仍 NONE="
				+ sealedVerdictNone + "（应 true）、七罪之源未被换掉=" + sealedStillSource + "（应 true）；"
				+ "无恶魔史 → 照旧判为纯洁无瑕=" + plainOk + "（应 true，裁决=" + plainVerdict + "）");
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
		player.getInventory().clearContent();
	}

	/** 1.7.1：撒旦圣经转化必须**佩戴**契约 + 契约发放优先装背包里那份（不新造、不增量） */
	private static void checkBibleRequiresContract(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearRobeAndSeal(player);
		unequip(player, ReliquarySlots.DEMON_PACT);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);

		// ① 契约只在背包、栏位空 → 不转化
		player.getInventory().add(new ItemStack(SummyReliquary.THE_PACT.get()));
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		setSinMasksForTest(player, 0b1111111, 0);
		com.summy.reliquary.effect.DemonPact.tickSatanicBible(player);
		boolean bagOnlyNotConverted = wears(player, SummyReliquary.SOURCE_OF_SINS.get())
				&& !wears(player, SummyReliquary.SATANIC_BIBLE.get());
		// ② 自愈：把背包里那份装进栏位，数量不增加；装进栏位后同一秒就转化
		int pactBefore = countItemEverywhere(player, SummyReliquary.THE_PACT.get());
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		boolean healedWorn = com.summy.reliquary.effect.DemonPact.wearsContract(player);
		int pactAfter = countItemEverywhere(player, SummyReliquary.THE_PACT.get());
		boolean noDuplicate = pactBefore == 1 && pactAfter == 1;
		boolean converted = wears(player, SummyReliquary.SATANIC_BIBLE.get());
		// ③ 忏悔后没契约 → 即使七罪全激活也不再转化
		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		setSinMasksForTest(player, 0b1111111, 0);
		com.summy.reliquary.effect.DemonPact.tickSatanicBible(player);
		boolean afterContritionNotConverted = wears(player, SummyReliquary.SOURCE_OF_SINS.get())
				&& !wears(player, SummyReliquary.SATANIC_BIBLE.get())
				&& !com.summy.reliquary.effect.DemonPact.wearsContract(player);
		log("撒旦圣经·需佩戴契约（1.7.1）：契约只在背包 → 不转化=" + bagOnlyNotConverted + "（应 true）；"
				+ "自愈把背包那份装进栏位=" + healedWorn + "（应 true）、契约数量 " + pactBefore + " → " + pactAfter
				+ "（应 1 → 1，不新造）=" + noDuplicate + "（应 true）；其后转化撒旦圣经=" + converted
				+ "（应 true）；忏悔后无契约 → 不再转化=" + afterContritionNotConverted + "（应 true）");
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
	}

	// ==================== 1.7.2：三条封锁 / 没收摘除 / 魂印四态 / 档位与阈值 / 创世纪表现 ====================

	/** 把某物品塞进指定栏位的指定格（自检搭场景用，1.7.2） */
	private static void equipAt(ServerPlayer player, String slot, int index, net.minecraft.world.item.Item item) {
		CuriosApi.getCuriosInventory(player).ifPresent(handler ->
				handler.setEquippedCurio(slot, index, new ItemStack(item)));
	}

	/** 1.7.2：恶魔交易的三条封锁（已启示 / 已放弃一切 / 持有圣心）+ 提示口径 */
	private static void checkTradeLocks(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.HALO);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.PENTAGRAM.get()));
		prepareDemonDeal(player);
		// 长按蓄满的复核条件里还有"已经听过邀请"，搭场景时补上（prepareDemonDeal 不含它）
		com.summy.reliquary.effect.PlayerFlags.setDemonInvited(player, true);

		// ① 三条封锁都关着 → 可签
		boolean baseOpen = com.summy.reliquary.effect.DemonDeal.isQualified(player)
				&& com.summy.reliquary.effect.DemonDeal.canSign(player);

		// ② 已获取启示 → 关（且驻留也不推进任何对话）
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, true);
		boolean revelationLocked = !com.summy.reliquary.effect.DemonDeal.isQualified(player)
				&& !com.summy.reliquary.effect.DemonDeal.canSign(player)
				&& com.summy.reliquary.effect.DemonDeal.tradeClosed(player);
		String beforeDwell = com.summy.reliquary.effect.DemonDeal.lastDialogue();
		dwellDemonDeal(player);
		boolean revelationSilent = java.util.Objects.equals(beforeDwell,
				com.summy.reliquary.effect.DemonDeal.lastDialogue());
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);

		// ③ 已放弃一切（无罪之人）→ 关
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, true);
		boolean renouncedLocked = !com.summy.reliquary.effect.DemonDeal.isQualified(player)
				&& com.summy.reliquary.effect.DemonDeal.tradeClosed(player);
		com.summy.reliquary.effect.PlayerFlags.setSinRenounced(player, false);

		// ④ 持有圣心（背包 / 饰品栏任一）→ 关；右键 PASS 且没有任何提示；）收走后恢复
		player.getInventory().add(new ItemStack(SummyReliquary.SACRED_HEART.get()));
		boolean heartHeld = com.summy.reliquary.effect.DemonDeal.holdsSacredHeart(player);
		boolean heartLocked = !com.summy.reliquary.effect.DemonDeal.isQualified(player)
				&& com.summy.reliquary.effect.DemonDeal.tradeClosed(player);
		boolean passWhileLocked = SummyReliquary.PENTAGRAM.get()
				.use(player.serverLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
				.getResult() == net.minecraft.world.InteractionResult.PASS;
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		boolean heartReleased = com.summy.reliquary.effect.DemonDeal.isQualified(player)
				&& !com.summy.reliquary.effect.DemonDeal.tradeClosed(player);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		boolean heartWornLocked = !com.summy.reliquary.effect.DemonDeal.isQualified(player);
		unequip(player, ReliquarySlots.BLESSING);

		// ⑤ 提示：交易关闭 → 恶魔话语不显示（第 4 参 tradeClosed）
		int modeClosed = com.summy.reliquary.item.PentagramItem.spokenMode(true, false, false, true);

		log("交易封锁（1.7.2）：三条都关着时可签=" + baseOpen + "（应 true）；已启示 → 关=" + revelationLocked
				+ "（应 true）、驻留静默=" + revelationSilent + "（应 true）；已放弃一切 → 关="
				+ renouncedLocked + "（应 true）；持有圣心（背包）→ 关=" + heartLocked
				+ "（应 true，holdsSacredHeart=" + heartHeld + "）、右键 PASS 无提示=" + passWhileLocked
				+ "（应 true）、收走后恢复可签=" + heartReleased + "（应 true）、戴在加护栏也算持有="
				+ heartWornLocked + "（应 true）；交易关闭时提示四态值=" + modeClosed + "（应 0）");
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.DemonDeal.setForcedValley(null);
		resetDemonPactState(player);
		clearRobeAndSeal(player);
	}

	/** 1.7.2：签约的没收（天使线可没收档）+ 只摘除（天启 / 神性 / 圣心）+ 星的发放标记重置 */
	private static void checkPactConfiscation(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.HALO);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		// 可没收档：全部放进背包
		for (var item : new net.minecraft.world.item.Item[]{SummyReliquary.HOLY_LIGHT.get(),
				SummyReliquary.SALVATION.get(), SummyReliquary.HOLY_MANTLE.get(),
				SummyReliquary.THE_BODY.get(), SummyReliquary.THE_MIND.get(),
				SummyReliquary.THE_SOUL.get(), SummyReliquary.STAR_OF_BETHLEHEM.get()}) {
			player.getInventory().add(new ItemStack(item));
		}
		// 1.7.9：四把武器**不在没收清单**里 —— 签约前后都必须在身上（一把不少、也不重复发）
		for (var weapon : new net.minecraft.world.item.Item[]{SummyReliquary.SACRIFICIAL_DAGGER.get(),
				SummyReliquary.DARK_ARTS.get(), SummyReliquary.HOLY_SPEAR.get(),
				SummyReliquary.SERAPH_SPEAR.get()}) {
			player.getInventory().add(new ItemStack(weapon));
		}
		// 只摘除档：天启戴在启示之座、圣心戴在加护栏、神性放背包（证明背包里的不动）
		// 注意：先用天使线把栏位撑开（灵台 3 / 启示 1 / 加护 2），否则这两格是 0 格、根本戴不上
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.FINAL_REVELATION.get());
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		player.getInventory().add(new ItemStack(SummyReliquary.GODHEAD.get()));

		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);

		boolean lightGone = countItem(player, SummyReliquary.HOLY_LIGHT.get()) == 0;
		boolean salvationGone = countItem(player, SummyReliquary.SALVATION.get()) == 0;
		boolean mantleGone = countItem(player, SummyReliquary.HOLY_MANTLE.get()) == 0;
		boolean starGone = countItem(player, SummyReliquary.STAR_OF_BETHLEHEM.get()) == 0;
		boolean altarGone = countItem(player, SummyReliquary.THE_BODY.get()) == 0
				&& countItem(player, SummyReliquary.THE_MIND.get()) == 0
				&& countItem(player, SummyReliquary.THE_SOUL.get()) == 0;
		// 三件套全被没收 → 补偿 1 个咒印（不是「6」）
		boolean compensated = countItem(player, SummyReliquary.THE_MARK.get()) == 1
				&& countItem(player, SummyReliquary.SIX.get()) == 0;
		// 只摘除：天启 / 圣心 从栏位进背包（仍在），神性本来就在背包（不动）
		boolean revelationKept = !wears(player, SummyReliquary.FINAL_REVELATION.get())
				&& countItemEverywhere(player, SummyReliquary.FINAL_REVELATION.get()) == 1;
		boolean sacredHeartKept = !wears(player, SummyReliquary.SACRED_HEART.get())
				&& countItemEverywhere(player, SummyReliquary.SACRED_HEART.get()) == 1;
		boolean godheadKept = countItemEverywhere(player, SummyReliquary.GODHEAD.get()) == 1;
		boolean starFlagCleared = !com.summy.reliquary.effect.PlayerFlags.isStarGranted(player);
		boolean weaponsKept = countItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get()) == 1
				&& countItemEverywhere(player, SummyReliquary.DARK_ARTS.get()) == 1
				&& countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get()) == 1
				&& countItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get()) == 1;

		log("签约没收（1.7.2）：圣光 / 救恩 / 斗篷 被删=" + (lightGone && salvationGone && mantleGone)
				+ "（应 true）、伯列恒之星被删=" + starGone + "（应 true）、灵台三件套被删=" + altarGone
				+ "（应 true）、补偿=咒印×1 且无「6」=" + compensated + "（应 true）；天启 / 圣心只被摘到背包="
				+ (revelationKept && sacredHeartKept) + "（应 true）、背包里的神性不动=" + godheadKept
				+ "（应 true）；星之发放标记已清=" + starFlagCleared + "（应 true）；"
				+ "四把武器都没被没收（1.7.9）=" + weaponsKept + "（应 true）");
		player.getInventory().clearContent();
		for (var weapon : new net.minecraft.world.item.Item[]{SummyReliquary.SACRIFICIAL_DAGGER.get(),
				SummyReliquary.DARK_ARTS.get(), SummyReliquary.HOLY_SPEAR.get(),
				SummyReliquary.SERAPH_SPEAR.get()}) {
			clearItemEverywhere(player, weapon);
		}
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		unequip(player, ReliquarySlots.REVELATION);
		resetDemonPactState(player);
		clearRobeAndSeal(player);
	}

	/** 1.7.2：忏悔的没收（666 前可得的恶魔线饰品）+ 只摘除（666 之后可得的）+ 恶魔王冠不没收不摘除 */
	private static void checkRepentConfiscation(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.HALO);
		unequip(player, ReliquarySlots.SPIRIT_ALTAR);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		// 666 之前可得的恶魔线饰品（应被没收）
		equip(player, ReliquarySlots.BACK, SummyReliquary.CEREMONIAL_ROBES.get());
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		equipAt(player, ReliquarySlots.BLESSING, 0, SummyReliquary.VENGEFUL_SPIRIT.get());
		equipAt(player, ReliquarySlots.BLESSING, 1, SummyReliquary.NIGHT_WRAITH.get());
		// 666 之后才得的（只摘除）+ 恶魔王冠（不收不摘）
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.BRIMSTONE.get());
		equip(player, ReliquarySlots.HALO, SummyReliquary.DEVIL_CROWN.get());
		player.getInventory().add(new ItemStack(SummyReliquary.ABYSS_LORD.get()));
		// 1.7.9：四把武器在忏悔时也不没收、不摘除（签约时已经自动发了一把匕首，所以这里用"前后数量不变"判定）
		for (var weapon : new net.minecraft.world.item.Item[]{SummyReliquary.SACRIFICIAL_DAGGER.get(),
				SummyReliquary.DARK_ARTS.get(), SummyReliquary.HOLY_SPEAR.get(),
				SummyReliquary.SERAPH_SPEAR.get()}) {
			player.getInventory().add(new ItemStack(weapon));
		}
		int daggerBefore = countItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get());
		int darkArtsBefore = countItemEverywhere(player, SummyReliquary.DARK_ARTS.get());
		int holySpearBefore = countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		int seraphSpearBefore = countItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());

		boolean swapped = com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);

		boolean robeGone = countItemEverywhere(player, SummyReliquary.CEREMONIAL_ROBES.get()) == 0;
		boolean markGone = countItemEverywhere(player, SummyReliquary.THE_MARK.get()) == 0;
		boolean vengefulGone = countItemEverywhere(player, SummyReliquary.VENGEFUL_SPIRIT.get()) == 0;
		boolean wraithGone = countItemEverywhere(player, SummyReliquary.NIGHT_WRAITH.get()) == 0;
		boolean pactGone = !com.summy.reliquary.effect.DemonPact.hasContract(player)
				&& com.summy.reliquary.effect.DemonPact.slotCount(player) == 0;
		boolean brimstoneKept = !wears(player, SummyReliquary.BRIMSTONE.get())
				&& countItemEverywhere(player, SummyReliquary.BRIMSTONE.get()) == 1;
		boolean abyssLordKept = countItemEverywhere(player, SummyReliquary.ABYSS_LORD.get()) == 1;
		boolean crownUntouched = wears(player, SummyReliquary.DEVIL_CROWN.get());
		boolean weaponsKept = countItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get()) == daggerBefore
				&& countItemEverywhere(player, SummyReliquary.DARK_ARTS.get()) == darkArtsBefore
				&& countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get()) == holySpearBefore
				&& countItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get()) == seraphSpearBefore
				&& daggerBefore >= 1 && darkArtsBefore == 1 && holySpearBefore == 1 && seraphSpearBefore == 1;
		boolean angelBack = com.summy.reliquary.effect.PlayerFlags.hasAngel(player)
				&& !com.summy.reliquary.effect.PlayerFlags.isDemon(player);

		log("忏悔没收（1.7.2）：法袍 / 咒印 / 复仇之魂 / 夜之幽魂 被删="
				+ (robeGone && markGone && vengefulGone && wraithGone) + "（应 true）、契约与契约栏已收回="
				+ pactGone + "（应 true）；硫磺火只被摘到背包=" + brimstoneKept + "（应 true）、背包里的深渊领主不动="
				+ abyssLordKept + "（应 true）、恶魔王冠不收不摘=" + crownUntouched + "（应 true）；换回天使标记="
				+ (swapped && angelBack) + "（应 true）；四把武器都没被没收（1.7.9）=" + weaponsKept
				+ "（应 true）");
		player.getInventory().clearContent();
		for (var weapon : new net.minecraft.world.item.Item[]{SummyReliquary.SACRIFICIAL_DAGGER.get(),
				SummyReliquary.DARK_ARTS.get(), SummyReliquary.HOLY_SPEAR.get(),
				SummyReliquary.SERAPH_SPEAR.get()}) {
			clearItemEverywhere(player, weapon);
		}
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		unequip(player, ReliquarySlots.HALO);
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearSpiritAltar(player);
	}

	/** 1.7.2：签约时的魂印栏四态（美德 / 圣经 / 七罪之源 / 空栏）与快照取值 */
	private static void checkSealSnapshotFourStates(ServerPlayer player) {
		// ① 美德：快照 virtues，魂印栏换成七罪之源，七罪（全已赎罪）→ 全未激活；忏悔拿回美德 + 掩码
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		setSinMasksForTest(player, 0b1111111, 0b1111111);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.VIRTUES.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		String virtuesSnapshot = com.summy.reliquary.effect.PlayerFlags.sinSnapshot(player).getString("item");
		boolean virtuesSwapped = wears(player, SummyReliquary.SOURCE_OF_SINS.get());
		boolean virtuesAllCleared = com.summy.reliquary.sin.SinManager.mask(player) == 0
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == 0;
		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		boolean virtuesRestored = wears(player, SummyReliquary.VIRTUES.get())
				&& com.summy.reliquary.sin.SinManager.mask(player) == 0b1111111
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == 0b1111111;

		// ② 撒旦圣经：快照 source_of_sins，圣经被这场交易吞掉（换成七罪之源）
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		setSinMasksForTest(player, 0b1111111, 0);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		String bibleSnapshot = com.summy.reliquary.effect.PlayerFlags.sinSnapshot(player).getString("item");
		boolean bibleSwapped = wears(player, SummyReliquary.SOURCE_OF_SINS.get())
				&& countItemEverywhere(player, SummyReliquary.SATANIC_BIBLE.get()) == 0;
		// ③ 空栏（背包里拿着那份）：快照 none，魂印栏与背包数量都不变（不白送）
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		unequip(player, ReliquarySlots.SOUL_SEAL);
		player.getInventory().add(new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		String emptySnapshot = com.summy.reliquary.effect.PlayerFlags.sinSnapshot(player).getString("item");
		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		boolean emptyStaysEmpty = !wears(player, SummyReliquary.SOURCE_OF_SINS.get())
				&& countItemEverywhere(player, SummyReliquary.SOURCE_OF_SINS.get()) == 1;
		// ④ 空栏且身上一件魂印物品都没有（/clear 类异常）：安全网补一份七罪之源
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		unequip(player, ReliquarySlots.SOUL_SEAL);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		boolean safetyNet = countItemEverywhere(player, SummyReliquary.SOURCE_OF_SINS.get()) == 1;

		log("魂印四态（1.7.2）：美德 → 快照「" + virtuesSnapshot + "」（应 virtues）、魂印栏换七罪之源="
				+ virtuesSwapped + "（应 true）、全已赎罪 → 全未激活=" + virtuesAllCleared
				+ "（应 true）、忏悔后拿回美德且掩码 127/127=" + virtuesRestored + "（应 true）");
		log("魂印四态（1.7.2）：撒旦圣经 → 快照「" + bibleSnapshot + "」（应 source_of_sins）、圣经被吞（栏位变七罪之源）="
				+ bibleSwapped + "（应 true）；空栏 → 快照「" + emptySnapshot + "」（应 none）、忏悔后魂印栏仍空且背包那份未被复制="
				+ emptyStaysEmpty + "（应 true）；空栏 + 身上完全没有魂印物品 → 安全网补 1 份=" + safetyNet
				+ "（应 true）");
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
	}

	/** 1.7.2：撒旦圣经只继承"激活档"（暴怒 1.5 / 怠惰抗性 I）+ 贪婪阈值 ≥36 / ≤35 */
	private static void checkBibleTierAndGreedThreshold(ServerPlayer player) {
		// ① 圣经：暴怒随机上限 = 1.5
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		// Sin 的枚举顺序：傲慢0 贪婪1 色欲2 嫉妒3 暴食4 **暴怒5** **怠惰6**
		// → 暴怒 = 0b0100000、怠惰 = 0b1000000
		setSinMasksForTest(player, 0b1100000, 0);   // 暴怒 + 怠惰
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		float bibleMax = 0.0F;
		for (int index = 0; index < 300; index++) {
			float rolled = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, player,
					player.damageSources().playerAttack(player), 10.0F);
			bibleMax = Math.max(bibleMax, rolled / 10.0F);
		}
		// ② 圣经：怠惰抗性 = I（amp 0）
		setSinMasksForTest(player, 0b1100000, 0);
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		var bibleResistance = player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		int bibleAmp = bibleResistance == null ? -1 : bibleResistance.getAmplifier();
		player.removeAllEffects();
		// ③ 对照：真正已赎罪（戴七罪之源）→ 暴怒上限 2.0、怠惰抗性 II
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		setSinMasksForTest(player, 0, 0b1100000);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		float redeemedMax = 0.0F;
		for (int index = 0; index < 300; index++) {
			float rolled = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, player,
					player.damageSources().playerAttack(player), 10.0F);
			redeemedMax = Math.max(redeemedMax, rolled / 10.0F);
		}
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		var redeemedResistance = player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		int redeemedAmp = redeemedResistance == null ? -1 : redeemedResistance.getAmplifier();
		player.removeAllEffects();
		log(String.format("圣经档位（1.7.2）：戴撒旦圣经时暴怒上限=%.2f（应 ≈1.50，不再是 2.0）、怠惰抗性 amp=%d"
				+ "（应 0 = I）；对照「真正已赎罪」暴怒上限=%.2f（应 ≈2.00）、怠惰抗性 amp=%d（应 1 = II）",
				bibleMax, bibleAmp, redeemedMax, redeemedAmp));

		// ④ 贪婪阈值：35 未觉醒 / 36 觉醒；36 增伤不吃惩罚 / 35 吃惩罚 / 40 封顶前 +40%
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		resetSinState(player);
		player.getInventory().add(new ItemStack(Items.DIAMOND, 35));
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		boolean notAwakened35 = com.summy.reliquary.sin.SinManager.state(player, Sin.GREED)
				== com.summy.reliquary.sin.SinManager.SinState.UNACTIVATED;
		player.getInventory().add(new ItemStack(Items.DIAMOND, 1));
		com.summy.reliquary.sin.SinEffects.tickPlayer(player);
		boolean awakened36 = com.summy.reliquary.sin.SinManager.state(player, Sin.GREED)
				== com.summy.reliquary.sin.SinManager.SinState.ACTIVATED;
		float at36 = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, player,
				player.damageSources().playerAttack(player), 10.0F);
		com.summy.reliquary.sin.SinEffects.removeDiamonds(player, 1);
		float at35 = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, player,
				player.damageSources().playerAttack(player), 10.0F);
		player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
		float at40 = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, player,
				player.damageSources().playerAttack(player), 10.0F);
		log(String.format("贪婪阈值（1.7.2）：35 颗未觉醒=%s（应 true）、36 颗觉醒=%s（应 true）；36 颗 10 → %.1f"
				+ "（应 13.6 = +36%% 且不吃惩罚）、35 颗 10 → %.1f（应 1.0 = 一成惩罚）、40 颗 10 → %.1f（应 14.0）",
				notAwakened35, awakened36, at36, at35, at40));
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
	}

	/** 1.7.2：创世纪生效时的收尾表现（送回重生点 + 图腾动画 + 音效） */
	private static void checkGenesisActivation(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.PlayerFlags.setRevelationObtained(player, false);
		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		com.summy.reliquary.item.GenesisItem.resetActivationCountForTest();

		// 个人重生点 = 当前位置；然后故意跑远，确认"用掉创世纪"会把玩家送回那个点
		net.minecraft.core.BlockPos anchor = player.blockPosition();
		player.setRespawnPosition(player.serverLevel().dimension(), anchor, 0.0F, true, false);
		player.teleportTo(anchor.getX() + 40.5D, anchor.getY(), anchor.getZ() + 40.5D);
		double movedAway = Math.sqrt(player.blockPosition().distSqr(anchor));

		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		boolean used = com.summy.reliquary.item.GenesisItem.confirm(player);
		double distanceToAnchor = Math.sqrt(player.blockPosition().distSqr(anchor));
		boolean teleported = distanceToAnchor <= 2.0D;
		int animations = com.summy.reliquary.item.GenesisItem.activationCount();
		int sounds = com.summy.reliquary.item.GenesisItem.soundCount();
		boolean protocol12 = "12".equals(com.summy.reliquary.net.ReliquaryNetworking.protocolVersion());

		log(String.format("创世纪表现（1.7.2）：用掉=%s（应 true）、使用前离重生点 %.1f 格 → 使用后 %.1f 格"
				+ "（应 ≤2 = 已送回重生点）=%s（应 true）、图腾动画计数=%d（应 >0）、音效计数=%d（应 >0）、协议=12=%s（应 true）",
				used, movedAway, distanceToAnchor, teleported, animations, sounds, protocol12));
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
	}

	/** 1.7.2：伯列恒之星的闭环 —— 签约清标记，日后重新戴齐三件套能补发（不会卡死） */
	private static void checkStarRegrantLoop(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		com.summy.reliquary.effect.PlayerFlags.setStarGranted(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		boolean clearedOnSign = !com.summy.reliquary.effect.PlayerFlags.isStarGranted(player);
		com.summy.reliquary.item.ActOfContritionItem.swapDemonToAngel(player);
		// 忏悔后重新合成三件套并戴上（灵台 3 格）
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		equipAt(player, ReliquarySlots.SPIRIT_ALTAR, 0, SummyReliquary.THE_BODY.get());
		equipAt(player, ReliquarySlots.SPIRIT_ALTAR, 1, SummyReliquary.THE_MIND.get());
		equipAt(player, ReliquarySlots.SPIRIT_ALTAR, 2, SummyReliquary.THE_SOUL.get());
		boolean fullSet = com.summy.reliquary.effect.SpiritAltarSet.isFullSet(player);
		com.summy.reliquary.advancement.SinChallenges.tickStar(player);
		int stars = countItemEverywhere(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		boolean flagSet = com.summy.reliquary.effect.PlayerFlags.isStarGranted(player);
		com.summy.reliquary.advancement.SinChallenges.tickStar(player);
		int starsAfterSecond = countItemEverywhere(player, SummyReliquary.STAR_OF_BETHLEHEM.get());
		log("星之闭环（1.7.2）：签约即时已清发放标记=" + clearedOnSign + "（应 true）；忏悔后重新戴齐三件套="
				+ fullSet + "（应 true）→ 补发星×" + stars + "（应 1）、标记已置位=" + flagSet
				+ "（应 true）、再跑一次仍×" + starsAfterSecond + "（应 1 = 只发一次）");
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
	}

	/** 1.7.2：外力清掉恶魔标记（例如 OP angel grant）后，自愈按"完整忏悔"收尾 */
	private static void checkAbnormalUnmarkRestore(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		setSinMasksForTest(player, 0b1111000, 0b0000111);   // 3 赎 + 4 激活
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.VENGEFUL_SPIRIT.get());
		int maskAfterPact = com.summy.reliquary.sin.SinManager.mask(player);
		int redeemedAfterPact = com.summy.reliquary.sin.SinManager.redeemedMask(player);

		// 模拟 OP /summyreliquary angel grant：只置天使标记（会清掉恶魔标记），契约与栏位还在
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);

		boolean pactGone = !com.summy.reliquary.effect.DemonPact.hasContract(player)
				&& com.summy.reliquary.effect.DemonPact.slotCount(player) == 0;
		boolean masksRestored = com.summy.reliquary.sin.SinManager.mask(player) == 0b1111111
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == 0b0000111;
		boolean snapshotCleared = !com.summy.reliquary.effect.PlayerFlags.hasSinSnapshot(player);
		boolean vengefulUnequipped = !wears(player, SummyReliquary.VENGEFUL_SPIRIT.get())
				&& countItemEverywhere(player, SummyReliquary.VENGEFUL_SPIRIT.get()) == 1;
		com.summy.reliquary.effect.DemonPact.tickPlayer(player);
		boolean idempotent = com.summy.reliquary.sin.SinManager.mask(player) == 0b1111111
				&& !com.summy.reliquary.effect.DemonPact.hasContract(player);

		log("异常清标记（1.7.2）：签约后掩码=" + Integer.toBinaryString(maskAfterPact) + "/"
				+ Integer.toBinaryString(redeemedAfterPact) + "（应 1111000/0）；外力清标记 → 契约与栏位收回="
				+ pactGone + "（应 true）、七罪按快照还原 127/7=" + masksRestored + "（应 true）、快照已清="
				+ snapshotCleared + "（应 true）、对方线饰品只摘不删=" + vengefulUnequipped
				+ "（应 true）、再跑一次幂等=" + idempotent + "（应 true）");
		// 收尾必须把恶魔线饰品全部卸下：否则复仇之魂的火焰环会一直每 tick 发粒子（1.7.2 踩过）
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		unequip(player, ReliquarySlots.BACK);
		unequip(player, ReliquarySlots.REVELATION);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
	}

	// ==================== 1.7.3：粒子路径 + 创世纪防丢失 / 重新武装 / 内存清理 ====================

	/**
	 * 1.7.4：自愈链的前置门 —— "纯洁无瑕"的每秒自愈不能把戴撒旦圣经的玩家写成全赎罪。
	 *
	 * <p>旧顺序是先 {@code markAllRedeemed} 再尝试转化：戴圣经（魂印栏不是七罪之源）时，
	 * 七罪会先被写成全赎罪、紧接着转化失败，留下"圣经 + 全赎罪"的残留态，
	 * 于是圣经就会按赎罪后档结算（暴怒 2.0 / 怠惰抗性 II）。现在先做无副作用探测。
	 */
	private static void checkBibleSelfHealGuard(ServerPlayer player) {
		// ① 圣经 + 裁决 FLAWLESS + 七罪全未激活 → 必须原样不动（不写全赎罪、不转美德、不给天使标记）
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		unequip(player, ReliquarySlots.SOUL_SEAL);
		player.removeAllEffects();
		resetSinState(player);
		// 迁移位先置位：否则 selfHeal 的"老存档补天使标记"那一段会干扰"没给标记"的断言
		com.summy.reliquary.effect.PlayerFlags.setAngelMigrated(player, true);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player,
				com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS);
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);

		boolean bibleUntouched = com.summy.reliquary.sin.SinManager.mask(player) == 0
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == 0;
		boolean bibleStays = wears(player, SummyReliquary.SATANIC_BIBLE.get());
		boolean noAngelFromBible = !com.summy.reliquary.effect.PlayerFlags.hasAngel(player);

		// ② 对照：同样条件但戴七罪之源 → 照旧全赎 + 转美德 + 给天使标记
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player,
				com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS);
		unequip(player, ReliquarySlots.SOUL_SEAL);
		resetSinState(player);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean converted = wears(player, SummyReliquary.VIRTUES.get())
				&& com.summy.reliquary.effect.PlayerFlags.hasAngel(player)
				&& com.summy.reliquary.sin.SinManager.redeemedMask(player) == 0b1111111;

		log("自愈前置门（1.7.4）：戴撒旦圣经 + FLAWLESS + 七罪全未激活 → 七罪原样不动（仍全未激活）="
				+ bibleUntouched + "（应 true）、圣经仍在魂印栏=" + bibleStays + "（应 true）、没给天使标记="
				+ noAngelFromBible + "（应 true）；对照戴七罪之源 → 全赎 + 转美德 + 给天使标记=" + converted
				+ "（应 true）");

		// 收尾复位（避免影响后面的用例）
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.SOUL_SEAL);
		player.getInventory().clearContent();
		resetSinState(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.advancement.SinChallenges.resetVerdict(player);
	}

	// ==================== 1.7.5：两把仪式匕首 + 遁入暗影 ====================

	/**
	 * 1.7.5：两把仪式匕首的物品规格 + 右键技能「遁入暗影」全流程。
	 *
	 * <p>用一个"带圣心（+30% 全伤害最终倍率）"的场面同时验证两件事：
	 * 基础斩击**吃**增伤（实际扣血明显高于理论值），而强力斩击的数值＝理论总和、**不再**被增伤放大。
	 */
	private static void checkRitualDaggers(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		clearNearbyMonsters(player, 16.0D);

		ItemStack dagger = new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get());
		ItemStack darkArts = new ItemStack(SummyReliquary.DARK_ARTS.get());
		boolean sword = dagger.getItem() instanceof net.minecraft.world.item.SwordItem
				&& darkArts.getItem() instanceof net.minecraft.world.item.SwordItem;
		boolean tag = dagger.is(net.minecraft.tags.ItemTags.SWORDS)
				&& darkArts.is(net.minecraft.tags.ItemTags.SWORDS);
		boolean durability = dagger.getMaxDamage() == 666 && darkArts.getMaxDamage() == 1666;
		boolean enchant = dagger.getItem().getEnchantmentValue() == 22
				&& darkArts.getItem().getEnchantmentValue() == 25;
		boolean repair = dagger.getItem().isValidRepairItem(dagger,
				new ItemStack(net.minecraft.world.item.Items.IRON_INGOT))
				&& !dagger.getItem().isValidRepairItem(dagger,
						new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS))
				&& darkArts.getItem().isValidRepairItem(darkArts,
						new ItemStack(net.minecraft.world.item.Items.NETHERITE_SCRAP))
				&& !darkArts.getItem().isValidRepairItem(darkArts,
						new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
		// 伤害 / 攻速：直接读物品属性修饰符（手持时 = 玩家基础 1 + 修饰符；攻速 = 4.0 + 修饰符）
		double daggerDamage = dagger.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_DAMAGE).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		double daggerSpeed = dagger.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_SPEED).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		double darkArtsDamage = darkArts.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_DAMAGE).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		double darkArtsSpeed = darkArts.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_SPEED).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		boolean attack4 = Math.abs(daggerDamage - 3.0D) < 0.001D && Math.abs(daggerSpeed + 1.6D) < 0.001D;
		boolean attack6 = Math.abs(darkArtsDamage - 5.0D) < 0.001D && Math.abs(darkArtsSpeed + 2.0D) < 0.001D;
		// 1.7.7：圣光短矛 —— 剑类 / 7 伤 1.0 速（修饰符 +6 / -3.0）/ 耐久 1000 / 附魔 25 / 金锭修理
		ItemStack spear = new ItemStack(SummyReliquary.HOLY_SPEAR.get());
		double spearDamage = spear.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_DAMAGE).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		double spearSpeed = spear.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_SPEED).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		boolean spearSpec = spear.getItem() instanceof net.minecraft.world.item.SwordItem
				&& spear.is(net.minecraft.tags.ItemTags.SWORDS)
				&& Math.abs(spearDamage - 6.0D) < 0.001D && Math.abs(spearSpeed + 3.0D) < 0.001D
				&& spear.getMaxDamage() == 1000 && spear.getItem().getEnchantmentValue() == 25
				&& spear.getItem().isValidRepairItem(spear, new ItemStack(Items.GOLD_INGOT))
				&& !spear.getItem().isValidRepairItem(spear, new ItemStack(Items.IRON_INGOT));
		// 1.7.8：炽天使之枪 —— 剑类 / 10 伤 1.0 速（+9 / -3.0）/ 耐久 2222 / 附魔 30 / 心之碎片修理
		ItemStack seraph = new ItemStack(SummyReliquary.SERAPH_SPEAR.get());
		double seraphDamage = seraph.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_DAMAGE).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		double seraphSpeed = seraph.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(Attributes.ATTACK_SPEED).stream()
				.mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
		boolean seraphSpec = seraph.getItem() instanceof net.minecraft.world.item.SwordItem
				&& seraph.is(net.minecraft.tags.ItemTags.SWORDS)
				&& Math.abs(seraphDamage - 9.0D) < 0.001D && Math.abs(seraphSpeed + 3.0D) < 0.001D
				&& seraph.getMaxDamage() == 2222 && seraph.getItem().getEnchantmentValue() == 30
				&& seraph.getItem().isValidRepairItem(seraph, new ItemStack(SummyReliquary.HEART_SHARD.get()))
				&& !seraph.getItem().isValidRepairItem(seraph, new ItemStack(Items.GOLD_INGOT));
		// 1.7.8：投掷系统 —— 实体已注册、"投掷源 == 近战源"（所以近战增伤照吃）、幻影不可拾取且锁定面板值
		// 1.7.10：金刀片的投掷实体也要注册
		boolean thrownRegistered = SummyReliquary.THROWN_SPEAR.isPresent()
				&& SummyReliquary.THROWN_RAZOR.isPresent();
		var throwSource = player.damageSources().playerAttack(player);
		boolean sourceIsMelee = throwSource.getDirectEntity() == player;
		net.minecraft.world.entity.monster.Zombie spearDummy = spawnTestZombie(player, 2.0D, 0.0D);
		float viaThrow = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, spearDummy,
				throwSource, 10.0F);
		float viaMelee = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, spearDummy,
				player.damageSources().playerAttack(player), 10.0F);
		boolean sameAsMelee = Math.abs(viaThrow - viaMelee) < 0.0001F;
		float panelDamage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		var phantom = new com.summy.reliquary.entity.ThrownSpear(player.serverLevel(), player, spear, panelDamage);
		boolean phantomOk = Math.abs(phantom.getLockedAttackDamage() - panelDamage) < 0.0001F
				&& !phantom.isPickupAllowed()
				&& phantom.getDisplayStack().is(SummyReliquary.HOLY_SPEAR.get());
		phantom.discard();
		spearDummy.discard();
		log("仪式匕首·规格（1.7.5）：剑类=" + sword + "（应 true）、#swords 标签=" + tag
				+ "（应 true）、耐久 666/1666=" + durability + "（应 true）、附魔等级 22/25=" + enchant
				+ "（应 true）、修理材料（铁锭 / 下界合金碎片，不认木板与铁锭）=" + repair
				+ "（应 true）；献祭匕首 4 伤 / 2.4 速（修饰符 +3 / -1.6）=" + attack4
				+ "（应 true）、暗仪刺刀 6 伤 / 2.0 速（修饰符 +5 / -2.0）=" + attack6 + "（应 true）；"
				+ "圣光短矛（1.7.7）：剑类 + #swords + 7 伤 1.0 速（+6 / -3.0）+ 耐久 1000 + 附魔 25 + 金锭修理="
				+ spearSpec + "（应 true）；炽天使之枪（1.7.8）：剑类 + #swords + 10 伤 1.0 速（+9 / -3.0）+ "
				+ "耐久 2222 + 附魔 30 + 心之碎片修理=" + seraphSpec + "（应 true）；投掷系统（1.7.8）：实体已注册="
				+ thrownRegistered + "（应 true）、投掷源判定为近战=" + sourceIsMelee + "（应 true）、"
				+ "投掷增伤与左键近战一致=" + sameAsMelee + "（应 true）、幻影锁定面板值且不可拾取=" + phantomOk
				+ "（应 true）");

		// ===== 技能全流程 =====
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		resetSinState(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		clearNearbyMonsters(player, 16.0D);
		// 圣心 = 唯一的增伤来源（+30% 全伤害最终倍率），用来区分"基础斩击吃增伤 / 强力斩击不吃"
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.SACRED_HEART.get());
		player.setItemSlot(EquipmentSlot.MAINHAND, dagger);
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		player.setHealth(player.getMaxHealth());
		float theory = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);

		// 3 只站在接触范围内（会被标记）、1 只在 2.5 格（只吃强力斩击）、1 只在 5 格（完全不吃）
		net.minecraft.world.entity.monster.Zombie first = spawnTestZombie(player, 1.5D, 0.0D);
		net.minecraft.world.entity.monster.Zombie second = spawnTestZombie(player, 0.0D, 1.5D);
		net.minecraft.world.entity.monster.Zombie third = spawnTestZombie(player, -1.5D, 0.0D);
		net.minecraft.world.entity.monster.Zombie heavyOnly = spawnTestZombie(player, 2.5D, 0.0D);
		net.minecraft.world.entity.monster.Zombie untouched = spawnTestZombie(player, 5.0D, 0.0D);

		// 1.7.9：四把武器都要达标才能用 —— 匕首的判据是恶魔标记（暗仪刺刀另需 700）。
		// 注意：必须在 clearBlessingSlots 之后设（它内部会 ensureAngelLine → 把恶魔标记换回天使标记）。
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		// 技能门槛的诊断（恶魔标记 / 冷却 / 是否已有技能在跑）
		boolean gateDebug = com.summy.reliquary.effect.WeaponGates.qualified(player, dagger.getItem());
		boolean demonDebug = com.summy.reliquary.effect.PlayerFlags.isDemon(player);
		boolean cooldownDebug = player.getCooldowns().isOnCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		boolean activeDebug = com.summy.reliquary.effect.ShadowDash.isActive(player);
		boolean started = com.summy.reliquary.effect.ShadowDash.tryStart(player, dagger, false);
		boolean active = com.summy.reliquary.effect.ShadowDash.isActive(player);
		double speedBonus = com.summy.reliquary.effect.ShadowDash.movementBonus(player);

		// 判定期（献祭匕首 20 tick）：受击被取消、击退被取消
		float healthBefore = player.getHealth();
		player.invulnerableTime = 0;
		player.hurt(player.damageSources().generic(), 4.0F);
		boolean hurtCanceled = player.getHealth() >= healthBefore;
		boolean knockCanceled = false;
		var knock = new net.minecraftforge.event.entity.living.LivingKnockBackEvent(player, 1.0F, 1.0D, 0.0D);
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(knock);
		knockCanceled = knock.isCanceled() || knock.getStrength() == 0.0F;

		// 推进到判定期结束（第 20 次 tick 进入结算期）
		for (int index = 0; index < 20; index++) {
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
		}
		int marks = com.summy.reliquary.effect.ShadowDash.markCount(player);
		boolean resolving = com.summy.reliquary.effect.ShadowDash.isResolving(player);
		double speedAfterResolve = com.summy.reliquary.effect.ShadowDash.movementBonus(player);
		float heavyLocked = com.summy.reliquary.effect.ShadowDash.heavyDamage(player);
		boolean heavyTheoryOk = Math.abs(heavyLocked - theory * 3.0F) < 0.05F;

		// 每 2 tick 结算一个标记目标：再 6 tick 三个标记全部结算完（此刻还没放强力斩击）
		for (int index = 0; index < 6; index++) {
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
		}
		float markedLost = first.getMaxHealth() - first.getHealth();
		// 再 1 tick 放强力斩击
		com.summy.reliquary.effect.ShadowDash.tickServer(server);
		float heavyLost = heavyOnly.getMaxHealth() - heavyOnly.getHealth();
		float untouchedLost = untouched.getMaxHealth() - untouched.getHealth();
		// 基础斩击吃了圣心的 1.3 倍；强力斩击按理论总和结算（若被二次放大，数值会接近 1.3 倍）
		boolean baseBuffed = markedLost > theory;
		boolean heavyNotBuffed = heavyLost <= heavyLocked * 1.15F && heavyLost > heavyLocked * 0.7F;
		boolean awayUntouched = untouchedLost == 0.0F;

		boolean finished = !com.summy.reliquary.effect.ShadowDash.isActive(player);
		boolean cooldown = player.getCooldowns().isOnCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		boolean speedRestored = Math.abs(com.summy.reliquary.effect.ShadowDash.movementBonus(player)) < 0.001D;

		log("遁入暗影（1.7.5）：触发=" + started + "（应 true）[诊断：达标=" + gateDebug + "、恶魔标记=" + demonDebug
				+ "、冷却中=" + cooldownDebug + "、已有技能=" + activeDebug + "]、无敌=" + active + "（应 true）、加速="
				+ String.format("%.0f%%", speedBonus) + "（应 100%）、无敌期间受击被取消=" + hurtCanceled
				+ "（应 true）、击退被取消=" + knockCanceled + "（应 true）；标记=" + marks
				+ "（应 3 = 接触范围内的三只，同一目标不重复）、进入结算=" + resolving
				+ "（应 true）、结算时加速已关闭=" + (Math.abs(speedAfterResolve) < 0.001D)
				+ "（应 true）、锁定强力伤害=" + String.format("%.2f", heavyLocked) + "（应 "
				+ String.format("%.2f", theory * 3.0F) + " = 理论总和）=" + heavyTheoryOk
				+ "；基础斩击扣血=" + String.format("%.2f", markedLost) + "（应 > 理论 "
				+ String.format("%.2f", theory) + "，吃到圣心 +30%）=" + baseBuffed
				+ "、强力斩击扣血=" + String.format("%.2f", heavyLost) + "（应 ≈ " + String.format("%.2f", heavyLocked)
				+ "，不再吃增伤）=" + heavyNotBuffed + "、5 格外未受影响=" + awayUntouched
				+ "（应 true）；技能已结束=" + finished + "（应 true）、冷却已写入=" + cooldown
				+ "（应 true）、加速已收回=" + speedRestored + "（应 true）");

		// 收尾
		first.discard();
		second.discard();
		third.discard();
		heavyOnly.discard();
		untouched.discard();
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		player.getInventory().clearContent();
		clearBlessingSlots(player);
		com.summy.reliquary.effect.ShadowDash.clear();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
	}

	// ==================== 1.7.6：两把匕首的获取、门禁与保护 ====================

	/**
	 * 1.7.6：两张配方 / 签约发放 / 防丢失门禁 / 700 台词 / 文案 / 掉落与死亡保护。
	 */
	private static void checkDaggerAcquisition(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		net.minecraft.server.level.ServerLevel level = player.serverLevel();

		// ① 两张配方都在
		boolean recipes = server.getRecipeManager().byKey(SummyReliquary.id("sacrificial_dagger")).isPresent()
				&& server.getRecipeManager().byKey(SummyReliquary.id("dark_arts")).isPresent();
		// ② 700 解锁台词：1.7.10 起是"2 条暗红台词 + 灰「你解锁了」 + 灰尾行（匕首那行）"= 4 行，
		//    尾部行的语言键与译文都在（`tailKey` 机制）
		boolean unlockLines = com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.lineCount() == 2
				&& com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.tailKey() != null
				&& translated("message.summy-reliquary.pact.unlock.700.3");
		// ③ 文案：风味 + Shift（暗仪刺刀比献祭匕首多一行括注）+ 防丢失两行
		boolean texts = translated("item.summy-reliquary.sacrificial_dagger.tagline.1")
				&& translated("item.summy-reliquary.sacrificial_dagger.shift.1")
				&& translated("item.summy-reliquary.sacrificial_dagger.shift.4")
				&& !translated("item.summy-reliquary.sacrificial_dagger.shift.5")
				&& translated("item.summy-reliquary.dark_arts.tagline.1")
				&& translated("item.summy-reliquary.dark_arts.shift.4")
				&& translated("item.summy-reliquary.dark_arts.shift.5")
				&& translated("message.summy-reliquary.dagger.recovery.1")
				&& translated("message.summy-reliquary.dagger.recovery.2");

		// ④ 签约发放：一把就够，再签一次不重复发
		resetDemonPactState(player);
		// 真实签约流程会先把玩家标成恶魔（见 PentagramItem），这里照做，防丢失的"已签约"判据才成立
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		clearItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get());
		clearItemEverywhere(player, SummyReliquary.DARK_ARTS.get());
		player.getInventory().clearContent();
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		int afterFirst = countItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get());
		com.summy.reliquary.effect.DemonPact.grant(player, true);
		int afterSecond = countItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get());
		boolean signGrant = afterFirst == 1 && afterSecond == 1;

		// ⑤ 防丢失：连续 N 秒没匕首 → 开放；未开放强合被拦并退料；开放后放行；拿回匕首即关闭
		clearItemEverywhere(player, SummyReliquary.SACRIFICIAL_DAGGER.get());
		clearItemEverywhere(player, SummyReliquary.DARK_ARTS.get());
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerMissingSeconds(player, 0);
		int need = com.summy.reliquary.config.ReliquaryConfig.daggerRecoverySeconds();
		com.summy.reliquary.effect.DaggerRecovery.tickForTest(player, need);
		boolean opened = com.summy.reliquary.effect.DaggerRecovery.isOpen(player);

		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);
		player.getInventory().clearContent();
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
				player, new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()),
				player.inventoryMenu.getCraftSlots()));
		boolean blocked = !player.inventoryMenu.getCarried().is(SummyReliquary.SACRIFICIAL_DAGGER.get())
				&& countItem(player, SummyReliquary.SACRIFICIAL_DAGGER.get()) == 0;
		boolean refunded = countItem(player, Items.NETHERITE_SCRAP) == 1
				&& countItem(player, Items.IRON_INGOT) == 1
				&& countItem(player, Items.OBSIDIAN) == 1;
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
				player, new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()),
				player.inventoryMenu.getCraftSlots()));
		boolean allowedWhenOpen = player.inventoryMenu.getCarried().is(SummyReliquary.SACRIFICIAL_DAGGER.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()));
		com.summy.reliquary.effect.DaggerRecovery.tickForTest(player, 1);
		boolean closedAfterRegain = !com.summy.reliquary.effect.DaggerRecovery.isOpen(player)
				&& com.summy.reliquary.effect.DaggerRecovery.missingSeconds(player) == 0;
		player.getInventory().clearContent();

		// ⑥ 暗仪刺刀门禁：未解锁 → 拦下 + 退料；700 解锁 → 放行
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, true);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.DARK_ARTS.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
				player, new ItemStack(SummyReliquary.DARK_ARTS.get()), player.inventoryMenu.getCraftSlots()));
		boolean darkBlocked = !player.inventoryMenu.getCarried().is(SummyReliquary.DARK_ARTS.get());
		boolean darkRefunded = countItem(player, Items.NETHER_STAR) == 1
				&& countItem(player, SummyReliquary.SACRIFICIAL_DAGGER.get()) == 1
				&& countItem(player, Items.OBSIDIAN) == 2
				&& countItem(player, Items.NETHERITE_INGOT) == 1;
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.bit());
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.DARK_ARTS.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
				player, new ItemStack(SummyReliquary.DARK_ARTS.get()), player.inventoryMenu.getCraftSlots()));
		boolean darkAllowed = player.inventoryMenu.getCarried().is(SummyReliquary.DARK_ARTS.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		// ⑦ 掉落保护 + 死亡不掉落（剔除掉落 + 按"原玩家有"补发）
		net.minecraft.world.entity.item.ItemEntity daggerDrop = new net.minecraft.world.entity.item.ItemEntity(
				level, player.getX(), player.getY() + 1.0D, player.getZ(),
				new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.EntityJoinLevelEvent(daggerDrop, level));
		boolean dropProtected = daggerDrop.isInvulnerable() && daggerDrop.lifespan == Integer.MAX_VALUE;
		java.util.List<net.minecraft.world.entity.item.ItemEntity> drops = new java.util.ArrayList<>();
		drops.add(daggerDrop);
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.living.LivingDropsEvent(
				player, player.damageSources().generic(), drops, 0, true));
		boolean stripped = drops.isEmpty();
		daggerDrop.discard();
		com.summy.reliquary.item.RitualDaggerItem.restoreOnDeath(player, true, true);
		int restoredCount = countItem(player, SummyReliquary.SACRIFICIAL_DAGGER.get())
				+ countItem(player, SummyReliquary.DARK_ARTS.get());
		player.getInventory().clearContent();
		com.summy.reliquary.item.RitualDaggerItem.restoreOnDeath(player, false, false);
		int notRestoredCount = countItem(player, SummyReliquary.SACRIFICIAL_DAGGER.get())
				+ countItem(player, SummyReliquary.DARK_ARTS.get());
		boolean restoreOk = restoredCount == 2 && notRestoredCount == 0;

		log("匕首获取与保护（1.7.6）：两张配方在=" + recipes + "（应 true）、700 台词 3 行=" + unlockLines
				+ "（应 true）、文案（风味 + Shift，暗仪刺刀多一行）= " + texts + "（应 true）；签约发 1 把且不重复="
				+ signGrant + "（应 true，实测 " + afterFirst + "/" + afterSecond + "）；防丢失（" + need
				+ " 秒）开放=" + opened + "（应 true）、未开放强合被拦=" + blocked + "（应 true）、退料 碎片/铁锭/黑曜石=1/1/1="
				+ refunded + "（应 true）、开放后放行=" + allowedWhenOpen + "（应 true）、拿回匕首即关闭="
				+ closedAfterRegain + "（应 true）；暗仪刺刀未解锁被拦=" + darkBlocked + "（应 true）、退料"
				+ " 星/匕首/黑曜石/合金锭=1/1/2/1=" + darkRefunded + "（应 true）、700 后放行=" + darkAllowed
				+ "（应 true）；掉落物受保护=" + dropProtected + "（应 true）、死亡掉落里不含匕首=" + stripped
				+ "（应 true）、复活补发（有→补 2 / 无→不补）=" + restoreOk + "（应 true）");

		// 收尾复位
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerMissingSeconds(player, 0);
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		resetSinState(player);
	}

	/** 清掉玩家附近的敌对生物（自检搭场景用；不会动玩家与其它实体） */
	private static void clearNearbyMonsters(ServerPlayer player, double radius) {
		for (LivingEntity entity : player.serverLevel().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius))) {
			if (entity instanceof net.minecraft.world.entity.monster.Monster) {
				entity.discard();
			}
		}
	}

	/** 在玩家身边生成一只不动的僵尸（自检搭场景用） */
	private static net.minecraft.world.entity.monster.Zombie spawnTestZombie(ServerPlayer player,
			double dx, double dz) {
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		net.minecraft.world.entity.monster.Zombie zombie =
				new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
		zombie.moveTo(player.getX() + dx, player.getY(), player.getZ() + dz, 0.0F, 0.0F);
		zombie.setNoAi(true);
		zombie.setPersistenceRequired();
		level.addFreshEntity(zombie);
		return zombie;
	}

	/** 1.7.3：创世纪防丢失 —— 防火、掉落实体保护、死亡不掉、主动丢弃不补发 */
	private static void checkGenesisLossProtection(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.GENESIS.get());
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());

		// ① 物品本身防火（掉进岩浆/火里不会被烧掉）
		boolean fireResistant = new ItemStack(SummyReliquary.GENESIS.get()).getItem().isFireResistant();

		// ② 掉落实体保护：EntityJoinLevelEvent 后应 invulnerable + lifespan 拉满（永不 despawn）
		net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
				player.serverLevel(), player.getX(), player.getY() + 1.0D, player.getZ(),
				new ItemStack(SummyReliquary.GENESIS.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.EntityJoinLevelEvent(
				drop, player.serverLevel()));
		boolean dropProtected = drop.isInvulnerable() && drop.lifespan == Integer.MAX_VALUE;

		// ③ 死亡掉落列表里不应出现创世纪（避免和复活补发重复）
		List<net.minecraft.world.entity.item.ItemEntity> drops = new java.util.ArrayList<>();
		drops.add(drop);
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.living.LivingDropsEvent(
				player, player.damageSources().generic(), drops, 0, true));
		boolean strippedFromDrops = drops.isEmpty();
		drop.discard();

		// ④ 死亡补发：原玩家身上有 → 补 1；原玩家身上没有（主动丢弃 / 丢入虚空）→ 不补
		player.getInventory().clearContent();
		boolean restored = com.summy.reliquary.item.GenesisItem.restoreOnDeath(player, true);
		int afterRestore = countItem(player, SummyReliquary.GENESIS.get());
		player.getInventory().clearContent();
		boolean noRestoreWhenDiscarded = !com.summy.reliquary.item.GenesisItem.restoreOnDeath(player, false);
		int afterDiscard = countItem(player, SummyReliquary.GENESIS.get());
		boolean restoreOk = restored && afterRestore == 1 && noRestoreWhenDiscarded && afterDiscard == 0;

		log("创世纪防丢失（1.7.3）：物品防火=" + fireResistant + "（应 true）、掉落实体被保护（invulnerable + "
				+ "lifespan 拉满）=" + dropProtected + "（应 true）、死亡掉落里不含创世纪=" + strippedFromDrops
				+ "（应 true）；死亡补发（原玩家有）=" + restored + "（应 true，补发后×" + afterRestore
				+ " 应 1）、主动丢弃后不补发=" + noRestoreWhenDiscarded + "（应 true，×" + afterDiscard
				+ " 应 0）→ " + restoreOk + "（应 true）");
		player.getInventory().clearContent();
		resetDemonPactState(player);
	}

	/** 1.7.3：创世纪用掉后重新武装两条线（能再拿、不能使用）+ 内存态全清 */
	private static void checkGenesisReweaponAndMemory(ServerPlayer player) {
		resetDemonPactState(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.GENESIS.get());
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.HALO);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		com.summy.reliquary.effect.PlayerFlags.setGenesisGrantedGodhead(player, true);
		com.summy.reliquary.effect.PlayerFlags.setGenesisGrantedAbaddon(player, true);
		com.summy.reliquary.item.GenesisItem.resetUsed(player);

		// ① 先造出各种"内存态"（全部走各自的自检钩子）：只关心"创世纪会不会把它们清干净"，
		//    所以不掺入"是否戴契约 / 亚巴顿冷却是否就绪 / 是否在峡谷"这些无关前置，避免断言被它们带偏
		com.summy.reliquary.effect.SoulShield.setGuardForTest(player, 100);
		com.summy.reliquary.effect.Abaddon.setGuardAndAuraForTest(player, 100);
		com.summy.reliquary.effect.RevelationBeam.markBusyForTest(player, 200);
		com.summy.reliquary.effect.DamagePools.markPendingForTest(player);
		com.summy.reliquary.effect.DemonPact.primeBlackHeartRefillForTest(player);
		com.summy.reliquary.effect.DemonDeal.setDwellForTest(player, 3);

		boolean beforeSoul = com.summy.reliquary.effect.SoulShield.guardUntil(player) > 0;
		boolean beforeAbaddonGuard = com.summy.reliquary.effect.Abaddon.guardUntil(player) > 0;
		boolean beforeAbaddonAura = com.summy.reliquary.effect.Abaddon.auraUntil(player) > 0;
		boolean beforeCooldown = com.summy.reliquary.effect.RevelationBeam
				.cooldownRemainingForTest(player) > 0;
		boolean beforeCharging = com.summy.reliquary.effect.RevelationBeam.isCharging(player);
		boolean beforePending = com.summy.reliquary.effect.DamagePools.hasPending(player);
		boolean beforeRefill = com.summy.reliquary.effect.DemonPact
				.blackHeartRefillRemainingForTest(player) > 0;
		boolean beforeDwell = com.summy.reliquary.effect.DemonDeal.dwellSecondsForTest(player) > 0;
		boolean allSet = beforeSoul && beforeAbaddonGuard && beforeAbaddonAura && beforeCooldown
				&& beforeCharging && beforePending && beforeRefill && beforeDwell;

		// ② 用掉创世纪（真实 confirm 流程）
		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		boolean used = com.summy.reliquary.item.GenesisItem.confirm(player);

		// ③ 重置后：发放标记被清空（可以重新武装）、内存态全清
		boolean grantsCleared = !com.summy.reliquary.effect.PlayerFlags.isGenesisGrantedGodhead(player)
				&& !com.summy.reliquary.effect.PlayerFlags.isGenesisGrantedAbaddon(player);
		boolean usedKept = com.summy.reliquary.item.GenesisItem.isUsed(player);
		boolean memoryCleared = com.summy.reliquary.effect.SoulShield.guardUntil(player) == 0
				&& com.summy.reliquary.effect.Abaddon.guardUntil(player) == 0
				&& com.summy.reliquary.effect.Abaddon.auraUntil(player) == 0
				&& com.summy.reliquary.effect.RevelationBeam.cooldownRemainingForTest(player) == 0
				&& !com.summy.reliquary.effect.RevelationBeam.isCharging(player)
				&& !com.summy.reliquary.effect.DamagePools.hasPending(player)
				&& com.summy.reliquary.effect.DemonPact.blackHeartRefillRemainingForTest(player) == -1
				&& com.summy.reliquary.effect.DemonDeal.dwellSecondsForTest(player) == 0;

		// ④ 重新武装：再走完天使线（神性）/ 恶魔线（亚巴顿）→ 各再拿 1 个，但**不能使用**
		boolean godheadGrant = com.summy.reliquary.item.GenesisItem.grantFrom(player, true);
		boolean abaddonGrant = com.summy.reliquary.item.GenesisItem.grantFrom(player, false);
		int again = countItem(player, SummyReliquary.GENESIS.get());
		boolean noThird = !com.summy.reliquary.item.GenesisItem.grantFrom(player, true);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.GENESIS.get()));
		boolean useBlocked = SummyReliquary.GENESIS.get()
				.use(player.serverLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
				.getResult() == net.minecraft.world.InteractionResult.FAIL;
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		boolean reweaponOk = used && grantsCleared && usedKept && godheadGrant && abaddonGrant
				&& again == 2 && noThird && useBlocked;

		log("创世纪重新武装 + 内存清理（1.7.3）：用掉创世纪=" + used + "（应 true）、发放标记已清空（神性 + 亚巴顿）="
				+ grantsCleared + "（应 true）、使用标记保留=" + usedKept + "（应 true）；内存态 全部就位（设置后）="
				+ allSet + "（应 true；明细：魂心=" + beforeSoul + "、亚巴顿守卫=" + beforeAbaddonGuard
				+ "、恶魔光环=" + beforeAbaddonAura + "、光束冷却=" + beforeCooldown + "、蓄力=" + beforeCharging
				+ "、伤害池挂起=" + beforePending + "、黑心补满计时=" + beforeRefill + "、恶魔交易驻留=" + beforeDwell
				+ "）→ 重置后全部清空=" + memoryCleared + "（应 true，含魂心无敌窗口 / 亚巴顿守卫与光环 / "
				+ "光束冷却与蓄力 / 伤害池挂起 / 黑心补满计时 / 恶魔交易驻留）；重新武装：神性发 1=" + godheadGrant
				+ "、亚巴顿再发 1=" + abaddonGrant + "（共×" + again + " 应 2）、第三次不发=" + noThird
				+ "（应 true）、右键被拒（已用过）=" + useBlocked + "（应 true）→ " + reweaponOk + "（应 true）");

		com.summy.reliquary.effect.DemonDeal.setForcedValley(null);
		com.summy.reliquary.effect.DemonDeal.resetForTest(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.GENESIS.get());
		clearItemEverywhere(player, SummyReliquary.SACRED_HEART.get());
		unequip(player, ReliquarySlots.REVELATION);
		unequip(player, ReliquarySlots.HALO);
		clearBlessingSlots(player);
		com.summy.reliquary.effect.SoulShield.clearGuardForTest(player);
		com.summy.reliquary.effect.DamagePools.forget(player);
		com.summy.reliquary.effect.Abaddon.forget(player);
		com.summy.reliquary.effect.RevelationBeam.forget(player);
		com.summy.reliquary.effect.DemonPact.forget(player);
		resetDemonPactState(player);
		resetSinState(player);
	}

	// ==================== 1.7.9：两把天使线长矛 + 四把武器的门槛 ====================

	/**
	 * 1.7.9：两张长矛配方（短矛的防丢失表 / 炽天使之枪的升级表）与它们的门禁行为。
	 *
	 * <p>材料 / 退料一致性由 {@code checkRecipeCoverage} 的自动比对覆盖，这里只补"配方内容对了没"
	 * 与"未达标时收走产物并原样退料"两件事。
	 */
	private static void checkSpearRecipesAndGates(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		var holyRecipe = server.getRecipeManager().byKey(SummyReliquary.id("holy_spear")).orElse(null);
		var seraphRecipe = server.getRecipeManager().byKey(SummyReliquary.id("seraph_spear")).orElse(null);
		boolean recipes = holyRecipe != null && seraphRecipe != null;
		java.util.Map<Item, Integer> holy = holyRecipe == null
				? java.util.Map.of() : ingredientCounts(holyRecipe);
		java.util.Map<Item, Integer> seraph = seraphRecipe == null
				? java.util.Map.of() : ingredientCounts(seraphRecipe);
		boolean holyMaterials = holy.getOrDefault(SummyReliquary.HOLY_LIGHT.get(), 0) == 1
				&& holy.getOrDefault(Items.TRIDENT, 0) == 1
				&& holy.getOrDefault(Items.GLOWSTONE_DUST, 0) == 2
				&& holy.getOrDefault(Items.GOLD_BLOCK, 0) == 1 && holy.size() == 4;
		boolean seraphMaterials = seraph.getOrDefault(Items.NETHER_STAR, 0) == 1
				&& seraph.getOrDefault(SummyReliquary.HEART_SHARD.get(), 0) == 2
				&& seraph.getOrDefault(SummyReliquary.HOLY_SPEAR.get(), 0) == 1
				&& seraph.getOrDefault(Items.GOLD_BLOCK, 0) == 1 && seraph.size() == 4;

		// ① 炽天使之枪：没有天使标记 → 收走产物 + 退料（星 1 / 心之碎片 2 / 短矛 1 / 金块 1）
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		clearItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.SERAPH_SPEAR.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.SERAPH_SPEAR.get()), player.inventoryMenu.getCraftSlots()));
		boolean seraphBlocked = !player.inventoryMenu.getCarried().is(SummyReliquary.SERAPH_SPEAR.get());
		boolean seraphRefunded = countItem(player, Items.NETHER_STAR) == 1
				&& countItem(player, SummyReliquary.HEART_SHARD.get()) == 2
				&& countItem(player, SummyReliquary.HOLY_SPEAR.get()) == 1
				&& countItem(player, Items.GOLD_BLOCK) == 1;
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		// ② 有天使标记 → 放行
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.SERAPH_SPEAR.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.SERAPH_SPEAR.get()), player.inventoryMenu.getCraftSlots()));
		boolean seraphAllowed = player.inventoryMenu.getCarried().is(SummyReliquary.SERAPH_SPEAR.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		// ③ 圣光短矛的防丢失配方：丢失态但**没有天使标记** → 收走 + 退料（圣光 1 / 三叉戟 1 / 萤石粉 2 / 金块 1）
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.HOLY_SPEAR.get()), player.inventoryMenu.getCraftSlots()));
		boolean holyBlocked = !player.inventoryMenu.getCarried().is(SummyReliquary.HOLY_SPEAR.get());
		boolean holyRefunded = countItem(player, SummyReliquary.HOLY_LIGHT.get()) == 1
				&& countItem(player, Items.TRIDENT) == 1
				&& countItem(player, Items.GLOWSTONE_DUST) == 2
				&& countItem(player, Items.GOLD_BLOCK) == 1;
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		// ④ 丢失态 + 天使标记 → 放行
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.HOLY_SPEAR.get()), player.inventoryMenu.getCraftSlots()));
		boolean holyAllowed = player.inventoryMenu.getCarried().is(SummyReliquary.HOLY_SPEAR.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		// ⑤ 只有天使标记、但没丢（未开放）→ 照样拦下（"仅在触发丢失后可用"）
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		player.inventoryMenu.setCarried(new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		MinecraftForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(player,
				new ItemStack(SummyReliquary.HOLY_SPEAR.get()), player.inventoryMenu.getCraftSlots()));
		boolean holyBlockedWhenPresent = !player.inventoryMenu.getCarried().is(SummyReliquary.HOLY_SPEAR.get());
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.getInventory().clearContent();

		log("长矛配方与门禁（1.7.9）：两张配方在=" + recipes + "（应 true）、短矛材料（圣光1/三叉戟1/萤石粉2/金块1）="
				+ holyMaterials + "（应 true）、炽天使材料（星1/心之碎片2/短矛1/金块1）=" + seraphMaterials
				+ "（应 true）；炽天使·无标记被拦=" + seraphBlocked + "（应 true）、退料=" + seraphRefunded
				+ "（应 true）、有标记放行=" + seraphAllowed + "（应 true）；短矛·丢失态+无标记被拦=" + holyBlocked
				+ "（应 true）、退料=" + holyRefunded + "（应 true）、丢失态+有标记放行=" + holyAllowed
				+ "（应 true）、没丢时被拦=" + holyBlockedWhenPresent + "（应 true）");

		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		clearItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		resetDemonPactState(player);
	}

	/** 1.7.9：投掷（只有炽天使之枪能投）+ 落点 4 格圣光爆发（14 真伤、吃无敌帧/抗性也不减）+ 20 tick 后删除 */
	private static void checkSpearThrowAndBurst(ServerPlayer player) {
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		clearNearbyMonsters(player, 16.0D);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);

		// ① 谁能投：圣光短矛不能、炽天使之枪能
		boolean holyThrowable = ((com.summy.reliquary.item.SpearItem)
				SummyReliquary.HOLY_SPEAR.get()).isThrowable();
		boolean seraphThrowable = ((com.summy.reliquary.item.SpearItem)
				SummyReliquary.SERAPH_SPEAR.get()).isThrowable();

		// ② 幻影锁定面板攻击力；伤害源与左键近战完全同源
		ItemStack seraph = new ItemStack(SummyReliquary.SERAPH_SPEAR.get());
		player.setItemSlot(EquipmentSlot.MAINHAND, seraph);
		float panel = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		com.summy.reliquary.entity.ThrownSpear phantom =
				new com.summy.reliquary.entity.ThrownSpear(level, player, seraph, panel);
		boolean locked = Math.abs(phantom.getLockedAttackDamage() - panel) < 0.0001F;
		boolean meleeSource = player.damageSources().playerAttack(player).getDirectEntity() == player;
		phantom.discard();

		// ③ 落点爆发：3 格内（钻石甲 + 抗性 IV + 20 tick 无敌帧）恰好 14；6.5 格外不吃
		Zombie near = spawnTestZombie(player, 3.0D, 0.0D);
		near.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		near.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		near.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		near.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		near.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 600, 3));
		near.invulnerableTime = 20;
		// 注意：半径是从**落点**（≈ 玩家 +3 格）算起的，所以"4 格外"要放到 9 格
		Zombie far = spawnTestZombie(player, 9.0D, 0.0D);

		com.summy.reliquary.entity.ThrownSpear burst =
				new com.summy.reliquary.entity.ThrownSpear(level, player, seraph, panel);
		Vec3 center = near.position();
		burst.burstForTest(center);
		float nearLost = near.getMaxHealth() - near.getHealth();
		float farLost = far.getMaxHealth() - far.getHealth();
		boolean once = burst.isBurstDone();
		burst.burstForTest(center);
		float nearLostAgain = near.getMaxHealth() - near.getHealth();
		boolean burstOnce = once && Math.abs(nearLostAgain - nearLost) < 0.001F;
		float expected = com.summy.reliquary.config.ReliquaryConfig.holyLightBurstDamage();
		boolean exact = Math.abs(nearLost - expected) < 0.001F;
		boolean outOfRange = farLost == 0.0F;
		burst.discard();

		// ④ 命中后停留 20 tick 再删除（不再飞回主人）
		com.summy.reliquary.entity.ThrownSpear life =
				new com.summy.reliquary.entity.ThrownSpear(level, player, seraph, panel);
		life.markLandedForTest();
		int ticks = 0;
		while (!life.isRemoved() && ticks < 200) {
			life.tick();
			ticks++;
		}
		boolean removedAfter20 = life.isRemoved() && ticks == 20;

		near.discard();
		far.discard();
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		player.getInventory().clearContent();
		resetDemonPactState(player);

		log("长矛投掷（1.7.9）：短矛可投=" + holyThrowable + "（应 false）、炽天使可投=" + seraphThrowable
				+ "（应 true）、幻影锁定面板值=" + locked + "（应 true，面板 " + String.format("%.2f", panel)
				+ "）、伤害源=近战源=" + meleeSource + "（应 true）；落点爆发：3 格内（钻石甲 + 抗性 IV + 无敌帧 20）扣血="
				+ String.format("%.2f", nearLost) + "（应恰好 " + String.format("%.0f", expected)
				+ "，不吃护甲 / 抗性 / 无敌帧）=" + exact + "、4 格外（距落点约 6 格）扣血="
				+ String.format("%.2f", farLost)
				+ "（应 0）=" + outOfRange + "、同一枚只爆一次=" + burstOnce + "（应 true）；命中后停留 "
				+ ticks + " tick 再删除（应 20）=" + removedAfter20 + "（应 true）");
	}

	/** 1.7.9：圣光短矛的获取（赎罪→美德自动发放）+ 防丢失配方状态机 + 掉落 / 死亡保护 */
	private static void checkSpearAcquisitionAndRecovery(ServerPlayer player) {
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		resetSinState(player);
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		clearItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearObtained(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearMissingSeconds(player, 0);

		// ① 转化发放：一次 1 把、重复调用不再发
		com.summy.reliquary.advancement.SinChallenges.grantAngelForConversion(player);
		int first = countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		boolean marked = com.summy.reliquary.effect.PlayerFlags.isSpearObtained(player)
				&& com.summy.reliquary.effect.PlayerFlags.hasAngel(player);
		com.summy.reliquary.advancement.SinChallenges.grantAngelForConversion(player);
		int second = countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		boolean grantOnce = first == 1 && second == 1 && marked;

		// ② 防丢失：连续 N 秒没有任何长矛 → 开放；拿回长矛 → 立刻关闭
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		clearItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearMissingSeconds(player, 0);
		int need = com.summy.reliquary.config.ReliquaryConfig.daggerRecoverySeconds();
		com.summy.reliquary.effect.SpearRecovery.tickForTest(player, need);
		boolean opened = com.summy.reliquary.effect.SpearRecovery.isOpen(player);
		player.getInventory().add(new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		com.summy.reliquary.effect.SpearRecovery.tickForTest(player, 1);
		boolean closedAfterRegain = !com.summy.reliquary.effect.SpearRecovery.isOpen(player)
				&& com.summy.reliquary.effect.SpearRecovery.missingSeconds(player) == 0;
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());

		// ③ 掉落保护 + 死亡不掉落（剔除掉落物 + 按"原玩家有"补发；主动丢弃不补发）
		net.minecraft.world.entity.item.ItemEntity holyDrop = new net.minecraft.world.entity.item.ItemEntity(
				level, player.getX(), player.getY() + 1.0D, player.getZ(),
				new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.EntityJoinLevelEvent(holyDrop, level));
		boolean dropProtected = holyDrop.isInvulnerable() && holyDrop.lifespan == Integer.MAX_VALUE;
		List<net.minecraft.world.entity.item.ItemEntity> drops = new java.util.ArrayList<>();
		drops.add(holyDrop);
		MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.living.LivingDropsEvent(
				player, player.damageSources().generic(), drops, 0, true));
		boolean stripped = drops.isEmpty();
		holyDrop.discard();
		com.summy.reliquary.effect.SpearRecovery.restoreOnDeath(player, true, true);
		int restored = countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get())
				+ countItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		clearItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.effect.SpearRecovery.restoreOnDeath(player, false, false);
		int notRestored = countItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get())
				+ countItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		boolean restoreOk = restored == 2 && notRestored == 0;

		log("长矛获取与防丢失（1.7.9）：转化发放 1 把且不重复=" + grantOnce + "（应 true，实测 "
				+ first + "/" + second + "）；防丢失（" + need + " 秒）开放=" + opened + "（应 true）、"
				+ "拿回长矛即关闭=" + closedAfterRegain + "（应 true）；掉落物受保护=" + dropProtected
				+ "（应 true）、死亡掉落里不含长矛=" + stripped + "（应 true）、复活补发（有→补 2 / 无→不补）="
				+ restoreOk + "（应 true）");

		player.getInventory().clearContent();
		clearItemEverywhere(player, SummyReliquary.HOLY_SPEAR.get());
		clearItemEverywhere(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearMissingSeconds(player, 0);
		resetDemonPactState(player);
		resetSinState(player);
	}

	/** 1.7.9：四把武器的门槛三态 + 长矛的攻击距离（entity_reach）+ 未达标时左键伤害归零 */
	private static void checkWeaponGatesAndReach(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		clearNearbyMonsters(player, 16.0D);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);

		// ① 三态判据
		boolean noneOk = !com.summy.reliquary.effect.WeaponGates.qualified(player,
						SummyReliquary.SACRIFICIAL_DAGGER.get())
				&& !com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.DARK_ARTS.get())
				&& !com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.HOLY_SPEAR.get())
				&& !com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		boolean demonOk = com.summy.reliquary.effect.WeaponGates.qualified(player,
						SummyReliquary.SACRIFICIAL_DAGGER.get())
				&& !com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.DARK_ARTS.get())
				&& !com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.HOLY_SPEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player,
				com.summy.reliquary.effect.EvilUnlock.OCCULT_EYE.bit());
		boolean darkOk = com.summy.reliquary.effect.WeaponGates.qualified(player,
				SummyReliquary.DARK_ARTS.get());
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		boolean angelOk = com.summy.reliquary.effect.WeaponGates.qualified(player,
						SummyReliquary.HOLY_SPEAR.get())
				&& com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.SERAPH_SPEAR.get())
				&& !com.summy.reliquary.effect.WeaponGates.qualified(player, SummyReliquary.DARK_ARTS.get());

		// ② 攻击距离：短矛 +0.5 / 炽天使 +1.0 / 空手 0 / 未达标 0
		net.minecraft.world.entity.ai.attributes.Attribute reach =
				net.minecraftforge.common.ForgeMod.ENTITY_REACH.get();
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double holyReach = modifierAmount(player, reach, "spear_reach");
		double holyTotal = player.getAttributeValue(reach);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.SERAPH_SPEAR.get()));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double seraphReach = modifierAmount(player, reach, "spear_reach");
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double emptyReach = modifierAmount(player, reach, "spear_reach");
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.SERAPH_SPEAR.get()));
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double unqualifiedReach = modifierAmount(player, reach, "spear_reach");
		boolean reachOk = Math.abs(holyReach - com.summy.reliquary.effect.WeaponGates.HOLY_SPEAR_REACH) < 1.0E-6D
				&& Math.abs(seraphReach - com.summy.reliquary.effect.WeaponGates.SERAPH_SPEAR_REACH) < 1.0E-6D
				&& Math.abs(emptyReach) < 1.0E-6D && Math.abs(unqualifiedReach) < 1.0E-6D
				&& Math.abs(holyTotal - (3.0D + com.summy.reliquary.effect.WeaponGates.HOLY_SPEAR_REACH)) < 1.0E-6D;

		// ③ 未达标时左键伤害整体归零（直接跑一遍事件处理器，避免真的打人）
		Zombie dummy = spawnTestZombie(player, 2.0D, 0.0D);
		var source = player.damageSources().playerAttack(player);
		net.minecraftforge.event.entity.living.LivingHurtEvent blocked =
				new net.minecraftforge.event.entity.living.LivingHurtEvent(dummy, source, 10.0F);
		com.summy.reliquary.ReliquaryEvents.onLivingHurt(blocked);
		boolean zeroed = blocked.isCanceled() || blocked.getAmount() == 0.0F;
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		net.minecraftforge.event.entity.living.LivingHurtEvent allowed =
				new net.minecraftforge.event.entity.living.LivingHurtEvent(dummy, source, 10.0F);
		com.summy.reliquary.ReliquaryEvents.onLivingHurt(allowed);
		boolean notZeroed = !allowed.isCanceled() && allowed.getAmount() > 0.0F;
		// 10% 圣光 proc 的门槛：手持圣光短矛 + 天使标记才掷骰
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		boolean procGate = com.summy.reliquary.effect.WeaponGates.holySpearProc(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, false);
		boolean procClosed = !com.summy.reliquary.effect.WeaponGates.holySpearProc(player);
		dummy.discard();

		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		player.getInventory().clearContent();
		resetDemonPactState(player);

		log("四把武器门槛（1.7.9）：全未达标 → 四把都禁用=" + noneOk + "（应 true）、恶魔标记 → 匕首可用 / 刺刀不可="
				+ demonOk + "（应 true）、+700 → 刺刀可用=" + darkOk + "（应 true）、天使标记 → 两把矛可用 / 刺刀不可="
				+ angelOk + "（应 true）；攻击距离修饰符：短矛=" + String.format("%.2f", holyReach)
				+ "（应 0.5）、炽天使=" + String.format("%.2f", seraphReach) + "（应 1.0）、空手="
				+ String.format("%.2f", emptyReach) + "（应 0）、未达标=" + String.format("%.2f", unqualifiedReach)
				+ "（应 0）、总距离=" + String.format("%.2f", holyTotal) + "（应 3.5）→ " + reachOk
				+ "（应 true）；未达标左键归零=" + zeroed + "（应 true）、达标后正常=" + notZeroed
				+ "（应 true）；圣光 proc 门槛：手持短矛 + 天使标记=" + procGate + "（应 true）、"
				+ "摘掉天使标记=" + procClosed + "（应 true = 不掷骰）");
	}

	/** 1.7.9：两把长矛的文案（风味 + Shift）、模型（using 谓词与 overrides）与资源存在性 */
	private static void checkSpearTextsAndModels(ServerPlayer player) {
		Minecraft client = Minecraft.getInstance();
		boolean keys = translated("item.summy-reliquary.holy_spear.tagline.1")
				&& translated("item.summy-reliquary.holy_spear.shift.1")
				&& translated("item.summy-reliquary.holy_spear.shift.2")
				&& translated("item.summy-reliquary.holy_spear.shift.3")
				&& translated("item.summy-reliquary.seraph_spear.tagline.1")
				&& translated("item.summy-reliquary.seraph_spear.shift.1")
				&& translated("item.summy-reliquary.seraph_spear.shift.2")
				&& translated("item.summy-reliquary.seraph_spear.shift.3")
				&& translated("message.summy-reliquary.spear.recovery.1")
				&& translated("message.summy-reliquary.spear.recovery.2")
				&& translated("message.summy-reliquary.recipe.spear_present")
				&& translated("death.attack.summy-reliquary.holy_light_burst");
		int flavorColor = com.summy.reliquary.item.ReliquaryTooltips
				.angelFlavor("item.summy-reliquary.holy_spear.tagline.1")
				.getStyle().getColor() == null ? -1
						: com.summy.reliquary.item.ReliquaryTooltips
								.angelFlavor("item.summy-reliquary.holy_spear.tagline.1")
								.getStyle().getColor().getValue();
		boolean colorOk = flavorColor == com.summy.reliquary.text.ReliquaryFaction.ANGEL.nameColor();
		String holyModel = resourceText(client, SummyReliquary.NAMESPACE, "models/item/holy_spear.json");
		String seraphModel = resourceText(client, SummyReliquary.NAMESPACE, "models/item/seraph_spear.json");
		boolean overrides = holyModel.contains("overrides") && holyModel.contains("using")
				&& seraphModel.contains("overrides") && seraphModel.contains("using");
		boolean usingModels = hasResource(client, "models/item/holy_spear_using.json")
				&& hasResource(client, "models/item/seraph_spear_using.json");
		// 1.7.9 修订：蓄力姿势的"头尾翻转"只发生在**第三人称**（第一人称实测是对的，不动它）——
		// 判据：两个 using 模型的第三人称 Z 与主模型相差 180°（mod 360 = 0），第一人称 Z 与主模型一致。
		String baseText = resourceText(client, SummyReliquary.NAMESPACE, "models/item/spear_in_hand.json");
		String holyUsing = resourceText(client, SummyReliquary.NAMESPACE, "models/item/holy_spear_using.json");
		String seraphUsing = resourceText(client, SummyReliquary.NAMESPACE, "models/item/seraph_spear_using.json");
		boolean thirdFlipped = thirdPersonFlipped(baseText, holyUsing)
				&& thirdPersonFlipped(baseText, seraphUsing);
		boolean firstPersonKept = sameZ(baseText, holyUsing, "firstperson_righthand")
				&& sameZ(baseText, holyUsing, "firstperson_lefthand")
				&& sameZ(baseText, seraphUsing, "firstperson_righthand")
				&& sameZ(baseText, seraphUsing, "firstperson_lefthand");
		// 投掷物的平面内补偿：+45° 对齐飞行方向 + 180° 换头尾 = 225°（两次实测才定下来）
		boolean tiltAlignedAndFlipped = com.summy.reliquary.client.ThrownSpearRenderer.Z_TILT_DEGREES == 225.0F;
		// 谓词注册：客户端 tick 会在进世界前幂等注册（这里由客户端用例再断言一次）
		log("长矛文案与模型（1.7.9）：语言键（风味 2 + Shift 6 + 防丢失 2 + 提示 1 + 死亡文本 1）齐=" + keys
				+ "（应 true）、天使线风味色=" + String.format("#%06X", flavorColor) + "（应 #FFE4B5）=" + colorOk
				+ "（应 true）；主模型含 using overrides=" + overrides + "（应 true）、两个举矛模型在包内="
				+ usingModels + "（应 true）；蓄力朝向（修订）：两把矛的**第三人称** Z 都比主模型多 180°（头尾翻转）="
				+ thirdFlipped + "（应 true）、第一人称 Z 与主模型一致（不动第一人称）=" + firstPersonKept
				+ "（应 true）；投掷物平面补偿=+" + String.format("%.0f",
						com.summy.reliquary.client.ThrownSpearRenderer.Z_TILT_DEGREES)
				+ "°（应为 +225 = +45 对齐飞行方向 + 180 换头尾；写 −45 会让矛身垂直于飞行方向，"
				+ "只写 +45 会顺着飞但前后反）=" + tiltAlignedAndFlipped + "（应 true）");
	}

	/** 从模型 JSON 文本里取某个 display 上下文的 rotation 三元组（拿不到返回 null） */
	private static int[] rotationOf(String json, String context) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
				"\"" + context + "\"\\s*:\\s*\\{[^}]*?\"rotation\"\\s*:\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]")
				.matcher(json);
		if (!matcher.find()) {
			return null;
		}
		return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
				Integer.parseInt(matcher.group(3))};
	}

	/** using 模型的两个第三人称 Z 是否都比主模型多 180°（mod 360） */
	private static boolean thirdPersonFlipped(String base, String using) {
		return flipped(base, using, "thirdperson_righthand") && flipped(base, using, "thirdperson_lefthand");
	}

	private static boolean flipped(String base, String using, String context) {
		int[] a = rotationOf(base, context);
		int[] b = rotationOf(using, context);
		return a != null && b != null && Math.floorMod(b[2] - a[2], 360) == 180;
	}

	/** using 模型该上下文的 Z 是否与主模型一致（用于"第一人称没被改动"的断言） */
	private static boolean sameZ(String base, String using, String context) {
		int[] a = rotationOf(base, context);
		int[] b = rotationOf(using, context);
		return a != null && b != null && a[2] == b[2];
	}

	/** 1.7.9：手持圣光短矛的 10% 圣光 proc —— 与饰品位各自独立掷骰 */
	private static void checkSpearHolyLightProc(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		clearNearbyMonsters(player, 16.0D);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.HOLY_SPEAR.get()));
		com.summy.reliquary.effect.HolyLightEffect.reset();
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(true);

		// ① 只拿短矛（不戴圣光）→ 也有一条通道
		Zombie first = spawnTestZombie(player, 4.0D, 0.0D);
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(first,
						player.damageSources().playerAttack(player), 10.0F));
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		int spearOnly = com.summy.reliquary.effect.HolyLightEffect.triggerCount();
		first.discard();

		// ② 再戴上圣光 → 同一次命中两条通道（两条光柱）
		com.summy.reliquary.effect.HolyLightEffect.reset();
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(true);
		equip(player, ReliquarySlots.BLESSING, SummyReliquary.HOLY_LIGHT.get());
		Zombie second = spawnTestZombie(player, 4.0D, 0.0D);
		float healthBefore = second.getHealth();
		com.summy.reliquary.effect.HolyLightEffect.record(
				new net.minecraftforge.event.entity.living.LivingHurtEvent(second,
						player.damageSources().playerAttack(player), 10.0F));
		com.summy.reliquary.effect.HolyLightEffect.tickServer(server);
		int both = com.summy.reliquary.effect.HolyLightEffect.triggerCount();
		float dealt = healthBefore - second.getHealth();
		second.discard();
		com.summy.reliquary.effect.HolyLightEffect.setForcedRoll(null);
		com.summy.reliquary.effect.HolyLightEffect.reset();

		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		resetDemonPactState(player);

		log("圣光短矛 proc（1.7.9）：只持短矛（不戴圣光）触发 " + spearOnly + " 条（应 1 = 武器位独立通道）、"
				+ "再戴圣光触发 " + both + " 条（应 2 = 饰品位 + 武器位各自掷骰）、总伤害 "
				+ String.format("%.1f", dealt) + "（两次 120% 应为 24，但僵尸只有 20 点生命 → 打满即止，"
				+ "所以 ≤ 20 都算正常；关键断言是「触发 2 条」）");
	}

	/** 1.7.9：创世纪照旧没收四把武器（签约 / 忏悔不没收由 checkPactConfiscation / checkRepentConfiscation 断言） */
	private static void checkWeaponGenesisConfiscation(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		resetSinState(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.SlotSizing.syncNow(player);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);
		for (Item item : new Item[]{SummyReliquary.SACRIFICIAL_DAGGER.get(), SummyReliquary.DARK_ARTS.get(),
				SummyReliquary.HOLY_SPEAR.get(), SummyReliquary.SERAPH_SPEAR.get()}) {
			player.getInventory().add(new ItemStack(item));
		}
		int before = countItem(player, SummyReliquary.SACRIFICIAL_DAGGER.get())
				+ countItem(player, SummyReliquary.DARK_ARTS.get())
				+ countItem(player, SummyReliquary.HOLY_SPEAR.get())
				+ countItem(player, SummyReliquary.SERAPH_SPEAR.get());
		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		boolean used = com.summy.reliquary.item.GenesisItem.confirm(player);
		int after = countItem(player, SummyReliquary.SACRIFICIAL_DAGGER.get())
				+ countItem(player, SummyReliquary.DARK_ARTS.get())
				+ countItem(player, SummyReliquary.HOLY_SPEAR.get())
				+ countItem(player, SummyReliquary.SERAPH_SPEAR.get());
		log("四把武器的创世纪没收（1.7.9）：用掉前 " + before + " 把（应 4）、用掉创世纪=" + used
				+ "（应 true）、用掉后剩 " + after + " 把（应 0）→ " + (before == 4 && used && after == 0)
				+ "（应 true）");
		player.getInventory().clearContent();
		resetDemonPactState(player);
		resetSinState(player);
	}

	// ==================== 1.7.10：武器 × 饰品联动 ====================

	/** 自检用：造一份"带 NBT"的副本（耐久伤害 + 附魔 + 自定义名） */
	private static ItemStack nbtItem(Item item) {
		ItemStack stack = new ItemStack(item);
		if (stack.isDamageableItem()) {
			stack.setDamageValue(7);
		}
		stack.enchant(net.minecraft.world.item.enchantment.Enchantments.VANISHING_CURSE, 1);
		stack.setHoverName(Component.literal("带 NBT 的测试物品"));
		return stack;
	}

	/**
	 * 1.7.10 修订：**持有判定必须只比物品类型**（忽略耐久 / 附魔 / 改名这些 NBT），
	 * 并且要把 副手 / 盔甲 / 光标 也算进来。
	 *
	 * <p>旧写法 {@code Inventory#contains(new ItemStack(item))} 是"连 NBT 一起比"：匕首是剑、近战会掉耐久，
	 * 于是"背包里明明有匕首"也会被判成"没有" → 5 分钟后误报"没找到匕首"并开放防丢失配方。
	 */
	private static void checkHeldItemScan(ServerPlayer player) {
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		player.containerMenu.setCarried(ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			player.setItemSlot(slot, ItemStack.EMPTY);
		}
		player.removeAllEffects();
		clearNearbyMonsters(player, 24.0D);

		Item dagger = SummyReliquary.SACRIFICIAL_DAGGER.get();
		Item darkArts = SummyReliquary.DARK_ARTS.get();
		Item holySpear = SummyReliquary.HOLY_SPEAR.get();
		Item seraphSpear = SummyReliquary.SERAPH_SPEAR.get();

		// ① 主背包：带 NBT 的匕首 / 刺刀 / 两把矛都算"持有"（旧写法在这里会判 false）
		player.getInventory().add(nbtItem(dagger));
		boolean mainInv = com.summy.reliquary.util.HeldItems.holds(player, dagger)
				&& com.summy.reliquary.effect.DaggerRecovery.holdsDagger(player);
		player.getInventory().add(nbtItem(darkArts));
		boolean darkArtsHeld = com.summy.reliquary.effect.DaggerRecovery.holdsDagger(player);
		player.getInventory().add(nbtItem(holySpear));
		player.getInventory().add(nbtItem(seraphSpear));
		boolean spearsHeld = com.summy.reliquary.util.HeldItems.holds(player, holySpear)
				&& com.summy.reliquary.effect.SpearRecovery.holdsSpear(player);
		player.getInventory().clearContent();

		// ② 副手 ③ 盔甲槽 ④ 光标 ⑤ 饰品栏
		player.setItemSlot(EquipmentSlot.OFFHAND, nbtItem(darkArts));
		boolean offhand = com.summy.reliquary.util.HeldItems.holds(player, darkArts);
		player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.CHEST, nbtItem(holySpear));
		boolean armorSlot = com.summy.reliquary.util.HeldItems.holds(player, holySpear);
		player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
		player.containerMenu.setCarried(nbtItem(seraphSpear));
		boolean carried = com.summy.reliquary.util.HeldItems.holds(player, seraphSpear);
		player.containerMenu.setCarried(ItemStack.EMPTY);
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
		boolean curioSlot = com.summy.reliquary.util.HeldItems.holds(player,
				SummyReliquary.SOURCE_OF_SINS.get());

		// ⑥ 同源判定（都走同一个 HeldItems）：圣心 / 魂印物品 / 有罪之人 / 成就兜底 / 创世纪
		player.getInventory().clearContent();
		player.getInventory().add(nbtItem(SummyReliquary.SACRED_HEART.get()));
		boolean sacredHeart = com.summy.reliquary.effect.DemonDeal.holdsSacredHeart(player);
		player.getInventory().clearContent();
		player.getInventory().add(nbtItem(SummyReliquary.SATANIC_BIBLE.get()));
		boolean sealItems = com.summy.reliquary.util.HeldItems.holds(player,
				SummyReliquary.SATANIC_BIBLE.get());
		player.getInventory().clearContent();
		player.getInventory().add(nbtItem(SummyReliquary.SOURCE_OF_SINS.get()));
		boolean sourceOfSins = com.summy.reliquary.advancement.SinChallenges.hasSourceOfSins(player)
				&& com.summy.reliquary.advancement.ItemObtained.has(player,
						SummyReliquary.SOURCE_OF_SINS.get());
		player.getInventory().clearContent();
		player.getInventory().add(nbtItem(SummyReliquary.GENESIS.get()));
		boolean genesis = com.summy.reliquary.item.GenesisItem.holdsGenesis(player);
		player.getInventory().clearContent();

		// ⑦ 回归：带着"用过 / 附魔过"的匕首与长矛推进 300 秒 → 不再误报（保持 0 秒、不开放）
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		player.getInventory().add(nbtItem(dagger));
		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerMissingSeconds(player, 0);
		com.summy.reliquary.effect.DaggerRecovery.tickForTest(player,
				com.summy.reliquary.config.ReliquaryConfig.daggerRecoverySeconds());
		boolean daggerNoFalseAlarm = !com.summy.reliquary.effect.DaggerRecovery.isOpen(player)
				&& com.summy.reliquary.effect.DaggerRecovery.missingSeconds(player) == 0;
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setSpearObtained(player, true);
		player.getInventory().add(nbtItem(seraphSpear));
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearMissingSeconds(player, 0);
		com.summy.reliquary.effect.SpearRecovery.tickForTest(player,
				com.summy.reliquary.config.ReliquaryConfig.daggerRecoverySeconds());
		boolean spearNoFalseAlarm = !com.summy.reliquary.effect.SpearRecovery.isOpen(player)
				&& com.summy.reliquary.effect.SpearRecovery.missingSeconds(player) == 0;

		log("持有判定（1.7.10 修订）：带 NBT（耐久 + 附魔 + 改名）时仍算持有 —— 主背包 匕首=" + mainInv
				+ " / 刺刀=" + darkArtsHeld + " / 两把矛=" + spearsHeld + "（都应 true）；位置覆盖：副手="
				+ offhand + "、盔甲槽=" + armorSlot + "、光标=" + carried + "、饰品栏=" + curioSlot
				+ "（都应 true）；同源判定：圣心=" + sacredHeart + "、撒旦圣经=" + sealItems
				+ "、七罪之源（有罪之人 + 成就兜底）=" + sourceOfSins + "、创世纪=" + genesis
				+ "（都应 true）；回归：带 NBT 的匕首推进 300 秒 → 不开放防丢失=" + daggerNoFalseAlarm
				+ "（应 true；聊天两行只在开放那一刻发）、带 NBT 的炽天使之枪同理=" + spearNoFalseAlarm + "（应 true）");

		// 收尾
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerMissingSeconds(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setSpearRecoveryOpen(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSpearMissingSeconds(player, 0);
		clearSpiritAltar(player);
		resetDemonPactState(player);
		resetSinState(player);
	}

	/**
	 * 1.7.10：五芒星发放口径 —— **七罪全部已激活或已赎罪**（"开一罪赎一罪"到第 7 项就该发）。
	 *
	 * <p>判据来源：`SinManager.setState(REDEEMED)` 会同时写激活位与赎罪位，而 `allSinsTriggered` 只看激活位掩码；
	 * 五芒星发放与「罪无可赦」共用这一条（`SinManager.setState` 里同一处触发 + 每秒兜底）。
	 */
	private static void checkPentagramGrantByRedeemedPath(ServerPlayer player) {
		// ① "开一罪赎一罪"：每项都先激活再赎罪，记录"第几项之后就拿到了五芒星"
		resetSinState(player);
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		clearPentagramItems(player);
		int grantedAtRound = 0;
		int round = 0;
		for (Sin sin : Sin.values()) {
			round++;
			com.summy.reliquary.sin.SinManager.setState(player, sin,
					com.summy.reliquary.sin.SinManager.SinState.ACTIVATED);
			com.summy.reliquary.sin.SinManager.setState(player, sin,
					com.summy.reliquary.sin.SinManager.SinState.REDEEMED);
			if (com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player)) {
				grantedAtRound = round;
			}
		}
		int mask = com.summy.reliquary.sin.SinManager.mask(player);
		int redeemed = com.summy.reliquary.sin.SinManager.redeemedMask(player);
		boolean byRedeem = grantedAtRound == Sin.values().length
				&& mask == 0b1111111 && redeemed == 0b1111111
				&& com.summy.reliquary.sin.SinManager.allSinsTriggered(player)
				&& com.summy.reliquary.effect.Pentagram.heldCount(player) == 1;

		// ② 对照：七罪全部"已激活（未赎罪）"同样发
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		clearPentagramItems(player);
		resetSinState(player);
		setSinMasksForTest(player, 0b1111111, 0);
		boolean byActivate = com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player)
				&& com.summy.reliquary.effect.Pentagram.heldCount(player) == 1;

		// ③ 反向：只有 6 项已赎罪 → 不算全触发、也不发
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		clearPentagramItems(player);
		resetSinState(player);
		setSinMasksForTest(player, 0b0111111, 0b0111111);
		boolean sixOnly = !com.summy.reliquary.sin.SinManager.allSinsTriggered(player)
				&& !com.summy.reliquary.effect.PlayerFlags.isPentagramGranted(player)
				&& !com.summy.reliquary.effect.Pentagram.grantIfEarned(player);

		log("五芒星发放口径（1.7.10）：\"开一罪赎一罪\"到第 " + grantedAtRound + " / " + Sin.values().length
				+ " 项时发放（应 7）→ 掩码 " + Integer.toBinaryString(mask) + " / "
				+ Integer.toBinaryString(redeemed) + "（应 1111111 / 1111111）、身上 1 枚="
				+ byRedeem + "（应 true）；对照·七罪全部已激活（未赎罪）同样发放=" + byActivate
				+ "（应 true）；反向·只有 6 项已赎罪不算全触发且不发放=" + sixOnly + "（应 true）");

		// 收尾（还原成"全激活"状态，避免影响后续用例）
		clearPentagramItems(player);
		com.summy.reliquary.effect.Pentagram.resetGranted(player);
		setSinMasksForTest(player, 0b1111111, 0);
	}

	/**
	 * 1.7.10 天使线联动：圣心（长矛投掷初速 ×1.25，且**不**作用于金刀片）、神性（落点爆发 半径 +1 / 伤害 +2）。
	 */
	private static void checkAngelWeaponSynergies(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		clearNearbyMonsters(player, 24.0D);
		com.summy.reliquary.effect.PlayerFlags.setAngel(player, true);

		// ===== ① 圣心：长矛投掷初速 =====
		ItemStack seraph = new ItemStack(SummyReliquary.SERAPH_SPEAR.get());
		float multiplierWithout = com.summy.reliquary.effect.Synergies.spearThrowVelocityMultiplier(player);
		float speedWithout = measureSpearThrow(player, seraph, level);
		equipAt(player, ReliquarySlots.BLESSING, 0, SummyReliquary.SACRED_HEART.get());
		float multiplierWith = com.summy.reliquary.effect.Synergies.spearThrowVelocityMultiplier(player);
		float speedWith = measureSpearThrow(player, seraph, level);
		// SpearItem 的基础出手速度是 2.5；佩戴圣心后应 ×(1 + 配置百分比)
		float expectedSpeed = 2.5F * (1.0F + com.summy.reliquary.config.ReliquaryConfig
				.sacredHeartThrowSpeedPercent() / 100.0F);
		boolean speedOk = Math.abs(multiplierWithout - 1.0F) < 1.0E-6F
				&& Math.abs(multiplierWith - expectedSpeed / 2.5F) < 1.0E-6F
				&& Math.abs(speedWith - expectedSpeed) < 0.05F
				&& speedWith > speedWithout + 0.1F;
		log("武器联动·圣心（1.7.10）：投掷初速倍率 无圣心=" + String.format("%.2f", multiplierWithout)
				+ "（应 1.00）、有圣心=" + String.format("%.2f", multiplierWith) + "（应 "
				+ String.format("%.2f", expectedSpeed / 2.5F) + "）；实测幻影速度 "
				+ String.format("%.2f", speedWithout) + " → " + String.format("%.2f", speedWith)
				+ "（应 " + String.format("%.2f", expectedSpeed) + "）→ " + speedOk + "（应 true）");

		// ===== ② 圣心不作用于金刀片（追踪对比：长矛会被掰弯、刀片不会）=====
		clearBlessingSlots(player);
		equipAt(player, ReliquarySlots.BLESSING, 0, SummyReliquary.SACRED_HEART.get());
		// 目标放在**侧向** 6 格（不是正前方），这样"被掰向目标"才表现为速度的 x 分量变化
		Zombie bait = spawnTestZombie(player, 6.0D, 0.0D);
		var spearPhantom = new com.summy.reliquary.entity.ThrownSpear(level, player,
				new ItemStack(SummyReliquary.SERAPH_SPEAR.get()), 1.0F);
		spearPhantom.setPos(player.getX(), player.getY() + 1.5D, player.getZ());
		spearPhantom.setDeltaMovement(0.0D, 0.0D, 1.0D);
		level.addFreshEntity(spearPhantom);
		var razorPhantom = new com.summy.reliquary.entity.ThrownRazor(level, player);
		razorPhantom.setPos(player.getX(), player.getY() + 1.5D, player.getZ());
		razorPhantom.setDeltaMovement(0.0D, 0.0D, 1.0D);
		level.addFreshEntity(razorPhantom);
		com.summy.reliquary.effect.SacredHeart.tick(server);
		double spearX = spearPhantom.getDeltaMovement().x;
		double razorX = razorPhantom.getDeltaMovement().x;
		boolean homingSplit = Math.abs(spearX) > 1.0E-3D && Math.abs(razorX) < 1.0E-9D;
		spearPhantom.discard();
		razorPhantom.discard();
		bait.discard();
		log("武器联动·圣心追踪范围（1.7.10）：同一次 tick 后 长矛速度 x 分量=" + String.format("%.3f", spearX)
				+ "（被掰向目标，应 ≠ 0）、金刀片速度 x 分量=" + String.format("%.3f", razorX)
				+ "（应 0 = 不吃追踪）→ " + homingSplit + "（应 true）");

		// ===== ③ 神性：落点爆发 半径 +1 / 伤害 +2 =====
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);
		double radiusBase = com.summy.reliquary.effect.Synergies.holyBurstRadius(player);
		double damageBase = com.summy.reliquary.effect.Synergies.holyBurstDamage(player);
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		double radiusWith = com.summy.reliquary.effect.Synergies.holyBurstRadius(player);
		double damageWith = com.summy.reliquary.effect.Synergies.holyBurstDamage(player);
		boolean godheadOk = Math.abs(radiusWith - radiusBase
						- com.summy.reliquary.config.ReliquaryConfig.holyLightBurstRadiusGodhead()) < 1.0E-6D
				&& Math.abs(damageWith - damageBase
						- com.summy.reliquary.config.ReliquaryConfig.holyLightBurstDamageGodhead()) < 1.0E-6D;
		log("武器联动·神性（1.7.10）：投掷落点爆发 半径 " + String.format("%.1f", radiusBase) + " → "
				+ String.format("%.1f", radiusWith) + "（应 +1 = 5）、伤害 " + String.format("%.1f", damageBase)
				+ " → " + String.format("%.1f", damageWith) + "（应 +2 = 16）→ " + godheadOk + "（应 true）");

		// 收尾
		unequip(player, ReliquarySlots.REVELATION);
		clearBlessingSlots(player);
		player.getInventory().clearContent();
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		clearNearbyMonsters(player, 24.0D);
		resetDemonPactState(player);
	}

	/** 自检用：按"蓄满 20 tick"直接走 releaseUsing 投出一把矛，返回幻影的初速，然后删掉它 */
	private static float measureSpearThrow(ServerPlayer player, ItemStack spear,
			net.minecraft.server.level.ServerLevel level) {
		player.getCooldowns().removeCooldown(spear.getItem());
		player.setItemSlot(EquipmentSlot.MAINHAND, spear);
		int duration = spear.getItem().getUseDuration(spear);
		spear.getItem().releaseUsing(spear, level, player, duration - 20);
		float speed = 0.0F;
		for (var phantom : level.getEntitiesOfClass(com.summy.reliquary.entity.ThrownSpear.class,
				player.getBoundingBox().inflate(8.0D))) {
			speed = (float) phantom.getDeltaMovement().length();
			phantom.discard();
		}
		player.getCooldowns().removeCooldown(spear.getItem());
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		return speed;
	}

	/** 1.7.10 收尾：自检用 —— 补齐 / 撤销某条本模组进度 */
	private static void setAdvancement(ServerPlayer player, String id, boolean done) {
		var advancement = player.getServer().getAdvancements().getAdvancement(SummyReliquary.id(id));
		if (advancement == null) {
			return;
		}
		if (done) {
			player.getAdvancements().award(advancement, "event");
		} else {
			player.getAdvancements().revoke(advancement, "event");
		}
	}

	/** 1.7.10 收尾：自检用 —— 把七罪全部设成未激活，并确保魂印栏里是七罪之源 */
	private static void prepareSourceOfSins(ServerPlayer player) {
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			com.summy.reliquary.sin.SinManager.setState(player, sin,
					com.summy.reliquary.sin.SinManager.SinState.UNACTIVATED);
		}
		player.getInventory().add(new ItemStack(SummyReliquary.SOURCE_OF_SINS.get()));
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SOURCE_OF_SINS.get());
	}

	/** 1.7.10 收尾：自检用 —— 玩家个人游戏秒数（与 SinChallenges 内部口径一致） */
	private static int playSecondsForTest(ServerPlayer player) {
		return player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(
				net.minecraft.stats.Stats.PLAY_TIME)) / 20;
	}

	/** 1.7.10 收尾：「纯洁无瑕」的佩戴率判据（参考神秘遗物的 IPlaytimeCounter） */
	private static void checkFlawlessWearingRatio(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		clearBlessingSlots(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player, 0);
		prepareSourceOfSins(player);

		// ① 98% → 拒绝
		com.summy.reliquary.effect.PlayerFlags.setSinWornSeconds(player, 980);
		com.summy.reliquary.effect.PlayerFlags.setSinUnwornSeconds(player, 20);
		com.summy.reliquary.advancement.SinChallenges.evaluateDragon(player);
		boolean rejected = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
				!= com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS;

		// ② 99% → 通过
		com.summy.reliquary.effect.PlayerFlags.setSinWornSeconds(player, 9900);
		com.summy.reliquary.effect.PlayerFlags.setSinUnwornSeconds(player, 100);
		com.summy.reliquary.advancement.SinChallenges.evaluateDragon(player);
		boolean accepted = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
				== com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS;

		// ③ 宽限：起点在 60 秒前 → 未佩戴不增长；起点挪到 200 秒前 → +1
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player, 0);
		int now = playSecondsForTest(player);
		com.summy.reliquary.effect.PlayerFlags.setSinWornSeconds(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setSinUnwornSeconds(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setSinTrackingStartSeconds(player, Math.max(1, now - 60 + 1));
		unequip(player, ReliquarySlots.SOUL_SEAL);
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean graceHeld = com.summy.reliquary.effect.PlayerFlags.sinUnwornSeconds(player) == 0;
		com.summy.reliquary.effect.PlayerFlags.setSinTrackingStartSeconds(player, Math.max(1, now - 200 + 1));
		com.summy.reliquary.advancement.SinChallenges.selfHeal(player);
		boolean graceExpired = com.summy.reliquary.effect.PlayerFlags.sinUnwornSeconds(player) == 1;

		// ④ 洗白：60 秒未佩戴 + 6000 秒佩戴 = 99.0% → 通过
		prepareSourceOfSins(player);
		com.summy.reliquary.effect.PlayerFlags.setSinWornSeconds(player, 6000);
		com.summy.reliquary.effect.PlayerFlags.setSinUnwornSeconds(player, 60);
		com.summy.reliquary.advancement.SinChallenges.evaluateDragon(player);
		boolean washed = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
				== com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS;

		boolean configOk = com.summy.reliquary.config.ReliquaryConfig.flawlessMinWearingPercent() == 99
				&& com.summy.reliquary.config.ReliquaryConfig.flawlessUnwornGraceSeconds() == 120;
		log("「纯洁无瑕」佩戴率（1.7.10 收尾）：98% 被拒=" + rejected + "（应 true）、99% 通过=" + accepted
				+ "（应 true）、宽限内未佩戴不增长=" + graceHeld + "（应 true）、超出宽限后 +1=" + graceExpired
				+ "（应 true）、洗白 60/6060=99% 通过=" + washed + "（应 true）；配置默认 99/120=" + configOk
				+ "（应 true）");

		// 收尾
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player, 0);
		com.summy.reliquary.effect.PlayerFlags.resetSinWearTracking(player);
		unequip(player, ReliquarySlots.SOUL_SEAL);
		resetDemonPactState(player);
	}

	/** 1.7.10 收尾：创世纪重置会清空七罪佩戴计时（保证"两段线之间"还能做出纯洁无瑕） */
	private static void checkGenesisClearsWearingTimer(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setSinWornSeconds(player, 100);
		com.summy.reliquary.effect.PlayerFlags.setSinUnwornSeconds(player, 50);
		com.summy.reliquary.effect.PlayerFlags.setSinTrackingStartSeconds(player, 1);

		com.summy.reliquary.item.GenesisItem.resetUsed(player);
		com.summy.reliquary.item.GenesisItem.requestConfirmation(player);
		boolean confirmed = com.summy.reliquary.item.GenesisItem.confirm(player);
		boolean cleared = com.summy.reliquary.effect.PlayerFlags.sinWornSeconds(player) == 0
				&& com.summy.reliquary.effect.PlayerFlags.sinUnwornSeconds(player) == 0
				&& com.summy.reliquary.effect.PlayerFlags.sinTrackingStartSeconds(player) == 0;

		// 重置后立刻戴上 → 佩戴率视为 100% → 可以再次完成「纯洁无瑕」
		prepareSourceOfSins(player);
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player, 0);
		com.summy.reliquary.advancement.SinChallenges.evaluateDragon(player);
		boolean redoable = com.summy.reliquary.effect.PlayerFlags.dragonVerdict(player)
				== com.summy.reliquary.advancement.SinChallenges.VERDICT_FLAWLESS;
		log("创世纪清佩戴计时（1.7.10 收尾）：重置已执行=" + confirmed + "（应 true）、三个计时键归零="
				+ cleared + "（应 true）、重置后可再次完成「纯洁无瑕」=" + redoable + "（应 true）");

		// 收尾
		com.summy.reliquary.effect.PlayerFlags.setDragonVerdict(player, 0);
		com.summy.reliquary.effect.PlayerFlags.resetSinWearTracking(player);
		unequip(player, ReliquarySlots.SOUL_SEAL);
		resetDemonPactState(player);
	}

	/** 1.8.0：赎罪是终态 —— 赎罪后的罪不会因再次满足条件而回退成「已激活」 */
	private static void checkRedeemedNeverReactivates(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		prepareSourceOfSins(player);

		int survived = 0;
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.ACTIVATED);
			boolean activated = SinManager.state(player, sin) == SinManager.SinState.ACTIVATED;
			// 赎罪（内部会清零该罪计数）
			SinManager.redeem(player, sin);
			boolean redeemed = SinManager.state(player, sin) == SinManager.SinState.REDEEMED;
			boolean cleared = countFor(player, sin) == 0;
			// 再走一次「触发」（直接调 activate，等价于任何满足了条件的老触发路径）
			SinManager.activate(player, sin, true);
			boolean stayed = SinManager.state(player, sin) == SinManager.SinState.REDEEMED;
			if (activated && redeemed && cleared && stayed) {
				survived++;
			}
		}
		boolean allRedeemed = SinManager.allRedeemed(player);

		// 端到端：愤怒赎罪后再杀 200 只也不复活
		Zombie victim = spawnTestZombie(player, 8.0D);
		for (int index = 0; index < 200; index++) {
			com.summy.reliquary.sin.SinEffects.onKill(player, victim);
		}
		boolean wrathStayed = SinManager.state(player, com.summy.reliquary.sin.Sin.WRATH)
				== SinManager.SinState.REDEEMED;
		victim.discard();

		log("赎罪终态（1.8.0）：七罪逐个「激活→赎罪→再触发」全部保持已赎罪=" + survived + "/7（应 7）、"
				+ "全赎判定=" + allRedeemed + "（应 true）、愤怒赎罪后再击杀 200 次仍为已赎罪="
				+ wrathStayed + "（应 true）");

		// 收尾
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			SinManager.setState(player, sin, SinManager.SinState.UNACTIVATED);
		}
		unequip(player, ReliquarySlots.SOUL_SEAL);
		resetDemonPactState(player);
	}

	/** 取某一罪当前的触发计数（自检用） */
	private static int countFor(ServerPlayer player, com.summy.reliquary.sin.Sin sin) {
		return switch (sin) {
			case PRIDE -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.PRIDE_KILLS);
			case ENVY -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.ENVY_SEEN);
			case WRATH -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.WRATH_KILLS);
			case SLOTH -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.SLOTH_SLEEPS);
			case GREED -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.GREED_PEAK_DIAMONDS);
			case GLUTTONY -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.GLUTTONY_MEALS);
			case LUST -> com.summy.reliquary.sin.SinProgress.get(player,
					com.summy.reliquary.sin.SinProgress.LUST_BREEDS);
		};
	}

	/** 1.8.0：激活的硬前置 —— 必须佩戴七罪之源，且该罪处于未激活 */
	private static void checkActivationRequiresSource(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		clearBlessingSlots(player);

		// ① 什么都没戴
		unequip(player, ReliquarySlots.SOUL_SEAL);
		SinManager.activate(player, com.summy.reliquary.sin.Sin.WRATH, true);
		boolean noneBlocked = SinManager.state(player, com.summy.reliquary.sin.Sin.WRATH)
				== SinManager.SinState.UNACTIVATED;

		// ② 戴美德
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.VIRTUES.get());
		SinManager.activate(player, com.summy.reliquary.sin.Sin.WRATH, true);
		boolean virtuesBlocked = SinManager.state(player, com.summy.reliquary.sin.Sin.WRATH)
				== SinManager.SinState.UNACTIVATED;

		// ③ 戴撒旦圣经
		equip(player, ReliquarySlots.SOUL_SEAL, SummyReliquary.SATANIC_BIBLE.get());
		SinManager.activate(player, com.summy.reliquary.sin.Sin.WRATH, true);
		boolean bibleBlocked = SinManager.state(player, com.summy.reliquary.sin.Sin.WRATH)
				== SinManager.SinState.UNACTIVATED;

		// ④ 戴七罪之源 → 允许激活
		prepareSourceOfSins(player);
		SinManager.activate(player, com.summy.reliquary.sin.Sin.WRATH, true);
		boolean allowedWithSource = SinManager.state(player, com.summy.reliquary.sin.Sin.WRATH)
				== SinManager.SinState.ACTIVATED;

		log("激活前置（1.8.0）：未佩戴时被挡=" + noneBlocked + "（应 true）、戴美德被挡=" + virtuesBlocked
				+ "（应 true）、戴撒旦圣经被挡=" + bibleBlocked + "（应 true）、戴七罪之源可激活="
				+ allowedWithSource + "（应 true）");

		// 收尾
		SinManager.setState(player, com.summy.reliquary.sin.Sin.WRATH, SinManager.SinState.UNACTIVATED);
		unequip(player, ReliquarySlots.SOUL_SEAL);
		resetDemonPactState(player);
	}

	/** 1.8.0：匕首防丢失以「发放过」为前置（不要求真正持有） */
	private static void checkDaggerGrantGate(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDemonSealed(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerGranted(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerMissingSeconds(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);

		// ① 从没发放过 → 推 600 秒也不开放
		com.summy.reliquary.effect.DaggerRecovery.tickForTest(player, 600);
		boolean neverOpen = !com.summy.reliquary.effect.DaggerRecovery.isOpen(player);

		// ② 发放过（模拟签约那一刻，即使玩家没真正拿到）→ 无匕首满 5 分钟必须开放
		com.summy.reliquary.effect.PlayerFlags.setDaggerGranted(player, true);
		com.summy.reliquary.effect.DaggerRecovery.tickForTest(player, 600);
		boolean opened = com.summy.reliquary.effect.DaggerRecovery.isOpen(player);

		// ③ 重新拿到匕首 → 立刻清零并关闭
		player.getInventory().add(new net.minecraft.world.item.ItemStack(
				SummyReliquary.SACRIFICIAL_DAGGER.get()));
		com.summy.reliquary.effect.DaggerRecovery.tickForTest(player, 1);
		boolean closedAgain = !com.summy.reliquary.effect.DaggerRecovery.isOpen(player)
				&& com.summy.reliquary.effect.DaggerRecovery.missingSeconds(player) == 0;

		log("匕首防丢失前置（1.8.0）：未发放时不开放=" + neverOpen + "（应 true）、发放过且无匕首 600 秒后开放="
				+ opened + "（应 true）、拿到匕首后关闭并清零=" + closedAgain + "（应 true）");

		// 收尾
		player.getInventory().clearContent();
		com.summy.reliquary.effect.PlayerFlags.setDaggerGranted(player, false);
		com.summy.reliquary.effect.PlayerFlags.setDaggerMissingSeconds(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setDaggerRecoveryOpen(player, false);
		resetDemonPactState(player);
	}

	/** 1.8.0：恶魔线解锁玄秘魔眼（邪恶度 700）即获得与圣心 / 神性同款的恐惧免疫 */
	private static void checkOccultEyeFearImmunity(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		clearBlessingSlots(player);
		unequip(player, ReliquarySlots.REVELATION);

		// ① 邪恶度 699 → 不免疫
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 699.0D);
		boolean belowImmune = com.summy.reliquary.effect.DivineImmunity.immune(player);
		boolean belowFearable = com.summy.reliquary.effect.OccultEye.canFear(player);

		// ② 邪恶度 700 → 免疫（且恐惧无法施加）
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 700.0D);
		boolean atImmune = com.summy.reliquary.effect.DivineImmunity.immune(player);
		boolean atFearable = com.summy.reliquary.effect.OccultEye.canFear(player);

		// ③ 先中招、后解锁 → 每秒清理把恐惧与黑暗一起清掉
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				SummyReliquary.FEAR.get(), 200, 0, false, false, false));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DARKNESS, 200, 0, false, false, false));
		boolean hadFear = player.hasEffect(SummyReliquary.FEAR.get());
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 700.0D);
		com.summy.reliquary.effect.DivineImmunity.tickPlayer(player);
		boolean cleaned = !player.hasEffect(SummyReliquary.FEAR.get())
				&& !player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS);

		log("魔眼恐惧免疫（1.8.0）：邪恶 699 免疫=" + belowImmune + "（应 false）、699 恐惧可施加="
				+ belowFearable + "（应 true）、邪恶 700 免疫=" + atImmune + "（应 true）、700 恐惧可施加="
				+ atFearable + "（应 false）、先中招（成功=" + hadFear + "）后解锁并清理 → 恐惧与黑暗都消失="
				+ cleaned + "（应 true）");

		// 收尾
		player.removeEffect(SummyReliquary.FEAR.get());
		player.removeEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
		com.summy.reliquary.effect.PlayerFlags.setEvil(player, 0.0D);
		com.summy.reliquary.effect.PlayerFlags.setEvilUnlocks(player, 0);
		resetDemonPactState(player);
	}

	/** 1.8.0：愤怒自伤倍率 50% 且永不致死 */
	private static void checkWrathSelfHitNeverKills(ServerPlayer player) {
		resetDemonPactState(player);
		prepareBarePlayer(player);
		prepareSourceOfSins(player);
		SinManager.setState(player, com.summy.reliquary.sin.Sin.WRATH, SinManager.SinState.ACTIVATED);
		com.summy.reliquary.sin.SinEffects.resetCounters();

		Zombie victim = spawnTestZombie(player, 8.0D);
		victim.setNoAi(true);
		com.summy.reliquary.sin.SinEffects.resetCounters();

		// ① 满血测倍率：自伤应正好等于"本次攻击结算值（含暴怒随机浮动）× 50%"
		player.setHealth(player.getMaxHealth());
		player.invulnerableTime = 0;
		float swing = 0.0F;
		for (int index = 0; index < 200 && com.summy.reliquary.sin.SinEffects.wrathSelfHitCount() == 0; index++) {
			swing = com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 10.0F);
		}
		float lost = player.getMaxHealth() - player.getHealth();
		boolean ratioOk = Math.abs(lost - swing * 0.5F) < 0.01F;

		// ② 残血不致死：2 点生命被 20 点伤害的自伤打中，也只能掉到 1 点
		com.summy.reliquary.sin.SinEffects.resetCounters();
		player.setHealth(2.0F);
		player.invulnerableTime = 0;
		for (int index = 0; index < 200; index++) {
			com.summy.reliquary.sin.SinEffects.modifyOutgoingDamage(player, victim, 20.0F);
		}
		boolean alive = player.isAlive();
		float health = player.getHealth();
		victim.discard();

		boolean multOk = Math.abs(com.summy.reliquary.config.ReliquaryConfig.wrathSelfHitMultiplier() - 0.5D) < 1e-6;
		log("愤怒自伤（1.8.0）：满血被 10 点伤害自伤后掉血=" + String.format("%.1f", lost)
				+ "（本次结算 " + String.format("%.2f", swing) + " 的 50%）= " + ratioOk
				+ "（应 true）、残血连打后存活=" + alive
				+ "（应 true）、剩余生命=" + String.format("%.1f", health) + "（应 ≥ 1.0）、自伤倍率默认 0.5="
				+ multOk + "（应 true）");

		// 收尾
		player.setHealth(player.getMaxHealth());
		SinManager.setState(player, com.summy.reliquary.sin.Sin.WRATH, SinManager.SinState.UNACTIVATED);
		com.summy.reliquary.sin.SinEffects.resetCounters();
		unequip(player, ReliquarySlots.SOUL_SEAL);
		resetDemonPactState(player);
	}

	/** 1.7.10 收尾：新成就「近乎完美」—— 除无罪之人外的全部本模组成就 */
	private static void checkNearlyPerfect(ServerPlayer player) {
		String[] all = {"reliquary", "sinner", "unforgivable", "pure", "sinless", "flawless",
				"trinity", "revelation", "bread_and_fish", "heart", "god", "light", "shade", "genesis",
				"deal", "fresh_soul", "dark_tome", "beast_mark", "nightmare", "demon_flame",
				"evil_eye", "demon_king", "finale", "nearly_perfect"};
		for (String id : all) {
			setAdvancement(player, id, false);
		}
		// 成就定义从**注册表**读（advancements JSON 在 data/ 下，客户端资源管理器读不到）
		var nearlyPerfect = player.getServer().getAdvancements().getAdvancement(SummyReliquary.id("nearly_perfect"));
		var sinner = player.getServer().getAdvancements().getAdvancement(SummyReliquary.id("sinner"));
		var display = nearlyPerfect == null ? null : nearlyPerfect.getDisplay();
		boolean jsonOk = nearlyPerfect != null && display != null
				&& display.getFrame() == net.minecraft.advancements.FrameType.CHALLENGE
				&& display.isHidden()
				&& display.getIcon().is(SummyReliquary.DUALITY_STAT.get())
				&& nearlyPerfect.getParent() == sinner;
		boolean langOk = translated("advancements.summy-reliquary.nearly_perfect.title")
				&& translated("advancements.summy-reliquary.nearly_perfect.description")
				&& translated("advancements.summy-reliquary.nearly_perfect.description.tail");

		// ① 什么都没完成 → 不发放
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		boolean nothing = !com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "nearly_perfect");

		// ② 补齐 22 条（除无罪之人与自己）→ 发放
		for (String id : com.summy.reliquary.advancement.ReliquaryAdvancements.NEARLY_PERFECT_REQUIRED) {
			setAdvancement(player, id, true);
		}
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		boolean fired = com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "nearly_perfect");

		// ③ 少一条 → 不发放
		setAdvancement(player, "god", false);
		setAdvancement(player, "nearly_perfect", false);
		com.summy.reliquary.advancement.ItemObtained.tick(player);
		boolean missingRejected = !com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "nearly_perfect");

		// ④ 列表完整性：要求列表里的每一条都真实存在于注册表；长度 = 全部 − 2
		boolean idsExist = true;
		for (String id : com.summy.reliquary.advancement.ReliquaryAdvancements.NEARLY_PERFECT_REQUIRED) {
			if (player.getServer().getAdvancements().getAdvancement(SummyReliquary.id(id)) == null) {
				idsExist = false;
			}
		}
		boolean listOk = idsExist && com.summy.reliquary.advancement.ReliquaryAdvancements
				.NEARLY_PERFECT_REQUIRED.size() == all.length - 2;

		log("「近乎完美」（1.7.10 收尾）：JSON（parent=sinner / challenge / hidden / duality_stat）=" + jsonOk
				+ "（应 true）、语言键齐全=" + langOk + "（应 true）、未完成时不发放=" + nothing
				+ "（应 true）、补齐 22 条后发放=" + fired + "（应 true）、少一条不发放=" + missingRejected
				+ "（应 true）、要求列表完整（22 条且 id 都存在）=" + listOk + "（应 true）");

		// 收尾
		for (String id : all) {
			setAdvancement(player, id, false);
		}
	}

	/** 1.7.10：新武器「金刀片」—— 规格 / 无属性 / 无门槛 / 配方 / 固定伤害不吃加成 / 穿透 / CD 与不消耗 */
	private static void checkGoldenRazor(ServerPlayer player) {
		Minecraft client = Minecraft.getInstance();
		MinecraftServer server = player.getServer();
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		clearNearbyMonsters(player, 24.0D);

		ItemStack razor = new ItemStack(SummyReliquary.GOLDEN_RAZOR.get());
		boolean registered = SummyReliquary.GOLDEN_RAZOR.isPresent() && SummyReliquary.THROWN_RAZOR.isPresent();
		String razorModel = resourceText(client, SummyReliquary.NAMESPACE, "models/item/golden_razor.json");
		boolean assets = hasResource(client, "textures/item/golden_razor.png")
				&& hasResource(client, "models/item/golden_razor.json")
				&& razorModel.contains("item/handheld") && !razorModel.contains("overrides");
		boolean lang = translated("item.summy-reliquary.golden_razor")
				&& translated("item.summy-reliquary.golden_razor.tagline.1")
				&& translated("item.summy-reliquary.golden_razor.shift.1")
				&& translated("death.attack.summy-reliquary.golden_razor")
				&& translated("death.attack.summy-reliquary.golden_razor.player");
		boolean noAttributes = razor.getAttributeModifiers(EquipmentSlot.MAINHAND).isEmpty()
				&& !(razor.getItem() instanceof net.minecraft.world.item.SwordItem)
				&& !razor.is(net.minecraft.tags.ItemTags.SWORDS);
		boolean ungated = com.summy.reliquary.effect.WeaponGates.qualified(player, razor.getItem())
				&& !com.summy.reliquary.effect.WeaponGates.isRitualWeapon(razor.getItem());
		var recipe = server.getRecipeManager().byKey(SummyReliquary.id("golden_razor")).orElse(null);
		java.util.Map<Item, Integer> counts = recipe == null
				? java.util.Map.of() : ingredientCounts(recipe);
		boolean recipeOk = counts.getOrDefault(Items.GOLD_INGOT, 0) == 6
				&& counts.getOrDefault(Items.GOLD_NUGGET, 0) == 2 && counts.size() == 2;

		// ① 固定伤害不吃任何加成：先量"什么都没戴"的伤害，再量"戴上契约 / 圣心 / 神性"的伤害
		Zombie dummy = spawnTestZombie(player, 3.0D, 0.0D);
		dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		dummy.setHealth(200.0F);
		var razorPlain = new com.summy.reliquary.entity.ThrownRazor(level, player);
		dummy.invulnerableTime = 0;
		razorPlain.hitEntityForTest(dummy);
		float lostPlain = 200.0F - dummy.getHealth();
		// 事件层面再确认一次：戴满饰品时事件数值也必须原样（5.0）
		LivingHurtEvent event = new LivingHurtEvent(dummy,
				razorDamageSource(level, player, dummy), 5.0F);
		com.summy.reliquary.ReliquaryEvents.onLivingHurt(event);
		equipAt(player, ReliquarySlots.DEMON_PACT, 0, SummyReliquary.THE_PACT.get());
		equipAt(player, ReliquarySlots.BLESSING, 0, SummyReliquary.SACRED_HEART.get());
		equip(player, ReliquarySlots.REVELATION, SummyReliquary.GODHEAD.get());
		LivingHurtEvent buffedEvent = new LivingHurtEvent(dummy,
				razorDamageSource(level, player, dummy), 5.0F);
		com.summy.reliquary.ReliquaryEvents.onLivingHurt(buffedEvent);
		boolean noBonus = Math.abs(event.getAmount() - 5.0F) < 1.0E-4F
				&& Math.abs(buffedEvent.getAmount() - event.getAmount()) < 1.0E-4F;

		// ② 真实命中：固定 5 点（戴满饰品时与空手时**完全一样**）+ 可穿透（第二个目标也吃 5）
		dummy.setHealth(200.0F);
		dummy.invulnerableTime = 0;
		var razorEntity = new com.summy.reliquary.entity.ThrownRazor(level, player);
		razorEntity.hitEntityForTest(dummy);
		float lostFirst = 200.0F - dummy.getHealth();
		Zombie dummy2 = spawnTestZombie(player, 4.0D, 0.0D);
		dummy2.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		dummy2.setHealth(200.0F);
		dummy2.invulnerableTime = 0;
		razorEntity.hitEntityForTest(dummy2);
		float lostSecond = 200.0F - dummy2.getHealth();
		double razorConfigured = com.summy.reliquary.config.ReliquaryConfig.razorDamage();
		boolean pierceOk = razorEntity.piercedCount() == 2
				&& Math.abs(lostFirst - razorConfigured) < 0.25F
				&& Math.abs(lostSecond - razorConfigured) < 0.25F
				&& Math.abs(lostFirst - lostPlain) < 1.0E-4F;
		dummy.discard();
		dummy2.discard();

		// ③ 投掷：进冷却 0.5 秒、本体不消耗、冷却中再投被拒
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(SummyReliquary.GOLDEN_RAZOR.get()));
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SummyReliquary.GOLDEN_RAZOR.get()));
		player.getCooldowns().removeCooldown(SummyReliquary.GOLDEN_RAZOR.get());
		int countBefore = countItem(player, SummyReliquary.GOLDEN_RAZOR.get());
		var first = SummyReliquary.GOLDEN_RAZOR.get().use(level, player, InteractionHand.MAIN_HAND);
		boolean threw = first.getResult() == net.minecraft.world.InteractionResult.SUCCESS
				|| first.getResult() == net.minecraft.world.InteractionResult.CONSUME;
		boolean cooldown = player.getCooldowns().isOnCooldown(SummyReliquary.GOLDEN_RAZOR.get());
		int countAfter = countItem(player, SummyReliquary.GOLDEN_RAZOR.get());
		var second = SummyReliquary.GOLDEN_RAZOR.get().use(level, player, InteractionHand.MAIN_HAND);
		boolean blocked = second.getResult() == net.minecraft.world.InteractionResult.FAIL;
		int spawned = 0;
		for (var entity : level.getEntitiesOfClass(com.summy.reliquary.entity.ThrownRazor.class,
				player.getBoundingBox().inflate(8.0D))) {
			spawned++;
			entity.discard();
		}
		boolean useOk = threw && cooldown && countAfter == countBefore && blocked && spawned == 1
				&& com.summy.reliquary.config.ReliquaryConfig.razorCooldownTicks() == 10
				&& !razorEntity.isPickupAllowed();
		player.getCooldowns().removeCooldown(SummyReliquary.GOLDEN_RAZOR.get());

		// ④ 1.7.10 收尾：出手速度 1.8 + 命中方块后插在原地停留 100 tick（5 秒）再清除
		com.summy.reliquary.entity.ThrownRazor stuckRazor =
				new com.summy.reliquary.entity.ThrownRazor(level, player);
		boolean tuningOk = Math.abs(com.summy.reliquary.config.ReliquaryConfig.razorVelocity() - 1.8D) < 1.0E-6D
				&& com.summy.reliquary.config.ReliquaryConfig.razorStuckTicks() == 100
				&& Math.abs(com.summy.reliquary.config.ReliquaryConfig.razorMaxLifeTicks() - 200.0D) < 1.0E-6D;
		stuckRazor.setDeltaMovement(0.4D, -0.3D, 0.2D);
		stuckRazor.hitBlockForTest(player.position().add(0.0D, 1.0D, 1.0D));
		boolean stuckEntered = !stuckRazor.isRemoved() && stuckRazor.isStuck();
		boolean stuckFrozen = stuckRazor.getDeltaMovement().length() < 1.0E-9D;
		stuckRazor.stuckCountdownForTest(99);
		boolean stuckAlive = !stuckRazor.isRemoved() && stuckRazor.stuckTicks() == 99;
		stuckRazor.stuckCountdownForTest(1);
		boolean stuckGone = stuckRazor.isRemoved() && stuckRazor.stuckTicks() == 100;
		boolean stuckOk = tuningOk && stuckEntered && stuckFrozen && stuckAlive && stuckGone;

		log("金刀片（1.7.10）：注册（物品 + 投掷实体）=" + registered + "（应 true）、素材（32×32 贴图 + handheld 模型且无 overrides）="
				+ assets + "（应 true）、语言键（名 / 风味 / Shift / 两条死亡文本）=" + lang + "（应 true）、"
				+ "无属性（无主手修饰符、非剑、不进 #swords）=" + noAttributes + "（应 true）、无门槛=" + ungated
				+ "（应 true）、配方（金锭×" + counts.getOrDefault(Items.GOLD_INGOT, 0) + " + 金粒×"
				+ counts.getOrDefault(Items.GOLD_NUGGET, 0) + "，应 6 + 2）=" + recipeOk
				+ "（应 true）；固定伤害：空手时事件数值=" + String.format("%.1f", event.getAmount())
				+ "、戴契约 / 圣心 / 神性时=" + String.format("%.1f", buffedEvent.getAmount())
				+ "（都应 5，不吃任何加成）=" + noBonus + "（应 true）、真实命中掉血 空手 "
				+ String.format("%.1f", lostPlain) + " → 戴满 " + String.format("%.1f", lostFirst)
				+ "（应完全一样）+ 穿透第二个目标 " + String.format("%.1f", lostSecond) + "（各应 5）=" + pierceOk
				+ "（应 true）；投掷：已投出=" + threw + "、进冷却=" + cooldown + "、本体数量 "
				+ countBefore + " → " + countAfter + "（应不变）、冷却中再投被拒=" + blocked
				+ "、场上幻影=" + spawned + "（应 1）→ " + useOk + "（应 true）"
				+ "；1.7.10 收尾：出手速度=" + String.format("%.2f", com.summy.reliquary.config.ReliquaryConfig.razorVelocity())
				+ "（应 1.80）、落地停留=" + com.summy.reliquary.config.ReliquaryConfig.razorStuckTicks()
				+ " tick（应 100 = 5 秒）：命中方块后未消失=" + stuckEntered + "、速度归零=" + stuckFrozen
				+ "（都应 true）、第 99 tick 仍在=" + stuckAlive + "（应 true）、第 100 tick 已清除=" + stuckGone
				+ "（应 true）→ " + stuckOk + "（应 true）");

		// 收尾
		player.getInventory().clearContent();
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		unequip(player, ReliquarySlots.REVELATION);
		clearBlessingSlots(player);
		clearRobeAndSeal(player);
		clearSpiritAltar(player);
		resetDemonPactState(player);
	}

	/** 自检用：构造金刀片的专属伤害源（与 {@code ThrownRazor} 内部一致） */
	private static net.minecraft.world.damagesource.DamageSource razorDamageSource(
			net.minecraft.server.level.ServerLevel level, ServerPlayer attacker, net.minecraft.world.entity.Entity directEntity) {
		var registry = level.registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
		var holder = registry.getHolder(net.minecraft.resources.ResourceKey.create(
				net.minecraft.core.registries.Registries.DAMAGE_TYPE, SummyReliquary.id("golden_razor")))
				.orElse(null);
		if (holder == null) {
			return level.damageSources().generic();
		}
		return new net.minecraft.world.damagesource.DamageSource(holder, directEntity, attacker);
	}

	/** 1.7.10：创造页顺序（唯一顺序源逐项比对）+ 品牌（mods.toml 描述 / MOD 图标 / 创造页图标） */
	private static void checkCreativeOrderAndBranding(ServerPlayer player) {
		Minecraft client = Minecraft.getInstance();
		// ① 顺序
		java.util.List<Item> order = new java.util.ArrayList<>();
		SummyReliquary.acceptCreativeItems(order::add);
		String[] expected = {
				"sacrificial_dagger", "dark_arts", "holy_spear", "seraph_spear", "golden_razor",
				"the_body", "the_mind", "the_soul", "star_of_bethlehem", "final_revelation", "godhead",
				"salvation", "holy_light", "holy_mantle", "sacred_heart", "the_halo", "pentagram",
				"source_of_sins", "virtues", "satanic_bible", "the_mark", "the_pact", "ceremonial_robes",
				"brimstone", "abaddon", "vengeful_spirit", "night_wraith", "occult_eye", "abyss_lord",
				"devil_crown",
				"sin_fragment_pride", "sin_fragment_gluttony", "sin_fragment_wrath", "sin_fragment_envy",
				"sin_fragment_sloth", "sin_fragment_lust", "sin_fragment_greed", "redemption",
				"act_of_contrition", "wooden_cross", "heart_shard", "six", "trinity", "genesis",
				"freeloaders_rice", "bangbang_halo"};
		boolean sizeOk = order.size() == expected.length && expected.length == 46;
		StringBuilder mismatch = new StringBuilder();
		for (int index = 0; index < Math.min(order.size(), expected.length); index++) {
			var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(order.get(index));
			if (id == null || !expected[index].equals(id.getPath())) {
				mismatch.append(index).append('=').append(id == null ? "?" : id.getPath()).append(' ');
			}
		}
		boolean orderOk = sizeOk && mismatch.length() == 0;

		// ② mod 描述与 logoFile（直接读 Forge 解析好的 IModInfo，避免碰 dev 环境的文件路径）
		var modInfo = net.minecraftforge.fml.ModList.get().getModFileById(SummyReliquary.MOD_ID)
				.getMods().get(0);
		String description = modInfo.getDescription();
		boolean logoOk = "assets/summy-reliquary/icon.png".equals(modInfo.getLogoFile().orElse(""));
		boolean descOk = description != null && description.contains("金刀片")
				&& description.contains("24 个成就") && description.contains("七罪主题饰品合集");
		boolean tomlOk = logoOk && descOk;

		// ③ 图标：icon.png 必须是根成就图标（duality_stat，16×16）的 **32× 最近邻放大**
		//     → 512×512、每个源像素变成 32×32 = 1024 个像素（平台要求 ≥400×400）
		int[] icon = pngStats(client, "icon.png");
		int[] marker = pngStats(client, "textures/item/duality_stat.png");
		boolean iconOk = icon[0] == 512 && icon[1] == 512 && marker[2] > 0 && icon[2] == marker[2] * 1024;

		// ④ 创造页图标（懒构建，构建了才判定）
		var tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
				.get(SummyReliquary.id("main"));
		boolean tabBuilt = tab != null && !tab.getDisplayItems().isEmpty();
		boolean tabIconOk = !tabBuilt || (tab.getIconItem() != null
				&& tab.getIconItem().is(SummyReliquary.DUALITY_STAT.get()));

		log("创造页整理与品牌（1.7.10）：唯一顺序源共 " + order.size() + " 项（应 46）、逐项与预期一致="
				+ orderOk + "（应 true" + (mismatch.length() == 0 ? "" : "，异常：" + mismatch.toString().trim())
				+ "）、分块=武器 → 天使线 → 恶魔线 → 中立材料工具；mods.toml（logoFile=" + modInfo.getLogoFile()
				+ "=" + logoOk + "、描述含「金刀片 / 24 个成就 / 七罪主题饰品合集」=" + descOk + "）="
				+ tomlOk + "（应 true）；icon.png=" + icon[0] + "×" + icon[1] + "、不透明像素 "
				+ icon[2] + "（duality_stat=" + marker[2] + "）→ " + iconOk + "（应 true）；创造页图标="
				+ (tabBuilt ? String.valueOf(tabIconOk) : "（未构建，按不判定处理）"));
	}

	/** 自检用：读资源包里的 PNG 尺寸与"不透明像素数"（确认图标就是根成就图标放大版） */
	private static int[] pngStats(Minecraft client, String path) {
		var resource = client.getResourceManager().getResource(
				new net.minecraft.resources.ResourceLocation(SummyReliquary.NAMESPACE, path));
		if (resource.isEmpty()) {
			return new int[]{0, 0, 0};
		}
		try (var stream = resource.get().open()) {
			var image = javax.imageio.ImageIO.read(stream);
			if (image == null) {
				return new int[]{0, 0, 0};
			}
			int opaque = 0;
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					if (((image.getRGB(x, y) >>> 24) & 0xFF) > 0) {
						opaque++;
					}
				}
			}
			return new int[]{image.getWidth(), image.getHeight(), opaque};
		} catch (Exception exception) {
			return new int[]{0, 0, 0};
		}
	}

	/** 取目标身上狱火的等级（0 = 没有该效果） */
	private static int hellfireLevel(LivingEntity target) {
		var effect = target.getEffect(SummyReliquary.HELLFIRE.get());
		return effect == null ? 0 : effect.getAmplifier() + 1;
	}

	/** 给玩家塞满 6 件带诅咒的装备（满足契约"诅咒 > 5 条"的吸血门槛） */
	private static void equipSixCurses(ServerPlayer player) {
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
				EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND};
		Item[] gear = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS,
				Items.DIAMOND_BOOTS, Items.SHIELD, Items.DIAMOND_SWORD};
		for (int index = 0; index < slots.length; index++) {
			ItemStack stack = new ItemStack(gear[index]);
			stack.enchant(net.minecraft.world.item.enchantment.Enchantments.VANISHING_CURSE, 1);
			player.setItemSlot(slots[index], stack);
		}
	}

	/**
	 * 1.7.10 恶魔线联动：斩击接契约（吸血 + 击杀给邪恶度）、强化斩击接咒印（黑心爆发伤害）、
	 * 接触标记接深渊领主（1 层狱火，斩击后只退自己那 1 层）、亚巴顿（技能三项强化）。
	 */
	private static void checkDemonWeaponSynergies(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		net.minecraft.server.level.ServerLevel level = player.serverLevel();
		ItemStack dagger = new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get());

		// ===== ① 契约：斩击伤害吸血 + 斩击击杀给邪恶度 =====
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		resetSinState(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		player.removeAllEffects();
		clearNearbyMonsters(player, 24.0D);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		equipSixCurses(player);
		// 恶魔契约栏是**动态**的（有恶魔标记才出现 1 格）：走 grant(false) 让它自己把栏位开出来并强制佩戴
		com.summy.reliquary.effect.DemonPact.grant(player, false);
		boolean lifestealGate = com.summy.reliquary.effect.DemonPact.lifestealActive(player);
		Zombie hitTarget = spawnTestZombie(player, 3.0D, 0.0D);
		player.setHealth(player.getMaxHealth() * 0.5F);
		float healthBefore = player.getHealth();
		com.summy.reliquary.ReliquaryEvents.onLivingHurt(new LivingHurtEvent(hitTarget,
				player.damageSources().playerAttack(player), 10.0F));
		float healed = player.getHealth() - healthBefore;
		boolean lifestealWorks = healed > 0.0F;
		hitTarget.discard();

		double evilBefore = com.summy.reliquary.effect.PlayerFlags.evil(player);
		Zombie victim = spawnTestZombie(player, 3.0D, 0.0D);
		victim.setHealth(0.5F);
		victim.hurt(player.damageSources().playerAttack(player), 10.0F);
		double evilAfter = com.summy.reliquary.effect.PlayerFlags.evil(player);
		boolean evilGrows = evilAfter > evilBefore;
		victim.discard();
		log("武器联动·契约（1.7.10）：诅咒 > 5 条时吸血激活=" + lifestealGate + "（应 true）、斩击（playerAttack 源）"
				+ "回血=" + String.format("%.2f", healed) + "（应 > 0）=" + lifestealWorks + "（应 true）；"
				+ "斩击击杀 → 邪恶度 " + String.format("%.1f", evilBefore) + " → "
				+ String.format("%.1f", evilAfter) + "（应上升）=" + evilGrows + "（应 true）");

		// ===== ② 强化斩击 → 咒印的黑心爆发伤害（不动池子 / 补满计时）=====
		resetDemonPactState(player);
		clearRobeAndSeal(player);
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		clearNearbyMonsters(player, 24.0D);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		double poolBefore = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player);
		int refillBefore = com.summy.reliquary.effect.DemonPact.blackHeartRefillRemainingForTest(player);
		// 4 格：超出接触半径(2) → 不会被打标记 / 强力斩击(3 格)，但落在黑心爆发的 18 格内
		Zombie far = spawnTestZombie(player, 4.0D, 0.0D);
		far.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		far.setHealth(200.0F);
		player.setItemSlot(EquipmentSlot.MAINHAND, dagger);
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		boolean started = com.summy.reliquary.effect.ShadowDash.tryStart(player, dagger, false);
		for (int index = 0; index < 34; index++) {
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
		}
		float farLost = 200.0F - far.getHealth();
		double markShatter = com.summy.reliquary.config.ReliquaryConfig.markShatterDamage();
		boolean shatterOnHeavy = Math.abs(farLost - markShatter) < 0.01F;
		boolean poolUntouched = com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player) == poolBefore
				&& com.summy.reliquary.effect.DemonPact.blackHeartRefillRemainingForTest(player) == refillBefore;
		far.discard();
		com.summy.reliquary.effect.ShadowDash.clear();
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		// 不戴咒印 → 不触发
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		clearNearbyMonsters(player, 24.0D);
		Zombie far2 = spawnTestZombie(player, 4.0D, 0.0D);
		far2.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		far2.setHealth(200.0F);
		player.setItemSlot(EquipmentSlot.MAINHAND, dagger);
		com.summy.reliquary.effect.ShadowDash.tryStart(player, dagger, false);
		for (int index = 0; index < 34; index++) {
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
		}
		boolean noMarkNoShatter = far2.getHealth() >= 200.0F;
		far2.discard();
		com.summy.reliquary.effect.ShadowDash.clear();
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		log("武器联动·咒印（1.7.10）：戴咒印时强力斩击触发黑心爆发 → 18 格内（4 格处、未被打标记）的敌人掉血="
				+ String.format("%.1f", farLost) + "（应 " + String.format("%.0f", markShatter) + "）="
				+ shatterOnHeavy + "（应 true）[技能已触发=" + started + "]、黑心池与补满计时都没被改动="
				+ poolUntouched + "（应 true）；不戴咒印时同场景掉血=" + String.format("%.1f", 200.0F - far2.getHealth())
				+ "（应 0）=" + noMarkNoShatter + "（应 true）");

		// ===== ③ 接触标记 → 1 层狱火（斩击后只退自己那 1 层）=====
		resetDemonPactState(player);
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		clearNearbyMonsters(player, 24.0D);
		com.summy.reliquary.effect.PlayerFlags.setDemon(player, true);
		equipAt(player, ReliquarySlots.BLESSING, 0, SummyReliquary.ABYSS_LORD.get());
		Zombie markTarget = spawnTestZombie(player, 1.0D, 0.0D);
		markTarget.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		markTarget.setHealth(200.0F);
		player.setItemSlot(EquipmentSlot.MAINHAND, dagger);
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		com.summy.reliquary.effect.ShadowDash.tryStart(player, dagger, false);
		com.summy.reliquary.effect.ShadowDash.tickServer(server);
		int levelAfterMark = hellfireLevel(markTarget);
		// 判定期还剩 19 tick：逐 tick 记一下狱火等级，方便一眼看出"加了几层、退了几层"
		StringBuilder trace = new StringBuilder();
		for (int index = 0; index < 19; index++) {
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
			trace.append(hellfireLevel(markTarget));
		}
		// 这一 tick 结算标记目标：斩击本身也会让深渊领主再叠 1 层，随后只退掉"标记时自己加的那 1 层"
		com.summy.reliquary.effect.ShadowDash.tickServer(server);
		int levelAfterSlash = hellfireLevel(markTarget);
		// 再推 2 tick 才会放强力斩击（结算间隔 2 tick；每次都先清掉无敌帧，好让它真的打在它身上），
		// 强力斩击又是一次深渊领主叠层 —— 用来确认"只退了 1 层"而不是清空
		for (int index = 0; index < 2; index++) {
			markTarget.invulnerableTime = 0;
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
		}
		int levelAfterHeavy = hellfireLevel(markTarget);
		boolean markHellfire = levelAfterMark == 1 && levelAfterSlash == 1 && levelAfterHeavy == 2;
		markTarget.discard();
		com.summy.reliquary.effect.ShadowDash.clear();
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		// 目标原本就有 2 层（别人叠的）→ 标记 +1 = 3 层 → 斩击 +1 = 4 层 → 只退 1 = 3 层（别人的 2 层原样保留）
		clearNearbyMonsters(player, 24.0D);
		Zombie marked2 = spawnTestZombie(player, 1.0D, 0.0D);
		marked2.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
		marked2.setHealth(200.0F);
		com.summy.reliquary.effect.AbyssLord.applyStack(marked2);
		com.summy.reliquary.effect.AbyssLord.applyStack(marked2);
		int preExisting = hellfireLevel(marked2);
		player.setItemSlot(EquipmentSlot.MAINHAND, dagger);
		com.summy.reliquary.effect.ShadowDash.tryStart(player, dagger, false);
		com.summy.reliquary.effect.ShadowDash.tickServer(server);
		int afterMark2 = hellfireLevel(marked2);
		for (int index = 0; index < 19; index++) {
			com.summy.reliquary.effect.ShadowDash.tickServer(server);
		}
		com.summy.reliquary.effect.ShadowDash.tickServer(server);
		int afterSlash2 = hellfireLevel(marked2);
		boolean onlyOneRemoved = preExisting == 2 && afterMark2 == 3 && afterSlash2 == 3;
		marked2.discard();
		com.summy.reliquary.effect.ShadowDash.clear();
		player.getCooldowns().removeCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get());
		log("武器联动·狱火（1.7.10）：戴深渊领主时接触标记给 1 层狱火（0 → " + levelAfterMark
				+ "）[判定期后续等级序列=" + trace + "]；结算那一刻：斩击让深渊领主再 +1、随后只退掉自己那 1 层 → " + levelAfterSlash
				+ " 层（若清空会是 0、若不退会是 2）、再放强力斩击（又一次 +1）→ " + levelAfterHeavy
				+ " 层（应 2）=" + markHellfire + "（应 true）；目标原本已有 2 层（别人叠的）：标记后 "
				+ afterMark2 + " 层、斩击后 " + afterSlash2 + " 层（应仍 3 = 只退 1 层、不清空）="
				+ onlyOneRemoved + "（应 true）");

		// ===== ④ 亚巴顿：技能时长 +1 秒 / 接触半径 +1 / 强力半径 +2 =====
		clearBlessingSlots(player);
		clearNearbyMonsters(player, 24.0D);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		double contactBase = com.summy.reliquary.effect.Synergies.shadowDashContactRadius(player);
		double heavyBase = com.summy.reliquary.effect.Synergies.shadowDashHeavyRadius(player);
		int durationBase = com.summy.reliquary.effect.Synergies.shadowDashDurationTicks(player, false);
		equipAt(player, ReliquarySlots.BLESSING, 0, SummyReliquary.ABADDON.get());
		double contactWith = com.summy.reliquary.effect.Synergies.shadowDashContactRadius(player);
		double heavyWith = com.summy.reliquary.effect.Synergies.shadowDashHeavyRadius(player);
		int durationWith = com.summy.reliquary.effect.Synergies.shadowDashDurationTicks(player, false);
		// 亚巴顿 + 咒印的爆发值也一并核对（60）
		equip(player, ReliquarySlots.SPIRIT_ALTAR, SummyReliquary.THE_MARK.get());
		double abaddonShatter = com.summy.reliquary.effect.Synergies.shatterDamage(player);
		boolean shatterAbaddon = Math.abs(abaddonShatter
				- com.summy.reliquary.config.ReliquaryConfig.markShatterDamageAbaddon()) < 1.0E-6D;
		clearSpiritAltar(player);
		boolean abaddonOk = Math.abs(contactWith - contactBase
						- com.summy.reliquary.config.ReliquaryConfig.shadowDashAbaddonContactRadius()) < 1.0E-6D
				&& Math.abs(heavyWith - heavyBase
						- com.summy.reliquary.config.ReliquaryConfig.shadowDashAbaddonHeavyRadius()) < 1.0E-6D
				&& durationWith - durationBase
						== com.summy.reliquary.config.ReliquaryConfig.shadowDashAbaddonDurationTicks()
				&& shatterAbaddon;
			log("武器联动·亚巴顿（1.7.10）：接触半径 " + String.format("%.1f", contactBase) + " → "
				+ String.format("%.1f", contactWith) + "（应 +1）、强力半径 " + String.format("%.1f", heavyBase)
				+ " → " + String.format("%.1f", heavyWith) + "（应 +2）、判定时长 " + durationBase + " → "
				+ durationWith + " tick（应 +20 = +1 秒）、亚巴顿 + 咒印 的爆发值="
				+ String.format("%.0f", abaddonShatter) + "（应 60）→ " + abaddonOk + "（应 true）");

		// 收尾
		clearBlessingSlots(player);
		clearSpiritAltar(player);
		clearRobeAndSeal(player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		com.summy.reliquary.effect.ShadowDash.clear();
		resetDemonPactState(player);
		resetSinState(player);
	}
}
