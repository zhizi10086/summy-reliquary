package com.summy.reliquary.text;

/**
 * 本模组的「派系」配色（1.6.3）：
 *
 * <ul>
 *     <li><b>天使线</b>（灵台三件套 / 星 / 天启 / 神性 / 救恩 / 圣光 / 斗篷 / 圣心 / 美德）——
 *     物品名是白→金对称渐变，属性名淡金、数值亮金；</li>
 *     <li><b>恶魔线</b>（契约 / 法袍 / 圣经 / 复仇之魂 / 咒印 / 夜之幽魂）——
 *     物品名是暗红底 + 亮红扫光，属性名深红、数值亮红；</li>
 *     <li><b>中立 / 材料</b>——物品名保持原版白，属性名灰、数值白。</li>
 * </ul>
 *
 * <p>约定的提示写法是 {@code 属性名|数值}（如 {@code 移动速度|+20%}）：属性名用 {@link #nameColor()}、
 * 数值用 {@link #valueColor()}，中间留一个空格。不含 {@code |} 的行视为叙述句，保持原样。
 */
public enum ReliquaryFaction {
	/** 天使线 */
	ANGEL(0xFFE4B5, 0xFFD700),
	/** 恶魔线 */
	DEMON(0xC03030, 0xFF6B6B),
	/** 中立 / 材料 */
	NEUTRAL(0xAAAAAA, 0xFFFFFF);

	/** 属性名颜色（"暗一档"，好读且不抢数值） */
	private final int nameColor;
	/** 数值颜色（"亮一档"，让数字醒目） */
	private final int valueColor;

	ReliquaryFaction(int nameColor, int valueColor) {
		this.nameColor = nameColor;
		this.valueColor = valueColor;
	}

	public int nameColor() {
		return nameColor;
	}

	public int valueColor() {
		return valueColor;
	}
}
