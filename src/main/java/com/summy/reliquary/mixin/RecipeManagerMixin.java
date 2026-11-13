package com.summy.reliquary.mixin;

import com.summy.reliquary.integration.recipe.GatedRecipeContext;
import com.summy.reliquary.integration.recipe.GatedRecipeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 无玩家手动合成上下文时，从配方查找结果中剔除 30 张门禁配方。 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
	@Inject(
			method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
			at = @At("RETURN"),
			cancellable = true,
			require = 1
	)
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void summyReliquary$filterAutomatedRecipe(RecipeType type, RecipeInput input,
			Level level, CallbackInfoReturnable<Optional> cir) {
		Optional<?> found = cir.getReturnValue();
		if (GatedRecipeContext.isManualCrafting() || found == null || found.isEmpty()) {
			return;
		}
		if (found.get() instanceof RecipeHolder<?> holder && GatedRecipeRegistry.isGatedRecipe(holder)) {
			cir.setReturnValue(Optional.empty());
		}
	}

	@Inject(
			method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
			at = @At("RETURN"),
			cancellable = true,
			require = 1
	)
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void summyReliquary$filterAutomatedRecipeById(RecipeType type, RecipeInput input,
			Level level, ResourceLocation id, CallbackInfoReturnable<Optional> cir) {
		Optional<?> found = cir.getReturnValue();
		if (GatedRecipeContext.isManualCrafting() || found == null || found.isEmpty()) {
			return;
		}
		if (found.get() instanceof RecipeHolder<?> holder && GatedRecipeRegistry.isGatedRecipe(holder)) {
			cir.setReturnValue(Optional.empty());
		}
	}

	@Inject(
			method = "getRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;",
			at = @At("RETURN"),
			cancellable = true,
			require = 1
	)
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void summyReliquary$filterAutomatedRecipes(RecipeType type, RecipeInput input,
			Level level, CallbackInfoReturnable<List> cir) {
		if (GatedRecipeContext.isManualCrafting()) {
			return;
		}
		List<?> found = cir.getReturnValue();
		if (found == null || found.isEmpty()) {
			return;
		}
		List<Object> filtered = new ArrayList<>(found.size());
		for (Object value : found) {
			if (value instanceof RecipeHolder<?> holder && GatedRecipeRegistry.isGatedRecipe(holder)) {
				continue;
			}
			filtered.add(value);
		}
		if (filtered.size() != found.size()) {
			cir.setReturnValue(filtered);
		}
	}
}
