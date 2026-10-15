package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.LogisticRebarBlock
import io.github.pylonmc.rebar.event.RebarBlockBreakEvent
import io.github.pylonmc.rebar.event.RebarBlockDeserializeEvent
import io.github.pylonmc.rebar.event.RebarBlockSerializeEvent
import io.github.pylonmc.rebar.event.RebarBlockUnloadEvent
import io.github.pylonmc.rebar.logistics.LogisticGroup
import io.github.pylonmc.rebar.util.position.position
import io.github.pylonmc.rebar.waila.Waila
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import top.mc506lw.rebar.sidedio.vanilla.VanillaFaceSlots
import top.mc506lw.rebar.sidedio.vanilla.VanillaSides
import java.util.IdentityHashMap

/**
 * 面配置的登记处：查得到、存得住、清得掉。
 *
 * ## 为什么用 [IdentityHashMap] 而不是按坐标
 *
 * Rebar 的 [RebarBlock] 实例本身就是"一个方块在内存里的代表"，随区块加载创建、
 * 随卸载销毁。用实例当键，天然和生命周期对齐。坐标表反而要额外处理
 * "同一坐标换了个方块"的边界。
 *
 * ## 持久化的时序（这张图很重要）
 *
 * 方块加载时 Rebar 的调用顺序是：
 * ```
 * 反序列化 → RebarBlockDeserializeEvent      ← 这里能把 PDC 里的面配置读出来
 * 构造器（load constructor）
 * postInitialise()                           ← 附属在这里调 attach(...).apply()
 * RebarBlockLoadEvent
 * ```
 *
 * 问题：**反序列化的时候 `attach()` 还没跑，`SideConfig` 还不存在**，
 * 没地方存恢复出来的模式。所以这里用 [pending] 做一条传送带：
 * 反序列化时读出来暂存 → `apply()` 时取走并灌进新建的 [SideConfig]。
 * 两者在**同一个调用栈**里（见上面的顺序），中间不跨 tick，不存在配置短暂丢失的窗口。
 */
object SidedIoRegistry : Listener {

    private val configs = IdentityHashMap<RebarBlock, SideConfig>()

    /** 反序列化时读出来、等 apply() 来取的面模式。取一次就删。 */
    private val pending = IdentityHashMap<RebarBlock, Map<String, String>>()

    /** 反序列化时读出来的**机器级设置**，同样等 apply() 来取。 */
    private val pendingSettings = IdentityHashMap<RebarBlock, Map<String, String>>()

    /** 查一个方块的面配置；不是 Sided-IO 管的东西就返回 null。 */
    fun get(block: Block): SideConfig? =
        if (!block.position.isChunkLoaded) null else get(BlockStorage.get(block))

    /** 查一台机器的面配置。 */
    fun get(rebar: RebarBlock?): SideConfig? = rebar?.let { configs[it] }

    /** 这个方块是不是被 Sided-IO 管着面配置。 */
    fun isManaged(block: Block): Boolean = get(block) != null

    /** 登记（`SideConfigBuilder.apply()` 里调）。 */
    internal fun register(rebar: RebarBlock, logistic: LogisticRebarBlock, config: SideConfig) {
        // 先恢复玩家改过的格子，再算组 —— 顺序不能反：
        // 组名里带着方向名，先算组就等于照默认方向建了一批错名字的组。
        takePending(rebar)?.let { config.restoreLegacyAware(it) }
        // 机器级设置独立恢复（它不影响组名，先后无所谓，但和上面放一起好读）
        pendingSettings.remove(rebar)?.let { config.settings.restore(it) }
        configs[rebar] = config

        config.rebuildGroups { name, group -> createGroupOnce(logistic, name, group) }

        Waila.addWailaOverride(rebar.block, SidedWaila)
    }

    /** 手动注销。 */
    fun unregister(rebar: RebarBlock) {
        configs.remove(rebar)
        pending.remove(rebar)
        pendingSettings.remove(rebar)
        Waila.removeWailaOverride(rebar.block)
    }

    /** 插件卸载：清掉所有登记，避免热重载残留。 */
    internal fun shutdown() {
        for (rebar in configs.keys.toList()) {
            Waila.removeWailaOverride(rebar.block)
        }
        configs.clear()
        pending.clear()
        pendingSettings.clear()
    }

    /**
     * 面模式变了之后重算物流组，并**通知外界**。
     *
     * 右键切换面、或读档恢复之后都要调 —— 否则 Rebar 手上的组还是老方向。
     *
     * ## 为什么这里要发事件
     *
     * 因为"改配置"和"别人跟着变"是两件事。管道只在**自己**放下来 / 邻居变的时候
     * 问过一次"这个面允不允许接"；玩家之后把面从"输入"改成"空"，管道不会自己知道，
     * 那条胳膊会一直画着 —— 又变成"看着连了其实没连"。
     *
     * 这个方法就是**唯一收口处**（面板点击、扳手切面、[SidedIo.reattach] 都走它），
     * 所以在这里发 [SidedIoFaceConfigChangedEvent] 能覆盖所有改配置的路径。
     */
    fun refreshGroups(block: Block) {
        val rebar = if (!block.position.isChunkLoaded) null else BlockStorage.get(block) ?: return
        val config = configs[rebar] ?: return
        val logistic = rebar as? LogisticRebarBlock ?: return
        config.rebuildGroups { name, group -> createGroupOnce(logistic, name, group) }

        // 喊一声，让管道（Lapis-Pipez）之类重算连接形状。
        // 用 runCatching 兜住：通知失败不该连累面配置本身生效。
        runCatching { SidedIoFaceConfigChangedEvent(block).callEvent() }
    }

    /**
     * 建组，但**同一台机器同名只建一次**。
     *
     * 为什么需要这层保护：[SideConfig.rebuildGroups] 的组名是**确定性**的
     * （`input__north_input` 这种），而 [LogisticRebarBlock.createLogisticGroup]
     * 撞名直接抛 `IllegalStateException`。触发路径有两条：
     *
     * 1. **区块热重载**：同一台机器第二次 `attach()`，`register()` 里又会算出
     *    一模一样的组名 → 撞名。Rebar 每个区块卸载才清组，热重载不清。
     * 2. **[SidedIo.reattach]**：先 `apply()`（建一轮组）再 `refreshGroups()`
     *    （又按同名建一轮）→ 自己撞自己。
     *
     * 老的 `rebuildGroups` 注释假设"老组留在 map 里没人引用等于废弃"，那对
     * **换模式**（名字真的变了）成立；但对"同名重建"不成立 —— 那是硬撞。
     *
     * 注意这里是**真的跳过**而不是覆盖：Rebar 没提供 remove 组的口子
     * （`logisticBlocks` 是 private 的 IdentityHashMap），同名组没法替换。
     * 而"同名 = 同模式 = 同槽位语义"在按模式编码名字的方案下是成立的，
     * 所以跳过等于复用，语义正确。
     */
    private fun createGroupOnce(logistic: LogisticRebarBlock, name: String, group: LogisticGroup) {
        if (logistic.getLogisticGroups().containsKey(name)) return
        logistic.createLogisticGroup(name, group)
    }

    // ------------------------------------------------------------ 恢复传送带

    internal fun stagePending(rebar: RebarBlock, modes: Map<String, String>) {
        pending[rebar] = modes
    }

    private fun takePending(rebar: RebarBlock): Map<String, String>? = pending.remove(rebar)

    // ------------------------------------------------------------ 事件

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private fun onDeserialize(event: RebarBlockDeserializeEvent) {
        if (event.rebarBlock !is LogisticRebarBlock) return

        // ① 面网格
        event.pdc.get(SidedIoKeys.SIDE_MODES, PersistentDataType.TAG_CONTAINER)?.let { container ->
            val saved = HashMap<String, String>()
            // ⚠️ PDC 里的键必须是 [a-z0-9_-./]，而逻辑键是 `NORTH.sidedio:item`
            // （含大写与 `:`）—— 所以读写都要过 PersistKeys 编解码。
            // 新格式：`<面名>.<类型 id>` → `<模式名>`（例：`NORTH.sidedio:item` → `INPUT`）。
            // 老格式：`<面名>` → `<模式名>`（例：`NORTH` → `INPUT`）—— 只按面存，
            // 因为那时候还没有类型这一维。两种都要读，见 restoreLegacyAware。
            for (key in container.keys) {
                val value = container.get(key, PersistentDataType.STRING) ?: continue
                saved[PersistKeys.decode(key.key) ?: key.key] = value
            }
            if (saved.isNotEmpty()) stagePending(event.rebarBlock, saved)
        }

        // ② 机器级设置（独立容器）
        event.pdc.get(SidedIoKeys.MACHINE_SETTINGS, PersistentDataType.TAG_CONTAINER)?.let { container ->
            val saved = HashMap<String, String>()
            for (key in container.keys) {
                container.get(key, PersistentDataType.STRING)?.let { saved[key.key] = it }
            }
            if (saved.isNotEmpty()) pendingSettings[event.rebarBlock] = saved
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private fun onSerialize(event: RebarBlockSerializeEvent) {
        val config = configs[event.rebarBlock] ?: return

        // ① 面网格
        // ⚠️ PDC 容器是嵌套的：外层放一个 TAG_CONTAINER，里面每格一个 STRING。
        // 键要经 PersistKeys 编码成合法字符集，否则 NamespacedKey 构造器直接抛
        // `Non [a-z0-9_-./] character in key 'NORTH.sidedio:item'`，
        // 结果是**面配置一格都存不下来**（存盘/关服/区块卸载时各抛一次）。
        val container = event.pdc.adapterContext.newPersistentDataContainer()
        for ((key, mode) in config.persistenceSnapshot()) {
            container.set(SidedIoKeys.key(PersistKeys.encode(key)), PersistentDataType.STRING, mode)
        }
        event.pdc.set(SidedIoKeys.SIDE_MODES, PersistentDataType.TAG_CONTAINER, container)

        // ② 机器级设置（独立容器；键本来就是合法的小写+下划线，不用编码）
        val settings = config.settings.snapshot()
        if (settings.isNotEmpty()) {
            val settingsContainer = event.pdc.adapterContext.newPersistentDataContainer()
            for ((key, value) in settings) {
                settingsContainer.set(SidedIoKeys.key(key), PersistentDataType.STRING, value)
            }
            event.pdc.set(SidedIoKeys.MACHINE_SETTINGS, PersistentDataType.TAG_CONTAINER, settingsContainer)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private fun onBreak(event: RebarBlockBreakEvent) = unregister(event.rebarBlock)

    @EventHandler(priority = EventPriority.MONITOR)
    private fun onUnload(event: RebarBlockUnloadEvent) = unregister(event.rebarBlock)

    /**
     * **区块卸载 → 清掉这个区块里的原版容器缓存。**
     *
     * ⚠️ 这条以前**根本没有** —— 于是 `VanillaSideStore.unload(...)` 从来没被调用过，
     * 原版容器的面模式缓存（还有 `VanillaSides` 的"是不是容器"缓存）**只增不减**，
     * 一个大服跑久了就是内存泄漏。现在按区块整块清。
     *
     * 清的是三份按坐标的缓存，键格式里都带 `世界:区块X:区块Z:` 前缀：
     * [VanillaSideStore]（面模式）、`VanillaSides`（是不是容器）、
     * [VanillaFaceSlots]（原版的"面 → 槽号"，只用于推导**出厂方向**）。
     *
     * 为什么挂在 [SidedIoRegistry] 上而不是新起一个 Listener：
     * 它已经是注册好的 [Listener]（见 `SidedIoAddon.onEnable`），
     * 而这条逻辑本来就是"清缓存"，和 [onUnload] / [onBreak] 是同一类事。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    private fun onChunkUnload(event: ChunkUnloadEvent) {
        val world = event.world.name
        val cx = event.chunk.x
        val cz = event.chunk.z
        VanillaSideStore.unloadChunk(world, cx, cz)
        VanillaSides.forgetChunk(world, cx, cz)
        VanillaFaceSlots.forgetChunk(world, cx, cz)
    }
}
