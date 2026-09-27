package com.summy.reliquary.item;

import com.summy.reliquary.util.CurioHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

/**
 * 饰品类的公共小工具：栏位判定 + 「不可重复佩戴」+ 隐藏 Curios 自动提示行。
 */
public final class CurioItemSupport {
	/** 1.5.8 诊断：已经打过日志的「拒绝原因 + 物品」，避免每帧刷屏 */
	private static final Set<String> LOGGED_REJECTIONS = ConcurrentHashMap.newKeySet();

	private CurioItemSupport() {
	}

	/** 允许装进指定栏位、且同种物品还没被佩戴过 */
	public static boolean canEquipInto(SlotContext context, ItemStack stack, String slotId) {
		return canEquipInto(context, stack, slotId, false);
	}

	/**
	 * 同上，但可以额外要求玩家拥有「天使」标记。
	 *
	 * <p>灵台三件套 / 伯列恒之星 / 终末天启都必须先完成「纯洁之人」拿到天使标记才能佩戴。
	 * 客户端读同步缓存、服务端读存档，两条路径都在 {@code PlayerFlags.hasAngel} 里处理。
	 */
	public static boolean canEquipInto(SlotContext context, ItemStack stack, String slotId, boolean requiresAngel) {
		return canEquipInto(context, stack, slotId, requiresAngel, null, null);
	}

	/**
	 * 同上，但门槛可以换成任意自定义条件（例如「仪式法袍」要求的"已签约"）。
	 *
	 * <p>和天使门槛一样，**读档阶段（本模组数据未就绪）一律放行**，否则 Curios 在
	 * {@code readTag} 里重新校验时会把本来就戴着的饰品判成不合法卸回背包（1.5.9 修过的坑）。
	 *
	 * @param extraGate   额外门槛；null = 不检查
	 * @param gateReason  被拒时写进日志的原因（仅诊断用）
	 */
	public static boolean canEquipInto(SlotContext context, ItemStack stack, String slotId,
			boolean requiresAngel, java.util.function.Predicate<net.minecraft.world.entity.LivingEntity> extraGate,
			String gateReason) {
		if (!slotId.equals(context.identifier())) {
			return reject("栏位不符", "期望 " + slotId + "、实际 " + context.identifier(), context, stack);
		}
		// 1.5.7：Curios 会在**登录反序列化 / 重算**时重新跑这里的判断，那一刻玩家的本模组数据
		// （天使标记等）可能还没就绪，于是门槛会把"本来就戴着的饰品"判成不合法、卸回背包。
		// 因此：只要这件物品**已经就在这一格里**，就直接放行 —— 这是重算，不是新装备。
		if (alreadyInSlot(context, stack)) {
			return true;
		}
		// 拿不到具体格（index < 0）时同样不做限制，避免误伤
		if (context.index() < 0) {
			return true;
		}
		// 1.5.9：**本模组数据还没就绪**（登录读档阶段 —— Curios 会在 readTag 里逐个校验栏位内容，
		// 而那一刻 Forge 还没把 summy_reliquary 根标签写进实体）→ 一律放行。
		// 否则"本来就戴着"的门槛饰品会被判成不合法、退回背包（日志里能看到 readTag 的调用栈）。
		// 这里刻意跳过"不可重复佩戴"检查：读档窗口内任何拒绝都会导致卸装。
		boolean gated = requiresAngel || extraGate != null;
		if (gated && !com.summy.reliquary.effect.PlayerFlags.isDataReady(context.entity())) {
			allowWhileLoading(context, stack);
			return true;
		}
		if (requiresAngel && !com.summy.reliquary.effect.PlayerFlags.hasAngel(context.entity())) {
			reject("缺少天使标记", extraAngelDiagnosis(context), context, stack);
			return false;
		}
		if (extraGate != null) {
			net.minecraft.world.entity.LivingEntity entity = context.entity();
			if (entity == null || !extraGate.test(entity)) {
				reject(gateReason == null ? "未满足额外门槛" : gateReason, "", context, stack);
				return false;
			}
		}
		if (CurioHelper.isAlreadyEquipped(context, stack)) {
			return reject("同种物品已在其它格", "", context, stack);
		}
		return true;
	}

	/** 读档阶段放行时打一条去重 INFO（与拒绝日志同一套去重键，便于对照） */
	private static void allowWhileLoading(SlotContext context, ItemStack stack) {
		try {
			String itemId = stack.isEmpty() ? "（空）"
					: String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(stack.getItem()));
			String key = "数据未就绪（读档阶段）|" + itemId;
			if (LOGGED_REJECTIONS.add(key)) {
				com.summy.reliquary.SummyReliquary.LOGGER.info(
						"[Summy Reliquary] canEquip 放行：原因=数据未就绪（读档阶段）、物品={}、玩家={}、栏位={}#{}",
						itemId,
						context.entity() == null ? "（无）" : context.entity().getName().getString(),
						context.identifier(), context.index());
			}
		} catch (Throwable ignored) {
			// 诊断日志绝不影响玩法
		}
	}

	/**
	 * 1.5.8 诊断：`canEquip` 被拒时打一条 INFO（原因 + 物品 + 玩家 + 线程 + 调用栈前 8 帧）。
	 *
	 * <p>按「原因 + 物品」去重，所以正常的玩法操作不会刷屏；"重登后天使门槛饰品掉回背包"
	 * 这类问题能直接从调用栈看出是谁在什么时候把 `canEquip` 判成了 false。
	 */
	private static boolean reject(String reason, String detail, SlotContext context, ItemStack stack) {
		try {
			String itemId = stack.isEmpty() ? "（空）"
					: String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(stack.getItem()));
			String key = reason + "|" + itemId;
			if (LOGGED_REJECTIONS.add(key)) {
				StackTraceElement[] frames = Thread.currentThread().getStackTrace();
				StringBuilder trace = new StringBuilder();
				// 前 2 帧是 getStackTrace / reject 自身，跳过
				for (int index = 2; index < Math.min(frames.length, 10); index++) {
					trace.append("\n    at ").append(frames[index]);
				}
				com.summy.reliquary.SummyReliquary.LOGGER.info(
						"[Summy Reliquary] canEquip 被拒：原因={}、物品={}、玩家={}、客户端={}、栏位={}#{}、线程={}{}{}",
						reason, itemId,
						context.entity() == null ? "（无）" : context.entity().getName().getString(),
						context.entity() != null && context.entity().level().isClientSide(),
						context.identifier(), context.index(),
						Thread.currentThread().getName(),
						detail.isEmpty() ? "" : "、" + detail,
						trace.toString());
			}
		} catch (Throwable ignored) {
			// 诊断日志绝不影响玩法
		}
		return false;
	}

	/** 缺天使标记时的额外诊断：存档根标签到底有没有、客户端还是服务端、格子号是多少 */
	private static String extraAngelDiagnosis(SlotContext context) {
		var entity = context.entity();
		if (entity == null) {
			return "实体为空";
		}
		net.minecraft.nbt.CompoundTag data = entity.getPersistentData();
		boolean hasRoot = data.contains(com.summy.reliquary.sin.SinManager.ROOT,
				net.minecraft.nbt.CompoundTag.TAG_COMPOUND);
		boolean hasAngelKey = hasRoot
				&& data.getCompound(com.summy.reliquary.sin.SinManager.ROOT).contains("angel");
		return "persistentData 含 " + com.summy.reliquary.sin.SinManager.ROOT + "=" + hasRoot
				+ "、root 含 angel 键=" + hasAngelKey
				+ "、isClientSide=" + entity.level().isClientSide()
				+ "、context.index()=" + context.index();
	}

	/** 该物品是否本来就在 context 指向的那一格（用于识别"登录重算"而不是"新装备"） */
	private static boolean alreadyInSlot(SlotContext context, ItemStack stack) {
		if (stack.isEmpty() || context.index() < 0) {
			return false;
		}
		net.minecraft.world.entity.LivingEntity entity = context.entity();
		if (entity == null) {
			return false;
		}
		var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity).orElse(null);
		if (handler == null) {
			return false;
		}
		var stacksHandler = handler.getCurios().get(context.identifier());
		if (stacksHandler == null || context.index() >= stacksHandler.getSlots()) {
			return false;
		}
		ItemStack current = stacksHandler.getStacks().getStackInSlot(context.index());
		return !current.isEmpty() && current.is(stack.getItem());
	}

	/** 隐藏 Curios 自动生成的栏位行（我们自己/Curios 的栏位行由别处负责） */
	public static List<Component> keepLines(List<Component> lines) {
		return lines;
	}

	/** 空属性表：本模组的属性修正统一由 AttributeManager 管理 */
	public static Multimap<Attribute, AttributeModifier> noAttributes() {
		return ImmutableMultimap.of();
	}
}
