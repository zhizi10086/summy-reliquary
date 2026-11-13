package com.summy.reliquary.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/** Forge 全局战利品入口；实际来源过滤与抽取逻辑在 {@link CovenantAcquisition}。 */
public final class CovenantLootModifier extends LootModifier {
	public static final Codec<CovenantLootModifier> CODEC = RecordCodecBuilder.create(instance ->
			codecStart(instance).apply(instance, CovenantLootModifier::new));

	private CovenantLootModifier(LootItemCondition[] conditions) {
		super(conditions);
	}

	@Override
	protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
		CovenantAcquisition.tryGrant(context);
		return generatedLoot;
	}

	@Override
	public Codec<? extends IGlobalLootModifier> codec() {
		return CODEC;
	}
}
