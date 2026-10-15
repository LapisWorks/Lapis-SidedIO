package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.addon.RebarAddon
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import top.mc506lw.rebar.sidedio.gui.SidedIoCommand
import java.util.Locale

/**
 * Sided-IO 附属入口。
 *
 * 这个插件**不注册任何方块或物品** —— 它是个纯库：
 * 给别的 Rebar 附属提供"每面 I/O 配置"（Side Configuration）能力，
 * 外加把原版容器（熔炉 / 酿造台 / 箱子 …）也接上同一套语义。
 *
 * ## 为什么做成独立插件而不是一个 jar 里的工具类
 *
 * 因为面配置要做的事里有三件**必须是全局单例**：
 * 1. **事件监听**：右键切面、方块存/读盘。监听器得由某个插件注册。
 * 2. **PDC 键的所有权**：`sidedio:side_modes` 这个键必须有唯一的写入方，
 *    否则多个附属各写各的会互相覆盖。
 * 3. **跨附属共享**：Lapis-Pipez、Iron-Furnaces、以及玩家以后写的任何机器，
 *    要用**同一份**面配置。做成库（compileOnly）的话每个附属会各自持有一份状态，
 *    那台机器被两个附属各挂一次监听，右键切两格。
 *
 * 所以它是"装了就能用的基础设施插件"，其他附属 `depend: [Sided-IO]` 即可。
 */
class SidedIoAddon : JavaPlugin(), RebarAddon {

    companion object {
        lateinit var instance: SidedIoAddon
            private set
    }

    override fun onEnable() {
        instance = this

        // 通道类型注册表：核心四类（物品/流体/能量/气体）。
        // ChannelTypes 的伴生对象在类加载时就注册好了，这里只是显式碰一下
        // 让它一定被初始化 —— 否则第一个读盘请求可能在类加载竞态下走兜底。
        ChannelTypes.size

        registerWithRebar()

        // 面配置的登记处：监听方块存/读盘、拆/卸，管生命周期
        server.pluginManager.registerEvents(SidedIoRegistry, this)

        // 原版容器路径：插件启动时把已加载世界里已有的原版容器接进来
        // （之后新放的靠 SidedIoRegistry 事件里的 BlockStorage 判断自动生效）
        VanillaSidesBridge.initialize()

        // 切面的入口：对接 RebarWrench（装了才生效，没装就只走面板/GUI 按钮）
        WrenchBridge.hook()

        // /sidedio —— 开面配置面板 / 查六个面的模式
        getCommand("sidedio")?.setExecutor(SidedIoCommand)

        logger.info(
            "Sided-IO 已就绪：面配置对 Rebar 机器与原版容器同时生效" +
                if (WrenchBridge.available) "（已对接 RebarWrench 扳手）" else ""
        )
    }

    override fun onDisable() {
        WrenchBridge.unhook()
        SidedIoRegistry.shutdown()
    }

    override val javaPlugin: JavaPlugin
        get() = this

    /**
     * Rebar 按 key 的 namespace 匹配附属。默认实现会从插件名推导（"sided-io"），
     * 所以这里显式指定成内容键用的 namespace。
     */
    override fun getKey(): NamespacedKey = SidedIoKeys.key(SidedIoKeys.NAMESPACE)

    override val defaultLanguage: Locale
        get() = Locale.ENGLISH

    /** 附属在指南书里的图标 */
    override val material: Material
        get() = Material.COMPARATOR
}
