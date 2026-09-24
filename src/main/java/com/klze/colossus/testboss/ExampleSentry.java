package com.klze.colossus.testboss;

import com.klze.colossus.entity.squad.ColossusSquadMemberEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * 示范 squad 成员（第九批·"玩家可瞄准的弱点必须做成真实体"这条取证结论的活文档）：
 * 一具钉在队长肩位、会跟着队长转向、被打死后排重生、队长倒下即收摊的浮空炮台。
 *
 * <p>它<b>不</b>覆写锚点与 key——两者都来自 {@link com.klze.colossus.entity.squad.SquadManager.MemberDef}
 * （单一来源）；只声明自己的血池与弱点倍率。
 */
public class ExampleSentry extends ColossusSquadMemberEntity {

    /** 与 {@code ExampleColossus.registerSquad} 里那条 MemberDef 的 key 同源。 */
    public static final String KEY = "sentry";

    public ExampleSentry(EntityType<? extends ExampleSentry> type, Level level) {
        super(type, level);
    }

    @Override
    protected float incomingDamageScale() {
        return 1.5f; // 弱点：直击成员更痛（只在成员这一处乘，不再叠加转发）
    }

    @Override
    protected float damageForwardRatio() {
        return 0.25f; // 打在成员身上的伤害有 1/4 同步削本体（DBE"打腿扣本体"形）
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 60.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }
}
