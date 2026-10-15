package top.mc506lw.rebar.sidedio.vanilla

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.WorldlyContainer
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.CraftWorld
import top.mc506lw.rebar.sidedio.SIDED_FACES
import java.util.concurrent.ConcurrentHashMap

/**
 * 原版方块**每个面能碰哪些槽** —— 直接问 NMS。
 *
 * ## 为什么必须读 NMS，而不是手抄一张表
 *
 * 原版把"哪个面能碰哪些槽"写在 **`WorldlyContainer.getSlotsForFace(Direction)`** 里，
 * 由**方块实体自己**实现（`AbstractFurnaceBlockEntity` / `BrewingStandBlockEntity` …）。
 * 手抄一张表有两个问题：
 *
 * 1. 每个 MC 版本都可能调整（新方块、新面规则）；
 * 2. 漏一个方块就是**静默**错 —— 没有编译错误、没有运行时报错，只是行为不对。
 *
 * 直接问它，**永远和当前跑的版本一致**。
 *
 * ## 这一层**只**用来推导"出厂方向"，不用来砍槽位
 *
 * ⚠️ 这是个踩过的坑。原版 `getSlotsForFace` 会给每个面一份槽号列表
 * （熔炉：`上={进料}` / `侧={燃料}` / `下={成品,燃料}`）。
 * 我一度把它当成"这个面能碰哪些槽"直接砍槽位 —— 结果是
 * **熔炉侧面只能碰燃料槽，矿石根本塞不进去**（用户实测报的"侧面无法输入物品"）。
 *
 * 而 Iron Furnaces 那类模组的做法是"**进料不分面**"：它的
 * `TileEntityInventory.canPlaceItemThroughFace(...)` **忽略 direction**，
 * 任何面都能塞进料，只有"抽取"才分面。
 *
 * 所以现在的分工是：
 *
 * | 用途 | 谁做 |
 * |---|---|
 * | **推导出厂方向**（哪一面默认是进 / 出 / 双向） | [VanillaSides.suggestedDefaults] 用本类 |
 * | **决定"这一面能碰哪些槽"** | `VanillaSides.slotsFor`，**只看方向 + Rebar 组语义** |
 *
 * 也就是：本类回答"原版**建议**这个面怎么用"，**不**回答"这个面**只能**怎么用"。
 *
 * ## 为什么不是所有方块都问得到
 *
 * `getSlotsForFace` 挂在 `WorldlyContainer` 上，而实现它的只有**方块实体**。
 * 返回 `null` = "这个方块不是 `WorldlyContainer`"，调用方应退回"不看面"的旧行为。
 *
 * ## 为什么用直接调用而不是反射
 *
 * 本工程走 `paperweight-userdev` + Mojang 映射，NMS 在编译期就是可用的
 * （见 `build.gradle.kts` 的 `paperweight.paperDevBundle`），产物会被 reobf 成
 * 生产映射，和服务器上跑的那份对得上。
 *
 * 反射反而更脆：Rebar 或原版一改名就**静默失效**（拿不到面规则，悄悄退回旧行为）。
 * 直接调用会在**编译期**就炸出来。
 *
 * ## 为什么必须缓存
 *
 * [VanillaSides.suggestedDefaults] 会被 [top.mc506lw.rebar.sidedio.VanillaSideStore]
 * 问到（**每个方块一次**，结果落进它自己的默认值缓存），而那个方法内部会
 * `getState()`（构造 BlockState 快照，很贵）。不缓存的话，
 * 大管网每次重绘都会打几百次 `getBlockEntity` + `getSlotsForFace`。
 *
 * 缓存失效按**区块**整块清（[forgetChunk]，挂在 `ChunkUnloadEvent` 上）——
 * 缓存键里带着区块坐标，前缀匹配即可，不用解析坐标。
 */
internal object VanillaFaceSlots {

    /** 坐标 → 面 → 该面能碰到的槽号。空 map 表示"不是 WorldlyContainer"。 */
    private val cache = ConcurrentHashMap<String, Map<BlockFace, Set<Int>>>()

    /**
     * 缓存上限：超了就整表清空。
     *
     * ⚠️ 正常路径下 [forgetChunk] 会把卸载区块的项清掉，所以这个上限只是兜底
     * （比如插件热重载漏了一次事件）—— 有界总比无限增长好。
     */
    private const val CACHE_LIMIT = 4096

    /**
     * 每个面 → 从那一面能碰到的**槽号集合**。
     *
     * @return 不是 `WorldlyContainer`、区块没加载、或拿不到 NMS 世界时返回 `null`。
     */
    fun slotsFor(block: Block): Map<BlockFace, Set<Int>>? {
        val key = keyOf(block)
        cache[key]?.let { return it.takeIf { cached -> cached.isNotEmpty() } }

        val computed = compute(block)
        put(key, computed)
        return computed.takeIf { it.isNotEmpty() }
    }

    /**
     * 只问**一个面** —— 管道搬运走这条。
     *
     * @return `null` = 拿不到面信息（不是 `WorldlyContainer` / 区块没加载）；
     *         空 set = **这一面确实一个槽都碰不到**（调用方应收窄成"什么都不做"）。
     *         两者的区别很重要，别把空 set 当成"不知道"。
     */
    fun slotsOn(block: Block, face: BlockFace): Set<Int>? = slotsFor(block)?.get(face)

    /** 某个区块卸载了 —— 一次清掉它里面所有缓存项。 */
    fun forgetChunk(worldName: String, chunkX: Int, chunkZ: Int) {
        val prefix = "$worldName:$chunkX:$chunkZ:"
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    // ------------------------------------------------------------ 内部

    /** 真正的 NMS 查询。返回空 map 表示"不是 WorldlyContainer"。 */
    private fun compute(block: Block): Map<BlockFace, Set<Int>> {
        // ⚠️ 先查区块加载：`getBlockEntity` 在未加载区块上拿到的是 null，
        // 但我们更想明确区分"没加载"和"不是容器"。
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return emptyMap()
        val level = (block.world as? CraftWorld)?.handle ?: return emptyMap()

        val container = level.getBlockEntity(BlockPos(block.x, block.y, block.z)) as? WorldlyContainer
            ?: return emptyMap()

        val result = LinkedHashMap<BlockFace, Set<Int>>(SIDED_FACES.size)
        for (face in SIDED_FACES) {
            // Bukkit 的 BlockFace 和 NMS 的 Direction 是同一套名字（UP/DOWN/NORTH/…）
            val direction = runCatching { Direction.valueOf(face.name) }.getOrNull() ?: continue
            result[face] = runCatching { container.getSlotsForFace(direction).toSet() }
                .getOrDefault(emptySet())
        }
        return result
    }

    /** 带上限的写缓存。 */
    private fun put(key: String, value: Map<BlockFace, Set<Int>>) {
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[key] = value
    }

    /** 缓存键：带区块坐标，方便按区块整块清（格式和 `VanillaSideStore.keyOf` 一致）。 */
    private fun keyOf(block: Block): String =
        "${block.world.name}:${block.x shr 4}:${block.z shr 4}:${block.x}:${block.y}:${block.z}"
}
