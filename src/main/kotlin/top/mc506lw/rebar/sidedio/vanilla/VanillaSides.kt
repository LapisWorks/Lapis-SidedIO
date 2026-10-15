package top.mc506lw.rebar.sidedio.vanilla

import io.github.pylonmc.rebar.logistics.LogisticGroup
import io.github.pylonmc.rebar.logistics.LogisticGroupType
import io.github.pylonmc.rebar.logistics.slot.LogisticSlot
import io.github.pylonmc.rebar.logistics.slot.VanillaInventoryLogisticSlot
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import top.mc506lw.rebar.sidedio.SideMode

/**
 * 原版容器的面语义。
 *
 * ## 要解决什么问题
 *
 * 原版方块（熔炉、酿造台、箱子 …）的 [org.bukkit.inventory.Inventory] **不带任何
 * 输入/输出标记** —— 从 API 上看，熔炉的燃料槽和成品槽都是"一格物品栏"。
 * 于是任何搬运系统如果不去查外部定义，就只能"整箱当一坨"，结果就是
 * **把熔炉的进料、燃料、成品全都抽走**（这正是 0.1.59 实测踩到的坑）。
 *
 * Rebar 已经为此准备好了正确答案：[LogisticGroup.getVanillaLogisticSlots]，
 * 里面**预先写死**了一批原版方块的正确分组：
 *
 * | 方块 | 组 | 类型 |
 * |---|---|---|
 * | 熔炉 | `input`(0) / `fuel`(1) / `output`(2) | INPUT / INPUT / OUTPUT |
 * | 酿造台 | `output`(0-2) / `input`(3) / `fuel`(4) | BOTH / INPUT / INPUT |
 * | 箱子·木桶·漏斗·发射器·投掷器·潜影盒·书架 | `inventory`(全部) | BOTH |
 * | 合成器 | `inventory`(全部) | INPUT |
 *
 * 还有几个**带校验的子类**，不该被当成普通槽：
 * - `FurnaceFuelLogisticSlot` / `BrewingStandFuelLogisticSlot`：只接受燃料
 *   （`getMaxAmount` 对非燃料返回 0）。
 * - `CrafterLogisticSlot`：被禁用的槽不许写。
 * - `ChiseledBookshelfFuelLogisticSlot` / `JukeboxLogisticSlot`：各有自己的约束。
 *
 * ## 这个类做什么
 *
 * 提供一个**唯一入口** [sidesOf]，把原版方块按 Rebar 的定义拆成"哪些槽可抽 / 可插"，
 * 并且**原样保留 Rebar 给的 `LogisticSlot` 实例**（不包装、不转型）——
 * 因为燃料槽的"只收燃料"校验挂在子类的 `getMaxAmount` 上，
 * 一旦包装成普通槽，熔炉燃料槽就会变成什么都能塞的垃圾桶。
 *
 * ## 返回值语义
 *
 * - 返回非 null：这是 Rebar 认识的原版容器，按返回的分组走。
 * - 返回 null：Rebar 不认识这个方块（`getVanillaLogisticSlots` 返回空 map，
 *   或者它声明了 `NoVanillaInventoryRebarBlock`）。**调用方应退回"整箱 BOTH"
 *   的老行为** —— 不认识 ≠ 要禁掉它的搬运能力。
 */
object VanillaSides {

    /**
     * 把一个原版方块拆成"可抽 / 可插"两侧。
     *
     * @return 拆好的结果；Rebar 不认识这个方块时返回 null（见类注释）。
     */
    fun sidesOf(block: Block): VanillaSidesResult? {
        // ⚠️ 用 runCatching：getVanillaLogisticSlots 内部会 block.getState()，
        // 边界（区块正被卸载等）下可能抛异常；抛了就当作"不认识"，
        // 让调用方走兜底，不连累整条搬运链路。
        val groups = runCatching { LogisticGroup.getVanillaLogisticSlots(block) }.getOrNull()
        if (groups.isNullOrEmpty()) return null

        val extract = ArrayList<LogisticSlot>()
        val insert = ArrayList<LogisticSlot>()
        for (group in groups.values) {
            when (group.slotType) {
                LogisticGroupType.OUTPUT -> extract += group.slots
                LogisticGroupType.INPUT -> insert += group.slots
                LogisticGroupType.BOTH -> {
                    extract += group.slots
                    insert += group.slots
                }
            }
        }
        if (extract.isEmpty() && insert.isEmpty()) return null
        return VanillaSidesResult(block, extract, insert)
    }

    /**
     * 只做"这是不是一个被 Rebar 认识的原版容器"的判断，不建槽位。
     *
     * 管道用它决定"要不要把邻居当容器"。
     *
     * ⚠️ **必须缓存**：`getVanillaLogisticSlots` 内部会 `block.getState()`（构造 BlockState
     * 快照，很贵），而 [top.mc506lw.rebar.sidedio.SidedIo.canConnect] 会**每个面、每次重算**
     * 都问到它 —— 一个大管网每次重绘就是几百次 `getState()`。
     *
     * 缓存失效是**刻意宽松**的（只按坐标，超限整表清空）：查错了也不会出事 ——
     * 查错方向 ① 缓存说"是"但方块已经没了 → 管道还会再问自己的 `isContainer`，仍然不连；
     * ② 缓存说"不是"但刚放了个箱子 → Sided-IO 的默认是"放行"，管道照旧连上。
     */
    private val knownCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private const val CACHE_LIMIT = 4096

    fun isKnownContainer(block: Block): Boolean {
        val key = keyOf(block)
        knownCache[key]?.let { return it }
        val known = runCatching { LogisticGroup.getVanillaLogisticSlots(block) }
            .getOrNull()?.isNotEmpty() == true
        if (knownCache.size >= CACHE_LIMIT) knownCache.clear()
        knownCache[key] = known
        return known
    }

    /** 某个区块卸载了 —— 清掉它里面所有缓存项（见 `VanillaSideStore.unloadChunk`）。 */
    fun forgetChunk(worldName: String, chunkX: Int, chunkZ: Int) {
        val prefix = "$worldName:$chunkX:$chunkZ:"
        knownCache.keys.removeIf { it.startsWith(prefix) }
    }

    /** 缓存键：带区块坐标，方便按区块整块清（格式见 `VanillaSideStore.keyOf`）。 */
    private fun keyOf(block: Block): String =
        "${block.world.name}:${block.x shr 4}:${block.z shr 4}:${block.x}:${block.y}:${block.z}"

    /**
     * 某个原版方块按 [mode] 能碰到的槽。
     *
     * ## ⚠️ 为什么不按"面"再收窄（这里曾经收窄过 —— 那是个 bug）
     *
     * 原版的 `getSlotsForFace` 会给**每个面**一份槽号列表。熔炉是：
     * `上={进料}` / `侧={燃料}` / `下={成品,燃料}`。
     *
     * 我一度把它当成"这个面能碰哪些槽"直接拿来砍槽位 —— 结果是
     * **熔炉的侧面只能碰燃料槽，矿石根本塞不进去**（用户实测报的
     * "侧面无法输入物品"）。
     *
     * 而 Iron Furnaces 那类模组的做法不是这样。它的抽象基类
     * `TileEntityInventory` 里：
     *
     * ```java
     * // ⚠️ direction 参数被完全忽略 —— "插入"根本不看方向
     * public boolean canPlaceItemThroughFace(int i, ItemStack stack, @Nullable Direction dir) {
     *     return IisItemValidForSlot(i, stack);
     * }
     * // 只有"抽取"才分面
     * public boolean canTakeItemThroughFace(int i, ItemStack stack, Direction dir) {
     *     return IcanExtractItem(i, stack, dir);
     * }
     * ```
     *
     * 也就是"**进料不分面**"：任何面都能塞进料，只有抽成品才挑面。
     *
     * 所以这里只按**方向**选槽：面的方向由玩家配（`VanillaSideStore`），
     * 槽的归属由 Rebar 的组语义决定（[VanillaSidesResult.insert] / `.extract`）。
     * `getSlotsForFace` 只在 [suggestedDefaults] 里用来**推导出厂方向**，
     * **不再用来砍槽位**。
     */
    fun slotsFor(result: VanillaSidesResult, mode: SideMode): List<LogisticSlot> = when (mode.direction) {
        SideMode.Flow.IN -> result.insert
        SideMode.Flow.OUT -> result.extract
        SideMode.Flow.BOTH -> (result.extract + result.insert).distinct()
        SideMode.Flow.NONE -> emptyList()
    }

    /**
     * 原版方块的**默认**面模式。
     *
     * 出厂默认给 [SideMode.BOTH]（对齐"原版箱子能进能出"的直觉）。
     * 但这意味着熔炉刚放下来的时候所有面都是 BOTH —— 又回到"什么都抽"的老问题。
     * 所以 [top.mc506lw.rebar.sidedio.VanillaSideStore] 会用 [suggestedDefaults]
     * 给出一套更合理的初始值（见那里）。
     */
    val DEFAULT_MODE: SideMode = SideMode.BOTH

    /**
     * 给一个原版方块推荐的面模式。
     *
     * ## 首选：直接问原版（NMS `WorldlyContainer.getSlotsForFace`）
     *
     * 原版把"哪个面能碰哪些槽"写在各方块实体自己实现的 `getSlotsForFace` 里。
     * 所以这里的做法是：
     *
     * 1. 从 Rebar 的物流组摊平出**哪些槽号是"进"、哪些是"出"**
     *    （`INPUT` / `OUTPUT` / `BOTH` 三档）；
     * 2. 对每个面问 `getSlotsForFace` 拿到**那一面能碰到的槽号**；
     * 3. 两面交叉：碰得到"进"的槽 → 能进；碰得到"出"的槽 → 能出；
     *    两者都行 → `BOTH`；只有一边 → `INPUT` / `OUTPUT`。
     *
     * 这样**永远和当前 MC 版本一致**，不需要维护任何表。原版熔炉自然得出
     * **上=INPUT（进料）/ 侧=INPUT（燃料）/ 下=OUTPUT（成品）**。
     *
     * ⚠️ **一个槽都碰不到的"死面"刻意不返回 `NONE`** —— `NONE` 会被管道当成
     * "物理上接不上"，直接把玩家的管道断掉。原版里这种面（如果有）只是"搬了没意义"，
     * 不是"禁止连接"。`NONE` 只应该来自**玩家的显式配置**。
     *
     * ## 兜底：手写规则（NMS 问不到时）
     *
     * `getSlotsForFace` 只挂在 `WorldlyContainer` 上。拿不到时退回一张保守的手写表：
     * 有 OUTPUT 又有 INPUT 的方块（熔炉）→ **下=成品、上+四侧=进**。
     *
     * ## 现有模型表达不到的那一层
     *
     * 面模式只有 `INPUT / OUTPUT / BOTH / NONE` 四档，**区分不了
     * 「上=进料槽 / 侧=燃料槽」** —— 那需要"每个面各自一份槽位列表"
     * （NMS 那边其实已经拿到了，只是模型还没接）。
     * 好在燃料槽自带 filter（`FurnaceFuelLogisticSlot.getMaxAmount` 对非燃料返回 0），
     * 所以就算把非燃料插到侧面也会被拒，**不会真塞错**。
     */
    fun suggestedDefaults(block: Block): Map<BlockFace, SideMode>? {
        val groups = runCatching { LogisticGroup.getVanillaLogisticSlots(block) }.getOrNull()
        if (groups.isNullOrEmpty()) return null

        // ① 摊平"哪些槽号是进 / 是出"
        val insertable = HashSet<Int>()
        val extractable = HashSet<Int>()
        for (group in groups.values) {
            for (slot in group.slots) {
                // ⚠️ 只认原版库存槽（`VanillaInventoryLogisticSlot.slot` 是公开 val，
                // 不需要反射）。其余（虚拟库存槽）不参与原版方块的面推导。
                val index = (slot as? VanillaInventoryLogisticSlot)?.slot ?: continue
                if (index < 0) continue
                when (group.slotType) {
                    LogisticGroupType.INPUT -> insertable += index
                    LogisticGroupType.OUTPUT -> extractable += index
                    LogisticGroupType.BOTH -> {
                        insertable += index
                        extractable += index
                    }
                }
            }
        }

        // ② 首选：面 → 槽 直接问原版，再和"哪些槽是进 / 是出"交叉
        val perFace = runCatching { VanillaFaceSlots.slotsFor(block) }.getOrNull()
        if (perFace != null && (insertable.isNotEmpty() || extractable.isNotEmpty())) {
            return crossFaceModes(perFace, insertable, extractable)
        }

        // ③ 兜底：NMS 问不到 → 保守的手写表
        val hasOutput = groups.values.any { it.slotType == LogisticGroupType.OUTPUT }
        val hasInput = groups.values.any { it.slotType == LogisticGroupType.INPUT }
        val hasBoth = groups.values.any { it.slotType == LogisticGroupType.BOTH }

        return when {
            hasBoth -> emptyMap()  // 箱子之类：不用改，默认 BOTH 就是对的
            hasOutput && hasInput -> mapOf(
                // ⚠️ **下 = 成品**（对齐原版）。以前是 UP，正好反了。
                BlockFace.DOWN to SideMode.OUTPUT,
                // 上 + 四个侧面都是"进"（原版里上面进料、侧面进燃料）
                BlockFace.UP to SideMode.INPUT,
                BlockFace.NORTH to SideMode.INPUT,
                BlockFace.SOUTH to SideMode.INPUT,
                BlockFace.EAST to SideMode.INPUT,
                BlockFace.WEST to SideMode.INPUT,
            )
            hasOutput -> emptyMap()
            hasInput -> emptyMap()
            else -> null
        }
    }

    /**
     * 纯函数核心：把"面 → 能碰的槽号"和"哪些槽是进 / 是出"交叉成"面 → 方向"。
     *
     * 抽出来是为了**能单测** —— [suggestedDefaults] 要 `Block`，单测里造不出来，
     * 而这条交叉规则（尤其"死面不给 NONE"那一档）必须被钉死。
     *
     * @param perFace     面 → 该面能碰到的槽号（NMS `getSlotsForFace`）。
     * @param insertable  可插入的槽号（Rebar 组语义 INPUT + BOTH）。
     * @param extractable 可抽取的槽号（Rebar 组语义 OUTPUT + BOTH）。
     */
    fun crossFaceModes(
        perFace: Map<BlockFace, Set<Int>>,
        insertable: Set<Int>,
        extractable: Set<Int>,
    ): Map<BlockFace, SideMode> = perFace.mapValues { (_, slots) ->
        val canInsert = slots.any { it in insertable }
        val canExtract = slots.any { it in extractable }
        when {
            canInsert && canExtract -> SideMode.BOTH
            canInsert -> SideMode.INPUT
            canExtract -> SideMode.OUTPUT
            // ⚠️ 死面（一个可搬运的槽都碰不到）**刻意不返回 NONE** ——
            // NONE 会被管道当成"物理上接不上"，直接把玩家的管子断掉。
            // 原版里这种面只是"搬了没意义"，不是"禁止连接"。
            else -> SideMode.BOTH
        }
    }
}

/**
 * 原版方块的拆分结果：可抽的槽 + 可插的槽。
 *
 * 两侧都装着 [LogisticSlot]。**故意不丢成 `VanillaInventoryLogisticSlot`**，
 * 因为 `FurnaceFuelLogisticSlot` 之类子类的容量校验（只收燃料）
 * 是挂在 `getMaxAmount` 上的，转成父类型就把校验丢了。
 */
class VanillaSidesResult(
    val block: Block,
    /** 可抽的槽（OUTPUT + BOTH）。 */
    val extract: List<LogisticSlot>,
    /** 可插的槽（INPUT + BOTH）。 */
    val insert: List<LogisticSlot>,
) {
    val canExtract: Boolean get() = extract.isNotEmpty()
    val canInsert: Boolean get() = insert.isNotEmpty()

    /** 被识别到的槽是不是都是"普通槽"（没有特殊校验）—— 调试 / 测试用。 */
    val hasSpecialSlots: Boolean
        get() = (extract + insert).any { it.javaClass != VanillaInventoryLogisticSlot::class.java }
}
