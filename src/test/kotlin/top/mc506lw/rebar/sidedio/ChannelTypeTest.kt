package top.mc506lw.rebar.sidedio

import org.bukkit.Material
import org.bukkit.block.BlockFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ChannelType] / [ChannelTypes] 注册表的行为测试。
 *
 * 这套东西的核心承诺是"**第三方能加类型，核心不用改**"，
 * 所以这里重点测"注册 → 反查 → 兜底"这条链路，而不只是四个内置类型存不存在。
 */
class ChannelTypeTest {

    @Test
    fun `core types are registered`() {
        assertEquals(ChannelTypes.ITEM, ChannelTypes.find("sidedio:item"))
        assertEquals(ChannelTypes.FLUID, ChannelTypes.find("sidedio:fluid"))
        assertEquals(ChannelTypes.ENERGY, ChannelTypes.find("sidedio:energy"))
        assertEquals(ChannelTypes.GAS, ChannelTypes.find("sidedio:gas"))
    }

    @Test
    fun `core types have distinct colors so the panel can tell them apart`() {
        val colors = listOf(ChannelTypes.ITEM, ChannelTypes.FLUID, ChannelTypes.ENERGY, ChannelTypes.GAS)
            .map { it.color }
        assertEquals(colors.size, colors.toSet().size, "核心类型必须配色互不相同，否则面板分不出来")
    }

    @Test
    fun `third party type can be registered and looked up`() {
        val force = ChannelTypes.register(
            SidedIoKeys.key("force_test"),
            icon = Material.END_ROD,
            color = 0xB06CFF,
        )
        assertEquals(force, ChannelTypes.find("sidedio:force_test"))
        assertEquals("sidedio.channel_type.sidedio.force_test.name", force.nameKey)
    }

    @Test
    fun `registering the same id twice is rejected instead of silently overwriting`() {
        ChannelTypes.register(SidedIoKeys.key("dupe_test"), Material.STONE, 0x123456)
        val second = runCatching {
            ChannelTypes.register(SidedIoKeys.key("dupe_test"), Material.DIRT, 0x654321)
        }
        assertTrue(second.isFailure, "同 id 二次注册必须失败，不能静默覆盖")
        // 而且第一个的值必须没被动过
        assertEquals(Material.STONE, ChannelTypes.find("sidedio:dupe_test")!!.icon)
    }

    @Test
    fun `unknown id falls back instead of throwing`() {
        // 读盘路径：附属被拆了 / 类型改名了 → 不该让方块加载失败
        val fallback = ChannelTypes.findOrUnknown("mymod:does_not_exist")
        assertEquals(ChannelTypes.UNKNOWN, fallback)
        // 而 find 要如实报"没有"
        assertEquals(null, ChannelTypes.find("mymod:does_not_exist"))
    }

    @Test
    fun `unknown fallback is not itself registered`() {
        // UNKNOWN 不能被 find 查到 —— 否则它会占掉一个真实的 id
        assertEquals(null, ChannelTypes.find("sidedio:unknown"))
        assertFalse(ChannelTypes.all().contains(ChannelTypes.UNKNOWN))
    }

    @Test
    fun `malformed id string does not throw`() {
        // PDC 里的值可能被手改成一坨乱码 —— findOrUnknown 得兜住
        assertEquals(ChannelTypes.UNKNOWN, ChannelTypes.findOrUnknown(""))
        assertEquals(ChannelTypes.UNKNOWN, ChannelTypes.findOrUnknown("no_colon_at_all"))
        assertEquals(ChannelTypes.UNKNOWN, ChannelTypes.findOrUnknown(":::"))
    }

    @Test
    fun `equality is by id not by instance`() {
        // 类型实例是单例，但相等性必须按 id 判 —— 否则读盘还原出来的
        // 实例和注册表里的实例对不上，一切去重都失效
        val a = ChannelTypes.find("sidedio:item")!!
        val b = ChannelTypes.find("sidedio:item")!!
        assertEquals(a, b)
        assertNotEquals(a, ChannelTypes.ENERGY)
    }
}
