package top.mc506lw.rebar.sidedio

/**
 * 一台机器**支持哪些机器级设置**。
 *
 * 机器在 `attach()` 时声明自己支持哪几项，面板就只画那几格 ——
 * 不搬运物品的机器不该出现"自动输入"开关。
 *
 * ⚠️ 这里说的是**支持**，不是**当前值**。当前值在 [MachineSettings] 上。
 */
enum class MachineSetting {

    /** 红石控制（[RedstoneMode] 四态）。 */
    REDSTONE,

    /** 自动输入：机器自己从相邻容器**拉**东西进来，不靠管道。 */
    AUTO_INPUT,

    /** 自动输出：机器自己把产物**推**给相邻容器，不靠管道。 */
    AUTO_OUTPUT;

    /**
     * 翻译键：`sidedio.setting.auto_input` 这种。
     *
     * ⚠️ 说明用**平铺的 `_desc` 后缀**，不是 `.description` ——
     * 后者会让 YAML 里的键既是字符串又是 section，把名字吞掉（踩过）。
     */
    val translationKey: String get() = "sidedio.setting.${name.lowercase()}"

    val descriptionKey: String get() = "sidedio.setting.${name.lowercase()}_desc"
}

/**
 * 一台机器的**机器级设置** —— 和"面"无关的那部分配置。
 *
 * ## 为什么和 [SideConfig] 分开
 *
 * 面配置回答的是"东西从**哪个面**进/出"（`面 × 类型 → 方向`），
 * 机器级设置回答的是"这台机器**整体**怎么表现"：
 *
 * ```
 * 面配置     前·西面 双向   左·北面 输入   右·南面 输出   后·东面 空
 * 机器级     红石：高电平   自动输入：开   自动输出：关
 * ```
 *
 * 两者**正交**：一台机器只有一个红石档位，却有六个面。硬塞进同一个网格
 * 是错的（这也是 TE / EnderIO / Mekanism 都用独立区域放它的原因）。
 *
 * ## 分工：Sided-IO 只存，机器只做
 *
 * 这个类**不实现任何行为**。它只是：
 * - 存住玩家选的值（持久化在 `sidedio:machine_settings`）
 * - 给机器读（`config.settings.redstoneMode` / `.autoInput` / `.autoOutput`）
 * - 给面板画
 *
 * "低电平该停还是该跑"、"自动输入怎么搬"**全是机器的事** ——
 * Sided-IO 不该知道一台机器"运行"是什么意思。
 */
class MachineSettings internal constructor(
    /** 这台机器声明支持的设置项（面板据此决定画哪几格）。 */
    val supported: Set<MachineSetting>
) {

    /** 红石档位。只有 [supports] 了 [MachineSetting.REDSTONE] 才有意义。 */
    var redstoneMode: RedstoneMode = RedstoneMode.DEFAULT

    /** 自动输入开关。 */
    var autoInput: Boolean = false

    /** 自动输出开关。 */
    var autoOutput: Boolean = false

    fun supports(setting: MachineSetting): Boolean = setting in supported

    /** 面板里要画的设置项，按固定顺序（和枚举声明一致，保证可测）。 */
    fun visibleSettings(): List<MachineSetting> =
        MachineSetting.entries.filter { it in supported }

    // ------------------------------------------------------------ 持久化

    /**
     * 拍平成 `键 → 值`。键只用小写 + 下划线，**不带点** ——
     * 免得和面网格那边 `<面名>.<类型 id>` 的解析规则混淆。
     */
    internal fun snapshot(): Map<String, String> = buildMap {
        if (supports(MachineSetting.REDSTONE)) put(KEY_REDSTONE, redstoneMode.name)
        if (supports(MachineSetting.AUTO_INPUT)) put(KEY_AUTO_INPUT, autoInput.toString())
        if (supports(MachineSetting.AUTO_OUTPUT)) put(KEY_AUTO_OUTPUT, autoOutput.toString())
    }

    /** 从持久化数据恢复。不认识的值一律走默认，**不抛异常**。 */
    internal fun restore(saved: Map<String, String>) {
        saved[KEY_REDSTONE]?.let { redstoneMode = RedstoneMode.fromName(it) }
        saved[KEY_AUTO_INPUT]?.let { autoInput = it.toBooleanStrictOrNull() ?: autoInput }
        saved[KEY_AUTO_OUTPUT]?.let { autoOutput = it.toBooleanStrictOrNull() ?: autoOutput }
    }

    internal companion object {
        const val KEY_REDSTONE = "redstone"
        const val KEY_AUTO_INPUT = "auto_input"
        const val KEY_AUTO_OUTPUT = "auto_output"
    }
}
