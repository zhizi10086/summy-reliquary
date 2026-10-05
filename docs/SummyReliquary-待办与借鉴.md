# Summy Reliquary 待办与借鉴

> 用途：记录**尚未实施**的优化项与外部参考实现。每条按「**来源 / 现状 / 改法 / 涉及文件 / 验收**」五段写全，
> 实施完成后在条目标题后追加「✅ 已实施（版本号）」，并把实测结果补进对应版本的进度文档。
> 更新时间：2026-10-05　｜　对应版本：**1.8.2-forge**　｜　工程目录：`C:\Users\52527\Documents\ChatGPT\MC mod\summy-reliquary`

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

### 1.1 180° 正后方兜底（缺陷修复）

- **来源**：SwordGuidance 的 `chooseAxis(...)` 对"完全共线（含掉头 180°）"单独处理——沿用上一次的转轴并投影到与当前方向垂直的平面，保证不左右乱翻。
- **现状**：`steer()` 用 `velocity.normalize().scale(0.5).add(direction.scale(0.5)).normalize().scale(speed)`。当速度方向与"指向目标"方向恰好相反时，两项相加得到零向量，而 `Vec3.normalize()` 对长度小于 `1e-4` 的向量返回零向量，于是箭的速度被写成 0、悬停在半空；下一 tick 又因 `speed < MIN_SPEED(0.05)` 的保护不再介入，永远停在原地。
- **改法**：把纯计算抽成 `public static Vec3 homingDirection(Vec3 velocity, Vec3 toTargetUnit)`（注释标注「自检用」，与既有 `steerForTest` / `nearestEnemyForTest` 同风格）：先取 `speed = velocity.length()`，算出混合结果后判断 `blended.lengthSqr() < 1.0E-8`，成立时直接返回 `toTargetUnit.scale(speed)`，否则维持现有混合逻辑。`steer()` 只负责算夹角（见 1.2）、调用它并写回速度与 `hurtMarked`。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SacredHeart.java`
- **验收**：自检用 `velocity = (0,0,-1)`、`toTargetUnit = (0,0,1)` 直接调用 `homingDirection`，断言结果长度等于原速率（1.0）、单位方向与目标方向点积 ≈ 1，且任何输入下都不产生长度为 0 的速度。

### 1.2 小角度死区（减少无谓写入）

- **来源**：SwordGuidance 的 `deadZone = 0.01`（进入）/ `deadZoneExit() = deadZone × 5`（退出）——用滞后避免制导模式来回抖振。
- **现状**：`steer()` 每 tick 都重算并写回速度，且每次都置 `arrow.hurtMarked = true`；箭已经对准目标时仍在持续产生速度同步标记，属于无谓开销。
- **改法**：新增常量 `MIN_TURN_ANGLE = 0.02`（弧度，约 1.15°），与 `HOMING_STRENGTH` 并列，**不新增配置键**；`steer()` 先算 `θ = acos(clamp(û · d̂))`，`θ` 小于该值时直接 `return`——不写速度、不置 `hurtMarked`。**不做**参考实现那套进入/退出滞后：我方没有模式切换，不存在抖振，双阈值只会增加理解成本。
- **涉及文件**：`src/main/java/com/summy/reliquary/effect/SacredHeart.java`
- **验收**：自检中记录 `Vec3 before = arrow.getDeltaMovement();`，对已对准（θ < 阈值）的箭调用 `steerForTest(arrow, target)`，断言 `arrow.getDeltaMovement() == before`（**同一实例**，说明未被改写）；再构造 θ 明显大于阈值的场景，断言 `arrow.hurtMarked == true` 且与目标的点积变大（方向确实朝目标偏转）。

### 1.3 追踪半径改为真球体（口径修正）

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

（暂无）
