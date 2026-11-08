package com.summy.reliquary.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.summy.reliquary.integration.recipe.GatedRecipeContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 原版工作台与玩家 2x2 合成共用此静态刷新入口。
 * 在该调用期间放行门禁配方，其余调用默认都按自动化处理。
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
	@WrapMethod(method = "slotChangedCraftingGrid", require = 1)
	private static void summyReliquary$markManualCrafting(AbstractContainerMenu menu, Level level,
			Player player, CraftingContainer craftSlots, ResultContainer resultSlots,
			Operation<Void> original) {
		try (GatedRecipeContext.Scope ignored = GatedRecipeContext.enterManualCrafting(player)) {
			original.call(menu, level, player, craftSlots, resultSlots);
		}
	}
}
