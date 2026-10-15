package top.mc506lw.rebar.sidedio

import org.bukkit.block.BlockFace
import top.mc506lw.rebar.sidedio.vanilla.VanillaSides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VanillaSides] 的**纯函数**测试。
 *
 * ## 这里只测"推导出厂方向"这一步
 *
 * ⚠️ 注意这里**没有**测"某个面能碰哪些槽" —— 因为那件事**已经不存在了**。
 *
 * 曾经有过一版"面 → 槽号"的收窄（`admitsIndex`）：拿原版 `getSlotsForFace`
 * 把熔炉的**侧面限死在燃料槽**上。结果就是**侧面塞不进矿石**
 * （用户实测报的"侧面无法输入物品"）。
 *
 * 而 Iron Furnaces 那类模组的做法是"**进料不分面**"：它的抽象基类
 * `TileEntityInventory.canPlaceItemThroughFace(...)` **忽略 direction**，
 * 任何面都能塞进料，只有"抽取"才分面。
 *
 * 所以 [VanillaSides.slotsFor] 现在只按**方向**选槽，不再按面砍槽位。
 * `getSlotsForFace` 剩下的唯一用途就是下面的 [VanillaSides.crossFaceModes] ——
 * **推导出厂方向**（哪一面默认是进 / 出 / 双向）。
 */
class VanillaSidesTest {

    // ------------------------------------------------------------ crossFaceModes（方向推导）

    /**
     * 🔴 熔炉：`getSlotsForFace` 给出
     * `UP = {0}`（进料）/ `NORTH = {1}`（燃料）/ `DOWN = {2,1}`（成品 + 燃料）。
     *
     * Rebar 的组语义是 `input`(0)=INPUT / `fuel`(1)=INPUT / `output`(2)=OUTPUT，
     * 所以交叉结果应当是 **上=INPUT、侧=INPUT、下=BOTH**。
     *
     * 注意下面这一档值得留意：**下 = BOTH 而不是 OUTPUT** —— 原版确实允许从
     * 下面往燃料槽里放东西（`getSlotsForFace(DOWN)` 含槽 1），所以它是"双向"。
     * 这正是"直接问原版"比手抄一张"下=成品"的表更准的地方。
     */
    @Test
    fun `a furnace crosses into input on top and sides, both on the bottom`() {
        val perFace = mapOf(
            BlockFace.UP to setOf(0),
            BlockFace.NORTH to setOf(1),
            BlockFace.SOUTH to setOf(1),
            BlockFace.EAST to setOf(1),
            BlockFace.WEST to setOf(1),
            BlockFace.DOWN to setOf(2, 1),
        )
        val insertable = setOf(0, 1)   // input + fuel
        val extractable = setOf(2)     // output

        val modes = VanillaSides.crossFaceModes(perFace, insertable, extractable)

        assertEquals(SideMode.INPUT, modes[BlockFace.UP], "上面只能碰进料槽 → 进")
        assertEquals(SideMode.INPUT, modes[BlockFace.NORTH], "侧面只能碰燃料槽 → 进")
        assertEquals(SideMode.INPUT, modes[BlockFace.EAST], "侧面只能碰燃料槽 → 进")
        assertEquals(SideMode.BOTH, modes[BlockFace.DOWN], "下面同时能碰成品与燃料 → 双向")
    }

    /** 箱子这类纯 BOTH：每一面都能碰全部槽，且全部槽都是双向 → 全 BOTH。 */
    @Test
    fun `a plain chest comes out as both on every face`() {
        val all = (0 until 27).toSet()
        val perFace = SIDED_FACES.associateWith { all }
        val modes = VanillaSides.crossFaceModes(perFace, all, all)

        for (face in SIDED_FACES) {
            assertEquals(SideMode.BOTH, modes[face], "$face 应当双向")
        }
    }

    /** 只出（纯 OUTPUT 机器）：每一面都只能抽 → OUTPUT。 */
    @Test
    fun `an output only block comes out as output`() {
        val perFace = SIDED_FACES.associateWith { setOf(0) }
        val modes = VanillaSides.crossFaceModes(perFace, emptySet(), setOf(0))

        for (face in SIDED_FACES) {
            assertEquals(SideMode.OUTPUT, modes[face], "$face 应当只能抽")
        }
    }

    /**
     * 🔴 **死面绝不给 NONE**。
     *
     * 一个可搬运的槽都碰不到的面（原版里确实存在，比如某些机器的"背面"），
     * 语义是"搬了没意义"，**不是**"物理上接不上"。
     * 返回 NONE 会让管道直接把玩家的管子断掉 —— 那是破坏性的。
     */
    @Test
    fun `a dead face falls back to both instead of none`() {
        val perFace = mapOf(
            BlockFace.NORTH to emptySet(),        // 一个槽都碰不到
            BlockFace.SOUTH to setOf(7),          // 碰到的槽既不可插也不可抽
        )
        val modes = VanillaSides.crossFaceModes(perFace, setOf(0), setOf(1))

        assertEquals(SideMode.BOTH, modes[BlockFace.NORTH], "空面必须退回 BOTH，不能是 NONE")
        assertEquals(SideMode.BOTH, modes[BlockFace.SOUTH], "无关槽必须退回 BOTH，不能是 NONE")
    }

    /** 全量扫描：交叉结果**永远不产生 NONE**（NONE 只能来自玩家的显式配置）。 */
    @Test
    fun `crossing never invents a none`() {
        val candidates = listOf(emptySet(), setOf(0), setOf(1), setOf(0, 1), setOf(9))
        for (slots in candidates) {
            val modes = VanillaSides.crossFaceModes(
                mapOf(BlockFace.UP to slots), setOf(0), setOf(1)
            )
            assertFalse(
                modes.values.any { it == SideMode.NONE },
                "面 $slots 推出了 NONE —— NONE 只该由玩家显式设置"
            )
        }
    }
}
