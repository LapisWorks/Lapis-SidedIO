package top.mc506lw.rebar.sidedio

import org.bukkit.Material
import org.bukkit.NamespacedKey

/**
 * **通道类型** —— 一条通道里流的是什么。
 *
 * ## 为什么要有这个东西
 *
 * 面配置面板如果只画六个面，玩家只能表达"这个面用来进/出" ——
 * 但一台真实机器有**好几条互不相干的通道**：
 *
 * ```
 * 熔炉    ← 输入（矿石）/ 燃料（煤）/ 输出（成品）
 * 发电机  ← 燃料 / 能量 / 输出（废料）
 * 离心机  ← 输入（矿浆）/ 流体输出 / 能量
 * 未来的  ← 原力 / 气体 / 热量 / 应力 / 魔力 …
 * ```
 *
 * 这些通道**方向相同但内容不同**，玩家需要分别控制。比如"能量只准从顶面抽走，
 * 但燃油可以从任意面灌进来" —— 只按面配是表达不了的。
 *
 * 所以面板的模型是 **（通道 × 面）** 两张维度的格子，而不是一张。
 *
 * ## 为什么类型是"可注册的"而不是一个写死的枚举
 *
 * 因为**生态是会长的**。今天写死 `ITEM / FLUID / ENERGY / GAS` 四个，
 * 明天有人做了一台"原力压缩机"，就只能改 Sided-IO 的源码 —— 这不可接受：
 * Sided-IO 是个基础设施库，它**不该知道**生态里将来有什么。
 *
 * 所以类型是个**开放注册表**：核心提供 `item / fluid / energy / gas`，
 * 第三方附属可以 `ChannelTypes.register(...)` 加自己的 `force`，并给图标和颜色。
 * 面板、WAILA、日志全都只看类型给的图标/颜色/翻译键，
 * **不认识新类型也照常能画**（用兜底图标）。
 *
 * ## 稳定性契约
 *
 * [id] 是全生态共用的标识符（会出现在日志、命令输出、以后的 API 返回值里）。
 * 一旦发布就不要改 —— 别人可能在按它做判断。注册时撞 id 会被拒绝。
 *
 * @property id         全局唯一标识，`<命名空间>:<名字>` 形式（例如 `sidedio:item`、`mymod:force`）。
 * @property icon       面板里代表这个通道的图标。用 [Material] 而不是自定义贴图 ——
 *                      第三方附属不必为了加个类型就打包贴图资源。
 * @property color      主题色（TE 那套约定的扩展：物品=白、流体=蓝、能量=红、气体=青）。
 *                      面板里给图标名和侧边条上色，玩家靠颜色区分通道种类。
 * @property maxPerFace 一个面上这个类型最多能挂几条通道。**面板决定"右侧画几格"要用到**：
 *                      熔炉的输入/输出都能挂到同一个面，但能量通常一个面只有一条。
 *                      默认 [Int.MAX_VALUE]（不限）。
 */
class ChannelType private constructor(
    val id: NamespacedKey,
    val icon: Material,
    val color: Int,
    val maxPerFace: Int = Int.MAX_VALUE,
    /**
     * 这条通道的**方向能不能由玩家配**。
     *
     * `false` 表示"面板只展示现状，点不动"。什么时候会这样：
     *
     * **能量**就是这种。Rebar 的电力是 [io.github.pylonmc.rebar.electricity.ElectricNetwork]
     * 网络模型 —— producer 送电、consumer 取电，网络自己算路径，端口是**双向**的。
     * 所以"这个面只准进 / 只准出"在电力上**根本不存在**，
     * 四态方向对它是假概念。更糟的是 Rebar 没给任何"这个面不许接线"的钩子
     * （`WireEntity.canConnect` 只看距离和遮挡；两个电力事件都不可取消），
     * 唯一杠杆是"端口实体存不存在"，而那由**机器**决定（熔炉写死顶面）。
     *
     * 结论：与其画一个改了不生效的开关，不如**如实展示**机器实际的端口在哪。
     * 详见 [EnergyPorts]。
     */
    val configurable: Boolean = true,
) {

    /** 这个类型的翻译键 —— `channel_type.<命名空间>.<名字>.name`。 */
    val nameKey: String get() = "sidedio.channel_type.${id.namespace}.${id.key}.name"

    /** 这个类型的说明翻译键。 */
    val descriptionKey: String get() = "sidedio.channel_type.${id.namespace}.${id.key}.description"

    override fun equals(other: Any?): Boolean = other is ChannelType && other.id == id
    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ChannelType(${id.namespace}:${id.key})"

    companion object {

        /** 注册工厂。第三方附属用它造自己的类型。 */
        fun of(
            id: NamespacedKey,
            icon: Material,
            color: Int,
            maxPerFace: Int = Int.MAX_VALUE,
            configurable: Boolean = true,
        ): ChannelType = ChannelType(id, icon, color, maxPerFace, configurable)

        /** 从 JVM 静态字段里反查一个类型（用于持久化还原）。 */
        internal fun known(id: NamespacedKey): ChannelType? = ChannelTypes.find(id)
    }

    /**
     * 这个类型对应的**兜底**翻译键 —— 面板在既没有类型专属翻译、
     * 又连核心翻译都没有时用它，保证玩家不会看到裸 key。
     */
    val fallbackNameKey: String get() = "sidedio.channel_type.unknown"
}

/**
 * `ChannelType` 的**全局注册表**。
 *
 * ## 什么时候注册
 *
 * 第三方附属在自己的 `onEnable` 里注册，**早于**任何机器被放下来。Sided-IO 的
 * `onEnable` 里注册了核心的四类，所以核心类型永远可用。
 *
 * ## 什么时候读
 *
 * - 机器 `attach()` 时**不读** —— 通道自己带着 [ChannelType] 实例，不用查表。
 *   这样即使注册表还没建好（加载顺序问题），机器也能正常建通道。
 * - **读盘还原**时读 —— 存的是 `id` 字符串，得反查成实例。
 *   查不到（附属被卸载了 / 类型被改名了）就走 [UNKNOWN] 兜底，而不是让方块加载失败。
 *
 * ## 为什么不用 `ServiceLoader` / SPI
 *
 * 因为 Bukkit 附属本来就有明确的 `onEnable` 时序，注册表是进程内单例，
 * 用显式 `register()` 比 SPI 少一层"什么时候扫 classpath"的不确定性。
 * 而且 SPI 没法表达"同 id 撞车要拒绝"这种运行时约束。
 */
object ChannelTypes {

    private val registry = LinkedHashMap<NamespacedKey, ChannelType>()

    /** 核心类型：**物品**。熔炉的输入/输出、箱子、合成器都是这个。 */
    val ITEM: ChannelType = register(
        id = SidedIoKeys.key("item"),
        icon = Material.CHEST,
        color = 0xE0E0E0,          // 白 —— 对齐 TE 里"物品"没有专属色的习惯
    )

    /** 核心类型：**流体**。桶、管道、储罐。 */
    val FLUID: ChannelType = register(
        id = SidedIoKeys.key("fluid"),
        icon = Material.WATER_BUCKET,
        color = 0x4A9EFF,          // 蓝 —— 流体的通用色
    )

    /** 核心类型：**能量**。发电机、用电机器的电口。 */
    val ENERGY: ChannelType = register(
        id = SidedIoKeys.key("energy"),
        icon = Material.REDSTONE_BLOCK,
        color = 0xE04A4A,          // 红 —— 红石 = 能量
        // ⚠️ 能量是**不可配**的：它走 Rebar 的电网络，没有"方向"这个概念，
        // 而且 Rebar 没给"这个面不许接线"的钩子。面板只如实展示端口在哪。
        // 详见 [ChannelType.configurable] 和 [EnergyPorts]。
        configurable = false,
    )

    /** 核心类型：**气体**。化工类机器。 */
    val GAS: ChannelType = register(
        id = SidedIoKeys.key("gas"),
        icon = Material.GLASS_BOTTLE,
        color = 0x7FE0C0,          // 青绿 —— 和流体蓝拉开区分
    )

    /**
     * 兜底类型：**未知**。
     *
     * 只在"读盘时反查不到 id"时用（附属被拆了、类型改名了）。
     * 它**不出现在注册表里**（`find` 查不到），所以不会占掉任何 id。
     */
    val UNKNOWN: ChannelType = ChannelType.of(
        id = SidedIoKeys.key("unknown"),
        icon = Material.BARRIER,
        color = 0x9E9E9E,
    )

    /**
     * 注册一个类型。
     *
     * @throws IllegalArgumentException id 已被占用（**不覆盖**：静默覆盖会让
     *         两个附属上同一个 id、其中一个的行为莫名其妙地变了）。
     */
    fun register(
        id: NamespacedKey,
        icon: Material,
        color: Int,
        maxPerFace: Int = Int.MAX_VALUE,
        configurable: Boolean = true,
    ): ChannelType {
        require(!registry.containsKey(id)) {
            "ChannelType ${id.namespace}:${id.key} 已经被注册过了"
        }
        val type = ChannelType.of(id, icon, color, maxPerFace, configurable)
        registry[id] = type
        return type
    }

    /** 注册一个第三方类型（给附属用的入口，和 [register] 等价但语义更明确）。 */
    fun register(type: ChannelType): ChannelType {
        require(!registry.containsKey(type.id)) {
            "ChannelType ${type.id.namespace}:${type.id.key} 已经被注册过了"
        }
        registry[type.id] = type
        return type
    }

    /**
     * 按 id 反查。查不到返回 null —— **不要**在这里兜底成 [UNKNOWN]，
     * 因为调用方可能想区分"没有这个类型"和"类型叫 unknown"。
     */
    fun find(id: NamespacedKey): ChannelType? = registry[id]

    /** 按 `namespace:key` 字符串反查（持久化还原用）。 */
    fun find(id: String): ChannelType? {
        val key = NamespacedKey.fromString(id) ?: return null
        return registry[key]
    }

    /** 反查，查不到就 [UNKNOWN]（读盘路径专用）。 */
    fun findOrUnknown(id: String): ChannelType = find(id) ?: UNKNOWN

    /** 当前已注册的所有类型（按注册顺序）。 */
    fun all(): List<ChannelType> = registry.values.toList()

    /** 已注册的类型数量（调试 / 测试用）。 */
    val size: Int get() = registry.size

    /**
     * 测试用：清空到"只剩核心四类"的状态。
     *
     * 不暴露给生产代码 —— 注册表在进程里是单例，运行时清空会让所有
     * 已经建好的通道指向"注册表里没有的类型"，属于自毁。
     */
    internal fun resetToCore() {
        registry.clear()
        register(ITEM.id, ITEM.icon, ITEM.color)
        register(FLUID.id, FLUID.icon, FLUID.color)
        register(ENERGY.id, ENERGY.icon, ENERGY.color, configurable = ENERGY.configurable)
        register(GAS.id, GAS.icon, GAS.color)
    }
}
