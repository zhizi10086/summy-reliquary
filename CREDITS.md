# 素材来源与鸣谢

> 本模组自 2026-09-19 起是 **Forge 1.20.1 + Curios** 版本（此前的 Fabric + Trinkets 版本已原地改造）。
> 许可见仓库根目录的 `LICENSE`（**自定义许可**：允许整合包收录、转载与个人修改，**必须保留声明并署名**，
> **禁止任何商业用途**）。本模组的原创代码与原创素材同样受该自定义许可约束；取自或改编自《以撒的结合》的
> 贴图与设定**不在本许可范围内**，其权利属于原作者 / 权利方。
>
> **许可分层（1.7.10 起）**：`LICENSE` 已把 **源码（Source Code）**、**资产（Assets）**、**构件（Artifacts，发布的 jar）**
> 分成三节分别声明 —— 源码与构件均为"允许整合包 / 转载 / 个人修改、须署名并保留声明、**禁止任何商业用途**"；
> 资产另按四类来源细分（原创素材随本模组禁商用 / 《以撒的结合》素材**不主张任何权利** /
> 白饭贴图属 Farmer's Delight（MIT）/ `short_flame` 复制自原版贴图）。

## 致谢与二创声明（《以撒的结合》）

**中文**

本模组（Summy Reliquary）是一款基于《以撒的结合》（The Binding of Isaac）与基督教传统宗教文化符号的融合与再解读的**粉丝二创作品**。

我们衷心感谢《以撒的结合》及其原作创作者 Edmund McMillen 与发行方 Nicalis, Inc.，感谢他们创造了一个如此深邃、黑暗而富有诗意的世界。本模组的**物品贴图与部分设定有相当数量直接取自或改编自该作品**；我们对这部分素材**不主张任何权利**，仅用于非商业的粉丝创作与免费分享。本模组并非官方作品，与 Edmund McMillen、Nicalis, Inc. 及任何宗教组织均无隶属或合作关系。

本模组对七宗罪、天使、恶魔、天启等传统宗教元素的运用，属于文化符号的艺术再解读，旨在探索叙事与游戏机制的可能性，不代表任何宗教立场，亦无意冒犯任何信仰。我们尊重所有宗教传统与个人信仰。

本模组完全免费，仅供学习与娱乐，**禁止用于任何商业用途**。若任何内容无意中侵犯了您的权益，请通过 **525277385@qq.com** 与我们联系；一经核实，我们将**立即移除相关素材并发布修复版本**。

整合包收录与转载**允许**，但请保留本声明，并注明模组名与作者。

感谢所有玩家的支持与反馈。

**English**

**Acknowledgements & Fan-Work Disclaimer**

Summy Reliquary is a **fan-made derivative work** that fuses and re-interprets The Binding of Isaac together with traditional Christian religious imagery.

Our sincere thanks to The Binding of Isaac, its creator Edmund McMillen and its publisher Nicalis, Inc., for a world so deep, dark and poetic. A **substantial portion of this mod's item textures and some of its design concepts are taken directly from, or adapted from, that game**; we **claim no rights** over such material and use it only for non-commercial fan work and free distribution. This mod is not an official product and is not affiliated with or endorsed by Edmund McMillen, Nicalis, Inc. or any religious organization.

Traditional religious elements such as the Seven Deadly Sins, angels, demons and the Apocalypse are used as an artistic re-reading of cultural symbols, to explore narrative and gameplay possibilities. This represents no religious position and intends no offence to any belief. We respect all religious traditions and personal beliefs.

This mod is completely free, for learning and entertainment only, and **must not be used commercially**. If any content unintentionally infringes your rights, please contact us at **525277385@qq.com**; once verified, we will **remove the material immediately and publish a fixed release**.

Modpack inclusion and redistribution are **permitted**, provided this notice is kept and the mod name and author are credited.

Thank you to every player for your support and feedback.

## 贴图

- **白饭（`summy-reliquary:freeloaders_rice`）的物品贴图**
  取自 [Farmer's Delight（农夫乐事）](https://modrinth.com/mod/farmers-delight)，作者 vectorwing。
  该模组以 **MIT 许可证** 发布；本项目在保留本条署名与许可说明的前提下借用该贴图。
- **复仇之魂的「短寿命火焰」粒子贴图**（`textures/particle/short_flame.png`）：
  取自**原版** `minecraft:textures/particle/flame.png`（复制一份并缩短寿命，用于去掉火焰环的拖尾）。
- **物品贴图与部分设定**（`duality_stat` / `your_soul` / `error` / `purity` 等图标物品、两把仪式匕首
  `sacrificial_dagger` / `dark_arts`、两把天使线长矛 `holy_spear` / `seraph_spear`、金刀片 `golden_razor`、
  恶魔王冠 `devil_crown`，以及其余遗物素材）：**有相当数量直接取自或改编自《以撒的结合》**
  （The Binding of Isaac，原作创作者 Edmund McMillen / 发行方 Nicalis, Inc.）—— 这部分素材
  **不在本工程的许可范围内**（本模组对该部分素材不主张任何权利），详见上面的《致谢与二创声明》。
- **MOD 图标**（`assets/summy-reliquary/icon.png`）：由根成就图标 `duality_stat.png` 以
  **2× 最近邻放大**（16×16 → 32×32）生成；`duality_stat` 属于上述取自《以撒的结合》的素材。
- **栏位 / HUD / 效果图标**（`textures/slot/*.png`、`textures/gui/soul_heart/*.png`、
  `textures/gui/demon_black_heart/*.png`、`textures/mob_effect/*.png`）：作者自绘。

## 代码参考

- **箭矢追踪（圣心）**的制导思路参考了**合作者 mofeng945** 的 [TinkersNewlife](https://github.com/mofeng945/TinkersNewlife)
  中 `src/main/java/com/mofengbaizhi/tinkersnewlife/content/entity/SwordGuidance.java`（飞剑制导）。
  当前只借鉴三项：正后方 180° 兜底、小角度死区、追踪半径的球体距离判定；
  具体改法与验收标准记录在 `docs/SummyReliquary-待办与借鉴.md`（尚未实施）。

## 依赖与兼容

- [Forge](https://files.minecraftforge.net/) `47.4.10`（Minecraft 1.20.1）：模组加载器（**必需**）。
- [Curios](https://modrinth.com/mod/curios)：饰品槽位系统（**必需的前置模组**，本模组的 7 个栏位都基于它）。
- [Enchantment Reforged](https://www.mcmod.cn/class/23213.html)：**可选的软依赖** —— 影响「大胃袋」饥饿上限、
  「出其不意 / 魔剑」与圣光的交互、以及嗜血与契约吸血的结算口径；没装也能正常玩。
- [JEI](https://modrinth.com/mod/jei)（可选）：装了之后本模组会按玩家状态隐藏 / 放开受门禁管制的配方
  （天使线看天使标记、仪式法袍看是否已签约、恶魔线看邪恶度解锁位图、防丢失配方看是否处于丢失态）。
- 训练人偶（`dummmmmmy:target_dummy`，可选）：救恩领域与神性光环可按配置把训练人偶算作目标。
