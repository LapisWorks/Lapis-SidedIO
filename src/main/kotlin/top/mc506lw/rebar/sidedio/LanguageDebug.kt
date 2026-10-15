package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.i18n.RebarTranslator.Companion.translator
import io.github.pylonmc.rebar.registry.RebarRegistry
import net.kyori.adventure.text.Component
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale

/**
 * `/sidedio lang` —— 翻译链路的自证诊断。
 *
 * 排查"面板上只有物品原名（红色混凝土），没有通道名"时用：
 * 逐层检查 ① 附属的 translator 有没有注册、② 加载到了哪些 locale、
 * ③ 具体键在 zh / en 下能不能查到、④ 渲染后的成品文本长什么样。
 *
 * 控制台 / 玩家都能跑。玩家跑时用它自己的 locale，控制台按 zh_CN + en 各查一遍。
 */
object LanguageDebug {

    fun dump(sender: CommandSender): List<String> {
        val out = ArrayList<String>()
        out += "§7=== Sided-IO 翻译诊断 ==="

        // ① translator 注册情况
        val addon = SidedIoAddon.instance
        val translator = runCatching { addon.translator }.getOrNull()
        if (translator == null) {
            out += "§c✗ 附属 translator 未注册（registerWithRebar() 没生效？）"
            return out
        }
        out += "§a✓ translator 已注册：${addon.key}"
        out += "§7  已加载语言：${translator.languages.joinToString { it.toLanguageTag() }}"

        // ② GlobalTranslator 里有没有它（玩家端渲染靠这个）
        val sources = GlobalTranslator.translator().sources()
        out += if (sources.any { it == translator }) "§a✓ 已挂到 GlobalTranslator"
        else "§c✗ 没挂到 GlobalTranslator（玩家端不会翻）"

        // ③ 逐键查
        val locales: List<Locale> = if (sender is Player) listOf(sender.locale())
        else listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)

        val keys = buildList {
            add("sidedio.gui.title")
            add("sidedio.gui.button")
            add("sidedio.gui.button_frame")
            add("sidedio.gui.background")
            add("sidedio.gui.machine")
            add("sidedio.gui.type_lore")
            for (face in SIDED_FACES) add("sidedio.face.${face.name.lowercase()}")
            for (mode in SideModes.all()) add(mode.translationKey)
            for (type in ChannelTypes.all()) add(type.nameKey)
            add(ChannelTypes.UNKNOWN.nameKey)
        }

        for (locale in locales) {
            out += "§7--- locale = ${locale.toLanguageTag()} ---"
            for (key in keys) {
                val raw = runCatching { translator.canTranslate(key, locale) }.getOrElse { false }
                val rendered = runCatching {
                    GlobalTranslator.render(Component.translatable(key), locale).let {
                        if (it is net.kyori.adventure.text.TranslatableComponent) "<未翻译:${it.key()}>"
                        else net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(it)
                    }
                }.getOrElse { "<异常:${it.javaClass.simpleName}>" }
                out += (if (raw) "§a✓ " else "§c✗ ") + "$key §8→ §f$rendered"
            }
        }

        // ④ 附属注册表里所有 addon 的 namespace —— isRebarOrAddon 的前缀匹配依赖它
        out += "§7--- 已注册附属 namespace ---"
        for (a in RebarRegistry.ADDONS) out += "§7  ${a.key}"

        // ⑤ 真实构造一个面板格子，把它**带在物品上的名字**打出来。
        //    这一步才是"面板到底会不会显示字"的终局证据 —— 光看翻译键能查到
        //    不代表物品真的挂上了名字（曾经就栽在忘了 setItemMeta 上）。
        out += "§7--- 面板格子实拍（真实构造 ItemStack）---"
        for (mode in SideModes.all()) {
            val stack = top.mc506lw.rebar.sidedio.gui.SideConfigGui
                .faceStack(org.bukkit.block.BlockFace.NORTH, ChannelTypes.ITEM, mode)
            // ⚠️ 用**数据组件**查，不要用 legacy 的 `itemMeta.displayName()` ——
            // 现代 Paper 里自定义名字走 ITEM_NAME 组件，legacy 视图不保证能反映出来。
            val component = stack.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_NAME)
            out += if (component == null) {
                "§c✗ ${mode.id}：ITEM_NAME 组件为空 → 客户端会退回显示材质原名"
            } else {
                // 只报"翻译键 + 参数个数"，不打整棵组件树（RCON 会截断）
                val key = (component as? net.kyori.adventure.text.TranslatableComponent)?.key() ?: "<非 translatable>"
                val args = (component as? net.kyori.adventure.text.TranslatableComponent)
                    ?.arguments()?.size ?: 0
                "§a✓ ${mode.id}：ITEM_NAME = $key §8(args: $args)"
            }
        }

        // ⑥ 组件清单：tooltip 底部那行蓝色斜体"命名空间"是某个组件触发的，
        //    这里把物品身上**所有**数据组件列出来，和纯原版物品对比就能揪出元凶。
        out += "§7--- 数据组件对比（找出 tooltip 蓝色命名空间行的来源）---"
        val plain = ItemStack(Material.GOLD_BLOCK)
        out += "§7  纯原版 GOLD_BLOCK 的组件：" + describeComponents(plain)
        val built = top.mc506lw.rebar.sidedio.gui.SideConfigGui
            .faceStack(org.bukkit.block.BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        out += "§7  面板格子的组件：" + describeComponents(built)

        // ⑦ 可用的组件名清单（找"能隐藏命名空间行"的那个组件）
        out += "§7--- 含 CUSTOM/TOOLTIP/HIDE 的组件名 ---"
        out += "§7  " + runCatching {
            io.papermc.paper.datacomponent.DataComponentTypes::class.java.fields
                .map { it.name }
                .filter { it.contains("CUSTOM") || it.contains("TOOLTIP") || it.contains("HIDE") }
                .sorted()
                .joinToString(", ")
        }.getOrElse { "<读取失败>" }

        return out
    }

    /** 列出物品身上所有数据组件（只列类型名，够定位就行）。 */
    private fun describeComponents(stack: org.bukkit.inventory.ItemStack): String =
        runCatching {
            stack.dataTypes
                .filter { stack.isDataOverridden(it) }
                .joinToString(", ") { it.key().toString() }
        }.getOrElse { "<读取失败:${it.javaClass.simpleName}>" }
}
