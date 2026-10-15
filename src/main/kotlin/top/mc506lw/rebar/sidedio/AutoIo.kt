package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.logistics.slot.LogisticSlot
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * **自动输入 / 自动输出** —— 机器自己跟相邻容器搬东西，不靠管道。
 *
 * ## 为什么这是 Sided-IO 的活儿
 *
 * 因为搬东西需要的**信息全在 Sided-IO 手里**：哪个面是输入、哪个面是输出
 * （[SideConfig.canInsert] / [SideConfig.canExtract]），以及那个面**具体能碰哪些槽**
 * （[SideConfig.insertSlotsFor] / [SideConfig.extractSlotsFor]）。机器只需要在
 * 自己的 tick 里喊一声 [SidedIo.autoTick]，剩下的不用管。
 *
 * ⚠️ 注意"能碰哪些槽"是**按方向分开问的**，不是问一次 `slotsFor` 就够：
 * 一个双向面上，"往里塞"只该塞进料侧、"往外抽"只该抽产出侧 ——
 * 混着用会把矿石塞进成品槽、或者把没烧的原料一起抽走。
 *
 * 如果让每台机器自己实现，就会长出 N 份"找相邻容器 → 遍历槽 → 合并/填空位"
 * 的重复代码 —— 而且每份都可能漏掉"先合并再找空位"这种细节。
 *
 * ## 只做物品
 *
 * 现在只搬 [ChannelTypes.ITEM]。流体/能量有各自的网络（Rebar 的
 * `FluidRebarBlock` / `ElectricRebarBlock`），不该从这里走。
 *
 * ## 速率
 *
 * 每个面每 tick 最多搬 [DEFAULT_RATE] 个。不是"有多少搬多少" ——
 * 那样一个箱子会被瞬间抽空，而且服务端的 tick 会被拖长。
 */
object AutoIo {

    /** 每个面每 tick 最多搬多少个。 */
    const val DEFAULT_RATE = 8

    /**
     * 跑一次自动搬运。
     *
     * 机器在自己的 `tick()` 里调；两个开关都关着时**立刻返回**（零开销）。
     *
     * @param block  机器方块。
     * @param config 这台机器的面配置（含机器级设置）。
     * @param type   搬什么类型，默认物品。
     * @param rate   每个面每 tick 上限。
     */
    fun tick(
        block: Block,
        config: SideConfig,
        type: ChannelType = ChannelTypes.ITEM,
        rate: Int = DEFAULT_RATE
    ) {
        if (rate <= 0) return

        for (step in plan(config, type)) {
            val neighbor = neighborInventory(block, step.face) ?: continue

            // 自动输入 = 机器**收**：从邻居抽进机器的**进料侧**。
            if (step.takeIn) pull(neighbor, step.targets, rate)
            // 自动输出 = 机器**吐**：从机器的**产出侧**推进邻居。
            if (step.giveOut) push(step.sources, neighbor, rate)
        }
    }

    /**
     * 一个面上的搬运计划 —— **纯数据，不碰世界**。
     *
     * 拆出来是为了让"自动输出到底打哪个面"这件事**可以单测**：
     * 它依赖 `SideMode.covers` 那套交叉语义，错了不会崩、只会静默不搬东西，
     * 是最难查的一类 bug。
     */
    internal class FacePlan(
        val face: BlockFace,
        val takeIn: Boolean,
        val giveOut: Boolean,
        /** 自动输入往这些槽里塞（只含进料侧）。 */
        val targets: List<LogisticSlot>,
        /** 自动输出从这些槽里抽（只含产出侧）。 */
        val sources: List<LogisticSlot>,
    )

    /**
     * 算出这一 tick 每个面该做什么。
     *
     * ## ⚠️ 两个方向各查各的槽（这是防事故的关键）
     *
     * "往里塞"只该塞**进料侧**（[SideConfig.insertSlotsFor]）、
     * "往外抽"只该抽**产出侧**（[SideConfig.extractSlotsFor]）。
     * 用 `slotsFor` 一把抓会出两种反向事故：
     *
     * - 双向面上自动输入把矿石塞进熔炉的**成品槽**；
     * - 双向面上自动输出把**没烧的原料**一起抽走（用户实测报过）。
     *
     * ## 出厂默认下只有底面会输出
     *
     * 熔炉的出厂是"所有面进料、只有底面出成品"，所以 `自动输出` 打开后
     * **只会在底面生效**。想让别的面也出货，得先把那个面设成 `输出` 或 `双向`
     * （面板点一下 / 扳手右键 / `/sidedio info` 查当前值）。
     */
    internal fun plan(config: SideConfig, type: ChannelType): List<FacePlan> {
        val settings = config.settings
        val pullIn = settings.autoInput
        val pushOut = settings.autoOutput
        if (!pullIn && !pushOut) return emptyList()

        val result = ArrayList<FacePlan>()
        for (face in SIDED_FACES) {
            if (config.isFaceHidden(face)) continue
            if (!config.hasChannel(face, type)) continue

            val takeIn = pullIn && config.canInsert(face, type)
            val giveOut = pushOut && config.canExtract(face, type)
            if (!takeIn && !giveOut) continue

            val targets = if (takeIn) config.insertSlotsFor(face, type) else emptyList()
            val sources = if (giveOut) config.extractSlotsFor(face, type) else emptyList()
            // 说了要做、却一个槽都够不着 → 这个面这一 tick 没有意义，别白跑一趟
            if (targets.isEmpty() && sources.isEmpty()) continue

            result += FacePlan(face, takeIn, giveOut, targets, sources)
        }
        return result
    }

    // ------------------------------------------------------------ 相邻容器

    /**
     * 取某个面外侧的容器库存。
     *
     * ⚠️ **必须先判区块加载**：`Block.getState()` 在未加载的区块上会抛异常。
     * 相邻方块经常在没加载的区块里（机器贴着区块边界），不判会崩。
     */
    private fun neighborInventory(block: Block, face: BlockFace): Inventory? {
        val neighbor = block.getRelative(face)
        val world = block.world
        if (!world.isChunkLoaded(neighbor.x shr 4, neighbor.z shr 4)) return null
        val state = runCatching { neighbor.state }.getOrNull() as? Container ?: return null
        return state.inventory
    }

    // ------------------------------------------------------------ 搬运

    /** 从 [from] 抽最多 [max] 个进机器槽 [to]。 */
    private fun pull(from: Inventory, to: List<LogisticSlot>, max: Int) {
        var remaining = max
        for (index in 0 until from.size) {
            if (remaining <= 0) return
            val stack = from.getItem(index) ?: continue
            if (stack.isEmpty) continue

            val moved = insertIntoSlots(to, stack, remaining)
            if (moved > 0) {
                val left = stack.amount - moved
                from.setItem(index, if (left <= 0) null else stack.apply { amount = left })
                remaining -= moved
            }
        }
    }

    /** 把机器槽 [from] 里最多 [max] 个推进 [to]。 */
    private fun push(from: List<LogisticSlot>, to: Inventory, max: Int) {
        var remaining = max
        for (slot in from) {
            if (remaining <= 0) return
            val stack = slot.getItemStack() ?: continue
            if (stack.isEmpty) continue
            val available = slot.getAmount()
            if (available <= 0) continue

            val want = minOf(available, remaining.toLong()).toInt()
            if (want <= 0) continue

            val moved = insertIntoInventory(to, stack, want)
            if (moved > 0) {
                val left = available - moved
                slot.set(if (left <= 0) null else stack, maxOf(left, 0))
                remaining -= moved
            }
        }
    }

    /**
     * 往一组 [LogisticSlot] 里塞。
     *
     * 顺序：**先并到同类堆 → 再找空位**。反过来会把物品塞进空位而不堆叠，
     * 一个箱子很快被一格一个地占满。
     */
    private fun insertIntoSlots(slots: List<LogisticSlot>, stack: ItemStack, max: Int): Int {
        var left = minOf(max, stack.amount)
        var moved = 0

        for (slot in slots) {
            if (left <= 0) break
            val current = slot.getItemStack() ?: continue
            if (!current.isSimilar(stack)) continue
            val room = slot.getMaxAmount(stack) - slot.getAmount()
            if (room <= 0) continue
            val take = minOf(room, left.toLong()).toInt()
            if (take <= 0) continue
            val target = slot.getAmount() + take
            if (!slot.canSet(current, target)) continue
            slot.set(current, target)
            left -= take
            moved += take
        }

        for (slot in slots) {
            if (left <= 0) break
            if (slot.getItemStack() != null) continue
            val room = slot.getMaxAmount(stack)
            if (room <= 0) continue
            val take = minOf(room, left.toLong()).toInt()
            if (take <= 0) continue
            if (!slot.canSet(stack, take.toLong())) continue
            slot.set(stack, take.toLong())
            left -= take
            moved += take
        }
        return moved
    }

    /** 往一个普通 [Inventory] 里塞。规则同 [insertIntoSlots]：先并后填。 */
    private fun insertIntoInventory(inv: Inventory, stack: ItemStack, max: Int): Int {
        var left = minOf(max, stack.amount)
        var moved = 0

        for (index in 0 until inv.size) {
            if (left <= 0) break
            val current = inv.getItem(index) ?: continue
            if (current.isEmpty || !current.isSimilar(stack)) continue
            val room = current.maxStackSize - current.amount
            if (room <= 0) continue
            val take = minOf(room, left)
            current.amount += take
            inv.setItem(index, current)
            left -= take
            moved += take
        }

        for (index in 0 until inv.size) {
            if (left <= 0) break
            val current = inv.getItem(index)
            if (current != null && !current.isEmpty) continue
            val take = minOf(stack.maxStackSize, left)
            inv.setItem(index, stack.clone().apply { amount = take })
            left -= take
            moved += take
        }
        return moved
    }
}
