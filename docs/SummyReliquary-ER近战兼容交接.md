# Summy Reliquary × Enchantment Reforged：近战兼容交接

> 用途：把「暗仪刺刀的斩击」「投掷长矛命中」两处伤害补进 ER 的**完整近战管线**。
> **本文只做记录与交接 —— 本轮不在 Summy Reliquary 侧改动任何代码**，实施由 ER 方（`enchantment-reforged` /
> `enchantment-reforged-forge` 两个源树）完成。
> 记录时间：2026-10-09　｜　SR 版本：`1.8.5-forge`　｜　ER 版本线：Fabric `1.3.4` / Forge `1.3.4-forge`

---

## 1. 问题（已核实）

SR 有两处"自造"的近战伤害，都用 `player.damageSources().playerAttack(player)`、直接调 `target.hurt(...)`：

| 位置 | 符号 | 说明 |
| --- | --- | --- |
| 暗仪刺刀「遁入暗影」基础斩击 | `ShadowDash#strike` | 生产 jar 里方法名不变（SR 自己的私有静态方法） |
| 暗仪刺刀「遁入暗影」强力斩击 | `ShadowDash#heavySlash` | 生产 jar 里方法名不变；SR 用 `ShadowDash.suppressOutgoingBonus()` 让这一击**跳过自己的增伤乘区** |
| 投掷长矛命中 | `ThrownSpear#onHitEntity` | 原版覆写 → **生产 jar 里被重命名为 `m_5790_`**（dev 环境仍是 `onHitEntity`） |

这些伤害在 SR 自己、以及任何"只看 `DamageSource.getDirectEntity() == 玩家`"的模组眼里都是近战；
但 **ER 的近战管线不是挂在伤害事件上，而是用 Mixin 挂在原版 `Player#attack(Entity)`**（`PlayerEntityMixin`
的四个注入点：HEAD 记目标、`getAttackStrengthScale` 记蓄力、`EnchantmentHelper.getDamageBonus` 暴击放大附魔、
`)` `Entity#hurt` 主命中 / `LivingEntity#hurt` 横扫各自乘区与命中后效果）。因此这两处**完全绕过** ER 的近战管线。

### 具体缺什么

| 项 | 是否生效 | 备注 |
| --- | --- | --- |
| 力量倍率 `CombatFormulas.strengthMultiplier` | ❌ | **影响最大**：ER 把原版力量效果重定向成自定义效果，且 `ModStatusEffects` 里明确写了"自定义力量的伤害是命中时乘算，刻意不挂任何属性修饰符"。于是 `ATTACK_DAMAGE` 里**不含**力量，ER 的 ×1.5/级只在 `attack` 里乘 ⇒ 这两处连整份力量加成都没有 |
| 死神祝福·造成方 `deathsBlessingOutgoing` | ❌ | 只在 `attack` 里乘 |
| 复仇 `revengeMultiplier` | ❌ | 同上（且它自带 `isMeleeWeapon` 检查） |
| 魔剑 `applySpellblade` / 嗜血 `applyLifesteal` | ❌ | 只在主命中与横扫的注入点里结算 |
| 斩杀 `tryExecute` / 出其不意 `rollSurprise` | ❌ | 同上（只对"主命中"目标） |
| 近战粒子 `MeleeParticles.spawnOnHit` / `spawnSurprise` | ❌ | 同上 |
| 暴击放大附魔加成 | ❌ | 这两个攻击本来也没有暴击概念 |
| 受害者侧（`LivingEntity#hurt` 注入）：死神祝福·受伤方 / 灵动步伐 / 复仇窗口刷新 | ✅ | 与攻击者无关，照常生效 |

> 备注：左键使用 SR 的匕首 / 长矛（都是 `SwordItem` 子类）走的是原版 `attack()`，ER 的近战效果**正常生效**。
> 另外投掷的「落点圣光爆发」是独立真伤类型、神性光环 / 硫磺火等也不是近战，本就不该吃（保持不动）。

---

## 2. 口径（已定稿）

| 攻击 | ER 伤害乘区（力量 × 死神祝福·造成方 × 复仇） | 魔剑 / 嗜血 / 粒子 | 斩杀 | 出其不意（含 `spawnSurprise` 粒子） |
| --- | --- | --- | --- | --- |
| 左键近战（现状，不改） | ✅ | ✅ | ✅ | ✅ |
| 遁入暗影·基础斩击 | ✅（新增） | ✅ | ✅ | ✅ |
| 遁入暗影·**强力斩击** | ❌ 保持 SR「不吃增伤乘区」的原设计 | ✅ | ✅ | **❌（不掷出其不意）** |
| 投掷长矛命中 | ✅（新增，与左键同档） | ✅ | ✅ | ✅ |

---

## 3. ER 侧改动清单（两个源树同步实施）

### ① 新助手 `com.enchantmentreforged.compat.SummyReliquaryCompat`

- `isActive()`：目标类文件可见（`com/summy/reliquary/effect/ShadowDash.class`，**不要 Class.forName**）或 mod id
  `summy_reliquary` 已加载；否则整份兼容跳过。
- `meleeMultiplier(Player attacker)` = `CombatFormulas.strengthMultiplier` ×
  `EnchantmentEffects.deathsBlessingOutgoing` × `EnchantmentEffects.revengeMultiplier`
  （三个都已是 `public static`，见附录 A）。
- `onMeleeHit(LivingEntity attacker, ItemStack weapon, LivingEntity target, float dealt, boolean allowOffense)`：
  `applySpellblade(attacker, weapon, target, dealt)` → `applyLifesteal(attacker, weapon, dealt)` →
  `MeleeParticles.spawnOnHit(attacker, target)`；`allowOffense` 为 true 时再
  `tryExecute(attacker, target, weapon)` 与 `rollSurprise(attacker, weapon)`（命中出其不意时**照
  `PlayerEntityMixin` 的做法**：先 `target.invulnerableTime = 0`，再用同一个 `DamageSource` 与金额补一次 `hurt`，
  并再结算一次魔剑 / 嗜血）。
- 只在 `source.getDirectEntity() instanceof Player` 时生效（`ThrownSpear` 有一条非玩家兜底的 `thrown(...)` 源，
  原样放行）。

### ② 兼容 Mixin `com.enchantmentreforged.mixin.SummyReliquaryCompatMixin`

照 `SpearItemCompatMixin` 的写法：`@Mixin(targets = "…")` + 每个注入 `require = 0`。

- `@Mixin(targets = "com.summy.reliquary.effect.ShadowDash")`
  - `strike`：`@WrapOperation(method = "strike", remap = false)` 包住 `LivingEntity#hurt`
    → 金额 × `meleeMultiplier`（仅 playerAttack）→ 原方法返回 true 后 `onMeleeHit(..., true)`。
  - `heavySlash`：同样包住 `LivingEntity#hurt`，但**不乘** `meleeMultiplier`，只做 `onMeleeHit(..., true)`。
- `@Mixin(targets = "com.summy.reliquary.entity.ThrownSpear")`
  - 包住 `onHitEntity` 里的 `LivingEntity#hurt` → 乘 `meleeMultiplier` + `onMeleeHit(..., true)`。
  - ⚠ 方法选择器写**两条**（各 `require = 0`、`remap = false`）：
    `"onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V"`（dev）与
    `"m_5790_(Lnet/minecraft/world/phys/EntityHitResult;)V"`（生产）。
    依据：SR 产出 jar 里 `javap -p com.summy.reliquary.entity.ThrownSpear` 显示该覆写名为 `m_5790_`。
- `@At` 的 target 用**原版成员** `Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z`，
  **保持默认 remap=true**（与 `PlayerEntityMixin` 现有写法一致，构建期会被改写成生产名）。
- 乘法与 ER 在 `attack` 里的做法一致：先乘再 `hurt`，SR 自己的 `LivingHurtEvent` 乘区之后再叠加，
  与"左键近战 + SR 增伤"的既有顺序完全相同，不会重复计算。

### ③ 开关插件 `com.enchantmentreforged.compat.SummyReliquaryMixinPlugin`

照抄 `SpearMixinPlugin`：`required: false`、`shouldApplyMixin` 里查类文件 / mod 是否存在（**绝不 Class.forName
目标类**）、找不到就整份跳过；首次检查打一条 INFO 便于实机确认。Forge 侧用
`FMLLoader.getLoadingModList()` / `ModList`，Fabric 侧用 `FabricLoader`。

### ④ 配置与登记

- 新增 `enchantment_reforged.summy_reliquary.mixins.json`（内容照 `enchantment_reforged.spears.mixins.json`：
  `required:false` + `package` + `refmap` + `plugin` + `mixins:[SummyReliquaryCompatMixin]`）。
- Forge：`build.gradle` 的 `mixin { config ... }` 与 manifest `MixinConfigs` 各加一项
  （现有值是 `enchantment_reforged.mixins.json,enchantment_reforged.spears.mixins.json`）。
- Fabric：`fabric.mod.json` 的 `mixins` 数组加一项。

**SR 侧：零改动。**

---

## 4. 测试与验收

- ER 两个树各自 `gradlew clean build`。
- 实机（Ponder Time，Forge ER + Connector；以及 Kilt 实例，Fabric ER）：
  1. 喝力量 II 后分别用 左键 / 基础斩击 / 强力斩击 / 投掷命中 打同一目标：前三者应看到力量 ×3（强力斩击**不应**），投掷应与左键同档；
  2. 命中时出现 ER 的近战粒子；
  3. 带斩杀附魔打残血目标时斩杀生效；
  4. 没装 SR 时不产生任何 mixin 报错（插件跳过），启动日志干净。
- SR 侧回归：SR 的 `runClient -Pdevcheck` 必须仍全绿（SR 无改动；ER 缺席时兼容整份跳过）。

---

## 5. 假设与默认

- 强力斩击保持"不吃增伤乘区"，但仍吃 ER 的命中后效果（含出其不意的二次结算）；实机若觉得 AoE 双结算过强，
  把该分支的 `allowOffense` 拆成"是否允许斩杀/出其不意"两个开关即可。
- ER 默认开关（力量重做 / 魔剑 / 嗜血 / 死神祝福 / 出其不意 / 斩杀 / 复仇）全为 `true`，补全后立即可见。
- SR 侧不碰自己的 `Sacrifice`「投掷不算严格左键近战」口径（那是 SR 自己的 +40% 献祭加成，继续只作用于左键）。
- SR 只产出 Forge jar、在 Fabric 侧靠 Kilt 运行，因此 Fabric ER 的兼容也指向同一批类名（上述 targets 通用）。

---

## 附录 A：可直接复用的 ER 原语（均已核实为 `public static`）

| 符号 | 签名 |
| --- | --- |
| `CombatFormulas.strengthMultiplier` | `(LivingEntity) → float` |
| `EnchantmentEffects.deathsBlessingOutgoing` | `(LivingEntity) → float` |
| `EnchantmentEffects.revengeMultiplier` | `(LivingEntity) → float` |
| `EnchantmentEffects.applySpellblade` | `(LivingEntity, Entity, float)` 与 `(LivingEntity, ItemStack, Entity, float)` |
| `EnchantmentEffects.applyLifesteal` | `(LivingEntity, float)` 与 `(LivingEntity, ItemStack, float)` |
| `EnchantmentEffects.tryExecute` | `(LivingEntity attacker, Entity target, ItemStack weapon)` |
| `EnchantmentEffects.rollSurprise` | `(LivingEntity attacker, ItemStack weapon) → boolean` |
| `MeleeParticles.spawnOnHit` / `spawnSurprise` | `(LivingEntity attacker, Entity target)` |

## 附录 B：易踩的坑

- **别在 MixinPlugin 里 `Class.forName` 目标类** —— 那会让 SR 的类在 Mixin 准备阶段就被加载，之后注入全部静默失效
  （ER 的 `SpearMixinPlugin` 注释里已记过这个坑）。
- **SR 的覆写方法在生产 jar 里是 SRG 名**（`onHitEntity → m_5790_`）；SR 自己的私有方法名不变。
- 生产 / dev 两种方法名要各写一条选择器（`require = 0`），否则只在其中一种环境生效。
- 不要把"落点圣光爆发 / 神性光环 / 献祭自伤"也算进近战 —— 它们是独立伤害类型，保持现状。

---

## 附录 C：实施记录（2026-10-09，由 ER 方完成）

- **口径调整（本轮定稿）**：强力斩击**吃斩杀、不掷出其不意**。因此 `onMeleeHit` 没有采用单一的
  `allowOffense`，而是拆成两个独立开关 `allowExecute` / `allowSurprise`；
  `strike` 与投掷命中两个开关全开，`heavySlash` 传 `(true, false)`。
- **`remap` 的修正（与本文原方案不同）**：原方案建议每个注入都写 `remap = false`，实测这样会让
  `@At` 里的原版 target（`LivingEntity#hurt`）在生产环境（SRG 名）失配、只在 dev 生效。
  正确做法是**保持默认 `remap = true`**：`ShadowDash#strike` / `#heavySlash` 是 SR 自己的方法名且生产
  不变、映射表里没有它们，保持原名即可；`ThrownSpear` 的两条选择器（dev 的 `onHitEntity` /
  生产的 `m_5790_`）同样保持默认 remap，描述符里的原版类名交给 refmap 正常映射。
- **命中后效果补上了 `MeleeParticles.spawnSurprise`**：ER 自己的 `PlayerEntityMixin` 在出其不意成功时
  会同时播这个粒子，原交接方案漏了。
- **编译期需要 SR jar**：`@Mixin(targets = ...)` 要求目标类出现在编译类路径上，否则注解处理器直接报
  `Mixin target ... could not be found`；因此两个源树的 `libs/` 都放了一份
  `summy-reliquary-1.8.5-forge.jar` 并声明为 `compileOnly`（与矛模组同一套做法）。
- **Fabric 侧额外处理**：SR jar 是 Mojang 名，与 Fabric 的 Yarn 类路径继承链对不上，注解处理器会报
  `Superclass ... was not found in the hierarchy`，因此在 Fabric 的 `build.gradle` 上加了
  `-AdisableTargetValidator=true`（只关编译期目标校验，运行期仍由 Mixin 自己校验）。
- **验证到什么程度**：两树 `gradlew build` 均通过；Forge 的 `verifyMixinTargets` 全绿；编译期注解处理器
  确认 `strike` / `heavySlash` / `m_5790_` 三个目标方法都能被解析（只有 dev 名 `onHitEntity` 报
  "找不到"，那是预期）。**没做"装 SR 的无头服务端冒烟"**：SR 强制依赖 `curios`，而 SR 与 curios
  都只有生产 jar、dev 环境需要逐级 deobf，且 SR 本身是客户端 mod、服务端不是它的使用场景。
  注入点的正确性由上述编译期解析 + 与矛兼容同构的插件逻辑保证，最终以实机验收为准。
