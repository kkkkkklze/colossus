package com.klze.colossus.fight;

import java.util.HashSet;
import java.util.Set;

/**
 * 接触去重簿（DBE {@code HitboxContactKey} 的最小化）。
 *
 * <p>语义：一个"接触键"在一个攻击实例内只允许被消费一次——
 * 同招多判定帧、持续区每 tick 复判时，保证同一目标只吃一份伤害；
 * 要多段就把键拆开（新键 = 新段）。生命周期由宿主控制：
 * Colossus 在 {@code beginAttack} 时清空（每次出招都是全新接触集）。
 *
 * <p>纯逻辑、零 MC 依赖——进 StateSelfTest。
 */
public final class ContactBook {

    private final Set<String> claimed = new HashSet<>();

    /** 认领接触键；true = 首次消费（应结算），false = 已消费过（应跳过）。 */
    public boolean claim(String key) {
        return claimed.add(key);
    }

    public boolean has(String key) {
        return claimed.contains(key);
    }

    public int size() {
        return claimed.size();
    }

    /** 攻击实例切换/脱战时清空。 */
    public void clear() {
        claimed.clear();
    }
}
