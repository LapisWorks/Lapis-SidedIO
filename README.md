# Sided-IO

给 **Rebar 附属**用的**面配置（Side Configuration）库**：机器引入依赖、给插槽，
立刻拥有完整的"每面输入/输出"能力和配套面板。

> 这套设计就是 Mekanism / Thermal Expansion / Iron-Furnaces 那个**面配置面板**
> —— 点方块的一个面，在 `输入 / 输出 / 双向 / 关闭` 之间切。
> 它起源于 Thermal Expansion（2013 年左右），现在是整个科技模组圈的事实标准。
>
> 🤖 **写代码前先读 [AGENTS.md](./AGENTS.md)** —— 里面写了这个库的设计约束、
> 怎么查 Rebar 源码、以及踩过的坑。

## 这是什么 / 不是什么

| 是 | 不是 |
|---|---|
| 一个**独立插件**（装了就生效的基础设施）| 不是物品/方块内容包 —— 它不注册任何东西 |
| 给别的附属用的 **API**（`compileOnly` 依赖它）| 不是给玩家直接用的玩法模组 |
| Rebar **物流组**（`LogisticGroup`）的一层封装 | 不是另起一套物流系统 |

其他附属 `depend: [Sided-IO]` 之后，就能用一行代码给自己的机器接上面配置。

## 快速开始（附属开发者）

```kotlin
class MyFurnace : RebarBlock(...), LogisticRebarBlock, TickingRebarBlock {

    private val inputInv = VirtualInventory(6)
    private val fuelInv = VirtualInventory(1)
    private val outputInv = VirtualInventory(6)

    override fun postInitialise() {
        SidedIo.attach(this)
            // ① 进料：所有面都能进
            .channel(
                ChannelTypes.ITEM, inputInv,
                default = SIDED_FACES.associateWith { SideMode.INPUT },
                slotType = LogisticGroupType.INPUT, name = "input",
            )
            // ② 燃料：只收燃料，否则管道会把矿石、垃圾全塞进燃料槽
            .channel(
                ChannelTypes.ITEM, fuelInv,
                default = SIDED_FACES.associateWith { SideMode.INPUT },
                filter = { it.type.isFuel },
                slotType = LogisticGroupType.INPUT, name = "fuel",
            )
            // ③ 产出：只有底面出
            .channel(
                ChannelTypes.ITEM, outputInv,
                default = SIDED_FACES.associateWith { SideMode.NONE } +
                    (BlockFace.DOWN to SideMode.OUTPUT),
                slotType = LogisticGroupType.OUTPUT, name = "output",
            )
            .machineSettings(
                MachineSetting.REDSTONE, MachineSetting.AUTO_INPUT, MachineSetting.AUTO_OUTPUT,
            )
            .apply()
    }

    /** 自动输入 / 自动输出：机器自己跟相邻容器搬东西，不靠管道。就一行。 */
    override fun tick() {
        SidedIo.sides(this)?.let { SidedIo.autoTick(block, it) }
    }
}
```

> 完整可编译版本见 [`ExampleSidedMachine.kt`](./src/main/kotlin/top/mc506lw/rebar/sidedio/ExampleSidedMachine.kt)。

### ⚠️ `slotType` 和 `default` 是**两件不同的事**

| 参数 | 说的是 | 会不会变 |
|---|---|---|
| `default` | **面**出厂朝哪个方向 | 玩家随时改 |
| `slotType` | **这组槽本身**是进料口还是产出口 | 永远不变 |

两者**交叉**（`SideMode.covers`）才是"这个面能不能碰这组槽"。
少了 `slotType` 就会退化成"面是什么方向，就把该类型下所有组都套成那个类型" ——
于是侧面设成 `输入` 时**产出槽也变成输入**（成品再也抽不走）、
设成 `输出` 时**进料槽也变成输出**（没烧的矿石能被抽走）。实测踩过。

三条通道的 `default` 会**按面合并**（`SideConfig.mergeDefault`）：
相同取原样、一个 `NONE` 取另一个、`INPUT` vs `OUTPUT` → `BOTH`。
所以上面那三条合起来是 **"所有面都能进料、只有底面能出成品"**。

这一串做完的事：

1. 按面建好 Rebar 的 `LogisticGroup` 并注册；
2. 玩家拿 **RebarWrench 扳手**右键任意面 → 循环切换模式（潜行右键反向）；
3. 玩家看哪个面，WAILA 就显示那个面的模式（蓝进 / 橙出 / 灰双向 / 红关闭）；
4. 面板 `/sidedio` 把面摆成网格，逐个点着改（切通道是**原地重绘**，光标和鼠标位置都不动）；
5. 面模式存进方块 PDC，重启不丢；
6. 面模式变了自动重算物流组，并广播 `SidedIoFaceConfigChangedEvent`（管道之类的读取方据此重算）。

**原版容器**（熔炉 / 酿造台 / 箱子 / 漏斗 / 桶 / 潜影盒 / 合成器 / 书架）开箱即用 ——
靠 Rebar 的 `LogisticGroup.getVanillaLogisticSlots`，不需要任何额外代码。

## 自定义面状态（第五档、第六档…）

内置四档只表达**方向**。真实机器会长出更细的状态 —— 比如铁熔炉的第五档
**「燃料输入 / 弹出」**：燃料从这一面进、成品从这一面**弹出**（同一面上两组槽方向相反）。
这表达不成"一个方向"，所以 `SideMode` 是**可注册的普通类**，用"兜底方向 + 按组名的规则"描述：

```kotlin
// 在附属的 onEnable 里，早于任何机器被放下来
SideModes.register(
    id = "ironfurnaces:fuel_input_eject",       // 会进 PDC，一旦发布就别改
    direction = SideMode.Flow.BOTH,             // 整体算"双向"
    groupRules = mapOf(                         // 只碰名单里的组，各自一个方向
        "fuel" to SideMode.Flow.IN,             //   组名来自 channel(..., name = "fuel")
        "output" to SideMode.Flow.OUT,
    ),
)
```

之后面板上就会多出这一档，玩家点得到、PDC 存得住。

| 约定 | 说明 |
|---|---|
| **翻译键** | `<命名空间>.mode.<名字>` —— `ironfurnaces:fuel_input_eject` → `ironfurnaces.mode.fuel_input_eject`，文案写在**注册方自己的** lang 文件里 |
| **撞 id** | `register` 会**抛异常**（不覆盖）—— 防两个附属抢同一个 id |
| **重载** | 插件重载会再跑一次 `onEnable`，而注册表是进程级单例 → 注册前先 `SideModes.find(id)` 查一下 |
| **老存档** | `find` **忽略大小写**（老存档存的是 enum 时代的 `"INPUT"` 大写） |

## 玩家怎么用

| 操作 | 效果 |
|---|---|
| **拿扳手右键**方块的一个面 | 该面切到下一个模式（双向 → 输入 → 输出 → 关闭 → 自定义档）|
| **拿扳手潜行右键** | 反向切 |
| 看着一个面 | WAILA 顶部条显示该面的模式 |
| `/sidedio`（或 `/sio`）| 打开面配置面板 |
| `/sidedio info` | 打机器级设置 + 每个面的 `模式 [可插 N / 可抽 M]` + 邻居是什么（排查"自动输入输出没反应"用）|
| `/sidedio wrench` | 打印 RebarWrench 注册表（排查"扳手认不认这台机器"）|

### 扳手从哪来

切面手势由第三方插件 [RebarWrench](https://github.com/lijinhong11/RebarWrench) 提供。
它是**软依赖**：装了就能拿扳手切面，没装则退化为只能用 `/sidedio` 面板。

本插件通过反射桥（`WrenchBridge`）对接它，而不是硬依赖 —— 因为 RebarWrench
硬依赖 Pylon 且是 GPL-3.0，而 Sided-IO 保持"只依赖 Rebar"。

**给附属作者**：RebarWrench 按**精确类**查表（不走父类），所以你的每个机器实现类
都要单独上报：

```kotlin
override fun onEnable() {
    SidedIo.registerWrenchTarget(MyFurnace::class.java)
}
```

比 Sided-IO 先启用的附属会自动排队，等 Sided-IO 就绪后补登记。

> ### ⚠️ 与 Rebar 0.44.4+ 不兼容（截至 RebarWrench 1.0.4）
>
> **RebarWrench 1.0.4 在 Rebar 0.44.4 上会让玩家一连就掉线**，请先不要在服务器上装它。
>
> 原因：Rebar 在 0.44.x 把 `RebarAddon.getDefaultLanguage()` 改成了**抽象方法**，
> 而 RebarWrench 1.0.4 编译时依赖的旧 Rebar 里它还有默认实现，类里根本没这个方法。
> 于是 Rebar 做**物品翻译**时抛：
>
> ```
> java.lang.AbstractMethodError:
>   Method io/github/lijinhong11/rebarwrench/RebarWrench.getDefaultLanguage()Ljava/util/Locale; is abstract
>   at io.github.pylonmc.rebar.i18n.RebarTranslator.getRawTranslation(RebarTranslator.kt:148)
> ```
>
> 这个异常在 netty 发包路径上 → `EncoderException` → **玩家被踢**。
> 已验证 Maven Central 上最新就是 1.0.4（2026-06-09），上游暂无修复版。
>
> **对策**：把 jar 从 `plugins/` 挪走即可。Sided-IO 是软依赖，没装扳手会正常降级
> （`/sidedio` 面板照常可用，只是少了右键切面）。等 RebarWrench 适配 0.44.x 后再装回来。
>
> 顺带：这个坑也说明软依赖的设计是对的 —— 硬依赖会让 Sided-IO 一起加载失败。

模式配色沿用 Thermal Expansion 的约定，老玩家一眼就懂：

| 模式 | 面板（混凝土）| WAILA（文字）| 含义 |
|---|---|---|---|
| 输入 | 🔵 蓝 | 🔵 蓝 | 只许往里插（进料口）|
| 输出 | 🟠 橙 | 🟠 橙 | 只许往外抽（产出口）|
| 双向 | 🟣 紫 | ⚪ 灰 | 能插能抽（普通箱子）|
| 关闭 | ⚫ 深灰 | 🔴 红 | 管道连得上，但两头都搬不动 |

## 自动输入 / 自动输出

机器不用管道也能自己搬东西 —— 在自己的 `tick()` 里喊一声就行（见快速开始）。
两个开关都关着时**零开销**（直接返回）。

这是**纯 Sided-IO 的活儿**：搬什么、从哪个面搬，全在面配置里。所以机器不用自己写
"找相邻容器 → 遍历槽 → 先合并再找空位"那一套。

> ### ⚠️ "开了自动输出却没反应" —— 先看那个面能不能出
>
> **出厂默认是"所有面进料、只有底面出成品"**，所以打开自动输出后
> **只有底面会生效**：侧面出厂是 `输入`，`canExtract` 为 false，会被直接跳过。
> 想让侧面也出货，先把那个面设成 `输出` 或 `双向`。
>
> 对准机器敲 **`/sidedio info`** 一眼就能确认：
>
> ```
> 机器级：红石=… 自动输入=开 自动输出=开
> down  · sidedio:item → 双向 [可插 2 / 可抽 1]   ← 可抽 > 0 才会输出
> north · sidedio:item → 输入 [可插 2 / 可抽 0]   ← 可抽 0 = 这个面不出货
>    邻居: 容器 / 空气 / 石头（不是容器）
> ```
>
> 另外**邻居必须是 `Container`**（箱子 / 桶 / 漏斗…）—— 是别的 Rebar 机器不会搬。

两个方向**各查各的槽**，这是防事故的关键：

| 方向 | 用哪些槽 |
|---|---|
| 自动输入（机器**收**）| `insertSlotsFor` —— 只走进料侧 |
| 自动输出（机器**吐**）| `extractSlotsFor` —— 只走产出侧 |

用 `slotsFor` 一把抓会出两种反向事故：双向面上自动输入把矿石塞进**成品槽**、
自动输出把**没烧的原料**一起抽走（实测踩过）。

## 前置要求

| 要求 | 版本 |
|---|---|
| Minecraft | 26.2（Paper 或其分支）|
| Rebar | 0.44.4+ |
| JDK | 25 |
| RebarWrench（可选）| 1.0.4 —— **在 Rebar 0.44.4 上会踢人，暂时别装**，见上文 |

Rebar 与 Paper API 都是 `compileOnly`：**打出来的 jar 不能独立运行**，必须放进装了 Rebar 的服务器。

## 构建

```bash
./gradlew build               # 编译 + 单测 + 打包 → build/libs/Sided-IO-<版本>.jar
./gradlew compileKotlin       # 只编译（快）
./gradlew test                # 只跑测试
./gradlew publishToMavenLocal # 发布到 ~/.m2，供下游插件 compileOnly 引用
```

### 作为依赖被别的插件引用

Sided-IO 会把 jar + sources 发到 mavenLocal。下游工程（如 Iron-Furnaces）：

```kotlin
repositories { mavenLocal { content { includeGroup("top.mc506lw.rebar.sidedio") } } }
dependencies { compileOnly("top.mc506lw.rebar.sidedio:Sided-IO:$sidedIoVersion") }
```

**改了 Sided-IO 的公开 API 后，务必先 `publishToMavenLocal` 再构建下游**，
否则下游会拿旧 jar 编译报 `Unresolved reference`。

## 目录结构

```
src/main/kotlin/top/mc506lw/rebar/sidedio/
  SidedIoAddon.kt        入口：注册监听器与命令
  SidedIoKeys.kt         NamespacedKey 集中处
  SidedIo.kt             门面：attach() / reattach() / sides() / autoTick() / registerWrenchTarget()
  SideMode.kt            面状态（**可注册的类**，不是枚举）+ SideModes 注册表 + SIDED_FACES
  SideConfig.kt          面配置表（通道定义 + 面模式 + 组重建 + PDC 持久化）+ SideConfigBuilder
  ChannelType.kt         通道类型注册表（物品 / 流体 / 能量 / 气体 / 第三方）
  MachineSettings.kt     机器级设置（红石 / 自动输入 / 自动输出）—— 只存值，行为由机器实现
  RedstoneMode.kt        红石四态
  AutoIo.kt              自动输入 / 自动输出的搬运实现（plan() 是纯函数，可单测）
  EnergyPorts.kt         能量端口的只读探针（端口在哪面、接了几根线）
  SidedIoRegistry.kt     Rebar 机器路径的登记处（身份表 + PDC 持久化 + 事件）
  SidedIoFaceConfigChangedEvent.kt  面配置变更事件（管道之类的读取方订阅）
  SidedInteraction.kt    切面的公共逻辑（由扳手 / 面板两处调用，不自己监听事件）
  WrenchBridge.kt        RebarWrench 反射桥（软依赖：装了才对接）
  SidedWaila.kt          WAILA 提示 + 配色
  FaceRole.kt            面 → 角色（前 / 后 / 左 / 右 / 上 / 下），面板按角色摆格子
  VanillaSideStore.kt    原版容器路径的存储 + 桥接
  LanguageDebug.kt       `/sidedio lang` 翻译链路自证
  ExampleSidedMachine.kt 集成示例（未注册，只作参考）
  vanilla/VanillaSides.kt       原版容器语义（走 Rebar 的原版物流组）
  vanilla/VanillaFaceSlots.kt   原版 `getSlotsForFace` 的带缓存读取（只用于推导出厂方向）
  gui/SideConfigLayout.kt       面板布局的纯逻辑（可单测）
  gui/SideConfigGui.kt          面板渲染（InvUI，原地重绘 + 返回按钮）
  gui/SidedIoCommand.kt         /sidedio 命令
src/main/resources/lang/       en.yml + zh.yml（key 必须一致，有测试兜底）
src/test/kotlin/               125 个用例：面语义、组名、布局、机器设置、原版语义、
                               自动搬运的面规划、lang 一致性
```

## 其它附属怎么接

1. 在 `build.gradle.kts` 里加 `compileOnly("...:Sided-IO:<版本>")`
2. 在 `plugin.yml` 里加 `depend = listOf("Rebar", "Sided-IO")`
3. 机器实现 `LogisticRebarBlock`，在 `postInitialise()` 里 `SidedIo.attach(this)...apply()`

## 本机说明

- `gradle.properties` 里写死了本机 JDK 路径 `org.gradle.java.home=D:\Java\zulu25`；
  换机器请改掉或删除。CI 会自动删这一行。
- 中文资源统一 UTF-8，相关配置不要改。
- 本机依赖下载受网络策略限制，构建用 `--offline`。

## 发布

```bash
# 1. 改 gradle.properties 里的 version
# 2. 提交
# 3. 打标签推送
git tag -a v0.1.0 -m "v0.1.0"
git push origin main v0.1.0
# → CI 自动构建并发布 GitHub Release
```
