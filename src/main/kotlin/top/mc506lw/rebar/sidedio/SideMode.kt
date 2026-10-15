package top.mc506lw.rebar.sidedio

import org.bukkit.block.BlockFace

/**
 * 一个格子的方向 —— "这一格被用来干什么"。
 *
 * 这是 Side Configuration（面配置）的核心，Thermal Expansion 在 2013 年定下的
 * 三态 `INPUT / OUTPUT / BOTH / NONE` 已经成了整个科技模组圈的事实标准
 * （EnderIO、Mekanism、Iron-Furnaces 都照抄）。Rebar 的
 * [io.github.pylonmc.rebar.logistics.LogisticGroupType] 只有三态
 * （INPUT / OUTPUT / BOTH），**少一个 NONE** —— 而 NONE 是玩家最常用的那个：
 * "这个口我不许任何东西进出"。
 *
 * 所以这里补上 NONE。
 *
 * ## 和 [ChannelType] 的分工
 *
 * ```
 * ChannelType（流的是什么）  ×  SideMode（往哪流）  =  一个格子
 * ```
 *
 * 这两件事**正交**。`SideMode` 只回答方向，`ChannelType` 只回答内容。
 *
 * ## 为什么不是 enum（这是"可注册面状态"的关键）
 *
 * 内置四档（[BOTH] / [INPUT] / [OUTPUT] / [NONE]）只是**方向**。
 * 但真实机器会长出**更细的状态** —— Iron Furnaces 的面板就有第五档
 * **`Fuel Input/Eject`**：燃料从这一面进、成品从这一面弹出。
 *
 * 这个状态**表达不了**成"一个方向"：
 *
 * - "燃料进" = 只碰**燃料组**、方向 IN；
 * - "成品出" = 只碰**产出组**、方向 OUT。
 *
 * 同一个面上两组槽的方向**不一样**。所以 [SideMode] 做成**可注册的普通类**，
 * 由一个**兜底方向** [direction] 加一份**按组名的规则** [groupRules] 描述：
 *
 * ```kotlin
 * // 内置四档：没有组规则，所有组都用兜底方向
 * SideMode.INPUT              // direction = IN,  groupRules = null
 *
 * // 自定义档：只碰名单里的组，各自一个方向
 * SideModes.register("fuel_input", IN, mapOf("fuel" to IN, "output" to OUT))
 * ```
 *
 * ## 向后兼容
 *
 * 内置四档保留成 companion 常量，`SideMode.INPUT` 这种写法**完全不变** ——
 * 第三方源码不用改。只有 `when (mode)` 的穷尽性没了（改成 `when (mode.direction)`）。
 *
 * ## 循环顺序
 *
 * [SideModes.cycleOrder] 固定为 **双向 → 输入 → 输出 → 空**，注册进来的自定义状态
 * 追加在后面。之所以让"双向"打头：它是多数容器的默认状态，玩家从默认出发时
 * 第一下点到的是"输入"（最常用的收窄），而不是"空"（一刀切断）。
 *
 * ## 为什么 NONE 不映射成"不建组"
 *
 * 老实现里 NONE 会保留一个空槽组，怕"关掉一个面把管道全断开"。新模型改掉了：
 * NONE **确实不建组**（见 [SideConfig.rebuildGroups]）—— 因为"类型"这一层已经
 * 承担了"这个面有没有这条通道"的信息，一个面的物品口关了、能量口还开着是两件
 * 独立的事，不会互相连累。
 */
class SideMode private constructor(
    /**
     * 稳定标识。内置四档是 `both` / `input` / `output` / `none`；
     * 注册进来的建议用 `<命名空间>:<名字>`（例如 `ironfurnaces:fuel_input`）。
     *
     * 会出现在 PDC 里，**一旦发布就不要改**。
     */
    val id: String,
    /**
     * **兜底方向** —— 没有 [groupRules] 时，这个面上所有组都用它。
     *
     * 有 [groupRules] 时它只用来回答"这个状态整体算进还是算出"
     * （[allowsInsert] / [allowsExtract]），不参与选组。
     */
    val direction: Flow,
    /**
     * **按组名的规则**：组名 → 这个面在该组上的方向。
     *
     * `null` = 不按组筛（内置四档就是这种，所有组一视同仁）。
     * 非 `null` = **只碰名单里有的组**，且每组用各自的方向 ——
     * 名单外的组一律碰不到。
     */
    val groupRules: Map<String, Flow>? = null,
    /**
     * 玩家点一下会不会切到这一档。
     *
     * `false` 表示"只展示 / 只由机器设置"，不进循环。
     */
    val inCycle: Boolean = true,
) {

    /** 一个组在这个面上的方向。 */
    enum class Flow { IN, OUT, BOTH, NONE }

    /**
     * 映射到 Rebar 的物流组语义。
     *
     * [NONE] 故意映射成 `BOTH` —— 不是笔误：Rebar 只认三种组类型，而"空"是通过
     * **不建组**表达，不是通过组类型。映射成 INPUT 或 OUTPUT 都反而会让另一个方向意外可用。
     */
    val toGroupType: io.github.pylonmc.rebar.logistics.LogisticGroupType
        get() = when (direction) {
            Flow.IN -> io.github.pylonmc.rebar.logistics.LogisticGroupType.INPUT
            Flow.OUT -> io.github.pylonmc.rebar.logistics.LogisticGroupType.OUTPUT
            Flow.BOTH, Flow.NONE -> io.github.pylonmc.rebar.logistics.LogisticGroupType.BOTH
        }

    /** 这一档下有没有槽位可碰。 */
    val hasSlots: Boolean get() = direction != Flow.NONE

    /**
     * 面板 / WAILA 用的**翻译键**。
     *
     * ## 键的归属跟着 [id] 的命名空间走
     *
     * | [id] | 翻译键 | 谁的 lang 文件负责 |
     * |---|---|---|
     * | `both` / `input` / … （内置，无命名空间） | `sidedio.mode.both` | **Sided-IO** |
     * | `ironfurnaces:fuel_input_eject` | `ironfurnaces.mode.fuel_input_eject` | **Iron-Furnaces** |
     *
     * 这样自定义档的文案落在**注册方自己的** lang 文件里 —— 否则
     * Sided-IO 得知道每个附属的面状态该显示什么，那是反向依赖。
     *
     * ⚠️ 命名空间里的 `-` 要换成 `_`：翻译键只认 `[a-z0-9_.-]`，
     * 而 `-` 在部分客户端资源路径里会被截断。`:` 直接拼更是非法字符
     * （会让面板显示裸 key `sidedio.mode.mymod:force`）。
     */
    val translationKey: String get() = run {
        val colon = id.indexOf(':')
        if (colon < 0) {
            "sidedio.mode.${id.lowercase()}"
        } else {
            val namespace = id.substring(0, colon).lowercase().replace('-', '_')
            val key = id.substring(colon + 1).lowercase().replace('-', '_')
            "$namespace.mode.$key"
        }
    }

    /** 管道能不能从这一格**抽走**东西（任何一组能抽就算）。 */
    val allowsExtract: Boolean
        get() = direction == Flow.OUT || direction == Flow.BOTH ||
            groupRules?.values?.any { it == Flow.OUT || it == Flow.BOTH } == true

    /** 管道能不能往这一格**插入**东西（任何一组能插就算）。 */
    val allowsInsert: Boolean
        get() = direction == Flow.IN || direction == Flow.BOTH ||
            groupRules?.values?.any { it == Flow.IN || it == Flow.BOTH } == true

    /**
     * **这一面在这一组上的方向** —— 整个交叉语义的唯一出口。
     *
     * ## 为什么需要它（这是一个真实 bug 的修复）
     *
     * 一个面有方向，一个**槽位组**也有自己的类型（Rebar 的 `LogisticGroupType`：
     * INPUT / OUTPUT / BOTH）。这两件事**正交**，必须交叉：
     *
     * | 面方向 | 够得着 | 够不着 | 为什么 |
     * |---|---|---|---|
     * | [INPUT] | INPUT / BOTH 组 | **OUTPUT 组** | "这个面只进" = 不该碰产出槽 |
     * | [OUTPUT] | OUTPUT / BOTH 组 | **INPUT 组** | "这个面只出" = 不该碰进料槽 |
     * | [BOTH] | 全部 | —— | 双向就是都碰 |
     * | [NONE] | 无 | 全部 | 空面 |
     *
     * ## 不交叉会怎样
     *
     * 熔炉的"输入槽"和"输出槽"是**两条方向相反的通道**（都是物品类型）。
     * 如果只按面的方向给**所有**组套同一个类型，就会：
     *
     * - 侧面设成 INPUT → 输出槽组也变 INPUT → **成品再也抽不走**；
     * - 顶面设成 OUTPUT → 输入槽组也变 OUTPUT → **矿石能被抽走**。
     *
     * 这正是用户实测报的"自动输出到了错误的箱子"那一类问题的根源。
     *
     * @param slotType 组自己的类型（Rebar 语义）
     * @param groupName 组的**稳定名字**（`channel(..., name = "fuel")` 给的那个）。
     *                  空字符串 = 匿名组，只有不按组筛的状态才碰得到它。
     */
    fun flowFor(
        slotType: io.github.pylonmc.rebar.logistics.LogisticGroupType,
        groupName: String
    ): Flow {
        groupRules?.let { rules -> return rules[groupName] ?: Flow.NONE }
        return when (direction) {
            Flow.BOTH -> Flow.BOTH
            Flow.IN -> if (slotType == io.github.pylonmc.rebar.logistics.LogisticGroupType.OUTPUT) Flow.NONE else Flow.IN
            Flow.OUT -> if (slotType == io.github.pylonmc.rebar.logistics.LogisticGroupType.INPUT) Flow.NONE else Flow.OUT
            Flow.NONE -> Flow.NONE
        }
    }

    /** [flowFor] 的布尔版：够不够得着。 */
    fun covers(
        slotType: io.github.pylonmc.rebar.logistics.LogisticGroupType,
        groupName: String
    ): Boolean = flowFor(slotType, groupName) != Flow.NONE

    /** 玩家左键点一下切到的下一个方向（按 [SideModes.cycleOrder]）。 */
    fun next(): SideMode = SideModes.next(this)

    /** 上一个方向（反着转，比如右键 / Shift 右键）。 */
    fun previous(): SideMode = SideModes.previous(this)

    override fun equals(other: Any?): Boolean = other is SideMode && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "SideMode($id)"

    companion object {

        /** 双向。普通箱子就是这样：能放能取。**循环的起点**。 */
        @JvmField
        val BOTH: SideMode = SideMode("both", Flow.BOTH)

        /** 这一格**吃**东西（进料口）。管道只会往里插，不会往外抽。 */
        @JvmField
        val INPUT: SideMode = SideMode("input", Flow.IN)

        /** 这一格**吐**东西（产出口）。管道只会往外抽，不会往里插。 */
        @JvmField
        val OUTPUT: SideMode = SideMode("output", Flow.OUT)

        /** 空。这一格不参与搬运 —— 管道搬不动，但机器本身照常工作。 */
        @JvmField
        val NONE: SideMode = SideMode("none", Flow.NONE)

        /** 内置四档（顺序 = 面板上的循环顺序）。 */
        internal val BUILTIN: List<SideMode> = listOf(BOTH, INPUT, OUTPUT, NONE)

        /**
         * 造一档自定义状态（[SideModes.register] 用）。
         *
         * 构造器是 private 的，只有这里能造 —— 保证所有实例都经过注册表，
         * 不会出现"一个没人认识的面状态"。
         */
        internal fun create(
            id: String,
            direction: Flow,
            groupRules: Map<String, Flow>?,
            inCycle: Boolean,
        ): SideMode = SideMode(id, direction, groupRules, inCycle)

        /**
         * 玩家点一下的循环顺序：**双向 → 输入 → 输出 → 空 →（自定义档…）**。
         *
         * 刻意不按声明顺序（依赖声明顺序是个脆弱的约定）。显式列出来，改顺序时不会意外。
         */
        val CYCLE_ORDER: List<SideMode> get() = SideModes.cycleOrder

        /** 没有显式配置时，一个格子的默认方向（对齐原版箱子的直觉）。 */
        val DEFAULT: SideMode = BOTH

        /**
         * 从持久化字符串还原。
         *
         * ⚠️ **不抛异常**：PDC 里的值可能来自老版本、被手改、或某个附属被卸载了。
         * 遇到不认识的值一律退回 [DEFAULT]，而不是让整台机器加载失败。
         */
        fun fromName(name: String?): SideMode = SideModes.find(name) ?: DEFAULT

        /** 从 Rebar 的组类型**反推**一个方向（读别人的机器时用）。 */
        fun fromGroupType(type: io.github.pylonmc.rebar.logistics.LogisticGroupType): SideMode =
            when (type) {
                io.github.pylonmc.rebar.logistics.LogisticGroupType.INPUT -> INPUT
                io.github.pylonmc.rebar.logistics.LogisticGroupType.OUTPUT -> OUTPUT
                io.github.pylonmc.rebar.logistics.LogisticGroupType.BOTH -> BOTH
            }
    }
}

/**
 * `SideMode` 的**全局注册表** —— 附属用它注册自己的面状态。
 *
 * ## 为什么要可注册
 *
 * 内置四档只表达"方向"。但真实机器会长出更细的状态：
 *
 * ```kotlin
 * // Iron Furnaces 的第五档：燃料进、成品弹出
 * SideModes.register(
 *     id = "ironfurnaces:fuel_input",
 *     direction = SideMode.Flow.IN,                    // 整体算"进"
 *     groupRules = mapOf("fuel" to Flow.IN, "output" to Flow.OUT),
 * )
 * ```
 *
 * 之后面板上就会多出一档，玩家点得到、PDC 存得住（存的是 [SideMode.id]）。
 *
 * ## 什么时候注册
 *
 * 附属在自己的 `onEnable` 里注册，**早于**任何机器被放下来 ——
 * 读盘还原时按 id 反查，查不到会退回 [SideMode.DEFAULT]（不会崩，但玩家的配置丢了）。
 *
 * ## 稳定性契约
 *
 * [SideMode.id] 会进 PDC，**一旦发布就不要改**。撞 id 会被拒绝（不覆盖）。
 */
object SideModes {

    private val registry = LinkedHashMap<String, SideMode>()

    /**
     * 小写 id → 状态。
     *
     * ⚠️ 存在的理由：**老存档存的是大写**（`"INPUT"` / `"OUTPUT"` —— enum 时代的
     * `SideMode.name`）。精确匹配会让那些值查不到 → 退回 `DEFAULT` → 玩家的面配置
     * 静默丢失。所以查表要**忽略大小写**。
     */
    private val byLowerId = HashMap<String, SideMode>()

    init {
        for (mode in SideMode.BUILTIN) put(mode)
    }

    private fun put(mode: SideMode) {
        registry[mode.id] = mode
        byLowerId[mode.id.lowercase()] = mode
    }

    /**
     * 注册一档自定义面状态。
     *
     * @param id         稳定标识，建议 `<命名空间>:<名字>`。**不要改**（会进 PDC）。
     * @param direction  兜底方向 —— 回答"这一档整体算进还是算出"。
     * @param groupRules 按组名的规则：组名 → 方向。**名单外的组一律碰不到**。
     *                   组名由 `channel(..., name = "...")` 给。
     * @param inCycle    要不要出现在玩家点击的循环里（默认要）。
     * @throws IllegalArgumentException id 已被占用（**不覆盖**：静默覆盖会让
     *         两个附属上同一个 id、其中一个的行为莫名其妙地变了）。
     */
    fun register(
        id: String,
        direction: SideMode.Flow,
        groupRules: Map<String, SideMode.Flow>? = null,
        inCycle: Boolean = true,
    ): SideMode {
        require(!registry.containsKey(id)) { "面状态 $id 已经被注册过了" }
        val mode = SideMode.create(id, direction, groupRules, inCycle)
        put(mode)
        return mode
    }

    /** 按 id 反查（**忽略大小写**，见 [byLowerId]）。查不到返回 null。 */
    fun find(id: String?): SideMode? {
        if (id == null) return null
        return registry[id] ?: byLowerId[id.lowercase()]
    }

    /** 已注册的全部状态（内置四档在前，自定义按注册顺序在后）。 */
    fun all(): List<SideMode> = registry.values.toList()

    /**
     * 玩家点击的循环顺序：内置四档 + 所有 [SideMode.inCycle] 的自定义档。
     *
     * ⚠️ 每次调用**现算**，因为附属可能在运行期才注册（`onEnable` 时序）。
     * 缓存下来会让"注册得晚一点"变成"面板上看不到"。
     */
    val cycleOrder: List<SideMode>
        get() = all().filter { it.inCycle }

    internal fun next(current: SideMode): SideMode {
        val order = cycleOrder
        val index = order.indexOf(current)
        if (index < 0) return order.firstOrNull() ?: current
        return order[(index + 1) % order.size]
    }

    internal fun previous(current: SideMode): SideMode {
        val order = cycleOrder
        val index = order.indexOf(current)
        if (index < 0) return order.lastOrNull() ?: current
        return order[(index - 1 + order.size) % order.size]
    }

    /**
     * 测试用：清到"只剩内置四档"。
     *
     * 不暴露给生产代码 —— 注册表在进程里是单例，运行时清空会让已经配好的
     * 自定义状态变成"查不到的 id"，等于自毁。
     */
    internal fun resetToBuiltin() {
        registry.clear()
        byLowerId.clear()
        for (mode in SideMode.BUILTIN) put(mode)
    }
}

/** 六个面的固定顺序，用来生成稳定、可测的遍历结果。 */
val SIDED_FACES: List<BlockFace> = listOf(
    BlockFace.NORTH,
    BlockFace.EAST,
    BlockFace.SOUTH,
    BlockFace.WEST,
    BlockFace.UP,
    BlockFace.DOWN,
)

/**
 * 面板里六个面的**视觉排列** —— 上下前后左右，贴近玩家转视角的顺序。
 *
 * 和 [SIDED_FACES]（遍历用的稳定顺序）分开：那边要的是确定性，
 * 这边要的是"玩家找'上面'时能一眼扫到"。
 */
val PANEL_FACES: List<BlockFace> = listOf(
    BlockFace.UP,
    BlockFace.DOWN,
    BlockFace.NORTH,
    BlockFace.SOUTH,
    BlockFace.EAST,
    BlockFace.WEST,
)
