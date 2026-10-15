package top.mc506lw.rebar.sidedio

import io.github.pylonmc.rebar.logistics.LogisticGroup
import io.github.pylonmc.rebar.logistics.LogisticGroupType
import io.github.pylonmc.rebar.logistics.slot.LogisticSlot
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SideConfig] 的核心行为测试 —— **(面 × 类型) 二维模型**。
 *
 * 这里不碰 Bukkit 服务端：槽位用 [StubSlot] 顶替
 * （`LogisticSlot` 是纯接口，不依赖运行时环境）。
 *
 * 覆盖的是**最容易写错的三件事**：
 * 1. 两个轴**真的正交** —— 改北面的能量不能碰到北面的物品
 * 2. 方向 → 组类型/槽位的翻译（尤其 NONE 的"不建组"语义）
 * 3. 组名里带**组类型**这件事（`item__north_input`；换面方向 = **不**换组名，
 *    这是"改方向不产生残留组"的地基）
 */
class SideConfigTest {

    // ------------------------------------------------------ 名字 / 地基

    /**
     * 组名 = `类型 + 面 + **组类型**`。
     *
     * ⚠️ 带的是**组自己的类型**（INPUT/OUTPUT/BOTH），**不是面的方向**。
     * 面方向是玩家随时会改的，一旦编进名字，每改一次就换一批组名，
     * 而 Rebar 不给删组的口子 → 老组永远残留、被管道当成有效组读到。
     */
    @Test
    fun `group name encodes type face and slot type`() {
        val config = config()

        assertEquals(
            "sidedio__item__north_input",
            config.groupNameFor(ChannelTypes.ITEM, BlockFace.NORTH, LogisticGroupType.INPUT)
        )
        assertEquals(
            "sidedio__fluid__up_output",
            config.groupNameFor(ChannelTypes.FLUID, BlockFace.UP, LogisticGroupType.OUTPUT)
        )
    }

    /**
     * 🔴 **回归（用户实测报的 bug）**：改面方向**不该**换组名。
     *
     * Rebar 没有删物流组的 API，所以只要组名跟着方向变，玩家每点一次方向
     * 就会多留一批永不消失的老组；管道遍历 `getLogisticGroups()` 时会读到它们，
     * 表现是"面明明设成了输入，管道却还在往外抽"。
     *
     * 组名由**组类型**决定之后，改方向 = 同一个名字 → 复用，不留垃圾。
     */
    @Test
    fun `changing a face mode does not change the group names`() {
        val config = config()

        fun names(): List<String> {
            val result = mutableListOf<String>()
            config.rebuildGroups { name, _ -> result += name }
            return result
        }

        val before = names()
        for (face in SIDED_FACES) config.setMode(face, ChannelTypes.ITEM, SideMode.OUTPUT)
        val after = names()

        assertEquals(before, after, "改面方向后组名必须逐字节一致 —— 否则老组会残留")
    }

    /**
     * **回归测试**：同一次 `rebuildGroups` 反复跑，吐出的组名必须逐字节一致。
     *
     * 这是线上事故的直接成因 —— 组名确定性 + `createLogisticGroup` 撞名即抛，
     * 于是 `reattach`（apply 建一轮 + refreshGroups 再建一轮）和区块热重载
     * （同机器第二次 attach）都会炸 `IllegalStateException`。
     *
     * 真正的去重发生在 [SidedIoRegistry.createGroupOnce]（那层要 Bukkit，测不了），
     * 但它成立的**前提**就是这里断言的"名字可重复、可预测"。
     */
    @Test
    fun `rebuild groups is deterministic so duplicate creation can be skipped`() {
        val config = config()
        fun emit(): List<String> {
            val names = mutableListOf<String>()
            config.rebuildGroups { name, _ -> names += name }
            return names
        }
        assertEquals(emit(), emit(), "组名必须可重复 —— 否则 createGroupOnce 的去重会失效")
    }

    // ------------------------------------------------------ 两个轴正交

    @Test
    fun `changing one type on a face does not touch another type on the same face`() {
        // ★ 这是新模型存在的**全部理由**。老实现一个面只有一个方向，
        //   表达不了"这个面物品进、能量出"。
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        config.setMode(BlockFace.NORTH, ChannelTypes.FLUID, SideMode.OUTPUT)

        assertEquals(SideMode.INPUT, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.OUTPUT, config.mode(BlockFace.NORTH, ChannelTypes.FLUID))

        // 再改能量，物品必须纹丝不动
        config.setMode(BlockFace.NORTH, ChannelTypes.FLUID, SideMode.NONE)
        assertEquals(SideMode.INPUT, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.NONE, config.mode(BlockFace.NORTH, ChannelTypes.FLUID))
    }

    @Test
    fun `changing one face does not touch another face for the same type`() {
        val config = config()
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        config.setMode(BlockFace.SOUTH, ChannelTypes.ITEM, SideMode.OUTPUT)

        assertEquals(SideMode.INPUT, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.OUTPUT, config.mode(BlockFace.SOUTH, ChannelTypes.ITEM))
    }

    // ------------------------------------------------------ 类型的存在性

    @Test
    fun `typesOn only returns types the machine actually registered`() {
        // "注册哪个有哪个" —— 只注册了物品的机器，面板左侧就只有物品
        val onlyItems = config(itemSlots = slot())
        assertEquals(listOf(ChannelTypes.ITEM), onlyItems.typesOn(BlockFace.NORTH))

        val withFluid = config(itemSlots = slot(), fluidSlots = slot())
        assertEquals(listOf(ChannelTypes.ITEM, ChannelTypes.FLUID), withFluid.typesOn(BlockFace.NORTH))
    }

    @Test
    fun `types list is stable and core types come first`() {
        // 面板左侧的顺序必须是稳定的（注册顺序），否则每次开面板按钮位置乱跳
        val config = config(fluidSlots = slot(), itemSlots = slot())
        val types = config.types()
        assertEquals(types, config.types())
        assertTrue(types.indexOf(ChannelTypes.ITEM) < types.indexOf(ChannelTypes.FLUID),
            "核心类型按注册顺序排：物品在能量前面")
    }

    @Test
    fun `a type not attached to a face is not a channel there`() {
        // 机器可以把某个类型限定到部分面（例如"能量只在顶面"）
        val config = config(
            itemSlots = slot(),
            fluidSlots = slot(),
            fluidFaces = listOf(BlockFace.UP),
        )
        assertTrue(config.hasChannel(BlockFace.UP, ChannelTypes.FLUID))
        assertFalse(config.hasChannel(BlockFace.NORTH, ChannelTypes.FLUID))
        assertFalse(config.typesOn(BlockFace.NORTH).contains(ChannelTypes.FLUID))
        // 物品没被限制，哪儿都有
        assertTrue(config.hasChannel(BlockFace.NORTH, ChannelTypes.ITEM))
    }

    @Test
    fun `cycling an inexistent channel is a no-op instead of inventing a ghost cell`() {
        val config = config(fluidSlots = slot(), fluidFaces = listOf(BlockFace.UP))
        assertEquals(SideMode.NONE, config.cycleMode(BlockFace.NORTH, ChannelTypes.FLUID))
        assertFalse(config.hasChannel(BlockFace.NORTH, ChannelTypes.FLUID))
    }

    @Test
    fun `hidden faces expose no channels at all`() {
        val config = config(itemSlots = slot()).also { it.hideFaceInternal(BlockFace.DOWN) }
        assertTrue(config.isFaceHidden(BlockFace.DOWN))
        assertTrue(config.typesOn(BlockFace.DOWN).isEmpty())
        assertFalse(config.hasChannel(BlockFace.DOWN, ChannelTypes.ITEM))
        assertTrue(config.slotsFor(BlockFace.DOWN, ChannelTypes.ITEM).isEmpty())
        assertTrue(config.activeGroupNames(BlockFace.DOWN).isEmpty())
    }

    // ------------------------------------------------------ 循环顺序

    @Test
    fun `cycle order is both input output none`() {
        // 用户明确要求的顺序：双向 → 输入 → 输出 → 空
        val config = config()
        val start = config.mode(BlockFace.NORTH, ChannelTypes.ITEM)
        assertEquals(SideMode.BOTH, start, "出厂默认是双向")

        assertEquals(SideMode.INPUT, config.cycleMode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.OUTPUT, config.cycleMode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.NONE, config.cycleMode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.BOTH, config.cycleMode(BlockFace.NORTH, ChannelTypes.ITEM))
    }

    @Test
    fun `backwards cycle is the exact reverse`() {
        val config = config()
        assertEquals(SideMode.BOTH, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.NONE, config.cycleModeBackwards(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.OUTPUT, config.cycleModeBackwards(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.INPUT, config.cycleModeBackwards(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.BOTH, config.cycleModeBackwards(BlockFace.NORTH, ChannelTypes.ITEM))
    }

    @Test
    fun `cycle order contains every mode exactly once`() {
        assertEquals(SideModes.all().toSet(), SideMode.CYCLE_ORDER.toSet())
        assertEquals(SideModes.all().size, SideMode.CYCLE_ORDER.size)
    }

    // ------------------------------------------------------ 组重建

    @Test
    fun `rebuild groups emits one group per occupied cell`() {
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        config.setMode(BlockFace.NORTH, ChannelTypes.FLUID, SideMode.OUTPUT)

        val emitted = LinkedHashMap<String, LogisticGroup>()
        config.rebuildGroups { name, group -> emitted[name] = group }

        // 北面上两个类型各一个组 —— 这就是"两个轴都真的在起作用"。
        // ⚠️ 组名带的是**组类型**（这两条通道都没声明 → 默认 BOTH），
        //    面的方向（INPUT / OUTPUT）**不进名字** —— 见上面两个组名测试。
        assertTrue("sidedio__item__north_both" in emitted, "实际: ${emitted.keys}")
        assertTrue("sidedio__fluid__north_both" in emitted, "实际: ${emitted.keys}")
    }

    /**
     * 🔴 **回归（用户实测报的 bug）**：组的类型来自**通道自己的声明**（`slotType`），
     * **不是**面的方向。
     *
     * 修复前的实现是 `LogisticGroup(mode.toGroupType, ...)` —— 面是什么方向，
     * 就把该类型下**所有**组都套成那个类型。后果（熔炉的真实形状）：
     *
     * - 侧面设成 INPUT → **输出槽组也变 INPUT** → 成品再也抽不走；
     * - 顶面设成 OUTPUT → **输入槽组也变 OUTPUT** → 没烧的矿石能被抽走。
     *
     * 现在面的方向只决定"这个面**碰不碰**这组"（见 [SideMode.covers]）。
     */
    @Test
    fun `group type comes from the channel declaration not the face direction`() {
        val config = config(slotType = LogisticGroupType.OUTPUT)

        // 面设成 INPUT（"这个面只进"）→ 够不着 OUTPUT 组 → 这一面**不建组**
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        val whenInput = LinkedHashMap<String, LogisticGroup>()
        config.rebuildGroups { name, group -> whenInput[name] = group }
        assertFalse(
            whenInput.keys.any { it.contains("__item__north") },
            "面是 INPUT 时不该建出 OUTPUT 组（否则成品能从'只进'的面被抽走）：${whenInput.keys}"
        )

        // 面设成 OUTPUT → 够得着 → 建组，且类型仍是**声明**的 OUTPUT
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.OUTPUT)
        val whenOutput = LinkedHashMap<String, LogisticGroup>()
        config.rebuildGroups { name, group -> whenOutput[name] = group }
        assertEquals(
            LogisticGroupType.OUTPUT,
            whenOutput["sidedio__item__north_output"]?.slotType,
            "组类型必须来自通道声明，实际: ${whenOutput.keys}"
        )
    }

    @Test
    fun `none mode emits no group at all`() {
        // 新模型的 NONE：这一格直接不建组。
        // 和老实现（建空槽组）不同的理由是：类型这一层已经承担了
        // "这个面有没有这条通道"，所以"关掉"可以真的意味着"别连"。
        val config = config()
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.NONE)

        val emitted = LinkedHashMap<String, LogisticGroup>()
        config.rebuildGroups { name, group -> emitted[name] = group }
        assertTrue(emitted.keys.none { it.startsWith("sidedio__item__north") }, "实际: ${emitted.keys}")

        assertTrue(config.slotsFor(BlockFace.NORTH, ChannelTypes.ITEM).isEmpty())
        assertFalse(config.canInsert(BlockFace.NORTH, ChannelTypes.ITEM))
        assertFalse(config.canExtract(BlockFace.NORTH, ChannelTypes.ITEM))
    }

    @Test
    fun `forcing one direction keeps the other grid cells alive`() {
        // 把北面的物品关掉，北面的能量必须照常建组 ——
        // 老模型里"关掉一个面"会把整个面干掉，那是个连带伤害 bug。
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.NONE)
        config.setMode(BlockFace.NORTH, ChannelTypes.FLUID, SideMode.INPUT)

        val emitted = LinkedHashMap<String, LogisticGroup>()
        config.rebuildGroups { name, group -> emitted[name] = group }
        assertFalse(emitted.keys.any { it.contains("__item__north") })
        assertTrue(emitted.keys.any { it.contains("__fluid__north_both") }, "实际: ${emitted.keys}")
    }

    @Test
    fun `slotsFor returns the slot set of the matching type`() {
        val itemSlot = slot()
        val fluidSlot = slot()
        val config = config(itemSlots = itemSlot, fluidSlots = fluidSlot)

        config.setMode(BlockFace.UP, ChannelTypes.ITEM, SideMode.OUTPUT)
        config.setMode(BlockFace.UP, ChannelTypes.FLUID, SideMode.INPUT)

        assertEquals(listOf(itemSlot), config.slotsFor(BlockFace.UP, ChannelTypes.ITEM))
        assertEquals(listOf(fluidSlot), config.slotsFor(BlockFace.UP, ChannelTypes.FLUID))
        // 不分类的入口是并集
        assertEquals(setOf(itemSlot, fluidSlot), config.slotsFor(BlockFace.UP).toSet())
    }

    @Test
    fun `activeGroupNames lists only the cells that actually build a group`() {
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        config.setMode(BlockFace.NORTH, ChannelTypes.FLUID, SideMode.NONE)

        assertEquals(listOf("sidedio__item__north_both"), config.activeGroupNames(BlockFace.NORTH))
    }

    // ------------------------------------------------------ 同类型多组槽位

    /**
     * **回归测试**：同一类型下的多组槽位必须**各成一个组**，过滤条件不能互相覆盖。
     *
     * 这是熔炉的真实形状：`物品` 类型下同时有输入槽（什么都收）、燃料槽
     * （只收燃料）、输出槽。如果把它们合并成一份槽位，过滤器就只挂得上一个 ——
     * 结果是"石头被当燃料塞进熔炉"。
     *
     * 修复前的行为：同类型合并成一份 + 后注册的 filter 覆盖前面的。
     */
    @Test
    fun `same type with multiple slot sets keeps each filter separate`() {
        // ⚠️ 不用 Material.COAL 之类的常量当判据 —— `Material` 的静态初始化
        // 需要跑着的服务端注册表，单测环境里会抛 ExceptionInInitializerError。
        // 过滤器的**内容**在这里不重要，重要的是"它有没有被另一组冲掉"。
        val fuelFilter: (ItemStack) -> Boolean = { true }

        // ⚠️ 走**真实的生产路径**（SideConfigBuilder.groupSlots），
        //    而不是手工拼一个 SideConfig —— 手工拼的测不到"合并"这个 bug。
        val defs = listOf(
            SideConfig.ChannelDefinition(
                key = ChannelKey.of(ChannelTypes.ITEM),
                slots = mutableListOf(slot()),
                faces = SIDED_FACES.toMutableSet(),
                default = emptyMap(),
                filter = null,                 // 输入：不过滤
            ),
            SideConfig.ChannelDefinition(
                key = ChannelKey.of(ChannelTypes.ITEM),
                slots = mutableListOf(slot()),
                faces = SIDED_FACES.toMutableSet(),
                default = emptyMap(),
                filter = fuelFilter,           // 燃料：带过滤
            ),
        )
        val slotGroups = SideConfigBuilder.groupSlots(defs)
        val config = SideConfig(
            slotGroups,
            linkedMapOf(ChannelKey.of(ChannelTypes.ITEM) to SIDED_FACES.toMutableSet()),
            LinkedHashMap(),
        )
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)

        val emitted = LinkedHashMap<String, LogisticGroup>()
        config.rebuildGroups { name, group -> emitted[name] = group }

        // 两个组，组名用 #序号 区分（只看北面 —— rebuildGroups 会遍历所有面）
        val names = emitted.keys.filter { it.startsWith("sidedio__item__north_both") }
        assertEquals(2, names.size, "同类型两组槽位必须各建一个组，实际: $names")

        // 关键：燃料那个组的过滤必须还在（没被输入组覆盖掉）
        val filtered = names.count { emitted[it]!!.filter != null }
        assertEquals(1, filtered, "北面上必须恰好有一组带过滤 —— 燃料滤不能被输入组冲掉")
    }

    @Test
    fun `a single slot set keeps a clean group name without an index suffix`() {
        val config = config()
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        val names = mutableListOf<String>()
        config.rebuildGroups { name, _ -> names += name }
        assertTrue(names.none { it.contains("#") }, "单组槽位不该有 #序号 后缀，实际: $names")
    }

    // ------------------------------------------------------ 面方向 × 组类型（交叉）

    /**
     * 🔴 **回归（用户实测报的 bug）**：`slotsFor` 必须把"这个面**够得着**"的槽
     * 和"够不着"的槽**分开**。
     *
     * 熔炉就是真实形状：物品类型下有**三组**槽 —— 输入（INPUT）、燃料（INPUT）、
     * 输出（OUTPUT）。面设成 OUTPUT 时如果还把输入槽一起交出去，
     * **自动输出就会把没烧的矿石一起推走**；反过来面设成 INPUT 时把输出槽
     * 交出去，自动输入会把东西塞进产出槽。
     *
     * 修复前 `slotsFor` 返回的是"该类型下**全部**槽"（`slotsForType(key)`），
     * 面方向完全没参与过滤 —— 因为那时候"组类型"本身就是从面方向推出来的，
     * 过滤也无从下手。
     */
    @Test
    fun `slotsFor hands out only the slot groups the face can reach`() {
        val inputSlot = slot()
        val fuelSlot = slot()
        val outputSlot = slot()
        val config = furnaceConfig(inputSlot, fuelSlot, outputSlot)

        // 侧面（只进）→ 输入槽 + 燃料槽，**不含输出槽**
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        assertEquals(
            listOf(inputSlot, fuelSlot),
            config.slotsFor(BlockFace.NORTH, ChannelTypes.ITEM),
            "侧面只该够得着进料与燃料"
        )

        // 顶面（只出）→ **只有**输出槽
        config.setMode(BlockFace.UP, ChannelTypes.ITEM, SideMode.OUTPUT)
        assertEquals(
            listOf(outputSlot),
            config.slotsFor(BlockFace.UP, ChannelTypes.ITEM),
            "顶面只该够得着产出 —— 否则没烧的矿石会被一起推走"
        )

        // 双向 → 全部
        config.setMode(BlockFace.SOUTH, ChannelTypes.ITEM, SideMode.BOTH)
        assertEquals(
            listOf(inputSlot, fuelSlot, outputSlot),
            config.slotsFor(BlockFace.SOUTH, ChannelTypes.ITEM),
            "双向该够得着全部"
        )

        // 空 → 一个都不给
        config.setMode(BlockFace.DOWN, ChannelTypes.ITEM, SideMode.NONE)
        assertTrue(config.slotsFor(BlockFace.DOWN, ChannelTypes.ITEM).isEmpty())
    }

    /** 造一台"熔炉形状"的配置：物品类型下输入 / 燃料（INPUT）+ 输出（OUTPUT）。 */
    private fun furnaceConfig(
        inputSlot: LogisticSlot,
        fuelSlot: LogisticSlot,
        outputSlot: LogisticSlot
    ): SideConfig {
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val sets = linkedMapOf(
            key to listOf(
                SideConfig.SlotGroup(mutableListOf(inputSlot), null, LogisticGroupType.INPUT),
                SideConfig.SlotGroup(mutableListOf(fuelSlot), null, LogisticGroupType.INPUT),
                SideConfig.SlotGroup(mutableListOf(outputSlot), null, LogisticGroupType.OUTPUT),
            )
        )
        return SideConfig(sets, linkedMapOf(key to SIDED_FACES.toMutableSet()), LinkedHashMap())
    }

    /**
     * 🔴 **回归**：同类型多条通道对同一个面各提一个出厂方向时，必须**合并**
     * 而不是"后声明的盖掉前面的"。
     *
     * 用户实测报的 bug：熔炉的 `OUTPUT_DEFAULT`（顶面 = 输出）被最后注册的
     * 燃料通道的 `INPUT_DEFAULT`（顶面 = NONE）冲掉，于是**顶面出厂是"空"**。
     */
    @Test
    fun `conflicting per face defaults merge instead of overwriting`() {
        // 顶面：一个说 OUTPUT、一个说 NONE → 取 OUTPUT（"没意见"不覆盖）
        assertEquals(SideMode.OUTPUT, SideConfig.mergeDefault(SideMode.NONE, SideMode.OUTPUT))
        assertEquals(SideMode.OUTPUT, SideConfig.mergeDefault(SideMode.OUTPUT, SideMode.NONE))

        // 顶面：一个说 INPUT、一个说 OUTPUT → 双向（两条通道都要用这个面）
        assertEquals(SideMode.BOTH, SideConfig.mergeDefault(SideMode.INPUT, SideMode.OUTPUT))

        // 相同 → 原样
        assertEquals(SideMode.INPUT, SideConfig.mergeDefault(SideMode.INPUT, SideMode.INPUT))
    }

    /**
     * [SideMode.covers] 的真值表 —— 整个交叉语义的地基。
     *
     * 一句话：**"这个面朝哪流"和"这组槽是干什么的"必须对得上**。
     */
    @Test
    fun `covers is exactly the cross of face direction and slot group type`() {
        // 只进的面：够得着进料与双向，够不着产出
        assertTrue(SideMode.INPUT.covers(LogisticGroupType.INPUT, ""))
        assertTrue(SideMode.INPUT.covers(LogisticGroupType.BOTH, ""))
        assertFalse(SideMode.INPUT.covers(LogisticGroupType.OUTPUT, ""), "只进的面不该碰产出槽")

        // 只出的面：够得着产出与双向，够不着进料
        assertTrue(SideMode.OUTPUT.covers(LogisticGroupType.OUTPUT, ""))
        assertTrue(SideMode.OUTPUT.covers(LogisticGroupType.BOTH, ""))
        assertFalse(SideMode.OUTPUT.covers(LogisticGroupType.INPUT, ""), "只出的面不该碰进料槽")

        // 双向：全够得着
        for (group in LogisticGroupType.entries) {
            assertTrue(SideMode.BOTH.covers(group, ""), "双向该够得着 $group")
        }

        // 空：一个都够不着
        for (group in LogisticGroupType.entries) {
            assertFalse(SideMode.NONE.covers(group, ""), "空面不该够得着 $group")
        }
    }

    /**
     * 双向面上，"往里塞"和"往外抽"必须走**不同的槽侧**。
     *
     * 这是自动输入 / 自动输出的实际调用入口（见 [AutoIo.tick]）：
     * 双向面上若两边都用同一个 `slotsFor`，就会出现"抽进来的矿石被塞进成品槽"、
     * "没烧的原料被一起输出"这两类怪现象。
     */
    @Test
    fun `a both face inserts into the input side and extracts from the output side`() {
        val inputSlot = slot()
        val fuelSlot = slot()
        val outputSlot = slot()
        val config = furnaceConfig(inputSlot, fuelSlot, outputSlot)
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.BOTH)

        assertEquals(
            listOf(inputSlot, fuelSlot),
            config.insertSlotsFor(BlockFace.NORTH, ChannelTypes.ITEM),
            "往里塞只该塞进料侧"
        )
        assertEquals(
            listOf(outputSlot),
            config.extractSlotsFor(BlockFace.NORTH, ChannelTypes.ITEM),
            "往外抽只该抽产出侧"
        )
        // 不分类的入口仍然是全部（给"只要一个并集"的调用方兜底）
        assertEquals(
            listOf(inputSlot, fuelSlot, outputSlot),
            config.slotsFor(BlockFace.NORTH, ChannelTypes.ITEM)
        )
    }

    // ------------------------------------------------------ 聚合 / 持久化

    @Test
    fun `faceMode aggregates the widest direction across types`() {
        // WAILA 只想知道"这个面能不能进出"，聚合规则取最宽的
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.setMode(BlockFace.UP, ChannelTypes.ITEM, SideMode.NONE)
        config.setMode(BlockFace.UP, ChannelTypes.FLUID, SideMode.BOTH)
        assertEquals(SideMode.BOTH, config.faceMode(BlockFace.UP))

        config.setMode(BlockFace.UP, ChannelTypes.FLUID, SideMode.INPUT)
        assertEquals(SideMode.INPUT, config.faceMode(BlockFace.UP))
    }

    @Test
    fun `isCustomised is per cell not per face`() {
        // ★ 老实现按"面"判断，遇到"北面物品没改、北面能量改了"会误判成没改过，
        //   reattach 就会把玩家的能量配置丢掉。这里钉死是按格子。
        val config = config(itemSlots = slot(), fluidSlots = slot())
        assertFalse(config.isCustomised)
        config.setMode(BlockFace.NORTH, ChannelTypes.FLUID, SideMode.OUTPUT)
        assertTrue(config.isCustomised)
    }

    @Test
    fun `persistence round trips every cell`() {
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        config.setMode(BlockFace.UP, ChannelTypes.FLUID, SideMode.NONE)

        val snapshot = config.persistenceSnapshot()
        val restored = config(itemSlots = slot(), fluidSlots = slot())
        restored.restore(snapshot)

        assertEquals(SideMode.INPUT, restored.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.NONE, restored.mode(BlockFace.UP, ChannelTypes.FLUID))
    }

    @Test
    fun `restore ignores unknown faces and tolerates bad mode strings`() {
        val config = config()
        config.restore(mapOf("NORTH.sidedio:item" to "output", "UP.sidedio:item" to "not_a_mode", "BOGUS.sidedio:item" to "input"))
        assertEquals(SideMode.OUTPUT, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        // "not_a_mode" → DEFAULT(BOTH)，不是崩掉
        assertEquals(SideMode.BOTH, config.mode(BlockFace.UP, ChannelTypes.ITEM))
    }

    @Test
    fun `legacy face-only saves are migrated onto every type of that face`() {
        // 0.1.x 的老存档一个面只有一个方向，键形如 `NORTH` → `INPUT`。
        // 升级后不该丢掉玩家的配置 —— 平铺到这个面上所有类型。
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.restoreLegacyAware(mapOf("NORTH" to "input", "UP" to "output"))

        assertEquals(SideMode.INPUT, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.INPUT, config.mode(BlockFace.NORTH, ChannelTypes.FLUID))
        assertEquals(SideMode.OUTPUT, config.mode(BlockFace.UP, ChannelTypes.ITEM))
    }

    @Test
    fun `new format wins over a legacy key present at the same time`() {
        // 升级过程中又改过：新键是玩家最后表达的意思，必须覆盖老的
        val config = config(itemSlots = slot(), fluidSlots = slot())
        config.restoreLegacyAware(
            mapOf(
                "NORTH" to "input",                     // 老：整面输入
                "NORTH.sidedio:fluid" to "none",        // 新：流体单独关了
            )
        )
        assertEquals(SideMode.INPUT, config.mode(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(SideMode.NONE, config.mode(BlockFace.NORTH, ChannelTypes.FLUID))
    }

    // ------------------------------------------------------------ 熔炉形状的配置（回归）

    /**
     * 造一份**和铁熔炉一模一样**的配置：一个类型（物品）下三组槽
     * （输入 / 燃料 / 输出），出厂"所有面进料、只有底面出成品"。
     */
    private fun furnaceConfig(): SideConfig {
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val sets = linkedMapOf(
            key to listOf(
                SideConfig.SlotGroup(mutableListOf(slot()), null, LogisticGroupType.INPUT, "input"),
                SideConfig.SlotGroup(mutableListOf(slot()), null, LogisticGroupType.OUTPUT, "output"),
                SideConfig.SlotGroup(mutableListOf(slot()), null, LogisticGroupType.INPUT, "fuel"),
            )
        )
        val faces = linkedMapOf(key to SIDED_FACES.toMutableSet())
        // 全 6 面 INPUT + 只有底面 OUTPUT，合并后 = 底面 BOTH、其余 INPUT
        val defaults = linkedMapOf(
            key to LinkedHashMap(
                SIDED_FACES.associateWith { SideMode.INPUT } + (BlockFace.DOWN to SideMode.BOTH)
            )
        )
        return SideConfig(sets, faces, defaults)
    }

    /** 🔴 回归：**自动输出靠的是 `extractSlotsFor`，它必须交得出产出槽**。 */
    @Test
    fun `furnace bottom face hands out output slots to extraction`() {
        val config = furnaceConfig()
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val groups = config.slotGroups[key]!!
        val output = groups.first { it.name == "output" }.slots

        assertEquals(
            output, config.extractSlotsFor(BlockFace.DOWN, ChannelTypes.ITEM),
            "底面（出厂双向）必须能抽出产出槽 —— 自动输出就靠这一条"
        )
    }

    /** 🔴 回归：侧面出厂是 INPUT —— 能进料，**不能**抽产出（否则矿石会被当成品抽走）。 */
    @Test
    fun `furnace side face feeds but never extracts`() {
        val config = furnaceConfig()
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val groups = config.slotGroups[key]!!
        val input = groups.first { it.name == "input" }.slots
        val fuel = groups.first { it.name == "fuel" }.slots

        assertEquals(emptyList(), config.extractSlotsFor(BlockFace.NORTH, ChannelTypes.ITEM))
        assertEquals(input + fuel, config.insertSlotsFor(BlockFace.NORTH, ChannelTypes.ITEM))
    }

    /** 🔴 回归：**底面（双向）往里塞只走进料侧** —— 不能把矿石塞进成品槽。 */
    @Test
    fun `furnace bottom face inserts into the input side only`() {
        val config = furnaceConfig()
        val key = ChannelKey.of(ChannelTypes.ITEM)
        val groups = config.slotGroups[key]!!
        val input = groups.first { it.name == "input" }.slots
        val fuel = groups.first { it.name == "fuel" }.slots

        assertEquals(input + fuel, config.insertSlotsFor(BlockFace.DOWN, ChannelTypes.ITEM))
    }

    /**
     * 🔴 **回归**：没声明自定义档的机器，循环里**只有内置四档**。
     *
     * 这条钉住的是"自定义档不会泄漏到别的机器上" —— 包括原版容器的扳手循环。
     */
    @Test
    fun `a config supports only the builtin modes unless it declares more`() {
        SideModes.resetToBuiltin()
        try {
            val fuel = SideModes.register("test:fuel", SideMode.Flow.IN)
            val config = config()

            assertTrue(config.isModeSupported(SideMode.BOTH))
            assertTrue(config.isModeSupported(SideMode.NONE))
            assertFalse(config.isModeSupported(fuel), "没声明的自定义档不该被支持")

            // 循环 10 次也落不到它上面
            val seen = LinkedHashSet<SideMode>()
            repeat(10) { seen += config.cycleMode(BlockFace.NORTH, ChannelTypes.ITEM) }
            assertTrue(fuel !in seen, "循环里不该出现没声明的档，实际: $seen")
        } finally {
            SideModes.resetToBuiltin()
        }
    }

    // ------------------------------------------------------------ 测试替身

    /**
     * 造一份配置。**默认总是注册物品通道**（六面都接），
     * 再按参数加别的类型 —— 因为绝大多数测试只想测"方向/组的语义"，
     * 不希望每次都被"这台机器有没有通道"这件事绊住。
     *
     * 默认方向一律不显式给，交给 [SideConfig.defaultFor] 的兜底（BOTH），
     * 这样测试里"出厂值"就是可预测的，不用每次断言都写一遍推导。
     */
    private fun config(
        itemSlots: LogisticSlot? = StubSlot(),
        fluidSlots: LogisticSlot? = null,
        fluidFaces: List<BlockFace> = SIDED_FACES,
        slotType: LogisticGroupType = LogisticGroupType.BOTH,
    ): SideConfig {
        val sets = LinkedHashMap<ChannelKey, List<SideConfig.SlotGroup>>()
        val faces = LinkedHashMap<ChannelKey, MutableSet<BlockFace>>()

        if (itemSlots != null) {
            sets[ChannelKey.of(ChannelTypes.ITEM)] =
                listOf(SideConfig.SlotGroup(mutableListOf(itemSlots), null, slotType))
            faces[ChannelKey.of(ChannelTypes.ITEM)] = SIDED_FACES.toMutableSet()
        }
        if (fluidSlots != null) {
            sets[ChannelKey.of(ChannelTypes.FLUID)] =
                listOf(SideConfig.SlotGroup(mutableListOf(fluidSlots), null, slotType))
            faces[ChannelKey.of(ChannelTypes.FLUID)] = fluidFaces.toMutableSet()
        }
        return SideConfig(sets, faces, LinkedHashMap())
    }

    private fun slot(): LogisticSlot = StubSlot()
    /** 纯内存槽位，只为占位 / 身份比较。 */
    private class StubSlot : LogisticSlot {
        override fun getItemStack(): ItemStack? = null
        override fun getAmount(): Long = 0
        override fun getMaxAmount(stack: ItemStack): Long = 0
        override fun set(stack: ItemStack?, amount: Long) = Unit
    }

    // ------------------------------------------------------------ 只展示的通道（能量）

    /** 造一个带"只展示通道"的配置。 */
    private fun displayConfig(): SideConfig {
        val sets = LinkedHashMap<ChannelKey, List<SideConfig.SlotGroup>>()
        sets[ChannelKey.of(ChannelTypes.ITEM)] =
            listOf(SideConfig.SlotGroup(mutableListOf(StubSlot())))
        val faces = LinkedHashMap<ChannelKey, MutableSet<BlockFace>>()
        faces[ChannelKey.of(ChannelTypes.ITEM)] = SIDED_FACES.toMutableSet()
        faces[ChannelKey.of(ChannelTypes.ENERGY)] = SIDED_FACES.toMutableSet()
        return SideConfig(
            slotGroups = sets,
            channelFaces = faces,
            defaults = LinkedHashMap(),
            displayKeys = setOf(ChannelKey.of(ChannelTypes.ENERGY)),
        )
    }

    @Test
    fun `display channels are listed but marked display only`() {
        val config = displayConfig()
        assertTrue(config.types().contains(ChannelTypes.ENERGY), "能量应出现在类型列表里")
        assertTrue(config.isDisplayOnly(ChannelTypes.ENERGY))
        assertFalse(config.isDisplayOnly(ChannelTypes.ITEM))
    }

    /** 只展示的通道**没有槽位** —— 所以不建物流组、不搬运。 */
    @Test
    fun `display channels have no slots and build no groups`() {
        val config = displayConfig()
        assertTrue(config.slotsFor(BlockFace.NORTH, ChannelTypes.ENERGY).isEmpty())

        val names = ArrayList<String>()
        config.rebuildGroups { name, _ -> names += name }
        assertTrue(names.none { it.contains("energy") }, "能量不该建组，实际：$names")
    }

    /** 只展示的通道**不持久化** —— 它没有方向可存。 */
    @Test
    fun `display channels are not persisted`() {
        val config = displayConfig()
        val snapshot = config.persistenceSnapshot()
        assertTrue(
            snapshot.keys.none { it.contains("energy") },
            "能量不该被持久化，实际：${snapshot.keys}"
        )
        assertTrue(snapshot.keys.any { it.contains("item") }, "物品该被持久化")
    }

    /** 不可配的类型标记本身要在注册表里坐实。 */
    @Test
    fun `energy is registered as not configurable`() {
        assertFalse(ChannelTypes.ENERGY.configurable, "能量应标记为不可配")
        assertTrue(ChannelTypes.ITEM.configurable)
        assertTrue(ChannelTypes.FLUID.configurable)
        assertTrue(ChannelTypes.GAS.configurable)
    }

    // ------------------------------------------------------------ 管道判定

    @Test
    fun `a face set to NONE refuses connections`() {
        val config = config(itemSlots = slot())
        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.NONE)
        assertFalse(config.allowsConnection(BlockFace.NORTH, ChannelTypes.ITEM), "空面不该允许接线")

        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.INPUT)
        assertTrue(config.allowsConnection(BlockFace.NORTH, ChannelTypes.ITEM), "输入面应允许接线")

        config.setMode(BlockFace.NORTH, ChannelTypes.ITEM, SideMode.BOTH)
        assertTrue(config.allowsConnection(BlockFace.NORTH, ChannelTypes.ITEM), "双向面应允许接线")
    }

    @Test
    fun `a hidden face refuses connections`() {
        val config = config(itemSlots = slot())
        config.hideFaceInternal(BlockFace.UP)
        assertFalse(config.allowsConnection(BlockFace.UP, ChannelTypes.ITEM))
    }

    /** 机器在这个面根本没这种通道 → 不许接。 */
    @Test
    fun `a face without the channel refuses connections`() {
        val config = config(itemSlots = slot(), fluidSlots = slot(), fluidFaces = listOf(BlockFace.UP))
        assertTrue(config.allowsConnection(BlockFace.UP, ChannelTypes.FLUID))
        assertFalse(
            config.allowsConnection(BlockFace.NORTH, ChannelTypes.FLUID),
            "流体通道只声明在顶面，北面不该允许接线"
        )
    }

    /** 只展示的通道（能量）**不参与**管道判定 —— 它走电网络。 */
    @Test
    fun `display channels never block pipe connections`() {
        val config = displayConfig()
        config.setMode(BlockFace.NORTH, ChannelTypes.ENERGY, SideMode.NONE)
        assertTrue(
            config.allowsConnection(BlockFace.NORTH, ChannelTypes.ENERGY),
            "能量是只展示的，不该参与管道判定"
        )
    }
}
