package com.summy.reliquary.advancement;

import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

/**
 * 本模组统一的进度触发器：判据里带一个事件 id。
 *
 * <p>JSON 写法：{@code {"trigger": "summy-reliquary:event", "conditions": {"event": "sins_obtained"}}}。
 * 事件 id 见 {@link ReliquaryAdvancements}。
 */
public class ReliquaryTrigger extends SimpleCriterionTrigger<ReliquaryTrigger.TriggerInstance> {
	private final ResourceLocation id;

	public ReliquaryTrigger(ResourceLocation id) {
		this.id = id;
	}

	@Override
	public ResourceLocation getId() {
		return id;
	}

	@Override
	protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
			DeserializationContext context) {
		return new TriggerInstance(id, player, GsonHelper.getAsString(json, "event"));
	}

	/** 触发一次：只有事件 id 匹配的判据会被授予 */
	public void trigger(ServerPlayer player, String event) {
		trigger(player, instance -> instance.event().equals(event));
	}

	/** 判据实例：玩家条件 + 事件 id */
	public static class TriggerInstance extends AbstractCriterionTriggerInstance {
		private final String event;

		public TriggerInstance(ResourceLocation id, ContextAwarePredicate player, String event) {
			super(id, player);
			this.event = event;
		}

		public String event() {
			return event;
		}
	}
}
