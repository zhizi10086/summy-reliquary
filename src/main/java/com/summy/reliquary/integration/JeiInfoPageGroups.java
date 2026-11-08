package com.summy.reliquary.integration;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEI 信息主题与物品的唯一映射来源。
 *
 * <p>刻意不引用任何 JEI 类型，使开发自检可以在未安装 JEI 的实例中验证映射。
 */
public final class JeiInfoPageGroups {
	private JeiInfoPageGroups() {
	}

	public static Map<String, List<Item>> all() {
		Map<String, List<Item>> groups = new LinkedHashMap<>();
		groups.put("sin_system", List.of(
				SummyReliquary.SOURCE_OF_SINS.get(),
				SummyReliquary.VIRTUES.get(),
				SummyReliquary.SIN_FRAGMENT_PRIDE.get(),
				SummyReliquary.SIN_FRAGMENT_GLUTTONY.get(),
				SummyReliquary.SIN_FRAGMENT_WRATH.get(),
				SummyReliquary.SIN_FRAGMENT_ENVY.get(),
				SummyReliquary.SIN_FRAGMENT_SLOTH.get(),
				SummyReliquary.SIN_FRAGMENT_LUST.get(),
				SummyReliquary.SIN_FRAGMENT_GREED.get(),
				SummyReliquary.REDEMPTION.get()));
		groups.put("satanic_bible", List.of(SummyReliquary.SATANIC_BIBLE.get()));
		groups.put("pentagram", List.of(SummyReliquary.PENTAGRAM.get()));
		groups.put("act_of_contrition", List.of(SummyReliquary.ACT_OF_CONTRITION.get()));
		groups.put("genesis", List.of(SummyReliquary.GENESIS.get()));
		groups.put("golden_razor", List.of(SummyReliquary.GOLDEN_RAZOR.get()));
		groups.put("the_pact", List.of(SummyReliquary.THE_PACT.get()));
		groups.put("six_and_mark", List.of(
				SummyReliquary.SIX.get(),
				SummyReliquary.THE_MARK.get()));
		groups.put("salvation", List.of(SummyReliquary.SALVATION.get()));
		groups.put("holy_light", List.of(SummyReliquary.HOLY_LIGHT.get()));
		groups.put("heart_materials", List.of(
				SummyReliquary.HEART_SHARD.get(),
				SummyReliquary.BROKEN_HEART.get()));
		return Map.copyOf(groups);
	}
}
