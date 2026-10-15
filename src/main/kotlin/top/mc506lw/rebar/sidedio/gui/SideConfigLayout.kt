package top.mc506lw.rebar.sidedio.gui

import org.bukkit.block.BlockFace
import top.mc506lw.rebar.sidedio.ChannelType
import top.mc506lw.rebar.sidedio.FaceRole
import top.mc506lw.rebar.sidedio.MachineSetting
import top.mc506lw.rebar.sidedio.RedstoneMode

/**
 * 面板布局的**纯逻辑**部分 —— 不碰 InvUI，可单测。
 *
 * ## 布局（9 宽 × 5 高 = 45 槽）
 *
 * ```
 *       c0      c1        c2        c3      c4      c5     c6  c7  c8
 *  0  [类型1] [自动输入] [自动输出]   ·       ·       ·      ·   ·   ·
 *  1  [类型2]    ·        ·         ·      [上]     ·      ·   ·   ·
 *  2  [类型3]    ·        ·       [左]    [前]    [右]     ·   ·   ·
 *  3  [类型4]    ·        ·         ·      [下]    [后]     ·   ·   ·
 *  4  [类型5] [忽略]  [低电平]  [高电平] [脉冲]    ·      ·   ·   ·
 * ```
 *
 * ## 三个区域，对应三类配置
 *
 * | 区域 | 位置 | 维度 | 说明 |
 * |---|---|---|---|
 * | **类型列** | 最左一列 | 选"在编辑哪条通道" | 只列这台机器注册过的 |
 * | **面网格** | 中间 3×3 | **面级** `(面 × 类型) → 方向` | 前在正中、后在右下 |
 * | **机器级行** | 第 0 行 + 第 4 行 | **机器级**（和面无关） | 自动输入/输出、红石 |
 *
 * ## 为什么面网格是"角色固定"而不是"方位固定"
 *
 * 格子的位置按 **前 / 后 / 左 / 右 / 上 / 下** 排，不按 北/南/东/西：
 * **前面永远在正中间**，玩家不用管机器朝哪边都能找到它。
 * 具体哪个面落在哪一格，由机器朝向 + [FaceRole.faceOf] 现算。
 *
 * "后"放在右下角（而不是正下方）是照抄参考模组的排布 ——
 * 背面是玩家最少碰的面，塞进角落不占视觉重心。
 *
 * ## 为什么面板只有 5 行
 *
 * 原来是 6 行（54 槽），但左下和右下各空一片。改成 5 行（45 槽）后
 * 三个区域刚好铺满，面板也更小巧。45 是 9 的整数倍，是合法的箱子尺寸。
 */
object SideConfigLayout {

    const val PANEL_WIDTH = 9
    const val PANEL_HEIGHT = 5

    /** 左侧类型按钮所在列。 */
    const val TYPE_COLUMN = 0

    /** 左侧类型按钮最多放几行（第 0..4 行）。 */
    const val MAX_TYPES = 5

    /** 自动输入开关。 */
    const val AUTO_INPUT_SLOT = 1

    /** 自动输出开关。 */
    const val AUTO_OUTPUT_SLOT = 2

    /**
     * 「返回」按钮的槽位（第 0 行最右 = 右上角）。
     *
     * ## 为什么在这个角
     *
     * 面板是**被机器界面顶开**的（InvUI 一个玩家同时只有一个窗口），
     * 所以机器必须能在面板里给玩家留一条回去的路。位置对齐
     * 机器自己的返回按钮 —— 本项目的铁熔炉把它放在**同一个角**（右上），
     * 玩家从哪个界面进来，"返回"都在同一个位置。
     *
     * 没有 `back` 回调时不画这个格子（[cells] 的 `hasBack` 参数）。
     */
    const val BACK_SLOT = 8

    /**
     * 六个面按**角色**固定的槽位。
     *
     * ```
     *        上
     *   左   前   右
     *        下   后
     * ```
     *
     * 这是面板的骨架 —— 玩家认的是"正中那个永远是前面"，
     * 而不是"西面永远在第三格"。
     */
    val ROLE_SLOTS: Map<FaceRole, Int> = mapOf(
        FaceRole.TOP to 13,      // 行1，中列
        FaceRole.LEFT to 21,     // 行2，列3
        FaceRole.FRONT to 22,    // 行2，中列 ← 正中
        FaceRole.RIGHT to 23,    // 行2，列5
        FaceRole.BOTTOM to 31,   // 行3，中列
        FaceRole.BACK to 32,     // 行3，列5 ← 右下角
    )

    /** 红石四个档位的槽位（行4，列1..4），顺序对齐 [RedstoneMode.CYCLE_ORDER]。 */
    val REDSTONE_SLOTS: Map<RedstoneMode, Int> = RedstoneMode.CYCLE_ORDER
        .mapIndexed { index, mode -> mode to 37 + index }
        .toMap()

    /** 类型按钮的槽号（左侧列，从上往下）。 */
    fun typeSlot(index: Int): Int = index * PANEL_WIDTH + TYPE_COLUMN

    /**
     * 面板上**每个格子放什么** —— 纯数据描述。
     *
     * @param types       这台机器注册的通道类型（左侧列）
     * @param facing      机器朝向（决定"前面"是哪个面）
     * @param hiddenFaces 机器主动屏蔽掉的面（不画格子）
     * @param settings    这台机器支持哪些机器级设置（不支持的格子不画）
     * @param hasBack     有没有"返回上一级界面"的去处；有才画返回按钮
     */
    fun cells(
        types: List<ChannelType>,
        facing: BlockFace,
        hiddenFaces: Set<BlockFace>,
        settings: Set<MachineSetting>,
        hasBack: Boolean = false,
    ): List<PanelCell> {
        val result = ArrayList<PanelCell>()
        val normalizedFacing = FaceRole.normalizeFacing(facing)

        // 左侧：类型选择按钮
        for ((index, type) in types.take(MAX_TYPES).withIndex()) {
            result += PanelCell.TypeButton(typeSlot(index), type)
        }

        // 机器级：自动输入 / 自动输出（第 0 行）
        if (MachineSetting.AUTO_INPUT in settings) {
            result += PanelCell.AutoToggle(AUTO_INPUT_SLOT, MachineSetting.AUTO_INPUT)
        }
        if (MachineSetting.AUTO_OUTPUT in settings) {
            result += PanelCell.AutoToggle(AUTO_OUTPUT_SLOT, MachineSetting.AUTO_OUTPUT)
        }

        // 返回上一级界面（机器自己提供的去处）
        if (hasBack) result += PanelCell.Back(BACK_SLOT)

        // 面网格：按角色画，面本身现算
        for ((role, slot) in ROLE_SLOTS) {
            val face = FaceRole.faceOf(role, normalizedFacing)
            if (face in hiddenFaces) continue
            result += PanelCell.FaceButton(slot, face)
        }

        // 机器级：红石四档（第 4 行）
        if (MachineSetting.REDSTONE in settings) {
            for ((mode, slot) in REDSTONE_SLOTS) {
                result += PanelCell.RedstoneOption(slot, mode)
            }
        }

        return result
    }

    /**
     * 点某个面格子时，方向应该切到哪个。
     *
     * @return `(面, 切换后的方向)`；格子不是面格时返回 null。
     */
    fun modeAfterClick(slot: Int, current: top.mc506lw.rebar.sidedio.SideMode, backwards: Boolean): Pair<BlockFace, top.mc506lw.rebar.sidedio.SideMode>? {
        val face = faceAtSlot(slot) ?: return null
        val next = if (backwards) current.previous() else current.next()
        return face to next
    }

    /** 某个槽位上放的是哪个面（按当前朝向算）。不是面格返回 null。 */
    fun faceAtSlot(slot: Int, facing: BlockFace = BlockFace.NORTH): BlockFace? {
        val role = ROLE_SLOTS.entries.firstOrNull { it.value == slot }?.key ?: return null
        return FaceRole.faceOf(role, FaceRole.normalizeFacing(facing))
    }

    /** 点某个格子时，切到哪个类型（左侧按钮）。返回 null 表示不是类型按钮。 */
    fun typeAfterClick(slot: Int, types: List<ChannelType>): ChannelType? {
        if (slot % PANEL_WIDTH != TYPE_COLUMN) return null
        val index = slot / PANEL_WIDTH
        if (index !in types.indices || index >= MAX_TYPES) return null
        return types[index]
    }

    /** 点某个格子时，红石该切到哪个档位。返回 null 表示不是红石格。 */
    fun redstoneAfterClick(slot: Int): RedstoneMode? =
        REDSTONE_SLOTS.entries.firstOrNull { it.value == slot }?.key

    /** 点某个格子时，是哪个自动开关。返回 null 表示不是自动开关格。 */
    fun autoToggleAtSlot(slot: Int): MachineSetting? = when (slot) {
        AUTO_INPUT_SLOT -> MachineSetting.AUTO_INPUT
        AUTO_OUTPUT_SLOT -> MachineSetting.AUTO_OUTPUT
        else -> null
    }

    /** 面板里所有涉及的角色。 */
    fun rolesInPanel(): Set<FaceRole> = ROLE_SLOTS.keys

    /**
     * 布局自检：六个角色必须各占一格、且不和其它区域撞。
     *
     * 手写槽位号最容易犯的错就是两个面用了同一个格子 ——
     * 结果玩家点"上"改的却是"下"。这个检查能在单测里立刻抓出来。
     */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        val slots = ROLE_SLOTS.values.toList()

        if (slots.size != slots.toSet().size) problems += "有格子被两个角色共用"
        if (ROLE_SLOTS.keys.size != FaceRole.entries.size) problems += "六个角色没有全覆盖"
        if (FaceRole.FRONT !in ROLE_SLOTS) problems += "缺前面的格子"
        if (FaceRole.BACK !in ROLE_SLOTS) problems += "缺背面的格子"

        val maxSlot = PANEL_WIDTH * PANEL_HEIGHT - 1
        for (slot in slots + REDSTONE_SLOTS.values +
            listOf(AUTO_INPUT_SLOT, AUTO_OUTPUT_SLOT, BACK_SLOT)) {
            if (slot < 0 || slot > maxSlot) problems += "格子 $slot 越出面板范围"
        }

        // 类型列不能被其它区域占用
        val others = slots + REDSTONE_SLOTS.values +
            listOf(AUTO_INPUT_SLOT, AUTO_OUTPUT_SLOT, BACK_SLOT)
        for (index in 0 until MAX_TYPES) {
            val ts = typeSlot(index)
            if (ts in others) problems += "类型按钮的槽 $ts 被其它区域占用了"
        }

        // 返回按钮不能和面格子 / 开关 / 红石撞
        val fixed = slots + REDSTONE_SLOTS.values + listOf(AUTO_INPUT_SLOT, AUTO_OUTPUT_SLOT)
        if (BACK_SLOT in fixed) problems += "返回按钮的槽 $BACK_SLOT 被其它区域占用了"
        if (BACK_SLOT % PANEL_WIDTH == TYPE_COLUMN) problems += "返回按钮压在类型列上了"

        // 红石四格必须互不相同
        if (REDSTONE_SLOTS.values.toSet().size != RedstoneMode.entries.size) {
            problems += "红石格子有重叠或缺失"
        }

        return problems
    }
}

/** 面板上的一个格子该放什么。 */
sealed interface PanelCell {
    val slot: Int

    /** 左侧的类型选择按钮。 */
    data class TypeButton(override val slot: Int, val type: ChannelType) : PanelCell

    /** 面网格里的一个格子（显示"当前类型在这个面的方向"）。 */
    data class FaceButton(override val slot: Int, val face: BlockFace) : PanelCell

    /** 机器级：自动输入 / 自动输出开关。 */
    data class AutoToggle(override val slot: Int, val setting: MachineSetting) : PanelCell

    /** 机器级：红石的某一个档位。 */
    data class RedstoneOption(override val slot: Int, val mode: RedstoneMode) : PanelCell

    /** 「返回上一级界面」—— 只有机器提供了 `back` 回调时才出现。 */
    data class Back(override val slot: Int) : PanelCell
}
