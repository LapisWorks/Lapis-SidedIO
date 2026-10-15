package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.i18n.RebarArgument
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player

/**
 * 面配置的交互逻辑（**不绑定任何触发方式**）。
 *
 * 早先这里是个监听 `PlayerInteractEvent`、用"空手右键"切面的对象。
 * 那个设计是错的 —— 空手右键是"打开机器界面"的通用手势，
 * 拿它切面既反直觉又和机器自己的 GUI 打架（玩家原话："谁家好人空手右键切页面啊"）。
 *
 * 现在切面的**触发**交给 RebarWrench（见 [WrenchBridge]），
 * 这个对象只留"切面之后要干什么"这部分纯逻辑，两种入口共用：
 *
 * - [WrenchBridge]：拿扳手右键 → `CONFIGURE` → 调 [cycle]
 * - [top.mc506lw.rebar.sidedio.gui.SideConfigGui]：面板里点按钮 → 调 [cycle]
 *
 * ## 两条路径
 *
 * - **Rebar 机器** → [SidedIoRegistry]（附属 `attach()` 时注册好的 [SideConfig]）
 * - **原版容器** → [VanillaSideStore]（按坐标 + 方块 PDC）
 */
object SidedInteraction {

    /**
     * 把一个格子的方向切到下一个（或上一个），处理"该走哪条路径"。
     *
     * @param type      流的是什么（[ChannelTypes.ITEM] / [ChannelTypes.ENERGY] / …）。
     *                  老调用方（扳手）不指定类型 —— 见 [cycleWidest]。
     * @param backwards 反向切（Shift 右键 / 面板里右键）
     * @return 切换后的方向；这个方块不归 Sided-IO 管、或这个格不存在时返回 null
     */
    fun cycle(
        player: Player,
        block: org.bukkit.block.Block,
        face: BlockFace,
        type: ChannelType,
        backwards: Boolean
    ): SideMode? {
        val mode = when {
            // 路径 1：Rebar 机器
            SidedIoRegistry.isManaged(block) -> {
                val config = SidedIoRegistry.get(block)!!
                if (!config.hasChannel(face, type)) return null
                val next = if (backwards) config.cycleModeBackwards(face, type) else config.cycleMode(face, type)
                // 方向变了 → 组的槽位要跟着重算（组名里带方向名，等于换组）
                SidedIoRegistry.refreshGroups(block)
                next
            }
            // 路径 2：原版容器（暂不分类型 —— 原版方块只有一个物品通道）
            VanillaSidesBridge.isManagedVanilla(block) -> {
                if (type != ChannelTypes.ITEM) return null
                if (backwards) VanillaSideStore.cycleModeBackwards(block, face)
                else VanillaSideStore.cycleMode(block, face)
            }
            else -> return null
        }
        return mode
    }

    /**
     * 兼容入口：不指定类型时，切**这个面上最宽的那个类型**。
     *
     * 给"手里只有一把扳手、没打开面板"的路径用（[WrenchBridge]）。
     * 拿扳手右键一台熔炉时，玩家心里想的是"这个面进/出料"，
     * 而不是"北面的物品通道"—— 所以取该面聚合出的方向对应的类型。
     *
     * 一台机器只有一个类型时（绝大多数情况），这和 [cycle] 完全等价。
     */
    fun cycleWidest(
        player: Player,
        block: org.bukkit.block.Block,
        face: BlockFace,
        backwards: Boolean
    ): SideMode? {
        val config = SidedIoRegistry.get(block)
        if (config != null) {
            val types = config.typesOn(face)
            if (types.isEmpty()) return null
            // 取当前方向"最宽"的类型；并列时取第一个（注册顺序，稳定）
            val target = types.maxByOrNull { rank(config.mode(face, it)) }!!
            return cycle(player, block, face, target, backwards)
        }
        // 原版容器路径：不分类型
        return cycle(player, block, face, ChannelTypes.ITEM, backwards)
    }

    /**
     * 给玩家发一条"这个格子现在是什么方向"的动作栏提示。
     *
     * 抽出来是因为两个入口都要发：[WrenchBridge] 走扳手路径，
     * 面板走 GUI 路径 —— 提示文案必须一致，否则玩家会以为是两个功能。
     *
     * @param type 为 null 时只报面和方向（扳手路径不区分类型）。
     */
    fun announceFaceMode(player: Player, face: BlockFace, mode: SideMode, type: ChannelType? = null) {
        val message = if (type == null) {
            Component.translatable(
                "sidedio.message.face-mode",
                RebarArgument.of("face", Component.translatable(faceKey(face))),
                RebarArgument.of("mode", modeComponent(mode)),
            )
        } else {
            Component.translatable(
                "sidedio.message.face-mode-typed",
                RebarArgument.of("face", Component.translatable(faceKey(face))),
                RebarArgument.of("type", Component.translatable(type.nameKey).color(TextColor.color(type.color))),
                RebarArgument.of("mode", modeComponent(mode)),
            )
        }
        player.sendActionBar(message)
    }

    /**
     * 切换红石档位后给玩家的动作栏提示。
     *
     * 红石是**机器级**的，没有"面"可报，所以单独一条消息。
     */
    fun announceRedstone(player: Player, mode: RedstoneMode) {
        player.sendActionBar(
            Component.translatable(
                "sidedio.message.redstone",
                RebarArgument.of("mode", Component.translatable(mode.translationKey).color(colorOf(mode))),
            )
        )
    }

    /** 方向的显示组件（带 TE 配色）。 */
    private fun modeComponent(mode: SideMode): Component =
        Component.translatable(modeKey(mode)).color(colorOf(mode))

    /**
     * ⚠️ 面的翻译键是**自带的** `sidedio.face.*`，不是 `block.minecraft.<face>` ——
     * 后者原版根本没有，用了会在界面上显示裸 key。
     */
    private fun faceKey(face: BlockFace) = "sidedio.face.${face.name.lowercase()}"

    private fun modeKey(mode: SideMode) = mode.translationKey

    /** 红石档位的显示颜色：忽略=灰、低=蓝、高=橙、脉冲=紫。 */
    private fun colorOf(mode: RedstoneMode): TextColor = when (mode) {
        RedstoneMode.IGNORED -> TextColor.color(0x6B7280)
        RedstoneMode.LOW -> TextColor.color(0x4A9EFF)
        RedstoneMode.HIGH -> TextColor.color(0xFFA500)
        RedstoneMode.PULSE -> TextColor.color(0xA855F7)
    }

    private fun rank(mode: SideMode): Int = when (mode.direction) {
        SideMode.Flow.NONE -> 0
        SideMode.Flow.IN, SideMode.Flow.OUT -> 1
        SideMode.Flow.BOTH -> 2
    }
}
