package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.advancement.ItemObtained;
import com.summy.reliquary.client.ReliquaryClientState;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.net.ReliquaryNetworking;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「契约」的服务端逻辑（1.6.0）：动态栏位、强制佩戴 / 收回、圣物没收与补偿、黑心池、
 * 邪恶度（获取 / 衰减 / 里程碑解锁）、献祭（对村民一击必杀 + 完成判定）。
 *
 * <p>核心口径：
 * <ul>
 *     <li><b>契约效果全部挂在"佩戴契约"上</b>：未佩戴时属性、黑心、献祭、邪恶度积攒全部不生效；</li>
 *     <li><b>恶魔契约栏位是动态的</b>：持恶魔标记时 1 格、否则 0 格（Curios 的槽位尺寸修饰符会随存档持久化）；</li>
 *     <li><b>黑心是独立池</b>：不占用原版吸收值，在 {@code LivingDamageEvent} 里最后一道防线地吸收伤害；</li>
 *     <li><b>邪恶度跟着玩家走</b>：跨契约保留，但只有佩戴契约时才积攒、才让加成生效。</li>
 * </ul>
 */
public final class DemonPact {
	/** 恶魔话语的暗红（与 DemonDeal 保持一致） */
	public static final int CHAT_RED = 0x8B0000;
	/** 灰色旁白 */
	public static final int GRAY = 0xAAAAAA;

	/**
	 * 签约时会被**没收**的天使线饰品（1.7.2：加入伯列恒之星）。
	 *
	 * <p>口径见 1.7.2 的"不可回头点为界"规则：签约必然发生在**未过启示**时，
	 * 所以天使线里"过线前可得"的东西一律没收；圣心不在这里（它改成"持有即封锁交易"）。
	 */
	private static final List<java.util.function.Supplier<Item>> RELICS = List.of(
			SummyReliquary.HOLY_LIGHT,
			SummyReliquary.SALVATION,
			SummyReliquary.HOLY_MANTLE,
			SummyReliquary.STAR_OF_BETHLEHEM);
	/** 灵台三件套（被没收时按数量补偿「6」） */
	private static final List<java.util.function.Supplier<Item>> ALTAR_RELICS = List.of(
			SummyReliquary.THE_BODY,
			SummyReliquary.THE_MIND,
			SummyReliquary.THE_SOUL);
	/**
	 * 签约时**只强制摘除、不没收**的天使线饰品（1.7.2）。
	 *
	 * <p>它们都属于"过线或封锁"档：持有终末天启 / 神性会在每秒兜底里置位"已获取启示"、
	 * 持有圣心会直接关闭恶魔交易，所以正常情况下根本签不了；万一因指令 / 创造模式绕过来，
	 * 就把它们从饰品栏摘下来（放进背包，绝不删除）。
	 */
	private static final List<java.util.function.Supplier<Item>> ANGEL_RELICS_UNEQUIP_ONLY = List.of(
			SummyReliquary.FINAL_REVELATION,
			SummyReliquary.GODHEAD,
			SummyReliquary.SACRED_HEART);
	/**
	 * 忏悔时会被**没收**的恶魔线饰品（1.7.2）：都是"666（恶魔线的不可回头点）之前可得"的。
	 *
	 * <p>撒旦圣经不在这里 —— 它是魂印物品，由快照还原逻辑负责替换掉。
	 * 恶魔王冠也**不没收**（按需求留给玩家作纪念）。
	 */
	private static final List<java.util.function.Supplier<Item>> DEMON_RELICS_BEFORE_666 = List.of(
			SummyReliquary.CEREMONIAL_ROBES,
			SummyReliquary.THE_MARK,
			SummyReliquary.VENGEFUL_SPIRIT,
			SummyReliquary.NIGHT_WRAITH);
	/**
	 * 忏悔时**只强制摘除、不没收**的恶魔线饰品（1.7.2）：都是 666 之后才解锁的
	 * （硫磺火 666 / 玄秘魔眼 700 / 深渊领主 900 / 亚巴顿 1000），正常忏悔时不该出现在身上。
	 */
	private static final List<java.util.function.Supplier<Item>> DEMON_RELICS_AFTER_666 = List.of(
			SummyReliquary.BRIMSTONE,
			SummyReliquary.OCCULT_EYE,
			SummyReliquary.ABYSS_LORD,
			SummyReliquary.ABADDON);

	/** 防止"献祭追加伤害 → 再次触发事件 → 再追加"的递归 */
	private static boolean applyingSacrifice = false;
	/** 防止"黑心碎裂伤害 → 再次触发受伤事件 → 再次判定碎裂"的递归 */
	private static boolean applyingShatter = false;
	/** 撒旦圣经：距离下一次「黑心补满」还剩多少秒（服务端内存计时，与魂心的刷新计时同款做法） */
	private static final Map<UUID, Integer> BLACK_HEART_REFILL = new HashMap<>();

	private DemonPact() {
	}

	// ==================== 佩戴判定与数值 ====================

	/** 是否佩戴着契约 */
	public static boolean wears(LivingEntity entity) {
		return entity != null && CurioHelper.wears(entity, SummyReliquary.THE_PACT.get());
	}

	/** 契约功能是否生效（总开关 + 佩戴） */
	public static boolean active(LivingEntity entity) {
		return ReliquaryConfig.enableDemonPact() && wears(entity);
	}

	/** 是否佩戴着仪式法袍（1.6.1） */
	public static boolean wearsRobe(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableRobe()
				&& CurioHelper.wears(entity, SummyReliquary.CEREMONIAL_ROBES.get());
	}

	/** 是否佩戴着撒旦圣经（1.6.1） */
	public static boolean wearsSatanicBible(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableSatanicBible()
				&& CurioHelper.wears(entity, SummyReliquary.SATANIC_BIBLE.get());
	}

	/**
	 * 黑心池上限（点）：基础黑心 + 仪式法袍 + 撒旦圣经。
	 *
	 * <p>1.6.1 起不再是固定值 —— 卸下加成来源后上限会下降，{@code tickPlayer} 会把当前值夹回上限。
	 */
	public static double blackHeartMaxPoints(LivingEntity entity) {
		double hearts = ReliquaryConfig.pactBlackHearts();
		if (wearsRobe(entity)) {
			hearts += ReliquaryConfig.robeBlackHearts();
		}
		if (wearsSatanicBible(entity)) {
			hearts += ReliquaryConfig.satanicBibleBlackHearts();
		}
		// 1.6.2：咒印把灵魂的魂心转化成黑心（+3 心）
		hearts += com.summy.reliquary.effect.SatanicMark.blackHeartHearts(entity);
		return hearts * ReliquaryConfig.pactAbsorptionPerBlackHeart();
	}

	/** 邪恶度（客户端读同步值、服务端读存档） */
	public static int evilValue(LivingEntity entity) {
		if (entity == null) {
			return 0;
		}
		if (entity.level().isClientSide()) {
			return (int) Math.floor(ReliquaryClientState.evil());
		}
		return PlayerFlags.evilDisplay(entity);
	}

	/**
	 * 邪恶度的**精确值**（1.6.4：契约提示里显示一位小数）。
	 *
	 * <p>只用于显示；阈值判定与攻击 / 攻速加成仍走 {@link #evilValue} 的整数口径，避免改动玩法数值。
	 */
	public static double evilExact(LivingEntity entity) {
		if (entity == null) {
			return 0.0D;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.evil();
		}
		return PlayerFlags.evilExact(entity);
	}

	/** 黑心池剩余点数（客户端读同步值） */
	public static int blackHeartPoints(LivingEntity entity) {
		if (entity == null) {
			return 0;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.blackHeartPoints();
		}
		return (int) Math.round(PlayerFlags.blackHeartPoints(entity));
	}

	/** 已解锁的里程碑位图（客户端读同步值） */
	public static int evilUnlocks(LivingEntity entity) {
		if (entity == null) {
			return 0;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.evilUnlocks();
		}
		return PlayerFlags.evilUnlocks(entity);
	}

	/**
	 * 生效诅咒条数：4 件盔甲 + 主手 + 副手，统计 {@code Enchantment#isCurse()} 的条数。
	 *
	 * <p>用 `Enchantment#isCurse()` 而不是物品标签（1.20.1 没有诅咒标签），这样**原版的消失诅咒 /
	 * 绑定诅咒**与其它模组（如 enchantment-reforged）自定的诅咒附魔都会被算进来。
	 */
	public static int curseCount(LivingEntity entity) {
		if (entity == null) {
			return 0;
		}
		int count = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
			ItemStack stack = entity.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			for (var entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
				if (entry.getKey().isCurse()) {
					count++;
				}
			}
		}
		return count;
	}

	/** 邪恶度带来的攻击加成（%）：奇数点给攻击（ceil） */
	public static double evilAttackPercent(int evil) {
		return (evil + 1) / 2 * ReliquaryConfig.evilPerPointPercent();
	}

	/** 邪恶度带来的攻速加成（%）：偶数点给攻速（floor） */
	public static double evilSpeedPercent(int evil) {
		return evil / 2 * ReliquaryConfig.evilPerPointPercent();
	}

	/** 契约 + 邪恶度 的伤害加成（%，进「全伤害最终倍率」乘区） */
	public static double attackBonusPercent(LivingEntity entity) {
		if (!active(entity)) {
			return 0.0D;
		}
		return ReliquaryConfig.pactDamagePercent() + evilAttackPercent(evilValue(entity));
	}

	/** 契约 + 邪恶度 的攻速加成（%） */
	public static double attackSpeedBonusPercent(LivingEntity entity) {
		if (!active(entity)) {
			return 0.0D;
		}
		return ReliquaryConfig.pactAttackSpeedPercent() + evilSpeedPercent(evilValue(entity));
	}

	/** 按诅咒条数换算的生命加成（%） */
	public static double healthBonusPercent(LivingEntity entity) {
		return active(entity) ? ReliquaryConfig.pactHealthPerCursePercent() * curseCount(entity) : 0.0D;
	}

	/** 按诅咒条数换算的移动速度加成（%） */
	public static double speedBonusPercent(LivingEntity entity) {
		return active(entity) ? ReliquaryConfig.pactSpeedPerCursePercent() * curseCount(entity) : 0.0D;
	}

	/** 是否满足生命吸取条件（诅咒条数 > 阈值） */
	public static boolean lifestealActive(LivingEntity entity) {
		return active(entity) && curseCount(entity) > ReliquaryConfig.pactLifestealCurseThreshold();
	}

	// ==================== 动态栏位 / 契约生命周期 ====================

	/** 把恶魔契约栏位校正到「持恶魔标记 = 1 格、否则 0 格」 */
	public static void syncSlot(ServerPlayer player) {
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return;
		}
		var stacks = handler.getCurios().get(ReliquarySlots.DEMON_PACT);
		if (stacks == null) {
			return;
		}
		boolean want = PlayerFlags.isDemon(player);
		int current = stacks.getSlots();
		if (want && current < 1) {
			handler.growSlotType(ReliquarySlots.DEMON_PACT, 1 - current);
		} else if (!want && current > 0) {
			// 先清空格内物品，避免缩格时被挤出/丢失
			for (int index = 0; index < current; index++) {
				handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, index, ItemStack.EMPTY);
			}
			handler.shrinkSlotType(ReliquarySlots.DEMON_PACT, current);
		}
	}

	/** 该玩家身上（背包或栏位）是否有契约 */
	public static boolean hasContract(ServerPlayer player) {
		return ItemObtained.has(player, SummyReliquary.THE_PACT.get());
	}

	/**
	 * 发放契约并强制佩戴（1.6.0）。
	 *
	 * @param countSign true = 这是一次"真正的签约"（累加签约次数）；false = 自愈补发
	 */
	public static void grant(ServerPlayer player, boolean countSign) {
		if (!ReliquaryConfig.enableDemonPact()) {
			return;
		}
		syncSlot(player);
		// 1.6.1：真正的签约那一刻先记录「魂印物品 + 七罪逐项状态」快照，再把七罪重置成恶魔侧状态
		// （美德 → 七罪之源 + 全部未激活；已赎罪的罪 → 未激活）。悔罪时按快照原样恢复。
		if (countSign) {
			snapshotAndResetSins(player);
		}
		// 1.7.1：判据改成"**栏位里**有没有契约" —— 背包里已有那份就优先直接佩戴（不新造一份），
		// 否则才补发一份强制佩戴。避免"契约躺在背包、栏位空着、契约效果全不生效"。
		if (!wearsContract(player)) {
			ItemStack pact = takePactFromInventory(player);
			if (pact.isEmpty()) {
				pact = new ItemStack(SummyReliquary.THE_PACT.get());
			}
			boolean equipped = false;
			var handler = CuriosApi.getCuriosInventory(player).orElse(null);
			if (handler != null && handler.getCurios().containsKey(ReliquarySlots.DEMON_PACT)
					&& handler.getCurios().get(ReliquarySlots.DEMON_PACT).getSlots() > 0
					&& handler.getCurios().get(ReliquarySlots.DEMON_PACT).getStacks()
							.getStackInSlot(0).isEmpty()) {
				handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, 0, pact);
				equipped = true;
			}
			if (!equipped && !player.getInventory().add(pact)) {
				player.drop(pact, false);
			}
		}
		// 每份新契约都要重新走一遍献祭
		PlayerFlags.setSacrificeDone(player, false);
		if (countSign) {
			PlayerFlags.setPactSigns(player, PlayerFlags.pactSigns(player) + 1);
			// 1.6.9：进度「成交」——只有真正的签约才算（自愈补发不触发）
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
					com.summy.reliquary.advancement.ReliquaryAdvancements.PACT_SIGNED);
		}
		confiscateRelics(player);
		// 1.7.6：签到那一刻把「献祭匕首」交给玩家（身上一把都没有才发，避免重复发放）
		if (countSign) {
			grantSacrificialDagger(player);
		}
		sync(player);
	}

	/** 1.7.6：签约时给一把献祭匕首（身上已经有任意一把仪式匕首就跳过；背包满则掉脚下并受掉落保护） */
	private static void grantSacrificialDagger(ServerPlayer player) {
		// 1.8.0：先置位"已发放" —— 即使身上已有一把、或背包满把匕首掉在地上/掉进虚空，
		// 这次发放也算发生过，之后丢失满 5 分钟一定会收到防丢失提示。
		com.summy.reliquary.effect.PlayerFlags.setDaggerGranted(player, true);
		if (DaggerRecovery.holdsDagger(player)) {
			return;
		}
		ItemStack dagger = new ItemStack(SummyReliquary.SACRIFICIAL_DAGGER.get());
		if (!player.getInventory().add(dagger)) {
			player.drop(dagger, false);
		}
	}

	// ==================== 签约快照 / 七罪状态迁移（1.6.1） ====================

	/**
	 * 签约瞬间：把魂印栏物品与七罪逐项状态存进玩家 NBT，然后重置成"恶魔侧"状态。
	 *
	 * <p><b>1.7.2 的魂印栏口径（四态）</b>：
	 * <ul>
	 *     <li>美德 / 撒旦圣经 → 就地换成七罪之源（圣经被这场交易吞掉）；</li>
	 *     <li>七罪之源 → 不动；空栏 → 不动（避免"空栏签约 → 忏悔白得一份"）。</li>
	 * </ul>
	 * 快照的 {@code item} 因此有 {@code virtues} / {@code source_of_sins} / {@code none} 三种取值。
	 *
	 * <p><b>1.7.2 的七罪重置口径</b>：只把**已赎罪**的项置为未激活，已激活的项原样保留 ——
	 * 于是"3 激活 + 4 赎罪"签约后是"3 激活 + 4 未激活"，玩家只需重新激活当初赎过的那几项
	 * 就能把七罪之源变成撒旦圣经；美德态（七项全为已赎罪）仍然会全部变未激活。
	 */
	private static void snapshotAndResetSins(ServerPlayer player) {
		boolean hadVirtues = CurioHelper.wears(player, SummyReliquary.VIRTUES.get());
		boolean hadBible = CurioHelper.wears(player, SummyReliquary.SATANIC_BIBLE.get());
		boolean hadSource = CurioHelper.wears(player, SummyReliquary.SOURCE_OF_SINS.get());
		String seal = hadVirtues ? "virtues" : (hadBible || hadSource) ? "source_of_sins" : "none";
		PlayerFlags.setSinSnapshot(player, seal, SinManager.mask(player), SinManager.redeemedMask(player));
		if (hadVirtues || hadBible) {
			setSoulSeal(player, SummyReliquary.SOURCE_OF_SINS.get());
		}
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			// 只重置"已赎罪"（含美德态）的项；已激活的项保持激活
			if (SinManager.state(player, sin) == SinManager.SinState.REDEEMED) {
				SinManager.setState(player, sin, SinManager.SinState.UNACTIVATED);
			}
		}
	}

	/**
	 * 悔罪（痛悔短祷换回天使标记）时：按签约快照恢复魂印栏物品与七罪逐项状态。
	 *
	 * @return true 表示确实恢复了快照（老存档没有快照时返回 false，不做任何改动）
	 */
	public static boolean restoreSinSnapshot(ServerPlayer player) {
		net.minecraft.nbt.CompoundTag snapshot = PlayerFlags.sinSnapshot(player);
		if (snapshot == null) {
			return false;
		}
		PlayerFlags.clearSinSnapshot(player);
		String item = snapshot.getString("item");
		if ("none".equals(item)) {
			// 空栏签约：魂印栏保持不动；只有"身上一件魂印物品都没有"（例如被 /clear 过）才补一份安全网
			if (!holdsAnySealItem(player)) {
				setSoulSeal(player, SummyReliquary.SOURCE_OF_SINS.get());
			}
		} else {
			Item sealItem = "virtues".equals(item)
					? SummyReliquary.VIRTUES.get() : SummyReliquary.SOURCE_OF_SINS.get();
			setSoulSeal(player, sealItem);
		}
		restoreSinMasks(player, snapshot.getInt("sins"), snapshot.getInt("redeemed"));
		return true;
	}

	/** 身上（背包 + 副手 + 盔甲 + 光标 + 饰品栏）是否还有任何一件魂印物品（七罪之源 / 美德 / 撒旦圣经） */
	private static boolean holdsAnySealItem(ServerPlayer player) {
		for (Item item : List.of(SummyReliquary.SOURCE_OF_SINS.get(), SummyReliquary.VIRTUES.get(),
				SummyReliquary.SATANIC_BIBLE.get())) {
			// 1.7.10 修订：按物品类型判定（忽略 NBT），并覆盖副手 / 盔甲 / 光标
			if (com.summy.reliquary.util.HeldItems.holds(player, item)) {
				return true;
			}
		}
		return false;
	}

	/** 按两个位掩码把七罪逐项状态写回去（已赎罪优先于已激活） */
	private static void restoreSinMasks(ServerPlayer player, int sins, int redeemed) {
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			int bit = 1 << sin.ordinal();
			SinManager.SinState state;
			if ((redeemed & bit) != 0) {
				state = SinManager.SinState.REDEEMED;
			} else if ((sins & bit) != 0) {
				state = SinManager.SinState.ACTIVATED;
			} else {
				state = SinManager.SinState.UNACTIVATED;
			}
			SinManager.setState(player, sin, state);
		}
	}

	/**
	 * 把魂印栏里的「七罪之源 / 美德 / 撒旦圣经」整格替换成指定物品。
	 *
	 * <p>魂印栏空了（只有创造模式能摘）时退化为直接给背包，保证物品不会凭空消失。
	 */
	private static void setSoulSeal(ServerPlayer player, Item item) {
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			var stacks = handler.getCurios().get(ReliquarySlots.SOUL_SEAL);
			if (stacks != null) {
				for (int index = 0; index < stacks.getSlots(); index++) {
					ItemStack current = stacks.getStacks().getStackInSlot(index);
					if (!current.isEmpty() && (current.is(SummyReliquary.SOURCE_OF_SINS.get())
							|| current.is(SummyReliquary.VIRTUES.get())
							|| current.is(SummyReliquary.SATANIC_BIBLE.get()))) {
						handler.setEquippedCurio(ReliquarySlots.SOUL_SEAL, index, new ItemStack(item));
						sync(player);
						return;
					}
				}
			}
		}
		give(player, new ItemStack(item));
	}

	/**
	 * 撒旦圣经的自动转化（1.6.1）：**佩戴契约** + 魂印栏是七罪之源 + 七罪全部已激活
	 * → 就地换成撒旦圣经。每秒兜底检查一次。
	 *
	 * <p>1.7.1：判据从"持有恶魔标记或曾签约"收紧为"**佩戴契约**" —— 忏悔会把契约与栏位一起没收，
	 * 之后即使七罪又全部激活也不会再自动变出撒旦圣经（只能重新签约）。
	 *
	 * <p>自检直接调它（跳过 {@code tickPlayer} 里的契约自愈），才能单独验证"契约只在背包时不转化"。
	 */
	public static void tickSatanicBible(ServerPlayer player) {
		if (!ReliquaryConfig.enableSatanicBible()) {
			return;
		}
		if (!wearsContract(player)) {
			return;
		}
		if (!CurioHelper.wears(player, SummyReliquary.SOURCE_OF_SINS.get())) {
			return;
		}
		for (com.summy.reliquary.sin.Sin sin : com.summy.reliquary.sin.Sin.values()) {
			if (SinManager.state(player, sin) != SinManager.SinState.ACTIVATED) {
				return;
			}
		}
		setSoulSeal(player, SummyReliquary.SATANIC_BIBLE.get());
		// 1.7.1：同时发放「恶魔王冠」（只发一次；死亡不掉；签约/忏悔都不影响它）
		grantDevilCrown(player);
		SummyReliquary.LOGGER.info("[Summy Reliquary] {} 的七罪之源转化为撒旦圣经", player.getName().getString());
	}

	/** 是否**佩戴**着契约（栏位里那份；与"持有"区分开） */
	public static boolean wearsContract(LivingEntity entity) {
		return entity != null && CurioHelper.wears(entity, SummyReliquary.THE_PACT.get());
	}

	/** 从**背包**里取出一份契约（找到就取走 1 个；找不到返回空栈；不看饰品栏） */
	private static ItemStack takePactFromInventory(ServerPlayer player) {
		var inventory = player.getInventory();
		for (int index = 0; index < inventory.getContainerSize(); index++) {
			ItemStack stack = inventory.getItem(index);
			if (stack.is(SummyReliquary.THE_PACT.get())) {
				ItemStack taken = stack.copyWithCount(1);
				stack.shrink(1);
				return taken;
			}
		}
		return ItemStack.EMPTY;
	}

	/** 恶魔王冠：转化撒旦圣经时发放一次（已有标记或身上已有则跳过） */
	private static void grantDevilCrown(ServerPlayer player) {
		if (!ReliquaryConfig.enableDevilCrown() || PlayerFlags.isDevilCrownGranted(player)) {
			return;
		}
		PlayerFlags.setDevilCrownGranted(player, true);
		ItemStack crown = new ItemStack(SummyReliquary.DEVIL_CROWN.get());
		if (!player.getInventory().add(crown)) {
			player.drop(crown, false);
		}
		player.displayClientMessage(
				Component.translatable("message.summy-reliquary.devil_crown.granted"), true);
	}

	/** 收回契约（忏悔时）：删掉格内与背包里的契约，并把栏位压回 0 格 */
	public static void revoke(ServerPlayer player) {
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			var stacks = handler.getCurios().get(ReliquarySlots.DEMON_PACT);
			if (stacks != null) {
				for (int index = 0; index < stacks.getSlots(); index++) {
					handler.setEquippedCurio(ReliquarySlots.DEMON_PACT, index, ItemStack.EMPTY);
				}
			}
		}
		player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(SummyReliquary.THE_PACT.get()), Integer.MAX_VALUE,
				player.inventoryMenu.getCraftSlots());
		syncSlot(player);
		sync(player);
	}

	/**
	 * 圣物没收（每次签约执行一次，1.7.2 起按"不可回头点为界"的口径）：
	 * 把圣光 / 救恩 / 神圣斗篷 / **伯列恒之星** / 肉体 / 思想 / 灵魂 从背包与栏位里删掉；
	 * 灵台三件套按数量补偿 —— 不足 3 件补等量的「6」，3 件全没收则改补 1 个咒印。
	 *
	 * <p>收尾还会：① 把"过线 / 封锁档"的天使线饰品（终末天启 / 神性 / 圣心）**只摘除不删除**；
	 * ② 清掉伯列恒之星的"已发放"去重标记 —— 否则玩家拿到星之后再签约，星被收走却再也补不回来。
	 */
	private static void confiscateRelics(ServerPlayer player) {
		if (!ReliquaryConfig.enableDemonPact()) {
			return;
		}
		for (var relic : RELICS) {
			removeAll(player, relic.get());
		}
		int altarCount = 0;
		for (var relic : ALTAR_RELICS) {
			int removed = removeAll(player, relic.get());
			if (removed > 0) {
				altarCount += Math.min(1, removed);
			}
		}
		// 1.7.2：伯列恒之星的发放标记与它一起重置（星没了但标记还在 = 永久拿不回来）
		PlayerFlags.setStarGranted(player, false);
		// 过线 / 封锁档：只摘除，不删除
		unequipOnly(player, ANGEL_RELICS_UNEQUIP_ONLY);
		if (altarCount <= 0) {
			return;
		}
		if (altarCount >= 3) {
			give(player, new ItemStack(SummyReliquary.THE_MARK.get()));
		} else {
			for (int index = 0; index < altarCount; index++) {
				give(player, new ItemStack(SummyReliquary.SIX.get()));
			}
		}
	}

	/**
	 * 忏悔时的恶魔线没收（1.7.2）：仪式法袍 / 咒印 / 复仇之魂 / 夜之幽魂。
	 *
	 * <p>撒旦圣经不在这里（魂印物品由 {@link #restoreSinSnapshot} / {@link #confiscateSatanicBible}
	 * 处理）；666 之后才解锁的（硫磺火 / 魔眼 / 深渊领主 / 亚巴顿）走 {@link #unequipHighDemonRelics}；
	 * **恶魔王冠按需求不没收**（留作纪念）。
	 */
	public static void confiscateDemonRelics(ServerPlayer player) {
		if (!ReliquaryConfig.enableDemonPact()) {
			return;
		}
		for (var relic : DEMON_RELICS_BEFORE_666) {
			removeAll(player, relic.get());
		}
	}

	/** 忏悔时只摘除（不删除）"666 之后才解锁"的恶魔线饰品（1.7.2） */
	public static void unequipHighDemonRelics(ServerPlayer player) {
		unequipOnly(player, DEMON_RELICS_AFTER_666);
	}

	/**
	 * 异常解除契约时把**全部**恶魔线饰品摘到背包（不删除，1.7.2）。
	 *
	 * <p>与"真忏悔"不同：那种情形本来就不可达（666 之后无法忏悔），所以这里既不没收、
	 * 也不做任何删除，只保证"换回天使线之后恶魔线饰品不再挂在身上生效"。
	 */
	public static void unequipDemonRelics(ServerPlayer player) {
		unequipOnly(player, DEMON_RELICS_BEFORE_666);
		unequipOnly(player, DEMON_RELICS_AFTER_666);
	}

	/** 异常解除契约时只摘除（不删除）"过线 / 封锁档"的天使线饰品（1.7.2） */
	public static void unequipHighAngelRelics(ServerPlayer player) {
		unequipOnly(player, ANGEL_RELICS_UNEQUIP_ONLY);
	}

	/**
	 * 魂印栏里的撒旦圣经被没收（1.7.2）：优先由快照还原，快照缺失时换成七罪之源。
	 *
	 * <p>1.6.1 之前的老存档没有快照，{@code restoreSinSnapshot} 会返回 false 而不动魂印栏，
	 * 于是圣经会留在原位 —— 这里补一道兜底，保证"忏悔 = 圣经被没收"这条口径始终成立。
	 */
	public static void confiscateSatanicBible(ServerPlayer player) {
		if (player == null || !CurioHelper.wears(player, SummyReliquary.SATANIC_BIBLE.get())) {
			return;
		}
		setSoulSeal(player, SummyReliquary.SOURCE_OF_SINS.get());
	}

	/** 把指定物品从**饰品栏**摘到背包（满了掉脚下）；背包里的同名物品不动，也从不删除 */
	private static int unequipOnly(ServerPlayer player, List<java.util.function.Supplier<Item>> items) {
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return 0;
		}
		int moved = 0;
		for (var entry : new ArrayList<>(handler.getCurios().entrySet())) {
			for (int index = 0; index < entry.getValue().getSlots(); index++) {
				ItemStack stack = entry.getValue().getStacks().getStackInSlot(index);
				if (stack.isEmpty()) {
					continue;
				}
				boolean match = false;
				for (var candidate : items) {
					if (stack.is(candidate.get())) {
						match = true;
						break;
					}
				}
				if (!match) {
					continue;
				}
				ItemStack taken = stack.copy();
				handler.setEquippedCurio(entry.getKey(), index, ItemStack.EMPTY);
				give(player, taken);
				moved++;
			}
		}
		return moved;
	}

	/** 删除玩家身上（背包 + 全部 Curios 栏位）的某物品，返回删除数量 */
	private static int removeAll(ServerPlayer player, Item item) {
		int removed = player.getInventory().clearOrCountMatchingItems(
				stack -> stack.is(item), Integer.MAX_VALUE, player.inventoryMenu.getCraftSlots());
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			for (var entry : new ArrayList<>(handler.getCurios().entrySet())) {
				for (int index = 0; index < entry.getValue().getSlots(); index++) {
					ItemStack stack = entry.getValue().getStacks().getStackInSlot(index);
					if (!stack.isEmpty() && stack.is(item)) {
						removed += stack.getCount();
						handler.setEquippedCurio(entry.getKey(), index, ItemStack.EMPTY);
					}
				}
			}
		}
		return removed;
	}

	/** 补发物品：优先进背包，满了掉脚下 */
	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}

	// ==================== 黑心池 ====================

	/** 黑心池加上点数（受上限约束） */
	public static void addBlackHearts(ServerPlayer player, double points) {
		double max = blackHeartMaxPoints(player);
		double current = PlayerFlags.blackHeartPoints(player);
		PlayerFlags.setBlackHeartPoints(player, Math.min(max, current + points));
		sync(player);
	}

	/**
	 * 扣黑心池（1.6.4：由 {@link DamagePools} 按"这一次实际被吸收的量"调用）。
	 *
	 * <p>黑心是"最后一道防线"：原版护盾与魂心都在它前面结算（顺序见 {@link DamagePools}）。
	 * 池子归零时触发撒旦圣经的 18 格反噬。改成这个入口的原因与魂心相同：Kilt 上事件金额不可靠。
	 */
	public static void consumeBlackHearts(ServerPlayer player, double amount) {
		if (player == null || applyingShatter || amount <= 0.0D || !active(player)) {
			return;
		}
		// 上限随佩戴的祭品变化（法袍 +2 心、撒旦圣经 +1 心），超出部分先夹回
		double pool = Math.min(PlayerFlags.blackHeartPoints(player), blackHeartMaxPoints(player));
		if (pool <= 0.0D) {
			return;
		}
		double absorbed = Math.min(pool, amount);
		double remaining = pool - absorbed;
		PlayerFlags.setBlackHeartPoints(player, remaining);
		if (remaining <= 0.0D) {
			shatterBlackHearts(player);
		}
		sync(player);
	}

	/** 1.6.5：只扣黑心、不触发反噬（由 {@link DamagePools} 在命中前调用，归零判定推迟到对账） */
	public static void deductBlackHearts(ServerPlayer player, double amount) {
		if (player == null || amount <= 0.0D || !active(player)) {
			return;
		}
		double pool = Math.min(PlayerFlags.blackHeartPoints(player), blackHeartMaxPoints(player));
		if (pool <= 0.0D) {
			return;
		}
		PlayerFlags.setBlackHeartPoints(player, Math.max(0.0D, pool - Math.min(pool, amount)));
		sync(player);
	}

	/** 1.6.5：对账时退还"多扣"的黑心（夹在动态上限内） */
	public static void refundBlackHearts(ServerPlayer player, double amount) {
		if (player == null || amount <= 0.0D || !active(player)) {
			return;
		}
		double max = blackHeartMaxPoints(player);
		if (max <= 0.0D) {
			return;
		}
		PlayerFlags.setBlackHeartPoints(player,
				Math.min(max, PlayerFlags.blackHeartPoints(player) + amount));
		sync(player);
	}

	/** 1.6.5：对账时判定"黑心被打空" → 触发撒旦圣经的 18 格反噬 */
	public static void shatterBlackHeartsIfEmpty(ServerPlayer player) {
		if (player == null || applyingShatter || !active(player)) {
			return;
		}
		if (blackHeartMaxPoints(player) <= 0.0D
				|| PlayerFlags.blackHeartPoints(player) > 0.0D) {
			return;
		}
		shatterBlackHearts(player);
		sync(player);
	}

	/**
	 * 黑心被击碎（池子从 >0 归零）时：对 18 格内"与神性光环同口径"的敌人造成伤害。
	 *
	 * <p>伤害类型 {@code summy-reliquary:pact_shatter} 是**变种魔法**（无视护甲、保护附魔与抗性
	 * 照常生效），结算前清无敌帧；数值与半径都在 {@code [demon_pact]} 里。
	 */
	private static void shatterBlackHearts(ServerPlayer player) {
		// 1.6.9：碎一次就从零开始重新计时（否则"无敌/缓冲期内"会被提前补满，与魂心口径不一致）
		BLACK_HEART_REFILL.put(player.getUUID(), ReliquaryConfig.satanicRefillSeconds());
		double damage = shatterDamage(player);
		if (!wearsSatanicBible(player) || damage <= 0.0D) {
			return;
		}
		applyShatterDamage(player, damage);
	}

	/**
	 * 1.7.10：**武器联动入口** —— 强化斩击触发「咒印的黑心爆发伤害」。
	 *
	 * <p>与 {@link #shatterBlackHearts} 的区别（按需求）：门槛只看**佩戴咒印**（不要求撒旦圣经），
	 * 而且**不改动黑心池、也不重置黑心的补满计时** —— 这只是一次性伤害，不是真的"碎心"。
	 * 伤害与半径沿用同一套配置（咒印 40 / 亚巴顿 + 咒印 60 / 半径 18）。
	 *
	 * @return true 表示这次真的发出了爆发伤害
	 */
	public static boolean triggerShatterDamage(ServerPlayer player) {
		if (player == null || applyingShatter || !com.summy.reliquary.effect.SatanicMark.wears(player)) {
			return false;
		}
		double damage = shatterDamage(player);
		if (damage <= 0.0D) {
			return false;
		}
		applyShatterDamage(player, damage);
		return true;
	}

	/** 真正发放一次"黑心碎裂"伤害（半径 {@code satanic_shatter_radius}、无视无敌帧、同款粒子） */
	private static void applyShatterDamage(ServerPlayer player, double damage) {
		ServerLevel level = player.serverLevel();
		double radius = ReliquaryConfig.satanicShatterRadius();
		applyingShatter = true;
		try {
			int hits = 0;
			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(radius),
					entity -> com.summy.reliquary.effect.Godhead.isAuraTarget(player, entity))) {
				if (target.distanceTo(player) > radius) {
					continue;
				}
				// 无视无敌帧（与圣光同款做法）
				target.invulnerableTime = 0;
				target.hurt(pactShatterSource(level, player), (float) damage);
				hits++;
			}
			if (hits > 0) {
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
						player.getX(), player.getY() + 1.0D, player.getZ(),
						40, radius * 0.35D, 1.0D, radius * 0.35D, 0.02D);
			}
		} finally {
			applyingShatter = false;
		}
	}

	/** 碎裂伤害的伤害源（数据包缺失时退回原版魔法伤害，避免抛异常） */
	private static DamageSource pactShatterSource(ServerLevel level, ServerPlayer attacker) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("pact_shatter"));
		var holder = registry.getHolder(key).orElse(null);
		if (holder == null) {
			return level.damageSources().magic();
		}
		return new DamageSource(holder, attacker, attacker);
	}

	/**
	 * 当前生效的黑心碎裂伤害（1.6.10 起三档）：
	 * **亚巴顿 + 咒印 60** > 咒印 40（{@code mark_shatter_damage}）> 默认 24（{@code satanic_pact_shatter_damage}）。
	 */
	public static double shatterDamage(LivingEntity entity) {
		if (com.summy.reliquary.effect.Synergies.withAbaddon(entity, SummyReliquary.THE_MARK.get())
				&& ReliquaryConfig.markShatterDamageAbaddon() > 0.0D) {
			return ReliquaryConfig.markShatterDamageAbaddon();
		}
		if (com.summy.reliquary.effect.SatanicMark.wears(entity)
				&& ReliquaryConfig.markShatterDamage() > 0.0D) {
			return ReliquaryConfig.markShatterDamage();
		}
		return ReliquaryConfig.satanicShatterDamage();
	}

	// ==================== 献祭 ====================

	/**
	 * 佩戴契约时对村民追加的献祭伤害（真实伤害口径、一击必杀）。
	 *
	 * <p>用本模组的 {@code summy-reliquary:sacrifice} 伤害类型（自带死亡文本「被献祭了」）；
	 * {@code applyingSacrifice} 防止递归，{@code isDivine} 防止我们自己的神性伤害再次触发它。
	 */
	public static void extraSacrificeDamage(LivingHurtEvent event) {
		if (applyingSacrifice || !(event.getEntity() instanceof Villager villager)) {
			return;
		}
		if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
			return;
		}
		if (!active(attacker) || PlayerFlags.isSacrificeDone(attacker)) {
			return;
		}
		if (com.summy.reliquary.effect.HolyLightEffect.isDivine(event.getSource())) {
			return;
		}
		double damage = ReliquaryConfig.pactVillagerSacrificeDamage();
		if (damage <= 0.0D) {
			return;
		}
		applyingSacrifice = true;
		try {
			// 目标一定在主世界/下界等某个 ServerLevel 上（村民只会在服务端的维度里）
			ServerLevel level = (ServerLevel) villager.level();
			RevelationBeam.applyTrueDamage(level, attacker, villager,
					(float) damage, "sacrifice", false);
		} finally {
			applyingSacrifice = false;
		}
	}

	/** 生物死亡：献祭完成判定 + 邪恶度积攒 */
	public static void onDeath(LivingEntity victim, Entity killer) {
		if (!(killer instanceof ServerPlayer player) || killer == victim) {
			return;
		}
		gainEvil(player, victim);
		// 1.6.2：「6」的掉落 —— 佩戴契约 + 已解锁咒印时，末影龙 / 凋灵 / 监守者各掉一个（各只一次）
		grantSixDrop(player, victim);
		if (!(victim instanceof Villager) || !active(player) || PlayerFlags.isSacrificeDone(player)) {
			return;
		}
		PlayerFlags.setSacrificeDone(player, true);
		// 1.6.9：进度「新鲜灵魂」——完成村民献祭（重复完成只是重复触发，已完成不会重复授予）
		com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
				com.summy.reliquary.advancement.ReliquaryAdvancements.SACRIFICE_DONE);
		boolean second = PlayerFlags.pactSigns(player) > 1;
		if (!second) {
			// 首份契约：4 行台词 + 黑心
			addBlackHearts(player, blackHeartMaxPoints(player));
		}
		DelayedChat.send(player, sacrificeLines(second));
		sync(player);
	}

	/**
	 * 「6」的掉落（1.6.2）。
	 *
	 * <p>条件：**佩戴契约** 且 **已解锁咒印（邪恶 300）**；对象：末影龙（bit0）/ 凋灵（bit1）/
	 * 监守者（bit2），每类**只掉一次**（记录在 NBT 位图里，忏悔时清零，于是可以再各拿一次）。
	 */
	private static void grantSixDrop(ServerPlayer player, LivingEntity victim) {
		if (!active(player)
				|| !EvilUnlock.THE_MARK.unlocked(PlayerFlags.evilUnlocks(player))) {
			return;
		}
		int bit;
		if (victim instanceof EnderDragon) {
			bit = 1;
		} else if (victim instanceof WitherBoss) {
			bit = 2;
		} else if (victim instanceof Warden) {
			bit = 4;
		} else {
			return;
		}
		int drops = PlayerFlags.sixDrops(player);
		if ((drops & bit) != 0) {
			return;
		}
		PlayerFlags.setSixDrops(player, drops | bit);
		give(player, new ItemStack(SummyReliquary.SIX.get()));
		SummyReliquary.LOGGER.info("[Summy Reliquary] {} 击杀 {} 获得了 1 个「6」",
				player.getName().getString(), victim.getName().getString());
	}

	/** 献祭台词（首轮 4 行 / 二轮起 2 行）；自检也复用 */
	public static List<Component> sacrificeLines(boolean second) {
		List<Component> lines = new ArrayList<>();
		if (second) {
			lines.add(demon("message.summy-reliquary.pact.sacrifice.again.1"));
			lines.add(demon("message.summy-reliquary.pact.sacrifice.again.2"));
		} else {
			for (int index = 1; index <= 4; index++) {
				lines.add(demon("message.summy-reliquary.pact.sacrifice." + index));
			}
		}
		return lines;
	}

	// ==================== 邪恶度 ====================

	/** 击杀生物积攒邪恶度（佩戴契约时） */
	public static void gainEvil(ServerPlayer player, LivingEntity victim) {
		if (!active(player) || victim instanceof Player || !(victim instanceof Mob)) {
			return;
		}
		if (DummySupport.isTargetDummyEntity(victim)) {
			return;
		}
		double gain = gainFor(victim);
		if (gain <= 0.0D) {
			return;
		}
		// 友善档的每日上限
		if (isFriendly(victim)) {
			double friendlyToday = PlayerFlags.evilFriendlyToday(player);
			gain = Math.min(gain, Math.max(0.0D, ReliquaryConfig.evilFriendlyDailyCap() - friendlyToday));
			if (gain <= 0.0D) {
				return;
			}
			PlayerFlags.setEvilFriendlyToday(player, friendlyToday + gain);
		}
		// 当日总量上限
		double today = PlayerFlags.evilToday(player);
		gain = Math.min(gain, Math.max(0.0D, ReliquaryConfig.evilDailyCap() - today));
		if (gain <= 0.0D) {
			return;
		}
		PlayerFlags.setEvilToday(player, today + gain);
		PlayerFlags.setEvil(player, PlayerFlags.evil(player) + gain);
		checkMilestones(player);
		sync(player);
	}

	/** 该生物对应的邪恶度收益 */
	public static double gainFor(LivingEntity victim) {
		if (victim instanceof EnderDragon) {
			return ReliquaryConfig.evilDragonGain();
		}
		if (victim instanceof WitherBoss || victim instanceof Warden) {
			return ReliquaryConfig.evilWardenWitherGain();
		}
		if (isFriendly(victim)) {
			return ReliquaryConfig.evilFriendlyGain();
		}
		if (victim instanceof Enemy) {
			return ReliquaryConfig.evilHostileGain();
		}
		return ReliquaryConfig.evilNeutralGain();
	}

	/** 友善生物：动物 / 村民 / 流浪商人 */
	public static boolean isFriendly(LivingEntity victim) {
		return victim instanceof Animal || victim instanceof Villager || victim instanceof WanderingTrader;
	}

	/** 检查并触发邪恶度里程碑（每位只发一次；解锁永久） */
	public static void checkMilestones(ServerPlayer player) {
		int evil = PlayerFlags.evilDisplay(player);
		int unlocks = PlayerFlags.evilUnlocks(player);
		for (EvilUnlock unlock : EvilUnlock.ordered()) {
			if (evil < unlock.threshold() || unlock.unlocked(unlocks)) {
				continue;
			}
			unlocks |= unlock.bit();
			PlayerFlags.setEvilUnlocks(player, unlocks);
			if (unlock == EvilUnlock.BRIMSTONE) {
				// 666：永久锁定天使线
				PlayerFlags.setHellLocked(player, true);
			}
			DelayedChat.send(player, unlockLines(unlock));
			sync(player);
		}
	}

	/**
	 * 解锁台词（暗红斜体 + 结尾一行灰色的「（你解锁了 xxx）」+ 可选的灰色尾部行）；自检也复用。
	 *
	 * <p>1.7.10：尾部行（只有 700 档有，见 {@link EvilUnlock#tailKey()}）放在「你解锁了 xxx」**之后**、
	 * 与它**同款样式**（灰 `#AAAAAA` 正体）—— 即"先说解锁了什么，再说匕首可以升级"。
	 */
	public static List<Component> unlockLines(EvilUnlock unlock) {
		List<Component> lines = new ArrayList<>();
		for (int index = 1; index <= unlock.lineCount(); index++) {
			lines.add(demon(unlock.lineKey(index)));
		}
		lines.add(Component.translatable("message.summy-reliquary.pact.unlock.suffix",
						Component.translatable("item.summy-reliquary." + unlock.itemId()).getString())
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(GRAY))));
		if (unlock.tailKey() != null) {
			lines.add(Component.translatable(unlock.tailKey())
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(GRAY))));
		}
		return lines;
	}

	/** 恶魔话语：暗红 + 斜体 */
	private static Component demon(String key) {
		return Component.translatable(key)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(CHAT_RED)).withItalic(true));
	}

	/** 服务端每秒：契约自愈 + 每日刷新（衰减 / 上限重置）+ 里程碑兜底 */
	public static void tickPlayer(ServerPlayer player) {
		if (!ReliquaryConfig.enableDemonPact()) {
			return;
		}
		// 契约自愈：持恶魔标记但没有契约 → 补发；没有标记但栏位还在 → 收回
		// 1.7.1：判据＝恶魔标记 + **栏位里没有契约**（背包里躺着一份也算"没有佩戴"，要装进栏位）
		if (PlayerFlags.isDemon(player) && !wearsContract(player)) {
			grant(player, false);
		} else if (!PlayerFlags.isDemon(player)) {
			boolean hasSlot = CuriosApi.getCuriosInventory(player)
					.map(handler -> handler.getCurios().containsKey(ReliquarySlots.DEMON_PACT)
							&& handler.getCurios().get(ReliquarySlots.DEMON_PACT).getSlots() > 0)
					.orElse(false);
			if (hasSlot) {
				revoke(player);
				// 1.7.2：外力解除（例如 OP 直接给天使标记）按"完整忏悔"收尾 ——
				// 幂等：快照已被清则什么都不做；再摘掉不该在身上的对方线饰品（不没收、不删除），
				// 但不消耗痛悔短祷、也不主动发天使标记（标记由触发方决定）。
				restoreSinSnapshot(player);
				confiscateSatanicBible(player);
				unequipDemonRelics(player);
			}
		}
		syncSlot(player);
		// 撒旦圣经：七罪全部重新激活时自动转化（每秒兜底）
		tickSatanicBible(player);
		// 黑心：上限随佩戴的祭品变化；超出上限时夹回
		double maxPool = blackHeartMaxPoints(player);
		if (PlayerFlags.blackHeartPoints(player) > maxPool) {
			PlayerFlags.setBlackHeartPoints(player, maxPool);
			sync(player);
		}
		// 撒旦圣经：黑心每 30 秒自动补满
		tickBlackHeartRefill(player, maxPool);

		// 每日刷新（6:00 = dayTime % 24000 == 0）
		long day = player.level().getDayTime() / 24000L;
		if (!PlayerFlags.hasEvilDay(player)) {
			PlayerFlags.setEvilDay(player, day);
		} else if (PlayerFlags.evilDay(player) != day) {
			PlayerFlags.setEvilDay(player, day);
			PlayerFlags.setEvilToday(player, 0.0D);
			PlayerFlags.setEvilFriendlyToday(player, 0.0D);
			// 仪式法袍：佩戴时把每日衰减换成配置里的更低值（默认 5 → 3）
			// 咒印（1.6.2）：佩戴时邪恶度不再自然衰减（直接归零）
			// 1.6.8：**邪恶为满时锁死** —— 满值后既不再增长也不再衰减（法袍 / 咒印的修正一律不适用）
			double decay = PlayerFlags.evil(player) >= ReliquaryConfig.evilMax()
					? 0.0D
					: (com.summy.reliquary.effect.SatanicMark.wears(player)
							&& ReliquaryConfig.markStopsEvilDecay()
									? 0.0D
									: (wearsRobe(player) ? ReliquaryConfig.robeDailyDecay()
											: ReliquaryConfig.evilDailyDecay()));
			if (decay > 0.0D) {
				PlayerFlags.setEvil(player, PlayerFlags.evil(player) - decay);
			}
			sync(player);
		}
		checkMilestones(player);
	}

	/** 撒旦圣经：每 {@code satanic_pact_refill_seconds} 秒把黑心补满 */
	private static void tickBlackHeartRefill(ServerPlayer player, double maxPool) {
		UUID id = player.getUUID();
		if (!wearsSatanicBible(player)) {
			BLACK_HEART_REFILL.remove(id);
			return;
		}
		// 1.6.9：刚戴上的那一次直接补满并重置计时（与魂心"刚装上就补满"对齐）
		if (!BLACK_HEART_REFILL.containsKey(id)) {
			BLACK_HEART_REFILL.put(id, ReliquaryConfig.satanicRefillSeconds());
			if (PlayerFlags.blackHeartPoints(player) < maxPool) {
				PlayerFlags.setBlackHeartPoints(player, maxPool);
				sync(player);
			}
			return;
		}
		int remaining = BLACK_HEART_REFILL.getOrDefault(id,
				ReliquaryConfig.satanicRefillSeconds()) - 1;
		if (remaining > 0) {
			BLACK_HEART_REFILL.put(id, remaining);
			return;
		}
		BLACK_HEART_REFILL.put(id, ReliquaryConfig.satanicRefillSeconds());
		if (PlayerFlags.blackHeartPoints(player) < maxPool) {
			PlayerFlags.setBlackHeartPoints(player, maxPool);
			sync(player);
		}
	}

	/**
	 * 登录 / 换维度 / 复活（1.6.9）：与魂心的 {@code SoulShield.onJoin} 对齐。
	 *
	 * <p>口径：戴着撒旦圣经且池子**已被打空**（≤0）时补满；池子还有存量就原样保留。
	 * 摘下圣经**不清空**池子（黑心的来源是契约 / 法袍 / 圣经 / 咒印多份）。
	 */
	public static void onJoin(ServerPlayer player) {
		if (player == null || !wearsSatanicBible(player)) {
			return;
		}
		double maxPool = blackHeartMaxPoints(player);
		BLACK_HEART_REFILL.put(player.getUUID(), ReliquaryConfig.satanicRefillSeconds());
		if (maxPool > 0.0D && PlayerFlags.blackHeartPoints(player) <= 0.0D) {
			PlayerFlags.setBlackHeartPoints(player, maxPool);
			sync(player);
		}
	}

	/** 把契约相关状态推给客户端 */
	public static void sync(ServerPlayer player) {
		RevelationTracker.sync(player);
	}

	/**
	 * 1.7.3：清掉该玩家在契约体系里的**内存态**（目前只有"黑心补满计时"）。
	 *
	 * <p>创世纪重置时会调用；不清的话重置后戴上撒旦圣经的补满计时可能还停在旧值上。
	 */
	public static void forget(ServerPlayer player) {
		if (player != null) {
			BLACK_HEART_REFILL.remove(player.getUUID());
		}
	}

	/** 自检用：黑心补满计时还剩多少秒（没在计时返回 -1） */
	public static int blackHeartRefillRemainingForTest(ServerPlayer player) {
		return BLACK_HEART_REFILL.getOrDefault(player.getUUID(), -1);
	}

	/** 服务器停止：清掉递归标记 */
	public static void clear() {
		applyingSacrifice = false;
		applyingShatter = false;
		BLACK_HEART_REFILL.clear();
	}

	/** 自检用：把「黑心补满」的计时器压到下一秒（便于在几秒内验证周期补满） */
	public static void primeBlackHeartRefillForTest(ServerPlayer player) {
		BLACK_HEART_REFILL.put(player.getUUID(), 1);
	}

	/** 自检用：直接改服务端邪恶度（会触发里程碑检查） */
	public static void setEvilForTest(ServerPlayer player, double value) {
		PlayerFlags.setEvil(player, value);
		checkMilestones(player);
		sync(player);
	}

	/** 自检用：当前契约栏位的格数（-1 = 该玩家没有这个栏位） */
	public static int slotCount(ServerPlayer player) {
		return CuriosApi.getCuriosInventory(player)
				.map(handler -> handler.getCurios().containsKey(ReliquarySlots.DEMON_PACT)
						? handler.getCurios().get(ReliquarySlots.DEMON_PACT).getSlots() : -1)
				.orElse(-1);
	}

	/** 黑心 HUD 需要的心数（4 点 = 2 满心；3 点 = 1 满心 + 1 半心） */
	public static int blackHeartFullCount(int points) {
		return Math.max(0, points) / 2;
	}

	/** 黑心 HUD 是否需要画半心 */
	public static boolean blackHeartHasHalf(int points) {
		return Math.max(0, points) % 2 == 1;
	}

	/** 粒子 / 音效：契约的"恶魔"气息（签约与献祭时用） */
	public static void spawnDemonBurst(ServerLevel level, ServerPlayer player) {
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.0D, player.getZ(),
				24, 0.5D, 0.7D, 0.5D, 0.02D);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 1.0F, 0.6F);
	}

	/** 服务器 tick：给 {@link #sync} 用的空实现占位（保持与其它模块一致的调用形状） */
	public static void tickServer(MinecraftServer server) {
		// 目前所有逻辑都在玩家 tick 里；保留入口以便后续扩展
	}
}
