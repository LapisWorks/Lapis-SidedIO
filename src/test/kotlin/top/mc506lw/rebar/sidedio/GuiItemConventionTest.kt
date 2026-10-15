package top.mc506lw.rebar.sidedio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫：**不许再手搓 `ItemStack.itemMeta`**。
 *
 * ## 为什么要立这条规矩
 *
 * Bukkit 的 `ItemStack#getItemMeta()` 返回的是**副本**。写了
 * ```kotlin
 * val meta = stack.itemMeta
 * meta.displayName(...)     // 改的是副本
 * return stack              // ← 副本没写回去，改动全部丢失
 * ```
 * 的代码**不报错、不抛异常、编译照过**，只是物品默默没有名字。
 * 线上表现就是面板里所有格子 tooltip 显示材质原名（"橙色混凝土"）。
 *
 * 正确写法是 [io.github.pylonmc.rebar.item.builder.ItemStackBuilder]：
 * 它内部直接 `stack.setData(DataComponentTypes.ITEM_NAME, …)`，不存在丢改动的问题。
 * 这也是 Pylon / Iron-Furnaces 全线在用的写法。
 *
 * 这个测试扫源码找 `val xxx = <expr>.itemMeta` 这种"读进局部变量"的模式 ——
 * 它就是上面那个陷阱的形状。（只读用法如 `stack.itemMeta?.has(k)` 不匹配，不受影响。）
 */
class GuiItemConventionTest {

    /**
     * 匹配"把 itemMeta 整个读进局部变量"的写法，即**语句止于 `.itemMeta`**：
     * `val meta = stack.itemMeta`。
     *
     * ⚠️ 故意要求**行尾就是 itemMeta** —— 这样只读链条
     * （`val name = stack.itemMeta?.displayName()`）不会被误伤，
     * 因为那不是"拿一份 meta 去改"的形状。之前用宽松正则吃过一次假阳性。
     */
    private val trap = Regex("""^\s*val\s+\w+\s*(?::\s*[\w<>., ]+)?\s*=\s*[\w.]*\.itemMeta\s*(?://.*)?$""")

    @Test
    fun `no source file reads itemMeta into a local variable`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "找不到源码目录：${root.absolutePath}")

        val offenders = ArrayList<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (trap.containsMatchIn(line)) {
                        offenders += "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "发现手搓 itemMeta 的写法（改动会静默丢失，请改用 ItemStackBuilder）：\n" +
                offenders.joinToString("\n")
        )
    }

    /** 正面约束：GUI 物品一律走 ItemStackBuilder。 */
    @Test
    fun `the side config gui builds its items with ItemStackBuilder`() {
        val gui = File("src/main/kotlin/top/mc506lw/rebar/sidedio/gui/SideConfigGui.kt")
        assertTrue(gui.isFile, "找不到 SideConfigGui.kt")
        val source = gui.readText()
        assertTrue(
            source.contains("ItemStackBuilder.of("),
            "SideConfigGui 应当用 ItemStackBuilder 构造 GUI 物品"
        )
    }
}
