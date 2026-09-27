package com.summy.reliquary.integration;

import com.summy.reliquary.SummyReliquary;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

/**
 * JEI 集成（软依赖，只有装了 JEI 才会加载这个类）。
 *
 * <p>本模组的「赎罪」「天使线配方（三件套 / 圣光 / 斗篷 / 神性 / 圣心）」与「仪式法袍」都注册在数据包里，JEI 默认一定会显示，
 * 所以必须在运行时用 {@code IRecipeManager#hideRecipes / unhideRecipes} 按玩家状态主动隐藏 / 放开
 * ——只实现"追加配方"的 {@code ISimpleRecipeManagerPlugin} 挡不住默认显示。
 * 服务端另有两道兜底拦截（见 {@code RedemptionRecipeGate} 与 {@code SpiritAltarRecipeGate}）。
 */
@JeiPlugin
public class ReliquaryJeiPlugin implements IModPlugin {
	private IJeiRuntime runtime;

	@Override
	public ResourceLocation getPluginUid() {
		return SummyReliquary.id("jei");
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
		RecipeVisibility.setApplier(this::apply);
	}

	@Override
	public void onRuntimeUnavailable() {
		RecipeVisibility.clearApplier();
		runtime = null;
	}

	/**
	 * 按玩家状态批量隐藏 / 放开配方。
	 *
	 * @return false 表示这次没能写入（世界还没加载或配方还不存在），桥会在之后重试
	 */
	private boolean apply(Map<ResourceLocation, Boolean> wanted) {
		Minecraft client = Minecraft.getInstance();
		if (runtime == null || client.level == null) {
			return false;
		}
		List<CraftingRecipe> hide = new ArrayList<>();
		List<CraftingRecipe> unhide = new ArrayList<>();
		for (Map.Entry<ResourceLocation, Boolean> entry : wanted.entrySet()) {
			Optional<? extends Recipe<?>> recipe = client.level.getRecipeManager().byKey(entry.getKey());
			CraftingRecipe crafting = recipe
					.filter(CraftingRecipe.class::isInstance)
					.map(CraftingRecipe.class::cast)
					.orElse(null);
			if (crafting == null) {
				// 配方还没加载好：这一次整体放弃，让桥稍后重试
				return false;
			}
			(entry.getValue() ? unhide : hide).add(crafting);
		}
		if (!hide.isEmpty()) {
			runtime.getRecipeManager().hideRecipes(RecipeTypes.CRAFTING, hide);
		}
		if (!unhide.isEmpty()) {
			runtime.getRecipeManager().unhideRecipes(RecipeTypes.CRAFTING, unhide);
		}
		return true;
	}
}
