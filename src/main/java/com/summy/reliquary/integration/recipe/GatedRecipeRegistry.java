package com.summy.reliquary.integration.recipe;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.effect.EvilUnlock;
import com.summy.reliquary.effect.RedemptionRecipeGate;
import com.summy.reliquary.effect.SpiritAltarRecipeGate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 所有玩家门禁配方的统一索引。
 *
 * <p>业务判据仍由灵台、邪恶度与赎罪三个原门禁负责；本类只合并配方 ID 与产物，
 * 供 JEI、反自动化守卫和拆解标签自检复用，避免再维护第四条业务规则。
 */
public final class GatedRecipeRegistry {
	/** 供其它模组或数据包引用的门禁产物标签。 */
	public static final ResourceLocation OUTPUT_TAG_ID =
			SummyReliquary.id("gated_recipe_outputs");

	private static final Map<ResourceLocation, Item> RECIPES = buildRecipes();
	private static final Set<ResourceLocation> RECIPE_IDS =
			Collections.unmodifiableSet(new LinkedHashSet<>(RECIPES.keySet()));
	private static final Set<Item> RESULT_ITEMS =
			Collections.unmodifiableSet(new LinkedHashSet<>(RECIPES.values()));

	private GatedRecipeRegistry() {
	}

	private static Map<ResourceLocation, Item> buildRecipes() {
		Map<ResourceLocation, Item> recipes = new LinkedHashMap<>();
		recipes.putAll(SpiritAltarRecipeGate.gatedRecipeItems());
		for (ResourceLocation id : EvilUnlock.gatedRecipes().keySet()) {
			Item item = BuiltInRegistries.ITEM.get(id);
			if (item == null) {
				throw new IllegalStateException("门禁配方产物未注册：" + id);
			}
			recipes.put(id, item);
		}
		recipes.put(RedemptionRecipeGate.recipeId(), SummyReliquary.REDEMPTION.get());
		return Collections.unmodifiableMap(recipes);
	}

	/** 全部受玩家门禁保护的配方 ID。 */
	public static Set<ResourceLocation> recipeIds() {
		return RECIPE_IDS;
	}

	/** 全部门禁配方直接产物。 */
	public static Set<Item> resultItems() {
		return RESULT_ITEMS;
	}

	public static boolean isGatedRecipe(ResourceLocation id) {
		return id != null && RECIPES.containsKey(id);
	}

	public static boolean isGatedRecipe(RecipeHolder<?> holder) {
		return holder != null && isGatedRecipe(holder.id());
	}

	public static boolean isGatedRecipe(Recipe<?> recipe, HolderLookup.Provider registries) {
		return recipe instanceof com.summy.reliquary.recipe.ManualOnlyShapelessRecipe
				|| recipe != null && isGatedResult(recipe.getResultItem(registries));
	}

	public static boolean isGatedResult(ItemStack stack) {
		return stack != null && !stack.isEmpty() && RESULT_ITEMS.contains(stack.getItem());
	}
}
