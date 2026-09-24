package com.klze.colossus.env;

import com.klze.colossus.Colossus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * 受控破块 API（环境层第二件）。配方综合三个实证样本：
 * NagaSmashGoal（蹭碎+硬度过滤）、Yeti（BREAK_n 偏移表+四向旋转）、
 * TF EntityUtil.canDestroyBlock（硬度 [0,50) 的"可破坏带"，避开基岩/终界石）。
 *
 * <p>门控三闸门：{@code RULE_MOBGRIEFING} → 硬度带 → {@code colossus:unbreakable}
 * 方块 tag（竞技场玻璃/剧情方块豁免，整合包可改）。
 */
public final class ArenaBlockAccess {

    /** 豁免 tag：任何想"打不碎"的竞技场方块都进这里（数据侧可覆盖）。 */
    public static final TagKey<Block> UNBREAKABLE =
            TagKey.create(Registries.BLOCK, Colossus.res("unbreakable"));

    /** 可破坏硬度带（TF 的上限 50 排除基岩/终界石/命令方块）。 */
    private static final float MIN_HARDNESS = 0f;
    private static final float MAX_HARDNESS = 50f;

    private ArenaBlockAccess() {}

    /** 该实体此刻能否破坏方块（mobGriefing 门控）。 */
    public static boolean canGrief(Level level, Entity who) {
        return level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    /** 单块准入：硬度带 + 非空气 + 非豁免 tag（含 dragon/wither immune）。 */
    public static boolean destroyable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(UNBREAKABLE)
                || state.is(net.minecraft.tags.BlockTags.DRAGON_IMMUNE)
                || state.is(net.minecraft.tags.BlockTags.WITHER_IMMUNE)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= MIN_HARDNESS && hardness < MAX_HARDNESS;
    }

    /**
     * 清空一个世界坐标 AABB 内的可破坏方块（Naga 蹭墙形：判定帧后扫身体盒）。
     * @return 实际破坏数
     */
    public static int clearBox(Level level, AABB box, Entity who, boolean dropItems) {
        return clearBox(level, box, who, dropItems, s -> true);
    }

    public static int clearBox(Level level, AABB box, Entity who, boolean dropItems,
                               Predicate<BlockState> extraFilter) {
        if (!canGrief(level, who)) return 0;
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x0 = floor(box.minX), x1 = floor(box.maxX);
        int y0 = floor(box.minY), y1 = floor(box.maxY);
        int z0 = floor(box.minZ), z1 = floor(box.maxZ);
        // 体积闸门（审查 P2#13）：单 tick 扫超 8192 格直接拒——招式表写错也不该卡死服务器
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 8192) {
            com.klze.colossus.Colossus.LOGGER.warn(
                    "clearBox volume too large ({}x{}x{}), refusing single-tick sweep",
                    x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
            return 0;
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (!extraFilter.test(state) || !destroyable(level, cursor, state)) continue;
                    level.destroyBlock(cursor, dropItems, who);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 朝向前方的破块方块（smash 砸地碎裂带）：以视线方向 forward 格为中心、
     * width 宽 × height 高（从脚下起）的盒子。
     */
    public static int breakAhead(Entity who, double forward, double width, double height,
                                 boolean dropItems) {
        // yBodyRot 在 LivingEntity 上；非活体（如弹实体）退回头朝向
        float bodyYaw = who instanceof net.minecraft.world.entity.LivingEntity le ? le.yBodyRot : who.getYRot();
        double dirX = -Math.sin(Math.toRadians(bodyYaw));
        double dirZ = Math.cos(Math.toRadians(bodyYaw));
        double cx = who.getX() + dirX * forward;
        double cz = who.getZ() + dirZ * forward;
        AABB box = new AABB(cx - width / 2, who.getY(), cz - width / 2,
                cx + width / 2, who.getY() + height, cz + width / 2);
        return clearBox(who.level(), box, who, dropItems);
    }

    /**
     * 偏移表模式（Yeti BREAK_1..4 形）：局部坐标列表按朝向旋转后放置方块——
     * 用于"拍地留火/留冰柱座"这类改变地形的招式。
     */
    public static int applyPattern(Level level, Vec3 origin, float yBodyRot,
                                   List<Vec3> localOffsets, BlockState state, Entity who) {
        return applyPattern(level, origin, yBodyRot, localOffsets, state, who,
                net.minecraft.world.level.block.Block.UPDATE_ALL); // 默认带同步（审查 P2#13：传 0 = 鬼块）
    }

    public static int applyPattern(Level level, Vec3 origin, float yBodyRot,
                                   List<Vec3> localOffsets, BlockState state,
                                   Entity who, int setFlags) {
        if (!canGrief(level, who)) return 0;
        int count = 0;
        for (Vec3 local : localOffsets) {
            Vec3 world = rotateFacing(local, yBodyRot);
            BlockPos pos = BlockPos.containing(origin.x + world.x, origin.y + world.y, origin.z + world.z);
            BlockState current = level.getBlockState(pos);
            if (destroyable(level, pos, current) || current.isAir()) {
                level.setBlock(pos, state, setFlags);
                count++;
            }
        }
        return count;
    }

    /** 局部偏移按四向（南/西/北/东）旋转——BR rotatedPoint 的量化形（免浮点误差）。 */
    public static Vec3 rotateFacing(Vec3 local, float yBodyRot) {
        int quadrant = Math.round(((yBodyRot % 360) + 360) % 360 / 90f) & 3;
        double x = local.x, y = local.y, z = local.z;
        return switch (quadrant) {
            case 0 -> new Vec3(x, y, z);    // 南（+Z，yRot=0）
            case 1 -> new Vec3(z, y, -x);   // 西
            case 2 -> new Vec3(-x, y, -z);  // 北
            default -> new Vec3(-z, y, x);  // 东
        };
    }

    private static int floor(double v) { return (int) Math.floor(v); }
}
