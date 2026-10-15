package top.mc506lw.rebar.sidedio.gui

import io.github.pylonmc.rebar.i18n.RebarArgument
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import top.mc506lw.rebar.sidedio.ChannelType
import top.mc506lw.rebar.sidedio.EnergyPorts
import top.mc506lw.rebar.sidedio.FaceRole
import top.mc506lw.rebar.sidedio.MachineSetting
import top.mc506lw.rebar.sidedio.RedstoneMode
import top.mc506lw.rebar.sidedio.SideConfig
import top.mc506lw.rebar.sidedio.SideMode
import top.mc506lw.rebar.sidedio.SidedInteraction
import top.mc506lw.rebar.sidedio.SidedIoRegistry
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemWrapper
import xyz.xenondevs.invui.window.Window

/**
 * 机器配置面板（InvUI 渲染层）。
 *
 * 布局的**语义**在 [SideConfigLayout]（可单测），这里只负责画出来。
 *
 * ## 面板长什么样（9 × 5）
 *
 * ```
 *       c0      c1        c2        c3      c4      c5
 *  0  [类型1] [自动输入] [自动输出]    ·       ·       ·
 *  1  [类型2]    ·        ·          ·      [上]     ·
 *  2  [类型3]    ·        ·        [左]    [前]    [右]
 *  3  [类型4]    ·        ·          ·      [下]    [后]
 *  4  [类型5] [忽略]  [低电平]  [高电平] [脉冲]    ·
 * ```
 *
 * 三个区域对应三类配置，**维度不同**：
 *
 * | 区域 | 维度 | 谁的值 |
 * |---|---|---|
 * | 类型列 | 选"在编辑哪条通道" | 机器注册的类型 |
 * | 中间 3×3 | **面级** `(面 × 类型) → 方向` | [SideConfig] |
 * | 第 0 / 4 行 | **机器级**（和面无关） | [top.mc506lw.rebar.sidedio.MachineSettings] |
 *
 * ## 为什么"前面"永远在正中
 *
 * 面格子的位置按**角色**（前/后/左/右/上/下）固定，不按方位。
 * 具体哪个面落在正中，由机器朝向现算（[FaceRole.faceOf]）。
 * 所以玩家不用管机器朝哪边 —— 正中永远是前面。
 *
 * ## 配色（对齐参考模组）
 *
 * | 方向 | 颜色 | 材质 |
 * |---|---|---|
 * | 输入 | 蓝 | `BLUE_CONCRETE` |
 * | 输出 | 橙 | `ORANGE_CONCRETE` |
 * | 双向 | 紫 | `PURPLE_CONCRETE` |
 * | 空 | 深灰 | `GRAY_CONCRETE` |
 *
 * ## 交互
 *
 * - 面格子：左键 → 下一个方向，右键 → 上一个
 * - 类型：左键 → 切到那个类型（**原地重绘**，不关窗 —— 光标和鼠标位置都不动）
 * - 自动开关：左键 → 开 / 关
 * - 红石：左键 → 直接切到那一档
 * - 返回：左键 → 回机器自己的界面（只有机器传了 `back` 回调时才有这一格）
 */
object SideConfigGui {

    /**
     * 面板骨架：**全是占位字符，没有空格**。
     *
     * ⚠️ **空格不是"空槽"，是被 `sanitize()` 直接删掉的**。
     * InvUI 的 `Structure` 构造器会先把 `str.replace(" ", "").replace("\n", "")`，
     * 再断言 `width * height == 剩下字符串的长度`。
     *
     * 线上炸过：这里写成了空格占位 → 净化后长度变 0 → `Gui` 里一个跨度都没有
     * → `setItem(10, ...)` 抛 `ArrayIndexOutOfBoundsException`。
     *
     * 所以整块（宽 × 高）**全部填满**（用 `x` 占位，运行时按
     * [SideConfigLayout.cells] 逐个替换成真内容，剩下的由背景兜底）。
     */
    internal val STRUCTURE: String = "x".repeat(SideConfigLayout.PANEL_WIDTH * SideConfigLayout.PANEL_HEIGHT)

    /** 骨架里唯一的字符 —— 所有格子运行时再决定放什么。 */
    internal const val PLACEHOLDER = 'x'

    /** 给 [player] 打开方块 [block] 的面配置面板。 */
    fun open(player: Player, block: Block) {
        val config = SidedIoRegistry.get(block) ?: return
        open(player, block, config)
    }

    /**
     * 打开面板，并指定点「返回」时去哪。
     *
     * ## 机器从自己的界面跳过来时**必须**传这个
     *
     * 面板是**顶掉**机器的窗口打开的（InvUI 一个玩家只有一个窗口）。
     * 不传 `back` 的话玩家在面板里没有回去的路，只能按 ESC 关掉再重新右键机器
     * （用户实测报的"页面上没有返回按钮"）。
     *
     * ```kotlin
     * SideConfigGui.open(player, block) { p -> openMainGui(p) }
     * ```
     */
    fun open(player: Player, block: Block, back: ((Player) -> Unit)?) {
        val config = SidedIoRegistry.get(block) ?: return
        open(player, block, config, initialType = null, back = back)
    }

    /**
     * 带"当前选中类型"和"返回去处"地打开面板。
     *
     * @param back 点「返回」时去哪 —— **机器**把"上一级界面"的开法传进来。
     *             面板是被机器界面顶开的（InvUI 一个玩家只有一个窗口），
     *             没有这条回调玩家就回不去了。`null` = 没有上一级，不画返回按钮。
     */
    private fun open(
        player: Player,
        block: Block,
        config: SideConfig,
        initialType: ChannelType? = null,
        back: ((Player) -> Unit)? = null,
    ) {
        // 这台机器**真的有**的类型（注册了哪个有哪个）—— 面板左侧列的就是它
        val types = config.types()
        if (types.isEmpty()) return

        val selected = initialType?.takeIf { it in types } ?: types.first()
        Panel(block, config, types, back, selected).open(player)
    }

    // ------------------------------------------------------------ 面板实例

    /**
     * 一次打开的面板 —— 持有 gui / window / 当前选中类型 / 各种回调。
     *
     * ## 为什么要有这个类（切类型时**不关窗**）
     *
     * 切"正在编辑的类型"（物品 → 能量）以前是 `closeInventory()` + 重新 `open()`。
     * 那会**把光标上的物品丢回背包、把鼠标挪回屏幕中央** ——
     * 用户实测报的"切换类型的时候鼠标不应该重置"。
     *
     * 现在改成**原地重绘**：窗口一直开着，只把每个格子的内容换掉。
     * 布局是固定的（45 格全占位），换内容不需要动结构。
     *
     * 把状态收进一个持有者，是因为重绘要同时用到 gui、window、选中类型、
     * 返回回调 —— 散成十几个参数会传疯。
     */
    private class Panel(
        val block: Block,
        val config: SideConfig,
        val types: List<ChannelType>,
        val back: ((Player) -> Unit)?,
        var selected: ChannelType,
    ) {

        lateinit var gui: Gui
        lateinit var window: Window

        fun open(player: Player) {
            gui = Gui.builder()
                .setStructure(SideConfigLayout.PANEL_WIDTH, SideConfigLayout.PANEL_HEIGHT, STRUCTURE)
                .build()
            window = Window.builder()
                .setViewer(player)
                .setTitle(title(selected, config, block))
                .setUpperGui(gui)
                .build()
            render()
            window.open()
        }

        /**
         * 按当前 [selected] 把面板重画一遍。**窗口不关** —— 光标、鼠标位置都不动。
         *
         * 布局的格子集合不随选中类型变化（左侧列始终是这台机器的全部类型，
         * 面网格始终是没被屏蔽的面），所以"重设每个格子"就够了，不用清空。
         */
        fun render() {
            gui.setBackground(ItemWrapper(background(config, selected)))

            for (cell in SideConfigLayout.cells(
                types = types,
                facing = config.facing,
                hiddenFaces = config.hiddenFaces,
                settings = config.settings.supported,
                hasBack = back != null,
            )) {
                gui.setItem(cell.slot, itemFor(cell))
            }

            window.setTitle(title(selected, config, block))
            gui.notifyWindows()
        }

        private fun itemFor(cell: PanelCell): Item = when (cell) {
            is PanelCell.TypeButton -> typeButton(cell, this)
            is PanelCell.FaceButton -> faceButton(cell, this)
            is PanelCell.AutoToggle -> autoToggleButton(cell, this)
            is PanelCell.RedstoneOption -> redstoneButton(cell, this)
            is PanelCell.Back -> backButton(this)
        }
    }

    // ------------------------------------------------------------ 各类按钮

    /**
     * 左侧的类型按钮。
     *
     * 点它 = "接下来我要编辑这个类型"。点击后**原地重绘**（不关窗、不重开）——
     * 见 [Panel] 的类注释。
     */
    private fun typeButton(cell: PanelCell.TypeButton, panel: Panel): Item =
        Item.builder()
            .setItemProvider { _: Player? ->
                ItemWrapper(typeStack(cell.type, cell.type == panel.selected, panel.config))
            }
            .addClickHandler { _, click ->
                if (cell.type != panel.selected) {
                    panel.selected = cell.type
                    panel.render()
                }
            }
            .build()

    /** 「返回上一级界面」。只有机器提供了 `back` 回调时才会出现。 */
    private fun backButton(panel: Panel): Item =
        Item.builder()
            .setItemProvider { _: Player? ->
                ItemWrapper(
                    ItemStackBuilder.of(Material.ARROW)
                        .name(
                            Component.translatable("sidedio.gui.back")
                                .decoration(TextDecoration.ITALIC, false)
                        )
                        .lore(
                            Component.translatable("sidedio.gui.back_lore")
                                .decoration(TextDecoration.ITALIC, false)
                                .colorIfAbsent(NamedTextColor.DARK_GRAY)
                        )
                        .build()
                )
            }
            .addClickHandler { _, click ->
                panel.back?.invoke(click.player)
            }
            .build()

    /** 面网格里的一个格子。 */
    private fun faceButton(cell: PanelCell.FaceButton, panel: Panel): Item {
        val type = panel.selected
        val config = panel.config

        // ⚠️ **不可配的类型**（能量）走只读分支：它的方向是假概念，
        // 画成可点的开关等于骗玩家。改成如实展示机器实际的端口情况。
        if (!type.configurable) return readOnlyFaceButton(cell, type, config, panel.block)

        return Item.builder()
            // ⚠️ 必须每次重绘都重新求值（不能用 `setItemProvider(ItemStack)`）
            // —— 后者只渲染一次，点完显示的还是旧方向（Lapis-Pipez 踩过这个坑）。
            .setItemProvider { _: Player? ->
                ItemWrapper(faceStack(cell.face, type, config.mode(cell.face, type), config.facing))
            }
            .addClickHandler { _, click ->
                val backwards = click.clickType.isRightClick
                // ⚠️ InvUI 的 BiConsumer 第一个参数是 **Item**（那个 item 本身），
                // 不是 Player —— 玩家要从 click.player 取。
                // 走统一入口：回调里会同时 refreshGroups（方向变了组名也跟着变）
                // 并给玩家发动作栏提示，和"拿扳手右键"走同一条逻辑，手感一致。
                val face = cell.face
                val current = config.mode(face, type)
                val next = if (backwards) {
                    config.cycleModeBackwards(face, type)
                } else {
                    config.cycleMode(face, type)
                }
                if (next != current || config.hasChannel(face, type)) {
                    SidedIoRegistry.refreshGroups(panel.block)
                    SidedInteraction.announceFaceMode(click.player, face, next, type)
                }
                // 重绘整个面板 —— 换了方向后，左侧类型的"方向摘要"和背景色也都变了
                panel.render()
            }
            .build()
    }

    /**
     * **只读**的面格子 —— 给不可配的类型（能量）用。
     *
     * 显示机器在这个面**实际有没有电口、接了几根线**。点它没有任何反应
     * （因为确实改不了：端口在哪一面是机器定的，Rebar 也没给拦接线的钩子）。
     */
    private fun readOnlyFaceButton(
        cell: PanelCell.FaceButton,
        type: ChannelType,
        config: SideConfig,
        block: Block
    ): Item = Item.builder()
        .setItemProvider { _: Player? ->
            // 每次重绘都重新读 —— 玩家可能在面板开着的时候接了根线
            val wires = EnergyPorts.statusOn(block, cell.face)
            ItemWrapper(readOnlyFaceStack(cell.face, config.facing, wires))
        }
        .build()

    /** 机器级：自动输入 / 自动输出开关。 */
    private fun autoToggleButton(cell: PanelCell.AutoToggle, panel: Panel): Item =
        Item.builder()
            .setItemProvider { _: Player? ->
                ItemWrapper(autoToggleStack(cell.setting, isAutoOn(cell.setting, panel.config)))
            }
            .addClickHandler { _, _ ->
                // 左键开关，右键也是开关（开关没有"上一个"的概念）
                val now = isAutoOn(cell.setting, panel.config)
                setAuto(cell.setting, panel.config, !now)
                panel.render()
            }
            .build()

    /** 机器级：红石的某一档。点一下**直接切到那一档**（不是循环）。 */
    private fun redstoneButton(cell: PanelCell.RedstoneOption, panel: Panel): Item =
        Item.builder()
            .setItemProvider { _: Player? ->
                ItemWrapper(redstoneStack(cell.mode, panel.config.settings.redstoneMode == cell.mode))
            }
            .addClickHandler { _, click ->
                panel.config.settings.redstoneMode = cell.mode
                SidedInteraction.announceRedstone(click.player, cell.mode)
                panel.render()
            }
            .build()

    // ------------------------------------------------------------ 自动开关读写

    private fun isAutoOn(setting: MachineSetting, config: SideConfig): Boolean = when (setting) {
        MachineSetting.AUTO_INPUT -> config.settings.autoInput
        MachineSetting.AUTO_OUTPUT -> config.settings.autoOutput
        MachineSetting.REDSTONE -> false
    }

    private fun setAuto(setting: MachineSetting, config: SideConfig, on: Boolean) {
        when (setting) {
            MachineSetting.AUTO_INPUT -> config.settings.autoInput = on
            MachineSetting.AUTO_OUTPUT -> config.settings.autoOutput = on
            MachineSetting.REDSTONE -> Unit
        }
    }

    // ------------------------------------------------------------ 物品构造

    /** 类型按钮的外观：类型图标 + 名字 + 这个类型在六个面上的方向摘要。 */
    private fun typeStack(type: ChannelType, selected: Boolean, config: SideConfig): ItemStack {
        val name = Component.translatable(type.nameKey).color(TextColor.color(type.color))
        val displayName =
            if (selected) name.decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
            else name.decoration(TextDecoration.ITALIC, false)

        val lore = ArrayList<Component>()

        // ⚠️ 只展示的类型（能量）**不打逐面摘要** —— 那个摘要读的是 `config.mode(...)`，
        // 对能量来说是编造的值。改成如实说明"由机器决定"。
        if (config.isDisplayOnly(type)) {
            lore += Component.translatable("sidedio.gui.display_only_lore")
                .decoration(TextDecoration.ITALIC, false)
                .colorIfAbsent(NamedTextColor.GRAY)
        } else {
            lore += Component.translatable("sidedio.gui.type_lore").decoration(TextDecoration.ITALIC, false)
                .color(NamedTextColor.GRAY)

            // 这个类型在六个面上分别是什么方向 —— 选中前也能一眼看清全貌
            for (face in top.mc506lw.rebar.sidedio.SIDED_FACES) {
                if (config.isFaceHidden(face)) continue
                if (config.typesOn(face).none { it == type }) continue
                val mode = config.mode(face, type)
                lore += Component.translatable(
                    "sidedio.gui.type_summary",
                    RebarArgument.of("face", faceLabel(face, config.facing)),
                    RebarArgument.of("mode", Component.translatable(modeKey(mode)).color(colorOf(mode))),
                ).decoration(TextDecoration.ITALIC, false)
            }
        }

        val builder = ItemStackBuilder.of(type.icon).name(displayName).lore(lore)
        if (selected) {
            // 附魔光效表达"当前正在编辑它"。
            // ⚠️ 附魔会在 tooltip 里多出一行"耐久 I" —— 必须显式隐藏，
            // 否则玩家看到的是个莫名其妙的附魔说明（光效本身不受影响）。
            builder.editMeta { meta -> meta.addEnchant(Enchantment.UNBREAKING, 1, true) }
            builder.hideFromTooltip(DataComponentTypes.ENCHANTMENTS)
        }
        return builder.build()
    }

    /**
     * 面格子的外观：方向色混凝土 + "前面·西面 / 通道 / 方向"三行。
     *
     * @param facing 机器朝向 —— 决定这个面被叫"前面"还是"左面"。
     */
    internal fun faceStack(face: BlockFace, type: ChannelType, mode: SideMode, facing: BlockFace = BlockFace.NORTH): ItemStack {
        val lore = listOf(
            Component.translatable(
                "sidedio.gui.button_frame",
                RebarArgument.of("type", Component.translatable(type.nameKey).color(TextColor.color(type.color))),
            ),
            Component.empty(),
            Component.translatable("sidedio.gui.hint.left"),
            Component.translatable("sidedio.gui.hint.right"),
        ).map { it.decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.GRAY) }

        return ItemStackBuilder.of(materialFor(mode))
            .name(
                Component.translatable(
                    "sidedio.gui.button",
                    RebarArgument.of("face", faceLabel(face, facing)),
                    RebarArgument.of("mode", Component.translatable(modeKey(mode)).color(colorOf(mode))),
                ).decoration(TextDecoration.ITALIC, false)
            )
            .lore(lore)
            .build()
    }

    /** 自动输入 / 自动输出开关的外观。 */
    internal fun autoToggleStack(setting: MachineSetting, on: Boolean): ItemStack {
        val material = when (setting) {
            MachineSetting.AUTO_INPUT -> Material.BLUE_CONCRETE
            MachineSetting.AUTO_OUTPUT -> Material.ORANGE_CONCRETE
            MachineSetting.REDSTONE -> Material.REDSTONE_TORCH
        }
        val stateColor = if (on) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY
        return ItemStackBuilder.of(material)
            .name(
                Component.translatable(setting.translationKey).decoration(TextDecoration.ITALIC, false)
            )
            .lore(
                Component.translatable(
                    "sidedio.gui.setting_state",
                    RebarArgument.of(
                        "state",
                        Component.translatable(if (on) "sidedio.gui.state_on" else "sidedio.gui.state_off").color(stateColor)
                    ),
                ).decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.GRAY),
                Component.empty(),
                Component.translatable(setting.descriptionKey)
                    .decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.DARK_GRAY),
                Component.translatable("sidedio.gui.setting_hint")
                    .decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.DARK_GRAY),
            )
            .build()
    }

    /** 红石某一档的外观。选中的那档叠光效。 */
    internal fun redstoneStack(mode: RedstoneMode, selected: Boolean): ItemStack {
        val material = when (mode) {
            RedstoneMode.IGNORED -> Material.REDSTONE_BLOCK
            RedstoneMode.LOW -> Material.REDSTONE_TORCH
            RedstoneMode.HIGH -> Material.REDSTONE_LAMP
            RedstoneMode.PULSE -> Material.REPEATER
        }
        val builder = ItemStackBuilder.of(material)
            .name(
                Component.translatable(mode.translationKey)
                    .decoration(TextDecoration.ITALIC, false)
                    .color(if (selected) NamedTextColor.GREEN else NamedTextColor.WHITE)
            )
            .lore(
                Component.translatable(mode.descriptionKey)
                    .decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.GRAY),
                Component.empty(),
                Component.translatable("sidedio.gui.setting_hint")
                    .decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.DARK_GRAY),
            )
        if (selected) {
            builder.editMeta { meta -> meta.addEnchant(Enchantment.UNBREAKING, 1, true) }
            builder.hideFromTooltip(DataComponentTypes.ENCHANTMENTS)
        }
        return builder.build()
    }

    /**
     * **只读**面格子的外观 —— 给不可配的类型（能量）用。
     *
     * @param wires 这个面接了几根线；`null` 表示**这个面根本没有端口**。
     */
    internal fun readOnlyFaceStack(face: BlockFace, facing: BlockFace, wires: Int?): ItemStack {
        val material = when {
            wires == null -> Material.GRAY_CONCRETE
            wires > 0 -> Material.REDSTONE_BLOCK
            else -> Material.REDSTONE_TORCH
        }
        val statusKey = when {
            wires == null -> "sidedio.energy.no_port"
            wires > 0 -> "sidedio.energy.wired"
            else -> "sidedio.energy.idle"
        }
        val status = Component.translatable(statusKey, RebarArgument.of("wires", wires ?: 0))
            .decoration(TextDecoration.ITALIC, false)
            .colorIfAbsent(NamedTextColor.GRAY)

        return ItemStackBuilder.of(material)
            .name(
                Component.translatable(
                    "sidedio.gui.button",
                    RebarArgument.of("face", faceLabel(face, facing)),
                    RebarArgument.of("mode", status),
                ).decoration(TextDecoration.ITALIC, false)
            )
            .lore(
                Component.translatable("sidedio.gui.readonly_hint")
                    .decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.DARK_GRAY),
            )
            .build()
    }

    // ------------------------------------------------------------ 背景与标题

    /**
     * 背景填充。
     *
     * 做成"跟着当前类型走颜色"的玻璃板 —— 玩家扫一眼背景色调就知道
     * 现在在编辑物品（白）还是能量（红）。这是零成本的上下文提示。
     */
    private fun background(config: SideConfig, selected: ChannelType): ItemStack =
        ItemStackBuilder.of(glassyPaneFor(selected.color))
            .name(
                // ⚠️ `%type%` 必须真的传进去 —— 只写键不传参的话，
                // 界面上会原样显示 `%type%` 字面量（踩过）。
                Component.translatable(
                    "sidedio.gui.background",
                    RebarArgument.of("type", Component.translatable(selected.nameKey).color(TextColor.color(selected.color))),
                ).decoration(TextDecoration.ITALIC, false)
            )
            .build()

    private fun title(selected: ChannelType, config: SideConfig, block: Block): Component =
        Component.translatable(
            "sidedio.gui.title",
            RebarArgument.of("type", Component.translatable(selected.nameKey).color(TextColor.color(selected.color))),
        )

    // ------------------------------------------------------------ 映射

    /**
     * 一个面的显示名 —— **角色·方位**，例如 `前面·西面`。
     *
     * 光给方位（"西面"）玩家还得换算"机器的西面是哪边"；光给角色（"前面"）
     * 又不知道在世界里朝哪。两个都给最省脑子。
     *
     * 上/下没有"方位"可言（UP 就是 UP），所以只显示角色。
     */
    private fun faceLabel(face: BlockFace, facing: BlockFace): Component {
        val role = FaceRole.of(face, facing)
        return if (role == FaceRole.TOP || role == FaceRole.BOTTOM) {
            Component.translatable(role.translationKey)
        } else {
            Component.translatable(
                "sidedio.gui.face_label",
                RebarArgument.of("role", Component.translatable(role.translationKey)),
                RebarArgument.of("compass", Component.translatable("sidedio.face.${face.name.lowercase()}")),
            )
        }
    }

    private fun modeKey(mode: SideMode) = mode.translationKey

    /**
     * 方向 → 材质。**对齐参考模组那套颜色语言**：
     * 输入=蓝、输出=橙、双向=紫、空=深灰。
     *
     * 这套约定在科技模组圈通用，玩家扫一眼颜色就知道这格是什么方向，
     * 不用读字。
     */
    private fun materialFor(mode: SideMode): Material = when (mode.direction) {
        SideMode.Flow.IN -> Material.BLUE_CONCRETE
        SideMode.Flow.OUT -> Material.ORANGE_CONCRETE
        SideMode.Flow.BOTH -> Material.PURPLE_CONCRETE
        SideMode.Flow.NONE -> Material.GRAY_CONCRETE
    }

    /**
     * 主题色 → 最接近的染色玻璃板。
     *
     * 用一个朴素的"最近色"匹配，而不是维护一张色表 ——
     * 第三方注册新类型时不用（也不能）来这里登记颜色。
     */
    private fun glassyPaneFor(color: Int): Material {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val panes = listOf(
            0xFFFFFF to Material.WHITE_STAINED_GLASS_PANE,
            0x9E9E9E to Material.LIGHT_GRAY_STAINED_GLASS_PANE,
            0x4A9EFF to Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            0xE04A4A to Material.RED_STAINED_GLASS_PANE,
            0x7FE0C0 to Material.CYAN_STAINED_GLASS_PANE,
            0xE0E0E0 to Material.GRAY_STAINED_GLASS_PANE,
            0xFFD700 to Material.YELLOW_STAINED_GLASS_PANE,
            0x8B5A2B to Material.BROWN_STAINED_GLASS_PANE,
            0x50C878 to Material.GREEN_STAINED_GLASS_PANE,
            0x6A0DAD to Material.PURPLE_STAINED_GLASS_PANE,
        )
        return panes.minByOrNull { (hex, _) ->
            val dr = ((hex shr 16) and 0xFF) - r
            val dg = ((hex shr 8) and 0xFF) - g
            val db = (hex and 0xFF) - b
            dr * dr + dg * dg + db * db
        }?.second ?: Material.BLACK_STAINED_GLASS_PANE
    }

    /**
     * 方向的显示颜色（文字用）—— 和 [materialFor] 同一套语言。
     *
     * 蓝 = 输入、橙 = 输出、紫 = 双向、深灰 = 空。
     */
    internal fun colorOf(mode: SideMode): TextColor = when (mode.direction) {
        SideMode.Flow.IN -> TextColor.color(0x4A9EFF)    // 蓝 = 输入
        SideMode.Flow.OUT -> TextColor.color(0xFFA500)   // 橙 = 输出
        SideMode.Flow.BOTH -> TextColor.color(0xA855F7)  // 紫 = 双向
        SideMode.Flow.NONE -> TextColor.color(0x6B7280)  // 深灰 = 空
    }
}
