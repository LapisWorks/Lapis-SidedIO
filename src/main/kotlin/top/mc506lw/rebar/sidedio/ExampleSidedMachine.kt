package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.LogisticRebarBlock
import io.github.pylonmc.rebar.block.interfaces.TickingRebarBlock
import io.github.pylonmc.rebar.logistics.LogisticGroupType
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.persistence.PersistentDataContainer
import xyz.xenondevs.invui.inventory.VirtualInventory

/**
 * **集成示例**：一台"照抄铁熔炉结构"的机器，用 Sided-IO 接入面配置。
 *
 * 它存在的意义不是给玩家用（没注册进任何物品），而是把
 * "**引入依赖 → 给插槽 → 立刻拥有完整的面 I/O 能力**" 这件事
 * 变成可编译、可读的代码 —— 这就是这个库想要的体验。
 *
 * ## 不用 Sided-IO，这台机器得自己写什么
 *
 * 1. 建 3 个 `LogisticGroup` 并逐个 `createLogisticGroup`；
 * 2. 给每个面调 `addCargoLogisticGroup(face, groupName)`；
 * 3. 监听 `PlayerInteractEvent` 做右键切面，并自己反推命中面；
 * 4. override `getWaila` 画提示；
 * 5. 在 `write(pdc)` / 读盘里序列化面配置；
 * 6. 每次面变了重算组（还要绕开 Rebar "组类型不可变、组名不可覆盖"的两个坑）。
 *
 * 六件事、跨五个 Rebar 子系统。用 Sided-IO 就是 [postInitialise] 里那几行。
 *
 * ## 这里演示的三个关键点
 *
 * | 点 | 在哪 |
 * |---|---|
 * | **`slotType` 与 `default` 是两件事** | 三条 `channel(...)` |
 * | **自动输入 / 自动输出** | [tick] |
 * | **注销**（否则登记表留死引用） | [onPostBlockBreak] |
 */
class ExampleSidedMachine :
    RebarBlock,
    LogisticRebarBlock,
    TickingRebarBlock,
    BlockBreakRebarBlockHandler {

    /** 进料区（对应铁熔炉的 `inputInv`）。 */
    private val inputInv = VirtualInventory(6)

    /** 燃料区（对应铁熔炉的 `fuelInv`）。 */
    private val fuelInv = VirtualInventory(1)

    /** 产出区（对应铁熔炉的 `outputInv`）。 */
    private val outputInv = VirtualInventory(6)

    constructor(block: Block, context: BlockCreateContext) : super(block, context)

    constructor(block: Block, pdp: PersistentDataContainer) : super(block, pdp)

    override fun postInitialise() {
        // ★ 全部的面配置能力，就这几行。
        SidedIo.attach(this)
            // ① 进料：**所有面**都能进料（对齐铁熔炉的"进料不分面"）。
            //
            // ⚠️ `slotType` 说的是"这组槽**本身**是进料口还是产出口"，
            // 和"面出厂朝哪个方向"（`default`）是**两件正交的事**。
            // 少了 `slotType` 就会退化成"面是什么方向就把所有组套成那个类型" ——
            // 于是侧面设成输入时**产出槽也变成输入**，成品再也抽不走。
            .channel(
                ChannelTypes.ITEM, inputInv,
                default = SIDED_FACES.associateWith { SideMode.INPUT },
                slotType = LogisticGroupType.INPUT,
                name = "input",
            )
            // ② 燃料：带过滤，只收燃料 —— 否则管道会把矿石、垃圾全塞进燃料槽。
            .channel(
                ChannelTypes.ITEM, fuelInv,
                default = SIDED_FACES.associateWith { SideMode.INPUT },
                filter = { it.type.isFuel },
                slotType = LogisticGroupType.INPUT,
                name = "fuel",
            )
            // ③ 产出：**只有底面**能抽成品。
            //
            // 三条通道的 `default` 会被**按面合并**（见 `SideConfig.mergeDefault`）：
            // 底面 = 输入 ∪ 输出 → 双向；其余面 = 输入。也就是
            // "**所有面都能进料，只有底面能出成品**"。
            .channel(
                ChannelTypes.ITEM, outputInv,
                default = SIDED_FACES.associateWith { SideMode.NONE } +
                    (BlockFace.DOWN to SideMode.OUTPUT),
                slotType = LogisticGroupType.OUTPUT,
                name = "output",
            )
            // 声明这台机器支持的**机器级**设置（和面无关的那部分）。
            // 不声明就不会出现在面板上。
            .machineSettings(
                MachineSetting.REDSTONE,
                MachineSetting.AUTO_INPUT,
                MachineSetting.AUTO_OUTPUT,
            )
            .apply()
    }

    /**
     * 自动输入 / 自动输出：机器自己跟相邻容器搬东西，不靠管道。
     *
     * 两个开关都关着时**零开销**（直接返回）。搬什么、从哪个面搬，
     * 全由面配置决定（见 [AutoIo]）。
     */
    override fun tick() {
        SidedIo.sides(this)?.let { SidedIo.autoTick(block, it) }
    }

    override fun onPostBlockBreak(context: BlockBreakContext) {
        // ⚠️ 记得注销 —— 否则登记表 / WAILA override 会留着死引用。
        SidedIoRegistry.unregister(this)
    }

    /** 目前所有格子的方向（给命令 / 调试 / 别的附属读）。 */
    fun currentSides(): Map<BlockFace, Map<ChannelType, SideMode>>? =
        SidedIo.sides(this)?.channelModes()
}
