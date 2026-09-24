package com.klze.colossus.state;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 状态机与 vanilla Goal 体系的桥（首领崛起 StateGoal 模式）：
 * 状态栈非空时本 goal 抢走 MOVE+LOOK 控制权，导航停摆——战斗中一切表现由状态说了算；
 * 栈空（idle）时其余 goal（追击/锁定目标）自然接管。
 *
 * <p>注意：状态推进本身在 {@code serverAiStep} 里驱动（确定性优先），本 goal 只做"占锁"。
 */
public class ColossusStateGoal extends Goal {

    private final ColossusBossEntity boss;

    public ColossusStateGoal(ColossusBossEntity boss) {
        this.boss = boss;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return !boss.getStateController().isIdle();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
