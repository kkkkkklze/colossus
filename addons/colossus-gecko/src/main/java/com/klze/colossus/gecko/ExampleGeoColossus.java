package com.klze.colossus.gecko;

import com.klze.colossus.move.MoveSetBuilder;
import com.klze.colossus.move.MoveTriggers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** addon 自带的示范 Boss：证明"下游只 extends 适配基类"这条路走得通。 */
public class ExampleGeoColossus extends GeoColossusEntity {

    public static final ResourceLocation BOSS_ID = ColossusGecko.res("example_geo");

    public ExampleGeoColossus(EntityType<? extends ExampleGeoColossus> type, Level level) {
        super(type, level);
    }

    @Override
    protected String idleAnim() {
        return "idle";
    }

    @Override
    protected String walkAnim() {
        return "move";
    }

    @Override
    protected String deathAnim() {
        return "death";
    }

    @Override
    public ResourceLocation getBossId() {
        return BOSS_ID;
    }

    @Override
    protected void registerMoves(MoveSetBuilder m) {
        m.move("gecko_slam").duration(30).cooldown(60)
                .anim("attack_slam")
                .at(6, MoveTriggers.sound(SoundEvents.ANVIL_LAND, 1.0f, 1.0f))
                .between(14, 16, MoveTriggers.arcHit(5.0f, 120.0f, 6.0f, 0.4f))
                .done();
    }

    public static AttributeSupplier.Builder attributes() {
        return com.klze.colossus.entity.ColossusBossEntity.baseBossAttributes()
                .add(Attributes.MAX_HEALTH, 200.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28);
    }
}
