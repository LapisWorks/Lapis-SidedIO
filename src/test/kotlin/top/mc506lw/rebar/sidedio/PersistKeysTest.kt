package top.mc506lw.rebar.sidedio

import org.bukkit.NamespacedKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PersistKeys] 的编解码契约。
 *
 * 这个编解码存在的唯一理由是**线上真的炸了**：逻辑键 `NORTH.sidedio:item`
 * 直接喂给 `NamespacedKey` 会抛
 * `Non [a-z0-9_-./] character in key 'NORTH.sidedio:item' at index 0 ('N')`，
 * 导致面配置**一格都存不下来**。所以这里不只是测编解码自身，
 * 更要**用真实 NapespacedKey 构造器验证编码后确实合法**。
 */
class PersistKeysTest {

    /** 线上日志里那条报错的原始键 —— 回归的锚点。 */
    private val failingKey = "NORTH.sidedio:item"

    @Test
    fun `encode produces only characters legal for a namespaced key`() {
        val encoded = PersistKeys.encode(failingKey)
        // 合法集：[a-z0-9_-./]。逐字符断言，出了界立刻红。
        for (c in encoded) {
            assertTrue(
                c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '/',
                "编码结果含非法字符 '$c'（整体：$encoded）"
            )
        }
    }

    /**
     * 关键断言：编码后的键能被**真实的** `NamespacedKey` 构造器接受。
     * 这条测试曾经会让 [failingKey] 直接抛异常。
     */
    @Test
    fun `encoded key is accepted by the real namespaced key constructor`() {
        val encoded = PersistKeys.encode(failingKey)
        val key = NamespacedKey(SidedIoKeys.NAMESPACE, encoded)
        assertEquals(encoded, key.key)
        assertEquals(SidedIoKeys.NAMESPACE, key.namespace)
    }

    /** 对照：未经编码的逻辑键必须抛（证明这个编解码不是多余的）。 */
    @Test
    fun `raw logical key would have thrown`() {
        val threw = runCatching { NamespacedKey(SidedIoKeys.NAMESPACE, failingKey) }.isFailure
        assertTrue(threw, "原始逻辑键 '$failingKey' 本该被 NamespacedKey 拒绝，但它通过了 —— 说明测试前提失效")
    }

    @Test
    fun `round trip preserves the logical key`() {
        val decoded = PersistKeys.decode(PersistKeys.encode(failingKey))
        assertEquals(failingKey, decoded)
    }

    @Test
    fun `round trip works for every face and core channel type`() {
        for (face in SIDED_FACES) {
            for (type in ChannelTypes.all()) {
                val logical = "${face.name}.${type.id.namespace}:${type.id.key}"
                val encoded = PersistKeys.encode(logical)
                assertEquals(logical, PersistKeys.decode(encoded), "还原失败：$logical → $encoded")
                // 每个编码结果都得能被真构造器接受
                NamespacedKey(SidedIoKeys.NAMESPACE, encoded)
            }
        }
    }

    /** 第三方类型（带自己的命名空间）也要能过 —— "原力"这类。 */
    @Test
    fun `round trip works for third party namespaced types`() {
        val logical = "UP.mymod:the_force"
        val encoded = PersistKeys.encode(logical)
        assertEquals(logical, PersistKeys.decode(encoded))
        NamespacedKey(SidedIoKeys.NAMESPACE, encoded)
    }

    /** 已属合法集的键应当原样通过（不做无谓转义），保持 PDC 里可读。 */
    @Test
    fun `already legal keys stay readable`() {
        val logical = "north_sidedio.item"
        assertEquals(logical, PersistKeys.encode(logical))
    }

    /** `-` 本身被转义 —— 否则解码会有歧义。 */
    @Test
    fun `dash is escaped so decoding is unambiguous`() {
        val logical = "a-b"
        val encoded = PersistKeys.encode(logical)
        assertTrue(encoded.contains("-2d"), "破折号应被转义成 -2d，实际：$encoded")
        assertEquals(logical, PersistKeys.decode(encoded))
    }

    @Test
    fun `decode rejects truncated escape`() {
        assertNull(PersistKeys.decode("north-4"))
    }

    @Test
    fun `decode rejects non hex escape`() {
        assertNull(PersistKeys.decode("north-zz"))
    }

    /** 空串两个方向都不该炸。 */
    @Test
    fun `empty string round trips`() {
        assertEquals("", PersistKeys.encode(""))
        assertEquals("", PersistKeys.decode(""))
    }

    /** 老格式（只有面名，无点）也必须能过 —— 兼容旧存档。 */
    @Test
    fun `legacy face only key round trips`() {
        val encoded = PersistKeys.encode("NORTH")
        assertEquals("NORTH", PersistKeys.decode(encoded))
        assertNotNull(NamespacedKey(SidedIoKeys.NAMESPACE, encoded))
    }
}
