package com.klze.colossus.testboss;

import com.klze.colossus.Colossus;
import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.move.MoveSetBuilder;
import com.klze.colossus.move.MoveTriggers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * 示范 Boss——框架的活文档。一个 Boss = 本类（约 60 行）+ 模型 + 渲染器。
 *
 * <p>展示的能力：4 招（近战两段/吼叫/阶段 1 解锁的流星）、双段阈值阶段、
 * 距离自适应权重、扇形与圆形判定帧、类型化客户端演出事件。
 */
public class ExampleColossus extends ColossusBossEntity {

    public static final ResourceLocation BOSS_ID = Colossus.res("example");

    /**
     * 分体演示——"护壳核心"（PartEntity 岗位的弱点机制，服务端权威零客户端注入）：
     * 核心在位时本体只吃 30% 伤害，被分流出去的 70% 记账，攒满 150 核心碎、减伤消失。
     */
    public ExampleColossus(EntityType<? extends ExampleColossus> type, Level level) {
        super(type, level);
        registerPart(new com.klze.colossus.entity.part.ColossusBossPart<>(
                this, 0, new net.minecraft.world.phys.Vec3(0.0, 1.4, -0.9), 1.0f, 1.4f) {
            @Override
            public float damageMultiplier() {
                return 1.5f; // 直击部件（特效/路由路径）仍吃弱点倍率
            }
        });
    }

    @Override
    protected float incomingDamageScale(net.minecraft.world.damagesource.DamageSource source) {
        return partActive(0) ? 0.3f : 1.0f; // 核心在位：本体减伤 70%
    }

    @Override
    protected float partBreakThreshold(int partIndex) {
        return partIndex == 0 ? 150f : 0f; // 分流累计 150 击碎核心
    }

    /**
     * 附加资源条演示（第十一批）：<b>相位 2 起</b>长 100 点护盾，受击 20t 后快速回充。
     * 速率故意给高（50/t），让回归桩能在几十个 tick 内看到"长出盾→吸收→破→再回充"的整环；
     * 真内容按自己的节奏调这三个钩子，框架不预设数值。
     */
    @Override
    protected float maxShield() {
        return getPhase() >= 2 ? 100.0f : 0.0f;
    }

    @Override
    protected int shieldRegenDelayTicks() {
        return 20;
    }

    @Override
    protected float shieldRegenPerTick() {
        return 50.0f;
    }

    /**
     * 竞技场演示（第七批·活文档）：开战瞬间把本体左右 4 格塞成深板岩（BR closeOffExit 形），
     * 击杀或团灭时按快照原位恢复。默认界=以开战点为心的 64×32 半轴（v7：界锚 home 不锚 Boss），
     * 宽限 200t 后提醒并拉回。
     */
    @Override
    protected com.klze.colossus.env.ArenaSession.Spec arenaSpec() {
        return com.klze.colossus.env.ArenaSession.Spec.of(
                java.util.List.of(new net.minecraft.world.phys.Vec3(4.0, 0.0, 0.0),
                        new net.minecraft.world.phys.Vec3(-4.0, 0.0, 0.0)),
                net.minecraft.world.level.block.Blocks.DEEPSLATE);
    }

    /**
     * squad 演示（第九批）：肩位一具浮游炮台。锚点/key 只在这里声明一次（成员实体不复写），
     * 击破后 100t 重生，队长倒下时它跟着收摊。
     */
    @Override
    protected void registerSquad(com.klze.colossus.entity.squad.SquadManager squad) {
        squad.add(new com.klze.colossus.entity.squad.SquadManager.MemberDef(
                ExampleSentry.KEY,
                com.klze.colossus.ColossusRegistries.EXAMPLE_SENTRY.get(),
                new net.minecraft.world.phys.Vec3(0.0, 2.6, 0.0),
                100L));
    }

    @Override
    public ResourceLocation getBossId() {
        return BOSS_ID;
    }

    public static AttributeSupplier.Builder attributes() {
        return ColossusBossEntity.baseBossAttributes()
                .add(Attributes.MAX_HEALTH, 240.0)
                .add(Attributes.ATTACK_DAMAGE, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28);
    }

    /** 演示战利品管线：演出结束在尸体处放箱（data/colossus/loot_tables/entities/example_colossus.json）。 */
    @Override
    protected com.klze.colossus.loot.LootDelivery lootDelivery() {
        return com.klze.colossus.loot.LootDelivery.INTO_CHEST;
    }

    @Override
    protected void registerMoves(MoveSetBuilder m) {
        // 重锤：主力近战。双判定帧共享接触键 "smash"——同一目标整招至多吃一刀（先到先算）
        m.move("smash")
                .duration(36).cooldown(70).range(7.5f).weight(3)
                .anim("attack_smash")
                .at(10, MoveTriggers.sound(SoundEvents.ANVIL_LAND, 1.0f, 0.7f))
                .between(24, 26, MoveTriggers.arcHitContacted("smash", 6.5f, 100f, 8.0f, 0.6f))
                .at(30, MoveTriggers.arcHitContacted("smash", 6.0f, 120f, 4.0f, 0.0f)) // 补刀帧，漏网者吃
                .at(24, MoveTriggers.event("example:smash_ring"))
                .at(24, MoveTriggers.cue(com.klze.colossus.fx.ScreenShakeCue.TYPE,
                        (b, t) -> b.position(),
                        (b, t) -> new com.klze.colossus.fx.ScreenShakeCue.Data(4.0f, 14, 20.0f)))
                .at(26, MoveTriggers.breakAhead(2.5, 3.0, 1.6, false)) // 砸地碎裂带（受 mobGriefing 门控）
                .done();

        // 横扫：贴身高权重（ctx 自适应选招）；第二十批再加一层" anti-repeat"——
        // 最近 3 次出招里放过横扫，这次就不许再放（连续横扫是这套招最容易被打背板的一招）。
        // DSL 侧走 ctx.usedRecently(3)，JSON 侧走 requires.not_recent / weight.recent_band，同一份历史两种用法。
        m.move("sweep")
                .duration(24).cooldown(40).range(4.5f)
                .weight(ctx -> ctx.distSq() < 9 ? 5 : 1)
                .requires(ctx -> !ctx.usedRecently(3))
                .anim("attack_sweep")
                .at(14, MoveTriggers.arcHit(4.5f, 160f, 6.0f, 0.9f))
                .done();

        // 咆哮：解控近身的圈
        m.move("roar")
                .duration(30).cooldown(140)
                .anim("attack_roar")
                .at(4, MoveTriggers.sound(SoundEvents.WITHER_SPAWN, 1.0f, 1.0f))
                .at(4, MoveTriggers.event("example:roar"))
                .at(22, MoveTriggers.circleHit(10.0f, 4.0f, 1.3f))
                .done();

        // 流星：阶段 1 解锁的远程压制（与近战招互斥定位：requires 距离门）
        m.move("meteor")
                .duration(50).cooldown(120).phase(1, 9).weight(2)
                .requires(ctx -> ctx.distSq() > 36)
                .postInvuln(10) // 砸中者 10t 内吃不了其他攻击（防连招打断施法后摇）
                .anim("attack_meteor")
                .between(30, 32, MoveTriggers.circleHit(14.0f, 9.0f, 0.4f))
                .at(30, MoveTriggers.event("example:meteor_landing"))
                .at(31, MoveTriggers.cue(com.klze.colossus.fx.ScreenShakeCue.TYPE,
                        (b, t) -> b.position(),
                        (b, t) -> new com.klze.colossus.fx.ScreenShakeCue.Data(6.0f, 20, 32.0f)))
                .done();

        // 冰环：TelegraphZone 演示——前方 5 格半径 4 的危险圈，红色轮廓亮 30t 后结算
        m.move("icering")
                .duration(40).cooldown(100).phase(1, 9).weight(2)
                .anim("attack_icering")
                .at(6, MoveTriggers.telegraph(
                        b -> com.klze.colossus.env.TelegraphZone
                                .damageCircle(b, 5.0, 0, 4.0, 30, 0x66CCFF).withVisual("ring"),
                        b -> new com.klze.colossus.env.ZoneBurst(6.0f, 0.5f, 0)))
                .at(6, MoveTriggers.sound(SoundEvents.GENERIC_EXPLODE, 1.2f, 1.4f))
                // 圈爆同时沿视线扫一道冰锥（HitSolver：线段扫掠，大位移不漏目标）
                .at(37, MoveTriggers.sweepHit(8.0, 5.0f, 0.6f))
                .done();

        // 火墙（第十批·持续帧示范）：危险区画满整窗，伤害由 repeating 帧每 10t 复判一次。
        // 触发器没有新增类型——circleHit 复触发＝持续掉血；换 arcHitContacted 复触发＝每人整招只吃一次。
        m.move("flamewall")
                .duration(80).cooldown(160).phase(1, 9).weight(2)
                .anim("attack_flamewall")
                .at(6, MoveTriggers.telegraphVisual(
                        b -> com.klze.colossus.env.TelegraphZone
                                .damageCircle(b, 4.0, 0, 5.0, 68, 0xFF7A29).withVisual("ring")))
                .at(6, MoveTriggers.sound(SoundEvents.GENERIC_EXPLODE, 1.0f, 0.7f))
                .repeating(12, 72, 10, MoveTriggers.circleHit(5.0f, 2.5f, 0.1f))
                .done();
    }
}
