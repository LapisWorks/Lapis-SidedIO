package top.mc506lw.rebar.sidedio

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 与 [RebarWrench](https://github.com/lijinhong11/RebarWrench) 的软桥接。
 *
 * ## 为什么走反射，而不是 `compileOnly` 直接调
 *
 * RebarWrench 是**生态里的通用扳手**（其他附属都拿它配面），所以 Sided-IO 必须能对接它。
 * 但它有两个包袱：
 *
 * 1. **硬依赖 Pylon**：它的 `plugin.yml` 是 `depend: [Rebar, Pylon]`。
 *    Sided-IO 的定位是"只依赖 Rebar 的纯库"，不该为了把扳手而拖进 Pylon。
 * 2. **GPL-3.0**：Sided-IO 要发布的话，直接静态链接 GPL 代码有许可摩擦。
 *
 * 所以这里**不在 Gradle 里写 `implementation`，也不在 plugin.yml 里写 `depend`**，
 * 而是运行时检查 RebarWrench 在不在，在就用反射注册一个 [Wrenchable] 代理。
 * 它不在，Sided-IO 照常用（面配置面板 + 熔炉 GUI 按钮），只是没有"拿扳手右键切面"这个快捷手势。
 *
 * ## 手势映射（RebarWrench 定的）
 *
 * | 手势 | [WrenchAction] |
 * |---|---|
 * | 右键 | `CONFIGURE` ← **我们只认这个：切面模式** |
 * | 潜行 + 右键 | `ROTATE` |
 * | 潜行 + 左键 | `FAST_BREAK` |
 *
 * 返回 `ROTATE` / `FAST_BREAK` 的 `UNSUPPORTED`，把这两个手势留给机器自己
 * （比如以后熔炉想用 `ROTATE` 转朝向）。
 *
 * ## 为什么注册一个"万能" [Wrenchable] 而不是逐类注册
 *
 * RebarWrench 的 `registerWrenchable(Class, Wrenchable)` 是**按方块类**查表的。
 * 但 Sided-IO 管的面配置是**按实例**的（`SidedIoRegistry` 是 IdentityHashMap），
 * 而且同一台机器的面配置可能来自任意多个附属 —— 逐个类注册等于要枚举全生态的机器类型。
 *
 * 所以这里**不表态支持任何具体类**，而是注册一个"什么都接"的 Wrenchable：
 * 反正 `onWrenchInteract` 里会查 `SidedIoRegistry`，不是我们管的东西就返回 `UNSUPPORTED`。
 *
 * 但 RebarWrench 的查表是 `getWrenchable(rb.getClass())` —— **精确类匹配，不走父类**。
 * `AbstractIronFurnace` 的六个子类各是一个类，我们没法预先知道。所以这个桥还有个
 * 辅助手段：[registerClass] 让附属主动把自己注册进来（可选，做了更稳）。
 */
object WrenchBridge {

    /** RebarWrench 主类（从它这里取静态方法）。 */
    private const val PLUGIN_CLASS = "io.github.lijinhong11.rebarwrench.RebarWrench"

    /** 注册接口全名，用于反射找方法。 */
    private const val REGISTER_METHOD = "registerWrenchable"

    /** 反射出来的 `Wrenchable` 接口类型（拿来做 Proxy）。 */
    private var wrenchableInterface: Class<*>? = null

    /** 反射出来的 `WrenchResult` 枚举常量：SUCCESS / UNSUPPORTED / FAILED。 */
    private var resultSuccess: Any? = null
    private var resultUnsupported: Any? = null

    /** `WrenchAction` 枚举常量，用来比对 action 名字。 */
    private var actionConfigure: Any? = null

    /** RebarWrench 在不在（决定附件的 plugin.yml 描述和日志）。 */
    var available: Boolean = false
        private set

    /**
     * 等 RebarWrench 就绪后要注册的机器类。
     *
     * 为什么需要缓冲：附属的 `onEnable` 顺序不一定在 [hook] 之后 —— RebarWrench 是
     * **软依赖**，Bukkit 只保证硬依赖的顺序。如果某个附属先于 Sided-IO 启用了，
     * 它调的 [registerClass] 会落在这里，等 [hook] 成功再统一补注册。
     */
    private val pendingClasses = LinkedHashSet<Class<*>>()

    /**
     * 尝试接上 RebarWrench。装着就注册代理，没装就静默跳过。
     *
     * 在 [SidedIoAddon.onEnable] 里调。**顺序很重要**：必须等 RebarWrench 自己
     * `onEnable` 完（它那时才 `registerWithRebar()` 并建好查表），
     * 所以这里由 RebarWrench 的 `depend` 关系保证 —— 它先启用。
     */
    fun hook() {
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RebarWrench") ?: run {
            available = false
            return
        }
        // 插件在场还不够：得确认它的类真的能加载（版本不匹配 / 编译差异会在这里炸）
        val pluginClass = runCatching { Class.forName(PLUGIN_CLASS) }.getOrNull() ?: run {
            available = false
            return
        }

        runCatching {
            val api = Class.forName("io.github.lijinhong11.rebarwrench.api.Wrenchable")
            val resultClass = Class.forName("io.github.lijinhong11.rebarwrench.api.WrenchResult")

            wrenchableInterface = api
            resultSuccess = resultClass.getField("SUCCESS").get(null)
            resultUnsupported = resultClass.getField("UNSUPPORTED").get(null)
            actionConfigure = Class.forName("io.github.lijinhong11.rebarwrench.api.WrenchAction")
                .getField("CONFIGURE").get(null)

            // 扳手的 PDC 键：RebarWrench 在 onLoad 里建 `new NamespacedKey(this, "wrench")`
            wrenchItemKey = runCatching {
                pluginClass.getField("WRENCH_KEY").get(null) as? org.bukkit.NamespacedKey
            }.getOrNull()

            val proxy = Proxy.newProxyInstance(
                api.classLoader,
                arrayOf(api),
                WrenchableProxy
            )

            // RebarWrench.registerWrenchable(Class, Wrenchable)
            val register = pluginClass.getMethod(
                REGISTER_METHOD,
                Class::class.java,
                api
            )

            // 把已知的机器类都挂上（RebarWrench 精确查类，不走父类）。
            // pendingClasses 里是附属在 hook 之前就报上来的。
            for (clazz in pendingClasses) {
                register.invoke(null, clazz, proxy)
            }
            // 再挂一个兜底条目（用 RebarBlock 本身）—— 有些 RebarWrench 版本查表会回落到父类
            runCatching {
                Class.forName("io.github.pylonmc.rebar.block.RebarBlock")
            }.getOrNull()?.let { register.invoke(null, it, proxy) }

            available = true
            plugin.logger.info(
                "[Sided-IO] 已对接 RebarWrench：拿扳手右键一个面即可切换模式" +
                    "（先在待注册队列里收了 ${pendingClasses.size} 个机器类）"
            )
        }.onFailure { e ->
            available = false
            plugin.logger.warning("[Sided-IO] RebarWrench 对接失败（不影响面配置本体）：${e.message}")
        }
    }

    /** 手动注销（目前 RebarWrench 没有反注册 API，留作扩展点）。 */
    fun unhook() {
        available = false
    }

    /**
     * 把一个机器类注册到 RebarWrench 上，让它能被扳手右键配面。
     *
     * ⚠️ **附属必须调这个**（或通过 [SidedIo.registerWrenchTarget]），
     * 因为 RebarWrench 的查表是 `getWrenchable(block.getClass())` —— **精确类匹配，不走父类**。
     * Sided-IO 不认识具体附属的机器类型，没法替它们枚举。
     *
     * 没装 RebarWrench 时静默跳过（[available] 为 false）。
     *
     * ```kotlin
     * class MyAddon : JavaPlugin(), RebarAddon {
     *     override fun onEnable() {
     *         SidedIo.registerWrenchTarget(MyMachine::class.java)
     *     }
     * }
     * ```
     */
    fun registerClass(clazz: Class<*>): Boolean {
        // 附属可能比 Sided-IO 先启用（软依赖不保证顺序）→ 先排队，等 hook 时补注册
        if (!available) {
            pendingClasses += clazz
            return false
        }
        return runCatching {
            val api = wrenchableInterface ?: return@runCatching false
            val pluginClass = Class.forName(PLUGIN_CLASS)
            val proxy = Proxy.newProxyInstance(api.classLoader, arrayOf(api), WrenchableProxy)
            pluginClass.getMethod(REGISTER_METHOD, Class::class.java, api)
                .invoke(null, clazz, proxy)
            registeredCount++
            true
        }.getOrElse { e ->
            java.util.logging.Logger.getLogger("SidedIO")
                .warning("[Sided-IO] 给 ${clazz.simpleName} 注册扳手目标失败：${e.message}")
            false
        }
    }

    /** 成功登记到 RebarWrench 的机器类数量（调试 / 验证用，见 [logSummary]）。 */
    private var registeredCount = 0

    /** 把"登记了几台机器"打到日志里 —— 启动后一眼确认扳手到底认不认这些机器。 */
    fun logSummary() {
        if (!available) return
        java.util.logging.Logger.getLogger("SidedIO").info(
            "[Sided-IO] 扳手已登记 $registeredCount 个机器类" +
                if (registeredCount == 0) "（暂时没有附属上报机器类型；装了附属后会补登记）" else ""
        )
    }

    /**
     * 直接读 RebarWrench 的注册表，返回可读的几行文本。
     *
     * 用来**验证**登记是否真的落地：注册表是 RebarWrench 内部的
     * `HashMap<Class<? extends RebarBlock>, Wrenchable>`，只能反射拿。
     * 由 `/sidedio wrench` 调用（控制台也可用）。
     */
    fun debugRegistry(): List<String> {
        if (!available) return listOf("§cRebarWrench 未安装 / 未对接")
        return runCatching {
            val pluginClass = Class.forName(PLUGIN_CLASS)
            val field = pluginClass.declaredFields.firstOrNull { java.util.Map::class.java.isAssignableFrom(it.type) }
                ?: return@runCatching listOf("§c没找到注册表字段（RebarWrench 版本不匹配？）")
            field.isAccessible = true
            val map = field.get(null) as? Map<*, *>
                ?: return@runCatching listOf("§c注册表为 null")

            val mine = map.keys.filter { key ->
                (key as? Class<*>)?.name?.contains("IronFurnace", ignoreCase = true) == true
            }
            buildList {
                add("§7类登记总数：§f${map.size}")
                add("§7其中熔炉：§f${mine.size}")
                // 先把所有键打出来，方便确认到底登记了哪些类
                map.keys.mapNotNull { it as? Class<*> }
                    .sortedBy { it.name }
                    .forEach { add("  §8- §f${it.name}") }
                val fallback = map.keys.any { (it as? Class<*>)?.name == "io.github.pylonmc.rebar.block.RebarBlock" }
                add("§7兜底登记（RebarBlock 基类）：§f$fallback")
            }
        }.getOrElse { e -> listOf("§c读取注册表失败：${e.javaClass.simpleName}: ${e.message}") }
    }

    /**
     * 玩家手里这把是不是 RebarWrench 扳手。
     *
     * 机器自己的"右键开界面"逻辑必须靠它让路 —— 拿扳手右键是**配面**手势，
     * 不该把机器 GUI 弹出来（两个功能抢同一个事件）。
     *
     * 判据是 RebarWrench 自己定的 PDC 键：`rebarwrench:wrench`（见其 `RebarWrench.WRENCH_KEY`）。
     * 用键而不是物品类型：RebarWrench 是拿 `CLAY_BALL` 改的 `ITEM_MODEL`，
     * 从 `Material` 上认不出来。
     */
    fun isWrench(stack: org.bukkit.inventory.ItemStack?): Boolean {
        if (stack == null) return false
        val key = wrenchItemKey ?: return false
        return runCatching {
            stack.itemMeta?.persistentDataContainer?.has(key) == true
        }.getOrDefault(false)
    }

    /** RebarWrench 的扳手 PDC 键 `rebarwrench:wrench`，hook 成功后才有值。 */
    private var wrenchItemKey: org.bukkit.NamespacedKey? = null

    /**
     * 动态代理：把 RebarWrench 的 `onWrenchInteract(Player, RebarBlock, WrenchAction)`
     * 转到 Sided-IO 的切面逻辑上。
     */
    private object WrenchableProxy : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // 只处理 onWrenchInteract，其余（toString/hashCode/…）走默认
            if (method.name != "onWrenchInteract" || args == null || args.size < 3) {
                return defaultFor(method, proxy, args)
            }

            val player = args[0] as? Player ?: return resultUnsupported
            val rebar = args[1] ?: return resultUnsupported
            val action = args[2] ?: return resultUnsupported

            // 只认 CONFIGURE（普通右键）。ROTATE / FAST_BREAK 留给机器自己。
            if (action != actionConfigure) return resultUnsupported

            return handleConfigure(player, rebar)
        }

        private fun handleConfigure(player: Player, rebar: Any): Any? {
            val block = runCatching {
                rebar.javaClass.getMethod("getBlock").invoke(rebar) as? Block
            }.getOrNull() ?: return resultUnsupported

            SidedIoRegistry.get(block) ?: return resultUnsupported

            val face = targetFace(player) ?: return resultUnsupported
            // 走"最宽类型"入口：拿扳手右键时玩家心里想的是"这个面进/出料"，
            // 而不是"北面的物品通道"（那是打开面板才该关心的事）。
            val mode = SidedInteraction.cycleWidest(player, block, face, player.isSneaking)
                ?: return resultUnsupported
            SidedInteraction.announceFaceMode(player, face, mode)
            return resultSuccess
        }

        /** 从玩家视线推出他正对着哪个面 —— RebarWrench 只给 block，不给 face。 */
        private fun targetFace(player: Player): BlockFace? {
            val hit = player.rayTraceBlocks(REACH.toDouble()) ?: return null
            return hit.hitBlockFace?.takeIf { it != BlockFace.SELF }
        }

        private const val REACH = 5
    }

    /** 反射调用里非目标方法（toString / hashCode / equals）的兜底。 */
    private fun defaultFor(method: Method, proxy: Any, args: Array<out Any?>?): Any? =
        when (method.name) {
            "toString" -> "SidedIoWrenchableProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> null
        }
}
