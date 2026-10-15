package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataType
import top.mc506lw.rebar.sidedio.vanilla.VanillaSides
import java.util.concurrent.ConcurrentHashMap

/**
 * 原版容器的"面模式"存储。
 *
 * ## 为什么原版方块不能复用 [SidedIoRegistry]
 *
 * [SidedIoRegistry] 是按 [io.github.pylonmc.rebar.block.RebarBlock] 实例存配置的 ——
 * 原版方块**根本没有 RebarBlock 实例**（`BlockStorage.get` 返回 null）。
 * 所以原版路径得另起一套：按**坐标**存。
 *
 * 而且原版方块没有 `write(pdc)` 这样的序列化钩子（那是 Rebar 方块才有的），
 * 所以持久化只能写进**方块自己的 PDC**（`Block.getState().persistentDataContainer`）——
 * 原版方块在 Paper 上也有 PDC，而且**写回时要 `state.update()`**，
 * 否则改的只是个副本，方块本身没变。
 *
 * ## 为什么按坐标的 map 还是必要的
 *
 * PDC 是磁盘上的真相，但每次右键都读一次 NBT 不划算（`getState()` 会构造一个
 * BlockState 快照，很贵）。所以在内存里缓存一份"坐标 → 面模式"，
 * 写的时候同时落 PDC。
 *
 * ## 生命周期
 *
 * | 时机 | 做什么 |
 * |---|---|
 * | 右键切面 | 更新内存缓存 + 写 PDC |
 * | 区块卸载 | （可选）清掉内存缓存 —— PDC 里已经存好了 |
 * | 区块加载 | 不用主动做 —— 第一次访问时按需从 PDC 读 |
 */
object VanillaSideStore {

    /** 坐标 → 面模式。只缓存"玩家真的调过"的方块。 */
    private val cache = ConcurrentHashMap<String, Map<BlockFace, SideMode>>()

    /**
     * 坐标 → **出厂默认面模式**（[VanillaSides.suggestedDefaults] 的结果）。
     *
     * ⚠️ **必须缓存**：`suggestedDefaults` 内部走
     * `LogisticGroup.getVanillaLogisticSlots` → `block.getState()`，
     * 那是构造一个 BlockState 快照（很贵）；而 [modeOf] 会被管道**每面每次重算**调到。
     * 不缓存的话，一个大管网每次重绘都会打几百次 `getState()`。
     */
    private val defaultCache = ConcurrentHashMap<String, Map<BlockFace, SideMode>>()

    /**
     * 缓存上限：超了就整表清空。
     *
     * ⚠️ 为什么要这个：`unload(...)` **目前没有任何调用方**（Sided-IO 没挂区块卸载监听），
     * 所以缓存只增不减。加个上限至少让它有界，不至于把服跑穿。
     * 正经修法是挂 `ChunkUnloadEvent`，留待以后。
     */
    private const val CACHE_LIMIT = 4096

    /** 读一个原版方块的某个面的模式。 */
    fun modeOf(block: Block, face: BlockFace): SideMode {
        val key = keyOf(block)
        // ⚠️ **空结果也要缓存** —— 以前是 `if (it.isNotEmpty())`，于是"玩家没配过"
        // 这个最常见的情况**每次都要重新读一遍 PDC**（`block.state` 会构造快照，很贵），
        // 而管道每面每次重算都会问到它。`setMode` 会覆盖这条缓存，所以缓存空值不会丢配置。
        val modes = cache[key] ?: readFromPdc(block).also { put(cache, key, it) }
        return modes[face] ?: defaultsOf(block, key)[face] ?: VanillaSides.DEFAULT_MODE
    }

    /**
     * 出厂默认面模式。
     *
     * ⚠️ 这里以前是**直接返回 [VanillaSides.DEFAULT_MODE]（BOTH）** —— 于是
     * `VanillaSides.suggestedDefaults` 变成**死代码**（全项目没有调用方），
     * 文档承诺的"熔炉顶面输出、其余输入"从来没生效过。
     */
    private fun defaultsOf(block: Block, key: String): Map<BlockFace, SideMode> {
        defaultCache[key]?.let { return it }
        val computed = runCatching { VanillaSides.suggestedDefaults(block) }.getOrNull() ?: emptyMap()
        put(defaultCache, key, computed)
        return computed
    }

    /** 带上限的写缓存。 */
    private fun <V> put(map: MutableMap<String, V>, key: String, value: V) {
        if (map.size >= CACHE_LIMIT) map.clear()
        map[key] = value
    }

    /** 写一个原版方块某个面的模式；同时落 PDC。 */
    fun setMode(block: Block, face: BlockFace, mode: SideMode) {
        val key = keyOf(block)
        val modes = HashMap(cache[key] ?: readFromPdc(block))
        modes[face] = mode
        cache[key] = modes
        writeToPdc(block, modes)
    }

    /**
     * 循环切换并返回新模式。
     *
     * ⚠️ **只在内置四档里转**（[SideMode.BUILTIN]）。
     *
     * 原版容器不归任何附属管，所以别的附属注册的自定义档
     * （例如铁熔炉的「燃料输入/弹出」）在这里**没有意义** ——
     * 让玩家对着一个箱子按扳手切到"燃料输入/弹出"是说不通的。
     * 自定义档要机器自己用 `SideConfigBuilder.extraFaceModes` 声明才生效。
     */
    fun cycleMode(block: Block, face: BlockFace): SideMode {
        val next = modeOf(block, face).next(SideMode.BUILTIN)
        setMode(block, face, next)
        return next
    }

    /** 反向循环切换（同样只在内置四档里转，见 [cycleMode]）。 */
    fun cycleModeBackwards(block: Block, face: BlockFace): SideMode {
        val previous = modeOf(block, face).previous(SideMode.BUILTIN)
        setMode(block, face, previous)
        return previous
    }

    /** 这个原版方块有没有被玩家改过面配置。 */
    fun isCustomised(block: Block): Boolean = readFromPdc(block).isNotEmpty()

    /** 这块方块所在的区块卸载了 —— 清缓存。 */
    fun unload(block: Block) {
        val key = keyOf(block)
        cache.remove(key)
        defaultCache.remove(key)
    }

    /**
     * 某个区块卸载了 —— 一次清掉它里面**所有**缓存项。
     *
     * ⚠️ 以前只有 [unload]（按方块），而它**没有任何调用方** ——
     * 于是缓存只增不减。正解就是这条：挂 `ChunkUnloadEvent`
     * （见 [SidedIoRegistry.onChunkUnload]）。
     *
     * 靠 [keyOf] 的键格式（前面带 `区块X:区块Z:`）做前缀匹配，不用把坐标解析回来。
     */
    fun unloadChunk(worldName: String, chunkX: Int, chunkZ: Int) {
        val prefix = "$worldName:$chunkX:$chunkZ:"
        cache.keys.removeIf { it.startsWith(prefix) }
        defaultCache.keys.removeIf { it.startsWith(prefix) }
    }

    /** 某个方块当前所有面的模式（给 GUI 用）。 */
    fun faceModes(block: Block): Map<BlockFace, SideMode> =
        SIDED_FACES.associateWith { modeOf(block, it) }

    // ------------------------------------------------------------ PDC 读写

    private fun readFromPdc(block: Block): Map<BlockFace, SideMode> {
        // ⚠️ 用 runCatching：区块未加载时 getState() 会抛异常。
        // 抛了就当作"没配过"（返回默认），不连累上层。
        return runCatching {
            // ⚠️ PDC 挂在 `TileState` 上，**不是** `BlockState` ——
            // 熔炉 / 箱子 / 酿造台这些方块实体都是 TileState，但 `BlockState`
            // 这个基接口没有 `getPersistentDataContainer()`。转不过去就说明
            // 这个方块没有方块实体（也就存不了配置），返回空。
            val tile = block.state as? TileState ?: return emptyMap()
            val container = tile.persistentDataContainer
                .get(SidedIoKeys.SIDE_MODES, PersistentDataType.TAG_CONTAINER)
                ?: return emptyMap()
            val result = HashMap<BlockFace, SideMode>()
            for (face in SIDED_FACES) {
                container.get(modeKey(face), PersistentDataType.STRING)?.let {
                    result[face] = SideMode.fromName(it)
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun writeToPdc(block: Block, modes: Map<BlockFace, SideMode>) {
        runCatching {
            // ⚠️ 必须取 state 的**可变副本**再 update 回去。
            // `block.state` 每次调用都返回一个新快照，改它不影响世界里的方块 ——
            // 这是原版 PDC 最容易踩的坑（"存了但重启就没了"）。
            val tile = block.state as? TileState ?: return
            val adapterContext = tile.persistentDataContainer.adapterContext
            val container = adapterContext.newPersistentDataContainer()
            for ((face, mode) in modes) {
                container.set(modeKey(face), PersistentDataType.STRING, mode.id)
            }
            tile.persistentDataContainer.set(
                SidedIoKeys.SIDE_MODES, PersistentDataType.TAG_CONTAINER, container
            )
            // applyPhysics = false：改 PDC 不该触发原版物理更新（否则会连累红石）
            tile.update(true, false)
        }
    }

    private fun modeKey(face: BlockFace) = SidedIoKeys.key("side_${face.name.lowercase()}")

    /**
     * 缓存键。
     *
     * ⚠️ 格式里**带上区块坐标**（`x shr 4` / `z shr 4`）—— 这样
     * [unloadChunk] 用 `startsWith("世界:区块X:区块Z:")` 就能一次清掉整块，
     * 不用把坐标解析回来（解析要防脏数据，很啰嗦）。
     */
    private fun keyOf(block: Block): String =
        "${block.world.name}:${block.x shr 4}:${block.z shr 4}:${block.x}:${block.y}:${block.z}"
}

/**
 * 让原版容器也走 Sided-IO 那一套的桥接层。
 *
 * 这个类做的只有一件事：**把"这个原版方块的面配置"接到 [SidedIoAddon] 的事件流里**。
 * 真正的语义计算在 [VanillaSides]。
 *
 * ## 与 Rebar 机器路径的分工
 *
 * ```
 * 右键一个方块
 *   ├── 是 Rebar 机器？ → SidedIoRegistry.get(block) → SideConfig（开发者 attach() 注册的组）
 *   └── 是原版容器？    → VanillaSideStore（按坐标缓存 + 方块 PDC）
 * ```
 */
object VanillaSidesBridge {

    /**
     * 插件启动钩子。
     *
     * 这里**不做**"遍历全世界把已有容器登记一遍"这种重活 ——
     * 原版方块的面配置是按需读的（[VanillaSideStore.modeOf] 第一次访问时查 PDC），
     * 所以启动阶段什么都不用预热。
     *
     * 保留这个方法是为了给 [SidedIoAddon.onEnable] 一个明确的语义落点，
     * 将来若要加"启动时校验老 PDC 数据"也有地方放。
     */
    fun initialize() {
        // 原版路径完全按需，无需预热。
    }

    /** 一个方块是不是"我们管得着的原版容器"。 */
    fun isManagedVanilla(block: Block): Boolean {
        if (!block.position.isChunkLoaded) return false
        // Rebar 机器走另一条路，别在这里抢
        if (BlockStorage.get(block) != null) return false
        return VanillaSides.isKnownContainer(block)
    }
}
