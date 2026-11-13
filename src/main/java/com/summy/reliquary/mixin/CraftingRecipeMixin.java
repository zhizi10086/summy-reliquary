package com.summy.reliquary.mixin;

import com.summy.reliquary.integration.recipe.GatedRecipeContext;
import com.summy.reliquary.integration.recipe.GatedRecipeRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 最后一道防线：未知自动化若绕过 RecipeManager 直接组装，也会得到空产物。 */
@Mixin({ShapedRecipe.class, ShapelessRecipe.class})
public abstract class CraftingRecipeMixin {
	@Inject(method = "assemble", at = @At("HEAD"), cancellable = true, require = 1)
	private void summyReliquary$blockAutomatedAssembly(CraftingInput input,
			HolderLookup.Provider registries, CallbackInfoReturnable<ItemStack> cir) {
		Recipe<?> recipe = (Recipe<?>) (Object) this;
		if (GatedRecipeRegistry.isGatedRecipe(recipe, registries) && !GatedRecipeContext.isManualCrafting()) {
			cir.setReturnValue(ItemStack.EMPTY);
		}
	}
}
