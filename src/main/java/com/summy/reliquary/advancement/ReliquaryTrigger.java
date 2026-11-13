package com.summy.reliquary.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * 本模组统一的进度触发器：判据里带一个事件 id。
 *
 * <p>JSON 写法：{@code {"trigger": "summy-reliquary:event", "conditions": {"event": "sins_obtained"}}}。
 */
public class ReliquaryTrigger extends SimpleCriterionTrigger<ReliquaryTrigger.TriggerInstance> {
	@Override
	public Codec<TriggerInstance> codec() {
		return TriggerInstance.CODEC;
	}

	/** 触发一次：只有事件 id 匹配的判据会被授予 */
	public void trigger(ServerPlayer player, String event) {
		trigger(player, instance -> instance.event().equals(event));
	}

	/** 判据实例：玩家条件 + 事件 id */
	public record TriggerInstance(Optional<ContextAwarePredicate> player, String event)
			implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")
						.forGetter(TriggerInstance::player),
				Codec.STRING.fieldOf("event").forGetter(TriggerInstance::event))
				.apply(instance, TriggerInstance::new));
	}
}
