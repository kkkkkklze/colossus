package com.klze.colossus.progress;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Boss 身份定义（TF BossVariant 的轻量化）：一个 Boss "作为种类"的中央登记项。
 * 战利品表、音乐、召唤物、进度板 key 都以它为连接键，避免各处散落字符串。
 *
 * <p>实体类在构造/注册时自报家门：{@link #register} 由 ColossusBossEntity 子类
 * 的静态初始化或主入口调用一次。
 */
public record BossDefinition(
        ResourceLocation id,
        EntityType<? extends com.klze.colossus.entity.ColossusBossEntity> entityType,
        @Nullable ResourceLocation music,        // 战斗主题曲（可空）
        @Nullable ResourceLocation lootTable     // v0.2：战利品缓冲入箱用；v0.1 走实体自带 loot
) {

    private static final Map<ResourceLocation, BossDefinition> REGISTRY = new HashMap<>();

    public static void register(BossDefinition def) {
        if (REGISTRY.putIfAbsent(def.id(), def) != null) {
            throw new IllegalStateException("Duplicate boss definition: " + def.id());
        }
    }

    @Nullable
    public static BossDefinition get(ResourceLocation id) { return REGISTRY.get(id); }

    public static Map<ResourceLocation, BossDefinition> all() { return Map.copyOf(REGISTRY); }
}
