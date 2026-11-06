package com.summy.reliquary.client;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 血债祭坛独立盾条。
 *
 * <p>血债盾不写入原版吸收，以免把血债份额混入 Summy Enchantment 等按吸收池差值记账的护盾；
 * 界面仍复用原版黄心贴图，并且只画出血债祭坛自己的份额。
 */
public final class BloodDebtShieldOverlay implements IGuiOverlay {
	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
	private static final int HEART_SIZE = 9;
	private static final int HEART_STEP = 8;

	@Override
	public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
			float partialTick, int width, int height) {
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (!SoulHeartOverlay.shouldRender(player, client.options.hideGui)
				|| !ReliquaryClientState.hasSynced()) {
			return;
		}
		double points = ClientBloodDebtState.shield();
		if (points <= 0.0D) {
			return;
		}
		int hearts = Mth.ceil(points / 2.0D);
		boolean half = points % 2.0D != 0.0D;
		int x = width / 2 - 91;
		double soulPoints = SoulHeartOverlay.ourSoulHeartPoints(player);
		int soulRows = SoulHeartOverlay.soulHeartRows(soulPoints);
		double blackPoints = com.summy.reliquary.effect.DemonPact.blackHeartPoints(player);
		int blackRows = SoulHeartOverlay.soulHeartRows(blackPoints);
		float maxHealth = SoulHeartOverlay.maxHealthOf(player);
		// 把血债盾计入布局行数，使它作为额外黄心行出现在红心 / 原版吸收之上，
		// 而不是覆盖红心的第一排。
		float layoutAbsorption = (float) (player.getAbsorptionAmount() + points);
		int y = SoulHeartOverlay.soulHeartRowY(height, maxHealth,
				layoutAbsorption, player.getArmorValue(), gui.rightHeight,
				ReliquaryConfig.soulHeartHudOffsetY()) - 10 * (soulRows + blackRows);
		int rowSpacing = SoulHeartOverlay.vanillaHeartGridRowSpacing(maxHealth, layoutAbsorption);
		graphics.setColor(1.0F, 0.92F, 0.25F, 1.0F);
		for (int index = 0; index < hearts; index++) {
			int u = half && index == hearts - 1 ? 61 : 52;
			int column = index % 10;
			int row = index / 10;
			graphics.blit(ICONS, x + column * HEART_STEP, y - row * rowSpacing,
					u, 0, HEART_SIZE, HEART_SIZE, 256, 256);
		}
		graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
	}
}
