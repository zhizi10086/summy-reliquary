package com.summy.reliquary.integration;

import com.summy.reliquary.SummyReliquary;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
	/** 当前由本模组隐藏的“分类类型 + 配方”绑定，用于差异恢复。 */
	private final Set<HiddenBinding> hiddenBindings = new HashSet<>();

	private record HiddenBinding(RecipeType<?> recipeType, Object recipe) {
	}

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
	public void registerRecipes(IRecipeRegistration registration) {
		List<ItemStack> angel = List.of(
				new ItemStack(SummyReliquary.LIFE_DEATH.get()),
				new ItemStack(SummyReliquary.DIVINE_INTERVENTION.get()),
				new ItemStack(SummyReliquary.SACRED_VERDICT.get()));
		List<ItemStack> demon = List.of(
				new ItemStack(SummyReliquary.LIFE_DEATH.get()),
				new ItemStack(SummyReliquary.BLOOD_DEBT_ALTAR.get()),
				new ItemStack(SummyReliquary.HEX_SEAL.get()));
		registerCovenantPool(registration, angel, "angel");
		registerCovenantPool(registration, demon, "demon");
		registerHumbleingBundleInfo(registration);
		registerSystemInfoPages(registration);
	}

	@Override
	public void onRuntimeUnavailable() {
		RecipeVisibility.clearApplier();
		runtime = null;
		hiddenBindings.clear();
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
		Map<ResourceLocation, CraftingRecipe> recipes = new LinkedHashMap<>();
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
			recipes.put(entry.getKey(), crafting);
		}

		IRecipeManager recipeManager = runtime.getRecipeManager();
		IRecipeCategoriesLookup lookup = recipeManager.createRecipeCategoryLookup();
		List<IRecipeCategory<?>> categories = lookup.get().toList();
		if (categories.isEmpty()) {
			return false;
		}

		Set<HiddenBinding> desiredHidden = new HashSet<>();
		boolean includesVanillaCrafting = false;
		for (IRecipeCategory<?> category : categories) {
			RecipeType<?> type = category.getRecipeType();
			includesVanillaCrafting |= RecipeTypes.CRAFTING.equals(type);
			collectHiddenBindings(desiredHidden, type, wanted, recipes);
		}
		// 分类查询在 JEI 初始化早期可能暂时不完整，原版工作台始终作为兜底。
		if (!includesVanillaCrafting) {
			collectHiddenBindings(desiredHidden, RecipeTypes.CRAFTING, wanted, recipes);
		}

		for (HiddenBinding binding : hiddenBindings) {
			if (!desiredHidden.contains(binding)) {
				setHidden(binding, false);
			}
		}
		for (HiddenBinding binding : desiredHidden) {
			if (!hiddenBindings.contains(binding)) {
				setHidden(binding, true);
			}
		}
		hiddenBindings.clear();
		hiddenBindings.addAll(desiredHidden);
		return true;
	}

	private static void collectHiddenBindings(Set<HiddenBinding> target, RecipeType<?> recipeType,
			Map<ResourceLocation, Boolean> wanted, Map<ResourceLocation, CraftingRecipe> recipes) {
		for (Map.Entry<ResourceLocation, CraftingRecipe> entry : recipes.entrySet()) {
			CraftingRecipe recipe = entry.getValue();
			if (!wanted.getOrDefault(entry.getKey(), true)
					&& recipeType.getRecipeClass().isInstance(recipe)) {
				target.add(new HiddenBinding(recipeType, recipe));
			}
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void setHidden(HiddenBinding binding, boolean hidden) {
		RecipeType type = binding.recipeType();
		List recipes = List.of(binding.recipe());
		if (hidden) {
			runtime.getRecipeManager().hideRecipes(type, recipes);
		} else {
			runtime.getRecipeManager().unhideRecipes(type, recipes);
		}
	}

	private static void registerCovenantPool(IRecipeRegistration registration, List<ItemStack> items, String route) {
		int chance = com.summy.reliquary.config.ReliquaryConfig.covenantLootChancePercent();
		int pity = com.summy.reliquary.config.ReliquaryConfig.covenantPityChests();
		Component odds = pity > 0
				? text("covenant.odds", chance + "%", pity)
				: text("covenant.no_pity", chance + "%");
		registration.addItemStackInfo(items,
				text("covenant." + route),
				odds,
				text("covenant.neutral"));
	}

	private static void registerHumbleingBundleInfo(IRecipeRegistration registration) {
		int extraChance = com.summy.reliquary.config.ReliquaryConfig
				.humbleingBundleExtraLootChancePercent();
		int naturalChance = com.summy.reliquary.config.ReliquaryConfig
				.humbleingBundleNaturalLootChancePercent();
		registration.addItemStackInfo(new ItemStack(SummyReliquary.HUMBLEING_BUNDLE.get()),
				text("humbleing_bundle.acquire", naturalChance),
				text("humbleing_bundle.effect", extraChance),
				text("humbleing_bundle.reward"),
				text("humbleing_bundle.limits"));
	}

	private static void registerSystemInfoPages(IRecipeRegistration registration) {
		registerSinSystemInfo(registration);
		registerSatanicBibleInfo(registration);
		registerPentagramInfo(registration);
		registerActOfContritionInfo(registration);
		registerGenesisInfo(registration);
		registerGoldenRazorInfo(registration);
		registerThePactInfo(registration);
		registerSixAndMarkInfo(registration);
		registerSalvationInfo(registration);
		registerHolyLightInfo(registration);
		registerHeartMaterialsInfo(registration);
	}

	private static void registerSinSystemInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("sin_system"),
				text("sin_system.1"),
				text("sin_system.2"),
				text("sin_system.3"),
				text("sin_system.4"),
				text("sin_system.5"));
	}

	private static void registerSatanicBibleInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("satanic_bible"),
				text("satanic_bible.1"),
				text("satanic_bible.2"),
				text("satanic_bible.3",
						format(com.summy.reliquary.config.ReliquaryConfig.satanicBibleBlackHearts()),
						com.summy.reliquary.config.ReliquaryConfig.satanicRefillSeconds()),
				text("satanic_bible.4",
						format(com.summy.reliquary.config.ReliquaryConfig.satanicShatterRadius()),
						format(com.summy.reliquary.config.ReliquaryConfig.satanicShatterDamage())),
				text("satanic_bible.5"));
	}

	private static void registerPentagramInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("pentagram"),
				text("pentagram.1",
						format(com.summy.reliquary.config.ReliquaryConfig.pentagramAttackDamage())),
				text("pentagram.2",
						com.summy.reliquary.config.ReliquaryConfig.pentagramMessageSeconds()),
				text("pentagram.3"),
				text("pentagram.4"),
				text("pentagram.5"));
	}

	private static void registerActOfContritionInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("act_of_contrition"),
				text("act_of_contrition.1"),
				text("act_of_contrition.2"),
				text("act_of_contrition.3"),
				text("act_of_contrition.4"),
				text("act_of_contrition.5"));
	}

	private static void registerGenesisInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("genesis"),
				text("genesis.1"),
				text("genesis.2"),
				text("genesis.3"),
				text("genesis.4"),
				text("genesis.5"));
	}

	private static void registerGoldenRazorInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("golden_razor"),
				text("golden_razor.1",
						format(com.summy.reliquary.config.ReliquaryConfig.razorCooldownTicks() / 20.0D)),
				text("golden_razor.2",
						format(com.summy.reliquary.config.ReliquaryConfig.razorDamage())),
				text("golden_razor.3"));
	}

	private static void registerThePactInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("the_pact"),
				text("the_pact.1"),
				text("the_pact.2"),
				text("the_pact.3",
						format(com.summy.reliquary.config.ReliquaryConfig.pactHealthPerCursePercent()),
						format(com.summy.reliquary.config.ReliquaryConfig.pactSpeedPerCursePercent()),
						format(com.summy.reliquary.config.ReliquaryConfig.pactSpeedCapPercent())),
				text("the_pact.4"),
				text("the_pact.5",
						com.summy.reliquary.config.ReliquaryConfig.pactLifestealCurseThreshold(),
						format(com.summy.reliquary.config.ReliquaryConfig.pactLifestealPercent()),
						format(com.summy.reliquary.config.ReliquaryConfig
								.pactLifestealPerExtraCursePercent()),
						format(com.summy.reliquary.config.ReliquaryConfig.pactLifestealCapPercent())));
	}

	private static void registerSixAndMarkInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("six_and_mark"),
				text("six_and_mark.1"),
				text("six_and_mark.2"),
				text("six_and_mark.3"),
				text("six_and_mark.4",
						com.summy.reliquary.effect.EvilUnlock.THE_MARK.threshold()),
				text("six_and_mark.5"));
	}

	private static void registerSalvationInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("salvation"),
				text("salvation.1",
						format(com.summy.reliquary.config.ReliquaryConfig.salvationRadius())),
				text("salvation.2",
						format(com.summy.reliquary.config.ReliquaryConfig.salvationLockSeconds()),
						com.summy.reliquary.config.ReliquaryConfig.salvationDamage(),
						format(com.summy.reliquary.config.ReliquaryConfig.salvationCooldownSeconds())),
				text("salvation.3"),
				text("salvation.4"));
	}

	private static void registerHolyLightInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("holy_light"),
				text("holy_light.1",
						com.summy.reliquary.config.ReliquaryConfig.holyLightChancePercent(),
						com.summy.reliquary.config.ReliquaryConfig.holyLightChancePercentGodhead()),
				text("holy_light.2",
						com.summy.reliquary.config.ReliquaryConfig.holyLightDamagePercent()),
				text("holy_light.3"),
				text("holy_light.4"));
	}

	private static void registerHeartMaterialsInfo(IRecipeRegistration registration) {
		registration.addItemStackInfo(pageItems("heart_materials"),
				text("heart_materials.1"),
				text("heart_materials.2"),
				text("heart_materials.3",
						format(com.summy.reliquary.config.ReliquaryConfig.heartShardDropChance()),
						format(com.summy.reliquary.config.ReliquaryConfig.heartShardBossChance()),
						format(com.summy.reliquary.config.ReliquaryConfig.heartShardDragonChance())),
				text("heart_materials.4"),
				text("heart_materials.5"));
	}

	private static String format(double value) {
		return Math.abs(value - Math.rint(value)) < 1.0E-6D
				? String.valueOf((long) Math.rint(value))
				: String.valueOf(Math.round(value * 10.0D) / 10.0D);
	}

	private static List<ItemStack> pageItems(String key) {
		return JeiInfoPageGroups.all().getOrDefault(key, List.of())
				.stream().map(ItemStack::new).toList();
	}

	private static Component text(String key, Object... arguments) {
		return Component.translatable("jei.summy-reliquary." + key, arguments);
	}
}
