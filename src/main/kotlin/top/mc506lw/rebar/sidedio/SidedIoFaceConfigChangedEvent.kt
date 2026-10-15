package top.mc506lw.rebar.sidedio

import org.bukkit.block.Block
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * **某台机器的面配置变了** —— 玩家改了方向、或者机器重建了面配置。
 *
 * ## 为什么必须发这个事件
 *
 * 因为"接线的判定"和"改配置后的重算"是**两件事**。管道只在
 * "自己放下来 / 邻居变了"的时候问一次"这个面允不允许接"；玩家之后
 * 把面从**输入**改成**空**，管道**不会自己知道** —— 那条胳膊会一直画着，
 * 又变回"看着连了其实没连"。
 *
 * 所以 Sided-IO 要主动喊一声，让管道（以及任何关心面配置的东西）重算。
 *
 * ## 谁在听
 *
 * - **Lapis-Pipez**：收到就 `ConduitNetwork.markAround(block.position)`，
 *   下一 tick 重算那一圈管格的形状。
 *
 * ## 触发时机
 *
 * 由 [SidedIoRegistry.refreshGroups] 统一发出 —— 那是"方向变了，组要重算"
 * 的**唯一收口处**（面板点击、扳手切面、[SidedIo.reattach] 都走它）。
 *
 * ⚠️ 这是**通知**事件，不是"可取消的决策点"。想拦接线请用
 * [SidedIo.canConnect]。
 */
class SidedIoFaceConfigChangedEvent(
    /** 面配置发生变化的那个方块。 */
    val block: Block,
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList: HandlerList = HandlerList()
    }
}
