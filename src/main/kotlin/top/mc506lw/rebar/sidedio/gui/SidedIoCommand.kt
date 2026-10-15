package top.mc506lw.rebar.sidedio.gui

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import top.mc506lw.rebar.sidedio.SIDED_FACES
import top.mc506lw.rebar.sidedio.SidedIoRegistry
import top.mc506lw.rebar.sidedio.VanillaSidesBridge

/**
 * `/sidedio` —— 打开面配置面板。
 *
 * ## 为什么要有命令
 *
 * 面板需要一个稳定的打开入口。原来的"空手右键切面"已经被移掉
 * （空手右键是开机器界面的通用手势，见 [top.mc506lw.rebar.sidedio.SidedInteraction]），
 * 切面改由 RebarWrench 扳手触发、面板入口归命令。
 * 玩家对着机器敲 `/sidedio` 就开面板。
 *
 * 顺带做一个 `/sidedio info`，把六个面的模式**打印到聊天栏**——
 * 不带 GUI 也能查，方便在服务器控制台 / 脚本里排查。
 */
object SidedIoCommand : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 先处理"控制台也能跑"的子命令 —— 它们不依赖玩家视角
        if (args.getOrNull(0)?.lowercase() == "wrench") {
            sender.sendMessage("§7=== RebarWrench 注册表 ===")
            for (line in top.mc506lw.rebar.sidedio.WrenchBridge.debugRegistry()) sender.sendMessage(line)
            return true
        }

        if (args.getOrNull(0)?.lowercase() == "lang") {
            for (line in top.mc506lw.rebar.sidedio.LanguageDebug.dump(sender)) sender.sendMessage(line)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage("只有玩家能用这个命令（控制台可用 /sidedio wrench）")
            return true
        }

        val sub = args.getOrNull(0)?.lowercase() ?: "gui"

        return when (sub) {
            "gui" -> {
                val block = sender.getTargetBlockExact(REACH)
                if (block == null) {
                    sender.sendMessage("§c请对准一个方块（Rebar 机器或原版容器）")
                    return true
                }
                if (SidedIoRegistry.isManaged(block)) {
                    SideConfigGui.open(sender, block)
                } else if (VanillaSidesBridge.isManagedVanilla(block)) {
                    // 原版方块目前只支持扳手/面板切面，没有独立面板
                    // （面板要有"组"的概念才摆得出来，原版容器没有组）
                    sender.sendMessage("§e原版容器暂不支持面板，请拿 RebarWrench 扳手右键它的各个面来切换模式")
                } else {
                    sender.sendMessage("§c这个方块不受 Sided-IO 管理")
                }
                true
            }

            "info" -> {
                val block = sender.getTargetBlockExact(REACH)
                if (block == null) {
                    sender.sendMessage("§c请对准一个方块")
                    return true
                }
                val config = SidedIoRegistry.get(block)
                if (config == null) {
                    sender.sendMessage("§c这个方块不受 Sided-IO 管理")
                    return true
                }
                sender.sendMessage("§7=== 面配置：${block.type.name} ===")

                // 机器级设置 —— 自动输入/输出这类"整台机器"的行为看这里
                val settings = config.settings
                sender.sendMessage(
                    "§7机器级：§f红石=${settings.redstoneMode}" +
                        " §f自动输入=${onOff(settings.autoInput)}" +
                        " §f自动输出=${onOff(settings.autoOutput)}"
                )

                for (face in SIDED_FACES) {
                    val name = face.name.lowercase()
                    if (config.isFaceHidden(face)) {
                        sender.sendMessage("§7$name: §8（被机器屏蔽 —— 接不上、也不搬）")
                        continue
                    }
                    val types = config.typesOn(face)
                    if (types.isEmpty()) {
                        sender.sendMessage("§7$name: §8（无通道）")
                        continue
                    }
                    for (type in types) {
                        if (!type.configurable) {
                            sender.sendMessage("§7$name §8· §f${type.id} §8（只读）")
                            continue
                        }
                        val mode = config.mode(face, type)
                        // ⚠️ 自动输入 / 自动输出**就是看这两个数**：
                        // 0 表示这个面在这个方向上没有可搬的槽，自动搬运会直接跳过它。
                        val ins = config.insertSlotsFor(face, type).size
                        val ext = config.extractSlotsFor(face, type).size
                        sender.sendMessage(
                            "§7$name §8· §f${type.id} §8→ §f$mode " +
                                "§8[可插 $ins / 可抽 $ext]"
                        )
                    }
                    // 邻居是什么 —— 自动搬运只认 `Container`（箱子/桶/漏斗这类）。
                    // 是空气 / 别的机器，搬运会静默跳过，所以这里如实报出来。
                    val neighbor = block.getRelative(face)
                    val loaded = neighbor.world.isChunkLoaded(neighbor.x shr 4, neighbor.z shr 4)
                    val kind = when {
                        !loaded -> "§8未加载"
                        neighbor.state is org.bukkit.block.Container -> "§a容器"
                        neighbor.type.isAir -> "§8空气"
                        else -> "§8${neighbor.type.name}（不是容器）"
                    }
                    sender.sendMessage("§8   邻居: $kind")
                }
                true
            }

            else -> {
                sender.sendMessage("§7用法：/sidedio [gui|info|wrench|lang]")
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<out String>
    ): List<String> = if (args.size <= 1) {
        listOf("gui", "info", "wrench", "lang").filter { it.startsWith(args.getOrNull(0)?.lowercase() ?: "") }
    } else emptyList()

    private fun onOff(value: Boolean): String = if (value) "§a开" else "§8关"

    private const val REACH = 6
}
