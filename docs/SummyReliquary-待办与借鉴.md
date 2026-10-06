# Summy Reliquary 待办与借鉴

> 用途：记录**尚未实施**的优化项与外部参考实现。每条按「**来源 / 现状 / 改法 / 涉及文件 / 验收**」五段写全，
> 实施完成后在条目标题后追加「✅ 已实施（版本号）」，并把实测结果补进对应版本的进度文档。
> 更新时间：2026-10-06　｜　对应版本：**1.8.3-forge**　｜　工程目录：`C:\Users\52527\Documents\ChatGPT\MC mod\summy-reliquary`
> **2026-10-06：以下 7 条已全部在 1.8.3-forge 落地** —— 构建 444 条目 / 34 配方 / 24 进度 / 11 伤害类型，
> 自检 **574 行全绿**、服务端冒烟到 `Done`，jar 已部署到三个测试实例（未提交 GitHub）。

---

## 1. 圣心箭矢追踪 · 借鉴 SwordGuidance 的三项

**来源**：合作者 mofeng945 的仓库 [TinkersNewlife](https://github.com/mofeng945/TinkersNewlife)，
文件 `src/main/java/com/mofengbaizhi/tinkersnewlife/content/entity/SwordGuidance.java`（飞剑制导，单文件完整实现）。
作者已确认是本项目合作者，借鉴与改写均无授权障碍。

**定位差异**：对方是**自推进投掷物**的完整制导系统——自带速度上下限（`vMax` 1.2 / `vMin` 0.35）、
加速度与刹车（`aAccel` 0.08 / `aBrake` 0.4→1.2）、横向加速度上限 `aLat` 0.25（决定角速度上限 `ω = aLat / v`）、
三段制导律（死区直线 / 弦长公式 / 末端收束）、目标速度外推拦截点（迭代 3 次）、线段扫掠命中判定，
以及可写入 NBT 的转向轴状态。我方 [SacredHeart](../src/main/java/com/summy/reliquary/effect/SacredHeart.java)
是**原版箭的方向辅助**——保持速率、无状态、每 tick 重算，把朝向往目标掰一半。

**结论**：只借鉴下列三项，**不引入对方的速度模型与状态机**。

### 1.1 180° 正后方兜底（缺陷修复）✅ 已实施（1.8.3-forge）

- **来源**：SwordGuidance 的 `chooseAxis(...)` 对"完全共线（含掉头 180°）"单独处理——沿用上一次的转轴并投影到与当前方向垂直的平面，保证不左右乱翻。
- **现状**：`steer()` 用 `velocity.normalize().scale(0.5).add(direction.scale(0.5)).normalize().scale(speed)`。当速度方向与"指向目标"方向恰好相反时，两项相加得到零向量，而 `Vec3.normalize()` 对长度小于 `1e-4` 的向量返回零向量，于是箭的速度被写成 0、悬停在半空；下一 tick 又因 `speed < MIN_SPEED(0.05)` 的保护不再介入，永远停在原地。
- **改法**：把纯计算抽成 `public static Vec3 homingDirection(Vec3 velocity, Vec3 toTargetUnit)`（注释标注「自检用」，与既有 `steerForTest` / `nearestEnemyForTest` 同风格）：先取 `speed = velocity.length()`，算出混合结果后判断 `blended.lengthSqr() < 1.0E-8`，成立时直接返回 `toTargetUnit.scale(speed)`，否则维持现有混合逻辑。`steer()` 只负责算夹角（见 1.2）、调用它并写回速度与 `hurtMarked`。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SacredHeart.java`
- **验收**：自检用 `velocity = (0,0,-1)`、`toTargetUnit = (0,0,1)` 直接调用 `homingDirection`，断言结果长度等于原速率（1.0）、单位方向与目标方向点积 ≈ 1，且任何输入下都不产生长度为 0 的速度。

### 1.2 小角度死区（减少无谓写入）✅ 已实施（1.8.3-forge）

- **来源**：SwordGuidance 的 `deadZone = 0.01`（进入）/ `deadZoneExit() = deadZone × 5`（退出）——用滞后避免制导模式来回抖振。
- **现状**：`steer()` 每 tick 都重算并写回速度，且每次都置 `arrow.hurtMarked = true`；箭已经对准目标时仍在持续产生速度同步标记，属于无谓开销。
- **改法**：新增常量 `MIN_TURN_ANGLE = 0.02`（弧度，约 1.15°），与 `HOMING_STRENGTH` 并列，**不新增配置键**；`steer()` 先算 `θ = acos(clamp(û · d̂))`，`θ` 小于该值时直接 `return`——不写速度、不置 `hurtMarked`。**不做**参考实现那套进入/退出滞后：我方没有模式切换，不存在抖振，双阈值只会增加理解成本。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SacredHeart.java`
- **验收**：自检中记录 `Vec3 before = arrow.getDeltaMovement();`，对已对准（θ < 阈值）的箭调用 `steerForTest(arrow, target)`，断言 `arrow.getDeltaMovement() == before`（**同一实例**，说明未被改写）；再构造 θ 明显大于阈值的场景，断言 `arrow.hurtMarked == true` 且与目标的点积变大（方向确实朝目标偏转）。

### 1.3 追踪半径改为真球体（口径修正）✅ 已实施（1.8.3-forge）

- **来源**：SwordGuidance 的 `hitTest(...)` 用"上一 tick 位置 → 当前位置"的线段到目标最近距离与 `hitRadius` 比较，是严格的几何距离判定。
- **现状**：`nearestEnemy(...)` 的候选筛选用 `level.getEntitiesOfClass(LivingEntity.class, arrow.getBoundingBox().inflate(radius), ...)`——`inflate` 得到的是**方盒**，只有"取最近"才按 `distanceToSqr` 算距离。因此斜角方向距离超过标称半径、最远约 `radius × √3`（8 格时约 13.9 格，同戴神性 12 格时约 20.8 格）的敌对生物也会被选中，与提示文本"8 格 / 12 格"不符。
- **改法**：保留 AABB 查询作为**粗筛**（走区块索引，开销不变），在谓词中追加 `entity.distanceToSqr(arrow) <= radius * radius` 作为**球体精筛**；"取最近"沿用同一个距离口径，保持前后一致。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SacredHeart.java`
- **验收**：在现有 `checkSacredHeartHoming`（case 2017）里构造两个敌人并断言——① 位于 `dx = dz = radius × 0.8`（球距约 1.13r，落在旧方盒内）的敌人**不被选中**；② 位于轴向距离 `radius − 0.01` 的敌人**被选中**。1.1 / 1.2 / 1.3 三处的断言全部并入 `case 2017`，**不新增用例编号**。

### 明确不借鉴的部分

- **圆弧角速度上限**（`ω = aLat / v ≈ 0.21 rad/tick ≈ 12°/tick`）：该上限是为飞剑的圆弧观感服务的。对箭而言，转 90° 需要约 7.5 tick，早已飞出 8 格追踪半径，会明显削弱锁定手感。
- **速度上下限与三段制导律**（`vMax/vMin/aAccel/aBrake`、死区直线 → 弦长公式 → 末端收束）：只有自推进实体才需要自行控制速度；我方箭沿用原版弹道，改动速度等于改动武器平衡。
- **NBT 状态持久化**（`lastAxis / mode / inStraightMode`）：那是为了圆弧转向的转轴连续性与读档一致性。我方是每 tick 重算的无状态设计，没有需要持久化的中间量。
- **目标速度外推的提前量**（平滑 + 3 次迭代）：8 格半径内满蓄力箭的飞行时间不到 1 秒，而箭速（约 3 格/tick）远高于怪物移速，提前量的收益很小，不值得引入额外的状态与抖动风险。
- **线段扫掠命中判定**：原版箭已有自己的碰撞与命中结算，重复实现会与实体碰撞逻辑打架。

---

## 2. 其它待办

### 2.1 伯列恒之星 / 终末天启的「造成伤害 +20%」并入圣心 · 神性乘区 ✅ 已实施（1.8.3-forge）

- **来源**：自查发现（非外部参考）。
- **现状**：星与终末天启的 +20% 在 `SpiritAltarSet.onLivingHurt` 对事件金额单独乘 1.2；圣心 / 神性 / 契约 / 献祭则在 `ReliquaryEvents.finalDamageMultiplier` 里相加后一次相乘——两者是**乘算**关系（星 + 圣心 = 1.2 × 1.3 = 1.56）。该层只排除 7 种定值真伤、不排除 `isDivine` 全集，于是**圣光被双重计数**：主击金额已含 ×1.2 → 圣光基准继承 → 圣光事件又被乘一次。
- **改法**：删除 `SpiritAltarSet` 里的星 / 天启乘法；在 `finalDamageMultiplier` 的相加项中新增「佩戴伯列恒之星或终末天启 → `percent += starDamagePercent()`」。作用范围跟随该乘区既有的 `!isDivine(source)` 守卫。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SpiritAltarSet.java`、`src/main/java/com/summy/reliquary/ReliquaryEvents.java`
- **验收**：① 戴星 + 圣心打普通目标 = **1.5 倍**（原 1.56）；② 戴星时圣光不再被额外乘一次；③ 金刀片 / 启示之光 / 献祭 / 恶魔之焰 / 深渊光环 / 黑心碎裂 / 狱火 的数值不变；④ 星与天启的攻速 +20%（属性修饰符那一份）不受影响；⑤ 自检 `case 520`（`testDamageBonus`）现有 12.00 / 13.20 期望不变，新增「星 + 圣心 = 15.00」断言；⑥ 文档同步：`伤害结算顺序` 条目补一句「星 / 天启与圣心 · 神性同乘区相加」。

### 2.2 启示坐标固定为主世界，且首次进入世界即冻结 ✅ 已实施（1.8.3-forge）

- **来源**：自查发现（实现与文案不符）+ 需求。
- **现状**：`RevelationTracker.reveal` 用 `player.serverLevel()`（**当前维度**）的种子与共享出生点派生坐标，而物品提示与百科清单都写「揭示一个**主世界**坐标」；玩家若在下界 / 末地凑满 600 秒，会拿到以该维度出生点为中心的点。
- **改法**：① 派生一律改用 `player.server.overworld()`（种子 + 共享出生点）；② 新增公开静态方法 `RevelationTracker.ensureCoordinate(ServerPlayer)`：X/Z 缺失时派生并落盘，在 `sync(...)` 与 `reveal(...)` 开头调用；③ `reveal` 只置 `revealed` 标记，不再现算坐标；④ 创世纪 `reset` 继续清掉 X/Z，于是重置后下一次 `ensureCoordinate` 按**当时的主世界出生点**重新派生。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/RevelationTracker.java`
- **验收**：① 在下界把累计推满 600 秒触发揭示，坐标仍落在主世界出生点 1000 格内；② 登录 / 换维度 / 死亡重生后坐标不变；③ 同存档两名玩家坐标不同、不同存档坐标不同；④ 创世纪重置后 X/Z 被清、下次检查点重新派生（主世界出生点未变时结果与旧值相同）；⑤ 自检 `case 600`（`checkRevelation`）把距离基准由 `player.serverLevel().getSharedSpawnPos()` 改为 `player.server.overworld().getSharedSpawnPos()`。

### 2.3 恶魔王冠「每损失 10% 生命 +3%、上限 +15%」的作用范围异常 ✅ 已实施（1.8.3-forge）

- **来源**：自查发现。
- **现状**：该加成在 `SpiritAltarSet` 事件金额层单独相乘，排除条件是 `isExactDamage`。由于 `isDivine` 与 `isExactDamage` **互不包含**（`isDivine` 独有 `holy_light` / `pact_shatter` / `hellfire`；`isExactDamage` 独有 `godhead_aura` / `sacrifice`），当前实际会放大三类不该吃的伤害：**圣光**（且基准已含一遍 → 双重计数）、**黑心碎裂**（承诺的 24 / 40 / 60 变成 27.6 / 46 / 69，王冠 + 圣经 + 咒印本就可共存）、以及**玩家自己的献祭自伤**（该层缺 `attacker != victim` 豁免，4 点变 4.6）。
- **改法**（保持独立相乘，只收窄作用范围）：① 排除条件改为 **`isDivine(source) || isExactDamage(source)`**（必须是并集——单纯换成 `isDivine` 会把 `godhead_aura` 与 `sacrifice` 从排除名单里漏掉，反而让神性光环与契约献祭吃 +15%）；② 在该层开头加自伤豁免 `source.getEntity() == event.getEntity()` 时直接返回。改完后本模组 11 种自定义伤害类型全部不吃这一层加成。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SpiritAltarSet.java`
- **验收**：① 王冠 + 圣经 + 咒印、血量 ≤50% 时黑心碎裂 = **40**（不是 46）；② 戴圣光时只继承基线里那一份——圣光的加成只在**主击基准**里继承一次，层内排除后不会再重复吃；③ 神性光环 2 点、契约献祭 10000 点、献祭自伤 4 点均不变，**狱火**同样不吃（它没有归属实体，天然落在本层之外）；④ 普通近战 10 → 11.5 的现有期望不变；⑤ 同一层里星 / 思想 / 魔眼对上述伤害同样不再生效；⑥ 自检扩展 `case 2196`（`checkDevilCrown`），补黑心碎裂、圣光与自伤三条断言。

### 2.4 思想的「对发光目标 +10%」不认原版发光效果 ✅ 已实施（1.8.3-forge）

- **来源**：自查发现（核对 1.20.1 字节码确认）。
- **现状**：`SpiritAltarSet.onLivingHurt` 用 `victim.hasGlowingTag()` 判定，而 1.20.1 的 `Entity.hasGlowingTag()` 只读私有字段 `hasGlowingTag`；原版「发光」效果（光谱箭 / 发光药水）只让 `LivingEntity.isCurrentlyGlowing()` 为真、经 `updateGlowingStatus()` 写 shared flag 6，**不写那个字段**。于是被光谱箭 / 发光药水照亮的敌人吃不到这 +10%，而物品提示与百科写的都是「对发光目标」。另外两项属于**现状事实、本次不改**：视线正对的那一具与环形扫描**同源同值**，都用 `glow_radius`（24 格，只是射线判定不穿墙）；发光标记挂在实体自身、服务端全局共享，**跨玩家**——A 点亮的敌人 B 去打也吃（不引入「谁点的谁吃」）。
- **改法**：把判定从 `victim.hasGlowingTag()` 换成 **`victim.isCurrentlyGlowing()`**（服务端等价于「模组标记的字段 ∨ 原版发光效果」），发光标记本身的施加逻辑与跨玩家共享行为都不动。注意作用区间随之变化：从「24 格内打上的标记（出圈后最多再保留 1 秒）」扩大到「**任何被原版发光效果照亮的目标，不受距离限制**」。顺带订正两处过期数字：`SpiritAltarSet` 类注释里的「15 格」改为「24 格」、`ReliquaryConfig.glowRadius()` 的兜底值 15 改为 24（注册默认值本来就是 24）。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SpiritAltarSet.java`、`src/main/java/com/summy/reliquary/config/ReliquaryConfig.java`
- **验收**：① 用光谱箭照亮 30 格外（超出思想 24 格发光半径）的敌人，三件套齐时命中应吃到 +10%（现值不吃）；② 未发光目标仍然不吃；③ 现有 `case 520`（10 → 12.00 / 13.20）与发光开关用例期望不变；④ 改完后「对发光目标」的文案与实现口径一致。

### 附记（已确认的事实）

- **献祭自伤与一般受伤走同一条路径**：`player.hurt` → `LivingHurtEvent` → `DamagePools`，池子顺序为 **原版吸收 → 魂心 → 黑心 → 生命**（不是「吸收 → 黑心 → 魂心」）；只要护盾总量 ≥ 4 点，献祭就不掉血。刻意保留的差异只有三条：不做死亡拦截、不产生击退、不触发斗篷窗口与七罪受击修正。
- **坐标的独立性**：坐标 = f(世界种子, 玩家 UUID, 主世界出生点)，因此不同存档因种子不同而独立、同存档不同玩家因 UUID 不同而独立（理论上仍有极小碰撞概率）。固定主世界后，物品提示「揭示一个主世界坐标」与实现一致，**不需要改文案**。
- **数值承诺**：2.3 修复后，`咒印` / `亚巴顿` 的「黑心碎裂 24 / 40 / 60」与 `献祭匕首` 的「自损 4 点」才严格成立；修复前这两组数值会被上述加成改写，因此不需要改百科文案，但修复前后务必自查。
- **三条边界已锁定**：① 视线发光与范围发光同源同值（24 格）；② 发光标记的跨玩家共享行为保持不变；③ 原版发光并入判定后，作用区间变为「任何被发光效果照亮的目标，不受距离限制」——实施时不要顺手改动这三条。
