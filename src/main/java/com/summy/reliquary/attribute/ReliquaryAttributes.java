package com.summy.reliquary.attribute;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的自定义属性。
 *
 * <p>目前只有「魂心」：每 1 点提供 2 点吸收（黄血），由 {@code SoulShield} 每 30 秒补满。
 */
@EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ReliquaryAttributes {
	public static final DeferredRegister<Attribute> ATTRIBUTES =
			DeferredRegister.create(Registries.ATTRIBUTE, SummyReliquary.NAMESPACE);

	/** 魂心：0 起步、可叠加；实际效果在本模组里实现 */
	public static final DeferredHolder<Attribute, Attribute> SOUL_HEARTS = ATTRIBUTES.register("soul_hearts",
			() -> new RangedAttribute("attribute.summy-reliquary.soul_hearts", 0.0D, 0.0D, 1024.0D)
					.setSyncable(true));

	private ReliquaryAttributes() {
	}

	/** 把魂心属性加到玩家身上（自定义属性必须显式挂到实体类型上才能生效） */
	@SubscribeEvent
	public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
		if (!event.has(EntityType.PLAYER, SOUL_HEARTS)) {
			event.add(EntityType.PLAYER, SOUL_HEARTS);
		}
	}
}
