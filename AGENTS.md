# AGENTS.md

Sided-IO —— Rebar 附属的**面配置（Side Configuration）库**。
（Kotlin / Paper 26.2 / Java 25）
**这个文件是给 AI 助手和未来的自己看的工作手册：先读这里，再去翻源码。**

## 这是什么

一个**独立插件**，给别的 Rebar 附属提供"每面输入/输出"能力：机器引入依赖、
在 `postInitialise()` 里一行 `SidedIo.attach(this)...apply()`，就拥有
物流组注册 + 右键切面 + WAILA 提示 + 配置面板 + PDC 持久化 + 原版容器兼容。

**它不注册任何物品/方块。** 它存在的唯一目的是让别的附属少写五套样板代码。

Rebar 和 Paper API 都是 `compileOnly` —— 打出来的 jar 只能跑在
**装了 Rebar 的服务器**上，不要试图独立运行。

## 构建 / 验证

| 目的 | 命令 |
|---|---|
| 编译 + 单测 + 打包 | `./gradlew build` → `build/libs/Sided-IO-<版本>.jar` |
| 只编译（快） | `./gradlew compileKotlin` |
| 只跑测试 | `./gradlew test` |

- 本机受网络策略限制，**依赖下载过不去**：构建一律加 `--offline`。
- `gradle.properties` 里写死了本机 `org.gradle.java.home=D:\Java\zulu25`。
  CI（`.github/workflows/build.yml`）会自动删掉这一行，所以别依赖它。
- 源码、资源、语言文件统一 UTF-8（`gradle.properties` 已强制），**别改编码相关配置**。

## 怎么查 Rebar 源码（重点）

**本机 `E:\Projects\Minecraft\Plugins\Pylon\` 下就有 Rebar 与 Pylon 的完整源码，直接读，不要满盘 find jar。**

```
Pylon/rebar-0.44.4-26.2/     ← 框架本体（Kotlin）
Pylon/pylon-0.42.2-26.2/     ← 官方内容实现（Java）
Pylon/Iron-Furnaces/         ← 面配置的"标准答案"参考（Kotlin）
Pylon/pipez-master/          ← Pipez 参考源码（Java）
```

物流相关源码（本库最常查的）：

```
rebar/src/main/kotlin/io/github/pylonmc/rebar/
  logistics/LogisticGroup.kt              ← 组的定义 + getVanillaLogisticSlots（原版方块的分组表）
  logistics/LogisticGroupType.kt          ← INPUT / OUTPUT / BOTH（没有 NONE）
  logistics/CargoRoutes.kt                ← 按面路由 + 缓存失效
  logistics/slot/LogisticSlot.kt          ← 槽位接口（Long 数量）
  logistics/slot/VirtualInventoryLogisticSlot.kt   ← 公开 val inventory/slot，不需要反射
  logistics/slot/VanillaInventoryLogisticSlot.kt   ← 原版库存槽
  logistics/slot/FurnaceFuelLogisticSlot.kt        ← 燃料校验挂在 getMaxAmount 上
  block/interfaces/LogisticRebarBlock.kt  ← createLogisticGroup（撞名会抛异常！）
  block/interfaces/CargoRebarBlock.kt     ← addCargoLogisticGroup(face, name) + 搬运实现
  waila/Waila.kt                          ← addWailaOverride(block, WailaSupplier)
  event/RebarBlock{Serialize,Deserialize,Load,Unload,Break}Event.kt
```

需要看字节码 / 签名时（gradle 缓存里的 jar）：

```bash
D:/Java/zulu25/bin/javap.exe -p <类>        # 看 API 签名
D:/Java/zulu25/bin/javap.exe -p -c <类>     # 看字节码：常量、默认值、调用顺序
```

Paper API 的 jar 在本机 gradle 缓存里（查 InvUI / Bukkit 接口时用）：

```
~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/<版本>/<hash>/paper-api-<版本>.jar
~/.gradle/caches/modules-2/files-2.1/xyz.xenondevs.invui/invui/<版本>/<hash>/invui-<版本>.jar
```

## 设计约束（改这个库之前必须知道）

这几条不是"建议"，是这个库能成立的前提。

- **`LogisticGroup.slotType` 是 `val`，建完不能改。** 所以"面模式变了要换组类型"
  只能**换一个组名重建**。见 `SideConfig.rebuildGroups` 与 `groupNameFor`：
  组名格式 `<逻辑组名>__<面名>_<模式名>`，把模式编进名字里绕开"不可变 + 撞名抛异常"。
  代价是同一台机器反复切模式会攒下废弃组对象 —— 可接受（方块卸载时 Rebar 整表清空）。

- **`LogisticGroupType` 只有 INPUT / OUTPUT / BOTH，没有 NONE。**
  `SideMode.NONE` 映射成组类型 BOTH + **空槽位列表**（见 `SideMode.toGroupType`）。
  不能"不建组"：那样管道会从面上断开，再切回来也接不上。
  也不能映射成 INPUT/OUTPUT：那会让另一个方向意外可用。

- **组是按"面"建的，不是按"逻辑组"建的。** 因为玩家逐面调，
  一台机器可以有六个面各是不同模式 —— 压不进"一个组一个类型"。

- **WAILA 的 `WailaSupplier` 只有 `Player` 参数、拿不到命中面。**
  必须自己从视线重算（`getTargetBlockExact` + `rayTraceBlocks`）。

- **原版方块的 PDC 挂在 `TileState` 上，不是 `BlockState`。**
  写回时必须 `state.update(true, false)` —— `block.state` 每次返回新快照，
  改它不影响世界里的方块（"存了但重启就没了"）。

- **原版方块没有 `RebarBlock` 实例**，所以不能走 `SidedIoRegistry` 那张
  按实例的身份表 —— 原版路径按**坐标**存（`VanillaSideStore`）。

- **`SideConfig` 恢复玩家配置必须先于 `rebuildGroups`**（组名带模式名）。
  加载时序：`RebarBlockDeserializeEvent` → 构造器 → `postInitialise`（attach/apply）。
  所以反序列化时读出的模式要**暂存**（`SidedIoRegistry.pending`），
  `apply()` 时取走。两者在同一调用栈，不跨 tick。

- **燃料槽等带校验的槽位不能包装/转型。** `FurnaceFuelLogisticSlot.getMaxAmount`
  对非燃料返回 0，包装成普通槽就等于把燃料槽变成垃圾桶。`VanillaSides` 原样返回
  Rebar 给的 `LogisticSlot` 实例。

## 这个生态里踩过的坑（写代码前扫一眼）

- **`RebarUtils` 是 Kotlin 文件门面**（`@file:JvmName`）：Kotlin 里不能写 `RebarUtils.XXX`。
  其内部的 `rebarKey(key)` 是 **`internal`**，外部插件用不了 —— 自己建 `NamespacedKey`。
- **展示实体坐标相对方块中心**（方块表面在 ±0.5），贴面实体要凸出 0.5 一点点。
- **内容必须注册指南页**，否则手册里看不到（本库无内容，暂不涉及）。
- **Kotlin 块注释可嵌套**：文档注释里出现 `/*`（如 lang 的 glob）会再开一层注释，
  编译报 `Unclosed comment`。注释里别写 `/*`；要写就把星号挡一下。
- **InvUI**：`Gui.setItem` 收 `Item`，不是 `ItemProvider`；
  `Item.Builder.setItemProvider` 有 `ItemStack` / `ItemProvider` / `Function<Player, ItemProvider>` 三个重载，
  传 lambda 时注意参数个数决定选哪个 —— 选了不存在的分支会报
  "None of the following candidates is applicable"。
- **InvUI 动态按钮必须用每次求值的 provider**：`setItemProvider(ItemStack)` 只渲染一次，
  点完显示的还是旧内容。用 `Function<Player, ItemProvider>` 重载。
- **InvUI 的 `Click` 是 record**，用 `click.clickType.isRightClick` / `.isShiftClick`。
- **`Material.translationKey()` 返回 `String`** —— 要 `Component.translatable(...)` 包一层
  才能传给收 `Component` 的地方（例如 `WailaDisplay.of`）。
- **`BlockState` 没有 `getPersistentDataContainer()`** —— 在 `TileState` 上。

## 目录约定

```
src/main/kotlin/top/mc506lw/rebar/sidedio/
  SidedIoAddon.kt        入口：注册监听器与命令（不注册内容）
  SidedIoKeys.kt         NamespacedKey 集中处
  SidedIo.kt             门面，对外唯一入口
  SideMode.kt            核心枚举
  SideConfig.kt          面配置表 + SideConfigBuilder
  SidedIoRegistry.kt     Rebar 机器路径的登记处
  SidedInteraction.kt    右键切面
  SidedWaila.kt          WAILA 提示 + 配色
  VanillaSideStore.kt    原版容器路径
  vanilla/VanillaSides.kt
  gui/                   面板（布局逻辑与渲染分开）
src/main/resources/lang/       en.yml + zh.yml（key 必须一致，有测试兜底）
src/test/kotlin/               纯逻辑测试
```

- **能把逻辑抽成纯函数就抽**（`SideMode` 的切换、`SideConfigLayout` 的槽位映射
  都是纯的、可单测的）。别把逻辑塞进 `PlayerInteractEvent` 回调里，
  那样只能开服务器验证。
- 改了 lang 记得中英两份一起加（`LanguageResourcesTest` 会挂）。

## 发布流程

1. 改 `gradle.properties` 的 `version`
2. 提交（见下面的提交约定）
3. `git tag -a v0.1.0 -m "v0.1.0"` → `git push origin main v0.1.0`
4. CI 自动构建并发布 GitHub Release

注意：**CI 不会在普通 push 时构建**，日常验证就本地跑 `./gradlew build --offline`。

## 提交信息约定

中文，正文按「做了什么 / 为什么 / 内容 / 影响面 / 验证」组织：

```
feat(0.1.0): 一句话说清改了什么

做了什么
- ...

为什么
- ...

内容
- ...

影响面
- 无 / 需要重新放置已有方块 ...

验证
- ./gradlew build --offline 通过
- 新增或更新了哪些单测
```

## 本机 git 备注（换了机器可删）

这台机器上 `git push/fetch` 访问 github.com 会因 schannel 吊销检查失败
（`CRYPT_E_NO_REVOCATION_CHECK`）。仓库操作优先用 `gh`；需要 `git push` 时：

```bash
git -c http.sslBackend=openssl -c http.sslCAInfo=C:/Users/mc506lw/.workbuddy-ai/win-ca.pem push
```

`win-ca.pem` 不一定还在；不在就先从 Windows 证书库重新导出。
