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
     * 本桩专用的可计数待办种类（轮 7）：处理器只往**这个 Boss 实例自己的** persistentData
     * 上加一改——别的桩在世界里怎么串扰都动不到它，于是"到期时刻对不对""一发落没落第二次"
     * 这两条判据第一次有了隔离的观测面。
     */
    private static final String PING_KIND = "colossus:gametest_ping";
    private static final String PING_KEY = "colossus_gametest_ping";

    static {
        try {
            ColossusBossEntity.registerDeferredWork(PING_KIND, (boss, data) -> boss.getPersistentData()
                    .putInt(PING_KEY, boss.getPersistentData().getInt(PING_KEY) + 1));
        } catch (IllegalStateException alreadyRegistered) {
            // 同一 JVM 二次加载本类：种类已在注册表里，无需第二个 handler
        }
    }

    /** 读隔离计数器（0＝一次都没落）。 */
    private static int pingCount(ColossusBossEntity boss) {
        return boss.getPersistentData().getInt(PING_KEY);
    }

    /**
     * 收尾：先清掉本桩立起来的 Boss，再报成功（轮 7）。
     *
     * <p>为什么必须清：{@code GameTestBatchRunner} 是**逐批串行**的，但**结构从不清场**——
     * 一个测试留下的活 Boss 会在后面每一批里继续 tick。本轮实测撞到的后果：
     * 它自己被打死/掉出世界 → {@code resolveDeath} 往 level 级 KillBoard 记一刀
     * （kill-path 的"增量 +1"打成 +2，实测两轮复现）。
     * 已移除的实体传进来也无妨，{@code discard()} 自己会跳过。
     */
    private static void succeedClean(GameTestHelper helper, ColossusBossEntity... bosses) {
        for (ColossusBossEntity b : bosses) {
            if (b != null && !b.isRemoved()) b.discard();
        }
        helper.succeed();
    }

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
            succeedClean(helper, boss);
        });
    }

    /**
     * 已结算尸体过档回归（审查轮 8 P1，v0.2 P0 的第二个入口）。
     *
     * <p>症状：玩家杀死 Boss 后，在这 20t 收尸窗口内退出世界/区块卸载 → 重进世界原地站着
     * 一具 1 血、会正常选招攻击、永不消失的 Boss；任何人碰它一下就走完整遍死亡流程 ——
     * {@code KillBoard} 二次计数 + 战利品二次发放（缓冲已置 null，防重 roll 那道门失效）。
     *
     * <p>成因：{@code colossus_dying} 存的是 {@code deathPending && !deathResolved}，
     * 结算之后它就是 false（所以读档不续演出），而血量已是 0；hp 回填的 clamp 下限把 0 抬成 1.0。
     *
     * <p>时间线（1.20.1 一手源码）：{@code deathAnimationTicks()=100} ⇒ t≈101 结算并
     * {@code setHealth(0)}（{@code ColossusBossEntity.resolveDeath}）；原版 {@code tickDeath}
     * 要到 {@code deathTime >= 20} 才 {@code remove(KILLED)}（{@code LivingEntity:546-551}），
     * 而 {@code DeathTime} <b>是入档的</b>（{@code LivingEntity:672} 写 {@code putShort("DeathTime")}、
     * {@code :720} 读回——轮 8 我在 docstring 里写过"不入档"，轮 9 更正）⇒ 重载那具从存档里的
     * {@code deathTime}（约 9）接着走剩下 ~11t，桩在 +60t 取判足够宽。
     */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "death-resolve-save")
    public void resolvedCorpseDoesNotResurrectAsOneHpStatue(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var mock = helper.makeMockPlayer();
        boss.hurt(boss.damageSources().playerAttack(mock), 10_000f);

        helper.runAfterDelay(110, () -> {
            // 先自证时间线，否则整桩测的是别的东西
            helper.assertFalse(boss.isRemoved(),
                    "110t 应仍在原版收尸的 20t 窗口内（太早/太晚都要重取时点）");
            helper.assertTrue(boss.getHealth() <= 0.0f,
                    "结算后血量应为 0，实际 " + boss.getHealth() + "（＝演出还没结束，过档拿不到尸体形态）");
            int killsAtSave = BossKillBoard.get(helper.getLevel()).killCount(ExampleColossus.BOSS_ID);

            var tag = new net.minecraft.nbt.CompoundTag();
            boss.saveWithoutId(tag);
            // 模拟"这一档随卸载离场"：原尸必须先退场。`load(tag)` 会把新实体的 UUID 改成旧那具的，
            // 场上同 UUID 两具实体时按 UUID 反查就成歧义态（`ServerLevel.getEntity(UUID)` 走
            // `getEntities().get(uuid)` 一张表，一个键只能对一个值；`addWithUUID` 撞上重复 UUID
            // 时也只是打一条 warn）——本桩判据都持引用，不让归因脏在这里。
            boss.discard();
            ColossusBossEntity loaded = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                    new BlockPos(4, 3, 4));
            loaded.load(tag);
            // 这两条是原 bug 的正面判据：hp_ratio 的 clamp 下限 1.0f 会把尸体抬成 1 血，
            // 而 colossus_dying=false 让挂起位补不回来 → 它会照常选招
            helper.assertTrue(loaded.getHealth() <= 0.0f,
                    "读档不许给已结算的尸体抬血，实际 " + loaded.getHealth() + "（＝1 血雕像重现）");
            helper.assertTrue(loaded.isDeathPending(),
                    "读档应认出「这具已结算」并把死亡挂起补回来（否则它会照常选招攻击）");

            // 判别式补强（轮 9）：原症状是"谁碰它一下就走完整遍死亡流程"，光看血量并不能证这条路被堵死。
            // 修前：hp 被抬成 1.0 且 deathPending=false → hurt 受理 → die() → 二次演出 → 二次结算；
            // 修后：死亡挂起在，hurt 必须直接拒（顺带证明抬血覆写没被绕开）。
            boolean accepted = loaded.hurt(loaded.damageSources().playerAttack(mock), 1.0f);
            helper.assertFalse(accepted, "碰尸体这一刀必须被拒（受理＝二次结算那条路又通了）");
            helper.assertTrue(loaded.getHealth() <= 0.0f,
                    "被拒的一刀也不许把血量抬起来，实际 " + loaded.getHealth());

            helper.runAfterDelay(60, () -> {
                helper.assertTrue(loaded.isRemoved(),
                        "原版收尸路径必须自己走完（还站着＝血被钉住了，P0 死循环换个入口复发）");
                // 这条是<b>不变量兜底</b>：真正挡住二次结算的是上面那刀被拒；单看它，
                // 修前的尸体不挨刀也不会自己涨计数（轮 9 点的"无判别力"就出在这种断言上）
                helper.assertTrue(BossKillBoard.get(helper.getLevel())
                                .killCount(ExampleColossus.BOSS_ID) == killsAtSave,
                        "尸体退场不得二次结算（击杀计数从过档起又涨了＝战利品也会二次发放）");
                succeedClean(helper, boss, loaded);
            });
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
            succeedClean(helper, boss);
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
                succeedClean(helper, boss);
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

                // === 收摊门（leaderDown）判别式 ===
                // 轮 5 P2-5a 判过"等 140t 数实体"是恒真断言（Boss 已 isRemoved，squad.tick 停摆）；
                // 轮 7 又把断言换成 respawnPending()==0，那也问错了问题（倒下**之前**那次正常击破
                // 本来就该有排期）。这里改成先造出一条真实在途排期，再让队长倒下，看它是否被撤单——
                // 前置条件（pending==1）让"撤单"这一步真的有东西可撤。
                // 伤害取 100 而不是 10000：成员受击有 1/4 转发本体（ExampleSentry.damageForwardRatio），
                // 一万下去队长先死，撤单先于排期发生，下面的 pending==1 前置条件就红了。
                // 成员 60 血 × 直击倍率 1.5 → 100 足够击破，转发只有 25。
                back.get(0).hurt(boss.damageSources().playerAttack(mock), 100f); // 击破现役成员
                helper.assertTrue(boss.squad().respawnPending() == 1,
                        "成员被击破应排上一次重生（在途 " + boss.squad().respawnPending()
                                + " 条＝前置条件不成立，后面的撤单断言就是空转）");
                int pendingBefore = boss.squad().respawnPending();

                boss.hurt(boss.damageSources().playerAttack(mock), 10_000f); // 队长倒下（演出 100t）
                helper.runAfterDelay(5, () -> {
                    // 这条才是"撤单"的唯一判别式：倒下前在途 1 条、倒下后必须归零
                    helper.assertTrue(boss.squad().respawnPending() == 0,
                            "队长倒下即撤掉在途预约：倒下前 " + pendingBefore + " 条，现在仍剩 "
                                    + boss.squad().respawnPending() + " 条（＝死队长名下还挂着复活预约）");
                    helper.assertFalse(boss.isRemoved(),
                            "本桩靠「演出仍在进行」才有意义，此时就 isRemoved 说明时间线不对");
                });
                helper.runAfterDelay(140, () -> {
                    // 口径说明（审查轮 8 更正）：这条**不是**撤单的判别式——补员路径在 deathPending
                    // 下被 aiStep 早退 + tickSessionAndSquad 的双重门挡死，删掉 cancelAll 它也绿。
                    // 它证的是"队长结算移除之后场上不残留任何预约补出来的成员"，属时间线兜底。
                    helper.assertTrue(sentriesAround(helper, boss).isEmpty(),
                            "队长已结算移除，场上不该再有任何属于它的成员");
                    helper.assertTrue(boss.isRemoved(), "Boss 应已结算移除");

                    // === 收摊广播判别式（轮 7 补测）===
                    // 上面的时序把现役成员先杀了，"队长倒下时「活着的」成员必须自己收摊"
                    // 这条就失去观察者——旧桩里它由 180t 后的 "sentriesAround().isEmpty()" 兼着，
                    // 那个时点 Boss 已移除，等于没测。另起一队，在演出窗口内取判。
                    ColossusBossEntity second = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                            new BlockPos(4, 3, 4)); // 第一具已移除，原地复用不影响判定
                    second.hurt(second.damageSources().playerAttack(mock), 1.0f); // 点着，触发补员
                    helper.runAfterDelay(12, () -> {
                        helper.assertTrue(livingSentriesAround(helper, second).size() == 1,
                                "第二队应已补出「活着的」成员（没有活成员可收摊＝这条分支没被观察到）");
                        second.hurt(second.damageSources().playerAttack(mock), 10_000f);
                        helper.runAfterDelay(5, () -> {
                            // 队长血被钉在 1.0 且 isAlive() 仍为 true → 成员照旧跟锚点，
                            // 没有广播它就一定还活着；此刻未 isRemoved，判据不空转。
                            // 口径取"活着"而不是"存在"：广播生效后成员 die() 的尸体还要 20t 才消失，
                            // 按"名单为空"判会假红（首跑就红在这里）——见 livingSentriesAround 的说明。
                            int alive = livingSentriesAround(helper, second).size();
                            int present = sentriesAround(helper, second).size();
                            helper.assertTrue(alive == 0,
                                    "队长倒下即收摊：演出中场上仍有 " + alive + " 具活成员"
                                            + "（在场总数 " + present + "，尸体留在死亡动画里是预期的）");
                            helper.assertFalse(second.isRemoved(), "时间线核对：演出应仍未结束");
                            // 清场（轮 7 实测教训）：这具是为"收摊广播"临时立的，留着它退场＝
                            // 留一具仍在死亡演出里的 Boss 给后续批次——它照样 recordKill（把别的桩的
                            // "击杀 +1"变成 +2），演出中的自动选招还会打伤邻近结构的实体。
                            succeedClean(helper, boss, second);
                        });
                    });
                });
            });
        });
    }

    /**
     * 招式历史环形缓冲回归（第二十批）：出招要进历史、历史要过 NBT。
     *
     * <p>判据只取"8 格窗口内查得到/查不到"这种**单调**形式：本桩的 Boss 可能被邻近结构的实体
     * 勾到目标而自己出招（GameTest 结构从不清场，这是本工程已知的坑），
     * 所以不断"第几格是它"那种会被自动出招挪位的位置关系，只断"在不在窗口里"。
     */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "move-history")
    public void moveHistoryRecordsCastAndSurvivesSave(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var roar = Colossus.res("roar");
        var never = Colossus.res("no_such_move");
        helper.assertFalse(boss.usedRecently(never, com.klze.colossus.move.MoveHistory.SLOTS),
                "从没放过的招不该出现在历史里（历史环被初始化成\"全命中\"＝not_recent 会永久锁死选招）");

        helper.assertTrue(boss.forceMove(roar), "应能强制出招以写入历史");
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(boss.usedRecently(roar, com.klze.colossus.move.MoveHistory.SLOTS),
                    "出过一次的招必须进历史（beginAttack 没记账＝not_recent/recent_band 两条判据全是空转）");

            var tag = new net.minecraft.nbt.CompoundTag();
            boss.saveWithoutId(tag);
            boss.discard();
            ColossusBossEntity revived = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                    new BlockPos(4, 3, 4));
            revived.load(tag);
            helper.assertTrue(revived.usedRecently(roar, com.klze.colossus.move.MoveHistory.SLOTS),
                    "历史要入档：重载后失忆会让 Boss 在玩家眼里\"刚放过的招立刻又放一次\"（本轮新增的账没落盘）");
            helper.assertFalse(revived.usedRecently(never, com.klze.colossus.move.MoveHistory.SLOTS),
                    "读档也不该把没放过的招记成\"刚用过\"");

            // === 保底通道（审查轮 10 F1）===
            // 环形窗口只由"又出一次招"推进，光等待不消解封锁：整表被历史挡空时如果没有
            // "只放开历史门"的第二遍，Boss 会出现不随时间愈合的空窗，极端情形是永久死锁。
            // 这里用同一 id（roar 已在历史里）造一张只有一条被历史挡住的表，直接问 pick。
            var dice = net.minecraft.util.RandomSource.create(20260925L); // 定种子：桩要可复现
            var gatedOnly = new com.klze.colossus.move.MoveSetBuilder(revived, ExampleColossus.BOSS_ID)
                    .move(roar).duration(10).notRecent(com.klze.colossus.move.MoveHistory.SLOTS)
                    .at(1, null).andBuild();
            helper.assertTrue(gatedOnly.pick(
                            new com.klze.colossus.move.AttackContext(revived, null, 0.0),
                            id -> 0, dice).isPresent(),
                    "整表被历史挡空时必须保底出招（没有＝空窗不随等待消解，示范表实测能干站 70~140t）");
            // 反向：保底只放开历史门，普通谓词挡住的不许放行
            var hardGated = new com.klze.colossus.move.MoveSetBuilder(revived, ExampleColossus.BOSS_ID)
                    .move(Colossus.res("probe_hard")).duration(10).requires(ctx -> false)
                    .at(1, null).andBuild();
            helper.assertTrue(hardGated.pick(
                            new com.klze.colossus.move.AttackContext(revived, null, 0.0),
                            id -> 0, dice).isEmpty(),
                    "保底不得越过普通谓词/阶段/距离门（越了就是把没解锁的招放出来）");

            // === 降权不许压成禁选（审查轮 10 F2）===
            // demo 表在 8 格外的原始权重是 base 3 + distance_band(0)＝3，加上 recent_band(-6)＝-3；
            // 老写法会被 pick 的 w<=0 整条丢掉，于是"降权仍可选"其实是禁选——与 notRecent 撞成一件事。
            // 判据：放过一次 quake 之后，它在远端的权重仍要 >=1（不绕掷骰，理由见下面那段注释）
            var quake = Colossus.res("datapack_quake");
            helper.assertTrue(revived.moveSet().byId(quake) != null,
                    "demo 数据包的招该合进示范 Boss 的表（没合进来这条判据就是空转）");
            helper.assertTrue(revived.forceMove(quake), "先强制放一次 quake，把它写进历史");
            helper.runAfterDelay(45, () -> { // quake duration 40：等它收招，forceMove 才轮得到下一发
                var quakeDef = revived.moveSet().byId(quake);
                var far = new com.klze.colossus.move.AttackContext(revived, null, 100.0 * 100.0);
                // 判据直接打在权重上，不绕掷骰（轮 11 #3）：同一个种子掷 12 次只是**同一个 bit**，
                // 而"必中"还额外依赖池组成与表尾顺序——demo 包再加一招、或给 roar 也挂 notRecent，
                // 判据方向就会漂（可能恒绿也可能恒红，且都跟地板逻辑无关）。
                // 这里要证的只是"3 + (-6) 没把这条招挤出表"，那就断权重本身。
                // 断**精确值**而不是 >=1：远端本应是 base 3 + distance_band 0 = 3，
                // 被 recent_band(-6) 夹到地板 1。>=1 的写法在"历史压根没命中"时也给 3，照样绿＝可以空转
                // （轮 12 F6）。老实现的症状是 -3（被 pick 整条丢掉），所以 ==1 两头都有区分度。
                int wFar = quakeDef == null ? -999
                        : quakeDef.weight(far.withCandidate(quakeDef));
                helper.assertTrue(wFar == 1,
                        "放过的 quake 在远端权重应被夹到地板 1，实际 " + wFar
                                + "（3＝历史/降权没生效＝空转；<=0＝「降权」其实是禁选）");
                succeedClean(helper, boss, revived);
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
     * 场上还<b>活着</b>的、属于这个 Boss 的示范成员（收摊判定专用）。
     *
     * <p>不能直接复用 {@link #sentriesAround} 的"实体不存在"口径：成员 {@code die()} 之后还要走完
     * 死亡动画才 {@code isRemoved}，那 20t 里它仍在按类遍历的名单里——首跑就红在这一条上
     * （广播确实发了、血也确实清零了，只是尸体还没消失）。收摊的真实语义是 {@code !isAlive()}。
     */
    private static java.util.List<com.klze.colossus.testboss.ExampleSentry> livingSentriesAround(
            GameTestHelper helper, ColossusBossEntity boss) {
        java.util.List<com.klze.colossus.testboss.ExampleSentry> out = new java.util.ArrayList<>();
        for (com.klze.colossus.testboss.ExampleSentry s : sentriesAround(helper, boss)) {
            if (s.isAlive()) out.add(s);
        }
        return out;
    }

    /**
     * 出招序号回归（GL4 一手源码取证点名的事故形）：**连放同一招**时招式 id、动画名与
     * {@code ATTACK_DURATION} 都不变，只有 {@code attackSequence()} 递增——
     * 客户端若拿 index 当"新一次施法"的判据，第二下就不会重启动画（原地卡住）。
     * 顺带钉住 {@code forceMove} 的不可打断闸门。
     */
    @GameTest(template = YARD, timeoutTicks = 520, batch = "anim-seq")
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
                    succeedClean(helper, boss);
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
        // 注：timeoutTicks 必须大于本桩的轮询上界（40 + 40×10 = 440t），否则先被框架判超时，
        // 上面那句 helper.fail 是到不了的死支（审查轮 8 抓到：原先钉的是 200）。
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
            succeedClean(helper, boss);
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
     * 广播节律真机桩（轮 6 P2-3 的补口的口的口）：进度变化后<b>恰好广播一次</b>，
     * 之后静止三轮<b>一次都不再发</b>。判据用广播计数而不是对账基线——
     * 少了脏检查时基线仍会被反复设成同值，断言挡不住（审查建议的那条本身有洞）。
     */
    @GameTest(template = YARD, timeoutTicks = 250, batch = "progress-broadcast")
    public void progressBroadcastsOncePerChange(GameTestHelper helper) {
        var board = BossKillBoard.get(helper.getLevel());
        long base = com.klze.colossus.progress.ProgressSync.broadcastCount();
        board.recordKill(Colossus.res("gametest_probe_sync"));

        helper.runAfterDelay(30, () -> {
            long after = com.klze.colossus.progress.ProgressSync.broadcastCount();
            helper.assertTrue(after == base + 1,
                    "一次变化应恰好广播一次，实际 +" + (after - base) + "（>=2 即脏检查被拆）");
            helper.runAfterDelay(80, () -> {
                long idle = com.klze.colossus.progress.ProgressSync.broadcastCount();
                helper.assertTrue(idle == after,
                        "同值期间不得重播全表，又发了 " + (idle - after) + " 次");
                helper.succeed();
            });
        });
    }

    /**
     * datapack 招式表的真机链（第十四批）：数据包文件 → reload listener → 回执有货
     * → 合并进 Boss 的 MoveSet（与 Java 招共存且不抢 id）→ JSON 招的判定帧真的落伤。
     * 自检只能证到"解码对"，这条证的才是"内容作者放个 JSON 就能加招"这件事成立。
     */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "json-move")
    public void datapackMoveLoadsMergesAndHits(GameTestHelper helper) {
        var report = com.klze.colossus.move.MoveDataRegistry.lastReport();
        var jsonId = Colossus.res("datapack_quake");
        helper.assertTrue(report.applied() > 0,
                "数据包招式表没加载：files=" + report.files() + " records=" + report.records()
                        + " errors=" + report.errors());
        boolean inTable = com.klze.colossus.move.MoveDataRegistry.defsFor(Colossus.res("example"))
                .stream().anyMatch(m -> m.id().equals(jsonId));
        helper.assertTrue(inTable,
                "colossus/moves/colossus/example.json 里的 datapack_quake 没进表（回执 errors="
                        + report.errors() + "）");

        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        helper.assertTrue(boss.moveSet().byId(jsonId) != null,
                "JSON 招必须与 Java 招合并进同一张 MoveSet");
        helper.assertTrue(boss.moveSet().byId(Colossus.res("smash")) != null,
                "合并不能把 Java 侧原有招式挤掉");

        var cow = helper.spawn(net.minecraft.world.entity.EntityType.COW, new BlockPos(6, 3, 4));
        float hpBefore = cow.getHealth();
        helper.assertTrue(boss.forceMove(jsonId), "应能强制出这招 JSON 招");
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(cow.getHealth() < hpBefore || cow.isRemoved(),
                    "JSON 招的判定帧必须真落伤（牛 " + cow.getHealth() + "/" + hpBefore + "）");
            succeedClean(helper, boss);
        });
    }

    /**
     * 延迟工作的持久化回归（第十六批）：排一条 60t 后结算的危险区爆发，
     * 把 Boss 序列化进 NBT、在另一个实例上读回来——队列必须还在、到期时刻仍是"未来"，
     * 且到点真的打伤圈内实体。旧形态（Runnable + tickCount）在这里必然丢队列或算错时刻。
     */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "deferred-work")
    public void deferredWorkSurvivesSaveAndStillSettles(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var cow = helper.spawn(net.minecraft.world.entity.EntityType.COW, new BlockPos(4, 3, 4));
        // 区域直接以牛为心：本桩测的是"队列能否穿过存档、何时到期、结算几次"，
        // 不是几何判定（首跑用 damageCircle，Boss 下坠导致圈抬高、牛落在圈外 → 假红）
        var zone = new com.klze.colossus.env.TelegraphZone(cow.getX(), cow.getY() + 1.0, cow.getZ(),
                6.0, 6.0, 60, 0xFF4040, "dust"); // 60t 里牛会下落，盒子要给足厚度（首跑 1.5 厚 → 掉出圈外假红）
        final float hpBefore = cow.getHealth();

        boss.scheduleWork(60, com.klze.colossus.env.ZoneWork.KIND,
                com.klze.colossus.env.ZoneWork.encode(zone,
                        new com.klze.colossus.env.ZoneBurst(4.0f, 0.0f, 0)));
        // 顺带把两条弃单分支排进来：未知种类、残缺载荷——都只在 drain/load 之后才走得到
        boss.scheduleWork(60, "colossus:no_such_kind", new net.minecraft.nbt.CompoundTag());
        boss.scheduleWork(60, com.klze.colossus.env.ZoneWork.KIND, new net.minecraft.nbt.CompoundTag());
        // 第四条：与 zone 同一条时间线的**隔离计数器**（落在 Boss 自己的 persistentData 上）。
        // "何时到期""落几次"这两问只认这个观测面——牛是世界级实体，会被别的桩的残兵碰脏
        // （轮 7 实测：同一份代码两轮分别掉 8.0 / 10.0 点，"恰好 4 点"这条判据本身不成立）。
        boss.scheduleWork(60, PING_KIND, new net.minecraft.nbt.CompoundTag());
        helper.assertTrue(boss.pendingWorkCount() == 4, "排四条应得四条在途待办");

        var tag = new net.minecraft.nbt.CompoundTag();
        boss.saveWithoutId(tag);
        // 归因（轮 7 P2-3）：原实例必须退场，否则"牛掉血"可能出自它，断言就不是只证读档那条路
        boss.discard();
        ColossusBossEntity revived = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        revived.load(tag);
        helper.assertTrue(revived.pendingWorkCount() == 4,
                "读档后队列必须原样还在（实际 " + revived.pendingWorkCount() + " 条）");

        helper.runAfterDelay(10, () -> {
            // 时刻判据：旧形态按 tickCount 计数，重载后"还剩 50t"会立刻变成"已过期"——
            // 只断"结没结算"抓不到它（轮 7 变异表第一行）
            helper.assertTrue(pingCount(revived) == 0,
                    "+10t 时这条待办必须还没落（到期时刻仍是未来；现在就落＝时钟换错了）");
        });
        helper.runAfterDelay(75, () -> {
            helper.assertTrue(revived.pendingWorkCount() == 0,
                    "四条待办（含未知种类与残缺载荷两条弃单）都该排空，实际剩 "
                            + revived.pendingWorkCount() + "（剩 4＝drainWork 没跑到这个实例）");
            helper.assertTrue(pingCount(revived) == 1,
                    "到点必须恰好落一次，实际 " + pingCount(revived) + " 次（0＝没落，2+＝同一发结算多次）");
            // 伤害落点降到"至少一发"：它只证 zone 真的结算到了实体上，
            // 精确次数由上面那条计数器负责（牛可能挨别桩的刀，也可能被这一发打死）
            float lost = hpBefore - (cow.isRemoved() ? 0.0f : cow.getHealth());
            net.minecraft.world.damagesource.DamageSource last = cow.getLastDamageSource();
            helper.assertTrue(lost >= 4.0f,
                    "到点至少该吃到一发 4 点，实际掉 " + lost
                            + "（0＝没落/没打中；最后一击 msgId="
                            + (last == null ? "无" : last.getMsgId())
                            + " 直接源=" + (last == null || last.getDirectEntity() == null ? "无"
                                    : last.getDirectEntity().getEncodeId() + "@"
                                            + last.getDirectEntity().getStringUUID()) + "）");
            helper.assertTrue(revived.isAlive(), "未知/残缺 handler 不该把 Boss 弄崩");
            // 收尾清场（轮 7）：退场时留一只还活着、还会选招的 Boss，就是给邻近结构留一个射手
            succeedClean(helper, boss, revived);
        });
    }

    /**
     * 危险区投影的可持久／可重放回归（第二十四批，判据取自 v11 取证 A2/A3）：
     * 轮廓不再是"发一次就不管"的自定义包，而是 Boss 同步数据里的一份投影，
     * 所以这里问的是<b>数据本身</b>：穿不穿得过存档、绝对时刻对不对、满了拒不拒、到期清不清。
     *
     * <p>为什么不能只靠自检：投影的三条好处（中途进场补包、重进世界自愈、重载后进度不重播）
     * 里前两条由 vanilla 的 {@code ServerEntity#sendPairingData} 保证，本桩证不到发包，
     * 但<b>第三条（存档）与时刻口径</b>是它的地盘——旧写法把"还剩几 tick"重当成年龄，
     * 正是第十六批待办队列踩过的同一个坑。
     */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "telegraph")
    public void telegraphProjectionPersistsAndExpires(GameTestHelper helper) {
        ColossusBossEntity boss = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        var zone = new com.klze.colossus.env.TelegraphZone(4.5, 3.0, 4.5, 6.0, 1.5, 30, 0xFF4040, "dust");

        // 轮 17 P3-2：投影上限钳位的四条断言。它<b>不能</b>放 colossusSelfTest——那个 JVM 里
        // ColossusBossEntity 的 <clinit> 起不动（要 vanilla 注册表，而 Forge 注入的
        // Bootstrap:62 NetworkHooks.init 在独立进程里必炸），所以钉在本门。
        helper.assertTrue(ColossusBossEntity.clampTelegraphCap(-1) == 1
                        && ColossusBossEntity.clampTelegraphCap(0) == 1,
                "下界必须抬到 1：0 会让 size()>=cap 恒真 ⇒ 这个 Boss 所有带预警的招一招不落，实测 clamp(-1)="
                        + ColossusBossEntity.clampTelegraphCap(-1) + " clamp(0)="
                        + ColossusBossEntity.clampTelegraphCap(0));
        helper.assertTrue(ColossusBossEntity.clampTelegraphCap(8) == 8
                        && ColossusBossEntity.clampTelegraphCap(40) == ColossusBossEntity.HARD_MAX_TELEGRAPHS
                        && ColossusBossEntity.clampTelegraphCap(Integer.MAX_VALUE)
                                == ColossusBossEntity.HARD_MAX_TELEGRAPHS,
                "区间内原样保留、越界钳到硬上界（上界一松就是无界同步载荷），实测 clamp(40)="
                        + ColossusBossEntity.clampTelegraphCap(40) + " clamp(MAX)="
                        + ColossusBossEntity.clampTelegraphCap(Integer.MAX_VALUE));

        int first = boss.showTelegraph(zone, 40);
        helper.assertTrue(first >= 0, "showTelegraph 该登记成功并回一个 id（-1＝第一道门就把招堵死了）");
        var decoded = boss.telegraphViews();
        helper.assertTrue(decoded.size() == 1,
                "登记的那条必须能从同步数据里读回来（读不回＝客户端根本没东西可画）");
        var got = decoded.get(0);
        helper.assertTrue(Math.abs(got.zone().radiusXZ() - 6.0) < 1e-9 && "dust".equals(got.zone().visual())
                        && got.zone().warnTicks() == 30,
                "几何／样式／warn 都要穿过 entityData 原样回来，实际 warn="
                        + got.zone().warnTicks() + " visual=" + got.zone().visual()
                        + "（warn 丢了＝lifetime 少 30t，圈先消失、伤害后落地）");
        helper.assertTrue(got.endGameTime() - got.startGameTime() == 40,
                "寿命要按给定的 tick 记，实际 " + (got.endGameTime() - got.startGameTime()));

        // 封顶：填到上限后必须拒，且拒的是"多出来的那些"而不是悄悄挤掉旧的
        int refused = -2;
        for (int i = 0; i < 20; i++) refused = boss.showTelegraph(zone, 40);
        helper.assertTrue(boss.activeTelegraphCount() == ColossusBossEntity.MAX_ACTIVE_TELEGRAPHS,
                "在途条数要恰好停在上限，实际 " + boss.activeTelegraphCount());
        helper.assertTrue(refused == -1, "上限之后必须返回 -1 让调用方放弃整发（否则就是没预警的伤害）");

        var tag = new net.minecraft.nbt.CompoundTag();
        boss.saveWithoutId(tag);
        boss.discard(); // 原实例退场：否则"读回来的那份"可能仍在被活体 tick 改
        ColossusBossEntity revived = helper.spawn(ColossusRegistries.EXAMPLE_COLOSSUS.get(),
                new BlockPos(4, 3, 4));
        revived.load(tag);
        helper.assertTrue(revived.activeTelegraphCount() == ColossusBossEntity.MAX_ACTIVE_TELEGRAPHS,
                "投影要随实体落盘：读档后条数应仍是上限，实际 " + revived.activeTelegraphCount()
                        + "（0＝entityData 不落盘而读侧没重建）");
        revived.hideTelegraph(1);
        int afterLoad = revived.showTelegraph(zone, 40);
        helper.assertTrue(afterLoad > ColossusBossEntity.MAX_ACTIVE_TELEGRAPHS,
                "读档后序号必须续在存档里最大 id 之后，实际拿到 " + afterLoad
                        + "（回到小号会和还在世的轮廓撞号：撤一条会撤错、客户端会提前覆写）");

        helper.runAfterDelay(50, () -> {
            helper.assertTrue(revived.activeTelegraphCount() == 0,
                    "到点的轮廓必须从在途集合里退干净，实际还剩 " + revived.activeTelegraphCount()
                            + " 条（永不消失的圈＝tell 在撒谎；now=" + helper.getLevel().getGameTime() + "）");
            // 两条分开钉，不用上一轮的"同进退"合取：无目标的 Boss 在这 50t 里自己不会出招，
            // 合取的两个半边必然同时为假 ⇒ 恒真、抓不到任何东西（轮 14 P2-5，正是本仓刚立的
            // "装饰不是判据"那条）。这条钉的是"到期后有没有顺手重投投影"——漏 publish 就红。
            helper.assertTrue(revived.telegraphProjectionCount() == 0,
                    "在途集合清空后同步投影也要跟着空，实际投影里还有 " + revived.telegraphProjectionCount()
                            + " 条（集合与投影脱节＝客户端永远画着旧圈）");
            succeedClean(helper, boss, revived);
        });
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
