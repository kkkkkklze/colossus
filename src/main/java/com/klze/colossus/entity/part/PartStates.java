package com.klze.colossus.entity.part;

/**
 * 部件状态位编码（首领崛起 {@code DATA_DAMAGED_SEGMENTS} 位图模式的通用化）。
 *
 * <p>部件自己<b>不拥有网络</b>（PartEntity 不被原版追踪），全部状态压进
 * parent 的 entityData 位图（一个 {@code long}，3 bit × 21 部件 = 63 bit），
 * 客户端 {@code onSyncedDataUpdated} 回放到部件实例——"部件零包"铁律的编解码层。
 * 纯函数，可无头单测。
 */
public final class PartStates {

    /** 每部件占 3 bit（active/damaged/dead），long 内至多 21 个部件。 */
    public static final int BITS_PER_PART = 3;
    public static final int MAX_PARTS = 21;

    public static final int FLAG_ACTIVE = 0b001;
    public static final int FLAG_DAMAGED = 0b010;
    public static final int FLAG_DEAD = 0b100;

    private PartStates() {}

    public static boolean hasFlag(long bits, int partIndex, int flag) {
        check(partIndex);
        return ((int) (bits >>> (partIndex * BITS_PER_PART)) & flag) == flag;
    }

    /** 置/清布尔（唯一对外写口）。 */
    public static long withFlag(long bits, int partIndex, int flag, boolean on) {
        check(partIndex);
        long mask = (long) flag << (partIndex * BITS_PER_PART);
        return on ? bits | mask : bits & ~mask;
    }

    private static void check(int partIndex) {
        if (partIndex < 0 || partIndex >= MAX_PARTS) {
            throw new IllegalArgumentException(
                    "partIndex " + partIndex + " out of range [0," + MAX_PARTS + ")");
        }
    }
}
