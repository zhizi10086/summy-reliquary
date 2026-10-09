package com.summy.reliquary.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 配置：{@code config/summy_reliquary-common.toml}（文件名由 Forge 的 modId 决定）。
 *
 * <p>按需求只提供配置文件、不做图形界面；改完文件后 Forge 会热加载，效果立即生效。
 */
public final class ReliquaryConfig {
	/** 吸附半径的取值范围与默认值 */
	public static final int MIN_RADIUS = 1;
	public static final int MAX_RADIUS = 32;
	public static final int DEFAULT_RADIUS = 10;

	public static final ForgeConfigSpec SPEC;

	private static final ForgeConfigSpec.IntValue RADIUS;

	// ===== 首次进入世界时的发放 =====
	private static final ForgeConfigSpec.BooleanValue GRANT_START_ITEMS;
	private static final ForgeConfigSpec.BooleanValue AUTO_EQUIP_SOURCE_OF_SINS;

	// ===== 灵台（肉体 / 思想 / 灵魂） =====
	private static final ForgeConfigSpec.IntValue GLOW_RADIUS;
	private static final ForgeConfigSpec.IntValue SOUL_HEARTS;
	/** 每点魂心换算成多少点黄血 */
	private static final ForgeConfigSpec.DoubleValue ABSORPTION_PER_SOUL_HEART;
	private static final ForgeConfigSpec.IntValue SOUL_REFRESH_SECONDS;
	/** 魂心 HUD：是否用自绘的蓝色魂心覆盖原版黄心的对应位置 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_SOUL_HEART_HUD;
	/** 魂心 HUD（1.8.5）：布局模式 —— auto / vanilla / single_row */
	private static final ForgeConfigSpec.ConfigValue<String> SOUL_HEART_HUD_LAYOUT;
	/** 魂心 HUD（1.8.5）：在布局结果之上再整体下移多少像素（正数向下） */
	private static final ForgeConfigSpec.IntValue SOUL_HEART_HUD_OFFSET_Y;
	/** 受伤无敌帧（1.6.3）：帧伤/持续伤害的有效窗口（tick） */
	private static final ForgeConfigSpec.IntValue INVULNERABILITY_TICKS_FRAME_DAMAGE;
	/** 受伤无敌帧（1.6.3）：额外按"帧伤"处理的伤害类型 id 列表 */
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> FRAME_DAMAGE_IDS;
	/** 受伤无敌帧（1.6.3）：是否每 5 秒汇总一条节流日志 */
	private static final ForgeConfigSpec.BooleanValue LOG_IFRAME_THROTTLE;
	/** 伤害池（1.6.4）：每次魂心 / 黑心接管伤害时是否打一条 INFO */
	private static final ForgeConfigSpec.BooleanValue LOG_DAMAGE_POOLS;

	// ===== 硫磺火（1.6.4）：长按 V 发射的「恶魔之焰」 =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_BRIMSTONE;
	private static final ForgeConfigSpec.DoubleValue BRIMSTONE_CHARGE_SECONDS;
	private static final ForgeConfigSpec.IntValue BRIMSTONE_BEAM_LENGTH;
	private static final ForgeConfigSpec.IntValue BRIMSTONE_BEAM_RADIUS;
	private static final ForgeConfigSpec.DoubleValue BRIMSTONE_DURATION_SECONDS;
	private static final ForgeConfigSpec.IntValue BRIMSTONE_DAMAGE_PER_TICK;
	private static final ForgeConfigSpec.DoubleValue BRIMSTONE_DAMAGE_INTERVAL_SECONDS;
	private static final ForgeConfigSpec.IntValue BRIMSTONE_COOLDOWN_SECONDS;
	/** 魂心池（1.6.2）：完全破碎时对周围敌人的击退强度 */
	private static final ForgeConfigSpec.DoubleValue SOUL_SHATTER_KNOCKBACK;
	/** 魂心池（1.6.2）：完全破碎时的作用半径（格） */
	private static final ForgeConfigSpec.IntValue SOUL_SHATTER_RADIUS;
	/** 魂心池（1.6.2）：完全破碎后给予玩家的无敌秒数 */
	private static final ForgeConfigSpec.DoubleValue SOUL_SHATTER_INVULNERABLE_SECONDS;
	/** 三件套齐时的免死几率（百分比） */
	private static final ForgeConfigSpec.IntValue DEATH_IMMUNITY_PERCENT;
	private static final ForgeConfigSpec.IntValue BODY_HEALTH;
	private static final ForgeConfigSpec.IntValue BODY_SET_DAMAGE_REDUCTION_PERCENT;
	private static final ForgeConfigSpec.IntValue MIND_BONUS_PERCENT;

	// ===== 七罪效果（1.4.0） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_SIN_EFFECTS;
	private static final ForgeConfigSpec.IntValue PRIDE_KILL_REQUIRED;
	private static final ForgeConfigSpec.DoubleValue PRIDE_DAMAGE_PER_PERCENT;
	private static final ForgeConfigSpec.IntValue PRIDE_INCOMING_DAMAGE_PERCENT;
	private static final ForgeConfigSpec.IntValue ENVY_OBSERVE_RADIUS;
	private static final ForgeConfigSpec.IntValue ENVY_BONUS_PERCENT;
	private static final ForgeConfigSpec.IntValue ENVY_HOSTILE_RADIUS;
	private static final ForgeConfigSpec.IntValue ENVY_HOSTILE_REFRESH_TICKS;
	private static final ForgeConfigSpec.IntValue WRATH_KILL_REQUIRED;
	private static final ForgeConfigSpec.DoubleValue WRATH_RANDOM_MIN;
	private static final ForgeConfigSpec.DoubleValue WRATH_RANDOM_MAX;
	private static final ForgeConfigSpec.DoubleValue WRATH_RANDOM_MAX_REDEEMED;
	private static final ForgeConfigSpec.IntValue WRATH_SELF_HIT_PERCENT;
	private static final ForgeConfigSpec.DoubleValue WRATH_SELF_HIT_MULTIPLIER;
	private static final ForgeConfigSpec.IntValue SLOTH_SLEEP_REQUIRED;
	private static final ForgeConfigSpec.IntValue SLOTH_SLEEP_BEFORE_HOUR;
	private static final ForgeConfigSpec.IntValue SLOTH_RESISTANCE_AMPLIFIER;
	private static final ForgeConfigSpec.IntValue SLOTH_RESISTANCE_AMPLIFIER_REDEEMED;
	private static final ForgeConfigSpec.IntValue SLOTH_SLOWDOWN_PERCENT;
	private static final ForgeConfigSpec.IntValue GREED_DIAMOND_THRESHOLD;
	/** 「纯洁无瑕」：要求的最低佩戴率（百分比；99 = 允许 1% 未佩戴） */
	private static final ForgeConfigSpec.IntValue FLAWLESS_MIN_WEARING_PERCENT;
	/** 「纯洁无瑕」：开局宽限（秒；这段时间内未佩戴不计入判据） */
	private static final ForgeConfigSpec.IntValue FLAWLESS_UNWORN_GRACE_SECONDS;
	private static final ForgeConfigSpec.IntValue GREED_DAMAGE_PER_DIAMOND_PERCENT;
	private static final ForgeConfigSpec.IntValue GREED_DAMAGE_CAP_PERCENT;
	private static final ForgeConfigSpec.DoubleValue GREED_LOW_DIAMOND_DAMAGE_FACTOR;
	private static final ForgeConfigSpec.IntValue GREED_DEATH_DIAMOND_MIN;
	private static final ForgeConfigSpec.IntValue GREED_DEATH_DIAMOND_MAX;
	private static final ForgeConfigSpec.IntValue GLUTTONY_MEAL_REQUIRED;
	private static final ForgeConfigSpec.DoubleValue GLUTTONY_SATURATION_THRESHOLD;
	private static final ForgeConfigSpec.DoubleValue GLUTTONY_KILL_HEAL;
	private static final ForgeConfigSpec.IntValue GLUTTONY_KILL_FOOD;
	private static final ForgeConfigSpec.IntValue GLUTTONY_FOOD_CAP;
	private static final ForgeConfigSpec.IntValue GLUTTONY_DRAIN_SECONDS;
	private static final ForgeConfigSpec.IntValue GLUTTONY_WEAK_FOOD_THRESHOLD;
	private static final ForgeConfigSpec.IntValue LUST_BREED_REQUIRED;
	private static final ForgeConfigSpec.IntValue LUST_STRIP_ARMOR_PERCENT;
	private static final ForgeConfigSpec.IntValue LUST_ARMOR_REDUCTION_PERCENT;
	private static final ForgeConfigSpec.IntValue LUST_SELF_STRIP_PERCENT;

	// ===== 七德加成（1.4.4；生效条件：佩戴美德 + 七罪全部已赎清） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_VIRTUE_EFFECTS;
	private static final ForgeConfigSpec.IntValue HUMILITY_DAMAGE_BONUS_PERCENT;
	private static final ForgeConfigSpec.IntValue CHARITY_SPEED_PERCENT_PER_DROP;
	private static final ForgeConfigSpec.IntValue CHARITY_SPEED_CAP_PERCENT;
	private static final ForgeConfigSpec.IntValue CHARITY_DURATION_SECONDS;
	private static final ForgeConfigSpec.IntValue CHASTITY_DURABILITY_PER_SECOND;
	private static final ForgeConfigSpec.DoubleValue KINDNESS_HEAL_PER_SECOND;
	private static final ForgeConfigSpec.IntValue KINDNESS_RADIUS;
	private static final ForgeConfigSpec.IntValue PATIENCE_BONUS_PERCENT_PER_HIT;
	private static final ForgeConfigSpec.IntValue PATIENCE_CAP_PERCENT;
	private static final ForgeConfigSpec.IntValue PATIENCE_RESET_SECONDS;
	private static final ForgeConfigSpec.IntValue DILIGENCE_SPEED_PERCENT;

	// ===== 加护（救恩的领域，1.5.0） =====
	private static final ForgeConfigSpec.DoubleValue SALVATION_RADIUS;
	private static final ForgeConfigSpec.DoubleValue SALVATION_RADIUS_EXTENDED;
	private static final ForgeConfigSpec.DoubleValue SALVATION_LOCK_SECONDS;
	private static final ForgeConfigSpec.IntValue SALVATION_DAMAGE;
	private static final ForgeConfigSpec.DoubleValue SALVATION_COOLDOWN_SECONDS;
	/** 圣光：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_HOLY_LIGHT;
	/** 圣光：触发几率（百分比） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_CHANCE_PERCENT;
	/** 圣光：与神性同戴时的触发几率（百分比，1.6.10 联动） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_CHANCE_PERCENT_GODHEAD;
	/** 圣光：以命中伤害为基准的百分比 */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_DAMAGE_PERCENT;
	/** 圣光：手持圣光短矛时的独立触发几率（百分比，1.7.9） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_CHANCE_PERCENT_SPEAR;
	/** 炽天使之枪投掷命中：落点圣光爆发的真伤点数（1.7.9） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_BURST_DAMAGE;
	/** 炽天使之枪投掷命中：落点圣光爆发的半径（格，1.7.9） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_BURST_RADIUS;
	/** 炽天使之枪 + 神性：落点爆发的半径加成（格，1.7.10 联动） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_BURST_RADIUS_GODHEAD;
	/** 炽天使之枪 + 神性：落点爆发的伤害加成（点，1.7.10 联动） */
	private static final ForgeConfigSpec.IntValue HOLY_LIGHT_BURST_DAMAGE_GODHEAD;
	/** 神圣斗篷：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_HOLY_MANTLE;
	/** 神圣斗篷：受击后的无敌秒数 */
	private static final ForgeConfigSpec.DoubleValue HOLY_MANTLE_INVULNERABLE_SECONDS;
	/** 神圣斗篷：与神性同戴时的无敌秒数（1.6.10 联动） */
	private static final ForgeConfigSpec.DoubleValue HOLY_MANTLE_INVULNERABLE_SECONDS_GODHEAD;
	/** 圣心：最大生命 */
	private static final ForgeConfigSpec.DoubleValue SACRED_HEART_MAX_HEALTH;
	/** 圣心：护甲 */
	private static final ForgeConfigSpec.DoubleValue SACRED_HEART_ARMOR;
	/** 圣心：盔甲韧性 */
	private static final ForgeConfigSpec.DoubleValue SACRED_HEART_TOUGHNESS;
	/** 圣心：攻击速度（加法） */
	private static final ForgeConfigSpec.DoubleValue SACRED_HEART_ATTACK_SPEED;
	/** 圣心：移动速度（百分比） */
	private static final ForgeConfigSpec.IntValue SACRED_HEART_MOVEMENT_PERCENT;
	/** 圣心：挖掘速度（百分比） */
	private static final ForgeConfigSpec.IntValue SACRED_HEART_BREAK_SPEED_PERCENT;
	/** 圣心：全伤害最终倍率（百分比） */
	private static final ForgeConfigSpec.IntValue SACRED_HEART_DAMAGE_PERCENT;
	/** 圣心：箭矢追踪半径 */
	private static final ForgeConfigSpec.DoubleValue SACRED_HEART_ARROW_RADIUS;
	/** 圣心：与神性同戴时的箭矢追踪半径（1.6.10 联动） */
	private static final ForgeConfigSpec.DoubleValue SACRED_HEART_ARROW_RADIUS_GODHEAD;
	/** 圣心：投掷初速加成（百分比，1.7.10 联动） */
	private static final ForgeConfigSpec.IntValue SACRED_HEART_THROW_SPEED_PERCENT;
	/** 圣心：箭矢追踪开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_ARROW_HOMING;
	/** 神性：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_GODHEAD;
	/** 神性：光柱蓄力秒数 */
	private static final ForgeConfigSpec.DoubleValue GODHEAD_BEAM_CHARGE_SECONDS;
	/** 神性：光柱冷却秒数 */
	private static final ForgeConfigSpec.DoubleValue GODHEAD_BEAM_COOLDOWN_SECONDS;
	/** 神性：光柱半径 */
	private static final ForgeConfigSpec.IntValue GODHEAD_BEAM_RADIUS;
	/** 神性：光柱射程 */
	private static final ForgeConfigSpec.IntValue GODHEAD_BEAM_LENGTH;
	/** 神性：救恩领域半径 */
	private static final ForgeConfigSpec.DoubleValue GODHEAD_SALVATION_RADIUS;
	/** 神性：光环半径 */
	private static final ForgeConfigSpec.DoubleValue GODHEAD_AURA_RADIUS;
	/** 神性：光环伤害 */
	private static final ForgeConfigSpec.IntValue GODHEAD_AURA_DAMAGE;
	/** 神性：全伤害最终倍率（百分比） */
	private static final ForgeConfigSpec.IntValue GODHEAD_DAMAGE_PERCENT;
	/** 是否让救恩领域 / 神性光环把训练人偶也当作目标（dummmmmmy:target_dummy） */
	private static final ForgeConfigSpec.BooleanValue AFFECT_TARGET_DUMMIES;
	/** 启示之光 buff：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_REVELATION_LIGHT_BUFF;
	/** 启示之光 buff：时长（秒） */
	private static final ForgeConfigSpec.DoubleValue REVELATION_LIGHT_BUFF_SECONDS;
	/** 心之碎片：掉落总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_HEART_SHARD_DROP;
	/** 心之碎片：普通生物掉落几率（百分比） */
	private static final ForgeConfigSpec.DoubleValue HEART_SHARD_DROP_CHANCE;
	/** 心之碎片：凋灵 / 监守者掉落几率（百分比） */
	private static final ForgeConfigSpec.DoubleValue HEART_SHARD_BOSS_CHANCE;
	/** 心之碎片：末影龙掉落几率（百分比） */
	private static final ForgeConfigSpec.DoubleValue HEART_SHARD_DRAGON_CHANCE;
	/** 心之碎片：是否打印"带启示之光死亡"的判定过程日志 */
	private static final ForgeConfigSpec.BooleanValue LOG_DROP_CHECKS;
	/** 五芒星：近战伤害加成 */
	private static final ForgeConfigSpec.DoubleValue PENTAGRAM_ATTACK_DAMAGE;
	/** 五芒星：累计持有多少秒后触发那句聊天消息 */
	private static final ForgeConfigSpec.IntValue PENTAGRAM_MESSAGE_SECONDS;
	/** 恶魔交易：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_DEMON_DEAL;
	/** 恶魔交易：在灵魂沙峡谷里需要停留多少秒 */
	private static final ForgeConfigSpec.IntValue DEMON_BIOME_DWELL_SECONDS;
	/** 恶魔交易：签约蓄力秒数 */
	private static final ForgeConfigSpec.DoubleValue DEMON_CHARGE_SECONDS;
	/** 恶魔交易：逐行台词的间隔（tick） */
	private static final ForgeConfigSpec.IntValue DEMON_LINE_DELAY_TICKS;
	/** 恶魔交易：错误位置提示的最短间隔（秒） */
	private static final ForgeConfigSpec.IntValue DEMON_ERROR_COOLDOWN_SECONDS;
	/** 恶魔交易：蓄力时视野（FOV）最多收缩的比例 */
	private static final ForgeConfigSpec.DoubleValue DEMON_CHARGE_FOV_SCALE;
	/** 恶魔契约：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_DEMON_PACT;
	/** 恶魔契约：献祭时对村民追加的真实伤害 */
	private static final ForgeConfigSpec.DoubleValue PACT_VILLAGER_DAMAGE;
	/** 恶魔契约：黑心心数 */
	private static final ForgeConfigSpec.DoubleValue PACT_BLACK_HEARTS;
	/** 恶魔契约：每颗黑心换算的吸收点 */
	private static final ForgeConfigSpec.DoubleValue PACT_ABSORPTION_PER_HEART;
	/** 恶魔契约：伤害加成（%） */
	private static final ForgeConfigSpec.DoubleValue PACT_DAMAGE_PERCENT;
	/** 恶魔契约：攻速加成（%） */
	private static final ForgeConfigSpec.DoubleValue PACT_ATTACK_SPEED_PERCENT;
	/** 恶魔契约：每条诅咒的生命加成（%） */
	private static final ForgeConfigSpec.DoubleValue PACT_HEALTH_PER_CURSE_PERCENT;
	/** 恶魔契约：每条诅咒的速度加成（%） */
	private static final ForgeConfigSpec.DoubleValue PACT_SPEED_PER_CURSE_PERCENT;
	/** 恶魔契约：生命吸取所需的诅咒条数阈值 */
	private static final ForgeConfigSpec.IntValue PACT_LIFESTEAL_CURSE_THRESHOLD;
	/** 恶魔契约：生命吸取比例（%） */
	private static final ForgeConfigSpec.DoubleValue PACT_LIFESTEAL_PERCENT;
	/** 仪式法袍：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_ROBE;
	/** 仪式法袍：黑心上限加成（心数） */
	private static final ForgeConfigSpec.DoubleValue ROBE_BLACK_HEARTS;
	/** 仪式法袍：攻击力加成（加法） */
	private static final ForgeConfigSpec.DoubleValue ROBE_ATTACK_DAMAGE;
	/** 仪式法袍：佩戴时的邪恶每日衰减 */
	private static final ForgeConfigSpec.DoubleValue ROBE_DAILY_DECAY;
	/** 撒旦圣经：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_SATANIC_BIBLE;
	/** 撒旦圣经：黑心上限加成（心数） */
	private static final ForgeConfigSpec.DoubleValue SATANIC_BIBLE_BLACK_HEARTS;
	/** 撒旦圣经：黑心补满的间隔（秒） */
	private static final ForgeConfigSpec.IntValue SATANIC_REFILL_SECONDS;
	/** 撒旦圣经：黑心碎裂的作用半径（格） */
	private static final ForgeConfigSpec.IntValue SATANIC_SHATTER_RADIUS;
	/** 撒旦圣经：黑心碎裂的伤害 */
	private static final ForgeConfigSpec.DoubleValue SATANIC_SHATTER_DAMAGE;
	/** 咒印（1.6.2）：佩戴时覆盖的黑心碎裂伤害 */
	private static final ForgeConfigSpec.DoubleValue MARK_SHATTER_DAMAGE;
	/** 咒印：与亚巴顿同戴时覆盖的黑心碎裂伤害（1.6.10 联动） */
	private static final ForgeConfigSpec.DoubleValue MARK_SHATTER_DAMAGE_ABADDON;
	/** 咒印（1.6.2）：是否把邪恶每日衰减压到 0 */
	private static final ForgeConfigSpec.BooleanValue MARK_STOPS_EVIL_DECAY;
	/** 咒印（1.6.2）：黑心池上限加成（心数；由灵魂的魂心转化而来） */
	private static final ForgeConfigSpec.DoubleValue MARK_BLACK_HEARTS;
	/** 复仇之魂（1.6.2）：每秒狱火伤害 */
	private static final ForgeConfigSpec.DoubleValue VENGEFUL_HELLFIRE_DAMAGE;
	/** 复仇之魂（1.6.2）：狱火作用半径（格） */
	private static final ForgeConfigSpec.IntValue VENGEFUL_HELLFIRE_RADIUS;
	private static final ForgeConfigSpec.IntValue VENGEFUL_HELLFIRE_RADIUS_BRIMSTONE;
	/** 夜之幽魂（1.6.3）：移动速度加成（百分比） */
	private static final ForgeConfigSpec.DoubleValue NIGHT_WRAITH_MOVEMENT_PERCENT;

	// ===== 玄秘魔眼 / 恐惧（1.6.5） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_OCCULT_EYE;
	private static final ForgeConfigSpec.IntValue FEAR_RADIUS;
	private static final ForgeConfigSpec.IntValue FEAR_SECONDS;
	private static final ForgeConfigSpec.BooleanValue FEAR_AFFECTS_PLAYERS;
	private static final ForgeConfigSpec.BooleanValue FEAR_GLOW;
	private static final ForgeConfigSpec.BooleanValue FEAR_GLOW_AFFECTS_PLAYERS;
	private static final ForgeConfigSpec.DoubleValue FEAR_DAMAGE_MULTIPLIER;
	/** 恐惧是否附带「黑暗」（1.6.6 起的新键） */
	private static final ForgeConfigSpec.BooleanValue FEAR_DARKNESS;
	/** 1.6.5 的旧键（当时误用了「失明」）；只为兼容旧配置里显式的关闭而保留 */
	private static final ForgeConfigSpec.BooleanValue FEAR_BLINDNESS_LEGACY;
	private static final ForgeConfigSpec.IntValue FEAR_MINING_FATIGUE_AMPLIFIER;
	private static final ForgeConfigSpec.IntValue FEAR_SLOWNESS_AMPLIFIER;
	private static final ForgeConfigSpec.IntValue FEAR_WEAKNESS_AMPLIFIER;
	/** 恐惧：与亚巴顿同戴时是否额外把目标移速归零（1.6.10 联动） */
	private static final ForgeConfigSpec.BooleanValue FEAR_FREEZE_WITH_ABADDON;
	// ===== 深渊领主 / 狱火（1.6.7） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_ABYSS_LORD;
	private static final ForgeConfigSpec.IntValue HELLFIRE_SECONDS;
	private static final ForgeConfigSpec.IntValue HELLFIRE_MAX_LEVEL;
	private static final ForgeConfigSpec.DoubleValue HELLFIRE_ARMOR_PERCENT_PER_LEVEL;
	private static final ForgeConfigSpec.DoubleValue HELLFIRE_DAMAGE_PER_LEVEL_PER_SECOND;
	private static final ForgeConfigSpec.BooleanValue ENABLE_DEMON_FIRE_IMMUNITY;
	/** 狱火：与亚巴顿同戴时，狱火满级是否把目标「抗性提升」等级减半并持续覆盖（1.6.10 联动） */
	private static final ForgeConfigSpec.BooleanValue HELLFIRE_HALVES_RESISTANCE_WITH_ABADDON;
	// ===== 亚巴顿（1.6.8） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_ABADDON;
	private static final ForgeConfigSpec.DoubleValue ABADDON_BEAM_CHARGE_SECONDS;
	private static final ForgeConfigSpec.IntValue ABADDON_BEAM_DAMAGE_PER_TICK;
	private static final ForgeConfigSpec.IntValue ABADDON_BEAM_LENGTH;
	private static final ForgeConfigSpec.IntValue ABADDON_BEAM_RADIUS;
	private static final ForgeConfigSpec.IntValue ABADDON_REVIVE_COOLDOWN_SECONDS;
	private static final ForgeConfigSpec.IntValue ABADDON_REVIVE_INVULNERABLE_SECONDS;
	private static final ForgeConfigSpec.DoubleValue ABADDON_AURA_RADIUS;
	private static final ForgeConfigSpec.IntValue ABADDON_AURA_DAMAGE_PER_TICK;
	private static final ForgeConfigSpec.IntValue VENGEFUL_HELLFIRE_RADIUS_ABADDON;
	/** 邪恶度：上限 */
	private static final ForgeConfigSpec.IntValue EVIL_MAX;
	/** 邪恶度：每 1 点提供多少 % 的攻击或攻速（奇偶交替） */
	private static final ForgeConfigSpec.DoubleValue EVIL_PER_POINT_PERCENT;
	/** 邪恶度：友善生物 */
	private static final ForgeConfigSpec.DoubleValue EVIL_FRIENDLY;
	/** 邪恶度：中立生物 */
	private static final ForgeConfigSpec.DoubleValue EVIL_NEUTRAL;
	/** 邪恶度：敌对生物 */
	private static final ForgeConfigSpec.DoubleValue EVIL_HOSTILE;
	/** 邪恶度：监守者 / 凋灵 */
	private static final ForgeConfigSpec.DoubleValue EVIL_WARDEN_WITHER;
	/** 邪恶度：末影龙 */
	private static final ForgeConfigSpec.DoubleValue EVIL_DRAGON;
	/** 邪恶度：每游戏日增益总量上限 */
	private static final ForgeConfigSpec.DoubleValue EVIL_DAILY_CAP;
	/** 邪恶度：每游戏日自然衰减 */
	private static final ForgeConfigSpec.DoubleValue EVIL_DAILY_DECAY;
	/** 邪恶度：友善档每日上限 */
	private static final ForgeConfigSpec.DoubleValue EVIL_FRIENDLY_DAILY_CAP;

	// ===== 战斗手感 =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_INVULNERABILITY_CHANGE;
	private static final ForgeConfigSpec.IntValue INVULNERABILITY_TICKS;
	private static final ForgeConfigSpec.DoubleValue MAX_ATTACK_SPEED;

	// ===== 光环 =====
	private static final ForgeConfigSpec.DoubleValue HALO_MAX_HEALTH;
	private static final ForgeConfigSpec.DoubleValue HALO_ATTACK_DAMAGE;
	private static final ForgeConfigSpec.DoubleValue HALO_ATTACK_SPEED;
	private static final ForgeConfigSpec.DoubleValue HALO_ARMOR;
	private static final ForgeConfigSpec.DoubleValue HALO_ARMOR_TOUGHNESS;
	private static final ForgeConfigSpec.IntValue HALO_MOVEMENT_PERCENT;
	private static final ForgeConfigSpec.IntValue HALO_BREAK_SPEED_PERCENT;
	private static final ForgeConfigSpec.DoubleValue HALO_SIN_MULTIPLIER;
	private static final ForgeConfigSpec.DoubleValue HALO_VIRTUE_MULTIPLIER;
	/** 光环：生命值低于该百分比时获得「生命回复」（1.7.1，需已获取启示） */
	private static final ForgeConfigSpec.IntValue HALO_REGEN_THRESHOLD_PERCENT;
	/** 光环：生命回复的等级（amplifier；0 = 生命回复 I） */
	private static final ForgeConfigSpec.IntValue HALO_REGEN_AMPLIFIER;

	// ===== 恶魔王冠（1.7.1） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_DEVIL_CROWN;
	private static final ForgeConfigSpec.IntValue DEVIL_CROWN_MOVEMENT_PERCENT;
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_ATTACK_SPEED;
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_MELEE_DAMAGE;
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_MAX_HEALTH;
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_ARMOR;
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_ARMOR_TOUGHNESS;
	/** 恶魔王冠：每损失 10% 生命值提升的造成伤害（百分比） */
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_DAMAGE_PER_DECILE_PERCENT;
	/** 恶魔王冠：上述加成的上限（百分比） */
	private static final ForgeConfigSpec.DoubleValue DEVIL_CROWN_DAMAGE_CAP_PERCENT;

	// ===== 遁入暗影（1.7.5：献祭匕首 / 暗仪刺刀） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_SHADOW_DASH;
	/** 献祭匕首：技能判定时长（tick） */
	private static final ForgeConfigSpec.IntValue SHADOW_SACRIFICIAL_DURATION_TICKS;
	/** 暗仪刺刀：技能判定时长（tick） */
	private static final ForgeConfigSpec.IntValue SHADOW_DARK_ARTS_DURATION_TICKS;
	/** 献祭匕首：基础斩击倍率 */
	private static final ForgeConfigSpec.DoubleValue SHADOW_SACRIFICIAL_MULTIPLIER;
	/** 暗仪刺刀：基础斩击倍率 */
	private static final ForgeConfigSpec.DoubleValue SHADOW_DARK_ARTS_MULTIPLIER;
	/** 接触判定半径 */
	private static final ForgeConfigSpec.DoubleValue SHADOW_CONTACT_RADIUS;
	/** 强力斩击半径 */
	private static final ForgeConfigSpec.DoubleValue SHADOW_HEAVY_RADIUS;
	/** 技能期间移动速度加成（百分比） */
	private static final ForgeConfigSpec.IntValue SHADOW_MOVEMENT_PERCENT;
	/** 冷却（tick，从无敌解除开始计时） */
	private static final ForgeConfigSpec.IntValue SHADOW_COOLDOWN_TICKS;
	/** 逐个结算标记目标的间隔（tick） */
	private static final ForgeConfigSpec.IntValue SHADOW_RESOLVE_INTERVAL_TICKS;
	/** 献祭匕首：连续多少秒身上没有匕首才开放"防丢失配方" */
	private static final ForgeConfigSpec.IntValue DAGGER_RECOVERY_SECONDS;
	/** 亚巴顿联动：遁入暗影时长加成（tick，1.7.10） */
	private static final ForgeConfigSpec.IntValue SHADOW_ABADDON_DURATION_TICKS;
	/** 亚巴顿联动：接触判定半径加成（格，1.7.10） */
	private static final ForgeConfigSpec.DoubleValue SHADOW_ABADDON_CONTACT_RADIUS;
	/** 亚巴顿联动：强力斩击半径加成（格，1.7.10） */
	private static final ForgeConfigSpec.DoubleValue SHADOW_ABADDON_HEAVY_RADIUS;

	// ===== 献祭（1.8.2：献祭匕首的右键技能） =====
	/** 献祭：总开关 */
	private static final ForgeConfigSpec.BooleanValue ENABLE_SACRIFICE;
	/** 献祭：对自身造成的伤害点数（不致死） */
	private static final ForgeConfigSpec.DoubleValue SACRIFICE_SELF_DAMAGE;
	/** 献祭：近战增伤（百分比，随时间线性衰减到 0） */
	private static final ForgeConfigSpec.IntValue SACRIFICE_DAMAGE_PERCENT;
	/** 献祭：增伤持续秒数 */
	private static final ForgeConfigSpec.DoubleValue SACRIFICE_DURATION_SECONDS;
	/** 献祭：冷却秒数 */
	private static final ForgeConfigSpec.IntValue SACRIFICE_COOLDOWN_SECONDS;

	// ===== 神圣行动（1.8.2：神性回溯 / 亚巴顿恶魔形态的 X 键） =====
	/** X 技能：长按蓄力秒数 */
	private static final ForgeConfigSpec.DoubleValue DIVINE_ACTION_CHARGE_SECONDS;

	// ===== 金刀片（1.7.10：无属性、右键投掷、固定伤害、可穿透） =====
	private static final ForgeConfigSpec.BooleanValue ENABLE_GOLDEN_RAZOR;
	/** 金刀片：命中伤害点数（不吃任何加成） */
	private static final ForgeConfigSpec.DoubleValue RAZOR_DAMAGE;
	/** 金刀片：投掷冷却（tick，10 = 0.5 秒） */
	private static final ForgeConfigSpec.IntValue RAZOR_COOLDOWN_TICKS;
	/** 金刀片：出手速度 */
	private static final ForgeConfigSpec.DoubleValue RAZOR_VELOCITY;
	/** 金刀片：穿透等级（原版口径：可穿透 等级+1 个生物） */
	private static final ForgeConfigSpec.IntValue RAZOR_PIERCE_LEVEL;
	/** 金刀片：幻影存活上限（tick 兜底删除） */
	private static final ForgeConfigSpec.IntValue RAZOR_MAX_LIFE_TICKS;
	/** 金刀片：命中方块后插在原地停留多久再清除（tick，100 = 5 秒） */
	private static final ForgeConfigSpec.IntValue RAZOR_STUCK_TICKS;

	// ===== 启示之座（伯列恒之星 / 终末天启） =====
	private static final ForgeConfigSpec.IntValue REVEAL_SECONDS;
	private static final ForgeConfigSpec.IntValue REVEAL_RADIUS;
	private static final ForgeConfigSpec.IntValue STAR_ATTACK_SPEED_PERCENT;
	private static final ForgeConfigSpec.IntValue STAR_DAMAGE_PERCENT;
	private static final ForgeConfigSpec.IntValue FINAL_SOUL_HEARTS;
	private static final ForgeConfigSpec.DoubleValue FLIGHT_SPEED_MULTIPLIER;
	private static final ForgeConfigSpec.IntValue TRANSFORM_RADIUS;
	private static final ForgeConfigSpec.IntValue TRANSFORM_SECONDS;
	private static final ForgeConfigSpec.DoubleValue TRANSFORM_MAX_MOVE_BLOCKS;
	private static final ForgeConfigSpec.IntValue BEAM_LENGTH;
	private static final ForgeConfigSpec.IntValue BEAM_RADIUS;
	private static final ForgeConfigSpec.IntValue BEAM_DAMAGE_PER_TICK;
	private static final ForgeConfigSpec.DoubleValue BEAM_DURATION_SECONDS;
	private static final ForgeConfigSpec.DoubleValue BEAM_DAMAGE_INTERVAL_SECONDS;
	private static final ForgeConfigSpec.DoubleValue BEAM_CHARGE_SECONDS;
	private static final ForgeConfigSpec.IntValue BEAM_COOLDOWN_SECONDS;
	private static final ForgeConfigSpec.IntValue BEAM_BROADCAST_RADIUS;
	private static final ForgeConfigSpec.DoubleValue CHARGE_FOV_SCALE;

	static {
		ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

		builder.comment("邦邦女仆（邦邦咔邦光环）相关设置").push("maid");
		RADIUS = builder
				.comment("邦邦女仆状态下吸附掉落物的半径，单位：格；范围 1-32，默认 10")
				.defineInRange("radius", DEFAULT_RADIUS, MIN_RADIUS, MAX_RADIUS);
		builder.pop();

		builder.comment("首次进入世界时的发放（每个玩家只发一次；按存档保存，老玩家下次登录会补发一次）")
				.push("start");
		GRANT_START_ITEMS = builder
				.comment("首次进入时发放「七罪之源」与「光环」（都放进背包，不自动佩戴）")
				.define("grant_items", true);
		AUTO_EQUIP_SOURCE_OF_SINS = builder
				.comment("首次进入时是否直接把「七罪之源」装进魂印栏（默认关闭：只放进背包）")
				.define("auto_equip_source_of_sins", false);
		builder.pop();

		builder.comment("灵台（肉体 / 思想 / 灵魂）相关设置").push("spirit_altar");
		BODY_HEALTH = builder.comment("肉体：佩戴时的最大生命加成")
				.defineInRange("body_health", 10, 0, 1024);
		BODY_SET_DAMAGE_REDUCTION_PERCENT = builder
				.comment("肉体：三件套齐时受到的伤害减免（百分比，套装效果）")
				.defineInRange("body_set_damage_reduction_percent", 20, 0, 100);
		GLOW_RADIUS = builder.comment("思想：发光半径，单位：格")
				.defineInRange("glow_radius", 24, 1, 128);
		MIND_BONUS_PERCENT = builder.comment("思想：三件套齐时对发光目标的额外伤害百分比")
				.defineInRange("mind_bonus_percent", 10, 0, 1000);
		SOUL_HEARTS = builder.comment("灵魂：提供的魂心数量（每 1 魂心 = 2 点吸收）")
				.defineInRange("soul_hearts", 3, 0, 1024);
		ABSORPTION_PER_SOUL_HEART = builder
				.comment("灵魂：每 1 点魂心换算成多少点吸收（黄血）")
				.defineInRange("absorption_per_soul_heart", 2.0D, 0.0D, 1024.0D);
		SOUL_REFRESH_SECONDS = builder.comment("灵魂：吸收（黄血）补满的间隔秒数")
				.defineInRange("soul_refresh_seconds", 30, 1, 3600);
		ENABLE_SOUL_HEART_HUD = builder
				.comment("魂心 HUD：用自绘的蓝色魂心覆盖在原版黄心的对应位置（只表示本模组魂心份额，不隐藏原版黄心）")
				.define("enable_soul_heart_hud", true);
		SOUL_HEART_HUD_LAYOUT = builder
				.comment("魂心 HUD：布局模式 —— auto = 自动识别（检测到 Mantle 或经典状态条 Classic Bar 时按 single_row 处理）、"
						+ "vanilla = 完全复刻原版心网格折行（无 HUD 模组时的原口径）、"
						+ "single_row = 把血条视为恒定一排（Mantle 的血条恒为一行、Classic Bar 是一条横条），"
						+ "蓝心不再随生命上限往上折行，只保留盔甲条的避让、也不再为原版吸收值留行、"
						+ "classic_row = 按「经典状态条」的实际条位锚定（y = 屏幕高 − gui.rightHeight − 10），"
						+ "红心 / 黄心 / 盔甲都已并入它的条堆，因此不再叠加任何行")
				.define("soul_heart_hud_layout", "auto");
		SOUL_HEART_HUD_OFFSET_Y = builder
				.comment("魂心 HUD：在上述布局结果之上再整体下移 / 上移多少像素（正数向下、负数向上），"
						+ "主要用于把蓝心对准 Classic Bar 的实际条位")
				.defineInRange("soul_heart_hud_offset_y", 0, -40, 40);
		SOUL_SHATTER_KNOCKBACK = builder
				.comment("魂心完全破碎时对范围内敌人的击退强度（越大越远）")
				.defineInRange("soul_shatter_knockback", 2.0D, 0.0D, 20.0D);
		SOUL_SHATTER_RADIUS = builder.comment("魂心完全破碎时的影响半径（格）")
				.defineInRange("soul_shatter_radius", 7, 1, 64);
		SOUL_SHATTER_INVULNERABLE_SECONDS = builder
				.comment("魂心完全破碎后给予玩家的无敌时间（秒，期间取消一切伤害）")
				.defineInRange("soul_shatter_invulnerable_seconds", 5.0D, 0.0D, 60.0D);
		DEATH_IMMUNITY_PERCENT = builder.comment("灵魂：三件套齐时免死的几率（百分比）；触发时保留 1 点生命并播放图腾音效与粒子")
				.defineInRange("death_immunity_percent", 20, 0, 100);
		builder.pop();

		builder.comment("七罪之源：每一项罪的具体效果数值（只有佩戴七罪之源时才会生效）").push("sins");
		ENABLE_SIN_EFFECTS = builder
				.comment("总开关：关闭后七罪只保留状态与提示文本，不产生任何效果")
				.define("enable_sin_effects", true);
		PRIDE_KILL_REQUIRED = builder.comment("傲慢：触发需要击杀的中立/友善生物数量")
				.defineInRange("pride_kill_required", 10, 0, 100000);
		PRIDE_DAMAGE_PER_PERCENT = builder
				.comment("傲慢：目标每损失 1% 生命，你对其伤害增加的百分比（1.0 = 每损失 1% 生命加 1% 伤害）")
				.defineInRange("pride_damage_per_percent", 1.0D, 0.0D, 10.0D);
		PRIDE_INCOMING_DAMAGE_PERCENT = builder.comment("傲慢：自身受到的所有伤害增加（百分比）")
				.defineInRange("pride_incoming_damage_percent", 50, 0, 1000);
		ENVY_OBSERVE_RADIUS = builder.comment("嫉妒：触发判定「观察到」的半径（格）")
				.defineInRange("envy_observe_radius", 16, 1, 128);
		ENVY_BONUS_PERCENT = builder.comment("嫉妒：对满足条件目标的伤害增加（百分比）")
				.defineInRange("envy_bonus_percent", 50, 0, 1000);
		ENVY_HOSTILE_RADIUS = builder.comment("嫉妒：中立生物被强制敌对的半径（格）")
				.defineInRange("envy_hostile_radius", 8, 1, 64);
		ENVY_HOSTILE_REFRESH_TICKS = builder.comment("嫉妒：重新把中立生物的目标设为你自己的间隔（tick）")
				.defineInRange("envy_hostile_refresh_ticks", 20, 1, 200);
		WRATH_KILL_REQUIRED = builder.comment("暴怒：触发需要累计击杀的敌对/中立生物数量")
				.defineInRange("wrath_kill_required", 100, 0, 100000);
		WRATH_RANDOM_MIN = builder.comment("暴怒：攻击伤害随机浮动下限（0.5 = 五成伤害）")
				.defineInRange("wrath_random_min", 0.5D, 0.0D, 10.0D);
		WRATH_RANDOM_MAX = builder.comment("暴怒：攻击伤害随机浮动上限（激活时）")
				.defineInRange("wrath_random_max", 1.5D, 0.0D, 10.0D);
		WRATH_RANDOM_MAX_REDEEMED = builder.comment("暴怒：赎罪后的随机浮动上限")
				.defineInRange("wrath_random_max_redeemed", 2.0D, 0.0D, 10.0D);
		WRATH_SELF_HIT_PERCENT = builder.comment("暴怒：每次攻击使自己受到等量伤害的几率（百分比）")
				.defineInRange("wrath_self_hit_percent", 15, 0, 100);
		WRATH_SELF_HIT_MULTIPLIER = builder
				.comment("暴怒：自伤倍率（1.0 = 等量伤害；1.8.0 起默认 0.5，且自伤永不致死）")
				.defineInRange("wrath_self_hit_multiplier", 0.5D, 0.0D, 10.0D);
		SLOTH_SLEEP_REQUIRED = builder.comment("怠惰：触发需要「早睡」的次数")
				.defineInRange("sloth_sleep_required", 3, 0, 1000);
		SLOTH_SLEEP_BEFORE_HOUR = builder
				.comment("怠惰：入睡时刻的游戏时钟早于几点才算「早睡」（20 = 晚上 8 点；原版只允许夜间或雷雨入睡，"
						+ "因此正常情况下窗口是 19:42~20:00）")
				.defineInRange("sloth_sleep_before_hour", 20, 0, 24);
		SLOTH_RESISTANCE_AMPLIFIER = builder.comment("怠惰：抗性提升的等级（0 = 抗性提升 I）")
				.defineInRange("sloth_resistance_amplifier", 0, 0, 9);
		SLOTH_RESISTANCE_AMPLIFIER_REDEEMED = builder.comment("怠惰：赎罪后的抗性提升等级（1 = 抗性提升 II）")
				.defineInRange("sloth_resistance_amplifier_redeemed", 1, 0, 9);
		SLOTH_SLOWDOWN_PERCENT = builder.comment("怠惰：移动 / 挖掘 / 攻击速度降低的百分比")
				.defineInRange("sloth_slowdown_percent", 20, 0, 90);
		GREED_DIAMOND_THRESHOLD = builder.comment("贪婪：触发需要的背包钻石数量（大于该值即触发）")
				.defineInRange("greed_diamond_threshold", 36, 0, 10000);
		GREED_DAMAGE_PER_DIAMOND_PERCENT = builder.comment("贪婪：每 1 颗钻石提供的伤害加成（百分比）")
				.defineInRange("greed_damage_per_diamond_percent", 1, 0, 100);
		GREED_DAMAGE_CAP_PERCENT = builder.comment("贪婪：钻石提供的伤害加成上限（百分比）")
				.defineInRange("greed_damage_cap_percent", 64, 0, 10000);
		GREED_LOW_DIAMOND_DAMAGE_FACTOR = builder.comment("贪婪：钻石少于阈值时最终伤害的倍率（0.10 = 降至一成）")
				.defineInRange("greed_low_diamond_damage_factor", 0.10D, 0.0D, 1.0D);
		GREED_DEATH_DIAMOND_MIN = builder.comment("贪婪：每次死亡随机扣除的钻石数量下限")
				.defineInRange("greed_death_diamond_min", 3, 0, 10000);
		GREED_DEATH_DIAMOND_MAX = builder.comment("贪婪：每次死亡随机扣除的钻石数量上限")
				.defineInRange("greed_death_diamond_max", 8, 0, 10000);
		GLUTTONY_MEAL_REQUIRED = builder.comment("暴食：触发需要食用「总饱和度大于阈值」食物的次数")
				.defineInRange("gluttony_meal_required", 10, 0, 1000);
		GLUTTONY_SATURATION_THRESHOLD = builder.comment("暴食：触发判定的食物总饱和度阈值")
				.defineInRange("gluttony_saturation_threshold", 8.0D, 0.0D, 100.0D);
		GLUTTONY_KILL_HEAL = builder.comment("暴食：击杀生物回复的生命值")
				.defineInRange("gluttony_kill_heal", 2.0D, 0.0D, 1024.0D);
		GLUTTONY_KILL_FOOD = builder.comment("暴食：击杀生物回复的饥饿值与饱和度")
				.defineInRange("gluttony_kill_food", 1, 0, 100);
		GLUTTONY_FOOD_CAP = builder.comment("暴食：饥饿值上限（未赎罪时）")
				.defineInRange("gluttony_food_cap", 18, 0, 100);
		GLUTTONY_DRAIN_SECONDS = builder.comment("暴食：强制扣除 1 点饥饿值与饱和度的间隔秒数")
				.defineInRange("gluttony_drain_seconds", 20, 1, 3600);
		GLUTTONY_WEAK_FOOD_THRESHOLD = builder.comment("暴食：饥饿值低于该值时获得缓慢 I 与虚弱 I")
				.defineInRange("gluttony_weak_food_threshold", 10, 0, 100);
		LUST_BREED_REQUIRED = builder.comment("色欲：触发需要累计繁殖动物的次数")
				.defineInRange("lust_breed_required", 10, 0, 1000);
		LUST_STRIP_ARMOR_PERCENT = builder.comment("色欲：攻击时脱下目标一件护甲的几率（百分比）")
				.defineInRange("lust_strip_armor_percent", 15, 0, 100);
		LUST_ARMOR_REDUCTION_PERCENT = builder.comment("色欲：自身护甲值与盔甲韧性降低的百分比")
				.defineInRange("lust_armor_reduction_percent", 30, 0, 90);
		LUST_SELF_STRIP_PERCENT = builder.comment("色欲：受击时把自身一件护甲脱到背包的几率（百分比）")
				.defineInRange("lust_self_strip_percent", 15, 0, 100);
		// 1.7.10 收尾：「纯洁无瑕」的佩戴率判据（参考 Enigmatic Legacy 的 IPlaytimeCounter 双计数器做法）
		FLAWLESS_MIN_WEARING_PERCENT = builder
				.comment("「纯洁无瑕」：击杀末影龙时要求的最低「佩戴七罪之源」比例（百分比；99 = 允许 1% 未佩戴）")
				.defineInRange("flawless_min_wearing_percent", 99, 0, 100);
		FLAWLESS_UNWORN_GRACE_SECONDS = builder
				.comment("「纯洁无瑕」：开局宽限（秒）——获得七罪之源后的这段时间内，未佩戴不计入判据")
				.defineInRange("flawless_unworn_grace_seconds", 120, 0, 86400);
		builder.pop();

		builder.comment("美德加成（1.4.4 新增；生效条件：佩戴美德且七罪全部已赎清）").push("virtues");
		ENABLE_VIRTUE_EFFECTS = builder.comment("七德效果总开关").define("enable_virtue_effects", true);
		HUMILITY_DAMAGE_BONUS_PERCENT = builder
				.comment("谦逊：目标生命百分比或最大生命高于你时，对其额外造成的伤害（百分比，作用于全部伤害）")
				.defineInRange("humility_damage_bonus_percent", 15, 0, 1000);
		CHARITY_SPEED_PERCENT_PER_DROP = builder.comment("慷慨：每次丢弃物品获得的移动速度（百分比）")
				.defineInRange("charity_speed_percent_per_drop", 2, 0, 100);
		CHARITY_SPEED_CAP_PERCENT = builder.comment("慷慨：移动速度加成上限（百分比）")
				.defineInRange("charity_speed_cap_percent", 10, 0, 100);
		CHARITY_DURATION_SECONDS = builder.comment("慷慨：增益持续时间（秒，可刷新）")
				.defineInRange("charity_duration_seconds", 30, 1, 3600);
		CHASTITY_DURABILITY_PER_SECOND = builder.comment("贞洁：每秒为身上装备回复的耐久")
				.defineInRange("chastity_durability_per_second", 1, 0, 100);
		KINDNESS_HEAL_PER_SECOND = builder.comment("善意：每秒为自己与范围内友善生物/玩家回复的生命")
				.defineInRange("kindness_heal_per_second", 1.0D, 0.0D, 1024.0D);
		KINDNESS_RADIUS = builder.comment("善意：作用半径（格）").defineInRange("kindness_radius", 7, 1, 64);
		PATIENCE_BONUS_PERCENT_PER_HIT = builder
				.comment("耐心：对同一目标连续近战命中，每次叠加的伤害加成（百分比）")
				.defineInRange("patience_bonus_percent_per_hit", 6, 0, 100);
		PATIENCE_CAP_PERCENT = builder.comment("耐心：伤害加成上限（百分比）")
				.defineInRange("patience_cap_percent", 30, 0, 1000);
		PATIENCE_RESET_SECONDS = builder.comment("耐心：多久没有命中同一目标就清零（秒）")
				.defineInRange("patience_reset_seconds", 4, 1, 600);
		DILIGENCE_SPEED_PERCENT = builder.comment("勤勉：移动 / 攻击 / 挖掘速度提升（百分比）")
				.defineInRange("diligence_speed_percent", 10, 0, 1000);
		builder.pop();

		builder.comment("加护（救恩的领域审判，1.5.0）").push("blessing");
		SALVATION_RADIUS = builder.comment("救恩：领域半径（格）")
				.defineInRange("salvation_radius", 3.0D, 0.5D, 32.0D);
		SALVATION_RADIUS_EXTENDED = builder.comment("救恩：同时佩戴终末天启时的领域半径（格）")
				.defineInRange("salvation_extended_radius", 4.0D, 0.5D, 32.0D);
		SALVATION_LOCK_SECONDS = builder.comment("救恩：目标需要在领域内累计停留多少秒才被审判")
				.defineInRange("salvation_lock_seconds", 1.0D, 0.1D, 60.0D);
		SALVATION_DAMAGE = builder.comment("救恩：每次审判造成的伤害（无视护甲/附魔/抗性，先扣黄血）")
				.defineInRange("salvation_damage", 7, 0, 10000);
		SALVATION_COOLDOWN_SECONDS = builder.comment("救恩：每名敌人被审判后的冷却秒数")
				.defineInRange("salvation_cooldown_seconds", 0.5D, 0.05D, 60.0D);
		builder.pop();

		builder.comment("圣光（加护饰品：命中几率召唤圣光，1.5.4）").push("holy_light");
		ENABLE_HOLY_LIGHT = builder.comment("圣光：总开关")
				.define("enable_holy_light", true);
		HOLY_LIGHT_CHANCE_PERCENT = builder.comment("圣光：每次命中的触发几率（百分比）")
				.defineInRange("holy_light_chance_percent", 15, 0, 100);
		HOLY_LIGHT_CHANCE_PERCENT_GODHEAD = builder
				.comment("圣光：同时佩戴「神性」时的触发几率（百分比，1.6.10 联动）")
				.defineInRange("holy_light_chance_percent_godhead", 25, 0, 100);
		HOLY_LIGHT_DAMAGE_PERCENT = builder.comment("圣光：以本次命中（各类倍率后、减伤前）为基准的伤害百分比")
				.defineInRange("holy_light_damage_percent", 120, 0, 10000);
		HOLY_LIGHT_CHANCE_PERCENT_SPEAR = builder
				.comment("圣光：手持「圣光短矛」且达标时的独立触发几率（百分比，1.7.9）")
				.defineInRange("holy_light_chance_percent_spear", 10, 0, 100);
		HOLY_LIGHT_BURST_DAMAGE = builder
				.comment("炽天使之枪：投掷命中时落点圣光爆发的真实伤害点数（1.7.9）")
				.defineInRange("holy_light_burst_damage", 14, 0, 10000);
		HOLY_LIGHT_BURST_RADIUS = builder
				.comment("炽天使之枪：落点圣光爆发的半径（格，1.7.9）")
				.defineInRange("holy_light_burst_radius", 4, 0, 64);
		HOLY_LIGHT_BURST_RADIUS_GODHEAD = builder
				.comment("炽天使之枪 + 神性：落点爆发的半径加成（格，1.7.10 联动）")
				.defineInRange("holy_light_burst_radius_godhead", 1, 0, 32);
		HOLY_LIGHT_BURST_DAMAGE_GODHEAD = builder
				.comment("炽天使之枪 + 神性：落点爆发的伤害加成（点，1.7.10 联动）")
				.defineInRange("holy_light_burst_damage_godhead", 2, 0, 10000);
		builder.pop();

		builder.comment("神圣斗篷（加护饰品：受击后短暂无敌，1.5.4）").push("holy_mantle");
		ENABLE_HOLY_MANTLE = builder.comment("神圣斗篷：总开关")
				.define("enable_holy_mantle", true);
		HOLY_MANTLE_INVULNERABLE_SECONDS = builder.comment("神圣斗篷：受击后获得的无敌秒数")
				.defineInRange("holy_mantle_invulnerable_seconds", 1.0D, 0.05D, 60.0D);
		HOLY_MANTLE_INVULNERABLE_SECONDS_GODHEAD = builder
				.comment("神圣斗篷：同时佩戴「神性」时的无敌秒数（1.6.10 联动）")
				.defineInRange("holy_mantle_invulnerable_seconds_godhead", 1.5D, 0.05D, 60.0D);
		builder.pop();

		builder.comment("圣心（加护饰品：属性提升 + 箭矢追踪，1.5.5）").push("sacred_heart");
		SACRED_HEART_MAX_HEALTH = builder.comment("圣心：最大生命加成")
				.defineInRange("max_health", 10.0D, 0.0D, 1024.0D);
		SACRED_HEART_ARMOR = builder.comment("圣心：护甲值加成")
				.defineInRange("armor", 5.0D, 0.0D, 1024.0D);
		SACRED_HEART_TOUGHNESS = builder.comment("圣心：盔甲韧性加成")
				.defineInRange("armor_toughness", 5.0D, 0.0D, 1024.0D);
		SACRED_HEART_ATTACK_SPEED = builder.comment("圣心：攻击速度加成（加法）")
				.defineInRange("attack_speed", 0.5D, 0.0D, 64.0D);
		SACRED_HEART_MOVEMENT_PERCENT = builder.comment("圣心：移动速度加成（百分比，与光环同算法）")
				.defineInRange("movement_percent", 15, 0, 1000);
		SACRED_HEART_BREAK_SPEED_PERCENT = builder.comment("圣心：挖掘速度加成（百分比）")
				.defineInRange("break_speed_percent", 15, 0, 1000);
		SACRED_HEART_DAMAGE_PERCENT = builder.comment("圣心：全伤害最终倍率（百分比；与神性同乘区相加）")
				.defineInRange("damage_percent", 30, 0, 10000);
		SACRED_HEART_ARROW_RADIUS = builder.comment("圣心：箭矢追踪半径（格，以箭矢自身为中心）")
				.defineInRange("arrow_homing_radius", 8.0D, 1.0D, 64.0D);
		SACRED_HEART_ARROW_RADIUS_GODHEAD = builder
				.comment("圣心：同时佩戴「神性」时的箭矢追踪半径（格，1.6.10 联动）")
				.defineInRange("arrow_homing_radius_godhead", 12.0D, 1.0D, 128.0D);
		ENABLE_ARROW_HOMING = builder.comment("圣心：是否开启箭矢追踪")
				.define("enable_arrow_homing", true);
		SACRED_HEART_THROW_SPEED_PERCENT = builder
				.comment("圣心：投掷初速加成（百分比，1.7.10 联动；只作用于两把天使线长矛）")
				.defineInRange("throw_speed_bonus_percent", 25, 0, 1000);
		builder.pop();

		builder.comment("神性（启示之座饰品：继承天启、光环审判、环境免疫、死亡拦截，1.5.5）").push("godhead");
		ENABLE_GODHEAD = builder.comment("神性：总开关")
				.define("enable_godhead", true);
		GODHEAD_BEAM_CHARGE_SECONDS = builder.comment("神性：启示之光蓄力秒数")
				.defineInRange("beam_charge_seconds", 1.5D, 0.1D, 60.0D);
		GODHEAD_BEAM_COOLDOWN_SECONDS = builder.comment("神性：启示之光冷却秒数")
				.defineInRange("beam_cooldown_seconds", 5.0D, 0.0D, 3600.0D);
		GODHEAD_BEAM_RADIUS = builder.comment("神性：启示之光光柱半径（格）")
				.defineInRange("beam_radius", 3, 0, 32);
		GODHEAD_BEAM_LENGTH = builder.comment("神性：启示之光射程（格）")
				.defineInRange("beam_length", 35, 1, 128);
		GODHEAD_SALVATION_RADIUS = builder.comment("神性：佩戴时救恩领域半径（格）")
				.defineInRange("salvation_radius", 5.0D, 0.5D, 32.0D);
		GODHEAD_AURA_RADIUS = builder.comment("神性：光环半径（格）")
				.defineInRange("aura_radius", 8.0D, 0.5D, 32.0D);
		GODHEAD_AURA_DAMAGE = builder.comment("神性：光环每次结算的圣光伤害（真实伤害）")
				.defineInRange("aura_damage", 2, 0, 10000);
		GODHEAD_DAMAGE_PERCENT = builder.comment("神性：全伤害最终倍率（百分比；与圣心同乘区相加）")
				.defineInRange("damage_percent", 20, 0, 10000);
		builder.pop();

		builder.comment("战斗手感：受伤无敌帧与攻击速度上限").push("combat");
		ENABLE_INVULNERABILITY_CHANGE = builder
				.comment("是否修改生物受伤后的无敌帧（默认开启）")
				.define("enable_invulnerability_change", true);
		INVULNERABILITY_TICKS = builder
				.comment("一般受伤的有效无敌窗口（tick，默认 5 = 0.25 秒）；"
						+ "实现上会写成 窗口+10（原版判定是 invulnerableTime > 10），0 = 这一档交回原版")
				.defineInRange("invulnerability_ticks", 5, 0, 60);
		INVULNERABILITY_TICKS_FRAME_DAMAGE = builder
				.comment("帧伤 / 持续环境伤害（火焰、岩浆、仙人掌、细雪、甜浆果丛、石笋、溺水……）的有效无敌窗口（tick）"
						+ "；默认 10 = 与原版一致（原版字段 20），0 = 交回原版")
				.defineInRange("invulnerability_ticks_frame_damage", 10, 0, 60);
		FRAME_DAMAGE_IDS = builder
				.comment("额外按「帧伤」处理的伤害类型 id 列表（例如其它模组的持续伤害：\"mod:laser\"）")
				.defineListAllowEmpty("frame_damage_ids", List.of(), element -> element instanceof String);
		LOG_IFRAME_THROTTLE = builder
				.comment("每 5 秒为每个玩家汇总一条 INFO：受伤尝试 / 落地 / 被无敌帧挡下的次数与来源")
				.define("log_iframe_throttle", true);
		LOG_DAMAGE_POOLS = builder
				.comment("每次魂心 / 黑心接管伤害时打一条 INFO（护盾 A0、并入量 T、对账后的吸收值、护盾/池子各扣多少、退还多少）；"
						+ "1.6.5 起默认开启，用于排查 Kilt 结算")
				.define("log_damage_pools", true);
		MAX_ATTACK_SPEED = builder
				.comment("攻击速度属性的上限（原版玩家空手基础为 4.0）；超过该值的攻速加成不会生效")
				.defineInRange("max_attack_speed", 4.0D, 0.0D, 1024.0D);
		AFFECT_TARGET_DUMMIES = builder
				.comment("救恩领域 / 神性光环是否把「训练人偶」（dummmmmmy:target_dummy）也算作目标（默认开启）")
				.define("affect_target_dummies", true);
		builder.pop();

		builder.comment("硫磺火（1.6.4）：长按 V 1.5 秒发射的「恶魔之焰」火柱").push("brimstone");
		ENABLE_BRIMSTONE = builder.comment("是否启用硫磺火与恶魔之焰").define("enable_brimstone", true);
		BRIMSTONE_CHARGE_SECONDS = builder.comment("恶魔之焰：长按 V 的蓄力秒数")
				.defineInRange("charge_seconds", 1.5D, 0.1D, 60.0D);
		BRIMSTONE_BEAM_LENGTH = builder.comment("恶魔之焰：火柱长度（格）")
				.defineInRange("beam_length", 18, 1, 128);
		BRIMSTONE_BEAM_RADIUS = builder.comment("恶魔之焰：火柱半径（格）")
				.defineInRange("beam_radius", 2, 0, 32);
		BRIMSTONE_DURATION_SECONDS = builder.comment("恶魔之焰：持续照射秒数")
				.defineInRange("beam_duration_seconds", 1.3D, 0.05D, 60.0D);
		BRIMSTONE_DAMAGE_PER_TICK = builder.comment("恶魔之焰：每次结算的狱火（真伤）点数")
				.defineInRange("damage_per_tick", 6, 0, 100000);
		BRIMSTONE_DAMAGE_INTERVAL_SECONDS = builder.comment("恶魔之焰：两次结算之间的间隔秒数")
				.defineInRange("damage_interval_seconds", 0.1D, 0.05D, 10.0D);
		BRIMSTONE_COOLDOWN_SECONDS = builder.comment("恶魔之焰：冷却秒数（从发射成功算起）")
				.defineInRange("cooldown_seconds", 6, 0, 3600);
		builder.pop();

		builder.comment("启示之光 buff 与「心之碎片」掉落（1.5.6）").push("revelation_light");
		ENABLE_REVELATION_LIGHT_BUFF = builder
				.comment("是否让所有启示伤害（光柱 / 救恩领域）给受击者挂上 7 秒「启示之光」")
				.define("enable_buff", true);
		REVELATION_LIGHT_BUFF_SECONDS = builder.comment("启示之光 buff 的时长（秒）")
				.defineInRange("buff_seconds", 7.0D, 0.1D, 600.0D);
		ENABLE_HEART_SHARD_DROP = builder.comment("是否允许带启示之光的生物死亡时掉落「心之碎片」")
				.define("enable_heart_shard_drop", true);
		HEART_SHARD_DROP_CHANCE = builder.comment("心之碎片：普通生物的掉落几率（百分比）")
				.defineInRange("drop_chance_percent", 0.5D, 0.0D, 100.0D);
		HEART_SHARD_BOSS_CHANCE = builder.comment("心之碎片：凋灵 / 监守者的掉落几率（百分比）")
				.defineInRange("boss_chance_percent", 5.0D, 0.0D, 100.0D);
		HEART_SHARD_DRAGON_CHANCE = builder.comment("心之碎片：末影龙的掉落几率（百分比）")
				.defineInRange("dragon_chance_percent", 20.0D, 0.0D, 100.0D);
		LOG_DROP_CHECKS = builder
				.comment("是否打印心之碎片的判定过程日志（只在「带着启示之光死亡」的生物上打印，不会刷屏）")
				.define("log_drop_checks", true);
		builder.pop();

		// 恶魔交易（1.5.9）：灵魂沙峡谷驻留对话 + 长按五芒星签约
		builder.comment("恶魔交易（五芒星：灵魂沙峡谷驻留对话 + 长按右键签约）").push("demon_deal");
		ENABLE_DEMON_DEAL = builder.comment("总开关：关闭后不触发任何对话、也不能签约")
				.define("enable_demon_deal", true);
		DEMON_BIOME_DWELL_SECONDS = builder.comment("在灵魂沙峡谷里需要连续停留多少秒才触发对话")
				.defineInRange("biome_dwell_seconds", 5, 1, 3600);
		DEMON_CHARGE_SECONDS = builder.comment("手持五芒星长按右键签约所需的秒数")
				.defineInRange("charge_seconds", 5.0D, 0.1D, 60.0D);
		DEMON_LINE_DELAY_TICKS = builder.comment("恶魔台词的逐行间隔（tick；20 = 1 秒）")
				.defineInRange("line_delay_ticks", 40, 1, 400);
		DEMON_ERROR_COOLDOWN_SECONDS = builder.comment("「这里显然不是那个地方」提示的最短间隔（秒）")
				.defineInRange("error_message_cooldown_seconds", 5, 0, 3600);
		DEMON_CHARGE_FOV_SCALE = builder
				.comment("长按五芒星蓄力时视野（FOV）最多收缩的比例，0.20 = 收缩 20%")
				.defineInRange("charge_fov_scale", 0.20D, 0.0D, 0.9D);
		builder.pop();

		// 恶魔契约（1.6.0）：签约后发放的「恶魔契约」饰品 + 献祭 + 黑心 + 邪恶度体系
		builder.comment("恶魔契约（签约后强制佩戴、无法摘除）").push("demon_pact");
		ENABLE_DEMON_PACT = builder.comment("总开关：关闭后契约属性 / 献祭 / 黑心 / 邪恶度全部不生效")
				.define("enable_demon_pact", true);
		PACT_VILLAGER_DAMAGE = builder
				.comment("佩戴契约时对村民追加的真实伤害（仅献祭仪式用，完成首次献祭后失效）")
				.defineInRange("villager_sacrifice_damage", 10000.0D, 0.0D, 1000000.0D);
		PACT_BLACK_HEARTS = builder.comment("黑心心数（完成首份契约的献祭时获得）")
				.defineInRange("black_hearts", 2.0D, 0.0D, 100.0D);
		PACT_ABSORPTION_PER_HEART = builder.comment("每 1 颗黑心换算成多少点吸收（1 心 = 2 点）")
				.defineInRange("absorption_per_black_heart", 2.0D, 0.0D, 100.0D);
		PACT_DAMAGE_PERCENT = builder.comment("契约提供的伤害加成（百分比；与圣心/神性同乘区相加）")
				.defineInRange("damage_percent", 16.0D, 0.0D, 1000.0D);
		PACT_ATTACK_SPEED_PERCENT = builder.comment("契约提供的攻击速度加成（百分比）")
				.defineInRange("attack_speed_percent", 16.0D, 0.0D, 1000.0D);
		PACT_HEALTH_PER_CURSE_PERCENT = builder.comment("每一条生效诅咒提供的生命加成（百分比）")
				.defineInRange("health_per_curse_percent", 2.0D, 0.0D, 1000.0D);
		PACT_SPEED_PER_CURSE_PERCENT = builder.comment("每一条生效诅咒提供的移动速度加成（百分比）")
				.defineInRange("speed_per_curse_percent", 2.0D, 0.0D, 1000.0D);
		PACT_LIFESTEAL_CURSE_THRESHOLD = builder.comment("生命吸取需要诅咒条数**大于**该值（默认 5）")
				.defineInRange("lifesteal_curse_threshold", 5, 0, 100);
		PACT_LIFESTEAL_PERCENT = builder.comment("生命吸取比例（百分比，按本次出伤值回血）")
				.defineInRange("lifesteal_percent", 6.0D, 0.0D, 1000.0D);
		EVIL_MAX = builder.comment("邪恶度上限")
				.defineInRange("evil_max", 1000, 1, 1000000);
		EVIL_PER_POINT_PERCENT = builder
				.comment("每 1 点邪恶提供的攻击 / 攻速加成（百分比，奇偶交替：奇数点给攻击、偶数点给攻速）")
				.defineInRange("evil_per_point_percent", 0.1D, 0.0D, 10.0D);
		EVIL_FRIENDLY = builder.comment("邪恶度：友善生物（Animal / 村民 / 流浪商人）")
				.defineInRange("evil_friendly", 1.0D, 0.0D, 1000.0D);
		EVIL_NEUTRAL = builder.comment("邪恶度：中立生物")
				.defineInRange("evil_neutral", 0.5D, 0.0D, 1000.0D);
		EVIL_HOSTILE = builder.comment("邪恶度：敌对生物")
				.defineInRange("evil_hostile", 0.1D, 0.0D, 1000.0D);
		EVIL_WARDEN_WITHER = builder.comment("邪恶度：监守者 / 凋灵")
				.defineInRange("evil_warden_wither", 10.0D, 0.0D, 10000.0D);
		EVIL_DRAGON = builder.comment("邪恶度：末影龙")
				.defineInRange("evil_dragon", 20.0D, 0.0D, 10000.0D);
		EVIL_DAILY_CAP = builder.comment("邪恶度：每游戏日增益总量上限")
				.defineInRange("evil_daily_cap", 100.0D, 0.0D, 100000.0D);
		EVIL_DAILY_DECAY = builder.comment("邪恶度：每游戏日自然衰减（6:00 刷新）")
				.defineInRange("evil_daily_decay", 5.0D, 0.0D, 10000.0D);
		EVIL_FRIENDLY_DAILY_CAP = builder.comment("邪恶度：友善档每游戏日的上限")
				.defineInRange("evil_friendly_daily_cap", 20.0D, 0.0D, 100000.0D);
		// 仪式法袍（1.6.1）：背饰栏饰品，签约后才可佩戴
		ENABLE_ROBE = builder.comment("仪式法袍：总开关（关闭后门槛与加成全部不生效）")
				.define("enable_robe", true);
		ROBE_BLACK_HEARTS = builder.comment("仪式法袍：黑心池上限加成（心数，1 心 = absorption_per_black_heart 点）")
				.defineInRange("robe_black_hearts", 2.0D, 0.0D, 100.0D);
		ROBE_ATTACK_DAMAGE = builder.comment("仪式法袍：攻击力加成（加法，与五芒星同款）")
				.defineInRange("robe_attack_damage", 2.0D, 0.0D, 1024.0D);
		ROBE_DAILY_DECAY = builder.comment("仪式法袍：佩戴时替换掉的「邪恶度每游戏日自然衰减」值")
				.defineInRange("robe_daily_decay", 3.0D, 0.0D, 10000.0D);
		// 撒旦圣经（1.6.1）：魂印栏饰品，由七罪之源在「已签约 + 七罪全部激活」时自动转化而来
		ENABLE_SATANIC_BIBLE = builder.comment("撒旦圣经：总开关（关闭后不转化、不给加成）")
				.define("enable_satanic_bible", true);
		SATANIC_BIBLE_BLACK_HEARTS = builder.comment("撒旦圣经：黑心池上限加成（心数）")
				.defineInRange("satanic_bible_black_hearts", 1.0D, 0.0D, 100.0D);
		SATANIC_REFILL_SECONDS = builder.comment("撒旦圣经：黑心补满的间隔秒数")
				.defineInRange("satanic_pact_refill_seconds", 30, 1, 3600);
		SATANIC_SHATTER_RADIUS = builder.comment("撒旦圣经：黑心被击碎时的影响半径（格）")
				.defineInRange("satanic_pact_shatter_radius", 18, 1, 64);
		SATANIC_SHATTER_DAMAGE = builder.comment("撒旦圣经：黑心被击碎时对范围内敌人的伤害")
				.defineInRange("satanic_pact_shatter_damage", 24.0D, 0.0D, 100000.0D);
		// 咒印（1.6.2）：灵台栏的恶魔侧饰品
		MARK_SHATTER_DAMAGE = builder.comment("咒印：佩戴时覆盖的黑心碎裂伤害（默认把 24 提到 40）")
				.defineInRange("mark_shatter_damage", 40.0D, 0.0D, 100000.0D);
		MARK_SHATTER_DAMAGE_ABADDON = builder
				.comment("咒印：同时佩戴「亚巴顿」时覆盖的黑心碎裂伤害（1.6.10 联动，默认 60）")
				.defineInRange("mark_shatter_damage_abaddon", 60.0D, 0.0D, 100000.0D);
		MARK_STOPS_EVIL_DECAY = builder.comment("咒印：佩戴时是否把邪恶度的每日自然衰减压到 0")
				.define("mark_stops_evil_decay", true);
		MARK_BLACK_HEARTS = builder.comment("咒印：黑心池上限加成（心数；灵魂的魂心转化而来）")
				.defineInRange("mark_black_hearts", 3.0D, 0.0D, 100.0D);
		// 复仇之魂（1.6.2）：加护栏的恶魔侧饰品
		VENGEFUL_HELLFIRE_DAMAGE = builder.comment("复仇之魂：每秒对半径内敌人造成的狱火伤害")
				.defineInRange("vengeful_hellfire_damage", 6.0D, 0.0D, 100000.0D);
		VENGEFUL_HELLFIRE_RADIUS = builder.comment("复仇之魂：狱火的作用半径（格）")
				.defineInRange("vengeful_hellfire_radius", 3, 1, 32);
		VENGEFUL_HELLFIRE_RADIUS_BRIMSTONE = builder
				.comment("复仇之魂：同时佩戴「硫磺火」时的作用半径（格）")
				.defineInRange("vengeful_hellfire_radius_brimstone", 4, 1, 32);
		VENGEFUL_HELLFIRE_RADIUS_ABADDON = builder
				.comment("复仇之魂：同时佩戴「亚巴顿」时的作用半径（格；优先于硫磺火那一档）")
				.defineInRange("vengeful_hellfire_radius_abaddon", 5, 1, 64);
		NIGHT_WRAITH_MOVEMENT_PERCENT = builder.comment("夜之幽魂：佩戴时的移动速度加成（百分比）")
				.defineInRange("night_wraith_movement_percent", 20.0D, 0.0D, 1000.0D);
		builder.pop();

		builder.comment("玄秘魔眼（1.6.5）：由夜之幽魂升级而来；注视判定与「思想：看向发光」共用同一套实现")
				.push("occult_eye");
		ENABLE_OCCULT_EYE = builder.comment("是否启用玄秘魔眼与「恐惧」").define("enable_occult_eye", true);
		FEAR_RADIUS = builder.comment("注视距离（格）；与思想的 glow_radius 是同一套射线判定，只是各自取值")
				.defineInRange("fear_radius", 24, 1, 128);
		FEAR_SECONDS = builder.comment("恐惧持续时间（秒），每秒刷新")
				.defineInRange("fear_seconds", 6, 1, 600);
		FEAR_AFFECTS_PLAYERS = builder.comment("恐惧是否对玩家生效（关掉则玩家只被发光/增伤，不吃减益）")
				.define("fear_affects_players", true);
		FEAR_GLOW = builder.comment("是否给注视目标施加发光").define("fear_glow", true);
		FEAR_GLOW_AFFECTS_PLAYERS = builder.comment("发光是否对玩家生效")
				.define("fear_glow_affects_players", true);
		FEAR_DAMAGE_MULTIPLIER = builder.comment("对注视目标的伤害倍率（1.3 = ×1.3；与伯列恒之星同层）")
				.defineInRange("fear_damage_multiplier", 1.3D, 1.0D, 100.0D);
		// 1.6.5 误用了「失明」，1.6.6 改成原版「黑暗」（DARKNESS）；键名一并改名。
		// 旧键保留并参与判定：老配置文件里若显式写过 fear_blindness = false，仍然尊重那次关闭。
		FEAR_DARKNESS = builder.comment("恐惧是否附带「黑暗」（原版 DARKNESS 效果）")
				.define("fear_darkness", true);
		FEAR_BLINDNESS_LEGACY = builder.comment("（已弃用；1.6.6 起请用 fear_darkness）恐惧是否附带黑暗")
				.define("fear_blindness", true);
		FEAR_MINING_FATIGUE_AMPLIFIER = builder.comment("恐惧：挖掘疲劳等级（amplifier；2 = 挖掘疲劳 III）")
				.defineInRange("fear_mining_fatigue_amplifier", 2, 0, 9);
		FEAR_SLOWNESS_AMPLIFIER = builder.comment("恐惧：缓慢等级（amplifier；5 = 缓慢 VI）")
				.defineInRange("fear_slowness_amplifier", 5, 0, 9);
		FEAR_WEAKNESS_AMPLIFIER = builder.comment("恐惧：虚弱等级（amplifier；9 = 虚弱 X）")
				.defineInRange("fear_weakness_amplifier", 9, 0, 9);
		FEAR_FREEZE_WITH_ABADDON = builder
				.comment("恐惧：同时佩戴「亚巴顿」时是否额外把目标移速归零（1.6.10 联动）")
				.define("fear_freeze_with_abaddon", true);
		builder.pop();

		builder.comment("深渊领主（1.6.7）：加护栏的恶魔侧饰品；佩戴时所有伤害会给目标附加「狱火」")
				.push("abyss_lord");
		ENABLE_ABYSS_LORD = builder.comment("是否启用深渊领主与「狱火」效果")
				.define("enable_abyss_lord", true);
		HELLFIRE_SECONDS = builder.comment("狱火持续时间（秒），每次命中刷新")
				.defineInRange("hellfire_seconds", 6, 1, 600);
		HELLFIRE_MAX_LEVEL = builder.comment("狱火等级上限（可配置）")
				.defineInRange("hellfire_max_level", 10, 1, 100);
		HELLFIRE_ARMOR_PERCENT_PER_LEVEL = builder
				.comment("狱火：每级降低的护甲值百分比（10 = 每级 -10%，10 级时护甲归零）")
				.defineInRange("hellfire_armor_percent_per_level", 10.0D, 0.0D, 100.0D);
		HELLFIRE_DAMAGE_PER_LEVEL_PER_SECOND = builder
				.comment("狱火：每级每秒造成的狱火伤害（1 = 等级 × 1 点/秒）")
				.defineInRange("hellfire_damage_per_level_per_second", 1.0D, 0.0D, 1000.0D);
		ENABLE_DEMON_FIRE_IMMUNITY = builder
				.comment("恶魔线（当前持恶魔标记）是否完全免疫火焰 / 岩浆 / 岩浆块地板伤害且不着火")
				.define("enable_demon_fire_immunity", true);
		HELLFIRE_HALVES_RESISTANCE_WITH_ABADDON = builder
				.comment("狱火：同时佩戴「亚巴顿」时，狱火满级是否把目标「抗性提升」等级减半并持续覆盖（1.6.10 联动）")
				.define("hellfire_max_level_halves_resistance_with_abaddon", true);
		builder.pop();

		builder.comment("亚巴顿（1.6.8）：启示之座的恶魔侧终极饰品；继承并强化硫磺火、死亡时拦截复活")
				.push("abaddon");
		ENABLE_ABADDON = builder.comment("是否启用亚巴顿").define("enable_abaddon", true);
		ABADDON_BEAM_CHARGE_SECONDS = builder
				.comment("亚巴顿继承的恶魔之焰：长按 V 的蓄力秒数（1.8.2 起专属 1.0 秒，硫磺火仍走 [brimstone]）")
				.defineInRange("beam_charge_seconds", 1.0D, 0.1D, 60.0D);
		ABADDON_BEAM_DAMAGE_PER_TICK = builder
				.comment("亚巴顿继承的恶魔之焰：每次结算的狱火（真伤）点数")
				.defineInRange("beam_damage_per_tick", 9, 0, 100000);
		ABADDON_BEAM_LENGTH = builder.comment("亚巴顿继承的恶魔之焰：火柱长度（格）")
				.defineInRange("beam_length", 32, 1, 256);
		ABADDON_BEAM_RADIUS = builder.comment("亚巴顿继承的恶魔之焰：火柱半径（格）")
				.defineInRange("beam_radius", 3, 0, 32);
		ABADDON_REVIVE_COOLDOWN_SECONDS = builder
				.comment("亚巴顿：被击杀复活后的冷却秒数")
				.defineInRange("revive_cooldown_seconds", 1200, 0, 86400);
		ABADDON_REVIVE_INVULNERABLE_SECONDS = builder
				.comment("亚巴顿：复活后的无敌与恶魔光环持续秒数")
				.defineInRange("revive_invulnerable_seconds", 8, 0, 600);
		ABADDON_AURA_RADIUS = builder.comment("恶魔光环：半径（格）")
				.defineInRange("aura_radius", 5.0D, 0.0D, 128.0D);
		ABADDON_AURA_DAMAGE_PER_TICK = builder
				.comment("恶魔光环：每 tick 对范围内目标造成的真实伤害")
				.defineInRange("aura_damage_per_tick", 6, 0, 100000);
		builder.pop();

		// 五芒星（1.5.8）：完成「罪无可赦」后自动发放，装进 Curios 自带的「护符」栏位
		builder.comment("五芒星（护符栏饰品，完成「罪无可赦」后发放）").push("pentagram");
		PENTAGRAM_ATTACK_DAMAGE = builder.comment("佩戴时的近战伤害加成（加法）")
				.defineInRange("attack_damage", 1.0D, 0.0D, 1024.0D);
		PENTAGRAM_MESSAGE_SECONDS = builder.comment("累计持有多少秒后发出那句「交易」聊天消息（终身一次）")
				.defineInRange("message_seconds", 300, 1, 86400);
		builder.pop();

		builder.comment("「光环」的属性加成（佩戴七罪之源且激活时减半，佩戴美德时翻倍）").push("the_halo");
		HALO_MAX_HEALTH = builder.comment("最大生命").defineInRange("max_health", 4.0D, 0.0D, 1024.0D);
		HALO_ATTACK_DAMAGE = builder.comment("攻击力").defineInRange("attack_damage", 2.0D, 0.0D, 1024.0D);
		HALO_ATTACK_SPEED = builder.comment("攻击速度").defineInRange("attack_speed", 0.2D, 0.0D, 64.0D);
		HALO_ARMOR = builder.comment("护甲值").defineInRange("armor", 2.0D, 0.0D, 1024.0D);
		HALO_ARMOR_TOUGHNESS = builder.comment("护甲韧性").defineInRange("armor_toughness", 2.0D, 0.0D, 1024.0D);
		HALO_MOVEMENT_PERCENT = builder.comment("移动速度（百分比）").defineInRange("movement_percent", 10, 0, 1000);
		HALO_BREAK_SPEED_PERCENT = builder.comment("挖掘速度（百分比）").defineInRange("break_speed_percent", 10, 0, 1000);
		HALO_SIN_MULTIPLIER = builder
				.comment("佩戴七罪之源且至少激活一项时，整套光环属性的倍率")
				.defineInRange("sin_multiplier", 0.5D, 0.0D, 100.0D);
		HALO_VIRTUE_MULTIPLIER = builder
				.comment("佩戴美德时，整套光环属性的倍率")
				.defineInRange("virtue_multiplier", 2.0D, 0.0D, 100.0D);
		HALO_REGEN_THRESHOLD_PERCENT = builder
				.comment("1.7.1：已获取启示时，生命值低于该百分比 → 获得「生命回复」（原版 regeneration）")
				.defineInRange("regen_health_threshold_percent", 50, 1, 100);
		HALO_REGEN_AMPLIFIER = builder.comment("1.7.1：生命回复的等级（amplifier；0 = 生命回复 I）")
				.defineInRange("regen_amplifier", 0, 0, 9);
		builder.pop();

		builder.comment("恶魔王冠（1.7.1）：光环栏饰品，需佩戴撒旦圣经才生效；邪恶 666 后追加受伤加成")
				.push("devil_crown");
		ENABLE_DEVIL_CROWN = builder.comment("恶魔王冠：总开关").define("enable_devil_crown", true);
		DEVIL_CROWN_MOVEMENT_PERCENT = builder.comment("移动速度加成（百分比）")
				.defineInRange("movement_percent", 20, 0, 1000);
		DEVIL_CROWN_ATTACK_SPEED = builder.comment("攻击速度加成（加法）")
				.defineInRange("attack_speed", 0.4D, 0.0D, 64.0D);
		DEVIL_CROWN_MELEE_DAMAGE = builder.comment("近战伤害加成（加法）")
				.defineInRange("melee_damage", 6.0D, 0.0D, 1024.0D);
		DEVIL_CROWN_MAX_HEALTH = builder.comment("最大生命加成（负值 = 减益）")
				.defineInRange("max_health", -4.0D, -1024.0D, 1024.0D);
		DEVIL_CROWN_ARMOR = builder.comment("护甲值加成（负值 = 减益）")
				.defineInRange("armor", -4.0D, -1024.0D, 1024.0D);
		DEVIL_CROWN_ARMOR_TOUGHNESS = builder.comment("护甲韧性加成（负值 = 减益）")
				.defineInRange("armor_toughness", -4.0D, -1024.0D, 1024.0D);
		DEVIL_CROWN_DAMAGE_PER_DECILE_PERCENT = builder
				.comment("邪恶 666 后：每损失 10% 生命值提升的造成伤害（百分比）")
				.defineInRange("damage_per_lost_decile_percent", 3.0D, 0.0D, 1000.0D);
		DEVIL_CROWN_DAMAGE_CAP_PERCENT = builder.comment("上述加成的上限（百分比）")
				.defineInRange("damage_bonus_cap_percent", 15.0D, 0.0D, 10000.0D);
		builder.pop();

		builder.comment("遁入暗影（1.7.5）：献祭匕首 / 暗仪刺刀的右键技能")
				.push("shadow_dash");
		ENABLE_SHADOW_DASH = builder.comment("遁入暗影：总开关").define("enable_shadow_dash", true);
		SHADOW_SACRIFICIAL_DURATION_TICKS = builder
				.comment("【1.8.2 起废弃】献祭匕首已改走「献祭」技能，本键仅保留兼容旧配置；判定时长（tick，20 = 1 秒）")
				.defineInRange("sacrificial_duration_ticks", 20, 1, 200);
		SHADOW_DARK_ARTS_DURATION_TICKS = builder.comment("暗仪刺刀：技能判定时长（tick，40 = 2 秒）")
				.defineInRange("dark_arts_duration_ticks", 40, 1, 200);
		SHADOW_SACRIFICIAL_MULTIPLIER = builder
				.comment("【1.8.2 起废弃】献祭匕首已改走「献祭」技能，本键仅保留兼容旧配置；基础斩击倍率（乘玩家近战面板伤害）")
				.defineInRange("sacrificial_slash_multiplier", 1.0D, 0.0D, 100.0D);
		SHADOW_DARK_ARTS_MULTIPLIER = builder.comment("暗仪刺刀：基础斩击倍率（乘玩家近战面板伤害）")
				.defineInRange("dark_arts_slash_multiplier", 2.0D, 0.0D, 100.0D);
		SHADOW_CONTACT_RADIUS = builder.comment("接触判定半径（格）：技能期间在这个距离内即视为触碰")
				.defineInRange("contact_radius", 2.0D, 0.5D, 16.0D);
		SHADOW_HEAVY_RADIUS = builder.comment("强力斩击半径（格）")
				.defineInRange("heavy_slash_radius", 3.0D, 0.5D, 16.0D);
		SHADOW_MOVEMENT_PERCENT = builder.comment("技能判定期间的移动速度加成（百分比）")
				.defineInRange("movement_percent", 100, 0, 1000);
		SHADOW_COOLDOWN_TICKS = builder.comment("冷却（tick，120 = 6 秒；从无敌解除开始计时）")
				.defineInRange("cooldown_ticks", 120, 0, 24000);
		SHADOW_RESOLVE_INTERVAL_TICKS = builder.comment("逐个结算标记目标的间隔（tick）")
				.defineInRange("resolve_interval_ticks", 2, 1, 40);
		DAGGER_RECOVERY_SECONDS = builder
				.comment("献祭匕首：连续多少秒身上没有任何一把仪式匕首后，开放「防丢失配方」（默认 300 = 5 分钟）")
				.defineInRange("recovery_missing_seconds", 300, 1, 86400);
		SHADOW_ABADDON_DURATION_TICKS = builder
				.comment("亚巴顿联动：佩戴亚巴顿时技能判定时长加成（tick，20 = 1 秒，1.7.10）")
				.defineInRange("abaddon_duration_bonus_ticks", 20, 0, 600);
		SHADOW_ABADDON_CONTACT_RADIUS = builder
				.comment("亚巴顿联动：佩戴亚巴顿时接触判定半径加成（格，1.7.10）")
				.defineInRange("abaddon_contact_radius_bonus", 1.0D, 0.0D, 16.0D);
		SHADOW_ABADDON_HEAVY_RADIUS = builder
				.comment("亚巴顿联动：佩戴亚巴顿时强力斩击半径加成（格，1.7.10）")
				.defineInRange("abaddon_heavy_radius_bonus", 2.0D, 0.0D, 16.0D);
		builder.pop();

		builder.comment("献祭（1.8.2）：献祭匕首的右键技能 —— 自损换近战增伤，随时间线性衰减")
				.push("sacrifice");
		ENABLE_SACRIFICE = builder.comment("献祭：总开关")
				.define("enable_sacrifice", true);
		SACRIFICE_SELF_DAMAGE = builder
				.comment("献祭：对自身造成的伤害点数（2 心 = 4；结算前钳到「当前生命 − 1」，绝不致死）")
				.defineInRange("self_damage", 4.0D, 0.0D, 1024.0D);
		SACRIFICE_DAMAGE_PERCENT = builder
				.comment("献祭：近战增伤（百分比；只对严格左键近战生效）")
				.defineInRange("damage_percent", 40, 0, 1000);
		SACRIFICE_DURATION_SECONDS = builder
				.comment("献祭：增伤持续秒数（从满值线性衰减到 0）")
				.defineInRange("duration_seconds", 8.0D, 0.1D, 600.0D);
		SACRIFICE_COOLDOWN_SECONDS = builder
				.comment("献祭：冷却秒数（右键成功后计入物品冷却，图标会显示冷却扇形）")
				.defineInRange("cooldown_seconds", 8, 0, 86400);
		builder.pop();

		builder.comment("神圣行动（1.8.2）：神性「回溯」/ 亚巴顿「恶魔形态」的 X 键蓄力")
				.push("divine_action");
		DIVINE_ACTION_CHARGE_SECONDS = builder
				.comment("X 技能：长按蓄力秒数（满蓄力才生效，松手取消）")
				.defineInRange("charge_seconds", 1.0D, 0.1D, 60.0D);
		builder.pop();

		builder.comment("金刀片（1.7.10：无属性武器，右键投掷、固定伤害、可穿透生物）").push("golden_razor");
		ENABLE_GOLDEN_RAZOR = builder.comment("金刀片：总开关")
				.define("enable_golden_razor", true);
		RAZOR_DAMAGE = builder.comment("金刀片：命中伤害点数（不吃任何加成，但仍受护甲 / 保护 / 抗性减免）")
				.defineInRange("razor_damage", 5.0D, 0.0D, 10000.0D);
		RAZOR_COOLDOWN_TICKS = builder.comment("金刀片：投掷间隔（tick，10 = 0.5 秒）")
				.defineInRange("razor_cooldown_ticks", 10, 0, 24000);
		// 1.7.10 收尾：默认出手速度 1.5 → 1.8（飞行略快一些）
		RAZOR_VELOCITY = builder.comment("金刀片：出手速度")
				.defineInRange("razor_velocity", 1.8D, 0.1D, 20.0D);
		RAZOR_PIERCE_LEVEL = builder.comment("金刀片：穿透等级（原版口径：可穿透 等级+1 个生物）")
				.defineInRange("razor_pierce_level", 127, 0, 127);
		RAZOR_MAX_LIFE_TICKS = builder.comment("金刀片：幻影存活上限（tick，超时强制删除）")
				.defineInRange("razor_max_life_ticks", 200, 20, 12000);
		// 1.7.10 收尾：命中方块后插在原地停留 5 秒（100 tick）再清除
		RAZOR_STUCK_TICKS = builder.comment("金刀片：命中方块后插在原地停留多久再清除（tick，100 = 5 秒）")
				.defineInRange("razor_stuck_ticks", 100, 20, 12000);
		builder.pop();
		builder.comment("启示之座（伯列恒之星 / 终末天启）相关设置").push("revelation");
		REVEAL_SECONDS = builder.comment("累计佩戴多少秒后揭示坐标")
				.defineInRange("reveal_seconds", 600, 1, 86400);
		REVEAL_RADIUS = builder.comment("坐标距世界出生点的最大半径，单位：格")
				.defineInRange("coordinate_radius", 1000, 1, 100000);
		STAR_ATTACK_SPEED_PERCENT = builder.comment("伯列恒之星 / 终末天启：攻击速度加成（百分比）")
				.defineInRange("attack_speed_percent", 20, 0, 1000);
		STAR_DAMAGE_PERCENT = builder.comment("伯列恒之星 / 终末天启：造成伤害加成（百分比）")
				.defineInRange("damage_percent", 20, 0, 1000);
		FINAL_SOUL_HEARTS = builder.comment("终末天启：提供的魂心数量（每 1 魂心 = 2 点吸收）")
				.defineInRange("final_soul_hearts", 2, 0, 1024);
		FLIGHT_SPEED_MULTIPLIER = builder
				.comment("终末天启：创造飞行的速度倍率（原版飞行速度 0.05 的倍数，0.5 = 速度减半）")
				.defineInRange("flight_speed_multiplier", 0.5D, 0.0D, 10.0D);
		TRANSFORM_RADIUS = builder.comment("终末天启：转化判定中距揭示坐标的水平容差，单位：格")
				.defineInRange("transform_radius", 8, 1, 128);
		TRANSFORM_SECONDS = builder.comment("终末天启：满足条件需要连续保持的秒数")
				.defineInRange("transform_seconds", 5, 1, 3600);
		TRANSFORM_MAX_MOVE_BLOCKS = builder
				.comment("终末天启：转化期间允许的最大位移（格）；位移超过该值会重新计时（要求保持静止）")
				.defineInRange("transform_max_move_blocks", 1.0D, 0.05D, 64.0D);
		BEAM_LENGTH = builder.comment("启示之光：光柱长度，单位：格")
				.defineInRange("beam_length", 21, 1, 128);
		BEAM_RADIUS = builder.comment("启示之光：光柱半径，单位：格")
				.defineInRange("beam_radius", 2, 0, 32);
		BEAM_DAMAGE_PER_TICK = builder
				.comment("启示之光：每一次结算对光束内生物的伤害（无视护甲/附魔保护/抗性，先扣黄血）")
				.defineInRange("beam_damage_per_tick", 7, 0, 10000);
		BEAM_DURATION_SECONDS = builder.comment("启示之光：照射持续时间，单位秒（默认 1.5 秒）")
				.defineInRange("beam_duration_seconds", 1.5D, 0.1D, 60.0D);
		BEAM_DAMAGE_INTERVAL_SECONDS = builder.comment("启示之光：两次结算之间的间隔，单位秒（默认 0.1 秒）")
				.defineInRange("beam_damage_interval_seconds", 0.1D, 0.05D, 10.0D);
		BEAM_CHARGE_SECONDS = builder.comment("启示之光：长按 V 的蓄力秒数")
				.defineInRange("beam_charge_seconds", 2.5D, 0.1D, 60.0D);
		BEAM_COOLDOWN_SECONDS = builder.comment("启示之光：冷却秒数（从发射成功算起）")
				.defineInRange("beam_cooldown_seconds", 10, 0, 3600);
		BEAM_BROADCAST_RADIUS = builder.comment("启示之光：光柱渲染的广播半径，单位：格")
				.defineInRange("beam_broadcast_radius", 128, 8, 1024);
		CHARGE_FOV_SCALE = builder
				.comment("启示之光：蓄力时屏幕视野（FOV）最多收缩的比例，0.15 = 收缩 15%（类似拉弓）")
				.defineInRange("charge_fov_scale", 0.15D, 0.0D, 0.9D);
		builder.pop();

		SPEC = builder.build();
	}

	private ReliquaryConfig() {
	}

	/** 配置还没加载时退回默认值，避免启动早期读配置报错 */
	private static int intOr(ForgeConfigSpec.IntValue value, int fallback) {
		try {
			return value.get();
		} catch (IllegalStateException exception) {
			return fallback;
		}
	}

	private static double doubleOr(ForgeConfigSpec.DoubleValue value, double fallback) {
		try {
			return value.get();
		} catch (IllegalStateException exception) {
			return fallback;
		}
	}

	/** 吸附半径（格） */
	public static int radius() {
		return intOr(RADIUS, DEFAULT_RADIUS);
	}

	public static int bodyHealth() {
		return intOr(BODY_HEALTH, 10);
	}

	public static int bodySetDamageReductionPercent() {
		return intOr(BODY_SET_DAMAGE_REDUCTION_PERCENT, 20);
	}

	public static boolean enableInvulnerabilityChange() {
		try {
			return ENABLE_INVULNERABILITY_CHANGE.get();
		} catch (IllegalStateException exception) {
			return true;
		}
	}

	public static int invulnerabilityTicks() {
		return intOr(INVULNERABILITY_TICKS, 5);
	}

	/** 帧伤 / 持续环境伤害的有效无敌窗口（tick） */
	public static int invulnerabilityTicksFrameDamage() {
		return intOr(INVULNERABILITY_TICKS_FRAME_DAMAGE, 10);
	}

	/** 额外按「帧伤」处理的伤害类型 id 列表 */
	public static List<? extends String> frameDamageIds() {
		try {
			return FRAME_DAMAGE_IDS.get();
		} catch (IllegalStateException exception) {
			return List.of();
		}
	}

	/** 是否每 5 秒汇总一条受伤节流日志 */
	public static boolean logIframeThrottle() {
		return boolOr(LOG_IFRAME_THROTTLE, true);
	}

	public static double maxAttackSpeed() {
		return doubleOr(MAX_ATTACK_SPEED, 4.0D);
	}

	public static int glowRadius() {
		// 1.8.3：兜底值与注册默认值统一为 24（旧注释与兜底值写的 15 是过期数据）
		return intOr(GLOW_RADIUS, 24);
	}

	public static int mindBonusPercent() {
		return intOr(MIND_BONUS_PERCENT, 10);
	}

	public static double soulHearts() {
		return intOr(SOUL_HEARTS, 3);
	}

	/** 每点魂心换算的黄血量 */
	public static double absorptionPerSoulHeart() {
		return doubleOr(ABSORPTION_PER_SOUL_HEART, 2.0D);
	}

	/** 魂心 HUD：是否启用自绘魂心 */
	public static boolean enableSoulHeartHud() {
		return boolOr(ENABLE_SOUL_HEART_HUD, true);
	}

	/**
	 * 魂心 HUD 的布局模式（1.8.5）：`auto` / `vanilla` / `single_row`，统一小写。
	 *
	 * <p>非法值一律按 `auto` 处理，避免手改配置文件写出拼写错误时 HUD 直接消失。
	 */
	public static String soulHeartHudLayout() {
		String raw;
		try {
			raw = SOUL_HEART_HUD_LAYOUT.get();
		} catch (IllegalStateException exception) {
			return "auto";
		}
		if (raw == null) {
			return "auto";
		}
		String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
		return switch (normalized) {
			case "vanilla", "single_row", "classic_row" -> normalized;
			default -> "auto";
		};
	}

	/** 魂心 HUD 的额外纵向偏移（像素，正数向下） */
	public static int soulHeartHudOffsetY() {
		return intOr(SOUL_HEART_HUD_OFFSET_Y, 0);
	}

	/** 魂心破碎：击退强度 */
	public static double soulShatterKnockback() {
		return doubleOr(SOUL_SHATTER_KNOCKBACK, 2.0D);
	}

	/** 魂心破碎：作用半径（格） */
	public static double soulShatterRadius() {
		return intOr(SOUL_SHATTER_RADIUS, 7);
	}

	/** 魂心破碎：无敌秒数 */
	public static double soulShatterInvulnerableSeconds() {
		return doubleOr(SOUL_SHATTER_INVULNERABLE_SECONDS, 5.0D);
	}

	public static int soulRefreshSeconds() {
		return intOr(SOUL_REFRESH_SECONDS, 30);
	}

	/** 三件套齐时的免死几率（百分比） */
	public static int deathImmunityPercent() {
		return intOr(DEATH_IMMUNITY_PERCENT, 20);
	}

	// ==================== 七罪效果 ====================

	private static boolean boolOr(ForgeConfigSpec.BooleanValue value, boolean fallback) {
		try {
			return value.get();
		} catch (IllegalStateException exception) {
			return fallback;
		}
	}

	/** 七罪效果总开关 */
	public static boolean enableSinEffects() {
		return boolOr(ENABLE_SIN_EFFECTS, true);
	}

	public static int prideKillRequired() {
		return intOr(PRIDE_KILL_REQUIRED, 10);
	}

	public static double prideDamagePerPercent() {
		return doubleOr(PRIDE_DAMAGE_PER_PERCENT, 1.0D);
	}

	public static int prideIncomingDamagePercent() {
		return intOr(PRIDE_INCOMING_DAMAGE_PERCENT, 50);
	}

	public static int envyObserveRadius() {
		return intOr(ENVY_OBSERVE_RADIUS, 16);
	}

	public static int envyBonusPercent() {
		return intOr(ENVY_BONUS_PERCENT, 50);
	}

	public static int envyHostileRadius() {
		return intOr(ENVY_HOSTILE_RADIUS, 8);
	}

	public static int envyHostileRefreshTicks() {
		return intOr(ENVY_HOSTILE_REFRESH_TICKS, 20);
	}

	public static int wrathKillRequired() {
		return intOr(WRATH_KILL_REQUIRED, 100);
	}

	public static double wrathRandomMin() {
		return doubleOr(WRATH_RANDOM_MIN, 0.5D);
	}

	public static double wrathRandomMax() {
		return doubleOr(WRATH_RANDOM_MAX, 1.5D);
	}

	public static double wrathRandomMaxRedeemed() {
		return doubleOr(WRATH_RANDOM_MAX_REDEEMED, 2.0D);
	}

	public static int wrathSelfHitPercent() {
		return intOr(WRATH_SELF_HIT_PERCENT, 15);
	}

	/** 暴怒自伤倍率（1.8.0；自伤只会把血量打到 1 点，永不致死） */
	public static double wrathSelfHitMultiplier() {
		return doubleOr(WRATH_SELF_HIT_MULTIPLIER, 0.5D);
	}

	public static int slothSleepRequired() {
		return intOr(SLOTH_SLEEP_REQUIRED, 3);
	}

	/** 「早睡」的判定小时（20 = 晚上 8 点）：入睡时刻的游戏时钟早于该小时才算 */
	public static int slothSleepBeforeHour() {
		return intOr(SLOTH_SLEEP_BEFORE_HOUR, 20);
	}

	public static int slothResistanceAmplifier() {
		return intOr(SLOTH_RESISTANCE_AMPLIFIER, 0);
	}

	public static int slothResistanceAmplifierRedeemed() {
		return intOr(SLOTH_RESISTANCE_AMPLIFIER_REDEEMED, 1);
	}

	public static int slothSlowdownPercent() {
		return intOr(SLOTH_SLOWDOWN_PERCENT, 20);
	}

	public static int greedDiamondThreshold() {
		return intOr(GREED_DIAMOND_THRESHOLD, 36);
	}

	/** 「纯洁无瑕」：要求的最低佩戴率（百分比） */
	public static int flawlessMinWearingPercent() {
		return intOr(FLAWLESS_MIN_WEARING_PERCENT, 99);
	}

	/** 「纯洁无瑕」：开局宽限（秒） */
	public static int flawlessUnwornGraceSeconds() {
		return intOr(FLAWLESS_UNWORN_GRACE_SECONDS, 120);
	}

	public static int greedDamagePerDiamondPercent() {
		return intOr(GREED_DAMAGE_PER_DIAMOND_PERCENT, 1);
	}

	public static int greedDamageCapPercent() {
		return intOr(GREED_DAMAGE_CAP_PERCENT, 64);
	}

	public static double greedLowDiamondDamageFactor() {
		return doubleOr(GREED_LOW_DIAMOND_DAMAGE_FACTOR, 0.10D);
	}

	public static int greedDeathDiamondMin() {
		return intOr(GREED_DEATH_DIAMOND_MIN, 3);
	}

	public static int greedDeathDiamondMax() {
		return intOr(GREED_DEATH_DIAMOND_MAX, 8);
	}

	public static int gluttonyMealRequired() {
		return intOr(GLUTTONY_MEAL_REQUIRED, 10);
	}

	public static double gluttonySaturationThreshold() {
		return doubleOr(GLUTTONY_SATURATION_THRESHOLD, 8.0D);
	}

	public static double gluttonyKillHeal() {
		return doubleOr(GLUTTONY_KILL_HEAL, 2.0D);
	}

	public static int gluttonyKillFood() {
		return intOr(GLUTTONY_KILL_FOOD, 1);
	}

	public static int gluttonyFoodCap() {
		return intOr(GLUTTONY_FOOD_CAP, 18);
	}

	public static int gluttonyDrainSeconds() {
		return intOr(GLUTTONY_DRAIN_SECONDS, 20);
	}

	public static int gluttonyWeakFoodThreshold() {
		return intOr(GLUTTONY_WEAK_FOOD_THRESHOLD, 10);
	}

	public static int lustBreedRequired() {
		return intOr(LUST_BREED_REQUIRED, 10);
	}

	public static int lustStripArmorPercent() {
		return intOr(LUST_STRIP_ARMOR_PERCENT, 15);
	}

	public static int lustArmorReductionPercent() {
		return intOr(LUST_ARMOR_REDUCTION_PERCENT, 30);
	}

	public static int lustSelfStripPercent() {
		return intOr(LUST_SELF_STRIP_PERCENT, 15);
	}

	// ==================== 七德加成 ====================

	public static boolean enableVirtueEffects() {
		return boolOr(ENABLE_VIRTUE_EFFECTS, true);
	}

	public static int humilityDamageBonusPercent() {
		return intOr(HUMILITY_DAMAGE_BONUS_PERCENT, 15);
	}

	public static int charitySpeedPercentPerDrop() {
		return intOr(CHARITY_SPEED_PERCENT_PER_DROP, 2);
	}

	public static int charitySpeedCapPercent() {
		return intOr(CHARITY_SPEED_CAP_PERCENT, 10);
	}

	public static int charityDurationSeconds() {
		return intOr(CHARITY_DURATION_SECONDS, 30);
	}

	public static int chastityDurabilityPerSecond() {
		return intOr(CHASTITY_DURABILITY_PER_SECOND, 1);
	}

	public static double kindnessHealPerSecond() {
		return doubleOr(KINDNESS_HEAL_PER_SECOND, 1.0D);
	}

	public static int kindnessRadius() {
		return intOr(KINDNESS_RADIUS, 7);
	}

	public static int patienceBonusPercentPerHit() {
		return intOr(PATIENCE_BONUS_PERCENT_PER_HIT, 6);
	}

	public static int patienceCapPercent() {
		return intOr(PATIENCE_CAP_PERCENT, 30);
	}

	public static int patienceResetSeconds() {
		return intOr(PATIENCE_RESET_SECONDS, 4);
	}

	public static int diligenceSpeedPercent() {
		return intOr(DILIGENCE_SPEED_PERCENT, 10);
	}

	// ==================== 加护（救恩的领域） ====================

	public static double salvationRadius() {
		return doubleOr(SALVATION_RADIUS, 3.0D);
	}

	public static double salvationExtendedRadius() {
		return doubleOr(SALVATION_RADIUS_EXTENDED, 4.0D);
	}

	public static double salvationLockSeconds() {
		return doubleOr(SALVATION_LOCK_SECONDS, 1.0D);
	}

	public static int salvationDamage() {
		return intOr(SALVATION_DAMAGE, 7);
	}

	public static double salvationCooldownSeconds() {
		return doubleOr(SALVATION_COOLDOWN_SECONDS, 0.5D);
	}

	// ==================== 圣光 / 神圣斗篷（1.5.4） ====================

	public static boolean enableHolyLight() {
		return boolOr(ENABLE_HOLY_LIGHT, true);
	}

	public static int holyLightChancePercent() {
		return intOr(HOLY_LIGHT_CHANCE_PERCENT, 15);
	}

	/** 圣光：与神性同戴时的触发几率（百分比，1.6.10 联动） */
	public static int holyLightChancePercentGodhead() {
		return intOr(HOLY_LIGHT_CHANCE_PERCENT_GODHEAD, 25);
	}

	public static int holyLightDamagePercent() {
		return intOr(HOLY_LIGHT_DAMAGE_PERCENT, 120);
	}

	/** 圣光：手持圣光短矛时的独立触发几率（百分比，1.7.9） */
	public static int holyLightChancePercentSpear() {
		return intOr(HOLY_LIGHT_CHANCE_PERCENT_SPEAR, 10);
	}

	/** 炽天使之枪：投掷命中落点的圣光爆发真伤（1.7.9） */
	public static int holyLightBurstDamage() {
		return intOr(HOLY_LIGHT_BURST_DAMAGE, 14);
	}

	/** 炽天使之枪：落点圣光爆发的半径（格，1.7.9） */
	public static int holyLightBurstRadius() {
		return intOr(HOLY_LIGHT_BURST_RADIUS, 4);
	}

	/** 炽天使之枪 + 神性：落点爆发的半径加成（格，1.7.10 联动） */
	public static int holyLightBurstRadiusGodhead() {
		return intOr(HOLY_LIGHT_BURST_RADIUS_GODHEAD, 1);
	}

	/** 炽天使之枪 + 神性：落点爆发的伤害加成（点，1.7.10 联动） */
	public static int holyLightBurstDamageGodhead() {
		return intOr(HOLY_LIGHT_BURST_DAMAGE_GODHEAD, 2);
	}

	public static boolean enableHolyMantle() {
		return boolOr(ENABLE_HOLY_MANTLE, true);
	}

	/** 神圣斗篷的无敌窗口（tick）：秒数 × 20 */
	public static int holyMantleInvulnerableTicks() {
		return Math.max(1, (int) Math.round(doubleOr(HOLY_MANTLE_INVULNERABLE_SECONDS, 1.0D) * 20.0D));
	}

	/** 神圣斗篷 + 神性（联动）的无敌窗口（tick）：秒数 × 20 */
	public static int holyMantleInvulnerableTicksGodhead() {
		return Math.max(1,
				(int) Math.round(doubleOr(HOLY_MANTLE_INVULNERABLE_SECONDS_GODHEAD, 1.5D) * 20.0D));
	}

	// ==================== 圣心（1.5.5） ====================

	public static double sacredHeartMaxHealth() {
		return doubleOr(SACRED_HEART_MAX_HEALTH, 10.0D);
	}

	public static double sacredHeartArmor() {
		return doubleOr(SACRED_HEART_ARMOR, 5.0D);
	}

	public static double sacredHeartToughness() {
		return doubleOr(SACRED_HEART_TOUGHNESS, 5.0D);
	}

	public static double sacredHeartAttackSpeed() {
		return doubleOr(SACRED_HEART_ATTACK_SPEED, 0.5D);
	}

	public static int sacredHeartMovementPercent() {
		return intOr(SACRED_HEART_MOVEMENT_PERCENT, 15);
	}

	public static int sacredHeartBreakSpeedPercent() {
		return intOr(SACRED_HEART_BREAK_SPEED_PERCENT, 15);
	}

	public static int sacredHeartDamagePercent() {
		return intOr(SACRED_HEART_DAMAGE_PERCENT, 30);
	}

	public static double sacredHeartArrowRadius() {
		return doubleOr(SACRED_HEART_ARROW_RADIUS, 8.0D);
	}

	/** 圣心 + 神性（联动）的箭矢追踪半径（格） */
	public static double sacredHeartArrowRadiusGodhead() {
		return doubleOr(SACRED_HEART_ARROW_RADIUS_GODHEAD, 12.0D);
	}

	/** 圣心：投掷初速加成（百分比，1.7.10 联动） */
	public static int sacredHeartThrowSpeedPercent() {
		return intOr(SACRED_HEART_THROW_SPEED_PERCENT, 25);
	}

	public static boolean enableArrowHoming() {
		return boolOr(ENABLE_ARROW_HOMING, true);
	}

	// ==================== 神性（1.5.5） ====================

	public static boolean enableGodhead() {
		return boolOr(ENABLE_GODHEAD, true);
	}

	public static int godheadBeamChargeTicks() {
		return Math.max(1, (int) Math.round(doubleOr(GODHEAD_BEAM_CHARGE_SECONDS, 1.5D) * 20.0D));
	}

	public static int godheadBeamCooldownTicks() {
		return Math.max(0, (int) Math.round(doubleOr(GODHEAD_BEAM_COOLDOWN_SECONDS, 5.0D) * 20.0D));
	}

	public static int godheadBeamRadius() {
		return intOr(GODHEAD_BEAM_RADIUS, 3);
	}

	public static int godheadBeamLength() {
		return intOr(GODHEAD_BEAM_LENGTH, 35);
	}

	public static double godheadSalvationRadius() {
		return doubleOr(GODHEAD_SALVATION_RADIUS, 5.0D);
	}

	public static double godheadAuraRadius() {
		return doubleOr(GODHEAD_AURA_RADIUS, 8.0D);
	}

	public static int godheadAuraDamage() {
		return intOr(GODHEAD_AURA_DAMAGE, 2);
	}

	public static int godheadDamagePercent() {
		return intOr(GODHEAD_DAMAGE_PERCENT, 20);
	}

	// ==================== 训练人偶 / 启示之光 / 心之碎片（1.5.6） ====================

	/** 救恩领域与神性光环是否把训练人偶当作目标 */
	public static boolean affectTargetDummies() {
		return boolOr(AFFECT_TARGET_DUMMIES, true);
	}

	public static boolean enableRevelationLightBuff() {
		return boolOr(ENABLE_REVELATION_LIGHT_BUFF, true);
	}

	/** 启示之光 buff 时长（tick） */
	public static int revelationLightBuffTicks() {
		return Math.max(1, (int) Math.round(doubleOr(REVELATION_LIGHT_BUFF_SECONDS, 7.0D) * 20.0D));
	}

	public static boolean enableHeartShardDrop() {
		return boolOr(ENABLE_HEART_SHARD_DROP, true);
	}

	public static double heartShardDropChance() {
		return doubleOr(HEART_SHARD_DROP_CHANCE, 0.5D);
	}

	public static double heartShardBossChance() {
		return doubleOr(HEART_SHARD_BOSS_CHANCE, 5.0D);
	}

	public static double heartShardDragonChance() {
		return doubleOr(HEART_SHARD_DRAGON_CHANCE, 20.0D);
	}

	/** 心之碎片：是否打印判定过程日志 */
	public static boolean logDropChecks() {
		return boolOr(LOG_DROP_CHECKS, true);
	}

	// ==================== 恶魔交易（1.5.9） ====================

	public static boolean enableDemonDeal() {
		return boolOr(ENABLE_DEMON_DEAL, true);
	}

	/** 灵魂沙峡谷驻留秒数 */
	public static int demonBiomeDwellSeconds() {
		return intOr(DEMON_BIOME_DWELL_SECONDS, 5);
	}

	/** 签约蓄力 tick 数 */
	public static int demonChargeTicks() {
		return Math.max(1, (int) Math.round(doubleOr(DEMON_CHARGE_SECONDS, 5.0D) * 20.0D));
	}

	/** 逐行台词的间隔（tick） */
	public static int demonLineDelayTicks() {
		return Math.max(1, intOr(DEMON_LINE_DELAY_TICKS, 40));
	}

	/** 蓄力时视野最多收缩的比例（0.20 = 20%） */
	public static double demonChargeFovScale() {
		return doubleOr(DEMON_CHARGE_FOV_SCALE, 0.20D);
	}

	/**
	 * 自检用：直接读配置项的**新默认值**（不受"已存在的旧配置文件不会被改写"影响）。
	 */
	public static int demonLineDelayDefaultTicks() {
		return ((Number) DEMON_LINE_DELAY_TICKS.getDefault()).intValue();
	}

	/** 自检用：直接读 {@code charge_fov_scale} 的新默认值 */
	public static double demonChargeFovDefaultScale() {
		return ((Number) DEMON_CHARGE_FOV_SCALE.getDefault()).doubleValue();
	}

	// ==================== 恶魔契约 / 邪恶度（1.6.0） ====================

	public static boolean enableDemonPact() {
		return boolOr(ENABLE_DEMON_PACT, true);
	}

	/** 献祭时对村民追加的真实伤害 */
	public static double pactVillagerSacrificeDamage() {
		return doubleOr(PACT_VILLAGER_DAMAGE, 10000.0D);
	}

	/** 黑心心数 */
	public static double pactBlackHearts() {
		return doubleOr(PACT_BLACK_HEARTS, 2.0D);
	}

	/** 每颗黑心的吸收点数 */
	public static double pactAbsorptionPerBlackHeart() {
		return doubleOr(PACT_ABSORPTION_PER_HEART, 2.0D);
	}

	/** 黑心池上限（点） */
	public static double pactBlackHeartMaxPoints() {
		return pactBlackHearts() * pactAbsorptionPerBlackHeart();
	}

	public static double pactDamagePercent() {
		return doubleOr(PACT_DAMAGE_PERCENT, 16.0D);
	}

	public static double pactAttackSpeedPercent() {
		return doubleOr(PACT_ATTACK_SPEED_PERCENT, 16.0D);
	}

	public static double pactHealthPerCursePercent() {
		return doubleOr(PACT_HEALTH_PER_CURSE_PERCENT, 2.0D);
	}

	public static double pactSpeedPerCursePercent() {
		return doubleOr(PACT_SPEED_PER_CURSE_PERCENT, 2.0D);
	}

	public static int pactLifestealCurseThreshold() {
		return intOr(PACT_LIFESTEAL_CURSE_THRESHOLD, 5);
	}

	public static double pactLifestealPercent() {
		return doubleOr(PACT_LIFESTEAL_PERCENT, 6.0D);
	}

	// ===== 仪式法袍 / 撒旦圣经（1.6.1） =====

	/** 仪式法袍：总开关 */
	public static boolean enableRobe() {
		return boolOr(ENABLE_ROBE, true);
	}

	/** 仪式法袍：黑心池上限加成（心数） */
	public static double robeBlackHearts() {
		return doubleOr(ROBE_BLACK_HEARTS, 2.0D);
	}

	/** 仪式法袍：攻击力加成（加法） */
	public static double robeAttackDamage() {
		return doubleOr(ROBE_ATTACK_DAMAGE, 2.0D);
	}

	/** 仪式法袍：佩戴时替换掉的每日衰减 */
	public static double robeDailyDecay() {
		return doubleOr(ROBE_DAILY_DECAY, 3.0D);
	}

	/** 撒旦圣经：总开关 */
	public static boolean enableSatanicBible() {
		return boolOr(ENABLE_SATANIC_BIBLE, true);
	}

	/** 撒旦圣经：黑心池上限加成（心数） */
	public static double satanicBibleBlackHearts() {
		return doubleOr(SATANIC_BIBLE_BLACK_HEARTS, 1.0D);
	}

	/** 撒旦圣经：黑心补满的间隔（秒） */
	public static int satanicRefillSeconds() {
		return Math.max(1, intOr(SATANIC_REFILL_SECONDS, 30));
	}

	/** 撒旦圣经：黑心碎裂半径（格） */
	public static double satanicShatterRadius() {
		return intOr(SATANIC_SHATTER_RADIUS, 18);
	}

	/** 撒旦圣经：黑心碎裂伤害 */
	public static double satanicShatterDamage() {
		return doubleOr(SATANIC_SHATTER_DAMAGE, 24.0D);
	}

	/** 咒印：佩戴时覆盖的黑心碎裂伤害 */
	public static double markShatterDamage() {
		return doubleOr(MARK_SHATTER_DAMAGE, 40.0D);
	}

	/** 咒印 + 亚巴顿（联动）覆盖的黑心碎裂伤害（默认 60） */
	public static double markShatterDamageAbaddon() {
		return doubleOr(MARK_SHATTER_DAMAGE_ABADDON, 60.0D);
	}

	/** 咒印：佩戴时是否把邪恶每日衰减压到 0 */
	public static boolean markStopsEvilDecay() {
		return boolOr(MARK_STOPS_EVIL_DECAY, true);
	}

	/** 咒印：黑心池上限加成（心数） */
	public static double markBlackHearts() {
		return doubleOr(MARK_BLACK_HEARTS, 3.0D);
	}

	/** 复仇之魂：每秒狱火伤害 */
	public static double vengefulHellfireDamage() {
		return doubleOr(VENGEFUL_HELLFIRE_DAMAGE, 6.0D);
	}

	/** 复仇之魂：狱火半径（格） */
	public static double vengefulHellfireRadius() {
		return intOr(VENGEFUL_HELLFIRE_RADIUS, 3);
	}

	/** 夜之幽魂：移动速度加成（百分比） */
	public static double nightWraithMovementPercent() {
		return doubleOr(NIGHT_WRAITH_MOVEMENT_PERCENT, 20.0D);
	}

	public static int evilMax() {
		return intOr(EVIL_MAX, 1000);
	}

	/** 每 1 点邪恶提供的加成（%，奇偶交替） */
	public static double evilPerPointPercent() {
		return doubleOr(EVIL_PER_POINT_PERCENT, 0.1D);
	}

	public static double evilFriendlyGain() {
		return doubleOr(EVIL_FRIENDLY, 1.0D);
	}

	public static double evilNeutralGain() {
		return doubleOr(EVIL_NEUTRAL, 0.5D);
	}

	public static double evilHostileGain() {
		return doubleOr(EVIL_HOSTILE, 0.1D);
	}

	public static double evilWardenWitherGain() {
		return doubleOr(EVIL_WARDEN_WITHER, 10.0D);
	}

	public static double evilDragonGain() {
		return doubleOr(EVIL_DRAGON, 20.0D);
	}

	public static double evilDailyCap() {
		return doubleOr(EVIL_DAILY_CAP, 100.0D);
	}

	public static double evilDailyDecay() {
		return doubleOr(EVIL_DAILY_DECAY, 5.0D);
	}

	public static double evilFriendlyDailyCap() {
		return doubleOr(EVIL_FRIENDLY_DAILY_CAP, 20.0D);
	}

	/** 错误位置提示的最短间隔（tick） */
	public static int demonErrorCooldownTicks() {
		return Math.max(0, intOr(DEMON_ERROR_COOLDOWN_SECONDS, 5) * 20);
	}

	// ==================== 五芒星（1.5.8） ====================

	/** 五芒星：佩戴时的近战伤害加成（默认 +1） */
	public static double pentagramAttackDamage() {
		return doubleOr(PENTAGRAM_ATTACK_DAMAGE, 1.0D);
	}

	/** 五芒星：累计持有多少秒后发那句「交易」消息（默认 300 秒） */
	public static int pentagramMessageSeconds() {
		return intOr(PENTAGRAM_MESSAGE_SECONDS, 300);
	}

	public static double haloMaxHealth() {
		return doubleOr(HALO_MAX_HEALTH, 4.0D);
	}

	public static double haloAttackDamage() {
		return doubleOr(HALO_ATTACK_DAMAGE, 2.0D);
	}

	public static double haloAttackSpeed() {
		return doubleOr(HALO_ATTACK_SPEED, 0.2D);
	}

	public static double haloArmor() {
		return doubleOr(HALO_ARMOR, 2.0D);
	}

	public static double haloArmorToughness() {
		return doubleOr(HALO_ARMOR_TOUGHNESS, 2.0D);
	}

	public static int haloMovementPercent() {
		return intOr(HALO_MOVEMENT_PERCENT, 10);
	}

	public static int haloBreakSpeedPercent() {
		return intOr(HALO_BREAK_SPEED_PERCENT, 10);
	}

	/** 佩戴七罪之源（且至少激活一项）时光环属性的倍率 */
	public static double haloSinMultiplier() {
		return doubleOr(HALO_SIN_MULTIPLIER, 0.5D);
	}

	/** 佩戴美德时光环属性的倍率 */
	public static double haloVirtueMultiplier() {
		return doubleOr(HALO_VIRTUE_MULTIPLIER, 2.0D);
	}

	/** 光环（1.7.1）：生命值低于该百分比时获得「生命回复」 */
	public static int haloRegenThresholdPercent() {
		return intOr(HALO_REGEN_THRESHOLD_PERCENT, 50);
	}

	/** 光环（1.7.1）：生命回复等级（amplifier；0 = 生命回复 I） */
	public static int haloRegenAmplifier() {
		return intOr(HALO_REGEN_AMPLIFIER, 0);
	}

	// ==================== 恶魔王冠（1.7.1） ====================

	public static boolean enableDevilCrown() {
		return boolOr(ENABLE_DEVIL_CROWN, true);
	}

	public static int devilCrownMovementPercent() {
		return intOr(DEVIL_CROWN_MOVEMENT_PERCENT, 20);
	}

	public static double devilCrownAttackSpeed() {
		return doubleOr(DEVIL_CROWN_ATTACK_SPEED, 0.4D);
	}

	public static double devilCrownMeleeDamage() {
		return doubleOr(DEVIL_CROWN_MELEE_DAMAGE, 6.0D);
	}

	public static double devilCrownMaxHealth() {
		return doubleOr(DEVIL_CROWN_MAX_HEALTH, -4.0D);
	}

	public static double devilCrownArmor() {
		return doubleOr(DEVIL_CROWN_ARMOR, -4.0D);
	}

	public static double devilCrownArmorToughness() {
		return doubleOr(DEVIL_CROWN_ARMOR_TOUGHNESS, -4.0D);
	}

	/** 恶魔王冠：每损失 10% 生命值提升的造成伤害（百分比） */
	public static double devilCrownDamagePerDecilePercent() {
		return doubleOr(DEVIL_CROWN_DAMAGE_PER_DECILE_PERCENT, 3.0D);
	}

	/** 恶魔王冠：上述加成的上限（百分比） */
	public static double devilCrownDamageCapPercent() {
		return doubleOr(DEVIL_CROWN_DAMAGE_CAP_PERCENT, 15.0D);
	}

	// ===== 遁入暗影（1.7.5） =====

	/** 遁入暗影：总开关 */
	public static boolean enableShadowDash() {
		return boolOr(ENABLE_SHADOW_DASH, true);
	}

	/** 遁入暗影：技能判定时长（tick）= 加速时长 */
	public static int shadowDashDurationTicks(boolean darkArts) {
		return darkArts
				? intOr(SHADOW_DARK_ARTS_DURATION_TICKS, 40)
				: intOr(SHADOW_SACRIFICIAL_DURATION_TICKS, 20);
	}

	/** 遁入暗影：基础斩击倍率 */
	public static double shadowDashMultiplier(boolean darkArts) {
		return darkArts
				? doubleOr(SHADOW_DARK_ARTS_MULTIPLIER, 2.0D)
				: doubleOr(SHADOW_SACRIFICIAL_MULTIPLIER, 1.0D);
	}

	/** 遁入暗影：接触判定半径（格） */
	public static double shadowDashContactRadius() {
		return doubleOr(SHADOW_CONTACT_RADIUS, 2.0D);
	}

	/** 遁入暗影：强力斩击半径（格） */
	public static double shadowDashHeavyRadius() {
		return doubleOr(SHADOW_HEAVY_RADIUS, 3.0D);
	}

	/** 遁入暗影：技能判定期间的移动速度加成（百分比） */
	public static int shadowDashMovementPercent() {
		return intOr(SHADOW_MOVEMENT_PERCENT, 100);
	}

	/** 遁入暗影：冷却（tick，从无敌解除开始计时） */
	public static int shadowDashCooldownTicks() {
		return intOr(SHADOW_COOLDOWN_TICKS, 120);
	}

	/** 遁入暗影：逐个结算标记目标的间隔（tick） */
	public static int shadowDashResolveIntervalTicks() {
		return intOr(SHADOW_RESOLVE_INTERVAL_TICKS, 2);
	}

	/** 献祭匕首：连续多少秒没有匕首后开放「防丢失配方」 */
	public static int daggerRecoverySeconds() {
		return intOr(DAGGER_RECOVERY_SECONDS, 300);
	}

	/** 亚巴顿联动：遁入暗影时长加成（tick，1.7.10） */
	public static int shadowDashAbaddonDurationTicks() {
		return intOr(SHADOW_ABADDON_DURATION_TICKS, 20);
	}

	/** 亚巴顿联动：接触判定半径加成（格，1.7.10） */
	public static double shadowDashAbaddonContactRadius() {
		return doubleOr(SHADOW_ABADDON_CONTACT_RADIUS, 1.0D);
	}

	/** 亚巴顿联动：强力斩击半径加成（格，1.7.10） */
	public static double shadowDashAbaddonHeavyRadius() {
		return doubleOr(SHADOW_ABADDON_HEAVY_RADIUS, 2.0D);
	}

	// ==================== 献祭（1.8.2） ====================

	/** 献祭：总开关 */
	public static boolean enableSacrifice() {
		return boolOr(ENABLE_SACRIFICE, true);
	}

	/** 献祭：对自身造成的伤害点数 */
	public static double sacrificeSelfDamage() {
		return doubleOr(SACRIFICE_SELF_DAMAGE, 4.0D);
	}

	/** 献祭：近战增伤（百分比） */
	public static int sacrificeDamagePercent() {
		return intOr(SACRIFICE_DAMAGE_PERCENT, 40);
	}

	/** 献祭：增伤持续（tick） */
	public static int sacrificeDurationTicks() {
		return Math.max(1, (int) Math.round(doubleOr(SACRIFICE_DURATION_SECONDS, 8.0D) * 20.0D));
	}

	/** 献祭：增伤持续（秒，供提示用） */
	public static double sacrificeDurationSeconds() {
		return doubleOr(SACRIFICE_DURATION_SECONDS, 8.0D);
	}

	/** 献祭：冷却（tick） */
	public static int sacrificeCooldownTicks() {
		return Math.max(0, intOr(SACRIFICE_COOLDOWN_SECONDS, 8)) * 20;
	}

	// ==================== 神圣行动（1.8.2） ====================

	/** X 技能：长按蓄力秒数 */
	public static double divineActionChargeSeconds() {
		return doubleOr(DIVINE_ACTION_CHARGE_SECONDS, 1.0D);
	}

	/** X 技能：长按蓄力（tick，默认 1 秒 = 20） */
	public static int divineActionChargeTicks() {
		return Math.max(1, (int) Math.round(divineActionChargeSeconds() * 20.0D));
	}

	// ==================== 金刀片（1.7.10） ====================

	/** 金刀片：总开关 */
	public static boolean enableGoldenRazor() {
		return boolOr(ENABLE_GOLDEN_RAZOR, true);
	}

	/** 金刀片：命中伤害点数（不吃任何加成） */
	public static double razorDamage() {
		return doubleOr(RAZOR_DAMAGE, 5.0D);
	}

	/** 金刀片：投掷间隔（tick，默认 10 = 0.5 秒） */
	public static int razorCooldownTicks() {
		return Math.max(0, intOr(RAZOR_COOLDOWN_TICKS, 10));
	}

	/** 金刀片：出手速度 */
	public static double razorVelocity() {
		return doubleOr(RAZOR_VELOCITY, 1.8D);
	}

	/** 金刀片：穿透等级（原版口径） */
	public static int razorPierceLevel() {
		return intOr(RAZOR_PIERCE_LEVEL, 127);
	}

	/** 金刀片：幻影存活上限（tick） */
	public static int razorMaxLifeTicks() {
		return intOr(RAZOR_MAX_LIFE_TICKS, 200);
	}

	/** 金刀片：命中方块后的停留时长（tick，默认 100 = 5 秒） */
	public static int razorStuckTicks() {
		return Math.max(1, intOr(RAZOR_STUCK_TICKS, 100));
	}

	/** 是否在首次进入世界时发放七罪之源与光环 */
	public static boolean grantStartItems() {
		try {
			return GRANT_START_ITEMS.get();
		} catch (IllegalStateException exception) {
			return true;
		}
	}

	/** 首次发放时是否直接把七罪之源装进魂印栏 */
	public static boolean autoEquipSourceOfSins() {
		try {
			return AUTO_EQUIP_SOURCE_OF_SINS.get();
		} catch (IllegalStateException exception) {
			return false;
		}
	}

	public static int revealSeconds() {
		return intOr(REVEAL_SECONDS, 600);
	}

	public static int revealRadius() {
		return intOr(REVEAL_RADIUS, 1000);
	}

	public static int starAttackSpeedPercent() {
		return intOr(STAR_ATTACK_SPEED_PERCENT, 20);
	}

	public static int starDamagePercent() {
		return intOr(STAR_DAMAGE_PERCENT, 20);
	}

	public static double finalSoulHearts() {
		return intOr(FINAL_SOUL_HEARTS, 2);
	}

	public static double flightSpeedMultiplier() {
		return doubleOr(FLIGHT_SPEED_MULTIPLIER, 0.5D);
	}

	public static int transformRadius() {
		return intOr(TRANSFORM_RADIUS, 8);
	}

	public static int transformSeconds() {
		return intOr(TRANSFORM_SECONDS, 5);
	}

	public static double transformMaxMoveBlocks() {
		return doubleOr(TRANSFORM_MAX_MOVE_BLOCKS, 1.0D);
	}

	public static int beamLength() {
		return intOr(BEAM_LENGTH, 21);
	}

	public static int beamRadius() {
		return intOr(BEAM_RADIUS, 2);
	}

	public static int beamDamagePerTick() {
		return intOr(BEAM_DAMAGE_PER_TICK, 7);
	}

	/** 照射持续 tick 数（最少 1） */
	public static int beamDurationTicks() {
		return Math.max(1, (int) Math.round(doubleOr(BEAM_DURATION_SECONDS, 1.5D) * 20.0D));
	}

	/** 两次结算间隔 tick 数（最少 1） */
	public static int beamDamageIntervalTicks() {
		return Math.max(1, (int) Math.round(doubleOr(BEAM_DAMAGE_INTERVAL_SECONDS, 0.1D) * 20.0D));
	}

	/** 蓄力 tick 数（最少 1 tick） */
	public static int beamChargeTicks() {
		return Math.max(1, (int) Math.round(doubleOr(BEAM_CHARGE_SECONDS, 2.5D) * 20.0D));
	}

	/** 冷却 tick 数 */
	public static int beamCooldownTicks() {
		return Math.max(0, intOr(BEAM_COOLDOWN_SECONDS, 10) * 20);
	}

	/** 光柱渲染的广播半径（格） */
	public static double beamBroadcastRadius() {
		return intOr(BEAM_BROADCAST_RADIUS, 128);
	}

	/** 蓄力时 FOV 最大收缩比例（0.15 = 15%） */
	public static double chargeFovScale() {
		return doubleOr(CHARGE_FOV_SCALE, 0.15D);
	}

	// ===== 硫磺火 / 恶魔之焰（1.6.4） =====

	/** 伤害池结算日志开关 */
	public static boolean logDamagePools() {
		return boolOr(LOG_DAMAGE_POOLS, false);
	}

	/** 是否启用硫磺火与恶魔之焰 */
	public static boolean enableBrimstone() {
		return boolOr(ENABLE_BRIMSTONE, true);
	}

	/** 恶魔之焰：蓄力的秒数（提示文本用） */
	public static double brimstoneChargeSeconds() {
		return doubleOr(BRIMSTONE_CHARGE_SECONDS, 1.5D);
	}

	/** 恶魔之焰：蓄力 tick 数（最少 1） */
	public static int brimstoneChargeTicks() {
		return Math.max(1, (int) Math.round(brimstoneChargeSeconds() * 20.0D));
	}

	/** 恶魔之焰：火柱长度（格） */
	public static int brimstoneBeamLength() {
		return intOr(BRIMSTONE_BEAM_LENGTH, 18);
	}

	/** 恶魔之焰：火柱半径（格） */
	public static int brimstoneBeamRadius() {
		return intOr(BRIMSTONE_BEAM_RADIUS, 2);
	}

	/** 恶魔之焰：持续照射 tick 数（最少 1） */
	public static int brimstoneDurationTicks() {
		return Math.max(1, (int) Math.round(doubleOr(BRIMSTONE_DURATION_SECONDS, 1.3D) * 20.0D));
	}

	/** 恶魔之焰：每次结算的伤害点数 */
	public static int brimstoneDamagePerTick() {
		return intOr(BRIMSTONE_DAMAGE_PER_TICK, 6);
	}

	/** 恶魔之焰：两次结算之间的间隔 tick 数（最少 1） */
	public static int brimstoneDamageIntervalTicks() {
		return Math.max(1, (int) Math.round(doubleOr(BRIMSTONE_DAMAGE_INTERVAL_SECONDS, 0.1D) * 20.0D));
	}

	/** 恶魔之焰：冷却秒数（提示文本用） */
	public static int brimstoneCooldownSeconds() {
		return intOr(BRIMSTONE_COOLDOWN_SECONDS, 6);
	}

	/** 恶魔之焰：冷却 tick 数 */
	public static int brimstoneCooldownTicks() {
		return Math.max(0, brimstoneCooldownSeconds() * 20);
	}

	// ===== 玄秘魔眼 / 恐惧（1.6.5） =====

	/** 是否启用玄秘魔眼与「恐惧」 */
	public static boolean enableOccultEye() {
		return boolOr(ENABLE_OCCULT_EYE, true);
	}

	/** 注视距离（格） */
	public static double fearRadius() {
		return intOr(FEAR_RADIUS, 24);
	}

	/** 恐惧持续时间（tick） */
	public static int fearTicks() {
		return Math.max(1, intOr(FEAR_SECONDS, 6)) * 20;
	}

	/** 恐惧是否对玩家生效 */
	public static boolean fearAffectsPlayers() {
		return boolOr(FEAR_AFFECTS_PLAYERS, true);
	}

	/** 是否给注视目标施加发光 */
	public static boolean fearGlow() {
		return boolOr(FEAR_GLOW, true);
	}

	/** 发光是否对玩家生效 */
	public static boolean fearGlowAffectsPlayers() {
		return boolOr(FEAR_GLOW_AFFECTS_PLAYERS, true);
	}

	/** 对注视目标的伤害倍率 */
	public static double fearDamageMultiplier() {
		return doubleOr(FEAR_DAMAGE_MULTIPLIER, 1.3D);
	}

	/**
	 * 恐惧是否附带「黑暗」（1.6.6：新键 {@code fear_darkness}；旧键 {@code fear_blindness} 显式关闭时仍然尊重）。
	 */
	public static boolean fearDarkness() {
		return boolOr(FEAR_DARKNESS, true) && boolOr(FEAR_BLINDNESS_LEGACY, true);
	}

	public static int fearMiningFatigueAmplifier() {
		return intOr(FEAR_MINING_FATIGUE_AMPLIFIER, 2);
	}

	public static int fearSlownessAmplifier() {
		return intOr(FEAR_SLOWNESS_AMPLIFIER, 5);
	}

	public static int fearWeaknessAmplifier() {
		return intOr(FEAR_WEAKNESS_AMPLIFIER, 9);
	}

	/** 恐惧 + 亚巴顿（联动）：是否额外把目标移速归零 */
	public static boolean fearFreezeWithAbaddon() {
		return boolOr(FEAR_FREEZE_WITH_ABADDON, true);
	}

	// ==================== 深渊领主 / 狱火（1.6.7） ====================

	public static boolean enableAbyssLord() {
		return boolOr(ENABLE_ABYSS_LORD, true);
	}

	/** 狱火持续时间（tick）：每次命中刷新 */
	public static int hellfireTicks() {
		return Math.max(1, intOr(HELLFIRE_SECONDS, 6)) * 20;
	}

	/** 狱火持续时间（秒）—— 只用于提示文本 */
	public static int hellfireSeconds() {
		return Math.max(1, intOr(HELLFIRE_SECONDS, 6));
	}

	public static int hellfireMaxLevel() {
		return Math.max(1, intOr(HELLFIRE_MAX_LEVEL, 10));
	}

	/** 狱火 + 亚巴顿（联动）：满级时是否把目标「抗性提升」等级减半并持续覆盖 */
	public static boolean hellfireHalvesResistanceWithAbaddon() {
		return boolOr(HELLFIRE_HALVES_RESISTANCE_WITH_ABADDON, true);
	}

	/** 每级降低的护甲百分比（10 = 每级 -10%） */
	public static double hellfireArmorPercentPerLevel() {
		return doubleOr(HELLFIRE_ARMOR_PERCENT_PER_LEVEL, 10.0D);
	}

	/** 每级每秒的狱火伤害 */
	public static double hellfireDamagePerLevelPerSecond() {
		return doubleOr(HELLFIRE_DAMAGE_PER_LEVEL_PER_SECOND, 1.0D);
	}

	/** 恶魔线（当前持恶魔标记）是否免疫火焰并保持不着火 */
	public static boolean enableDemonFireImmunity() {
		return boolOr(ENABLE_DEMON_FIRE_IMMUNITY, true);
	}

	/** 复仇之魂：同时佩戴硫磺火时的作用半径（格） */
	public static int vengefulHellfireRadiusBrimstone() {
		return intOr(VENGEFUL_HELLFIRE_RADIUS_BRIMSTONE, 4);
	}

	// ==================== 亚巴顿（1.6.8） ====================

	public static boolean enableAbaddon() {
		return boolOr(ENABLE_ABADDON, true);
	}

	/** 亚巴顿继承的恶魔之焰：每次结算的狱火（真伤）点数 */
	public static int abaddonBeamDamagePerTick() {
		return intOr(ABADDON_BEAM_DAMAGE_PER_TICK, 9);
	}

	/** 亚巴顿继承的恶魔之焰：长按 V 的蓄力（tick，默认 1.0 秒） */
	public static int abaddonBeamChargeTicks() {
		return Math.max(1, (int) Math.round(doubleOr(ABADDON_BEAM_CHARGE_SECONDS, 1.0D) * 20.0D));
	}

	/** 亚巴顿继承的恶魔之焰：火柱长度（格） */
	public static int abaddonBeamLength() {
		return intOr(ABADDON_BEAM_LENGTH, 32);
	}

	/** 亚巴顿继承的恶魔之焰：火柱半径（格） */
	public static int abaddonBeamRadius() {
		return intOr(ABADDON_BEAM_RADIUS, 3);
	}

	/** 亚巴顿：被击杀复活后的冷却（tick） */
	public static int abaddonReviveCooldownTicks() {
		return Math.max(0, intOr(ABADDON_REVIVE_COOLDOWN_SECONDS, 1200)) * 20;
	}

	/** 亚巴顿：复活后的无敌与恶魔光环持续（tick） */
	public static int abaddonReviveTicks() {
		return Math.max(0, intOr(ABADDON_REVIVE_INVULNERABLE_SECONDS, 8)) * 20;
	}

	/** 恶魔光环半径（格） */
	public static double abaddonAuraRadius() {
		return doubleOr(ABADDON_AURA_RADIUS, 5.0D);
	}

	/** 恶魔光环每 tick 的真实伤害 */
	public static int abaddonAuraDamagePerTick() {
		return intOr(ABADDON_AURA_DAMAGE_PER_TICK, 6);
	}

	/** 复仇之魂：同时佩戴亚巴顿时的作用半径（格） */
	public static int vengefulHellfireRadiusAbaddon() {
		return intOr(VENGEFUL_HELLFIRE_RADIUS_ABADDON, 5);
	}
}
