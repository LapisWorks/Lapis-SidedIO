package top.mc506lw.rebar.sidedio

/**
 * **红石控制** —— 机器"什么时候允许运行"。
 *
 * ## 为什么它住在 Sided-IO 里，而不是各个机器自己造
 *
 * Rebar 只给了一个 `RedstoneRebarBlockHandler` 事件钩子，**没给语义**
 * （没有枚举、没有"忽略/低/高/脉冲"这套约定）。如果每个附属自己发明一个，
 * 就会长出 `ironfurnaces.redstone.low`、`mymod.redstone.low` 这种重复的键，
 * 而且面板渲染、PDC 持久化、WAILA 显示都得各写一遍。
 *
 * Sided-IO 的定位是"机器配置面板的基础设施"，所以这里把行业通行的那一套
 * 收进来统一定义 —— 和 [ChannelType] 的思路一致：**核心提供标准集，第三方可扩展**。
 *
 * ## 四态从哪来
 *
 * | 模组 | 档位 |
 * |---|---|
 * | Thermal Expansion | 忽略 / 低 / 高 |
 * | EnderIO | 忽略 / 低 / 高 / **脉冲** |
 * | Mekanism | 关闭 / 高 / 低 / **脉冲** |
 *
 * "脉冲"是完整集的标志（收到信号后只跑一小段），所以取四态。
 *
 * ## 语义由**机器**实现，Sided-IO 只存
 *
 * Sided-IO 负责把这四个值存下来、画出来、给机器读；
 * **"低电平到底该停还是该跑"由机器自己判定**（见各机器的 `isRedstoneBlocked()`）。
 * 这是刻意的：Sided-IO 不该知道一台机器"运行"意味着什么。
 */
enum class RedstoneMode {

    /** 忽略。红石信号完全不影响机器。 */
    IGNORED,

    /** 低电平有效：**收到信号时停止**（信号消失才跑）。 */
    LOW,

    /** 高电平有效：**收到信号才运行**。 */
    HIGH,

    /**
     * 脉冲：**收到信号的那一瞬间**跑一小段，然后停下（不管信号还在不在）。
     *
     * ⚠️ 这个档位需要**边沿检测** —— 轮询 `isBlockPowered` 是看不出来的，
     * 得靠 `RedstoneRebarBlockHandler.onRedstoneCurrentChange` 拿事件。
     * 所以声明支持 PULSE 的机器必须自己实现边沿逻辑，Sided-IO 不管。
     */
    PULSE;

    /**
     * 翻译键：`sidedio.redstone.ignored` 这种。
     *
     * ⚠️ 说明用的是**平铺的 `_desc` 后缀**，不是 `sidedio.redstone.ignored.description` ——
     * 后者会让 YAML 里的 `ignored` 从字符串变成 section，把名字本身吞掉
     * （踩过一次，见 LanguageResourcesTest 里的回归）。
     */
    val translationKey: String get() = "sidedio.redstone.${name.lowercase()}"

    /** 说明翻译键（面板 lore 里用）。 */
    val descriptionKey: String get() = "sidedio.redstone.${name.lowercase()}_desc"

    /** 点一下切到下一个档位。 */
    fun next(): RedstoneMode = CYCLE_ORDER[(CYCLE_ORDER.indexOf(this) + 1) % CYCLE_ORDER.size]

    /** 上一个档位。 */
    fun previous(): RedstoneMode =
        CYCLE_ORDER[(CYCLE_ORDER.indexOf(this) - 1 + CYCLE_ORDER.size) % CYCLE_ORDER.size]

    companion object {

        /**
         * 玩家点一下的循环顺序：**忽略 → 低 → 高 → 脉冲**。
         *
         * 忽略打头 —— 它是默认值，也是玩家最常回退到的状态。
         */
        val CYCLE_ORDER: List<RedstoneMode> = listOf(IGNORED, LOW, HIGH, PULSE)

        /** 没配置时的默认档位。 */
        val DEFAULT = IGNORED

        /**
         * 从持久化字符串还原。
         *
         * ⚠️ **不抛异常**：PDC 里的值可能来自老版本、被手改、或枚举改名过。
         * 不认识就退回 [DEFAULT]，不让整台机器加载失败。
         */
        fun fromName(name: String?): RedstoneMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}
