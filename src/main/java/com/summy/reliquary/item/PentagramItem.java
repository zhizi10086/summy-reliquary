package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.DemonDeal;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 五芒星（Pentagram，1.5.8）：装进 Curios **自带**的「护符」（{@code charm}）栏位。
 *
 * <p>它是完成「罪无可赦」（七罪同时处于已激活）后的**一次性发放**奖励，没有天使门槛，
 * 也没有任何获取配方；老存档靠每秒兜底补发一次，也可以用 OP 指令
 * {@code /summyreliquary pentagram <query|grant|reset> <玩家>} 查询与补发。
 *
 * <p>效果只有一条：近战伤害 +1（配置 {@code [pentagram] attack_damage}）。
 * 提示文本里那些「交易」的话只在**累计持有 300 秒**之后出现一次，
 * 之后提示里会一直多出那一行——它是"它在对你说话"。
 */
public class PentagramItem extends Item implements ICurioItem {
	/** 暗红（那句话的整体色） */
	public static final int DARK_RED = 0x8B0000;
	/** 呼吸到的最亮红（「埋葬无尽灵魂」） */
	public static final int BREATHE_PEAK = 0xC03030;
	/** 灰（「——他在对你说话」） */
	public static final int GRAY = 0xAAAAAA;
	/** 深红：恶魔话语出现在**物品提示**里时用（提示框背景更亮，要比聊天里的暗红亮一档） */
	public static final int TOOLTIP_RED = 0xB22222;
	/** 「埋葬无尽灵魂」的慢速呼吸周期（毫秒） */
	public static final long BREATHE_PERIOD_MS = 5000L;

	public PentagramItem(Properties properties) {
		super(properties);
	}

	/** 只允许装进 Curios 自带的「护符」栏位；不加天使门槛（门槛是「罪无可赦」） */
	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.CHARM);
	}

	@Override
	public List<Component> getSlotsTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public List<Component> getAttributesTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 风味行：并不普通的一枚护符（灰）
		tooltip.add(Component.translatable("item.summy-reliquary.pentagram.tagline")
				.withStyle(ChatFormatting.GRAY));
		if (!ReliquaryTooltips.shiftDown()) {
			tooltip.add(ReliquaryTooltips.shiftHint());
			return;
		}
		LivingEntity self = ReliquaryTooltips.localPlayer();
		boolean spoken = PlayerFlags.isPentagramSpoken(self);
		// 1.5.10：那句 300 秒消息触发后，Shift 描述只留「近战伤害 +x」这一行
		tooltip.addAll(shiftLines(spoken));
		// 那句消息发过之后，提示里会多出"恶魔话语"；按契约状态分三态（见 spokenMode 的说明）。
		if (spoken) {
			// 1.7.2：第 4 个参数泛化成"恶魔交易是否已关闭"（已启示 ‖ 已放弃一切 ‖ 持有圣心）
			switch (spokenMode(true, PlayerFlags.isDemon(self), PlayerFlags.isDemonSealed(self),
					com.summy.reliquary.effect.DemonDeal.tradeClosed(
							self instanceof Player player ? player : null))) {
				case 2 -> tooltip.addAll(sealedLines());
				case 1 -> tooltip.addAll(spokenLines());
				// 0：持恶魔标记 / 交易已关闭 → 原来那两行移除，也不补新行
				default -> {
				}
			}
		}
	}

	/**
	 * 提示里「恶魔话语」那部分该显示什么（1.5.9）——抽成纯函数便于自检逐态断言：
	 * <ul>
	 *     <li>{@code 0} = 不显示（还没听过那句交易；已签下契约、当前持恶魔标记；或**恶魔交易已关闭**，
	 *     1.7.2 起"关闭"含三种：已获取启示 / 已放弃一切 / 持有圣心）；</li>
	 *     <li>{@code 1} = 原来的两行（"到那埋葬无尽灵魂的地方……" + "——他在对你说话"）；</li>
	 *     <li>{@code 2} = 契约依然作数的两行（曾签约，之后用痛悔短祷换回了天使标记）。</li>
	 * </ul>
	 */
	public static int spokenMode(boolean spoken, boolean demon, boolean sealed, boolean tradeClosed) {
		if (!spoken || demon || tradeClosed) {
			return 0;
		}
		return sealed ? 2 : 1;
	}

	/**
	 * Shift 描述（灰，不斜体）——1.5.10 起按"那句 300 秒消息是否已触发"分两态：
	 * 未触发 = 三行（近战伤害 / 对的，只是这样 / ……真的吗？）；已触发 = 只留第一行。
	 */
	public static List<Component> shiftLines(boolean spoken) {
		// 1.6.3：五芒星属中立/材料派系 → 属性名灰、数值白
		Component damage = ReliquaryTooltips.statComponent(
				com.summy.reliquary.text.ReliquaryFaction.NEUTRAL,
				"item.summy-reliquary.pentagram.shift.1",
				formatNumber(ReliquaryConfig.pentagramAttackDamage()));
		if (spoken) {
			return List.of(damage);
		}
		return List.of(damage,
				Component.translatable("item.summy-reliquary.pentagram.shift.2")
						.withStyle(ChatFormatting.GRAY),
				Component.translatable("item.summy-reliquary.pentagram.shift.3")
						.withStyle(ChatFormatting.GRAY));
	}

	/** 那句话之后的追加两行：暗红斜体（含实时呼吸）+ 灰色斜体 */
	public static List<Component> spokenLines() {
		return List.of(
				breathingLine("item.summy-reliquary.pentagram.shift.trade"),
				Component.translatable("item.summy-reliquary.pentagram.shift.whisper")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(GRAY)).withItalic(true)));
	}

	/**
	 * 曾签约、后用痛悔短祷换回天使标记时的两行（1.5.9）：
	 * 「我们的约定依然作数」「我还在那个地方等你」——恶魔话语，深红 + 斜体。
	 */
	public static List<Component> sealedLines() {
		Style style = Style.EMPTY.withColor(TextColor.fromRgb(TOOLTIP_RED)).withItalic(true);
		return List.of(
				Component.translatable("item.summy-reliquary.pentagram.sealed.1").withStyle(style),
				Component.translatable("item.summy-reliquary.pentagram.sealed.2").withStyle(style));
	}

	// ==================== 长按右键：签约（1.5.9） ====================

	/** 蓄力时长 = [demon_deal] charge_seconds（默认 5 秒 = 100 tick） */
	@Override
	public int getUseDuration(ItemStack stack) {
		return DemonDeal.chargeTicks();
	}

	/**
	 * 右键使用：只有在**灵魂沙峡谷里、且已经听过邀请**时才进入蓄力；
	 * 不在峡谷时提示「这里显然不是那个"埋葬无尽灵魂"的地方」（按玩家 5 秒节流）。
	 */
	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide()) {
			// 客户端也走一遍便宜的门槛，避免"在不能签约的地方按右键 → 举着 5 秒却没反应"的空动作
			// 1.7.2：封锁条件与 DemonDeal.isQualified 对齐（已启示 / 已放弃一切 / 持有圣心）
			boolean canTry = ReliquaryConfig.enableDemonDeal()
					&& PlayerFlags.isPentagramSpoken(player)
					&& !PlayerFlags.isDemon(player)
					&& !com.summy.reliquary.effect.DemonDeal.tradeClosed(player)
					&& PlayerFlags.isDemonInvited(player)
					&& DemonDeal.inSoulSandValley(player);
			return canTry ? InteractionResultHolder.consume(stack) : InteractionResultHolder.pass(stack);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		// 1.5.10 门槛顺序：
		//   ① 总开关关 / ② 当前已经是恶魔（契约生效中）/ ③ 还没听过那句 300 秒消息 → 一律"无任何反应"
		// 1.6.10 追加：④ 获取过启示 → 恶魔交易已关闭，也一律"无任何反应"（不给任何提示）
		// 1.7.2：④ 扩展成 DemonDeal.tradeClosed（已启示 / 已放弃一切 / 持有圣心）
		if (!ReliquaryConfig.enableDemonDeal() || PlayerFlags.isDemon(serverPlayer)
				|| !PlayerFlags.isPentagramSpoken(serverPlayer)
				|| com.summy.reliquary.effect.DemonDeal.tradeClosed(serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		if (!DemonDeal.canSign(serverPlayer)) {
			// 不在峡谷 → 提示；在峡谷但还没听过邀请 → 静默不可用
			DemonDeal.speakWrongPlaceIfNeeded(serverPlayer, serverPlayer.serverLevel().getGameTime());
			return InteractionResultHolder.fail(stack);
		}
		serverPlayer.startUsingItem(hand);
		return InteractionResultHolder.consume(stack);
	}

	// 1.5.10：不再在行动栏提示签署进度 —— 改由镜头 FOV 收缩承担反馈。
	// 1.6.2：按需求**删掉了手持贴图拉伸**（只有视野收缩），见 SummyReliquaryClient 的 ComputeFov。

	/** 蓄满 5 秒（自然结束）时签约 */
	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity living) {
		if (!level.isClientSide() && living instanceof ServerPlayer player) {
			DemonDeal.sign(player);
		}
		return stack;
	}

	/** 中途松手：不足 5 秒就什么都不做（静默重置） */
	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
		if (level.isClientSide() || !(living instanceof ServerPlayer player)) {
			return;
		}
		int charged = DemonDeal.chargeTicks() - Math.max(0, timeLeft);
		if (charged >= DemonDeal.chargeTicks()) {
			DemonDeal.sign(player);
		}
	}

	/**
	 * 那条一次性聊天消息：整体暗红斜体，其中「埋葬无尽灵魂」用慢速呼吸色。
	 *
	 * <p>聊天组件是**发出瞬间的快照**（聊天行不会逐帧重算样式），所以这里的呼吸只是"发出去时的颜色"；
	 * 真正的实时呼吸只在提示（Shift 行）里成立。
	 */
	public static Component chatMessage() {
		return breathingLine("message.summy-reliquary.pentagram.message");
	}

	/**
	 * 把一段文本拆成「前半句 + 呼吸短语 + 后半句」三段组件。
	 *
	 * <p>短语本身从语言键 {@code item.summy-reliquary.pentagram.phrase} 读取，
	 * 这样中英两套文本都能命中同一段强调；找不到短语时整行用暗红（不会崩）。
	 */
	public static MutableComponent breathingLine(String translationKey) {
		String text = Component.translatable(translationKey).getString();
		String phrase = Component.translatable("item.summy-reliquary.pentagram.phrase").getString();
		int index = phrase.isEmpty() ? -1 : text.indexOf(phrase);
		if (index < 0) {
			return Component.literal(text).withStyle(
					Style.EMPTY.withColor(TextColor.fromRgb(DARK_RED)).withItalic(true));
		}
		int breatheColor = SinTexts.breathing(DARK_RED, BREATHE_PEAK, BREATHE_PERIOD_MS);
		return Component.empty()
				.append(Component.literal(text.substring(0, index)).withStyle(darkRed()))
				.append(Component.literal(phrase).withStyle(
						Style.EMPTY.withColor(TextColor.fromRgb(breatheColor)).withItalic(true)))
				.append(Component.literal(text.substring(index + phrase.length())).withStyle(darkRed()));
	}

	private static Style darkRed() {
		return Style.EMPTY.withColor(TextColor.fromRgb(DARK_RED)).withItalic(true);
	}

	/** 配置里是 double，提示里去掉多余的小数点（1.0 → 1） */
	private static String formatNumber(double value) {
		return Math.abs(value - Math.rint(value)) < 1.0E-6D
				? String.valueOf((long) Math.rint(value))
				: String.valueOf(value);
	}
}
