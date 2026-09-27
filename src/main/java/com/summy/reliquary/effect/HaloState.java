package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「光环」的属性倍率：
 * <ul>
 *     <li>佩戴七罪之源且至少激活一项 → ×0.5（并显示「你当知罪」）；</li>
 *     <li>佩戴美德 → ×2（并显示「你已赎罪」）；</li>
 *     <li>佩戴撒旦圣经（1.6.1）→ **×0**（光环属性全部归零，不追加新文案）；</li>
 *     <li>其它情况 → ×1。</li>
 * </ul>
 * 七罪之源与美德同占「魂印」栏位，二者互斥。
 */
public final class HaloState {
	/** 光环提示的附加状态 */
	public enum TooltipState {
		/** 没有任何附加文案 */
		NONE,
		/** 七罪之源 + 至少一项激活 */
		SIN,
		/** 佩戴美德 */
		VIRTUE,
		/** 佩戴撒旦圣经（1.6.1）：光环属性归零 */
		SATANIC
	}

	private HaloState() {
	}

	/** 光环属性倍率 */
	public static double multiplier(LivingEntity wearer) {
		return switch (state(wearer)) {
			case SIN -> ReliquaryConfig.haloSinMultiplier();
			case VIRTUE -> ReliquaryConfig.haloVirtueMultiplier();
			// 撒旦圣经：光环属性归零（信徒的光被契约吞掉了）
			case SATANIC -> 0.0D;
			default -> 1.0D;
		};
	}

	/** 当前处于哪种状态（客户端读同步缓存，服务端读存档数据） */
	public static TooltipState state(LivingEntity wearer) {
		if (wearer == null) {
			return TooltipState.NONE;
		}
		// 撒旦圣经优先：它占着魂印栏，光环整体归零
		if (CurioHelper.wears(wearer, SummyReliquary.SATANIC_BIBLE.get())) {
			return TooltipState.SATANIC;
		}
		if (CurioHelper.wears(wearer, SummyReliquary.VIRTUES.get())) {
			return TooltipState.VIRTUE;
		}
		// 只有"已激活但还没赎罪"的罪才会让光环属性减半；赎清后惩罚解除
		if (CurioHelper.wears(wearer, SummyReliquary.SOURCE_OF_SINS.get()) && SinManager.hasAnyUnredeemed(wearer)) {
			return TooltipState.SIN;
		}
		return TooltipState.NONE;
	}
}
