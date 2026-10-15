package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.logistics.LogisticGroupType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.block.BlockFace

/**
 * [SideMode] 的纯逻辑测试 —— 不碰服务器，跑得快，是回归的第一道网。
 *
 * 这些行为看着琐碎，但每一条都对应一个真实的玩家可见语义：
 * 循环顺序错了 → 玩家右键要按三四下才切到想要的面；
 * NONE 的 extract/insert 判断错了 → "关掉的面"还在偷偷搬东西。
 */
class SideModeTest {

    @Test
    fun `cycle order is both - input - output - none`() {
        // 玩家人定的顺序：双向 → 输入 → 输出 → 空
        assertEquals(SideMode.INPUT, SideMode.BOTH.next())
        assertEquals(SideMode.OUTPUT, SideMode.INPUT.next())
        assertEquals(SideMode.NONE, SideMode.OUTPUT.next())
        assertEquals(SideMode.BOTH, SideMode.NONE.next())
    }

    @Test
    fun `cycle order lists every mode exactly once`() {
        assertEquals(SideModes.all().size, SideMode.CYCLE_ORDER.size)
        assertEquals(SideModes.all().toSet(), SideMode.CYCLE_ORDER.toSet())
    }

    @Test
    fun `backwards cycle is the exact reverse`() {
        for (mode in SideModes.all()) {
            assertEquals(mode, mode.next().previous())
            assertEquals(mode, mode.previous().next())
        }
    }

    @Test
    fun `only output and both allow extraction`() {
        assertFalse(SideMode.INPUT.allowsExtract)
        assertTrue(SideMode.OUTPUT.allowsExtract)
        assertTrue(SideMode.BOTH.allowsExtract)
        assertFalse(SideMode.NONE.allowsExtract)
    }

    @Test
    fun `only input and both allow insertion`() {
        assertTrue(SideMode.INPUT.allowsInsert)
        assertFalse(SideMode.OUTPUT.allowsInsert)
        assertTrue(SideMode.BOTH.allowsInsert)
        assertFalse(SideMode.NONE.allowsInsert)
    }

    @Test
    fun `none never allows either direction`() {
        assertFalse(SideMode.NONE.allowsExtract)
        assertFalse(SideMode.NONE.allowsInsert)
        assertFalse(SideMode.NONE.hasSlots)
        for (mode in SideModes.all().filter { it != SideMode.NONE }) {
            assertTrue(mode.hasSlots, "$mode 应该有槽位")
        }
    }

    @Test
    fun `fromName is tolerant of unknown values`() {
        assertEquals(SideMode.OUTPUT, SideMode.fromName("OUTPUT"))
        assertEquals(SideMode.OUTPUT, SideMode.fromName("output"))
        assertEquals(SideMode.INPUT, SideMode.fromName("Input"))
        // 老存档 / 手改 / 改过名的值不该让机器加载失败
        assertEquals(SideMode.BOTH, SideMode.fromName(null))
        assertEquals(SideMode.BOTH, SideMode.fromName("garbage"))
    }

    @Test
    fun `six faces are covered exactly once`() {
        assertEquals(6, SIDED_FACES.size)
        assertEquals(6, SIDED_FACES.toSet().size)
        assertTrue(SIDED_FACES.containsAll(
            listOf(
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
            )
        ))
    }

    // ------------------------------------------------------ 可注册的面状态

    /**
     * 🔴 **Iron Furnaces 的第五档：`Fuel Input/Eject`** ——
     * 燃料从这一面进、成品从这一面弹出。
     *
     * 这个状态**表达不成"一个方向"**：同一个面上 `fuel` 组是 IN、`output` 组是 OUT。
     * 所以它靠 [SideMode.groupRules]（按组名给方向）描述 —— 这也是
     * `SideMode` 必须从 enum 改成可注册类的唯一理由。
     */
    @Test
    fun `a registered mode can give different directions to different slot groups`() {
        SideModes.resetToBuiltin()
        try {
            val fuel = SideModes.register(
                id = "test:fuel_input_eject",
                direction = SideMode.Flow.BOTH,     // 整体既能进又能出
                groupRules = mapOf(
                    "fuel" to SideMode.Flow.IN,
                    "output" to SideMode.Flow.OUT,
                ),
            )

            // 燃料组：只进
            assertEquals(SideMode.Flow.IN, fuel.flowFor(LogisticGroupType.INPUT, "fuel"))
            assertTrue(fuel.covers(LogisticGroupType.INPUT, "fuel"))

            // 产出组：只出
            assertEquals(SideMode.Flow.OUT, fuel.flowFor(LogisticGroupType.OUTPUT, "output"))
            assertTrue(fuel.covers(LogisticGroupType.OUTPUT, "output"))

            // ⚠️ 名单外的组一律碰不到 —— 哪怕它的类型"看起来对得上"
            assertEquals(SideMode.Flow.NONE, fuel.flowFor(LogisticGroupType.INPUT, "input"))
            assertFalse(fuel.covers(LogisticGroupType.INPUT, "input"), "没在名单里的组不该碰")

            // 整体方向：两边都算
            assertTrue(fuel.allowsInsert, "有组能进 → 整体算能插")
            assertTrue(fuel.allowsExtract, "有组能出 → 整体算能抽")
        } finally {
            SideModes.resetToBuiltin()
        }
    }

    /** 注册进来的档要出现在玩家点击的循环里，且**不能挤掉内置四档的顺序**。 */
    @Test
    fun `a registered mode joins the cycle order after the builtins`() {
        SideModes.resetToBuiltin()
        try {
            val extra = SideModes.register("test:extra", SideMode.Flow.IN)
            assertTrue(SideMode.CYCLE_ORDER.contains(extra), "注册的档该进循环")
            assertEquals(
                listOf(SideMode.BOTH, SideMode.INPUT, SideMode.OUTPUT, SideMode.NONE),
                SideMode.CYCLE_ORDER.take(4),
                "内置四档必须排在最前面、顺序不变"
            )
        } finally {
            SideModes.resetToBuiltin()
        }
    }

    /** 撞 id 要被拒绝 —— 静默覆盖会让两个附属上同一个 id 后行为莫名其妙地变。 */
    @Test
    fun `registering the same id twice is rejected`() {
        SideModes.resetToBuiltin()
        try {
            SideModes.register("test:dup", SideMode.Flow.IN)
            assertFailsWith<IllegalArgumentException> {
                SideModes.register("test:dup", SideMode.Flow.OUT)
            }
        } finally {
            SideModes.resetToBuiltin()
        }
    }

    /**
     * 🔴 **回归**：`fromName` 必须认**老存档的大写值**。
     *
     * enum 时代 PDC 里存的是 `SideMode.name`（`"INPUT"` / `"OUTPUT"`）。
     * 改成按 id 查表之后如果只做精确匹配，那些值会查不到 → 退回 `DEFAULT`
     * → **玩家的面配置静默丢失**。
     */
    @Test
    fun `fromName accepts the legacy uppercase spelling`() {
        assertEquals(SideMode.OUTPUT, SideMode.fromName("OUTPUT"))
        assertEquals(SideMode.OUTPUT, SideMode.fromName("output"))
        assertEquals(SideMode.INPUT, SideMode.fromName("Input"))
    }
}
