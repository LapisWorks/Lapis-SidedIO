package top.mc506lw.rebar.sidedio

import org.bukkit.block.BlockFace

/**
 * 一个面**相对机器**的角色 —— 前 / 后 / 左 / 右 / 上 / 下。
 *
 * ## 为什么要有它
 *
 * 光说"西面"玩家还得在脑子里换算"机器的西面是哪边"；光说"前面"又不知道
 * 在世界里是哪个方向。所以面板上两个都给：**`前面·西面`** ——
 * 前者是肌肉记忆（永远是那个朝向玩家的面），后者是实际坐标。
 *
 * ## 为什么"前面"不是固定的
 *
 * 机器有 `facing`（放置朝向），所以"前面"会随机器转动而变。
 * 面板上**前面永远画在正中间**，玩家不用管机器朝哪 ——
 * 这正是科技模组（TE / EnderIO / Mekanism）的通行做法。
 *
 * ## 左右怎么定
 *
 * 按"从机器背后往前看"的方位定：机器朝北时，左边是西。
 * 也就是说 [leftOf] 把朝向绕 Y 轴逆时针转 90°。
 */
enum class FaceRole {

    FRONT,
    BACK,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM;

    /** 翻译键：`sidedio.role.front` 这种。 */
    val translationKey: String get() = "sidedio.role.${name.lowercase()}"

    companion object {

        /**
         * 算出 [face] 相对 [facing] 的角色。
         *
         * ⚠️ [facing] 必须是**水平朝向**（NORTH/SOUTH/EAST/WEST）。
         * 如果机器没朝向（或朝向是上下这种不合法的值），调用方应先兜底成 NORTH。
         */
        fun of(face: BlockFace, facing: BlockFace): FaceRole = when (face) {
            BlockFace.UP -> TOP
            BlockFace.DOWN -> BOTTOM
            facing -> FRONT
            facing.oppositeFace -> BACK
            leftOf(facing) -> LEFT
            rightOf(facing) -> RIGHT
            // 到这儿说明 facing 本身是 UP/DOWN（不合法的机器朝向）——
            // 那种情况下"左右"没有意义，一律当背面处理，不抛异常。
            else -> BACK
        }

        /**
         * 机器左边的那个面。
         *
         * 朝向绕 Y 轴**逆时针**转 90°：朝北 → 左是西，朝西 → 左是南，以此类推。
         */
        fun leftOf(facing: BlockFace): BlockFace = when (facing) {
            BlockFace.NORTH -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.EAST
            BlockFace.EAST -> BlockFace.NORTH
            else -> BlockFace.WEST
        }

        /** 机器右边的那个面（左边的对面）。 */
        fun rightOf(facing: BlockFace): BlockFace = leftOf(facing).oppositeFace

        /**
         * 把一个面归一到**水平朝向**（给 `facing` 兜底用）。
         *
         * 机器的 `facing` 理论上是水平的，但第三方注册时可能给个 UP/DOWN，
         * 那种值会让 [of] 的四个侧面算不出来 —— 这里兜底成 NORTH。
         */
        fun normalizeFacing(facing: BlockFace?): BlockFace = when (facing) {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST -> facing
            else -> BlockFace.NORTH
        }

        /**
         * [of] 的**反函数**：给定角色和机器朝向，求出是哪个面。
         *
         * 面板上的格子是**按角色固定**的（前面永远在正中），所以画的时候
         * 要反过来问"正中那格现在对应哪个面"。
         */
        fun faceOf(role: FaceRole, facing: BlockFace): BlockFace = when (role) {
            FRONT -> facing
            BACK -> facing.oppositeFace
            LEFT -> leftOf(facing)
            RIGHT -> rightOf(facing)
            TOP -> BlockFace.UP
            BOTTOM -> BlockFace.DOWN
        }
    }
}
