package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.logistics.LogisticGroupType
import io.github.pylonmc.rebar.logistics.slot.LogisticSlot
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **自动输入 / 自动输出打到哪个面** —— 纯函数测试（不碰世界）。
 *
 * ## 为什么值得单独测
 *
 * [AutoIo] 的搬运失败**不会崩、也不会报错**，只会静默不动 ——
 * 而它依赖的是 `SideMode.covers` 那套"面方向 × 槽组类型"的交叉语义。
 * 那套语义一错，"自动输出没反应"这种事就只能靠玩家上报。
 *
 * 这里用**和铁熔炉一模一样**的形状钉住两条：
 *
 * 1. 出厂（全 6 面进料、只有底面出成品）下，**自动输出只打底面**；
 * 2. 自动输入在**每个面**都只往**进料侧**塞（不能碰产出槽）。
 */
class AutoIoPlanTest {

    // ------------------------------------------------------------ 形状

    /**
     * 铁熔炉形状的配置：一个类型（物品）下三组槽（输入 / 燃料 / 输出），
     * 出厂"所有面进料、只有底面出成品"。
     */
    private fun furnaceConfig(autoIn: Boolean, autoOut: Boolean): SideConfig {
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val sets = linkedMapOf(
            key to listOf(
                SideConfig.SlotGroup(mutableListOf(StubSlot()), null, LogisticGroupType.INPUT, "input"),
                SideConfig.SlotGroup(mutableListOf(StubSlot()), null, LogisticGroupType.OUTPUT, "output"),
                SideConfig.SlotGroup(mutableListOf(StubSlot()), null, LogisticGroupType.INPUT, "fuel"),
            )
        )
        val faces = linkedMapOf(key to SIDED_FACES.toMutableSet())
        val defaults = linkedMapOf(
            key to LinkedHashMap(
                SIDED_FACES.associateWith { SideMode.INPUT } + (BlockFace.DOWN to SideMode.BOTH)
            )
        )
        val settings = MachineSettings(setOf(MachineSetting.AUTO_INPUT, MachineSetting.AUTO_OUTPUT))
        settings.autoInput = autoIn
        settings.autoOutput = autoOut
        return SideConfig(sets, faces, defaults, settings)
    }

    private fun names(config: SideConfig): List<String> {
        val key = ChannelKey.of(ChannelTypes.ITEM)
        return config.slotGroups[key]!!.map { it.name }
    }

    // ------------------------------------------------------------ 输出

    /**
     * 🔴 出厂默认下，**自动输出只会在底面生效**。
     *
     * 这是"自动输出完全不起效"最容易踩的一种：玩家的箱子放在侧面，
     * 而侧面出厂是 `输入` —— `canExtract` 为 false，自动输出直接跳过它。
     * 想让侧面也出货，得先把那个面设成 `输出` / `双向`。
     */
    @Test
    fun `auto output targets only the bottom face by default`() {
        val config = furnaceConfig(autoIn = false, autoOut = true)
        val plan = AutoIo.plan(config, ChannelTypes.ITEM)

        assertEquals(1, plan.size, "出厂默认下只有底面能输出，实际: ${plan.map { it.face }}")
        val step = plan.single()
        assertEquals(BlockFace.DOWN, step.face)
        assertTrue(step.giveOut, "底面该出货")
        assertTrue(!step.takeIn, "没开自动输入，不该往里收")

        // 抽出来的**只有产出槽** —— 不是进料槽、也不是燃料槽
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val output = config.slotGroups[key]!!.first { it.name == "output" }.slots
        assertEquals(output, step.sources)
    }

    /** 把某个侧面设成 `输出` 之后，自动输出就打到那个面。 */
    @Test
    fun `auto output follows a face explicitly set to output`() {
        val config = furnaceConfig(autoIn = false, autoOut = true)
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.OUTPUT)

        val faces = AutoIo.plan(config, ChannelTypes.ITEM).map { it.face }
        assertTrue(BlockFace.NORTH in faces, "北面设成了输出，自动输出该打它，实际: $faces")
        assertTrue(BlockFace.DOWN in faces, "底面出厂就是双向，也该打")
    }

    /** 面设成 `空` 之后，自动输入输出都不碰它。 */
    @Test
    fun `a face turned off is skipped entirely`() {
        val config = furnaceConfig(autoIn = true, autoOut = true)
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.NONE)

        val faces = AutoIo.plan(config, ChannelTypes.ITEM).map { it.face }
        assertTrue(BlockFace.NORTH !in faces, "面设成空之后不该再参与搬运，实际: $faces")
    }

    /** 被机器屏蔽的面（例如熔炉的炉门）完全不参与自动搬运。 */
    @Test
    fun `a hidden face is skipped entirely`() {
        val config = furnaceConfig(autoIn = true, autoOut = true)
        config.hideFaceInternal(BlockFace.UP)

        val faces = AutoIo.plan(config, ChannelTypes.ITEM).map { it.face }
        assertTrue(BlockFace.UP !in faces, "屏蔽的面不该参与搬运，实际: $faces")
    }

    // ------------------------------------------------------------ 输入

    /**
     * 🔴 自动输入在**每个面**都只往**进料侧**塞。
     *
     * 双向面（底面）上不能把矿石塞进成品槽 —— 那会造出"看着能烧、其实永远不烧"
     * 的怪状态。
     */
    @Test
    fun `auto input never targets the output slots`() {
        val config = furnaceConfig(autoIn = true, autoOut = false)
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val groups = config.slotGroups[key]!!
        val input = groups.first { it.name == "input" }.slots
        val fuel = groups.first { it.name == "fuel" }.slots

        val plan = AutoIo.plan(config, ChannelTypes.ITEM)
        assertEquals(SIDED_FACES.size, plan.size, "每个面都该能进料")

        for (step in plan) {
            assertTrue(step.takeIn, "${step.face} 该能进料")
            assertEquals(
                input + fuel, step.targets,
                "${step.face} 的进料目标只该是输入槽 + 燃料槽（不能含产出槽）"
            )
            assertTrue(!step.giveOut, "没开自动输出，不该往外抽")
        }
    }

    /** 两个开关都关着 → 一个面都不规划（零开销）。 */
    @Test
    fun `nothing is planned when both switches are off`() {
        val config = furnaceConfig(autoIn = false, autoOut = false)
        assertEquals(emptyList(), AutoIo.plan(config, ChannelTypes.ITEM))
    }

    /** 只有声明支持自动开关的机器才读得到设置 —— 这里顺带钉住设置本身。 */
    @Test
    fun `settings are read from the config the machine shares`() {
        val config = furnaceConfig(autoIn = true, autoOut = true)
        assertTrue(config.settings.autoInput)
        assertTrue(config.settings.autoOutput)
        // 燃料 / 输入 / 输出三组都在
        assertEquals(listOf("input", "output", "fuel"), names(config))
    }

    // ------------------------------------------------------------ 替身

    /** 纯内存槽位，只为占位 / 身份比较。 */
    private class StubSlot : LogisticSlot {
        override fun getItemStack(): ItemStack? = null
        override fun getAmount(): Long = 0
        override fun getMaxAmount(stack: ItemStack): Long = 0
        override fun set(stack: ItemStack?, amount: Long) = Unit
    }
}
