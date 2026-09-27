package com.summy.reliquary.sin;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.network.chat.Component;

/**
 * 七罪之源提示里每一项罪的说明文本。
 *
 * <p>三种状态各一段：未激活显示**激活条件**、已激活显示**效果**、已赎罪显示**赎罪后效果**。
 * 数字全部读 {@code [sins]} 配置，所以改配置后提示会跟着变。
 */
public final class SinDescriptions {
	private SinDescriptions() {
	}

	/** 第二行：未激活 = 触发条件；激活 / 已赎罪 = 增益 */
	public static Component secondLine(Sin sin, SinManager.SinState state) {
		String suffix = state == SinManager.SinState.UNACTIVATED ? "condition" : "buff";
		return Component.translatable(key(sin, suffix), args(sin, suffix));
	}

	/** 第三行：激活 = 减益；已赎罪 = 赎罪文本；未激活 = null（这一行不显示） */
	public static Component thirdLine(Sin sin, SinManager.SinState state) {
		if (state == SinManager.SinState.UNACTIVATED) {
			return null;
		}
		String suffix = state == SinManager.SinState.ACTIVATED ? "debuff" : "redeemed";
		return Component.translatable(key(sin, suffix), args(sin, suffix));
	}

	private static String key(Sin sin, String suffix) {
		return "item.summy-reliquary.sin." + sin.id() + "." + suffix;
	}

	/** 各段文本的参数（全部来自 [sins] 配置）；没有占位符的段返回空数组 */
	private static Object[] args(Sin sin, String suffix) {
		return switch (sin) {
			case PRIDE -> switch (suffix) {
				case "condition" -> new Object[]{ReliquaryConfig.prideKillRequired()};
				case "buff" -> new Object[]{ReliquaryConfig.prideDamagePerPercent()};
				case "debuff" -> new Object[]{ReliquaryConfig.prideIncomingDamagePercent()};
				default -> new Object[0];
			};
			case GREED -> switch (suffix) {
				case "condition" -> new Object[]{ReliquaryConfig.greedDiamondThreshold()};
				case "buff" -> new Object[]{ReliquaryConfig.greedDamagePerDiamondPercent(),
						ReliquaryConfig.greedDamageCapPercent()};
				case "debuff" -> new Object[]{ReliquaryConfig.greedDiamondThreshold(),
						Math.round(ReliquaryConfig.greedLowDiamondDamageFactor() * 100.0D),
						ReliquaryConfig.greedDeathDiamondMin(), ReliquaryConfig.greedDeathDiamondMax()};
				default -> new Object[0];
			};
			case LUST -> switch (suffix) {
				case "condition" -> new Object[]{ReliquaryConfig.lustBreedRequired()};
				case "buff" -> new Object[]{ReliquaryConfig.lustStripArmorPercent()};
				case "debuff" -> new Object[]{ReliquaryConfig.lustArmorReductionPercent(),
						ReliquaryConfig.lustSelfStripPercent()};
				default -> new Object[0];
			};
			case ENVY -> switch (suffix) {
				case "buff" -> new Object[]{ReliquaryConfig.envyBonusPercent()};
				case "debuff" -> new Object[]{ReliquaryConfig.envyHostileRadius()};
				default -> new Object[0];
			};
			case GLUTTONY -> switch (suffix) {
				case "condition" -> new Object[]{ReliquaryConfig.gluttonyMealRequired(),
						ReliquaryConfig.gluttonySaturationThreshold()};
				case "buff" -> new Object[]{ReliquaryConfig.gluttonyKillHeal(), ReliquaryConfig.gluttonyKillFood()};
				case "debuff" -> new Object[]{ReliquaryConfig.gluttonyFoodCap(),
						ReliquaryConfig.gluttonyDrainSeconds(), ReliquaryConfig.gluttonyWeakFoodThreshold()};
				default -> new Object[0];
			};
			case WRATH -> switch (suffix) {
				case "condition" -> new Object[]{ReliquaryConfig.wrathKillRequired()};
				case "buff" -> new Object[]{ReliquaryConfig.wrathRandomMin(), ReliquaryConfig.wrathRandomMax()};
				case "debuff" -> new Object[]{ReliquaryConfig.wrathSelfHitPercent()};
				case "redeemed" -> new Object[]{ReliquaryConfig.wrathRandomMaxRedeemed()};
				default -> new Object[0];
			};
			case SLOTH -> switch (suffix) {
				case "condition" -> new Object[]{ReliquaryConfig.slothSleepRequired()};
				case "buff" -> new Object[]{ReliquaryConfig.slothResistanceAmplifier() + 1};
				case "debuff" -> new Object[]{ReliquaryConfig.slothSlowdownPercent()};
				case "redeemed" -> new Object[]{ReliquaryConfig.slothResistanceAmplifierRedeemed() + 1};
				default -> new Object[0];
			};
		};
	}
}
