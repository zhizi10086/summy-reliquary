package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.client.particle.ShortFlameParticle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * 「短寿命火焰」粒子（复仇之魂火焰环）的客户端提供者注册 —— **不依赖 Forge 的粒子事件**（1.7.3）。
 *
 * <p><b>为什么不能用那两条"看着更正规"的路</b>：
 * <ol>
 *     <li>Forge 的 {@code RegisterParticleProvidersEvent}：Kilt 上那处注入被跳过、事件根本不 post
 *     （启动日志里能搜到 {@code kilt$postRegisterParticleProviders exceeds the maximum allowed value: 0}）；</li>
 *     <li>{@code ParticleEngine#register(type, SpriteParticleRegistration)}（`registerSpriteSet` 背后就是它）：
 *     它**每次都新建一个空的 {@code MutableSpriteSet}**，只有"在资源重载内部"注册才会被随后 rebind 填上贴图；
 *     在别处调用 → 提供者拿到内部 List 为 null 的 SpriteSet → 每生成一颗粒子抛 NPE
 *     （被原版吞掉、打成 {@code Could not spawn particle effect}）→ 火焰环每 tick 8 颗、
 *     秒级刷爆日志并触发崩溃报告生成，实例会卡在"加载地形中"（1.7.2 实际踩到过）。</li>
 * </ol>
 *
 * <p><b>现在这条路</b>（Forge / Kilt 通用，且与注册时机无关）：
 * <ol>
 *     <li>贴图进**方块图集**：{@code assets/minecraft/atlases/blocks.json} 是 1.19.3+ 的原版图集来源机制，
 *     与加载器无关，把 {@code textures/particle/short_flame.png} 拼成 {@code summy-reliquary:short_flame}；
 *     <b>注意路径必须是 {@code assets/minecraft/}</b>：原版 {@code SpriteResourceLoader#create} 只会去读
 *     "图集自身那一个文件"（方块图集 = {@code minecraft:atlases/blocks.json}）并用 {@code getResourceStack}
 *     把**所有资源包**里的同名同命名空间文件叠起来 —— 放在 {@code assets/summy-reliquary/atlases/} 下
 *     在 Forge 和 Kilt 上都不会被读到（1.7.3 实测踩过：sprite 取到 {@code minecraft:missingno}）；</li>
 *     <li>自己造一个"单帧 SpriteSet"，直接引用那个 sprite（不再依赖引擎给的 SpriteSet）；</li>
 *     <li>用 {@code ParticleEngine#register(type, ParticleProvider)} 注册 —— 这个方法 javap 确认就是
 *     一句 {@code providers.put(...)}，**不会**新建 sprite set，所以什么时候注册都安全。</li>
 * </ol>
 * 由客户端 tick 在"已经进了世界"之后幂等调用。
 */
public final class ReliquaryParticles {
	/** 我们在方块图集里的火焰 sprite（来自 {@code textures/particle/short_flame.png}） */
	private static final ResourceLocation SPRITE =
			new ResourceLocation(SummyReliquary.NAMESPACE, "short_flame");
	/** 是否已经成功注册过（仅用于日志节流） */
	private static boolean registered;
	/** 取贴图失败的告警是否已打过 */
	private static boolean spriteWarned;

	private ReliquaryParticles() {
	}

	/**
	 * 注册（或补注册）本模组的粒子提供者。每 tick 调用无害（一次 {@code Map#put}）。
	 *
	 * <p>只在"已经进了世界"时执行：那时方块图集早已拼好，sprite 一定可取。
	 */
	public static void register() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.particleEngine == null || client.level == null) {
			return;
		}
		SpriteSet sprites = flameSprites(client);
		if (sprites == null) {
			return;
		}
		// 关键：用"直接给 Provider"的重载（纯 providers.put），绝不用 SpriteParticleRegistration。
		client.particleEngine.register(SummyReliquary.SHORT_FLAME.get(),
				new ShortFlameParticle.Provider(sprites));
		if (!registered) {
			SummyReliquary.LOGGER.info(
					"[Summy Reliquary] 粒子提供者已注册：short_flame={}（客户端 tick 处理器已运行），贴图={}",
					SummyReliquary.SHORT_FLAME.get(), sprites.get(0, 1).contents().name());
		}
		registered = true;
	}

	/** 自检 / 调试用：是否已经注册过 */
	public static boolean registered() {
		return registered;
	}

	/** 自检用：当前用的 sprite 名（应为 {@code summy-reliquary:short_flame}） */
	public static String spriteName() {
		SpriteSet sprites = flameSprites(Minecraft.getInstance());
		return sprites == null ? "（取不到）" : sprites.get(0, 1).contents().name().toString();
	}

	/** 自检用：sprite 是否为原版缺省贴图（missingno） */
	public static boolean spriteMissing() {
		SpriteSet sprites = flameSprites(Minecraft.getInstance());
		return sprites == null || sprites.get(0, 1).contents().name().getPath().contains("missingno");
	}

	/** 自己造一个"单帧 SpriteSet"：直接引用方块图集里的火焰 sprite */
	private static SpriteSet flameSprites(Minecraft client) {
		try {
			TextureAtlasSprite sprite = client.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(SPRITE);
			if (sprite == null) {
				return null;
			}
			// 图集里没有这张贴图时，原版给的是 missingno（不会抛异常）。
			// 这种情况不注册提供者（火焰环安静地不显示），并打一条一次性告警，避免"每 tick 刷屏"。
			if (sprite.contents().name().getPath().contains("missingno")) {
				warnOnce("方块图集里没有 " + SPRITE + "（取到 missingno）");
				return null;
			}
			return new SpriteSet() {
				@Override
				public TextureAtlasSprite get(int age, int lifetime) {
					return sprite;
				}

				@Override
				public TextureAtlasSprite get(RandomSource random) {
					return sprite;
				}
			};
		} catch (Throwable throwable) {
			warnOnce("取火焰贴图失败：" + throwable);
			return null;
		}
	}

	/** 一次性告警（贴图缺失 / 解析失败都不该每 tick 刷日志） */
	private static void warnOnce(String reason) {
		if (!spriteWarned) {
			spriteWarned = true;
			SummyReliquary.LOGGER.warn("[Summy Reliquary] 火焰环粒子将不显示：{}", reason);
		}
	}
}
