package com.summy.reliquary.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * 「短寿命火焰」（1.6.7）：复仇之魂范围圈专用。
 *
 * <p>为什么需要它：拖尾长度 ≈ 粒子寿命 × 玩家移动速度。原版 {@code FLAME} 的寿命是 10~40 tick
 * （0.5~2 秒），玩家一走就把火焰拉成一条长尾巴；而原版粒子的寿命没有参数可调，所以这里**照抄原版火焰的观感**
 * （满亮度、不透明、缓慢上升、尺寸随 age 放大），只把寿命缩到 4~8 tick（0.2~0.4 秒）——
 * 看起来仍是同一团火，但走动时不再拖尾。
 *
 * <p>注意：不能继承 {@code FlameParticle}（它的构造是包私有的），所以直接继承 {@code TextureSheetParticle}。
 */
public class ShortFlameParticle extends TextureSheetParticle {
	private final SpriteSet sprites;

	protected ShortFlameParticle(ClientLevel level, double x, double y, double z,
			double xd, double yd, double zd, SpriteSet sprites) {
		super(level, x, y, z);
		this.sprites = sprites;
		this.xd = xd + (this.random.nextDouble() * 2.0D - 1.0D) * 0.05D;
		this.yd = yd + (this.random.nextDouble() * 2.0D - 1.0D) * 0.05D;
		this.zd = zd + (this.random.nextDouble() * 2.0D - 1.0D) * 0.05D;
		this.quadSize *= 0.75F;
		// 唯一与原版火焰不同的地方：寿命 4~8 tick
		this.lifetime = 4 + this.random.nextInt(5);
		this.hasPhysics = false;
		this.setSpriteFromAge(sprites);
	}

	@Override
	public ParticleRenderType getRenderType() {
		// 1.7.3：改走**方块图集**（TERRAIN_SHEET）。
		// 原因：粒子图集在客户端**取不到**（Minecraft 的 AtlasSet 里没有 particles），
		// 而方块图集可以随便取（getTextureAtlas(LOCATION_BLOCKS)）。于是我们把自己的火焰贴图
		// 通过 `assets/summy-reliquary/atlases/blocks.json` 塞进方块图集，
		// 渲染批次也必须跟着用方块图集，UV 才对得上（贴图仍是同一张 8×8 火焰）。
		return ParticleRenderType.TERRAIN_SHEET;
	}

	/** 与原版火焰同款：由小变大 */
	@Override
	public float getQuadSize(float scaleFactor) {
		return this.quadSize
				* Mth.clamp(((float) this.age + scaleFactor) / (float) this.lifetime * 32.0F, 0.0F, 1.0F);
	}

	/** 与原版火焰一致：满亮度，不受环境光影响 */
	@Override
	protected int getLightColor(float partialTick) {
		return 15728880;
	}

	@Override
	public void tick() {
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		if (this.age++ >= this.lifetime) {
			this.remove();
			return;
		}
		this.setSpriteFromAge(this.sprites);
		this.move(this.xd, this.yd, this.zd);
		this.xd *= 0.96D;
		this.yd *= 0.98D;
		this.zd *= 0.96D;
		if (this.onGround) {
			this.xd *= 0.7D;
			this.zd *= 0.7D;
		}
	}

	/** 粒子提供者（由 {@code ReliquaryParticles} 直接注册到 ParticleEngine） */
	public static class Provider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
				double xd, double yd, double zd) {
			// 1.7.2 防御：提供者可能是在"资源还没重载"时被注册的，这时 SpriteSet 为 null。
			// 直接返回 null 表示"这次不生成"，原版会安静跳过 —— 绝不抛异常
			// （抛异常会让客户端每颗粒子刷一条 WARN，并可能在加载期把实例拖死）。
			if (this.sprites == null) {
				return null;
			}
			return new ShortFlameParticle(level, x, y, z, xd, yd, zd, this.sprites);
		}
	}
}
