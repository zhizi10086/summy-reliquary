package com.summy.reliquary.client;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 血债祭坛独立盾条：使用原版 HUD 黄心贴图，但不写入原版吸收值。
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
		double points = ReliquaryClientState.bloodDebtShield();
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
		int y = SoulHeartOverlay.soulHeartRowY(height, SoulHeartOverlay.maxHealthOf(player),
				player.getAbsorptionAmount(), player.getArmorValue(), gui.rightHeight,
				ReliquaryConfig.soulHeartHudOffsetY()) - 10 * (soulRows + blackRows);
		graphics.setColor(1.0F, 0.92F, 0.25F, 1.0F);
		for (int index = 0; index < hearts; index++) {
			int u = half && index == hearts - 1 ? 61 : 52;
			graphics.blit(ICONS, x + index * HEART_STEP, y, u, 0, HEART_SIZE, HEART_SIZE, 256, 256);
		}
		graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
	}
}
