package com.summy.reliquary.recipe;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 本模组的配方序列化器。 */
public final class ReliquaryRecipeSerializers {
	public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
			DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, SummyReliquary.NAMESPACE);

	public static final RegistryObject<RecipeSerializer<ManualOnlyShapelessRecipe>>
			MANUAL_ONLY_SHAPELESS = SERIALIZERS.register("manual_only_shapeless",
					ManualOnlyShapelessRecipe.Serializer::new);

	private ReliquaryRecipeSerializers() {
	}
}
