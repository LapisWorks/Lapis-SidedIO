package top.mc506lw.rebar.sidedio

import org.bukkit.block.BlockFace
import top.mc506lw.rebar.sidedio.gui.PanelCell
import top.mc506lw.rebar.sidedio.gui.SideConfigGui
import top.mc506lw.rebar.sidedio.gui.SideConfigLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面板布局的回归测试。
 *
 * 布局是**手写槽号**的，最容易犯的错就是两个东西撞同一格 ——
 * 玩家点"上"改的却是"下"。这个文件把骨架钉死。
 */
class SideConfigLayoutTest {

    @Test
    fun `layout is internally consistent`() {
        val problems = SideConfigLayout.validate()
        assertTrue(problems.isEmpty(), "布局自检失败：$problems")
    }

    /** 六个角色必须各占一格，且互不重叠。 */
    @Test
    fun `every role has a distinct slot`() {
        assertEquals(FaceRole.entries.size, SideConfigLayout.ROLE_SLOTS.size, "角色数不对")
        val slots = SideConfigLayout.ROLE_SLOTS.values
        assertEquals(slots.size, slots.toSet().size, "有格子被两个角色共用")
    }

    /**
     * **核心不变量**：前面永远在正中，背面在右下角。
     *
     * 这是参考模组的排布，也是玩家肌肉记忆的来源 —— 谁改了这里，
     * 玩家"中间那个是前面"的直觉就废了。
     */
    @Test
    fun `front is dead centre and back is bottom right`() {
        val front = SideConfigLayout.ROLE_SLOTS.getValue(FaceRole.FRONT)
        val back = SideConfigLayout.ROLE_SLOTS.getValue(FaceRole.BACK)

        // 正中：第 2 行（0 起）第 4 列
        assertEquals(2, front / SideConfigLayout.PANEL_WIDTH, "前面不在第 2 行")
        assertEquals(4, front % SideConfigLayout.PANEL_WIDTH, "前面不在第 4 列")

        // 右下角：第 3 行第 5 列
        assertEquals(3, back / SideConfigLayout.PANEL_WIDTH, "背面不在第 3 行")
        assertEquals(5, back % SideConfigLayout.PANEL_WIDTH, "背面不在第 5 列")
    }

    /** 面格子是按**角色**固定的 —— 换朝向只换"里面装哪个面"，不换格子位置。 */
    @Test
    fun `grid positions stay put when facing changes`() {
        for (facing in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            for ((role, slot) in SideConfigLayout.ROLE_SLOTS) {
                val face = SideConfigLayout.faceAtSlot(slot, facing)
                assertNotNull(face, "朝向 $facing 时角色 $role 的格子 $slot 没有对应面")
                assertEquals(role, FaceRole.of(face, facing), "朝向 $facing 时格子 $slot 的角色对不上")
            }
        }
    }

    /** 机器朝西时，"正中"应该是西面，"右下角"是东面。 */
    @Test
    fun `facing rotates which face sits in the centre`() {
        val frontSlot = SideConfigLayout.ROLE_SLOTS.getValue(FaceRole.FRONT)
        val backSlot = SideConfigLayout.ROLE_SLOTS.getValue(FaceRole.BACK)

        assertEquals(BlockFace.WEST, SideConfigLayout.faceAtSlot(frontSlot, BlockFace.WEST))
        assertEquals(BlockFace.EAST, SideConfigLayout.faceAtSlot(backSlot, BlockFace.WEST))

        assertEquals(BlockFace.NORTH, SideConfigLayout.faceAtSlot(frontSlot, BlockFace.NORTH))
        assertEquals(BlockFace.SOUTH, SideConfigLayout.faceAtSlot(backSlot, BlockFace.NORTH))
    }

    /** 红石四档各占一格，顺序对齐 CYCLE_ORDER。 */
    @Test
    fun `redstone modes get four distinct slots in cycle order`() {
        val slots = RedstoneMode.CYCLE_ORDER.map { SideConfigLayout.REDSTONE_SLOTS.getValue(it) }
        assertEquals(RedstoneMode.entries.size, slots.size)
        assertEquals(slots.size, slots.toSet().size, "红石格子有重叠")
        // 行 4（最后一行）
        for (slot in slots) {
            assertEquals(4, slot / SideConfigLayout.PANEL_WIDTH, "红石不在最后一行：$slot")
        }
    }

    /** 自动开关占第 0 行，且不和类型列撞。 */
    @Test
    fun `auto toggles sit on the first row and avoid the type column`() {
        for (slot in listOf(SideConfigLayout.AUTO_INPUT_SLOT, SideConfigLayout.AUTO_OUTPUT_SLOT)) {
            assertEquals(0, slot / SideConfigLayout.PANEL_WIDTH, "自动开关不在第 0 行：$slot")
            assertTrue(slot % SideConfigLayout.PANEL_WIDTH != SideConfigLayout.TYPE_COLUMN, "自动开关压到类型列了")
        }
        assertEquals(
            MachineSetting.AUTO_INPUT,
            SideConfigLayout.autoToggleAtSlot(SideConfigLayout.AUTO_INPUT_SLOT)
        )
        assertEquals(
            MachineSetting.AUTO_OUTPUT,
            SideConfigLayout.autoToggleAtSlot(SideConfigLayout.AUTO_OUTPUT_SLOT)
        )
        assertNull(SideConfigLayout.autoToggleAtSlot(0))
    }

    /** `cells()` 按机器能力产出格子：不支持红石的机器不该出现红石格。 */
    @Test
    fun `cells only include the settings the machine declares`() {
        val withoutRedstone = SideConfigLayout.cells(
            types = emptyList(),
            facing = BlockFace.NORTH,
            hiddenFaces = emptySet(),
            settings = setOf(MachineSetting.AUTO_INPUT),
        )
        assertTrue(withoutRedstone.none { it is PanelCell.RedstoneOption }, "不该有红石格")
        assertEquals(1, withoutRedstone.count { it is PanelCell.AutoToggle }, "应只有自动输入一个开关")

        val full = SideConfigLayout.cells(
            types = emptyList(),
            facing = BlockFace.NORTH,
            hiddenFaces = emptySet(),
            settings = MachineSetting.entries.toSet(),
        )
        assertEquals(2, full.count { it is PanelCell.AutoToggle })
        assertEquals(RedstoneMode.entries.size, full.count { it is PanelCell.RedstoneOption })
    }

    /** 屏蔽掉的面不该出现。 */
    @Test
    fun `hidden faces are not drawn`() {
        val cells = SideConfigLayout.cells(
            types = emptyList(),
            facing = BlockFace.NORTH,
            hiddenFaces = setOf(BlockFace.UP, BlockFace.DOWN),
            settings = emptySet(),
        )
        val faces = cells.filterIsInstance<PanelCell.FaceButton>().map { it.face }
        assertTrue(BlockFace.UP !in faces && BlockFace.DOWN !in faces, "屏蔽的面还是画出来了")
        assertEquals(4, faces.size, "应剩四个侧面")
    }

    /**
     * **返回按钮**：机器给了 `back` 才画，且不与任何区域撞格。
     *
     * 面板是顶掉机器窗口打开的，没有这个入口玩家就困在面板里
     * （用户实测报的"页面上没有返回按钮"）。
     */
    @Test
    fun `a back button appears only when the machine provides one`() {
        val without = SideConfigLayout.cells(
            types = emptyList(),
            facing = BlockFace.NORTH,
            hiddenFaces = emptySet(),
            settings = emptySet(),
            hasBack = false,
        )
        assertTrue(without.none { it is PanelCell.Back }, "没给 back 回调不该画返回按钮")

        val with = SideConfigLayout.cells(
            types = emptyList(),
            facing = BlockFace.NORTH,
            hiddenFaces = emptySet(),
            settings = MachineSetting.entries.toSet(),
            hasBack = true,
        )
        val backs = with.filterIsInstance<PanelCell.Back>()
        assertEquals(1, backs.size, "应恰好一个返回按钮")
        assertEquals(SideConfigLayout.BACK_SLOT, backs.single().slot)
        assertTrue(
            with.count { it.slot == SideConfigLayout.BACK_SLOT } == 1,
            "返回按钮的格子被别的区域占用了"
        )
    }

    /** 类型列最多 5 格，且都在第 0 列。 */
    @Test
    fun `type slots live in the first column`() {
        for (index in 0 until SideConfigLayout.MAX_TYPES) {
            val slot = SideConfigLayout.typeSlot(index)
            assertEquals(SideConfigLayout.TYPE_COLUMN, slot % SideConfigLayout.PANEL_WIDTH)
            assertEquals(index, slot / SideConfigLayout.PANEL_WIDTH)
        }
    }

    /** 点红石格返回对应档位；点别的格返回 null。 */
    @Test
    fun `redstoneAfterClick maps slots back to modes`() {
        for ((mode, slot) in SideConfigLayout.REDSTONE_SLOTS) {
            assertEquals(mode, SideConfigLayout.redstoneAfterClick(slot))
        }
        assertNull(SideConfigLayout.redstoneAfterClick(SideConfigLayout.ROLE_SLOTS.getValue(FaceRole.FRONT)))
    }

    /**
     * 骨架字符串必须能被 InvUI 解析 —— 长度 = 宽 × 高。
     *
     * ⚠️ 这条是"空格占位"那个线上事故的回归：InvUI 会把空格**删掉**，
     * 长度对不上就直接抛异常。
     */
    @Test
    fun `structure string matches the panel size`() {
        val expected = SideConfigLayout.PANEL_WIDTH * SideConfigLayout.PANEL_HEIGHT
        assertEquals(expected, SideConfigGui.STRUCTURE.length, "骨架长度和面板尺寸对不上")
        assertTrue(SideConfigGui.STRUCTURE.none { it == ' ' }, "骨架里不能有空格（会被 InvUI 删掉）")
    }
}
