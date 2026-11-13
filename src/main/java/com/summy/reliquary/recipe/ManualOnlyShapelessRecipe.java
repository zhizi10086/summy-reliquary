package com.summy.reliquary.recipe;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * 与原版无序配方行为一致，只通过不同序列化器标识声明“不参与 Create 自动搅拌”。
 *
 * <p>配方类型仍是 {@code minecraft:crafting}，因此人工工作台、JEI 普通合成与配方书不受影响。
 */
public class ManualOnlyShapelessRecipe extends ShapelessRecipe {
	public ManualOnlyShapelessRecipe(String group, CraftingBookCategory category,
			ItemStack result, NonNullList<Ingredient> ingredients) {
		super(group, category, result, ingredients);
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return ReliquaryRecipeSerializers.MANUAL_ONLY_SHAPELESS.get();
	}

	/** 委托原版序列化器读取和写入完全相同的 JSON 与网络格式。 */
	public static final class Serializer implements RecipeSerializer<ManualOnlyShapelessRecipe> {
		private static final ShapelessRecipe.Serializer STANDARD = new ShapelessRecipe.Serializer();
		private static final MapCodec<ManualOnlyShapelessRecipe> CODEC =
				STANDARD.codec().xmap(Serializer::convert, recipe -> recipe);
		private static final StreamCodec<RegistryFriendlyByteBuf, ManualOnlyShapelessRecipe> STREAM_CODEC =
				STANDARD.streamCodec().map(Serializer::convert, recipe -> recipe);

		@Override
		public MapCodec<ManualOnlyShapelessRecipe> codec() {
			return CODEC;
		}

		@Override
		public StreamCodec<RegistryFriendlyByteBuf, ManualOnlyShapelessRecipe> streamCodec() {
			return STREAM_CODEC;
		}

		private static ManualOnlyShapelessRecipe convert(ShapelessRecipe recipe) {
			return new ManualOnlyShapelessRecipe(recipe.getGroup(), recipe.category(),
					recipe.getResultItem(RegistryAccess.EMPTY), recipe.getIngredients());
		}
	}
}
