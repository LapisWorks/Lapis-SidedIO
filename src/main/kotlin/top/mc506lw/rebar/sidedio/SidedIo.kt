package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.LogisticRebarBlock
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

/**
 * Sided-IO 的入口门面。
 *
 * 这个库存在的理由是：**在 Rebar 里做面配置，是个十行起步、还容易写错的活儿**。
 * 拆开看每一步 Rebar 都给了 API，但要拼起来得同时懂
 * [LogisticGroup] / [LogisticRebarBlock] / `CargoRebarBlock` / WAILA / PDC 持久化
 * 五套东西 —— 而结果对每个附属来说都是**一样的**。
 *
 * 所以这里把它压成一行：
 *
 * ```kotlin
 * class MyFurnace : RebarBlock(...), LogisticRebarBlock {
 *
 *     private val inputInv = VirtualInventory(6)
 *     private val fuelInv = VirtualInventory(1)
 *     private val outputInv = VirtualInventory(6)
 *
 *     override fun postInitialise() {
 *         SidedIo.attach(this)
 *             .group("input",  SideMode.INPUT,  inputInv)
 *             .group("fuel",   SideMode.INPUT,  fuelInv, filter = { it.type.isFuel })
 *             .group("output", SideMode.OUTPUT, outputInv)
 *             .face(BlockFace.UP, SideMode.OUTPUT)
 *             .apply()
 *     }
 * }
 * ```
 *
 * 这一行做完的事：建物流组 → 挂到面上 → 恢复玩家的历史配置 → 注册右键交互 →
 * 挂 WAILA 提示 → 存进 PDC。
 *
 * ## 这套设计是从哪抄的
 *
 * 抄的是 Thermal Expansion 在 2013 年定下的 **Side Configuration** 模式
 * （`INPUT / OUTPUT / BOTH / NONE` + 蓝橙配色），这套东西现在是整个科技模组圈的
 * 事实标准 —— Mekanism、EnderIO、Iron-Furnaces 全是这个。
 * 但 Rebar 生态里一直没有一把"一行接入"的现成工具，每台机器都得自己拼一遍。
 */
object SidedIo {

    /**
     * 给一台机器接上 Sided-IO。
     *
     * @param rebar 机器本体。**必须实现 [LogisticRebarBlock]** —— 否则 Rebar 没有
     *              可用的物流组容器，这个库就无从下手。传错会在调用时直接抛
     *              [IllegalArgumentException]，而不是等到运行时静默不工作。
     *
     * @return 构建器，链式加组后用 [SideConfigBuilder.apply] 收尾。
     */
    fun attach(rebar: RebarBlock): SideConfigBuilder {
        require(rebar is LogisticRebarBlock) {
            "Sided-IO 需要机器实现 LogisticRebarBlock（${rebar.javaClass.simpleName} 没实现）"
        }
        return SideConfigBuilder(rebar.block, rebar, rebar)
    }

    /** 查一个方块的面配置；不是 Sided-IO 管的东西返回 null。 */
    fun sides(block: Block): SideConfig? = SidedIoRegistry.get(block)

    /** 查一台机器的面配置。 */
    fun sides(rebar: RebarBlock): SideConfig? = SidedIoRegistry.get(rebar)

    /** 这个方块是不是被 Sided-IO 管着面配置。 */
    fun isManaged(block: Block): Boolean = sides(block) != null

    /**
     * 玩家手里这把是不是 RebarWrench 扳手。
     *
     * 机器自己的"右键开界面"逻辑**必须**靠它让路 —— 拿扳手右键是配面手势，
     * 不该同时弹出机器界面。没装 RebarWrench 时恒返回 false。
     */
    fun isWrench(stack: org.bukkit.inventory.ItemStack?): Boolean = WrenchBridge.isWrench(stack)

    /** RebarWrench 扳手是否对接成功（附属用它决定要不要打日志 / 提示玩家）。 */
    fun isWrenchAvailable(): Boolean = WrenchBridge.available

    /**
     * 把一台机器类登记到 RebarWrench 扳手上，让它能被"拿扳手右键"配面。
     *
     * ⚠️ **附属必须为每个机器类调一次** —— RebarWrench 是精确类查表，不走父类继承。
     * 没装 RebarWrench 时静默排队（见 [WrenchBridge.registerClass]）。
     *
     * ```kotlin
     * // 在附属的 onEnable 里
     * for (clazz in MyBlocks.allImplementationClasses()) SidedIo.registerWrenchTarget(clazz)
     * ```
     *
     * @return 本次是否**成功**登记（false 表示已排队等 RebarWrench 加载，或登记失败）
     */
    fun registerWrenchTarget(clazz: Class<*>): Boolean = WrenchBridge.registerClass(clazz)

    /**
     * 重新接一遍，**保留玩家已改过的面模式**。
     *
     * 给"槽位集合会变的机器"用 —— 典型场景是熔炉：装了工厂升级后输入/输出
     * 从 1 格变 6 格、装能量升级后燃料槽整个消失。
     * 这时组的槽位列表必须跟着变，但**玩家调过的那六个面不该被重置**。
     *
     * [attach] + 重配 `.group(...)` 会新建一个 [SideConfig]，
     * 玩家的历史配置就没了；这个方法会把旧配置里的面模式搬过去。
     *
     * 用法：
     * ```kotlin
     * SidedIo.reattach(this) {
     *     it.group("input",  SideMode.INPUT,  virtualSlots(inputInv, *activeInputIndices))
     *     it.group("output", SideMode.OUTPUT, virtualSlots(outputInv, *activeOutputIndices))
     * }
     * ```
     *
     * @param configure 在新的构建器上重挂组（不写 `.apply()`，这里会调）。
     * @return 重建后的 [SideConfig]。
     */
    fun reattach(
        rebar: RebarBlock,
        configure: (SideConfigBuilder) -> Unit
    ): SideConfig {
        val previous = SidedIoRegistry.get(rebar)
        val builder = attach(rebar)
        configure(builder)
        val config = builder.apply()
        // 把玩家改过的配置搬回来（apply() 里已经恢复了 PDC 的历史，这里补内存里那份）
        previous?.let { prev ->
            // ---------------------------------------------------------------
            // ① 机器级设置（红石 / 自动输入 / 自动输出）
            // ---------------------------------------------------------------
            //
            // ⚠️ 这一段**必须独立于** `isCustomised`，不能塞进下面的 if。
            // 玩家完全可能"一个面都没改过，只开了自动输入输出" —— 那时
            // `isCustomised` 是 false，但设置必须搬过来。
            //
            // 用户实测报的 bug：装上**工厂升级**之后（走的就是 reattach），
            // 自动输入 / 自动输出**全被重置成关闭**。
            // 原因就是这里以前只搬面格子，settings 由新 builder 重新构造，
            // 而运行时重建没有 PDC 可恢复 → 全落回默认值（false）。
            config.settings.redstoneMode = prev.settings.redstoneMode
            config.settings.autoInput = prev.settings.autoInput
            config.settings.autoOutput = prev.settings.autoOutput

            // ---------------------------------------------------------------
            // ② 面格子
            // ---------------------------------------------------------------
            if (prev.isCustomised) {
                // ⚠️ 逐个 **(面, 类型)** 搬，不是逐面搬 ——
                // 一台机器可以"北面物品没改过、北面能量改过"，
                // 按面搬会把能量的配置当成物品的默认值糊上去。
                for (face in SIDED_FACES) {
                    for (type in prev.typesOn(face)) {
                        val mode = prev.mode(face, type)
                        // ⚠️ 机器能力可能**变了**：装了工厂升级之后燃料槽消失，
                        // 「燃料输入/弹出」那一档就不再支持（见 `extraFaceModes`）。
                        // 把已经不受支持的档搬过来，玩家会看到一个
                        // "切不到、也说不清"的格子 —— 直接跳过，那格退回出厂默认。
                        if (!config.isModeSupported(mode)) continue
                        config.setMode(face, type, mode)
                    }
                }
                // 方向搬回来了，组得按新方向再算一遍
                SidedIoRegistry.refreshGroups(rebar.block)
            }
        }
        return config
    }

    /**
     * 注册一个**第三方通道类型**，让面板能画它。
     *
     * ```kotlin
     * // 某个做"原力"机器的附属
     * val FORCE = SidedIo.registerChannelType(
     *     NamespacedKey(myPlugin, "force"),
     *     icon = Material.END_ROD,
     *     color = 0xB06CFF,
     * )
     * // 之后机器就能：
     * //   .channel(FORCE, forceSlots)
     * ```
     *
     * ⚠️ **要在机器被放下来之前注册**（`onEnable` 里）——
     * 读盘还原时按 id 反查类型，查不到会走 [ChannelTypes.UNKNOWN] 兜底
     * （不会崩，但图标和名字会是问号）。
     *
     * @return 注册好的类型实例（拿去建通道用）。
     */
    fun registerChannelType(
        id: NamespacedKey,
        icon: Material,
        color: Int,
        maxPerFace: Int = Int.MAX_VALUE
    ): ChannelType = ChannelTypes.register(id, icon, color, maxPerFace)

    /** 已注册的通道类型（面板左侧的顺序就是它）。 */
    fun channelTypes(): List<ChannelType> = ChannelTypes.all()

    /**
     * 跑一次**自动输入 / 自动输出**。
     *
     * 机器在自己的 `tick()` 里调一行就行：
     *
     * ```kotlin
     * override fun tick() {
     *     sides?.let { SidedIo.autoTick(block, it) }
     *     // …机器自己的逻辑
     * }
     * ```
     *
     * 两个开关都关着时零开销（直接返回）。搬运逻辑见 [AutoIo]。
     *
     * @param rate 每个面每 tick 最多搬几个。
     */
    fun autoTick(block: Block, config: SideConfig, rate: Int = AutoIo.DEFAULT_RATE) =
        AutoIo.tick(block, config, ChannelTypes.ITEM, rate)

    /**
     * **管道判定入口**：这个方块在这一面**允不允许**接 [type] 类型的管道。
     *
     * ## 为什么要给管道查
     *
     * Sided-IO 的面配置应该是**权威**的 —— 玩家把某个面设成 [SideMode.NONE]
     * （"空"）就是"这个口我不许任何东西进出"。管道如果还画着接上去的模型，
     * 就出现了"看着连了其实没连"的错觉。
     *
     * 所以管道在决定**渲染**和**接线**之前都应该问一下这里。
     *
     * ## 判定规则
     *
     * | 情况 | 结果 | 为什么 |
     * |---|---|---|
     * | 方块**不归 Sided-IO 管** | `true` | **放行** —— 不改不认识的东西的行为 |
     * | 面被机器主动屏蔽 | `false` | 屏蔽就是"物理上不可能接" |
     * | 类型是**只展示**的（能量） | `true` | 能量走 Rebar 电网络，不归管道判定 |
     * | 这个面没有这条通道 | `false` | 机器在这个面根本没有这种接口 |
     * | 方向是 [SideMode.NONE] | `false` | **用户要的"空就不连"** |
     * | 其余（双向/输入/输出） | `true` | 只要有一个方向可用就连 |
     *
     * ⚠️ **默认放行**是刻意的：Sided-IO 是可选依赖，没装或者没管的方块
     * 必须保持原样，不能因为"查不到"就把玩家的管道全断了。
     *
     * ## ⚠️ 原版容器走的是**另一条路**（这里曾经漏掉）
     *
     * [sides] 查的是 [SidedIoRegistry]，而它内部是
     * `get(BlockStorage.get(block))` —— **原版方块的 `BlockStorage.get` 返回 null**，
     * 所以原版容器在这里**恒为 null → 恒放行**。
     *
     * 也就是说：以前玩家把**原版熔炉/箱子**的某个面设成"空"，**管道照样连** ——
     * 和"面配置对原版容器同时生效"这句话是矛盾的。现在补上：
     * 原版容器改走 [VanillaSideStore]（它的默认值来自
     * [VanillaSides.suggestedDefaults]，已按原版 `getSlotsForFace` 对齐）。
     */
    fun canConnect(block: Block, face: BlockFace, type: ChannelType): Boolean {
        sides(block)?.let { return it.allowsConnection(face, type) }

        // 原版容器只有物品通道（能量/流体那边由管道自己的判据兜着，这里不插手）
        if (type != ChannelTypes.ITEM) return true
        if (!VanillaSidesBridge.isManagedVanilla(block)) return true
        return VanillaSideStore.modeOf(block, face) != SideMode.NONE
    }

    /**
     * 这台机器**当前真正有效**的物流组名。
     *
     * ## 为什么读取方需要它
     *
     * Rebar **没有删物流组的 API**（`logisticBlocks` 是 private 的 IdentityHashMap），
     * 所以"改面方向 / 改槽位"之后，老组会一直残留在机器的组表里。
     * 谁不加区分地遍历 `getLogisticGroups()`，就会把**已经作废的老组**当成有效组 ——
     * 表现是"某个面明明设成了输入，管道却还在抽"。
     *
     * 所以正确的读法是：
     *
     * ```kotlin
     * val valid = SidedIo.activeGroupNames(block)     // null = 这个方块不归 Sided-IO 管
     * for ((name, group) in rebar.getLogisticGroups()) {
     *     if (valid != null && name !in valid) continue   // 跳过残留的老组
     *     ...
     * }
     * ```
     *
     * @return `null` = 这个方块不归 Sided-IO 管（调用方应退回"全部组都算"的旧行为）；
     *         空集合 = 归它管，但当前一个组都不该存在（所有面都是"空"）。
     */
    fun activeGroupNames(block: Block): Set<String>? = sides(block)?.activeGroupNames

    /**
     * **只算 [face] 这一个面**的有效组名。
     *
     * 给"按面取值"的读取方用 —— 管道从机器的**某一面**接过来，就只该看那一面的组：
     *
     * - 熔炉**侧面**（`INPUT`）只有进料组 → 管道抽不到成品；
     * - 熔炉**顶面**（`OUTPUT`）只有产出组 → 管道塞不进矿石。
     *
     * 用整机那份 [activeGroupNames] 就做不到这一点（它把所有面的组混在一起），
     * 于是"从侧面接的管道能把成品抽走"。
     *
     * @return `null` = 这个方块不归 Sided-IO 管（调用方应退回旧行为）；
     *         空集合 = 这一面当前没有任何有效组（方向是"空" / 够不着任何组）。
     */
    fun activeGroupNames(block: Block, face: BlockFace): Set<String>? =
        sides(block)?.activeGroupNames(face)?.toSet()
}
