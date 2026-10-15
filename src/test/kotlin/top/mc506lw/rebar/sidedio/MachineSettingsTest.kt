package top.mc506lw.rebar.sidedio

import org.bukkit.block.BlockFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 机器级设置（红石 + 自动 I/O）与"面的角色"的契约。
 *
 * 这两块都是**纯逻辑**（不碰 Bukkit），所以能直接单测 ——
 * 尤其是循环顺序和"角色 ↔ 面"的互逆关系，写错了玩家会点错档位。
 */
class MachineSettingsTest {

    // ------------------------------------------------------------ 红石四态

    @Test
    fun `redstone cycle order is ignored low high pulse`() {
        assertEquals(
            listOf(RedstoneMode.IGNORED, RedstoneMode.LOW, RedstoneMode.HIGH, RedstoneMode.PULSE),
            RedstoneMode.CYCLE_ORDER
        )
    }

    @Test
    fun `redstone next and previous are inverses`() {
        for (mode in RedstoneMode.entries) {
            assertEquals(mode, mode.next().previous(), "$mode 的下一个的上一个应是自己")
            assertEquals(mode, mode.previous().next(), "$mode 的上一个的下一个应是自己")
        }
    }

    /** 循环要能绕回来：PULSE 的下一个是 IGNORED。 */
    @Test
    fun `redstone cycle wraps around`() {
        assertEquals(RedstoneMode.IGNORED, RedstoneMode.PULSE.next())
        assertEquals(RedstoneMode.PULSE, RedstoneMode.IGNORED.previous())
    }

    @Test
    fun `redstone fromName is forgiving`() {
        assertEquals(RedstoneMode.HIGH, RedstoneMode.fromName("high"))
        assertEquals(RedstoneMode.HIGH, RedstoneMode.fromName("HIGH"))
        assertEquals(RedstoneMode.DEFAULT, RedstoneMode.fromName("garbage"))
        assertEquals(RedstoneMode.DEFAULT, RedstoneMode.fromName(null))
    }

    /** 名字键和说明键必须**互不相同** —— 同键会让 YAML 把名字吞掉。 */
    @Test
    fun `redstone translation keys do not collide`() {
        for (mode in RedstoneMode.entries) {
            assertTrue(mode.translationKey != mode.descriptionKey, "$mode 的名字键和说明键撞了")
        }
        val all = RedstoneMode.entries.flatMap { listOf(it.translationKey, it.descriptionKey) }
        assertEquals(all.size, all.toSet().size, "有重复的翻译键")
    }

    // ------------------------------------------------------------ 机器级设置

    @Test
    fun `settings only expose what the machine declares`() {
        val settings = MachineSettings(setOf(MachineSetting.AUTO_INPUT))
        assertTrue(settings.supports(MachineSetting.AUTO_INPUT))
        assertFalse(settings.supports(MachineSetting.REDSTONE))
        assertEquals(listOf(MachineSetting.AUTO_INPUT), settings.visibleSettings())
    }

    @Test
    fun `visible settings keep a stable order`() {
        val settings = MachineSettings(MachineSetting.entries.toSet())
        assertEquals(MachineSetting.entries.toList(), settings.visibleSettings())
    }

    /** 快照只包含"声明支持"的项 —— 不支持的红石不该被写进 PDC。 */
    @Test
    fun `snapshot only contains supported settings`() {
        val settings = MachineSettings(setOf(MachineSetting.AUTO_INPUT, MachineSetting.AUTO_OUTPUT))
        settings.autoInput = true
        settings.redstoneMode = RedstoneMode.HIGH

        val snapshot = settings.snapshot()
        assertTrue(MachineSettings.KEY_AUTO_INPUT in snapshot)
        assertFalse(MachineSettings.KEY_REDSTONE in snapshot, "不支持红石却存了红石")
        assertEquals("true", snapshot[MachineSettings.KEY_AUTO_INPUT])
    }

    @Test
    fun `settings survive a snapshot restore round trip`() {
        val original = MachineSettings(MachineSetting.entries.toSet())
        original.redstoneMode = RedstoneMode.PULSE
        original.autoInput = true
        original.autoOutput = false

        val restored = MachineSettings(MachineSetting.entries.toSet())
        restored.restore(original.snapshot())

        assertEquals(RedstoneMode.PULSE, restored.redstoneMode)
        assertTrue(restored.autoInput)
        assertFalse(restored.autoOutput)
    }

    /** 垃圾值不能让机器加载失败。 */
    @Test
    fun `restore ignores garbage`() {
        val settings = MachineSettings(MachineSetting.entries.toSet())
        settings.restore(
            mapOf(
                MachineSettings.KEY_REDSTONE to "not_a_mode",
                MachineSettings.KEY_AUTO_INPUT to "maybe",
            )
        )
        assertEquals(RedstoneMode.DEFAULT, settings.redstoneMode)
        assertFalse(settings.autoInput, "非法布尔值应保持原样（默认 false）")
    }

    // ------------------------------------------------------------ 面的角色

    /** 机器朝北时六个面的角色。 */
    @Test
    fun `roles for a north facing machine`() {
        val facing = BlockFace.NORTH
        assertEquals(FaceRole.FRONT, FaceRole.of(BlockFace.NORTH, facing))
        assertEquals(FaceRole.BACK, FaceRole.of(BlockFace.SOUTH, facing))
        assertEquals(FaceRole.LEFT, FaceRole.of(BlockFace.WEST, facing))
        assertEquals(FaceRole.RIGHT, FaceRole.of(BlockFace.EAST, facing))
        assertEquals(FaceRole.TOP, FaceRole.of(BlockFace.UP, facing))
        assertEquals(FaceRole.BOTTOM, FaceRole.of(BlockFace.DOWN, facing))
    }

    /** 每个朝向都要能把六个面**一一映射**到六个角色。 */
    @Test
    fun `role mapping is a bijection for every facing`() {
        for (facing in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val roles = SIDED_FACES.map { FaceRole.of(it, facing) }
            assertEquals(FaceRole.entries.size, roles.toSet().size, "朝向 $facing 时有角色重复：$roles")
        }
    }

    /** [FaceRole.of] 和 [FaceRole.faceOf] 必须互逆 —— 否则面板点错面。 */
    @Test
    fun `of and faceOf are inverses`() {
        for (facing in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            for (role in FaceRole.entries) {
                val face = FaceRole.faceOf(role, facing)
                assertEquals(role, FaceRole.of(face, facing), "朝向 $facing 角色 $role 互逆失败")
            }
        }
    }

    /** 非法朝向（上下）要兜底成 NORTH，不能抛。 */
    @Test
    fun `normalizeFacing falls back to north`() {
        assertEquals(BlockFace.NORTH, FaceRole.normalizeFacing(BlockFace.UP))
        assertEquals(BlockFace.NORTH, FaceRole.normalizeFacing(BlockFace.DOWN))
        assertEquals(BlockFace.NORTH, FaceRole.normalizeFacing(null))
        assertEquals(BlockFace.WEST, FaceRole.normalizeFacing(BlockFace.WEST))
    }

    /** 左右必须是相反的两个面。 */
    @Test
    fun `left and right are opposite`() {
        for (facing in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            assertEquals(FaceRole.leftOf(facing).oppositeFace, FaceRole.rightOf(facing))
        }
    }
}
