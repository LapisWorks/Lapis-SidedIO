package top.mc506lw.rebar.sidedio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

/**
 * 语言文件校验：中英两份必须"键完全一致"，否则玩家切换语言时会看到原始 key。
 *
 * 这个测试不需要跑服务器，`./gradlew build` 就会带上，是最省事的回归保护。
 */
class LanguageResourcesTest {

    @Test
    fun `language files are valid and expose identical keys`() {
        val chinese = loadLanguage("lang/zh.yml")
        val english = loadLanguage("lang/en.yml")

        assertEquals(flattenKeys(chinese), flattenKeys(english))
    }

    /**
     * 内置四档必须有翻译。
     *
     * ⚠️ 只查**无命名空间的** id（内置四档）。带命名空间的自定义档
     * （`ironfurnaces:fuel_input_eject`）的文案归**注册方自己的** lang 文件管 ——
     * 它的键是 `ironfurnaces.mode.fuel_input_eject`，不在 `sidedio.` 下。
     * 遍历全表会把这些别人负责的档也当成"我们该翻的"，测试就假红了。
     */
    @Test
    fun `every builtin side mode has a translation`() {
        val english = loadLanguage("lang/en.yml")
        @Suppress("UNCHECKED_CAST")
        val modes = english["mode"] as Map<String, Any?>
        for (mode in SideModes.all().filter { ':' !in it.id }) {
            val key = mode.id.lowercase()
            assertNotNull(modes[key], "缺少 sidedio.mode.$key 的翻译")
        }
    }

    /**
     * 带命名空间的面状态，翻译键必须落在**它自己的**命名空间下。
     *
     * 这条钉住的是"文案归属"这件事：如果它算成 `sidedio.mode.*`，
     * Sided-IO 就得知道每个附属该显示什么 —— 反向依赖，而且键一定缺失，
     * 面板上显示裸 key。
     */
    @Test
    fun `a namespaced side mode resolves under its own namespace`() {
        val mode = SideModes.register(
            id = "testaddon:some_mode",
            direction = SideMode.Flow.IN,
        )
        try {
            assertEquals("testaddon.mode.some_mode", mode.translationKey)
        } finally {
            SideModes.resetToBuiltin()
        }
    }

    /**
     * 核心通道类型必须有翻译。
     *
     * 第三方类型自己负责自己的翻译（它们带 `<命名空间>:<名字>` 的键），
     * 但核心四类是我们发的，缺了就是我们的锅 —— 面板上会显示裸 key。
     *
     * ⚠️ 只查**核心四类**，不遍历整个注册表：测试在同一个 JVM 里跑，
     * [ChannelTypeTest] 会往里注册临时类型（`force_test` 之类），
     * 遍历全表会把这些测试垃圾也当成"我们该负责的类型"。
     */
    @Test
    fun `core channel types have a name and description`() {
        val english = loadLanguage("lang/en.yml")
        @Suppress("UNCHECKED_CAST")
        val channelTypes = english["channel_type"] as Map<String, Any?>

        val core = listOf(ChannelTypes.ITEM, ChannelTypes.FLUID, ChannelTypes.ENERGY, ChannelTypes.GAS)
        for (type in core) {
            // 键路径：channel_type.<命名空间>.<名字>.name
            @Suppress("UNCHECKED_CAST")
            val byNamespace = channelTypes[type.id.namespace] as? Map<String, Any?>
            assertNotNull(byNamespace, "缺少 sidedio.channel_type.${type.id.namespace} 段")
            @Suppress("UNCHECKED_CAST")
            val entry = byNamespace[type.id.key] as? Map<String, Any?>
            assertNotNull(entry, "缺少 sidedio.channel_type.${type.id.namespace}.${type.id.key} 段")
            assertNotNull(entry["name"], "缺少 ${type.nameKey} 的翻译")
            assertNotNull(entry["description"], "缺少 ${type.descriptionKey} 的翻译")
        }
    }

    /** 兜底类型的翻译也得在 —— 玩家看到裸 key 是最糟的体验。 */
    @Test
    fun `unknown channel fallback has a translation`() {
        val english = loadLanguage("lang/en.yml")
        @Suppress("UNCHECKED_CAST")
        val channelTypes = english["channel_type"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sidedio = channelTypes["sidedio"] as? Map<String, Any?>
        assertNotNull(sidedio, "缺少 sidedio.channel_type.sidedio 段")
        @Suppress("UNCHECKED_CAST")
        val unknown = sidedio["unknown"] as? Map<String, Any?>
        assertNotNull(unknown, "缺少 sidedio.channel_type.sidedio.unknown 段（要匹配 ChannelTypes.UNKNOWN.nameKey）")
        assertNotNull(unknown["name"])
        assertNotNull(unknown["description"])
    }

    /**
     * **回归**：每个用到的翻译键，在 YAML 里必须解析成**字符串**，不能是 section。
     *
     * 这是线上真的踩过的坑：YAML 里写
     * ```yaml
     * gui:
     *   button: "<white>%face% …"     # 字符串
     *   button.type: "<gray>通道：…"   # ← 这一行把 button 变成 map，
     *                                 #    上面的字符串被静默吞掉
     * ```
     * 结果 `RebarTranslator` 拿到 `MemorySection` → 转 String 失败 → 返回 null →
     * 面板上的面格子**没有名字**，客户端退回去显示材质原名（"红色混凝土"）。
     *
     * 这个测试把每个键都断言成非 Map，谁都别想再用点号制造这种碰撞。
     */
    @Test
    fun `no translation key is both a scalar and a section`() {
        for (file in listOf("lang/zh.yml", "lang/en.yml")) {
            val root = loadLanguage(file)
            assertScalarLeaves(root, file, "")
        }
    }

    private fun assertScalarLeaves(node: Map<*, *>, file: String, prefix: String) {
        for ((rawKey, value) in node) {
            val key = rawKey.toString()
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is Map<*, *>) {
                assertScalarLeaves(value, file, path)
            } else {
                assertTrue(
                    value is String,
                    "$file 的键 $path 既不是字符串也不是 section（值=$value）"
                )
            }
        }
    }

    /**
     * **回归**：`gui.button` 与 `gui.button_frame` 这类"前缀撞名"必须各自是独立字符串。
     *
     * 直接点名静态检查 —— 这两个键都在 SideConfigGui 里被 `Component.translatable` 用。
     */
    @Test
    fun `button and button_frame are separate scalar keys`() {
        for (file in listOf("lang/zh.yml", "lang/en.yml")) {
            val gui = loadLanguage(file)["gui"] as? Map<*, *> ?: error("$file 缺少 gui 段")
            assertTrue(gui["button"] is String, "$file 的 gui.button 必须是字符串")
            assertTrue(gui["button_frame"] is String, "$file 的 gui.button_frame 必须是字符串")
            // 老的点号键不能再存在，否则 YAML 又会把它解析成 section
            assertTrue(gui.keys.none { it == "button.type" }, "$file 不应再有 gui.button.type（点号会与 gui.button 撞名）")
        }
    }

    /**
     * **回归**：六个面必须有翻译。
     *
     * 曾经这里用的是 `block.minecraft.<face>` —— **原版根本没有这个键**，
     * 于是界面上直接显示裸 key `block.minecraft.up`。现在用自带的 `sidedio.face.*`，
     * 这个测试把六个面都钉死，谁再改回原版键就红。
     */
    @Test
    fun `all six faces have a translation`() {
        for (file in listOf("lang/zh.yml", "lang/en.yml")) {
            @Suppress("UNCHECKED_CAST")
            val faces = loadLanguage(file)["face"] as? Map<String, Any?>
            assertNotNull(faces, "$file 缺少 face 段")
            for (face in SIDED_FACES) {
                val key = face.name.lowercase()
                assertTrue(faces[key] is String, "$file 缺少 sidedio.face.$key 的翻译")
            }
        }
    }

    private fun loadLanguage(path: String): Map<String, Any?> {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path), "Missing resource: $path")
        return stream.use { Yaml().load(it) }
    }

    /**
     * **回归**：所有键都必须是**字符串**。
     *
     * YAML 1.1 会把裸键 `on` / `off` / `yes` / `no` 解析成**布尔值** ——
     * 写成
     * ```yaml
     * gui:
     *   on: "<green>开"
     * ```
     * 键 `on` 就变成了 `true`，于是 `Map<String, Any?>` 的转换直接抛
     * `ClassCastException: Boolean cannot be cast to String`。
     * 这个测试在遍历时**不假设键的类型**，谁再写这种键就立刻红。
     */
    @Test
    fun `all keys are strings not yaml booleans`() {
        for (file in listOf("lang/zh.yml", "lang/en.yml")) {
            assertStringKeys(loadRaw(file), file, "")
        }
    }

    private fun assertStringKeys(node: Map<*, *>, file: String, prefix: String) {
        for ((rawKey, value) in node) {
            assertTrue(rawKey is String, "$file 的键 '$rawKey'（在 '$prefix' 下）不是字符串 —— YAML 把 on/off/yes/no 当布尔了？")
            val path = if (prefix.isEmpty()) rawKey as String else "$prefix.$rawKey"
            if (value is Map<*, *>) assertStringKeys(value, file, path)
        }
    }

    private fun loadRaw(path: String): Map<*, *> {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path), "Missing resource: $path")
        return stream.use { Yaml().load(it) as Map<*, *> }
    }

    private fun flattenKeys(source: Map<*, *>, prefix: String = ""): Set<String> = buildSet {
        for ((rawKey, value) in source) {
            val key = rawKey.toString()
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            add(path)
            if (value is Map<*, *>) addAll(flattenKeys(value, path))
        }
    }
}
