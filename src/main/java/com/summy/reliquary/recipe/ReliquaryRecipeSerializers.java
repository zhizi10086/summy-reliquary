package com.summy.reliquary.recipe;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 本模组的配方序列化器。 */
public final class ReliquaryRecipeSerializers {
	public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
			DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, SummyReliquary.NAMESPACE);

	public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<ManualOnlyShapelessRecipe>>
			MANUAL_ONLY_SHAPELESS = SERIALIZERS.register("manual_only_shapeless",
					ManualOnlyShapelessRecipe.Serializer::new);

	private ReliquaryRecipeSerializers() {
	}
}
