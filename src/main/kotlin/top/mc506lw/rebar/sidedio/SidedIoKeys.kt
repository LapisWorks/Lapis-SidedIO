package top.mc506lw.rebar.sidedio

import org.bukkit.NamespacedKey

/**
 * 所有 NamespacedKey 集中在这里，方便统一改命名空间。
 *
 * ⚠️ 内容键一旦发布就不要改名 —— 方块 PDC 里存的面配置是按 key 读的，
 * 改名等于把所有老存档的面配置清空。
 */
object SidedIoKeys {

    /** 内容命名空间，要和 [SidedIoAddon.getKey] 保持一致 */
    const val NAMESPACE = "sidedio"

    fun key(name: String) = NamespacedKey(NAMESPACE, name)

    /** 方块 PDC 上存"每面模式"的键：`<face 名>` → `<模式 名>` */
    val SIDE_MODES = key("side_modes")

    /**
     * 方块 PDC 上存**机器级设置**（红石 / 自动输入 / 自动输出）的键。
     *
     * ⚠️ 和 [SIDE_MODES] **分开一个容器**，不是塞进同一个：
     * 那边是 `(面, 类型) → 方向` 的二维网格，键形如 `NORTH.sidedio:item`；
     * 这边是扁平的几个开关。混在一起会让"无点键 = 老格式面"的兼容解析
     * （见 `SideConfig.restoreLegacyAware`）把 `redstone` 误当成一个面名。
     */
    val MACHINE_SETTINGS = key("machine_settings")

    /** 方块 PDC 上存"这个方块是否由玩家手动改过面配置"的标志 */
    val SIDE_CONFIGURED = key("side_configured")
}
