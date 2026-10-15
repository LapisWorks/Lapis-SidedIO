package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.i18n.RebarArgument
import io.github.pylonmc.rebar.waila.WailaDisplay
import io.github.pylonmc.rebar.waila.WailaSupplier
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player

/**
 * WAILA 提示：玩家盯着某个面时，在顶部条里显示"这个面现在是什么模式"。
 *
 * 这解决 Sided-IO 这类库最容易踩的坑：**配置面板改完，玩家根本看不出改没改**。
 * MEK 的做法是画一堆带颜色的小箭头贴着方块；我们没有渲染入口，
 * 退而求其次用 WAILA —— 玩家盯着面看就立刻知道它是进是出。
 *
 * 面是靠玩家的视线命中面反推的，和右键交互用的是同一套面判定，两者不会打架。
 */
internal object SidedWaila : WailaSupplier {

    /** 交互距离上限（原版默认 4.5，向上取一点容错）。 */
    private const val REACH = 5

    override fun getWaila(player: Player): WailaDisplay? {
        val block = player.getTargetBlockExact(REACH) ?: return null
        val face = player.rayTraceBlocks(REACH.toDouble())?.hitBlockFace ?: return null
        if (face == BlockFace.SELF) return null

        val config = SidedIoRegistry.get(block) ?: return null
        val mode = config.faceMode(face)

        val display = WailaDisplay.of(Component.translatable(block.type.translationKey()))
            .add(
                Component.translatable(
                    "sidedio.waila.side",
                    RebarArgument.of("face", Component.translatable(faceKey(face))),
                    // ⚠️ 方向名走我们自己的翻译键 + 主题色，不走原版。
                    // `colorOf` 是 TE 那套约定（蓝进 / 橙出 / 灰双向 / 红空）。
                    RebarArgument.of(
                        "mode",
                        Component.translatable(modeKey(mode)).color(colorOf(mode))
                    ),
                )
            )

        // 面上的**每一条通道**再单独列一行 —— 这才是新模型的核心信息。
        // 一台熔炉的顶面可能是"物品输出 + 能量输入"，只报一个聚合方向会骗人。
        for (type in config.typesOn(face)) {
            val channelMode = config.mode(face, type)
            if (channelMode == SideMode.NONE) continue
            display.add(
                Component.translatable(
                    "sidedio.waila.channel",
                    RebarArgument.of(
                        "type",
                        Component.translatable(type.nameKey).color(TextColor.color(type.color))
                    ),
                    RebarArgument.of(
                        "mode",
                        Component.translatable(modeKey(channelMode)).color(colorOf(channelMode))
                    ),
                )
            )
        }

        return display
    }

    private fun faceKey(face: BlockFace) = "block.minecraft.${face.name.lowercase()}"

    private fun modeKey(mode: SideMode) = mode.translationKey
}

/**
 * 模式的显示颜色 —— 用 Thermal Expansion 定下的约定。
 *
 * 这套配色在整个科技模组圈通用，玩家一眼就能读懂：
 * 蓝 = 输入、橙 = 输出、灰 = 双向、红 = 关闭。
 * 换成别的颜色等于让老玩家重新学一遍。
 */
internal fun colorOf(mode: SideMode): TextColor = when (mode.direction) {
    SideMode.Flow.IN -> TextColor.color(0x4A9EFF)   // 蓝 = 输入（TE 约定）
    SideMode.Flow.OUT -> TextColor.color(0xFFA500)  // 橙 = 输出（TE 约定）
    SideMode.Flow.BOTH -> TextColor.color(0x9E9E9E) // 灰 = 双向
    SideMode.Flow.NONE -> TextColor.color(0xE04A4A) // 红 = 关闭
}
