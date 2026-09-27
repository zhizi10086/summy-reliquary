package com.summy.reliquary;

import com.mojang.logging.LogUtils;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.attribute.ReliquaryAttributes;
import com.summy.reliquary.advancement.ReliquaryTrigger;
import com.summy.reliquary.item.BangBangHaloItem;
import com.summy.reliquary.item.FreeloadersRiceItem;
import com.summy.reliquary.item.FinalRevelationItem;
import com.summy.reliquary.item.RedemptionItem;
import com.summy.reliquary.item.SinFragmentItem;
import com.summy.reliquary.item.SourceOfSinsItem;
import com.summy.reliquary.item.StarOfBethlehemItem;
import com.summy.reliquary.item.TheBodyItem;
import com.summy.reliquary.item.TheHaloItem;
import com.summy.reliquary.item.TheMindItem;
import com.summy.reliquary.item.TheSoulItem;
import com.summy.reliquary.item.VirtuesItem;
import com.summy.reliquary.net.ReliquaryNetworking;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

/**
 * Summy Reliquary 主入口（Forge 1.20.1 + Curios）。
 *
 * <p>类里只放「客户端与服务端都需要」的东西：物品注册、创造标签页、配置、网络注册与 Curios 栏位校验谓词。
 * 按键、渲染等客户端内容见 {@link com.summy.reliquary.client.SummyReliquaryClient}。
 *
 * <p>注意两个名字的区别：
 * <ul>
 *     <li>{@link #MOD_ID}：Forge 的 modId，不允许连字符，所以是 {@code summy_reliquary}；</li>
 *     <li>{@link #NAMESPACE}：注册表 / 数据包 / 资源命名空间，保持与 Fabric 版一致的
 *     {@code summy-reliquary}，这样老存档里的物品 id、配方、贴图与语言键全部继续有效。</li>
 * </ul>
 */
@Mod(SummyReliquary.MOD_ID)
public class SummyReliquary {
	/** Forge modId（不允许连字符） */
	public static final String MOD_ID = "summy_reliquary";
	/** 注册表 / 数据包 / 资源命名空间（与 Fabric 版保持一致，用于保住存档物品） */
	public static final String NAMESPACE = "summy-reliquary";
	public static final Logger LOGGER = LogUtils.getLogger();

	private static final DeferredRegister<Item> ITEMS =
			DeferredRegister.create(ForgeRegistries.ITEMS, NAMESPACE);
	private static final DeferredRegister<CreativeModeTab> TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, NAMESPACE);
	/** 状态效果：1.5.6 起新增「启示之光」（纯标记） */
	private static final DeferredRegister<net.minecraft.world.effect.MobEffect> EFFECTS =
			DeferredRegister.create(Registries.MOB_EFFECT, NAMESPACE);

	/** 启示之光：所有启示伤害会给受击者挂上它，带它死亡的生物有机会掉「心之碎片」 */
	public static final RegistryObject<net.minecraft.world.effect.MobEffect> REVELATION_LIGHT =
			EFFECTS.register("revelation_light",
					() -> new com.summy.reliquary.effect.RevelationLightEffect());
	/** 恐惧（1.6.5）：玄秘魔眼给"被注视的那一具"挂上的减益载体（图标用魔眼贴图） */
	public static final RegistryObject<net.minecraft.world.effect.MobEffect> FEAR =
			EFFECTS.register("fear", () -> new com.summy.reliquary.effect.FearEffect());
	/** 狱火（1.6.7）：深渊领主给受害者叠的减益（护甲 -10%/级、每秒 等级×1 点狱火伤害） */
	public static final RegistryObject<net.minecraft.world.effect.MobEffect> HELLFIRE =
			EFFECTS.register("hellfire", () -> new com.summy.reliquary.effect.HellfireEffect());
	/** 粒子类型（1.6.7）：复仇之魂范围圈专用的"短寿命火焰"（贴图仍是原版火焰，只把寿命缩短以消除拖尾） */
	private static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLES =
			DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, NAMESPACE);
	public static final RegistryObject<net.minecraft.core.particles.SimpleParticleType> SHORT_FLAME =
			PARTICLES.register("short_flame",
					() -> new net.minecraft.core.particles.SimpleParticleType(true));
	/** 实体类型（1.7.8）：天使线长矛投出去的"幻影矛" */
	private static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITY_TYPES =
			DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, NAMESPACE);
	public static final RegistryObject<net.minecraft.world.entity.EntityType<com.summy.reliquary.entity.ThrownSpear>>
			THROWN_SPEAR = ENTITY_TYPES.register("thrown_spear",
					() -> net.minecraft.world.entity.EntityType.Builder
							.<com.summy.reliquary.entity.ThrownSpear>of(
									com.summy.reliquary.entity.ThrownSpear::new,
									net.minecraft.world.entity.MobCategory.MISC)
							.sized(0.5F, 0.5F)
							// 与原版三叉戟同档：4 区块同步范围（自检与冷却回收按 64 格判定）
							.clientTrackingRange(4)
							.updateInterval(20)
							.build(id("thrown_spear").toString()));
	/** 实体类型（1.7.10）：金刀片投出去的"幻影刀片"（可穿透生物） */
	public static final RegistryObject<net.minecraft.world.entity.EntityType<com.summy.reliquary.entity.ThrownRazor>>
			THROWN_RAZOR = ENTITY_TYPES.register("thrown_razor",
					() -> net.minecraft.world.entity.EntityType.Builder
							.<com.summy.reliquary.entity.ThrownRazor>of(
									com.summy.reliquary.entity.ThrownRazor::new,
									net.minecraft.world.entity.MobCategory.MISC)
							.sized(0.4F, 0.4F)
							// 与原版箭矢同档：4 区块同步范围
							.clientTrackingRange(4)
							.updateInterval(20)
							.build(id("thrown_razor").toString()));
	/** 统一进度触发器：{@code summy-reliquary:event} */
	public static final ReliquaryTrigger EVENT_TRIGGER =
			net.minecraft.advancements.CriteriaTriggers.register(new ReliquaryTrigger(id("event")));

	/** 邦邦咔邦光环：装进「光环」栏位，按 R 切换邦邦女仆状态 */
	public static final RegistryObject<Item> BANG_BANG_HALO =
			ITEMS.register("bangbang_halo", () -> new BangBangHaloItem(new Item.Properties().stacksTo(1)));

	/** 白饭：装进「胃袋」栏位，佩戴时锁满饥饿并禁止吃喝 */
	public static final RegistryObject<Item> FREELOADERS_RICE =
			ITEMS.register("freeloaders_rice", () -> new FreeloadersRiceItem(new Item.Properties().stacksTo(1)));

	/**
	 * 七宗罪碎片：暂无功能，仅注册。
	 * 名字颜色取各自贴图的核心色（精确 RGB）。
	 */
	public static final RegistryObject<Item> SIN_FRAGMENT_PRIDE =
			ITEMS.register("sin_fragment_pride", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.PRIDE.color()));
	public static final RegistryObject<Item> SIN_FRAGMENT_GLUTTONY =
			ITEMS.register("sin_fragment_gluttony", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.GLUTTONY.color()));
	public static final RegistryObject<Item> SIN_FRAGMENT_WRATH =
			ITEMS.register("sin_fragment_wrath", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.WRATH.color()));
	public static final RegistryObject<Item> SIN_FRAGMENT_ENVY =
			ITEMS.register("sin_fragment_envy", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.ENVY.color()));
	public static final RegistryObject<Item> SIN_FRAGMENT_SLOTH =
			ITEMS.register("sin_fragment_sloth", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.SLOTH.color()));
	public static final RegistryObject<Item> SIN_FRAGMENT_LUST =
			ITEMS.register("sin_fragment_lust", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.LUST.color()));
	public static final RegistryObject<Item> SIN_FRAGMENT_GREED =
			ITEMS.register("sin_fragment_greed", () -> new SinFragmentItem(new Item.Properties(), com.summy.reliquary.sin.Sin.GREED.color()));

	/** 七罪之源：装入「魂印」栏位（暂无效果） */
	public static final RegistryObject<Item> SOURCE_OF_SINS =
			ITEMS.register("source_of_sins", () -> new SourceOfSinsItem(new Item.Properties().stacksTo(1)));

	/** 美德：装入「魂印」栏位（暂无效果），由「赎罪」右击转化而来 */
	public static final RegistryObject<Item> VIRTUES =
			ITEMS.register("virtues", () -> new VirtuesItem(new Item.Properties().stacksTo(1)));

	/** 赎罪：普通物品，手持右击可把魂印栏里的七罪之源转化为美德 */
	public static final RegistryObject<Item> REDEMPTION =
			ITEMS.register("redemption", () -> new RedemptionItem(new Item.Properties()));

	/* ===== 灵台套装（肉体 / 思想 / 灵魂）与启示（光环 / 伯列恒之星） ===== */

	/** 肉体：灵台栏位，最大生命 +10 / 套装 +20 */
	public static final RegistryObject<Item> THE_BODY =
			ITEMS.register("the_body", () -> new TheBodyItem(new Item.Properties().stacksTo(1)));

	/** 思想：灵台栏位，范围内敌对生物与玩家发光；套装时对发光目标加伤 */
	public static final RegistryObject<Item> THE_MIND =
			ITEMS.register("the_mind", () -> new TheMindItem(new Item.Properties().stacksTo(1)));

	/** 灵魂：灵台栏位，+3 魂心（每点 2 点吸收，每 30 秒补满）；套装时几率免疫投射物 */
	public static final RegistryObject<Item> THE_SOUL =
			ITEMS.register("the_soul", () -> new TheSoulItem(new Item.Properties().stacksTo(1)));

	/** 光环：光环栏位（与邦邦咔邦光环互斥），少量属性上升，受七罪/美德影响 */
	public static final RegistryObject<Item> THE_HALO =
			ITEMS.register("the_halo", () -> new TheHaloItem(new Item.Properties().stacksTo(1)));

	/** 恶魔王冠（1.7.1）：光环栏位，需佩戴撒旦圣经才生效；由撒旦圣经的转化发放、死亡不掉 */
	public static final RegistryObject<Item> DEVIL_CROWN =
			ITEMS.register("devil_crown", () -> new com.summy.reliquary.item.DevilCrownItem(
					new Item.Properties().stacksTo(1)));

	/** 伯列恒之星：启示栏位，攻击速度与伤害提升，累计佩戴满 600 秒揭示坐标 */
	public static final RegistryObject<Item> STAR_OF_BETHLEHEM =
			ITEMS.register("star_of_bethlehem", () -> new StarOfBethlehemItem(new Item.Properties().stacksTo(1)));

	/** 终末天启：启示之座饰品；魂心 +2、创造飞行（速度减半）、长按 V 召唤启示之光 */
	public static final RegistryObject<Item> FINAL_REVELATION =
			ITEMS.register("final_revelation", () -> new FinalRevelationItem(new Item.Properties().stacksTo(1)));

	/** 隐藏图标物品：只用于「三位一体」成就的图标，不进创造页、无配方 */
	public static final RegistryObject<Item> TRINITY =
			ITEMS.register("trinity", () -> new Item(new Item.Properties()));

	/* 隐藏图标物品：分别给两条末影龙挑战当图标，不进创造页、无配方 */
	/** 无罪之人 */
	public static final RegistryObject<Item> ERROR_ICON =
			ITEMS.register("error", () -> new Item(new Item.Properties()));
	/** 纯洁无瑕 */
	public static final RegistryObject<Item> PURITY_ICON =
			ITEMS.register("purity", () -> new Item(new Item.Properties()));

	/* 隐藏图标物品（1.6.9）：成就页新根与「新鲜灵魂」的图标，同样不进创造页、无配方 */
	/** 遗物：七罪（成就页根图标） */
	public static final RegistryObject<Item> DUALITY_STAT =
			ITEMS.register("duality_stat", () -> new Item(new Item.Properties()));
	/** 你的灵魂（「新鲜灵魂」成就图标，暂定为无用物品） */
	public static final RegistryObject<Item> YOUR_SOUL =
			ITEMS.register("your_soul", () -> new Item(new Item.Properties()));

	/* ===== 1.5.0：加护栏位与四件新物品 ===== */

	/**
	 * 创世纪：非饰品，仅右键使用（一次性全局重置），需要天使标记或恶魔侧任一标记。
	 *
	 * <p>1.7.3：加 {@code fireResistant} —— 它是"生存里只有 1 个"的一次性道具，
	 * 掉进岩浆/火里不能被烧掉（掉落实体还会被 {@code ReliquaryEvents} 设成无敌 + 不自然消失）。
	 */
	public static final RegistryObject<Item> GENESIS =
			ITEMS.register("genesis", () -> new com.summy.reliquary.item.GenesisItem(
					new Item.Properties().stacksTo(1).fireResistant()));

	/** 痛悔短祷：非饰品，出生点/重生点 8 格内且露天才可右键使用（一次性） */
	public static final RegistryObject<Item> ACT_OF_CONTRITION =
			ITEMS.register("act_of_contrition", () -> new com.summy.reliquary.item.ActOfContritionItem(
					new Item.Properties().stacksTo(1)));

	/** 木十字架：合成材料，只有合成痛悔短祷一个用途 */
	public static final RegistryObject<Item> WOODEN_CROSS =
			ITEMS.register("wooden_cross", () -> new Item(new Item.Properties()));

	/** 救恩：加护栏饰品，领域审判（默认不对玩家生效，可用指令把玩家加入名单） */
	public static final RegistryObject<Item> SALVATION =
			ITEMS.register("salvation", () -> new com.summy.reliquary.item.SalvationItem(
					new Item.Properties().stacksTo(1)));

	/* ===== 1.5.4：9 件新物品（圣光 / 神圣斗篷为正式饰品，其余 7 件占位） ===== */

	/** 圣光：加护栏饰品，命中时有几率召唤圣光（需要天使标记） */
	public static final RegistryObject<Item> HOLY_LIGHT =
			ITEMS.register("holy_light", () -> new com.summy.reliquary.item.HolyLightItem(
					new Item.Properties().stacksTo(1)));

	/** 神圣斗篷：加护栏饰品，受击后获得 1 秒无敌（需要天使标记） */
	public static final RegistryObject<Item> HOLY_MANTLE =
			ITEMS.register("holy_mantle", () -> new com.summy.reliquary.item.HolyMantleItem(
					new Item.Properties().stacksTo(1)));

	/* 以下仍为占位：贴图与名字已就位，功能后续补充
	   （五芒星 1.5.8、契约 1.6.0、仪式法袍与撒旦圣经 1.6.1 已转正） */
	/** 复仇之魂（1.6.2 转正）：加护栏饰品，需要恶魔标记 + 邪恶度 100 已解锁；每秒对 3 格内敌人造成 3 点狱火伤害 */
	public static final RegistryObject<Item> VENGEFUL_SPIRIT =
			ITEMS.register("vengeful_spirit", () -> new com.summy.reliquary.item.VengefulSpiritItem(
					new Item.Properties().stacksTo(1)));
	/** 契约（1.6.0 转正）：装进「恶魔契约」栏位，只能由签约发放、强制佩戴、无法摘除 */
	public static final RegistryObject<Item> THE_PACT =
			ITEMS.register("the_pact", () -> new com.summy.reliquary.item.ThePactItem(
					new Item.Properties().stacksTo(1)));

	/** 「6」（1.6.0）：签约没收灵台部件时的补偿物；3 个可无序合成咒印 */
	public static final RegistryObject<Item> SIX =
			ITEMS.register("six", () -> new Item(new Item.Properties().stacksTo(64)));
	/** 咒印（1.6.2 转正）：灵台栏饰品，需要恶魔标记 + 邪恶度 300 已解锁；继承灵台套装属性（魂心转黑心） */
	public static final RegistryObject<Item> THE_MARK =
			ITEMS.register("the_mark", () -> new com.summy.reliquary.item.TheMarkItem(
					new Item.Properties().stacksTo(1)));
	/** 亚巴顿（1.6.8 转正）：启示之座饰品，恶魔标记 + 邪恶度 1000；继承强化硫磺火、被击杀时拦截复活 */
	public static final RegistryObject<Item> ABADDON =
			ITEMS.register("abaddon", () -> new com.summy.reliquary.item.AbaddonItem(
					new Item.Properties().stacksTo(1)));
	/** 深渊领主（1.6.7 转正）：加护栏饰品，恶魔标记 + 邪恶度 900；佩戴时所有伤害给目标叠加「狱火」 */
	public static final RegistryObject<Item> ABYSS_LORD =
			ITEMS.register("abyss_lord", () -> new com.summy.reliquary.item.AbyssLordItem(
					new Item.Properties().stacksTo(1)));
	/** 仪式法袍（1.6.1 转正）：装进 Curios 自带的「背饰」栏位，签约后才能佩戴 */
	public static final RegistryObject<Item> CEREMONIAL_ROBES =
			ITEMS.register("ceremonial_robes", () -> new com.summy.reliquary.item.CeremonialRobesItem(
					new Item.Properties().stacksTo(1)));
	/** 夜之幽魂（1.6.3 转正）：加护栏饰品，需要恶魔标记 + 邪恶度 500；移速 +20% 并授予创造飞行（半速） */
	public static final RegistryObject<Item> NIGHT_WRAITH =
			ITEMS.register("night_wraith", () -> new com.summy.reliquary.item.NightWraithItem(
					new Item.Properties().stacksTo(1)));
	/** 硫磺火（1.6.4 转正）：进「启示之座」，需要恶魔标记 + 邪恶度 666；长按 V 发射恶魔之焰 */
	public static final RegistryObject<Item> BRIMSTONE =
			ITEMS.register("brimstone", () -> new com.summy.reliquary.item.BrimstoneItem(
					new Item.Properties().stacksTo(1)));
	/** 玄秘魔眼（1.6.5 转正）：加护栏，恶魔标记 + 邪恶 700；由夜之幽魂 + 3×「6」升级而来 */
	public static final RegistryObject<Item> OCCULT_EYE =
			ITEMS.register("occult_eye", () -> new com.summy.reliquary.item.OccultEyeItem(
					new Item.Properties().stacksTo(1)));
	/** 撒旦圣经（1.6.1）：魂印栏饰品，「已签约 + 七罪全部激活」时由七罪之源自动转化而来 */
	public static final RegistryObject<Item> SATANIC_BIBLE =
			ITEMS.register("satanic_bible", () -> new com.summy.reliquary.item.SatanicBibleItem(
					new Item.Properties().stacksTo(1)));
	/** 五芒星（1.5.8 转正）：护符栏饰品，近战伤害 +1；完成「罪无可赦」后自动发放 */
	public static final RegistryObject<Item> PENTAGRAM =
			ITEMS.register("pentagram", () -> new com.summy.reliquary.item.PentagramItem(
					new Item.Properties().stacksTo(1)));
	/** 神性：启示之座饰品，继承天启属性与光柱、光环审判、环境免疫、死亡拦截（需要天使标记） */
	public static final RegistryObject<Item> GODHEAD =
			ITEMS.register("godhead", () -> new com.summy.reliquary.item.GodheadItem(
					new Item.Properties().stacksTo(1)));
	/** 圣心：加护栏饰品，属性提升 + 箭矢追踪（需要天使标记） */
	public static final RegistryObject<Item> SACRED_HEART =
			ITEMS.register("sacred_heart", () -> new com.summy.reliquary.item.SacredHeartItem(
					new Item.Properties().stacksTo(1)));

	/** 心之碎片（1.5.6）：带启示之光的生物死亡时的掉落材料（1.6.1 起用自家贴图） */
	public static final RegistryObject<Item> HEART_SHARD =
			ITEMS.register("heart_shard", () -> new com.summy.reliquary.item.HeartShardItem(
					new Item.Properties().stacksTo(64)));

	/** 武器：献祭匕首（1.7.5）—— 4 伤害 / 2.4 攻速 / 耐久 666 / 附魔 22 / 铁锭修理；右键「遁入暗影」 */
	public static final RegistryObject<Item> SACRIFICIAL_DAGGER =
			ITEMS.register("sacrificial_dagger", () -> new com.summy.reliquary.item.RitualDaggerItem(
					com.summy.reliquary.item.RitualTiers.SACRIFICIAL, 3, -1.6F,
					new Item.Properties().stacksTo(1),
					"item.summy-reliquary.sacrificial_dagger", false));
	/** 武器：暗仪刺刀（1.7.5）—— 6 伤害 / 2.0 攻速 / 耐久 1666 / 附魔 25 / 下界合金碎片修理；右键「遁入暗影」 */
	public static final RegistryObject<Item> DARK_ARTS =
			ITEMS.register("dark_arts", () -> new com.summy.reliquary.item.RitualDaggerItem(
					com.summy.reliquary.item.RitualTiers.DARK_ARTS, 5, -2.0F,
					new Item.Properties().stacksTo(1),
					"item.summy-reliquary.dark_arts", true));
	/**
	 * 武器：圣光短矛（1.7.7）—— 7 伤害 / 1.0 攻速 / 耐久 1000 / 附魔 25 / 金锭修理；剑类、矛式渲染。
	 *
	 * <p>1.7.9：门槛改为**天使标记**（未达标完全禁用）；不能投掷，改为"攻击距离 +0.5 格 +
	 * 攻击时 10% 独立召唤圣光"；获取途径 = "七罪之源 → 美德"时自动发放。
	 */
	public static final RegistryObject<Item> HOLY_SPEAR =
			ITEMS.register("holy_spear", () -> new com.summy.reliquary.item.SpearItem(
					com.summy.reliquary.item.RitualTiers.HOLY_SPEAR, 6, -3.0F,
					new Item.Properties().stacksTo(1),
					"item.summy-reliquary.holy_spear", false));
	/**
	 * 武器：炽天使之枪（1.7.8）—— 10 伤害 / 1.0 攻速 / 耐久 2222 / 附魔 30 / 心之碎片修理；剑类、矛式渲染。
	 *
	 * <p>1.7.9：门槛改为天使标记（未达标完全禁用）；攻击距离 +1 格、长按右键蓄力投掷，
	 * 命中结算面板近战伤害 + 落点 4 格圣光爆发（14 点真伤）。
	 */
	public static final RegistryObject<Item> SERAPH_SPEAR =
			ITEMS.register("seraph_spear", () -> new com.summy.reliquary.item.SpearItem(
					com.summy.reliquary.item.RitualTiers.SERAPH_SPEAR, 9, -3.0F,
					new Item.Properties().stacksTo(1),
					"item.summy-reliquary.seraph_spear", true));

	/**
	 * 武器：金刀片（1.7.10）—— **无任何基础属性**的中立武器。
	 *
	 * <p>右键**立即投掷**一把可穿透生物的金刀片：固定 5 点物理伤害（不吃本模组任何加成）、
	 * 投掷间隔 0.5 秒、**不消耗本体**，没有天使 / 恶魔门槛。
	 */
	public static final RegistryObject<Item> GOLDEN_RAZOR =
			ITEMS.register("golden_razor", () -> new com.summy.reliquary.item.GoldenRazorItem(
					new Item.Properties().stacksTo(1)));

	/**
	 * 创造页物品的**唯一顺序源**（1.7.10 起按「派系 + 用途」分四块排列，共 46 项）。
	 *
	 * <p>抽成方法是为了让自检能直接拿到这份顺序逐项断言（见 {@code ForgeDevCheck#checkCreativeOrder}）；
	 * 标签页本身只调它一次，所以"页面里看到的顺序"和"自检断言的顺序"永远同源。
	 */
	public static void acceptCreativeItems(java.util.function.Consumer<Item> out) {
						// ===== ① 武器（恶魔线 → 天使线 → 中立）=====
						out.accept(SACRIFICIAL_DAGGER.get());
						out.accept(DARK_ARTS.get());
						out.accept(HOLY_SPEAR.get());
						out.accept(SERAPH_SPEAR.get());
						out.accept(GOLDEN_RAZOR.get());
						// ===== ② 天使线饰品（灵台 → 启示之座 → 加护 → 光环 → 护符）=====
						out.accept(THE_BODY.get());
						out.accept(THE_MIND.get());
						out.accept(THE_SOUL.get());
						out.accept(STAR_OF_BETHLEHEM.get());
						out.accept(FINAL_REVELATION.get());
						out.accept(GODHEAD.get());
						out.accept(SALVATION.get());
						out.accept(HOLY_LIGHT.get());
						out.accept(HOLY_MANTLE.get());
						out.accept(SACRED_HEART.get());
						out.accept(THE_HALO.get());
						out.accept(PENTAGRAM.get());
						// ===== ③ 恶魔线饰品（魂印 → 灵台 → 契约 → 背饰 → 启示之座 → 加护 → 光环）=====
						out.accept(SOURCE_OF_SINS.get());
						out.accept(VIRTUES.get());
						out.accept(SATANIC_BIBLE.get());
						out.accept(THE_MARK.get());
						out.accept(THE_PACT.get());
						out.accept(CEREMONIAL_ROBES.get());
						out.accept(BRIMSTONE.get());
						out.accept(ABADDON.get());
						out.accept(VENGEFUL_SPIRIT.get());
						out.accept(NIGHT_WRAITH.get());
						out.accept(OCCULT_EYE.get());
						out.accept(ABYSS_LORD.get());
						out.accept(DEVIL_CROWN.get());
						// ===== ④ 中立 · 材料与工具（碎片 → 材料 → 工具 → 中立饰品）=====
						out.accept(SIN_FRAGMENT_PRIDE.get());
						out.accept(SIN_FRAGMENT_GLUTTONY.get());
						out.accept(SIN_FRAGMENT_WRATH.get());
						out.accept(SIN_FRAGMENT_ENVY.get());
						out.accept(SIN_FRAGMENT_SLOTH.get());
						out.accept(SIN_FRAGMENT_LUST.get());
						out.accept(SIN_FRAGMENT_GREED.get());
						out.accept(REDEMPTION.get());
						out.accept(ACT_OF_CONTRITION.get());
						out.accept(WOODEN_CROSS.get());
						out.accept(HEART_SHARD.get());
						out.accept(SIX.get());
						out.accept(TRINITY.get());
						out.accept(GENESIS.get());
						out.accept(FREELOADERS_RICE.get());
						out.accept(BANG_BANG_HALO.get());
	}

	/**
	 * 专属创造标签页（1.7.10 起按「派系 + 用途」分四块排列；共 46 项）。
	 *
	 * <p>标签页图标＝根成就图标（{@code duality_stat}，「遗物：七罪」）。
	 */
	public static final RegistryObject<CreativeModeTab> ITEM_GROUP = TABS.register("main", () ->
			CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.summy-reliquary.main"))
					.icon(() -> new ItemStack(DUALITY_STAT.get()))
					.displayItems((parameters, output) -> acceptCreativeItems(item -> output.accept(new ItemStack(item))))
					.build());

	public SummyReliquary() {
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		ITEMS.register(modBus);
		TABS.register(modBus);
		EFFECTS.register(modBus);
		PARTICLES.register(modBus);
		ENTITY_TYPES.register(modBus);
		// 自定义属性（魂心）
		ReliquaryAttributes.ATTRIBUTES.register(modBus);

		// Curios 栏位校验谓词：我们的三个槽位只接受指定物品（从根上杜绝"串栏"）
		ReliquarySlots.registerValidators();

		// 网络通道（客户端按 R 请求切换邦邦女仆状态）
		ReliquaryNetworking.register();

		// 配置：只有吸附半径一项，直接用文件编辑（config/summy_reliquary-common.toml）
		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ReliquaryConfig.SPEC);

		LOGGER.info("[Summy Reliquary] 初始化完成（Forge + Curios）");
	}

	/** 构造本模组命名空间下的标识符 */
	public static ResourceLocation id(String path) {
		return new ResourceLocation(NAMESPACE, path);
	}
}
