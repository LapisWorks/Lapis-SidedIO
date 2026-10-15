package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.electricity.nodes.ElectricPortEntity
import io.github.pylonmc.rebar.entity.EntityStorage
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import kotlin.math.abs

/**
 * **能量端口的只读探针** —— 回答"这台机器的电口开在哪些面、接了几根线"。
 *
 * ## 为什么是只读的
 *
 * 因为能量**没有可配的方向**，也没法拦接线。三层原因（都查过源码）：
 *
 * 1. **语义**：Rebar 的电力是 `ElectricNetwork` 网络 —— producer 送、consumer 取，
 *    网络自己算路径。端口 `ElectricConnectorNode` 是**双向**的，
 *    "这个面只准进 / 只准出"在电力上不存在。
 * 2. **归属**：端口开在哪一面由**机器**决定（熔炉写死 `PORT_FACE = UP`）。
 *    端口位置要贴合机器模型，外部库无从得知。
 * 3. **钩子**：Rebar **没有任何**"这个面不许接线"的口子 ——
 *    `WireEntity.canConnect` 只看距离和遮挡；`RebarElectricNodeAddEvent` /
 *    `RemoveEvent` / `RebarPlayerInteractWireEvent` **都不可取消**；
 *    `WireConnectionService.onPlayerInteract` 注册在 `LOWEST`（最先跑）
 *    且在里面**直接把线连上**了。
 *
 * 所以与其画一个改了不生效的开关，不如**如实展示**现状。
 * 等 Rebar 上游加了可取消的连接事件，这里就能升级成真配置。
 *
 * ## 怎么读
 *
 * 端口实体是 `ElectricPortEntity`（一个 `ItemDisplay`），
 * 它的 `KEY` 是 public 的 `@JvmField`，`EntityStorage.getByKey` 也是 public，
 * 所以能枚举出来。位置 = `方块中心 + 面方向 × 半径 × 1.01`，
 * 反过来按主轴判断就能算出是哪个面。
 */
object EnergyPorts {

    /** 端口离方块中心多远算"属于这个方块"。半径最大 0.5，留点余量。 */
    private const val OWNERSHIP_RADIUS = 1.5

    /**
     * 这台机器**每个面接了几根线**。
     *
     * 只包含**真的有端口**的面；没端口的面不在 map 里。
     */
    fun connectedWires(block: Block): Map<BlockFace, Int> {
        val center = block.location.toCenterLocation().toVector()
        val result = HashMap<BlockFace, Int>()

        for (port in portsOf(block)) {
            val location = runCatching { port.entity.location }.getOrNull() ?: continue
            val face = faceOf(center, location.toVector()) ?: continue
            val wires = runCatching { port.connectedWires.size }.getOrDefault(0)
            result[face] = (result[face] ?: 0) + wires
        }
        return result
    }

    /** 这台机器**哪些面有电口**（不管接没接线）。 */
    fun facesWithPorts(block: Block): Set<BlockFace> = connectedWires(block).keys

    /**
     * 这台机器在 [face] 这一面的情况。
     *
     * @return 没端口返回 null；有端口返回接了几根线（0 = 有口没接线）。
     */
    fun statusOn(block: Block, face: BlockFace): Int? = connectedWires(block)[face]

    /** 这台机器**有没有**电口（面板据此决定要不要把能量列出来）。 */
    fun hasAnyPort(block: Block): Boolean = portsOf(block).isNotEmpty()

    // ------------------------------------------------------------ 内部

    private fun portsOf(block: Block): List<ElectricPortEntity> {
        val center = block.location.toCenterLocation().toVector()
        val all = runCatching { EntityStorage.getByKey(ElectricPortEntity.KEY) }.getOrNull() ?: return emptyList()
        return all.filterIsInstance<ElectricPortEntity>().filter { port ->
            val location = runCatching { port.entity.location }.getOrNull() ?: return@filter false
            location.world == block.world && location.toVector().distance(center) <= OWNERSHIP_RADIUS
        }
    }

    /**
     * 从"方块中心 → 端口"的偏移量反推是哪个面 —— 取**主轴**。
     *
     * ⚠️ 注意 Minecraft 的轴向：`NORTH = -Z`、`SOUTH = +Z`、`EAST = +X`、`WEST = -X`。
     * 搞反的话"顶面有电口"会显示成"底面"。
     */
    private fun faceOf(center: org.bukkit.util.Vector, port: org.bukkit.util.Vector): BlockFace? {
        val dx = port.x - center.x
        val dy = port.y - center.y
        val dz = port.z - center.z
        val ax = abs(dx)
        val ay = abs(dy)
        val az = abs(dz)
        return when {
            ax >= ay && ax >= az -> if (dx > 0) BlockFace.EAST else BlockFace.WEST
            ay >= az -> if (dy > 0) BlockFace.UP else BlockFace.DOWN
            else -> if (dz > 0) BlockFace.SOUTH else BlockFace.NORTH
        }
    }
}
