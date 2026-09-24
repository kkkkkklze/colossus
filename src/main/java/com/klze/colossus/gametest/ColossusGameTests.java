package com.klze.colossus.gametest;

import com.klze.colossus.Colossus;
import com.klze.colossus.ColossusRegistries;
import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.move.MoveSetBuilder;
import com.klze.colossus.progress.BossKillBoard;
import com.klze.colossus.testboss.ExampleColossus;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 真实世界的回归门（runGameTestServer，免 EULA、中文路径可用、失败退出码 1——
 * 三证均取自姊妹工程 forge-1.20.1-mod-2/-3 的 CI 实践）。
 *
 * <p>专治两轮审查暴露的盲区：mock 驱动的自检覆盖不到与 MC 生命周期
 * （die/hurt/存档/卸载）交互的分支——"断状态而非存在过"，跨 tick 用 runAfterDelay。
 */
@GameTestHolder(Colossus.MODID)
@PrefixGameTestTemplate(false)
public class ColossusGameTests {

    /** 9×9 空场（结构区内实体保证 tick——姊妹工程 flaky 教训：边界外实体不 tick 会时绿时红）。 */
    private static final String YARD = "colossus_yard";

    /**
     * 击杀路径全链回归（v0.2 P0 的防重现桩）：
     * 致死伤害 → 死亡演出(100t) + 原版死亡序列(20t) → 实体必须真的被移除、
     * KillBoard 必须记到首杀。P0 症状=击杀后 Boss 变不移除的 1 血雕像。
     *
     * <p>隔离两重：①跨运行——run/data 留着上一局的 KillBoard，所以断增量不是绝对值；
     * ②跨测试——KillBoard 是 level 级 SavedData，同 batch 里并发跑的别的会击杀 Boss
     * （第八批 arena 测试就是这么把增量打成 +2 的）。故每个会结算击杀的测试独占一个 batch
     * （{@code GameTestBatchRunner.runBatch} 逐批串行）。
     */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "kill-path")
    public void killPathRemovesBossAndRecordsKill(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var mock = helper.makeMockPlayer(); // 1.20.1：无参形态（GameType 参数是 1.20.2+）
        // 跨运行隔离：run/data 可能留着上一局的 KillBoard——断言增量而非绝对值
        int killsBefore = BossKillBoard.get(helper.getLevel()).killCount(ExampleColossus.BOSS_ID);
        boss.hurt(boss.damageSources().playerAttack(mock), 10_000f);
        helper.assertTrue(boss.isDeathPending(), "致死伤害后应进入死亡演出（挂起路径断裂）");

        helper.runAfterDelay(180, () -> {
            helper.assertTrue(boss.isRemoved(),
                    "击杀结算后 Boss 必须被移除（还活着=P0 死亡循环重现）");
            BossKillBoard board = BossKillBoard.get(helper.getLevel());
            helper.assertTrue(board.killCount(ExampleColossus.BOSS_ID) == killsBefore + 1,
                    "KillBoard 击杀计数应 +1，实际 " + board.killCount(ExampleColossus.BOSS_ID)
                            + "（开局 " + killsBefore + "）");
            helper.succeed();
        });
    }

    /**
     * 护壳弱点回归（第六批分体系统的玩家可达路径）：核心在位→本体减伤；
     * 分流累计攒满阈值→核心碎（位图清 ACTIVE 置 DEAD）、后续伤害恢复全额。
     */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "shell-part")
    public void coreProtectionAbsorbsThenBreaks(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        boss.setHealth(boss.getMaxHealth());
        var mock = helper.makeMockPlayer();
        helper.assertTrue(boss.partActive(0), "初始应有 ACTIVE 护壳");

        // 受控连击：每次手动清 invulnerableTime（护壳路径的合法测试操纵，同 MoveTriggers.strike 语义）
        // 首刀也钉一次：满血 240 → 每刀 20 落 6 血，11 刀攒满 150 分流阈值（14/刀）
        final float firstLoss;
        boss.invulnerableTime = 0;
        boss.hurt(boss.damageSources().playerAttack(mock), 20f);
        firstLoss = boss.getMaxHealth() - boss.getHealth();
        for (int i = 0; i < 12 && boss.partActive(0); i++) {
            boss.invulnerableTime = 0;
            boss.hurt(boss.damageSources().playerAttack(mock), 20f);
        }
        final float protectedLoss = firstLoss;
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(protectedLoss < 8f,
                    "护壳在位时应大幅减伤，实际掉血 " + protectedLoss);
            helper.assertFalse(boss.partActive(0), "累计分流（每刀 20×0.7=14，约 11 刀）≥ 150 后核心应被击碎");
            helper.assertTrue(boss.partFlag(0,
                            com.klze.colossus.entity.part.PartStates.FLAG_DEAD),
                    "击碎应置 DEAD 位（同步给客户端做碎裂表现）");
            // 钉住前提再验全额落血：碎壳时本体可能已被减伤打法磨掉半血，
            // 不钉的话 maxHealth 过低会让 20 伤被血量上限截断，断言假红。
            boss.setHealth(boss.getMaxHealth());
            boss.invulnerableTime = 0;
            float before = boss.getHealth();
            boss.hurt(boss.damageSources().playerAttack(mock), 20f);
            float fullLoss = before - boss.getHealth();
            helper.assertTrue(fullLoss > 8f,
                    "碎壳后同额伤害应接近全额落血，实际 " + fullLoss);
            helper.succeed();
        });
    }

    /**
     * 竞技场封路回归（第七/八批 env 层唯一可无头验证的路径）：
     * 开战上升沿 → 封路位换成封印方块且入快照；击杀 → 快照位全部还原成空气。
     * P0 症状=会话解除了但快照没落回（深板岩永久糊在竞技场上）。
     */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "arena-seal")
    public void arenaSealsOnActivateAndRestoresOnKill(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var mock = helper.makeMockPlayer();
        boss.hurt(boss.damageSources().playerAttack(mock), 1.0f); // 只点着，不打死

        helper.runAfterDelay(2, () -> {
            var arena = boss.arena();
            helper.assertTrue(arena != null && arena.isActive(),
                    "activated 上升沿应开启竞技场会话");
            var sealed = new java.util.ArrayList<>(arena.sealedPositions());
            helper.assertTrue(sealed.size() == 2,
                    "两个封路偏移都该入快照（未入快照的位=解封时没人还原）");
            // 快照里是世界绝对坐标；helper.getBlockState 收的是**结构局部**坐标（内部 absolutePos 转换），
            // 拿绝对坐标喂它会静默读到别处的方块——本轮首跑就是这么假红的。
            for (BlockPos pos : sealed) {
                helper.assertTrue(helper.getLevel().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.DEEPSLATE),
                        "封路位 " + pos + " 应为封印方块，实际 "
                                + helper.getLevel().getBlockState(pos).getBlock());
            }
            boss.hurt(boss.damageSources().playerAttack(mock), 10_000f);
            helper.runAfterDelay(180, () -> {
                helper.assertTrue(boss.isRemoved(), "Boss 应已结算移除");
                helper.assertTrue(arena.sealedPositions().isEmpty(), "解封后快照必须清空");
                for (BlockPos pos : sealed) {
                    helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(),
                            "封路位 " + pos + " 必须还原为空气，实际 "
                                    + helper.getLevel().getBlockState(pos).getBlock());
                }
                helper.succeed();
            });
        });
    }

    /**
     * squad 身份账回归（第八批 P1 防重现桩 + 第九批成员基类接线）。
     *
     * <p>判别式设计：旧写法用 {@code AABB inflate(96)} 扫场认身份，所以<b>任何一次"当下查不到人"</b>
     * （成员在没加载的区块里、被别的 mod 清掉、被虚空吞了）都会<b>立刻</b>补出一个新的；
     * 新写法认 UUID 账，未解析先熬 {@code LOST_GRACE=200t}。于是"成员消失后 20t 内场上必须还是 0 具、
     * 230t 后必须补回 1 具"这两条能把新旧两种实现直接区分开——比"把成员挪远"更可判
     * （锚点跟随的成员下一 tick 就被拉回肩上，物理挪动测不出东西）。
     */
    @GameTest(template = YARD, timeoutTicks = 700, batch = "squad-ledger")
    public void squadLedgerSpawnOnceAndCleanupWithLeader(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var mock = helper.makeMockPlayer();
        boss.hurt(boss.damageSources().playerAttack(mock), 1.0f); // 点着，触发补员
        final String key = com.klze.colossus.testboss.ExampleSentry.KEY;

        helper.runAfterDelay(6, () -> {
            var first = sentriesAround(helper, boss);
            helper.assertTrue(first.size() == 1, "开战应恰好补出一个成员，实际 " + first.size());
            var sentry = first.get(0);
            helper.assertTrue(key.equals(sentry.memberKey()),
                    "成员 key 应由 SquadManager 从定义注入，实际 \"" + sentry.memberKey() + "\"");
            helper.assertTrue(sentry.position().distanceTo(boss.position().add(0, 2.6, 0)) < 1.5,
                    "成员应钉在队长肩位锚点上，实际偏 "
                            + sentry.position().distanceTo(boss.position().add(0, 2.6, 0)));
            helper.assertTrue(boss.squad().trackedKeys().contains(key), "身份账里必须有这个 key");

            sentry.discard(); // 模拟"被别的 mod 清了/吞进虚空"：不走 die，因此也不排重生
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(sentriesAround(helper, boss).isEmpty(),
                        "查无此人在宽限期内不得补位（旧扫场形此刻会立刻刷出第二具）");
                helper.assertTrue(boss.squad().trackedKeys().contains(key),
                        "宽限期内账籍不能先销——销了就等于回到扫场形");
            });
            helper.runAfterDelay(240, () -> {
                var back = sentriesAround(helper, boss);
                helper.assertTrue(back.size() == 1,
                        "熬过 LOST_GRACE 后必须补回一具，实际 " + back.size() + "（0=宽限期没有出口，成员永远失踪）");
                helper.assertTrue(key.equals(back.get(0).memberKey()),
                        "补回来的那具必须仍挂同一个 key（key 由定义注入，换人不能换身份）");

                boss.hurt(boss.damageSources().playerAttack(mock), 10_000f);
                helper.runAfterDelay(180, () -> {
                    helper.assertTrue(boss.isRemoved(), "Boss 应已结算移除");
                    helper.assertTrue(sentriesAround(helper, boss).isEmpty(),
                            "队长倒下后成员必须收摊（还杵着=打完还在被触手抽）");
                    // 这条才是"排期关掉"的判别式（审查轮 5 P2-5a：原先等 140t 数实体是恒真断言——
                    // 那时 Boss 已 isRemoved，squad.tick 整个停摆，删掉 leaderDown 门也照样绿）
                    helper.assertTrue(boss.squad().respawnPending() == 0,
                            "收摊引起的成员之死不得再排重生，实际在途 " + boss.squad().respawnPending() + " 条");
                    helper.succeed();
                });
            });
        });
    }

    /**
     * 场上属于**这个 Boss**的示范成员（独立裁判：读成员自己身上的 leaderUUID，不吃 SquadManager 的账）。
     *
     * <p>为什么不能只按类+大窗口数：GameTest 会把多个结构放在彼此不远的地方，
     * {@code inflate(400)} 的窗口曾把兄弟测试的 Boss 补出的成员一起数进来（实测数出 3 具）。
     */
    private static java.util.List<com.klze.colossus.testboss.ExampleSentry> sentriesAround(
            GameTestHelper helper, ColossusBossEntity boss) {
        java.util.List<com.klze.colossus.testboss.ExampleSentry> out = new java.util.ArrayList<>();
        for (com.klze.colossus.testboss.ExampleSentry s : helper.getLevel().getEntitiesOfClass(
                com.klze.colossus.testboss.ExampleSentry.class,
                boss.getBoundingBox().inflate(64, 64, 64))) {
            if (boss.getUUID().equals(s.squadLeaderId())) out.add(s);
        }
        return out;
    }

    /**
     * 出招序号回归（GL4 一手源码取证点名的事故形）：**连放同一招**时招式身份与
     * {@code ATTACK_INDEX} 都不变，只有 {@code attackSequence()} 递增——
     * 客户端若拿 index 当"新一次施法"的判据，第二下就不会重启动画（原地卡住）。
     * 顺带钉住 {@code forceMove} 的不可打断闸门。
     */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "anim-seq")
    public void repeatedCastBumpsSequenceNotIdentity(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var roar = Colossus.res("roar");
        int s0 = boss.attackSequence();
        helper.assertTrue(boss.forceMove(roar), "空闲时应能强制出招（剧情/回归口子）");
        helper.assertTrue(boss.attackSequence() == s0 + 1,
                "首次出招序号应 +1，实际 " + boss.attackSequence());
        helper.assertTrue(roar.equals(boss.currentAttack() == null ? null : boss.currentAttack().id()),
                "forceMove 后当前招式应为 roar");

        helper.runAfterDelay(6, () -> {
            helper.assertFalse(boss.forceMove(roar), "施法中再 forceMove 必须被拒（不可打断闸门失守）");
            helper.assertTrue(boss.attackSequence() == s0 + 1,
                    "被拒的出招不得推进序号，否则客户端会凭空重启一次动画");
            helper.assertTrue(boss.attackTick() > 0, "帧表应随 tick 推进（attackTick 恒 0＝状态机没跑）");
        });
        // 第二次施法：等到 Boss 真的空下来（它会自己选招——射程内有别的结构的实体当目标，
        // 所以"固定时刻必然空闲"这种前提是错的，改成轮询到有结果为止）
        class Retry {
            int attempts = 0;

            void run() {
                if (boss.forceMove(roar)) {
                    helper.assertTrue(boss.attackSequence() == s0 + 2,
                            "同招二连的序号必须再 +1（这是动画重启的唯一判据），实际 "
                                    + boss.attackSequence());
                    helper.assertTrue(roar.equals(boss.currentAttack().id()),
                            "而招式身份不变——正说明「只有序号能区分两次施法」");
                    helper.succeed();
                    return;
                }
                if (++attempts > 40) {
                    helper.fail("轮询 400t 仍没能再次强制出招：Boss 卡在非空闲状态或闸门失效");
                    return;
                }
                helper.runAfterDelay(10, this::run);
            }
        }
        helper.runAfterDelay(40, () -> new Retry().run());
    }

    /**
     * 附加资源条回归（第十一批）：护盾在<b>护壳分流之后</b>吃伤、吸收期间本体不掉血、
     * 见底后溢出落本体、脱战回充、破盾不吞参战记账。
     * 症状对照：护盾形同虚设（吸收没生效）／"永远吸收不掉本体"（溢出算错）／纯吸收的攻击丢了 credit。
     */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "shield-meter")
    public void shieldAbsorbsThenOverflowHitsBody(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var mock = helper.makeMockPlayer();
        boss.setHealth(60.0f); // 上限 240 的 25% → 两道相位闸门 → 相位 2 → maxShield()=100

        // 闸门是<b>串行</b>的：一次只进一相，每道过场 phaseTransitionTicks()=60t
        // → 到相位 2 至少 120t。首跑按 30t 断言就红了（相位还是 0）。
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(boss.getPhase() >= 2,
                    "血量 25% 应已进相位 2，实际 phase=" + boss.getPhase());
            helper.assertTrue(boss.shield() >= 99.0f,
                    "相位 2 应回充出护盾，实际 " + boss.shield());
            float hpBefore = boss.getHealth();

            boss.invulnerableTime = 0;
            boss.hurt(boss.damageSources().playerAttack(mock), 20.0f); // ×护壳0.3=6 → 全进盾
            helper.assertTrue(boss.shield() < 99.0f,
                    "护盾必须吸收这一刀，实际仍为 " + boss.shield());
            helper.assertTrue(boss.getHealth() == hpBefore,
                    "护盾未破期间本体不得掉血（实际 " + boss.getHealth() + "）");

            boss.setShield(5.0f);
            boss.invulnerableTime = 0;
            boss.hurt(boss.damageSources().playerAttack(mock), 100.0f); // ×0.3=30 → 盾 5 + 溢出 25
            helper.assertTrue(boss.shield() == 0.0f, "溢出后护盾必须见底");
            helper.assertTrue(boss.getHealth() < hpBefore,
                    "溢出伤害必须落本体，实际仍为 " + boss.getHealth());
            helper.assertTrue(boss.isAlive() && !boss.isDeathPending(), "这一刀不该致死");
            helper.succeed();
        });
    }

    /**
     * 全局进度快照回归（第十三批）：真 SavedData 上走一次 recordKill，
     * 断言代际号 +1、快照编解码与权威表逐项相等、脏检查两侧都对。
     * 症状对照：包发了但镜像是旧的（encode/decode 不对称）、
     * 或"同值也发"（shouldSend 反了 → 每 20t 全员重播全表）。
     */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "progress-sync")
    public void progressSnapshotMatchesLiveBoard(GameTestHelper helper) {
        var board = BossKillBoard.get(helper.getLevel());
        long revBefore = board.revision();
        var probe = Colossus.res("gametest_probe_boss");

        board.recordKill(probe);
        helper.assertTrue(board.revision() == revBefore + 1,
                "recordKill 必须推进代际号，实际 " + board.revision() + "（开局 " + revBefore + "）");

        var decoded = com.klze.colossus.progress.ProgressLedger.decode(board.snapshot());
        helper.assertTrue(decoded.revision() == board.revision(),
                "快照代际号必须与权威表一致");
        helper.assertTrue(decoded.killCount(probe.toString()) == board.killCount(probe),
                "快照里的击杀数要与权威表相等，实际 " + decoded.killCount(probe.toString()));
        helper.assertTrue(decoded.isDefeated(probe.toString()), "已击败集合要进快照");
        helper.assertTrue(com.klze.colossus.progress.ProgressLedger.shouldSend(revBefore, board.revision()),
                "变化过的代际号该发");
        helper.assertTrue(!com.klze.colossus.progress.ProgressLedger.shouldSend(
                        board.revision(), board.revision()),
                "同值不该发（每 20t 全表重播是点名反面）");
        helper.succeed();
    }

    /**
     * 登记期校验回归（审查 P1#3）：坏窗口必须 build 招式表时就抛——
     * 留到出招那 tick 抛＝炸在 serverAiStep 里，持久 Boss 变崩溃循环。
     */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "move-register")
    public void badMoveWindowFailsAtRegistration(GameTestHelper helper) {
        boolean threwShort = false;
        boolean threwPastDuration = false;
        try {
            new MoveSetBuilder(null, Colossus.res("gametest"))
                    .move("bad").duration(10).between(9, 2, null);
        } catch (IllegalArgumentException e) {
            threwShort = true;
        }
        try {
            // 永不可能的帧（from > duration）在 done() 抛；窗口尾部超出 duration 是合法的（首进即触发）
            new MoveSetBuilder(null, Colossus.res("gametest"))
                    .move("bad2").between(14, 16, null).duration(10).done();
        } catch (IllegalArgumentException e) {
            threwPastDuration = true;
        }
        helper.assertTrue(threwShort, "to<from 应在登记期抛 IllegalArgumentException");
        helper.assertTrue(threwPastDuration, "from>duration 的帧永不可触发，应在登记期（done）抛");
        helper.succeed();
    }
}
