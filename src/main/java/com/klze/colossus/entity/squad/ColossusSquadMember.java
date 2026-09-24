package com.klze.colossus.entity.squad;

import java.util.UUID;

/**
 * squad 成员契约（v5 裁决：玩家可瞄准的"部件"= 真实体成员，Kraken 触手先例；
 * PartEntity 岗位是隐形受击/保护锚，两者正交）。
 *
 * <p>任何实体（怪、兽、乃至另一个 ColossusBossEntity）实现本接口即可入队；
 * 成员把击杀 credit 与参战贡献转发 leader（漏记会丢共享奖励——取证清单第 5 条）。
 */
public interface ColossusSquadMember {

    @javax.annotation.Nullable UUID squadLeaderId();

    void setSquadLeader(UUID leaderId);

    /** 排期/血条/诊断用的稳定 key（同 leader 内唯一）。 */
    String memberKey();

    /** 是否计入队长血条均值（false=纯装饰成员）。 */
    default boolean countsForBar() { return true; }

    /** 成员血比 [0,1]。 */
    float barRatio();

    /** 成员被击破（管理器会排重生）；返回 true 表示事件被消费。 */
    default boolean onSquadMemberDefeated() {
        return false;
    }

    /**
     * 队长被击杀时由 {@link SquadManager#notifyLeaderDeath} 广播：成员该收摊了。
     * 不做这件事的后果＝Boss 已死、触手还在场上打人且血条永远不满
     * （Kraken 在阶段结束处显式清理自己的触手，同一个理由）。
     */
    default void onLeaderDefeated() {
    }
}
