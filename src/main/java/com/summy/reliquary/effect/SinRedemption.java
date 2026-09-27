package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.Optional;

/**
 * 「赎罪 → 美德」转化：手持赎罪在世界里右击，把装在魂印栏里的七罪之源就地变成美德。
 *
 * <p>之所以改成世界内右击，是为了完全不使用 Mixin（Forge 与 Kilt 下都最稳）。
 * 只有在真的发生转化时才消耗赎罪、播放音效并取消这次交互；其它情况一律不干预，
 * 不影响正常放置方块、开箱子或使用物品。
 */
public final class SinRedemption {
	private SinRedemption() {
	}

	/** 尝试转化；返回 true 表示这次右击已经用于转化 */
	public static boolean tryRedeem(Player player, PlayerInteractEvent event) {
		ItemStack held = event.getItemStack();
		if (!held.is(SummyReliquary.REDEMPTION.get())) {
			return false;
		}

		// 门槛：必须七罪全部已赎罪，才能把七罪之源变成美德
		if (!SinManager.allRedeemed(player)) {
			player.displayClientMessage(
					net.minecraft.network.chat.Component.translatable("message.summy-reliquary.redeem.not_ready"), true);
			return false;
		}

		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return false;
		}

		Optional<SlotResult> found = handler.findFirstCurio(SummyReliquary.SOURCE_OF_SINS.get());
		if (found.isEmpty()) {
			return false;
		}

		SlotContext context = found.get().slotContext();
		if (!ReliquarySlots.SOUL_SEAL.equals(context.identifier())) {
			return false;
		}

		// 就地替换：魂印栏里的七罪之源 → 美德
		handler.setEquippedCurio(context.identifier(), context.index(),
				new ItemStack(SummyReliquary.VIRTUES.get()));
		held.shrink(1);

		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.6F);

		event.setCanceled(true);
		if (event instanceof PlayerInteractEvent.RightClickBlock blockEvent) {
			blockEvent.setCancellationResult(InteractionResult.SUCCESS);
		} else if (event instanceof PlayerInteractEvent.RightClickItem itemEvent) {
			itemEvent.setCancellationResult(InteractionResult.SUCCESS);
		}
		SummyReliquary.LOGGER.debug("[Summy Reliquary] {} 使用赎罪把魂印栏的七罪之源转化为美德", player.getName().getString());
		// 进度：纯洁之人
		if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			// 1.6.10：天使标记由这次转化**动作**驱动（不再依赖"纯洁之人"成就）
			com.summy.reliquary.advancement.SinChallenges.grantAngelForConversion(serverPlayer);
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(serverPlayer,
					com.summy.reliquary.advancement.ReliquaryAdvancements.REDEEMED_TO_VIRTUES);
		}
		return true;
	}
}
