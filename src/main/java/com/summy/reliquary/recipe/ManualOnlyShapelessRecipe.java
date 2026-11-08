package com.summy.reliquary.recipe;

import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
	public ManualOnlyShapelessRecipe(ResourceLocation id, String group,
			CraftingBookCategory category, ItemStack result, NonNullList<Ingredient> ingredients) {
		super(id, group, category, result, ingredients);
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return ReliquaryRecipeSerializers.MANUAL_ONLY_SHAPELESS.get();
	}

	/** 委托原版序列化器读取和写入完全相同的 JSON 与网络格式。 */
	public static final class Serializer implements RecipeSerializer<ManualOnlyShapelessRecipe> {
		private static final ShapelessRecipe.Serializer STANDARD = new ShapelessRecipe.Serializer();

		@Override
		public ManualOnlyShapelessRecipe fromJson(ResourceLocation id, JsonObject json) {
			return convert(STANDARD.fromJson(id, json));
		}

		@Override
		public ManualOnlyShapelessRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
			return convert(STANDARD.fromNetwork(id, buffer));
		}

		@Override
		public void toNetwork(FriendlyByteBuf buffer, ManualOnlyShapelessRecipe recipe) {
			STANDARD.toNetwork(buffer, recipe);
		}

		private static ManualOnlyShapelessRecipe convert(ShapelessRecipe recipe) {
			return new ManualOnlyShapelessRecipe(recipe.getId(), recipe.getGroup(), recipe.category(),
					recipe.getResultItem(RegistryAccess.EMPTY), recipe.getIngredients());
		}
	}
}
