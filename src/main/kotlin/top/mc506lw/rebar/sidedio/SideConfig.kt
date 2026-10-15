package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.LogisticRebarBlock
import io.github.pylonmc.rebar.logistics.LogisticGroup
import io.github.pylonmc.rebar.logistics.LogisticGroupType
import io.github.pylonmc.rebar.logistics.slot.LogisticSlot
import io.github.pylonmc.rebar.logistics.slot.VirtualInventoryLogisticSlot
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.invui.inventory.VirtualInventory

/**
 * 一台机器的"面配置表"。
 *
 * ## 模型：两张正交的轴
 *
 * ```
 *           NORTH   EAST   SOUTH   WEST    UP    DOWN      ← 面（永远六个，除非主动屏蔽）
 *  物品  │  双向  │ 输入 │ 输出 │  ...  │ 空  │ 输入 │      ← 每个面 × 每个类型 各一格方向
 *  能量  │  空    │ 空   │ 空   │  ...  │ 输入│ 空   │
 *  流体  │  …                                              ← 机器注册了才有这一行
 * ```
 *
 * - **纵向**是 [ChannelType]（流的是什么）—— 机器注册哪个类型就有哪一行。
 * - **横向**是 [BlockFace]（往哪流）—— 六个面永远都在，由机器声明"接哪些面"。
 * - **格子里**是 [SideMode]（怎么流）—— 双向 / 输入 / 输出 / 空。
 *
 * 这两个轴**完全正交**：玩家调"北面的物品口"和"北面的能量口"是两件互不相干的事。
 * 所以内部存储是 `(face, type) → SideMode` 的二维表，不是"面 → 模式"的一维表。
 *
 * ## ⚠️ 为什么"面模式"那套一维模型被推倒了
 *
 * 老实现里一个面只有**一个**模式。那对熔炉勉强够用（输入/输出/燃料方向一致），
 * 但表达不了"这个面能量只准抽走、物品只准灌进来"—— 因为面和类型耦合在一起了。
 * 用户原话："怎么只有6个面…不是有能量，流体啊等吗"。
 *
 * 新模型里，"六个面各是什么"仍然答得出来（[faceModes] 把所有类型聚合到面上，
 * 给 WAILA 那种"只看方向"的地方用），但真相是那个二维表。
 *
 * ## 概念对齐：槽位集合（slots）是怎么来的
 *
 * 玩家调的是 `(面, 类型)`；Rebar 的 [LogisticGroup] 只认"一组共享功能的槽"。
 * 桥接方式：
 *
 * - 开发者按**类型**声明槽位集合（`channel(ChannelTypes.ITEM, INPUT, slots = 输入箱)`）。
 * - 玩家给某个 `(面, 类型)` 设一个方向。
 * - [rebuildGroups] 把"这一格方向"翻译成一个 Rebar 组，槽位从该类型的集合里取。
 *
 * 所以**类型是槽位的归属，面是通路的开关** —— 两者分层清楚，互不污染。
 *
 * ## ⚠️ 为什么"方向变了"要重建组对象，而不是原地改
 *
 * Rebar 的 [LogisticGroup] 有个硬限制：**`slotType` 是 `val`，建完不能改**。
 * 而玩家把一格从 INPUT 切成 OUTPUT 时，组的类型就得从 INPUT 变 OUTPUT ——
 * 原地改不了。
 *
 * 也不能"删掉旧组再建同名新组"：`LogisticRebarBlock.createLogisticGroup` 会
 * `check(!contains(groupName))`，撞名直接抛异常。
 *
 * 所以这里用**代际后缀**：方向变了就建一个 `<类型名>__<面名>_<模式名>` 的新组。
 * 老组留在 Rebar 的 map 里（没人按老名字找了，等于被弃用），管道那边按新名字取。
 * 名字里的代际后缀是可预测的（只由类型名+面名+模式名决定，不含运行时状态），
 * 所以读盘 / 热重载后依然对得上。
 */
class SideConfig internal constructor(
    /**
     * 这台机器注册的槽位组：**类型 id → 该类型下的若干组槽位**。
     *
     * 为什么是"若干组"而不是"一份列表"：熔炉的"物品"类型下同时有
     * 输入槽、燃料槽、输出槽三组，**各组的插入过滤条件不同**
     * （燃料槽只收燃料，输入槽什么都收）。过滤条件是挂在 Rebar 的
     * `LogisticGroup` 上的，所以必须**一组槽位一个组对象**，
     * 否则燃料滤会被输入组的"不过滤"覆盖掉
     * （同类型的槽位合并成一份就必然撞上这个）。
     *
     * 用类型 id（而不是实例）当键，是为了让 [SideConfig] 能脱离 `ChannelTypes`
     * 注册表独立存在 —— 读盘还原时注册表可能还没建好，先按 id 存着，
     * 查出来再解析成实例。
     */
    internal val slotGroups: LinkedHashMap<ChannelKey, List<SlotGroup>>,
    /** 每个类型出厂接到哪些面上（**不是**方向，是"这个类型的通道在哪些面存在"）。 */
    internal val channelFaces: LinkedHashMap<ChannelKey, MutableSet<BlockFace>> = LinkedHashMap(),
    /** 出厂时 `(面, 类型)` 各是什么方向（没显式给的面由 [defaultFor] 推导）。 */
    internal val defaults: LinkedHashMap<ChannelKey, LinkedHashMap<BlockFace, SideMode>> = LinkedHashMap(),
    /**
     * **机器级**设置（红石 / 自动输入 / 自动输出）—— 和面无关的那部分。
     *
     * 见 [MachineSettings] 的类注释：Sided-IO 只存值，行为由机器实现。
     */
    val settings: MachineSettings = MachineSettings(emptySet()),
    /**
     * 机器朝向 —— 面板用它决定"前面"落在哪个面。
     *
     * 已经过 [FaceRole.normalizeFacing] 归一（保证是水平朝向）。
     */
    val facing: BlockFace = BlockFace.NORTH,
    /**
     * **只展示、不搬运**的通道（见 [SideConfigBuilder.displayChannel]）。
     *
     * 它们没有槽位、不建物流组，但要在面板左侧列出来，且选中时右边的面格子
     * 显示的是"实际端口在哪"（[EnergyPorts]）而不是一个改了不生效的开关。
     */
    internal val displayKeys: Set<ChannelKey> = emptySet(),
) {

    /**
     * `(面, 类型) → 方向` 的**玩家覆盖**。
     *
     * 内部用 [ChannelKey] 而不是 `ChannelType` 实例 —— 见 [slotGroups] 的注释。
     * 用二维 map（面 → 类型 → 模式）而不是 pair 当键，是因为遍历时
     * "先按面聚合"和"先按类型聚合"都用得上，二维结构两种顺序都 O(类型数)。
     */
    private val modes = HashMap<BlockFace, MutableMap<ChannelKey, SideMode>>()

    /**
     * 上一次 [rebuildGroups] 实际建出来的组名（见 [activeGroupNames]）。
     *
     * 存在的理由：Rebar 没有删组的 API，改方向 / 改槽位后老组会残留，
     * 读取方需要一份"当前真正有效"的名单来过滤。
     */
    private val activeGroups = linkedSetOf<String>()

    /** 机器主动屏蔽掉、**完全不该出现在面板上**的面。 */
    internal val hiddenFaces = linkedSetOf<BlockFace>()

    /** 机器屏蔽一个面（[SideConfigBuilder.hideFace] 收尾时调）。 */
    internal fun hideFaceInternal(face: BlockFace) {
        hiddenFaces += face
    }

    // ------------------------------------------------------------ 查询

    /**
     * 某个 `(面, 类型)` 的当前方向。
     *
     * 没被玩家改过的格子由 [defaultFor] 从"类型的出厂设置 + 槽位集合"推导。
     */
    fun mode(face: BlockFace, type: ChannelType): SideMode = mode(face, ChannelKey.of(type))

    /** 同 [mode]，但按 [ChannelKey] 查（内部 / 持久化路径用）。 */
    internal fun mode(face: BlockFace, key: ChannelKey): SideMode =
        modes[face]?.get(key) ?: defaultFor(face, key)

    /**
     * 某个 `(面, 类型)` 的默认方向。
     *
     * 优先级：
     * 1. 开发者在 `channel(...)` 里显式给的 [defaults]；
     * 2. 这个类型的通道**接不接这个面** —— 不接就是 [SideMode.NONE]；
     * 3. 否则从槽位集合的规模推导。
     *
     * ⚠️ 第 3 条为什么要看槽位：熔炉的"输入"和"输出"都是 ITEM 类型。
     * 如果默认一律给 BOTH，那玩家一放下来管道就会把矿石当成品抽走 ——
     * 这是 Sided-IO 从第一天起就在防的那个事故（见 SideMode 的类注释）。
     * 所以记不住方向时宁可给 NONE（不搬），而不是给 BOTH（乱搬）。
     */
    private fun defaultFor(face: BlockFace, key: ChannelKey): SideMode {
        if (face in hiddenFaces) return SideMode.NONE

        val explicit = defaults[key]?.get(face)
        if (explicit != null) return explicit

        val faces = channelFaces[key]
        if (faces != null && face !in faces) return SideMode.NONE

        return SideMode.DEFAULT
    }

    /** 某个面上的所有类型 —— **面板左侧要用它决定画几行**。
     *
     * 顺序固定：先按 [ChannelTypes.all] 的注册顺序（核心类型在前，第三方在后）。
     * 机器没注册的类型不出现（"注册哪个有哪个"）。
     */
    fun typesOn(face: BlockFace): List<ChannelType> =
        if (face in hiddenFaces) emptyList() else typesPerFaceInternal(face)

    /**
     * 这台机器**用到的所有类型**（不分面）。面板左侧列的就是它。
     *
     * 取的是"注册过槽位集合的类型"的并集 —— 一个类型只要有一个面接了就列出来。
     */
    fun types(): List<ChannelType> = orderedKeys().mapNotNull { ChannelTypes.find(it.id) }

    /** 这台机器用到的类型（按 id），内部 / 持久化用。 */
    internal fun orderedKeys(): List<ChannelKey> {
        val used = LinkedHashSet<ChannelKey>()
        // 先按注册表的全局顺序（核心类型优先），保持面板稳定
        for (type in ChannelTypes.all()) {
            val key = ChannelKey.of(type)
            if (slotGroups.containsKey(key) || key in defaults.keys || key in displayKeys) used += key
        }
        // 再把注册表里没有的（第三方卸了 / 老存档）补在后面
        for (key in slotGroups.keys) if (key !in used) used += key
        for (key in displayKeys) if (key !in used) used += key
        return used.toList()
    }

    /** 某个面上的类型列表（[ChannelKey] 版）。 */
    private fun typesPerFaceInternal(face: BlockFace): List<ChannelType> =
        orderedKeys()
            .filter { key ->
                val faces = channelFaces[key]
                faces == null || face in faces
            }
            .mapNotNull { ChannelTypes.find(it.id) }

    /** 某个类型在哪些面上存在（面板右侧画格子用）。 */
    fun facesOf(type: ChannelType): Set<BlockFace> {
        val key = ChannelKey.of(type)
        return channelFaces[key]?.toSet() ?: SIDED_FACES.toSet()
    }

    /** 某个面是不是被机器主动屏蔽了（不该出现在面板上）。 */
    fun isFaceHidden(face: BlockFace): Boolean = face in hiddenFaces

    /**
     * 这里有没有**至少一个**被玩家改过的格子。
     *
     * 注意是"格子"不是"面" —— 老实现按面判断，遇到"北面物品没改、
     * 北面能量改了"会误判成没改过，`reattach` 就会把玩家的能量配置丢掉。
     */
    val isCustomised: Boolean get() = modes.values.any { it.isNotEmpty() }

    /**
     * 某个面的**聚合方向** —— 把面上所有类型合并成一个方向。
     *
     * 给"只关心这个面能不能进/出"的地方用（WAILA、别的附属的快速判断）。
     * 合并规则是"取最宽的"（BOTH > INPUT/OUTPUT > NONE），
     * 理由和老的 [defaultFor] 一样：**默认不该让玩家的机器变哑巴**。
     */
    fun faceMode(face: BlockFace): SideMode {
        val types = typesOn(face)
        if (types.isEmpty()) return SideMode.NONE
        return types.maxByOrNull { rank(mode(face, it)) }!!.let { mode(face, it) }
    }

    /** 六个面各自的聚合方向（持久化 / WAILA / 测试用）。 */
    fun faceModes(): Map<BlockFace, SideMode> = SIDED_FACES.associateWith { faceMode(it) }

    /** 所有 `(面, 类型) → 方向` 的完整快照，按 [SIDED_FACES] + 注册顺序排。 */
    fun channelModes(): Map<BlockFace, Map<ChannelType, SideMode>> =
        SIDED_FACES.associateWith { face ->
            typesOn(face).associateWith { type -> mode(face, type) }
        }

    // ------------------------------------------------------------ 修改

    /** 设某个 `(面, 类型)` 的方向。 */
    fun setMode(face: BlockFace, type: ChannelType, mode: SideMode) {
        setMode(face, ChannelKey.of(type), mode)
    }

    internal fun setMode(face: BlockFace, key: ChannelKey, mode: SideMode) {
        modes.getOrPut(face) { HashMap() }[key] = mode
    }

    /**
     * 把某个 `(面, 类型)` 循环切到下一个方向。
     *
     * 顺序是 [SideMode.CYCLE_ORDER]：双向 → 输入 → 输出 → 空 → 双向。
     * **只在这个面真的有这个类型时才切** —— 面板不该画出不存在的格子，
     * 万一手滑传了个不存在的组合，这里静默忽略而不是造一格幽灵配置出来。
     */
    fun cycleMode(face: BlockFace, type: ChannelType): SideMode {
        val key = ChannelKey.of(type)
        if (!hasChannel(face, key)) return SideMode.NONE
        val next = mode(face, key).next()
        setMode(face, key, next)
        return next
    }

    /** 反向循环切换。 */
    fun cycleModeBackwards(face: BlockFace, type: ChannelType): SideMode {
        val key = ChannelKey.of(type)
        if (!hasChannel(face, key)) return SideMode.NONE
        val previous = mode(face, key).previous()
        setMode(face, key, previous)
        return previous
    }

    /** 这个 `(面, 类型)` 是不是一个真实存在的格子。 */
    fun hasChannel(face: BlockFace, type: ChannelType): Boolean =
        face !in hiddenFaces && typesOn(face).any { it == type }

    internal fun hasChannel(face: BlockFace, key: ChannelKey): Boolean {
        if (face in hiddenFaces) return false
        if (!slotGroups.containsKey(key) && key !in defaults.keys && key !in displayKeys) return false
        val faces = channelFaces[key]
        return faces == null || face in faces
    }

    /**
     * 这条通道是不是**只展示**的（[SideConfigBuilder.displayChannel]）。
     *
     * 面板据此决定画"可点的方向格"还是"只读的现状格"。
     */
    fun isDisplayOnly(type: ChannelType): Boolean = ChannelKey.of(type) in displayKeys

    /**
     * **管道判定核心**：这个面允不允许接 [type] 的管道。
     *
     * 判定表（[SidedIo.canConnect] 就是拿这个包一层）：
     *
     * | 情况 | 结果 |
     * |---|---|
     * | 面被机器屏蔽 | `false` |
     * | 类型是只展示的（能量） | `true`（它不归管道判定） |
     * | 这个面没有这条通道 | `false` |
     * | 方向是 [SideMode.NONE] | `false` ← **用户要的"空就不连"** |
     * | 其余 | `true` |
     *
     * 抽成独立方法是为了**能单测** —— `SidedIo.canConnect` 要 `Block`，
     * 在单测里造不出来，而这套规则必须被钉死。
     */
    fun allowsConnection(face: BlockFace, type: ChannelType): Boolean {
        if (face in hiddenFaces) return false
        if (isDisplayOnly(type)) return true
        if (!hasChannel(face, type)) return false
        return mode(face, type) != SideMode.NONE
    }

    /**
     * 从持久化数据恢复。
     *
     * 存盘格式：`<面名>.<类型 id>` → `<模式名>`。例：`NORTH.sidedio:item` → `INPUT`。
     *
     * ⚠️ **不认识的键一律忽略**（[SideMode.fromName] 和 [ChannelTypes.findOrUnknown]
     * 都做了容错）—— PDC 里的值可能来自老版本、被手改、或某个附属被拆了。
     * 让整台机器加载失败是不可接受的。
     */
    internal fun restore(saved: Map<String, String>) {
        for ((key, value) in saved) {
            val dot = key.indexOf('.')
            if (dot <= 0 || dot == key.length - 1) continue
            val faceName = key.substring(0, dot)
            val typeId = key.substring(dot + 1)
            val face = SIDED_FACES.firstOrNull { it.name.equals(faceName, ignoreCase = true) } ?: continue
            val channel = ChannelKey.of(typeId)
            setMode(face, channel, SideMode.fromName(value))
        }
    }

    /**
     * 读盘恢复，**同时兼容只有面、没有类型的老存档**。
     *
     * 老格式（0.1.x 早期）是 `NORTH` → `INPUT` —— 那时候一个面只有一个方向。
     * 新格式是 `NORTH.sidedio:item` → `INPUT`。
     *
     * 老键没有 `.`，[restore] 会跳过它们。这里补一步：遇到无点键，
     * 把方向**套用到这个面上所有已注册的类型**。理由是老存档的机器
     * 那时也不分类型，玩家表达的语义就是"这个面进/出"，
     * 平铺到所有类型是最忠实的迁移（而不是丢掉玩家的配置）。
     *
     * 如果新旧格式同时存在（升级过程中又改过），**新键优先** ——
     * 因为它是玩家最后表达的意思。
     */
    internal fun restoreLegacyAware(saved: Map<String, String>) {
        // 先做一条"新格式覆盖了哪些 (面, 类型)"的索引，老键只铺没被覆盖的格子
        val explicitlySet = HashMap<String, MutableSet<ChannelKey>>()
        for (key in saved.keys) {
            val dot = key.indexOf('.')
            if (dot <= 0) continue
            val faceName = key.substring(0, dot)
            explicitlySet.getOrPut(faceName.uppercase()) { HashSet() } += ChannelKey.of(key.substring(dot + 1))
        }

        val legacy = LinkedHashMap<BlockFace, SideMode>()
        for ((key, value) in saved) {
            if (key.indexOf('.') > 0) continue          // 新格式，交给 restore
            val face = SIDED_FACES.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: continue
            legacy[face] = SideMode.fromName(value)
        }

        // 新格式先落地
        restore(saved)

        // 老格式再把"没被新格式明确设过"的类型铺上去
        for ((face, mode) in legacy) {
            val covered = explicitlySet[face.name] ?: emptySet()
            for (type in typesOn(face)) {
                if (ChannelKey.of(type) in covered) continue
                setMode(face, ChannelKey.of(type), mode)
            }
        }
    }

    /** 内部：把 `(面, 类型 id)` 拍平成持久化用的字符串键。 */
    internal fun persistenceKey(face: BlockFace, key: ChannelKey): String =
        "${face.name}.${key.id}"

    /** 完整的持久化快照：`<面名>.<类型 id>` → `<模式名>`。 */
    internal fun persistenceSnapshot(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (face in SIDED_FACES) {
            for (type in typesOn(face)) {
                // 只展示的通道没有"方向"可存（能量就是这种），跳过
                if (!type.configurable) continue
                result[persistenceKey(face, ChannelKey.of(type))] = mode(face, type).id
            }
        }
        return result
    }

    // ------------------------------------------------------------ 槽位 / 搬运

    /** 某个面能不能抽出**这个类型**的东西。 */
    fun canExtract(face: BlockFace, type: ChannelType): Boolean = mode(face, type).allowsExtract

    /** 某个面能不能插入**这个类型**的东西。 */
    fun canInsert(face: BlockFace, type: ChannelType): Boolean = mode(face, type).allowsInsert

    /**
     * 某个 `(面, 类型)` 在当前方向下**能被管道碰到的槽位**。
     *
     * 方向是 [SideMode.NONE] 时返回空列表 —— 这正是"这格被关掉"的实现：
     * 管道还连着（组还在），但一个槽都摸不到。
     *
     * ## ⚠️ 方向要和**槽组的类型**交叉
     *
     * 不是"把该类型的全部槽都交出去"，而是**只交回这个面够得着的那几组**
     * （见 [SideMode.covers]）。少了这一步会出两种反向 bug：
     *
     * - 顶面设成 OUTPUT → 把**输入槽**也交出去 → 自动输出把没烧的矿石一起推走；
     * - 侧面设成 INPUT → 把**输出槽**也交出去 → 自动输入把东西塞进产出槽。
     *
     * 熔炉的输入槽和输出槽都是"物品"类型，所以这两组**必须**靠
     * `SlotGroup.slotType` 区分 —— 光看类型分不开。
     */
    fun slotsFor(face: BlockFace, type: ChannelType): List<LogisticSlot> =
        slotsFor(face, type) { true }

    /**
     * 某个面**能插进去**的槽 —— 进料组 + 双向组（且这个面够得着）。
     *
     * 和 [slotsFor] 的区别：双向面（`BOTH`）上 [slotsFor] 会把产出槽也交出来，
     * 而"往里塞东西"只该塞**进料侧** —— 否则自动输入会把矿石塞进熔炉的成品槽。
     */
    fun insertSlotsFor(face: BlockFace, type: ChannelType): List<LogisticSlot> =
        slotsFor(face, type) { it.slotType != LogisticGroupType.OUTPUT }

    /**
     * 某个面**能抽出来**的槽 —— 产出组 + 双向组（且这个面够得着）。
     *
     * 同理：双向面上"往外抽"只该抽**产出侧**，不能把进料槽里没烧的原料也抽走。
     */
    fun extractSlotsFor(face: BlockFace, type: ChannelType): List<LogisticSlot> =
        slotsFor(face, type) { it.slotType != LogisticGroupType.INPUT }

    /**
     * 槽位查询的**唯一实现**：先过"面够得着"（[SideMode.covers]），
     * 再过调用方自己那道 [accept]。
     *
     * 抽成一个私有重载而不是各写一遍，是为了让三条公开入口
     * （[slotsFor] / [insertSlotsFor] / [extractSlotsFor]）的
     * "面过滤"部分**逐字节一致** —— 分叉就会出现"某个入口忘了过滤"的漏网之鱼。
     */
    private fun slotsFor(
        face: BlockFace,
        type: ChannelType,
        accept: (SlotGroup) -> Boolean
    ): List<LogisticSlot> {
        val key = ChannelKey.of(type)
        if (face in hiddenFaces) return emptyList()
        if (!hasChannel(face, key)) return emptyList()
        val mode = mode(face, key)
        if (!mode.hasSlots) return emptyList()
        return slotGroups[key]
            ?.filter { mode.covers(it.slotType, it.name) && accept(it) }
            ?.flatMap { it.slots }
            ?: emptyList()
    }

    /** 某个面上**所有类型合并后**能碰到的槽位（给"不分类"的调用方兜底）。 */
    fun slotsFor(face: BlockFace): List<LogisticSlot> =
        typesOn(face).flatMap { slotsFor(face, it) }.distinct()

    // ------------------------------------------------------------ 组重建

    /**
     * 把 `(面, 类型) → 方向` 翻译成 Rebar 的物流组，逐个交给 [target]。
     *
     * ## 名字规则
     *
     * 每格一个组，组名 `<类型名>__<面名>_<组类型>`（例如 `item__north_input`）。
     *
     * ⚠️ 名字里带的是**组自己的类型**（[SlotGroup.slotType]），**不是面的方向**。
     * 这是刻意的：面的方向是玩家随时会改的东西，而组名必须是**确定性**的 ——
     * 一旦把方向编进名字，玩家每改一次方向就换一批组名，而 Rebar 不给删组的口子
     * （`logisticBlocks` 是 private 的 IdentityHashMap），于是老组永远留在那儿、
     * 被管道当成有效组读到，语义直接乱掉。
     *
     * 用组类型命名之后，"改面方向"**不换名字**，
     * [SidedIoRegistry.createGroupOnce] 会复用同名组，不留垃圾。
     *
     * ## 什么时候不建组
     *
     * - 面方向是 [SideMode.NONE]（玩家要"别连过来"）；
     * - 面方向**够不着**这组槽的类型（见 [SideMode.covers]）——
     *   顶面是 OUTPUT 时输入槽组就不该存在，否则管道能从顶面抽走进料。
     */
    fun rebuildGroups(target: (String, LogisticGroup) -> Unit) {
        activeGroups.clear()
        forEachActiveGroup { name, set, slotType ->
            val group = LogisticGroup(slotType, set.slots.toMutableList())
            set.filter?.let { group.withFilter(it) }
            activeGroups += name
            target(name, group)
        }
    }

    /**
     * 遍历"当前该建的组"。
     *
     * 抽出来是为了让 [rebuildGroups] 和 [activeGroupNames] 用**同一套判据** ——
     * 两边分叉就会出现"算出来的组名和实际建的不一致"，那是最难查的一类 bug。
     */
    private fun forEachActiveGroup(
        onlyFace: BlockFace? = null,
        consumer: (name: String, set: SlotGroup, slotType: LogisticGroupType) -> Unit
    ) {
        for (face in SIDED_FACES) {
            if (onlyFace != null && face != onlyFace) continue
            if (face in hiddenFaces) continue
            for (type in typesOn(face)) {
                val key = ChannelKey.of(type)
                val mode = mode(face, key)
                // 空方向不建组：Rebar 没有"空组"的概念，建个空槽组反而会让
                // 管道以为这里能连。玩家把面切成"空"就是想要"别连过来"。
                if (!mode.hasSlots) continue

                // ⚠️ 一个类型可以有**多组槽位**，每组各带各的过滤条件
                // （熔炉的"物品"就有输入/燃料/输出三组，只有燃料那组要过燃料滤）。
                // 所以这里是"每组一个组"，而不是"每类型一个组"。
                //
                // 序号只在**同 (面, 组名) 确实有多组**时才追加（`#1`、`#2`…），
                // 单组的机器名字保持干净。
                val counters = HashMap<String, Int>()
                for (set in slotGroups[key] ?: emptyList()) {
                    if (set.slots.isEmpty()) continue
                    // 面方向够不着这组槽 → 这组在这个面上不存在
                    if (!mode.covers(set.slotType, set.name)) continue
                    val suffix = set.name.ifEmpty { set.slotType.name.lowercase() }
                    val ordinal = counters.getOrDefault(suffix, 0)
                    counters[suffix] = ordinal + 1
                    consumer(groupNameFor(type, face, set.slotType, ordinal, set.name), set, set.slotType)
                }
            }
        }
    }

    /**
     * 组名由 `类型 + 面 + **组名** + 同组名序号` 决定。
     *
     * ⚠️ 带的是**组自己的名字/类型**，**不是面的方向**。
     *
     * 面方向是玩家随时会改的，一旦编进名字，每改一次方向就换一批组名，
     * 而 Rebar 不给删组的口子 → 老组永远残留、被管道当成有效组读到。
     * 用稳定的东西命名之后，"改面方向"**不换名字**，
     * [SidedIoRegistry.createGroupOnce] 会复用同名组，不留垃圾。
     *
     * 序号只在同 `(面, 组名)` 确实有多组时才追加后缀 ——
     * 单组的机器（绝大多数）名字保持干净，第三方按名字找组时不用关心序号。
     */
    fun groupNameFor(
        type: ChannelType,
        face: BlockFace,
        slotType: LogisticGroupType,
        ordinal: Int = 0,
        name: String = "",
    ): String {
        val suffix = name.ifEmpty { slotType.name.lowercase() }
        val base = "${type.id.namespace}__${type.id.key}__${face.name.lowercase()}_$suffix"
        return if (ordinal > 0) "$base#$ordinal" else base
    }

    /** 某个面当前生效的组名列表（给别的附属 / 调试用）。 */
    fun activeGroupNames(face: BlockFace): List<String> {
        val result = ArrayList<String>()
        forEachActiveGroup(onlyFace = face) { name, _, _ -> result += name }
        return result
    }

    /**
     * **整台机器**当前生效的组名（上一次 [rebuildGroups] 实际建出来的那些）。
     *
     * ⚠️ 存在的理由：Rebar **没有**删物流组的 API，所以"改面方向 / 改槽位"之后
     * 老组会残留在机器的组表里。读取方（例如管道）如果不加区分地遍历
     * `getLogisticGroups()`，就会把**已经作废的老组**也当成有效组 ——
     * 表现是"面明明设成了输入，管道却还在抽"。
     *
     * 所以读取方应该拿这份名单去过滤。空集合 = 还没建过组。
     */
    val activeGroupNames: Set<String> get() = activeGroups.toSet()

    // ------------------------------------------------------------ 内部类型

    /** 一组槽位 + 它自己的插入过滤条件 + 它的**方向类型** + 它的**稳定名字**。 */
    internal class SlotGroup(
        val slots: MutableList<LogisticSlot>,
        val filter: ((ItemStack) -> Boolean)? = null,
        /**
         * 这组槽**本身**是干什么的 —— 进料（[LogisticGroupType.INPUT]）、
         * 产出（[LogisticGroupType.OUTPUT]）、还是双向（[LogisticGroupType.BOTH]）。
         *
         * ## ⚠️ 它不是从"面的方向"推出来的
         *
         * 这是修复一个真实 bug 的关键：**面的方向**和**槽组的类型**是两件正交的事**，
         * 必须交叉（见 [SideMode.flowFor]）。
         *
         * 以前的实现是"面是什么方向，就把**所有**组都套成那个类型" ——
         * 于是熔炉侧面设成 INPUT 时，**输出槽组也变成 INPUT**（成品再也抽不走）；
         * 顶面设成 OUTPUT 时，**输入槽组也变成 OUTPUT**（矿石能被抽走）。
         *
         * 现在组类型由**注册通道时声明**，面的方向只决定"这个面碰不碰它"。
         */
        val slotType: LogisticGroupType = LogisticGroupType.BOTH,
        /**
         * 这组槽的**稳定名字**（`input` / `fuel` / `output` / …），由
         * `channel(..., name = "...")` 给。
         *
         * 存在的理由：**面状态可以按名字选组**。Iron Furnaces 的第五档
         * `Fuel Input/Eject` 就是"只碰 `fuel` 组（进）+ `output` 组（出）" ——
         * 光靠 [slotType] 分不开（熔炉的燃料组和输入组都是 `INPUT`）。
         *
         * 空字符串 = 匿名组，只有"不按组筛"的状态（内置四档）才碰得到。
         */
        val name: String = "",
    )

    /** 一个类型 + 它连带的若干组槽位。 */
    internal class ChannelDefinition(
        val key: ChannelKey,
        val slots: MutableList<LogisticSlot>,
        val faces: MutableSet<BlockFace>,
        val default: Map<BlockFace, SideMode>,
        val filter: ((ItemStack) -> Boolean)?,
        /** 这条通道的方向类型。见 [SlotGroup.slotType]。 */
        val slotType: LogisticGroupType = LogisticGroupType.BOTH,
        /** 这条通道的名字。见 [SlotGroup.name]。 */
        val name: String = "",
    )

    companion object {
        /** 方向"宽窄"排序，用于把面上多个类型聚合成一个方向。 */
        private fun rank(mode: SideMode): Int = when (mode.direction) {
            SideMode.Flow.NONE -> 0
            SideMode.Flow.IN, SideMode.Flow.OUT -> 1
            SideMode.Flow.BOTH -> 2
        }

        /**
         * 同一个类型的**多条通道**对同一个面各提了一个出厂方向 —— 合并成一条。
         *
         * | 甲 | 乙 | 合并 | 为什么 |
         * |---|---|---|---|
         * | 相同 | 相同 | 原样 | 没分歧 |
         * | [SideMode.NONE] | X | X | "我没意见"不该把别人的意见冲掉 |
         * | [SideMode.INPUT] | [SideMode.OUTPUT] | [SideMode.BOTH] | 两条通道都要用这个面 → 双向 |
         *
         * ⚠️ 第三条是**保守**的：宁可给"双向"（两条通道都能工作），也不要在
         * "到底听谁的"上猜错 —— 猜错的方向是"某个功能静默不工作"，很难查。
         *
         * 顺带说明：如果两条通道本来就**各管各的面**（像 [SideConfigBuilder.item]
         * 用 `filterValues` 分离过那样），它们根本不会走到这一条分支。
         */
        internal fun mergeDefault(a: SideMode, b: SideMode): SideMode = when {
            a == b -> a
            a == SideMode.NONE -> b
            b == SideMode.NONE -> a
            else -> SideMode.BOTH
        }
    }
}

/**
 * 一个 [ChannelType] 的**稳定身份** —— 用 `namespace:key` 字符串，而不是实例。
 *
 * ## 为什么不用 `ChannelType` 直接当 map 键
 *
 * 因为 [ChannelType] 的实例来自 `ChannelTypes` 注册表，而注册表**可能在
 * 一个方块加载时还没建好**（附属启用顺序、热重载）。用字符串当键，
 * [SideConfig] 就能在"类型还没解析出来"的状态下正常工作，
 * 之后再解析成实例去拿图标/颜色/翻译 —— 两边解耦。
 *
 * 这也让持久化变得直白：PDC 里存的就是这个字符串。
 */
internal class ChannelKey private constructor(val id: String) {

    /** 解析成注册表里的类型；查不到返回 null。 */
    val type: ChannelType? get() = ChannelTypes.find(id)

    /** 解析成类型，查不到走 [ChannelTypes.UNKNOWN] 兜底（渲染路径用）。 */
    val typeOrUnknown: ChannelType get() = ChannelTypes.findOrUnknown(id)

    override fun equals(other: Any?): Boolean = other is ChannelKey && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        fun of(type: ChannelType): ChannelKey =
            ChannelKey("${type.id.namespace}:${type.id.key}")

        /** 从持久化字符串还原。形如 `sidedio:item`。 */
        fun of(id: String): ChannelKey = ChannelKey(id)
    }
}

/**
 * `(面, 类型)` 逻辑键 ↔ **PDC 合法键** 的可逆编解码。
 *
 * ## 为什么需要它
 *
 * [SideConfig.persistenceKey] 是给人看的逻辑键：`NORTH.sidedio:item`。
 * 但 Bukkit 的 [org.bukkit.NamespacedKey] 只接受 `[a-z0-9_-./]`，
 * 于是这个逻辑键**直接当 PDC 键会抛**：
 *
 * ```
 * java.lang.IllegalArgumentException: Non [a-z0-9_-./] character in key
 *   'NORTH.sidedio:item' at index 0 ('N', bytes: [78])
 * ```
 *
 * 触发面很广：存盘、关服、区块卸载都会走 `onSerialize`，**每次抛一次**，
 * 结果就是玩家改的面配置**一格都存不下来**（而且日志被刷屏）。
 *
 * ## 编码规则（可逆、只用合法小写集）
 *
 * 逐个字符映射：
 * - `a`-`z`、`0`-`9`、`_`、`.` 原样保留；
 * - 其余（大写、`:`、以及**保留作转义前缀的 `-`**）编码成 `-` + 两位十六进制。
 *   反过来遇到 `-` 就吃两位十六进制还原。
 *
 * 例：`NORTH.sidedio:item` → `-4e-4f-52-54-48.sidedio-3aitem`
 *
 * 因为 `-` 一律被转义（`-` → `-2d`），解码无歧义。
 */
internal object PersistKeys {

    private const val ESC = '-'

    private fun isLegal(c: Char): Boolean =
        c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' 

    /** 逻辑键 → PDC 合法键。 */
    fun encode(logical: String): String {
        val sb = StringBuilder(logical.length + 8)
        for (c in logical) {
            when {
                isLegal(c) -> sb.append(c)
                else -> {
                    sb.append(ESC)
                    sb.append(c.code.toString(16).padStart(2, '0'))
                }
            }
        }
        return sb.toString()
    }

    /**
     * PDC 合法键 → 逻辑键。解码失败（半截转义 / 非法 hex）返回 null，
     * 由调用方当"不认识"忽略 —— 老存档、手改、其它插件写坏的值都不该让方块加载失败。
     */
    fun decode(encoded: String): String? {
        val sb = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == ESC) {
                if (i + 2 >= encoded.length) return null
                val hex = encoded.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16) ?: return null
                sb.append(code.toChar())
                i += 3
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/**
 * 通道配置构建器 —— [SidedIo.attach] 返回的就是它。
 *
 * 用法：
 * ```kotlin
 * SidedIo.attach(this)
 *     .channel(ChannelTypes.ITEM,  SideMode.INPUT,  slots = inputSlots)
 *     .channel(ChannelTypes.ITEM,  SideMode.OUTPUT, slots = outputSlots)
 *     .channel(ChannelTypes.ENERGY, SideMode.INPUT, slots = energySlots, default = SideMode.NONE)
 *     .apply()
 * ```
 *
 * ⚠️ 注意同一个 [ChannelType] 可以注册**多次**（物品的"输入"和"输出"是两条
 * 方向相反的通道）—— 这时它们共享同一个类型的图标和颜色，但槽位集合不同。
 * 组名会不会撞？不会：组名带的是**方向**，`item__north_input` 和
 * `item__north_output` 是不同名字。
 */
class SideConfigBuilder internal constructor(
    internal val block: Block,
    private val rebar: RebarBlock,
    private val logistic: LogisticRebarBlock
) {

    private val definitions = ArrayList<SideConfig.ChannelDefinition>()

    /**
     * 注册一条通道。
     *
     * @param type    流的是什么（[ChannelTypes.ITEM] / [ChannelTypes.ENERGY] / …）。
     * @param slots   这条通道的槽位。**同类型多次注册时，槽位会被合并** ——
     *                因为玩家看到的是"物品"一行，不是"物品的第2条通道"。
     * @param faces   这条通道接到哪些面。默认所有面 —— 玩家再用面板收窄。
     *                传空集合等于**这个类型不出现在面板上**（等价于没注册）。
     * @param default 出厂方向。不传就交给 [SideConfig.defaultFor] 推导。
     * @param filter  可选的物品过滤（只影响插入，例如燃料槽只收燃料）。
     * @param slotType 这组槽**本身**的方向类型（进料 / 产出 / 双向）。
     *
     * ## ⚠️ `slotType` 和 `default` 是两件不同的事
     *
     * - `default` 说的是"**面**出厂时朝哪个方向"（玩家之后会改）；
     * - `slotType` 说的是"**这组槽**是进料口还是产出口"（永远不会变）。
     *
     * 两者**交叉**之后才是"这个面能不能碰这组槽"（见 [SideMode.covers]）。
     * 一台熔炉就是最典型的例子：物品类型下注册**两条**通道 ——
     * 输入槽 `slotType = INPUT`、输出槽 `slotType = OUTPUT` ——
     * 于是"侧面能进料但不能抽产出、顶面能抽产出但不能进料"才表达得出来。
     *
     * 不传就当作 [LogisticGroupType.BOTH]（旧行为：这组槽两个方向都能用）。
     */
    fun channel(
        type: ChannelType,
        slots: Collection<LogisticSlot>,
        faces: Collection<BlockFace> = SIDED_FACES,
        default: Map<BlockFace, SideMode> = emptyMap(),
        filter: ((ItemStack) -> Boolean)? = null,
        slotType: LogisticGroupType = LogisticGroupType.BOTH,
        name: String = "",
    ): SideConfigBuilder {
        require(slots.isNotEmpty()) { "通道 ${type.id} 没有槽位 —— 空通道没有意义" }
        definitions += SideConfig.ChannelDefinition(
            key = ChannelKey.of(type),
            slots = slots.toMutableList(),
            faces = faces.toMutableSet(),
            default = default,
            filter = filter,
            slotType = slotType,
            name = name,
        )
        return this
    }

    /** 便捷重载：整个 [VirtualInventory] 当槽位。 */
    fun channel(
        type: ChannelType,
        inventory: VirtualInventory,
        faces: Collection<BlockFace> = SIDED_FACES,
        default: Map<BlockFace, SideMode> = emptyMap(),
        filter: ((ItemStack) -> Boolean)? = null,
        slotType: LogisticGroupType = LogisticGroupType.BOTH,
        name: String = "",
    ) = channel(type, virtualSlots(inventory), faces, default, filter, slotType, name)

    /** 便捷重载：只取 [VirtualInventory] 的某几格（例如只把工厂升级槽暴露出去）。 */
    fun channel(
        type: ChannelType,
        inventory: VirtualInventory,
        indices: IntArray,
        faces: Collection<BlockFace> = SIDED_FACES,
        default: Map<BlockFace, SideMode> = emptyMap(),
        filter: ((ItemStack) -> Boolean)? = null,
        slotType: LogisticGroupType = LogisticGroupType.BOTH,
        name: String = "",
    ) = channel(type, virtualSlots(inventory, *indices), faces, default, filter, slotType, name)

    /**
     * **单类型便捷写法**：给一个类型指定"输入用哪些槽、输出用哪些槽"。
     *
     * 覆盖熔炉 / 箱子这类最常见的形态 —— 一个类型两条方向相反的通道，
     * 出厂默认写在 `default` 里。
     *
     * ```kotlin
     * .item(inputSlots, outputSlots, default = mapOf(UP to OUTPUT, else INPUT))
     * ```
     *
     * ⚠️ 两条通道的 `default` 各自被**按方向过滤**过（输入通道只留"能插"的面、
     * 输出通道只留"能抽"的面）—— 这样它们在 [SideConfigBuilder.apply] 里
     * 合并时**不会互相覆盖**。少了这一步，后声明的那条会把前面的整张表盖掉，
     * 结果"顶面 = 输出"这条设定直接消失（用户实测报过）。
     */
    fun item(
        inputSlots: Collection<LogisticSlot>,
        outputSlots: Collection<LogisticSlot> = emptyList(),
        faces: Collection<BlockFace> = SIDED_FACES,
        default: Map<BlockFace, SideMode> = emptyMap(),
        filter: ((ItemStack) -> Boolean)? = null
    ): SideConfigBuilder = apply {
        if (inputSlots.isNotEmpty()) {
            channel(
                type = ChannelTypes.ITEM, slots = inputSlots, faces = faces,
                default = default.filterValues { it.allowsInsert },
                filter = filter,
                slotType = LogisticGroupType.INPUT,
                name = "input",
            )
        }
        if (outputSlots.isNotEmpty()) {
            channel(
                type = ChannelTypes.ITEM, slots = outputSlots, faces = faces,
                default = default.filterValues { it.allowsExtract },
                slotType = LogisticGroupType.OUTPUT,
                name = "output",
            )
        }
    }

    /**
     * 屏蔽一个面 —— 它**完全不出现在面板上**，也不参与任何搬运。
     *
     * 给"某个面在物理上不可能接管道"的机器用（例如贴地放置的机器底面）。
     * ⚠️ 普通机器**不该**用它：屏蔽是永久的、玩家改不回来，
     * 而"这个面暂时不用"应该用方向里的 [SideMode.NONE] 表达（玩家可逆）。
     */
    fun hideFace(face: BlockFace): SideConfigBuilder = apply { hiddenFaces += face }

    /**
     * 注册一条**只展示、不搬运**的通道。
     *
     * 给"走别的系统、但玩家想看到"的通道用 —— 典型就是**能量**：
     * 它走 Rebar 的电网络（[io.github.pylonmc.rebar.electricity.ElectricNetwork]），
     * 不经过 [LogisticGroup]，所以**没有槽位**、**不建物流组**、面板上也**点不动**。
     *
     * 面板会把它列在左侧类型列里；选中它时，右边的面格子显示的是
     * [EnergyPorts] 读出来的**实际端口情况**（哪个面有端口、连了几根线），
     * 而不是一个改了不生效的开关。
     *
     * ```kotlin
     * SidedIo.attach(this)
     *     .channel(ChannelTypes.ITEM, inputInv)
     *     .displayChannel(ChannelTypes.ENERGY)   // 熔炉的电口
     *     .apply()
     * ```
     *
     * @param faces 这条通道"名义上"接哪些面。默认所有面 —— 只影响面板是否画这格，
     *              实际有没有端口由 [EnergyPorts] 现读。
     */
    fun displayChannel(
        type: ChannelType,
        faces: Collection<BlockFace> = SIDED_FACES,
    ): SideConfigBuilder = apply {
        displayChannels += SideConfig.ChannelDefinition(
            key = ChannelKey.of(type),
            slots = mutableListOf(),
            faces = faces.toMutableSet(),
            default = emptyMap(),
            filter = null,
        )
    }

    private val hiddenFaces = linkedSetOf<BlockFace>()

    /** 只展示、不搬运的通道（见 [displayChannel]）。 */
    private val displayChannels = ArrayList<SideConfig.ChannelDefinition>()

    /**
     * 声明这台机器**支持哪些机器级设置**（红石 / 自动输入 / 自动输出）。
     *
     * 不声明就不出现在面板上 —— 不搬东西的机器不该有"自动输入"开关。
     *
     * ```kotlin
     * SidedIo.attach(this)
     *     .channel(...)
     *     .machineSettings(MachineSetting.REDSTONE, MachineSetting.AUTO_INPUT, MachineSetting.AUTO_OUTPUT)
     *     .apply()
     * ```
     */
    fun machineSettings(vararg settings: MachineSetting): SideConfigBuilder =
        apply { supportedSettings += settings }

    /** [machineSettings] 的集合重载。 */
    fun machineSettings(settings: Collection<MachineSetting>): SideConfigBuilder =
        apply { supportedSettings += settings }

    /**
     * 覆盖机器朝向（面板据此决定"前面"是哪个面）。
     *
     * 不调的话会自动从 [DirectionalRebarBlock.facing] 读；机器不是有向方块时兜底 NORTH。
     */
    fun facing(face: BlockFace): SideConfigBuilder = apply { facingOverride = face }

    private val supportedSettings = linkedSetOf<MachineSetting>()
    private var facingOverride: BlockFace? = null

    /**
     * 收尾：建好 [SideConfig]、恢复玩家历史配置、注册进 Rebar，返回可继续操作的实例。
     */
    fun apply(): SideConfig {
        // 同类型的多条通道各自成一个**槽位组**（见 [groupSlots]）
        val slotGroups = groupSlots(definitions)
        val channelFaces = LinkedHashMap<ChannelKey, MutableSet<BlockFace>>()
        val defaults = LinkedHashMap<ChannelKey, LinkedHashMap<BlockFace, SideMode>>()

        for (def in definitions) {
            channelFaces.getOrPut(def.key) { linkedSetOf() } += def.faces
            val faceDefaults = defaults.getOrPut(def.key) { LinkedHashMap() }
            for ((face, mode) in def.default) {
                // ⚠️ **合并**而不是覆盖。
                //
                // 同一个类型下可以有**多条**通道（熔炉的物品类型 = 输入 + 输出 + 燃料），
                // 它们各自都想给"出厂方向"提意见。以前的写法是 `faceDefaults[face] = mode` ——
                // 后声明的那条把前面整张表**盖掉**，于是熔炉的"顶面 = 输出"
                // 被最后注册的燃料通道的 `INPUT_DEFAULT`（顶面 = NONE）冲没了。
                //
                // 现在按面合并：两边都非 NONE 且不同（INPUT vs OUTPUT）→ 双向。
                val existing = faceDefaults[face]
                faceDefaults[face] =
                    if (existing == null) mode else SideConfig.mergeDefault(existing, mode)
            }
        }

        // 只展示的通道：**不进** slotGroups（所以不建组、不搬运），
        // 但要有 faces（面板画哪几格）和 key（左侧列出来）
        val displayKeys = linkedSetOf<ChannelKey>()
        for (def in displayChannels) {
            displayKeys += def.key
            channelFaces.getOrPut(def.key) { linkedSetOf() } += def.faces
        }

        val config = SideConfig(
            slotGroups = slotGroups,
            channelFaces = channelFaces,
            defaults = defaults,
            settings = MachineSettings(supportedSettings.toSet()),
            facing = facingOverride ?: readFacing(rebar),
            displayKeys = displayKeys,
        )
        for (face in hiddenFaces) config.hideFaceInternal(face)

        SidedIoRegistry.register(rebar, logistic, config)
        return config
    }

    /**
     * 从方块自己身上读朝向。
     *
     * ⚠️ `DirectionalRebarBlock.facing` 的 getter 在**没设过朝向时会抛异常**，
     * 所以先用 [DirectionalRebarBlock.setFacingIfAbsent] 兜底成 NORTH 再读，
     * 而不是直接 `facing`。
     */
    private fun readFacing(rebar: RebarBlock): BlockFace {
        val directional = rebar as? io.github.pylonmc.rebar.block.interfaces.DirectionalRebarBlock
            ?: return BlockFace.NORTH
        directional.setFacingIfAbsent(BlockFace.NORTH)
        return FaceRole.normalizeFacing(runCatching { directional.facing }.getOrNull())
    }

    companion object {
        /**
         * 把"开发者声明的通道"归组：**同类型下每个声明各成一组**。
         *
         * ⚠️ 刻意做成 `internal` 且**不碰 Bukkit**，就是为了让单测能直接测它 ——
         * 它是"燃料滤不被输入组冲掉"这条不变量的唯一落点，必须能被回归测试钉死。
         *
         * ## 为什么是"每组一个"而不是"合并成一份"
         *
         * 过滤条件是挂在 Rebar 的 [LogisticGroup] 上的，而一个类型下可能有
         * **过滤条件不同的**若干组槽位（熔炉的物品 = 输入（不过滤）
         * + 燃料（只收燃料）+ 输出）。合并成一份就必然有一方的过滤被覆盖，
         * 结果是"石头被管道当燃料塞进熔炉"。
         *
         * 面板上"物品"仍然只有一行 —— 行是按**类型**排的，和槽位分组无关。
         */
        internal fun groupSlots(
            definitions: List<SideConfig.ChannelDefinition>
        ): LinkedHashMap<ChannelKey, List<SideConfig.SlotGroup>> {
            val result = LinkedHashMap<ChannelKey, MutableList<SideConfig.SlotGroup>>()
            for (def in definitions) {
                result.getOrPut(def.key) { ArrayList() } +=
                    SideConfig.SlotGroup(def.slots.toMutableList(), def.filter, def.slotType, def.name)
            }
            return LinkedHashMap(result.mapValues { it.value.toList() })
        }
    }
}

/** 造槽位的便捷函数：把一个 [VirtualInventory] 整个包成槽位列表。 */
internal fun virtualSlots(inventory: VirtualInventory): MutableList<LogisticSlot> =
    MutableList(inventory.size) { VirtualInventoryLogisticSlot(inventory, it) }

/** 造槽位的便捷函数：只取 [VirtualInventory] 的某几格，越界索引会被丢掉。 */
internal fun virtualSlots(inventory: VirtualInventory, vararg indices: Int): MutableList<LogisticSlot> =
    indices.filter { it in 0 until inventory.size }
        .mapTo(ArrayList()) { VirtualInventoryLogisticSlot(inventory, it) }
