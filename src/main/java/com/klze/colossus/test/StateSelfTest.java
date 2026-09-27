package com.klze.colossus.test;

import com.klze.colossus.state.ActiveState;
import com.klze.colossus.state.FrameRunner;
import com.klze.colossus.state.State;
import com.klze.colossus.state.StateController;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态机与帧表内核自检（纯逻辑、零 MC 依赖）——gradle 任务 colossusSelfTest。
 *
 * <p>本工程的验证立场：GameTest 耗时被地形主导、不作性能/正确性信号；
 * 内核用可重复的确定性断言，产物从磁盘回读。
 */
public final class StateSelfTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        // 本门<b>不能</b>碰任何 Entity 后代：轮 17 试过一次显式 `Bootstrap.bootStrap()`，
        // 结果是 Forge 在 :62 注入的 `NetworkHooks.init()` 直接炸
        // （NoSuchMethodException: NetworkEvent.<init>()——它要的是 mod 总线，独立 JVM 没有）。
        // 所以"要活的注册表"的判据一律放 GameTest（那一道有真服务器），这里只留纯数据。
        testPushAndPop();
        testInterruptGate();
        testForcePushOverridesGate();
        testTransitionWindow();
        testEndAllFiresOnEnd();
        testReplaceActive();
        testFrameSingleShot();
        testFrameWindowFiresOnce();
        testFrameMissedWindowNotCompensated();
        testFrameAdvanceIdempotent();
        testFrameRepeatingFiresEveryPeriod();
        testFrameRepeatingSkipsBeatsWithoutCompensation();
        testFrameTimelineResume();
        testMoveAnimNameDomain();
        testMoveHistoryRing();
        testContactBook();
        testTableSamplerInterpolation();
        testPartRig();
        testPartStatesBits();
        testRespawnSchedule();
        testDirtyMeter();
        testProgressLedger();
        MoveJsonSelfTest.run((name, ok) -> check(name, ok));
        testDeferredWorkData();
        testArenaBounds();
        if (failures > 0) {
            System.out.println("SELFTEST FAILED: " + failures + "/" + checks);
            System.exit(1);
        }
        System.out.println("SELFTEST PASSED: " + checks + "/" + checks);
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
        if (!ok) failures++;
    }

    // ---------------- cases ----------------

    private static void testPushAndPop() {
        List<String> log = new ArrayList<>();
        StateController<List<String>> c = new StateController<>(log);
        check("idle at start", c.isIdle());
        c.push(countingState(log, "a", 3));
        check("push activates", !c.isIdle() && c.active().state().name().equals("counting"));
        for (int i = 0; i < 3; i++) c.tick();
        check("ticks counted", log.contains("a:1") && log.contains("a:2") && log.contains("a:3"));
        c.tick(); // 4 > 3 → END
        check("ends and pops to idle", c.isIdle() && log.contains("a:end"));
    }

    private static void testInterruptGate() {
        List<String> log = new ArrayList<>();
        StateController<List<String>> c = new StateController<>(log);
        State<List<String>> lock = countingState(log, "locked", 99);
        // 不可打断版本
        State<List<String>> unbreakable = new State<>() {
            @Override public Result onTick(List<String> e, ActiveState<List<String>> self) {
                e.add("u:" + self.tick());
                return self.tick() >= 99 ? Result.END : Result.CONTINUE;
            }
            @Override public boolean isInterruptable(List<String> e) { return false; }
            @Override public String name() { return "u"; }
        };
        c.push(unbreakable);
        boolean accepted = c.push(lock);
        check("push rejected while locked", !accepted && !log.contains("s:start"));
        check("locked state still active", c.active().state().name().equals("u"));
    }

    private static void testForcePushOverridesGate() {
        List<String> log = new ArrayList<>();
        StateController<List<String>> c = new StateController<>(log);
        State<List<String>> unbreakable = new State<>() {
            @Override public Result onTick(List<String> e, ActiveState<List<String>> self) { return Result.CONTINUE; }
            @Override public boolean isInterruptable(List<String> e) { return false; }
            @Override public String name() { return "u"; }
        };
        c.push(unbreakable);
        c.forcePush(countingState(log, "x", 1));
        check("forcePush replaced the lock",
                c.active().state().name().equals("counting") && log.contains("x:start"));
    }

    private static void testTransitionWindow() {
        List<String> log = new ArrayList<>();
        StateController<List<String>> c = new StateController<>(log);
        c.pushWithTransition(countingState(log, "t", 2), 5);
        for (int i = 0; i < 5; i++) c.tick();
        // onStart 立即跑；转场窗口只压逻辑帧（"t:N"），不应出现任何编号帧
        check("transition window suppresses logic",
                log.contains("t:start")
                        && log.stream().noneMatch(s -> s.matches("t:\\d+")));
        c.tick(); // tick becomes 1 → first logic tick
        check("first logic tick after window", log.contains("t:1"));
    }

    private static void testEndAllFiresOnEnd() {
        List<String> log = new ArrayList<>();
        StateController<List<String>> c = new StateController<>(log);
        c.push(countingState(log, "p", 50));
        c.push(countingState(log, "q", 50));
        c.endAll();
        check("endAll unwinds in LIFO order",
                log.contains("p:end") && log.contains("q:end")
                        && log.indexOf("q:end") < log.indexOf("p:end") && c.isIdle());
    }

    private static void testReplaceActive() {
        List<String> log = new ArrayList<>();
        StateController<List<String>> c = new StateController<>(log);
        c.push(countingState(log, "r", 50));
        c.replaceActive(countingState(log, "s2", 50));
        check("replace ends old top only",
                log.contains("r:start") && log.contains("r:end") && c.active() != null);
    }

    private static void testContactBook() {
        com.klze.colossus.fight.ContactBook book = new com.klze.colossus.fight.ContactBook();
        check("contact claimed once", book.claim("smash#3") && !book.claim("smash#3"));
        check("distinct keys independent", book.claim("smash#4") && book.size() == 2);
        book.clear();
        check("clear resets per-attack instance", book.claim("smash#3") && book.size() == 1);
    }

    private static void testTableSamplerInterpolation() {
        com.klze.colossus.anim.TableSampler sampler = new com.klze.colossus.anim.TableSampler()
                .track("right_hand", List.of(
                        new com.klze.colossus.anim.TableSampler.Key(0, -8, 10, 0),
                        new com.klze.colossus.anim.TableSampler.Key(24, -8, 26, 4),
                        new com.klze.colossus.anim.TableSampler.Key(36, -6, 2, 6)));
        // 中点插值：tick 12 应为两键中点
        net.minecraft.world.phys.Vec3 mid = sampler.sampleLocal("right_hand", 12);
        check("table lerps between keys",
                Math.abs(mid.x - (-8)) < 1e-6 && Math.abs(mid.y - 18) < 1e-6 && Math.abs(mid.z - 2) < 1e-6);
        // 表外钳位
        net.minecraft.world.phys.Vec3 before = sampler.sampleLocal("right_hand", -5);
        net.minecraft.world.phys.Vec3 after = sampler.sampleLocal("right_hand", 99);
        check("out-of-range clamps to first/last key",
                Math.abs(before.y - 10) < 1e-6 && Math.abs(after.z - 6) < 1e-6);
        check("unknown anchor falls back to origin",
                sampler.sampleLocal("nope", 5).equals(net.minecraft.world.phys.Vec3.ZERO));
    }

    private static void testPartRig() {
        com.klze.colossus.entity.part.PartRig rig = new com.klze.colossus.entity.part.PartRig()
                .pose(0, new com.klze.colossus.entity.part.RigPose(4f, 0f, 0f, 0f))
                .pose(1, new com.klze.colossus.entity.part.RigPose(10f, -40f, 0f, 1f));
        com.klze.colossus.entity.part.RigPose mid = rig.sample(0, 1, 0.5f);
        check("rig lerps length/mouth",
                Math.abs(mid.length() - 7f) < 1e-4 && Math.abs(mid.mouthOpen() - 0.5f) < 1e-4);
        check("unknown state falls back to ZERO",
                rig.get(9).length() == 0f);
        // 限速转向走短路：170° → -170° 差值折为 +20°，一步 15° 后应停在 185（过 180 侧前进）
        float step = com.klze.colossus.entity.part.PartRig.approach(170f, -170f, 15f);
        check("approach takes the short way over ±180", Math.abs(step - 185f) < 0.01f);
        float wrapped = com.klze.colossus.entity.part.PartRig.approach(170f, -170f, 400f);
        check("full step lands on target unwrapped", Math.abs(wrapped - 190f) < 0.01f);
    }

    private static void testPartStatesBits() {
        long bits = 0;
        bits = com.klze.colossus.entity.part.PartStates.withFlag(bits, 5,
                com.klze.colossus.entity.part.PartStates.FLAG_DAMAGED, true);
        bits = com.klze.colossus.entity.part.PartStates.withFlag(bits, 6,
                com.klze.colossus.entity.part.PartStates.FLAG_ACTIVE, true);
        check("flag isolated to its part",
                com.klze.colossus.entity.part.PartStates.hasFlag(bits, 5,
                        com.klze.colossus.entity.part.PartStates.FLAG_DAMAGED)
                        && !com.klze.colossus.entity.part.PartStates.hasFlag(bits, 6,
                        com.klze.colossus.entity.part.PartStates.FLAG_DAMAGED));
        bits = com.klze.colossus.entity.part.PartStates.withFlag(bits, 5,
                com.klze.colossus.entity.part.PartStates.FLAG_DAMAGED, false);
        check("clear restores", !com.klze.colossus.entity.part.PartStates.hasFlag(bits, 5,
                com.klze.colossus.entity.part.PartStates.FLAG_DAMAGED)
                && com.klze.colossus.entity.part.PartStates.hasFlag(bits, 6,
                com.klze.colossus.entity.part.PartStates.FLAG_ACTIVE));
    }

    private static void testRespawnSchedule() {
        com.klze.colossus.entity.squad.RespawnSchedule s = new com.klze.colossus.entity.squad.RespawnSchedule();
        s.schedule("tentacle_a", 1000L);
        s.schedule("tentacle_a", 2000L); // 去重：排期中不重排（Kraken noneMatch 语义）
        s.schedule("tentacle_b", 1500L);
        check("schedule dedups by key", s.size() == 2);
        var due1 = s.consumeDue(1200L);
        check("consume only due", due1.equals(List.of("tentacle_a")) && s.consumeDue(1200L).isEmpty());
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        s.schedule("tentacle_c", 9999L);
        s.save(tag);
        var restored = new com.klze.colossus.entity.squad.RespawnSchedule();
        restored.load(tag);
        var due2 = restored.consumeDue(1600L);
        var due3 = restored.consumeDue(10000L);
        check("NBT roundtrip keeps pending", due2.equals(List.of("tentacle_b"))
                && due3.equals(List.of("tentacle_c")) && restored.size() == 0);

        // 撤单（轮 7 P1-4）：队长倒下要把<b>已经在途</b>的排期整张撤掉——
        // 只挡"以后还能不能排"的写法会在 100t 死亡演出里到点补员，补出来的那具收不到收摊广播。
        // 判据取"到点也拿不出人 + 存档里也没了"，不只看 size（防"只清了视图"的假撤单）。
        s.schedule("tentacle_a", 3000L);
        s.schedule("tentacle_b", 3500L);
        s.cancelAll();
        net.minecraft.nbt.CompoundTag afterCancel = new net.minecraft.nbt.CompoundTag();
        s.save(afterCancel);
        var revived = new com.klze.colossus.entity.squad.RespawnSchedule();
        revived.load(afterCancel);
        check("cancelAll really empties the schedule (due-time + save)",
                s.size() == 0 && s.consumeDue(99999L).isEmpty() && !s.isScheduled("tentacle_a")
                        && revived.size() == 0);
    }

    private static State<List<String>> countingState(List<String> log, String tag, int endAt) {
        return new State<>() {
            @Override public void onStart(List<String> e) { e.add(tag + ":start"); }
            @Override public Result onTick(List<String> e, ActiveState<List<String>> self) {
                e.add(tag + ":" + self.tick());
                return self.tick() >= endAt ? Result.END : Result.CONTINUE;
            }
            @Override public void onEnd(List<String> e) { e.add(tag + ":end"); }
            @Override public String name() { return "counting"; }
        };
    }

    // ---------------- FrameRunner cases ----------------

    /**
     * 动画名值域（审查轮 8 P2）：它是客户端取动画的<b>键</b>，空串不会自己报错，
     * 只会让 GL/模型侧按名查不到而静默不播——最难查的一类故障，所以两条入口都在登记期拒。
     */
    private static void testMoveAnimNameDomain() {
        boolean blankRejected = false;
        boolean nullRejected = false;
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, new net.minecraft.resources.ResourceLocation("colossus", "t"))
                    .move("silent").duration(10).anim("").done();
        } catch (IllegalArgumentException expected) {
            blankRejected = true;
        }
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, new net.minecraft.resources.ResourceLocation("colossus", "t"))
                    .move("silent").duration(10).anim(null).done();
        } catch (IllegalArgumentException expected) {
            nullRejected = true;
        }
        check("blank anim name is rejected at registration (not silently unplayable)",
                blankRejected && nullRejected);

        // 空白串同样拒（" " 查不到动画，症状与 "" 一模一样）
        boolean whitespaceRejected = false;
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, new net.minecraft.resources.ResourceLocation("colossus", "t"))
                    .move("silent").duration(10).anim("   ").done();
        } catch (IllegalArgumentException expected) {
            whitespaceRejected = true;
        }
        check("whitespace-only anim name is rejected too", whitespaceRejected);
    }

    /**
     * 环形历史（第二十一批从实体里抽成纯件，就是为了让这几条进得了自检）。
     * 审查轮 10 F4 的原话：只查整环的桩对"方向/容量/0 留给空槽"三件事全都不敏感。
     */
    private static void testMoveHistoryRing() {
        var roar = new net.minecraft.resources.ResourceLocation("colossus", "roar");
        var sweep = new net.minecraft.resources.ResourceLocation("colossus", "sweep");
        // path.hashCode() & 0x7F == 0 的 id（实测 big/aon 都是）——+1 没做对就会一出生"刚用过"
        var zeroValued = new net.minecraft.resources.ResourceLocation("colossus", "big");
        var h = new com.klze.colossus.move.MoveHistory();
        check("empty ring answers 'not used' for every id, including a zero-valued one",
                !h.usedRecently(roar, 8) && !h.usedRecently(zeroValued, 8)
                        && com.klze.colossus.move.MoveHistory.valueOf(zeroValued) != 0L);
        h.record(zeroValued);
        check("a zero-valued id is still recorded (the +1 reserves 0 as 'empty slot')",
                h.usedRecently(zeroValued, 1));

        var h2 = new com.klze.colossus.move.MoveHistory();
        h2.record(roar);
        h2.record(sweep);
        check("low slot is newest: window 1 sees only the last cast",
                h2.usedRecently(sweep, 1) && !h2.usedRecently(roar, 1) && h2.usedRecently(roar, 2));

        var h3 = new com.klze.colossus.move.MoveHistory();
        h3.record(roar);
        for (int i = 0; i < com.klze.colossus.move.MoveHistory.SLOTS; i++) h3.record(sweep);
        check("shift drops the oldest: roaring slides out after 8 later casts",
                !h3.usedRecently(roar, com.klze.colossus.move.MoveHistory.SLOTS)
                        && h3.usedRecently(sweep, com.klze.colossus.move.MoveHistory.SLOTS));

        var h4 = new com.klze.colossus.move.MoveHistory();
        h4.record(roar);
        h4.record(sweep);
        h4.record(sweep);
        // 轮 11 #5：原先那条只记两次同招，"这招还剩几次额度"的 counter 型实现也照样为真，改不出红。
        // 位置语义的分水岭是【roar 是第 3 新】——窗口 2 查不到、窗口 3 查得到；
        // 而 sweep 连放两次只各占一格，不会把 roar 挤得更远。
        check("ring is positional, not a per-move counter",
                h4.usedRecently(roar, 3) && !h4.usedRecently(roar, 2) && h4.usedRecently(sweep, 1));

        // 槽宽/值域/格数的关系（轮 11：原先写的是 static 断言，被编译期常量折叠掉了＝没有保险）
        check("ring layout is self-consistent (8 slots × slot width == a long)",
                com.klze.colossus.move.MoveHistory.layoutSane());
        // 轮 13 P3-1：上一版这里写的是 `valueOf(roar) >= 1 && <= 255`——值域按构造就是 1..128，
        // `>= 1` 恒真，而把 VALUE_MASK 抬到 0xFF 之后 roar 仍然是 47，也照样绿 ⇒ 装饰不是判据。
        // 现在钉的是"一批 id 全部落在 1..128"：掩码一旦放宽，这批里必然冒出 >128 的值把它变红，
        // 而 0 是空槽哨兵（值里出现 0 就等于那招"一出生刚用过"）。
        boolean valuesInValueRange = true;
        for (String probe : List.of("roar", "sweep", "smash", "big", "aon", "meteor", "icering",
                "flamewall", "quake", "lunge", "tail_swipe", "recovery")) {
            long v = com.klze.colossus.move.MoveHistory.valueOf(
                    new net.minecraft.resources.ResourceLocation("colossus", probe));
            valuesInValueRange &= v >= 1 && v <= 128;
        }
        check("12 probe ids all land in 1..128 (0 stays reserved as the empty-slot sentinel)",
                valuesInValueRange);

        // === 轮 17 P3-2：telegraph 投影上限的钳位此前零自检（改回 0 也全绿） ===
        // 断言住在 GameTest 的 telegraph 那一条里（clampTelegraphCap 四档），不在这里：
        // 它是 ColossusBossEntity 的静态方法，而本门连 <clinit> 都起不动（main 顶上那条说明）。
        var h5 = new com.klze.colossus.move.MoveHistory();
        h5.record(roar);
        var restored = new com.klze.colossus.move.MoveHistory();
        restored.restore(h5.snapshot());
        check("snapshot/restore round-trips the ring",
                restored.usedRecently(roar, 1) && restored.snapshot() == h5.snapshot());

        // DSL 侧窗口在登记期拒（JSON 侧走字段级回执，两条都不许静默钳位）
        boolean win0Rejected = false;
        boolean win9Rejected = false;
        var bid = new net.minecraft.resources.ResourceLocation("colossus", "t");
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, bid).move("w").duration(10).notRecent(0).done();
        } catch (IllegalArgumentException expected) {
            win0Rejected = true;
        }
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, bid).move("w2").duration(10).notRecent(9).done();
        } catch (IllegalArgumentException expected) {
            win9Rejected = true;
        }
        check("DSL rejects out-of-range notRecent at registration (no silent clamp)",
                win0Rejected && win9Rejected);

        // === 轮 13 P2-2：阶段带/时长这两格原先只有 JSON 侧拒，DSL 侧静默接受 ===
        // 恒假的 [3,1) 在 available() 里返回 false，而"不可用"是选招的常态 ⇒ 一点日志都没有，
        // 那招就凭空消失了。写反的作者只会以为"这招没刷出来"。
        boolean reversedPhaseRejected = false;
        boolean zeroDurationRejected = false;
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, bid).move("p").duration(10).phase(3, 1);
        } catch (IllegalArgumentException expected) {
            reversedPhaseRejected = true;
        }
        try {
            new com.klze.colossus.move.MoveSetBuilder(null, bid).move("d").duration(0);
        } catch (IllegalArgumentException expected) {
            zeroDurationRejected = true;
        }
        check("DSL setters reject reversed phase band and zero duration at the author's line",
                reversedPhaseRejected && zeroDurationRejected);
        // 值域的正主是构造器：绕过 setter 直接造 MoveDef 也不许留下恒假门
        boolean ctorRejectsBypass = false;
        try {
            com.klze.colossus.move.MoveDef.of(new net.minecraft.resources.ResourceLocation("colossus", "bypass"),
                    10, 20, 3, 1, -1.0f, "bypass",
                    ctx -> 1, ctx -> true, 0, 0, java.util.List.of());
        } catch (IllegalArgumentException expected) {
            ctorRejectsBypass = true;
        }
        check("MoveDef's own constructor is the last gate (a bypassed builder still can't ship a dead band)",
                ctorRejectsBypass);
        var gated = new com.klze.colossus.move.MoveSetBuilder(null, bid)
                .move("w3").duration(10).notRecent(3).at(1, null).done().builtDefs().get(0);
        check("notRecent is data the engine can see (MoveDef.notRecent), not an opaque predicate",
                gated.notRecent() == 3);
    }

    /**
     * 时间线快照/续播（v12a 的形态：存"招内相对 tick + 已触发位图"，不存绝对时刻）。
     * 这里钉的是<b>行为</b>而不是实现式子：重放、漏放、位图撒谎、超出位图宽度。
     */
    private static void testFrameTimelineResume() {
        List<Integer> hit = new ArrayList<>();
        var runner = FrameRunner.<List<Integer>>builder()
                .between(2, 10, (c, tick) -> c.add(tick))
                .build();
        for (int t = 1; t <= 5; t++) runner.advance(hit, t);
        check("baseline: the window frame fired exactly once before the snapshot",
                hit.equals(List.of(2)) && runner.lastTick() == 5 && runner.firedBitmap() != 0L);

        // 存档恢复：同一个 runner 被重建，位图却"撒谎"（一位没置上）——绝不允许重放
        var restored = FrameRunner.<List<Integer>>builder()
                .between(2, 10, (c, tick) -> c.add(tick))
                .build();
        restored.resumeFrom(5, 0L, restored.framesDigest());
        for (int t = 6; t <= 12; t++) restored.advance(hit, t);
        check("resume never replays a window whose start has already passed, even if the bitmap lost its bit",
                hit.equals(List.of(2)) && restored.lastTick() == 12);

        // 反过来：位图里提前置了"窗口还没到"的那一帧 ⇒ 宁少发一次，也不在恢复后重放
        List<Integer> early = new ArrayList<>();
        var r2 = FrameRunner.<List<Integer>>builder()
                .between(2, 4, (c, tick) -> c.add(tick))
                .between(8, 9, (c, tick) -> c.add(tick))
                .build();
        r2.resumeFrom(5, 0b11L, r2.framesDigest()); // 第二帧（i=1）窗口在 8，但位图说它已触发
        for (int t = 6; t <= 12; t++) r2.advance(early, t);
        check("a bit set for a frame whose window has not arrived suppresses that frame (never double-fire)",
                early.isEmpty());

        // 持续帧与位图无关：恢复不打断它的节拍，也不补已错过的拍
        List<Integer> rep2 = new ArrayList<>();
        var r3 = FrameRunner.<List<Integer>>builder()
                .repeating(1, 12, 3, (c, tick) -> c.add(tick))
                .build();
        r3.resumeFrom(4, 0L, r3.framesDigest());
        for (int t = 5; t <= 12; t++) r3.advance(rep2, t);
        check("repeating frames keep their own period after resume (1,4,7,10 minus what already passed)",
                rep2.equals(List.of(7, 10)));

        // 快照能往返：同一份 (tick, bitmap) 恢复两次，后续触发序列必须完全一致
        var a = FrameRunner.<List<Integer>>builder().at(3, (c, tk) -> c.add(tk)).at(9, (c, tk) -> c.add(tk)).build();
        List<Integer> scratch = new ArrayList<>(); // 不能用 List.of()：帧的回调就是 c.add(...)，不可变表会抛 UOE
        a.advance(scratch, 3); a.advance(scratch, 4);
        long bits = a.firedBitmap(); int at = a.lastTick();
        List<Integer> s1 = new ArrayList<>();
        List<Integer> s2 = new ArrayList<>();
        var b1 = FrameRunner.<List<Integer>>builder().at(3, (c, tk) -> c.add(tk)).at(9, (c, tk) -> c.add(tk)).build();
        var b2 = FrameRunner.<List<Integer>>builder().at(3, (c, tk) -> c.add(tk)).at(9, (c, tk) -> c.add(tk)).build();
        b1.resumeFrom(at, bits, b1.framesDigest()); b2.resumeFrom(at, bits, b2.framesDigest());
        for (int t = 5; t <= 12; t++) { b1.advance(s1, t); b2.advance(s2, t); }
        check("the same snapshot restored twice yields the identical trigger sequence (deterministic)",
                s1.equals(s2) && s1.equals(List.of(9)));

        // 位图宽度是硬上界：超了就在登记期抛，不许静默截断
        boolean rejected = false;
        try {
            var big = FrameRunner.builder();
            for (int i = 1; i <= 65; i++) big.at(i, (c, tk) -> { });
            big.build();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check("a frame table wider than the persistence bitmap is rejected at build time (no silent truncation)",
                rejected);
        boolean fits = false;
        try {
            var exactly = FrameRunner.builder();
            for (int i = 1; i <= 64; i++) exactly.at(i, (c, tk) -> { });
            exactly.build();
            fits = true;
        } catch (IllegalArgumentException e) {
            fits = false;
        }
        check("64 frames still build (the cap boundary is inclusive, both ends pinned)", fits);

        // 轮 22 P2-1：`resumeFrom` 的**位图半侧**原先是覆盖，而 `lastTick` 半侧是 `Math.max`——
        // 两侧不对称。已推进过的 runner 被一份<b>更旧</b>的快照打一下，就会把"窗口里已经消费过"
        // 的那一帧重新武装，下一 tick 在窗口内<b>再触发一次</b>（＝白挨一刀的那类事故换了入口）。
        // 原先五处 resumeFrom 调用<b>全部作用在从未 advance 过的新表上</b>，所以这个方向零覆盖。
        var armed = FrameRunner.<List<Integer>>builder()
                .between(2, 10, (c, tk) -> c.add(tk))
                .between(20, 25, (c, tk) -> c.add(tk))
                .build();
        List<Integer> armedHits = new ArrayList<>();
        for (int t = 1; t <= 21; t++) armed.advance(armedHits, t);
        boolean replayed = false;
        if (armedHits.equals(List.of(2, 20))) {         // 前置条件必须真成立，否则这条桩什么都没测
            armed.resumeFrom(5, 0L, armed.framesDigest());                    // 更旧的快照 + 全 0 位图
            List<Integer> after = new ArrayList<>();
            for (int t = 22; t <= 25; t++) armed.advance(after, t);
            replayed = after.isEmpty();                 // 20 号那帧不许再触发一次
        }
        check("an older snapshot cannot re-arm an already-consumed frame on an advanced runner (bitmap side only advances)",
                replayed);
        // 同一份快照恢复两次也必须落在同一个状态（幂等，覆盖语义下第二次会改写第一位的状态）
        var twiceA = FrameRunner.<List<Integer>>builder().at(3, (c, tk) -> c.add(tk)).build();
        twiceA.resumeFrom(1, 0L, twiceA.framesDigest());
        twiceA.resumeFrom(1, 0L, twiceA.framesDigest());
        List<Integer> twiceHits = new ArrayList<>();
        for (int t = 2; t <= 6; t++) twiceA.advance(twiceHits, t);
        check("resumeFrom is idempotent: applying the same snapshot twice still fires the pending frame once",
                twiceHits.equals(List.of(3)));

        // 上界必须在<b>登记期</b>就拦住：build() 那道抛要等到"出招那一 tick"才执行得到
        // （{@code MoveDef.newRunner()} 由 AttackState 构造器调用），所以下游模组写 65 帧时
        // 只靠 build() 的守卫＝服务端打到一半炸掉（审查轮 22 本批唯一真回归）。
        boolean registerRejected = false;
        try {
            var mb = new com.klze.colossus.move.MoveSetBuilder(null,
                    new net.minecraft.resources.ResourceLocation("colossus", "t"))
                    .move("long").duration(70);
            for (int i = 1; i <= 65; i++) mb.at(i, (boss, tick) -> { });
            mb.done();
        } catch (IllegalArgumentException expected) {
            registerRejected = true;
        }
        boolean registerFits = false;
        try {
            var mb = new com.klze.colossus.move.MoveSetBuilder(null,
                    new net.minecraft.resources.ResourceLocation("colossus", "t"))
                    .move("edge").duration(64);
            for (int i = 1; i <= 64; i++) mb.at(i, (boss, tick) -> { });
            mb.done();
            registerFits = true;
        } catch (IllegalArgumentException e) {
            registerFits = false;
        }
        check("the frame-table bound is rejected at DSL registration, not when the move is executed mid-fight",
                registerRejected && registerFits);

        // 轮 23 P2-1 / 轮 22 §4.5：续播前先验两件事——**指纹对不对**、**位图有没有越界位**。
        // 位图按注册序的第 i 位解释，所以"帧序变了"就是"扣到别人身上"；而 || 语义一旦被越界位
        // 骗到，那一发在整个窗口内一次都不触发，且**再恢复多少次都解不开**（覆盖式至少还能被
        // 后一份正常快照纠正）。两种都必须整份作废、状态一点不动。
        var dig = FrameRunner.<List<Integer>>builder().at(3, (c, tk) -> c.add(tk)).at(9, (c, tk) -> c.add(tk)).build();
        String digestReason = dig.resumeFrom(0, 0L, dig.framesDigest() ^ 0x55L);
        List<Integer> afterRefusal = new ArrayList<>();
        for (int t = 1; t <= 10; t++) dig.advance(afterRefusal, t);
        boolean digestRefused = digestReason != null && digestReason.contains("digest")
                && afterRefusal.equals(List.of(3, 9)); // 被拒的快照不得留下任何痕迹
        // 越界位：这张表只有 2 帧，第 2 位以上不属于它。bitmap=-1（整招缴械）必须被认成损坏。
        var wide = FrameRunner.<List<Integer>>builder().at(2, (c, tk) -> c.add(tk)).build();
        String bitsReason = wide.resumeFrom(0, -1L, wide.framesDigest());
        boolean bitsRefused = bitsReason != null && wide.firedBitmap() == 0L;
        // 64 帧的表没有"上面那些位"可言（-1L << 64 会别名回 -1L << 0），这一档必须让路，
        // 否则合法的满宽度位图会被误判成损坏——边界两头都要钉。
        var full = FrameRunner.builder();
        for (int i = 1; i <= 64; i++) full.at(i, (c, tk) -> { });
        var fullRunner = full.build();
        boolean fullTableAcceptsAllBits = fullRunner.resumeFrom(0, -1L, fullRunner.framesDigest()) == null;
        check("a snapshot whose frame-table digest differs is refused outright (no silent permanent mute)",
                digestRefused && bitsRefused && fullTableAcceptsAllBits);
        // 轮 23 P2-2：第三条入口（公共工厂 MoveDef.of）也必须被带帧表的对象自己兜住——
        // DSL 的 done() 与 JSON 的 decodeFrames 都拒了，唯独 of() 没有，于是 addon 塞 65 帧
        // 会一路走到 newRunner() 把异常抛在出招那一 tick。
        boolean factoryRejected = false;
        try {
            var many = new java.util.ArrayList<FrameRunner.Frame<com.klze.colossus.entity.ColossusBossEntity>>();
            for (int i = 1; i <= 65; i++) {
                many.add(new FrameRunner.Frame<>(i, i, (boss, tk) -> { }));
            }
            com.klze.colossus.move.MoveDef.of(new net.minecraft.resources.ResourceLocation("colossus", "wide"),
                    70, 0, 0, 1, 8.0f, "anim", ctx -> 1, ctx -> true, 0, 0, many);
        } catch (IllegalArgumentException expected) {
            factoryRejected = true;
        }
        check("MoveDef's own constructor rejects an over-wide frame table (the third public entry point)",
                factoryRejected);

        check("resume rejection: a stale or nonsense timeline is refused with a reason",
                FrameRunner.resumeRejection(50, 20, 0) != null
                        && FrameRunner.resumeRejection(5, 20, 999) != null
                        && FrameRunner.resumeRejection(-1, 20, 0) != null
                        && FrameRunner.resumeRejection(2, 0, 0) != null
                        && FrameRunner.resumeRejection(5, 20, 14) == null);
        // 轮 22 P3-2：原先 `stateTick` 与 `elapsed` 各自单独和 duration 比，两边都放行时
        // **合起来可以远超整招长度**——`resumeRejection(95, 100, 100)` 交出 null，而那一刻这招
        // 已经在第 195 帧、整招只有 100 帧。javadoc 那句"只有超出整招长度才拒"按字面就是求和。
        // 第二条钉"理由串里必须带那个越界的数字"：接线时这条 reason 直接进日志，
        // 只说"有没有拒"的门会让日志里出现"拒了，但不知道为什么"。
        String sumReason = FrameRunner.resumeRejection(95, 100, 100);
        check("resume rejection compares the SUM (stateTick + elapsed), and the reason carries the number",
                sumReason != null && sumReason.contains("195")
                        && FrameRunner.resumeRejection(95, 100, 4) == null
                        // 求和必须走 long：`99 + 2_000_000_000` 用 int 加会溢出成负数，
                        // 那会把"最越界"的一档判成放行
                        && FrameRunner.resumeRejection(99, 100, 2_000_000_000) != null);
    }

    /**
     * 竞技场边界的纯判据（第三十六批，v14 取证来料）。这些性质原先只住在 {@code ArenaSession}
     * 里——那个类 import MC，四条门里只有 {@code runGameTestServer} 碰得到它，而它需要一个活世界
     * 加活区块，所以"配一个坏半轴就把玩家每 tick 弹来弹去"这类故障<b>没有任何门能提前看见</b>。
     */
    private static void testArenaBounds() {
        double def = com.klze.colossus.env.ArenaBounds.DEFAULT_RADIUS_XZ;
        // ①坏半轴一律回默认，且<b>回出来的值必须仍然 >0</b>——这条才是"防弹跳风暴"的真判据：
        // AABB 的 min>max 会让 contains() 恒假，于是每个 tick 都判越界、每 tick 都传送一次。
        boolean radiusLegal = true;
        double[] bad = new double[]{0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, 1e9,
                com.klze.colossus.env.ArenaBounds.MAX_BOUND_RADIUS + 0.5};
        for (double v : bad) {
            double got = com.klze.colossus.env.ArenaBounds.radiusOr(v, def);
            radiusLegal &= got == def;
        }
        radiusLegal &= com.klze.colossus.env.ArenaBounds.radiusOr(64.0, def) == 64.0
                && com.klze.colossus.env.ArenaBounds.radiusOr(
                        com.klze.colossus.env.ArenaBounds.MAX_BOUND_RADIUS, def)
                        == com.klze.colossus.env.ArenaBounds.MAX_BOUND_RADIUS; // 边界含
        check("an unusable arena radius falls back to the default, and the fallback is strictly positive",
                radiusLegal);

        // ②宽限允许 0（"立刻拉回"是作者的合法意图），提醒间隔至少 1
        boolean timingDomain = com.klze.colossus.env.ArenaBounds.graceTicksOr(0, 200) == 0
                && com.klze.colossus.env.ArenaBounds.graceTicksOr(-1, 200) == 200
                && com.klze.colossus.env.ArenaBounds.graceTicksOr(24_001, 200) == 200
                && com.klze.colossus.env.ArenaBounds.warnEveryTicksOr(0, 100) == 100
                && com.klze.colossus.env.ArenaBounds.warnEveryTicksOr(20, 100) == 20;
        check("grace may be zero but the warn interval must be at least one tick", timingDomain);

        // ③读档坐标三元组的可用性：缺一个键 / 写成字符串 / NaN / 超世界边界，全部判不可用。
        //    1.20.1 的 getDouble 对类型不对的键回 0，所以"少一个 home_y"会静默把圆心沉到世界底。
        boolean coordGate = com.klze.colossus.env.ArenaBounds.usableCoordinate(10, 20, 30)
                && !com.klze.colossus.env.ArenaBounds.usableCoordinate(10, Double.NaN, 30)
                && !com.klze.colossus.env.ArenaBounds.usableCoordinate(10, Double.POSITIVE_INFINITY, 30)
                && !com.klze.colossus.env.ArenaBounds.usableCoordinate(
                        com.klze.colossus.env.ArenaBounds.MAX_CENTER_ABS + 1, 20, 30)
                && !com.klze.colossus.env.ArenaBounds.usableCoordinate(10, 20, -1e30);
        check("a save coordinate is usable only when all three axes are finite and inside the world",
                coordGate);

        // ④封路分类的<b>优先级</b>：区块没加载必须排在"被占用"之前——
        // 反过来的话，会话就会为了问 canBeReplaced 而去 getBlockState，服务端强制生成缺失区块，
        // 那道门等于没设（v14 取证里 TF 1.20.1 的七个 Boss 都是先问区块）。
        boolean sealOrder = com.klze.colossus.env.ArenaBounds.classifySeal(false, false)
                == com.klze.colossus.env.ArenaBounds.SealOutcome.NO_CHUNK
                && com.klze.colossus.env.ArenaBounds.classifySeal(false, true)
                == com.klze.colossus.env.ArenaBounds.SealOutcome.NO_CHUNK
                && com.klze.colossus.env.ArenaBounds.classifySeal(true, false)
                == com.klze.colossus.env.ArenaBounds.SealOutcome.OCCUPIED
                && com.klze.colossus.env.ArenaBounds.classifySeal(true, true)
                == com.klze.colossus.env.ArenaBounds.SealOutcome.PLACED;
        check("seal classification asks the chunk before the block (never probes a missing chunk)", sealOrder);

        // ⑤解封：区块没加载 ⇒ 这一格<b>不许当作已恢复</b>（旧写法调过 setBlock 就删账，
        // 于是方块永久留在世界里、连重试的机会都没了）
        check("an unrestored slot is reported as not-ready so its snapshot survives for retry",
                !com.klze.colossus.env.ArenaBounds.worldReady(false)
                        && com.klze.colossus.env.ArenaBounds.worldReady(true));
    }

    private static void testFrameSingleShot() {
        List<Integer> hit = new ArrayList<>();
        FrameRunner<Object> fr = FrameRunner.builder()
                .at(3, (c, t) -> hit.add(t))
                .build();
        for (int t = 1; t <= 5; t++) fr.advance(null, t);
        check("single frame fires exactly at tick", hit.equals(List.of(3)));
    }

    private static void testFrameWindowFiresOnce() {
        List<Integer> hit = new ArrayList<>();
        FrameRunner<Object> fr = FrameRunner.builder()
                .between(4, 7, (c, t) -> hit.add(t))
                .build();
        for (int t = 1; t <= 9; t++) fr.advance(null, t);
        check("window fires once on first entry", hit.equals(List.of(4)));
    }

    private static void testFrameMissedWindowNotCompensated() {
        List<Integer> hit = new ArrayList<>();
        FrameRunner<Object> fr = FrameRunner.builder()
                .between(4, 5, (c, t) -> hit.add(t))   // 被跳过
                .between(8, 9, (c, t) -> hit.add(t))   // 正常命中
                .build();
        // tick 从 3 直接跳到 6（模拟偶发延迟）：窗口 1 错过、不补发
        fr.advance(null, 3);
        fr.advance(null, 6);
        fr.advance(null, 8);
        check("missed window is dropped, later window still fires", hit.equals(List.of(8)));
    }

    private static void testFrameAdvanceIdempotent() {
        List<Integer> hit = new ArrayList<>();
        FrameRunner<Object> fr = FrameRunner.builder()
                .at(2, (c, t) -> hit.add(t))
                .build();
        fr.advance(null, 2);
        fr.advance(null, 2); // 同 tick 重放
        fr.advance(null, 1); // 倒退输入
        check("advance is idempotent & monotonic", hit.equals(List.of(2)));
    }

    private static void testProgressLedger() {
        java.util.Map<String, Integer> kills = new java.util.LinkedHashMap<>();
        kills.put("colossus:a", 3);
        kills.put("colossus:b", 1);
        java.util.Set<String> defeated = new java.util.LinkedHashSet<>(java.util.List.of("colossus:a"));
        var tag = com.klze.colossus.progress.ProgressLedger.encode(0x5EEDL, 42L, kills, defeated);
        var back = com.klze.colossus.progress.ProgressLedger.decode(tag);
        check("progress snapshot round-trips identity, revision and counts",
                back.sourceIdentity() == 0x5EEDL && back.revision() == 42L && back.killCount("colossus:a") == 3
                        && back.killCount("colossus:b") == 1 && back.isDefeated("colossus:a")
                        && !back.isDefeated("colossus:b"));

        // 畸形条目（缺 id 的行）只跳过该条，不整包作废、不抛
        var rows = (net.minecraft.nbt.ListTag) tag.get("kills");
        net.minecraft.nbt.CompoundTag broken = new net.minecraft.nbt.CompoundTag();
        broken.putInt("count", 9);
        rows.add(broken);
        var tolerant = com.klze.colossus.progress.ProgressLedger.decode(tag);
        check("malformed entry is skipped, rest of the package still applies",
                tolerant.killCount("colossus:a") == 3 && tolerant.killCount("colossus:b") == 1
                        && tolerant.kills().size() == 2);

        java.util.Map<String, Integer> flood = new java.util.LinkedHashMap<>();
        for (int i = 0; i < com.klze.colossus.progress.ProgressLedger.MAX_ENTRIES + 50; i++) {
            flood.put("colossus:f" + i, i);
        }
        var capped = com.klze.colossus.progress.ProgressLedger.decode(
                com.klze.colossus.progress.ProgressLedger.encode(0x5EEDL, 7L, flood, java.util.Set.of()));
        // 等号而非 <=：写成 <= 时把上限调到 40960、或把封顶循环整个删掉，这条照样绿（弱断言）
        check("decode-side cap trims to exactly MAX_ENTRIES",
                capped.kills().size() == com.klze.colossus.progress.ProgressLedger.MAX_ENTRIES);

        check("dirty check: same revision sends nothing, changed sends once",
                !com.klze.colossus.progress.ProgressLedger.shouldSend(9L, 9L)
                        && com.klze.colossus.progress.ProgressLedger.shouldSend(-1L, 9L));
    }

    /** 延迟工作的数据形态（第十六批）：区域与爆发都必须能过 NBT 一遭不变样。 */
    private static void testDeferredWorkData() {
        var zone = new com.klze.colossus.env.TelegraphZone(10.5, 3.0, -4.25,
                6.5, 1.0, 30, 0xFF4040, "ring");
        var back = com.klze.colossus.env.TelegraphZone.fromTag(zone.toTag());
        check("zone tag round-trips geometry, warn and visual",
                Math.abs(back.cx() - 10.5) < 1e-9 && Math.abs(back.cy() - 3.0) < 1e-9
                        && Math.abs(back.cz() + 4.25) < 1e-9 && Math.abs(back.radiusXZ() - 6.5) < 1e-9
                        && Math.abs(back.radiusY() - 1.0) < 1e-9 && "ring".equals(back.visual())
                        // 第二十四批补：旧 fromTag 把 warn 写死成 0，重载路径的轮廓寿命少一整段 warn
                        && back.warnTicks() == 30);
        // 等号而非 >=：>= 时把 FADE 改成 0、或把 lifetime 写成 warnTicks 都不会红（弱断言）
        check("lifetime is exactly warn + fade once (one number, two consumers)",
                zone.lifetimeTicks() == 30 + com.klze.colossus.env.TelegraphZone.FADE_TICKS
                        && back.lifetimeTicks() == zone.lifetimeTicks());
        check("a zero/negative warn still yields a drawable lifetime (no zero-length outline)",
                new com.klze.colossus.env.TelegraphZone(0, 0, 0, 1, 1, 0, 0, "dust").lifetimeTicks()
                        == 1 + com.klze.colossus.env.TelegraphZone.FADE_TICKS
                        && new com.klze.colossus.env.TelegraphZone(0, 0, 0, 1, 1, -5, 0, "dust")
                                .lifetimeTicks() == 1 + com.klze.colossus.env.TelegraphZone.FADE_TICKS);

        // === 轮 16 P2-3/P3-5：形状自身的上界钳在 record 的规范构造器里，三条消费路径同时受益 ===
        // 半径无界不是"难看"而是事故：几何档每帧顶点数 ≈6πr（r=1e9 时 int 饱和成 Integer.MAX_VALUE
        // ⇒ 每帧 42 亿顶点的循环）、粒子档每 tick 点数、服务端 ZoneWork 的 getEntitiesOfClass(巨大 AABB)。
        var wide = new com.klze.colossus.env.TelegraphZone(0, 0, 0, 1e9, 1e9, 30, 0, "ring");
        check("radius is clamped at the shape itself (both axes, positive + finite)",
                wide.radiusXZ() == com.klze.colossus.env.TelegraphZone.MAX_RADIUS
                        && wide.radiusY() == com.klze.colossus.env.TelegraphZone.MAX_RADIUS);
        var junk = new com.klze.colossus.env.TelegraphZone(0, 0, 0, Double.NaN, -4.0, 30, 0, "ring");
        check("NaN / negative radius fall back to a drawable 1.0 rather than propagating",
                junk.radiusXZ() == 1.0 && junk.radiusY() == 1.0);
        // 溢出这一档是本轮新抓的：warn=Integer.MAX_VALUE 时 settle 变 1、lifetime 变负数，
        // "想要超长预警"静默成"没有预警的一发"
        var huge = new com.klze.colossus.env.TelegraphZone(0, 0, 0, 4, 1, Integer.MAX_VALUE, 0, "ring");
        check("warn saturates into MAX_WARN_TICKS so settle/lifetime never overflow",
                huge.warnTicks() == com.klze.colossus.env.TelegraphZone.MAX_WARN_TICKS
                        && huge.settleDelayTicks() > 0 && huge.lifetimeTicks() > 0);
        // 关系不变量：轮廓必须比"那一发的结算时刻"<b>活得久</b>（先消失的就是"伤害凭空落下"）
        boolean outlineOutlivesBurst = true;
        int warnCap = com.klze.colossus.env.TelegraphZone.MAX_WARN_TICKS;
        for (int w : new int[]{0, 1, 5, 30, 60, warnCap, warnCap + 1, Integer.MAX_VALUE, -5}) {
            var z = new com.klze.colossus.env.TelegraphZone(0, 0, 0, 4, 1, w, 0, "dust");
            // 断的是"钳后的值"而不是输入值：否则越界那三档会拿 max(1,w+1) 去比钳位结果，
            // 恰好把钳位本身判成 bug（轮 17 P3-4 复算时发现我第一版就是这么写错的）。
            int want = Math.min(Math.max(w, 0), warnCap);
            outlineOutlivesBurst &= z.warnTicks() == want
                    && z.settleDelayTicks() == Math.max(1, want + 1)
                    && z.lifetimeTicks() >= z.settleDelayTicks()
                    && z.lifetimeTicks() > 0 && z.settleDelayTicks() > 0;
        }
        check("across every legal warn: outline lifetime >= burst delay (one shared formula)",
                outlineOutlivesBurst);
        var blankVisual = new com.klze.colossus.env.TelegraphZone(0, 0, 0, 4, 1, 20, 0, "  ");
        check("a blank visual falls back to the particle tier instead of rendering nothing at all",
                "dust".equals(blankVisual.visual()));

        // === 轮 17 P3-5 后半 / P3-11 / P2-3：圆心钳位、样式名一条规则、粒子成本按存活数量纲封顶 ===
        var far = new com.klze.colossus.env.TelegraphZone(Double.NaN, 1e30, -1e30, 4, 1, 20, 0, "dust");
        // 只断"钳后的运行时值"。原先这里还有一条 `MAX_CENTER_ABS == WorldBorder.MAX_CENTER_COORDINATE`，
        // 但两边都是编译期常量 ⇒ javac 直接折成 true（轮 11 那个 static 断言的同一个坑），
        // 常量值本身由 javac 的常量内联保证，写在断言里是装饰不是判据。
        check("center coords are clamped: NaN->0, |v|->+-MAX_CENTER_ABS, and the box stays finite",
                far.cx() == 0.0
                        && far.cy() == com.klze.colossus.env.TelegraphZone.MAX_CENTER_ABS
                        && far.cz() == -com.klze.colossus.env.TelegraphZone.MAX_CENTER_ABS
                        && Double.isFinite(far.box().maxX) && Double.isFinite(far.box().minZ));
        check("\"no visual written\" has exactly one rule (null / \"\" / blank all land on DEFAULT_VISUAL)",
                com.klze.colossus.env.TelegraphZone.DEFAULT_VISUAL.equals(
                        com.klze.colossus.env.TelegraphZone.visualOrDefault(null))
                        && com.klze.colossus.env.TelegraphZone.DEFAULT_VISUAL.equals(
                        com.klze.colossus.env.TelegraphZone.visualOrDefault(""))
                        && com.klze.colossus.env.TelegraphZone.DEFAULT_VISUAL.equals(
                        com.klze.colossus.env.TelegraphZone.visualOrDefault(" \t "))
                        && "ring".equals(com.klze.colossus.env.TelegraphZone.visualOrDefault("ring")));
        // 成本 = 每 tick 生成率 × <b>粒子自身寿命</b>（轮 19 P2-1：轮 18 那版乘的是"还剩几 tick"，
        // 数的是未来发射而不是场上存活——只剩 1 tick 的大圈被记 5 个、实际场上站着 197 个）。
        // 下面这套断言的设计要求是"改错必须变红"（轮 19 P2-2：上一批新写的两条一条恒真、一条单向）。
        final int BUDGET = com.klze.colossus.env.TelegraphBudget.MAX_LIVE_GLOBAL;
        final int CAP = com.klze.colossus.env.TelegraphBudget.MAX_LIVE_PER_OUTLINE;
        boolean costIsLifetimePeak = true;   // ①记账 == 一生峰值（不早不晚）
        boolean coversRing = true;           // ②圈合得上（slots ≤ 率 × 覆盖窗口）
        boolean neverUnderBooks = true;      // ③轨迹上每一点都不低报（这条才真抓住 P2-1）
        for (int life : new int[]{com.klze.colossus.env.TelegraphBudget.DUST_LIVE_TICKS,
                com.klze.colossus.env.TelegraphBudget.SPARK_LIVE_TICKS}) {
            for (double radius : new double[]{0.5, 6.0, 30.0, 256.0}) {
                for (int span : new int[]{11, 40, 70, 1210}) {
                    var plan = com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * radius, life,
                            0L, span, com.klze.colossus.env.TelegraphBudget.MAX_LIVE_GLOBAL);
                    if (plan.empty()) { costIsLifetimePeak = false; continue; }
                    costIsLifetimePeak &= plan.liveCost() == plan.ratePerTick()
                            * Math.min(life, span)
                            && plan.liveCost() <= com.klze.colossus.env.TelegraphBudget.MAX_LIVE_PER_OUTLINE;
                    coversRing &= plan.slots() >= plan.ratePerTick()
                            && (long) plan.ratePerTick() * Math.min(life, span) >= plan.slots();
                    // 逐 tick 推演：第 t tick 场上真的有 率 × min(粒子寿命, t) 个粒子，
                    // 且到期之后按尾段衰减。旧实现记"率 × 剩余"，尾段必然低于这个数。
                    for (int t = 1; t <= span + life + 1; t++) {
                        int alive = com.klze.colossus.env.TelegraphBudget.tailAlive(
                                plan.ratePerTick(), life, 0L, span, t);
                        neverUnderBooks &= plan.liveCost() >= alive;
                    }
                }
            }
        }
        check("particle ledger books each outline's LIFETIME PEAK (rate x min(particle life, emission span))",
                costIsLifetimePeak);
        check("the drawn outline closes (slots <= rate x coverage window)", coversRing);
        check("booked cost is >= the ring's real on-screen population at every tick, including the tail",
                neverUnderBooks);
        // 粒子寿命长到"一发就超上限"时必须不画，而不是"至少发一个"（轮 19 P2-2 第③条）
        check("a particle whose own life exceeds the cap makes the outline draw nothing at all",
                com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * 6.0, 300, 0L, 1210L, BUDGET).empty()
                        && com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * 6.0, 241, 0L, 1210L, BUDGET).empty()
                        && !com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * 6.0, 240, 0L, 1210L, BUDGET).empty());
        int booked = 0;
        int funded = 0;
        for (int i = 0; i < 40; i++) { // 40 条同放（> HARD_MAX_TELEGRAPHS，故意压满）
            var plan = com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * 6.0,
                    com.klze.colossus.env.TelegraphBudget.DUST_LIVE_TICKS, 0L, 1210L, BUDGET - booked);
            if (plan.empty()) continue;
            booked += plan.liveCost();
            funded++;
        }
        check("the global ledger never books more than the cap, and the tail of the batch gets nothing",
                booked <= BUDGET && funded > 0 && funded < 40);
        // 尾段记账（轮 19 遗留的 post-mortem 滞留）：圈到期后粒子还要活最多"粒子寿命"那么久，
        // 账本必须继续记它，否则"合计 ≤ 上限"只在圈还活着的那段成立。
        boolean tailHolds = true;
        for (int life : new int[]{48, 71}) {
            for (int span : new int[]{11, 70, 1210}) {
                var plan = com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * 6.0, life, 0L, span, 2000);
                // 到期那一 tick：尾段应当等于它生前的峰值账（粒子还没开始退场）
                tailHolds &= com.klze.colossus.env.TelegraphBudget.tailAlive(
                        plan.ratePerTick(), life, 0L, span, span) == plan.liveCost();
                // 之后逐 tick 单调不增，且到 end + 粒子寿命 时归零
                int prev = plan.liveCost() + 1;
                for (int dt = 1; dt <= life + 2; dt++) {
                    int now = com.klze.colossus.env.TelegraphBudget.tailAlive(
                            plan.ratePerTick(), life, 0L, span, span + dt);
                    tailHolds &= now <= prev && now >= 0;
                    prev = now;
                }
                tailHolds &= com.klze.colossus.env.TelegraphBudget.tailAlive(
                        plan.ratePerTick(), life, 0L, span, span + life + 1) == 0;
            }
        }
        check("an outline's particles stay booked after it expires, decaying to zero exactly one particle-life later",
                tailHolds);
        // 尾段账本的性质（轮 20 P1-1 + 轮 21 P2-3/P2-4 的判据）。原先账本住在 TelegraphClient 里，
        // 四条门一条都执行不到它——runGameTestServer 是无客户端的服务端，所以"每次投影重投就多
        // 一份尾段账"那个 P1 能在全绿底下活一整个批次。断言一律用<b>等号</b>与<b>具体条目</b>：
        // 轮 21 P2-4 指出上一版这里两条都是 `<=`、第三条只重复断条数，把"逐出谁"改成删最老也全绿。
        final int ceiling = com.klze.colossus.env.TelegraphBudget.MAX_LIVE_GLOBAL;
        var ledger = new com.klze.colossus.env.TailLedger();
        for (int publish = 0; publish < 5; publish++) {
            for (long t = 100L; t <= 120L; t++) ledger.noteEmission(42L, 5, t); // 真发射 21 个 tick
            ledger.retire(42L, 48);                                            // 同一条圈被重投 5 次
        }
        // tailAlive 数的是"过去粒子寿命内发射过、还没散的那些 tick"：发射到 120 止（窗口 [100,121)），
        // 所以 now=140 时还剩 21 个 tick 的份（105 颗），now=169 起颗粒不留
        boolean idempotent = ledger.size() == 1
                && ledger.bookNow(140L, ceiling) == 5 * 21
                && ledger.bookNow(169L, ceiling) == 0;
        ledger.cancel(42L);
        idempotent &= ledger.size() == 0 && ledger.bookNow(200L, ceiling) == 0; // 又活了：撤账
        var neverEmitted = new com.klze.colossus.env.TailLedger();
        neverEmitted.retire(7L, 48); // 一 tick 都没真发射过 ⇒ 没有余晖可记（不留幽灵账）
        idempotent &= neverEmitted.size() == 0 && neverEmitted.bookNow(600L, ceiling) == 0;
        // 轮 23 P3-5：低报必须<b>至少可观测</b>。retire 的返回值原先在两个调用点都被丢弃，
        // 于是"根本没记上"与"记上又被逐出"这两件事只有自检知道。计数器是客户端一次性告警的口子。
        idempotent &= neverEmitted.rejectedBookings() == 1;
        var crowded = new com.klze.colossus.env.TailLedger();
        // 每条圈的发射跨度都做有梯度的（轮 22 §4.2 + 轮 23 的新模型）：尾段窗口现在直接是
        // {@code [首次发射 tick, 末次发射 tick + 1)}，所以两端都不同 ⇒ "按 end 逐出"真的可测。
        for (int i = 0; i < 400; i++) {
            crowded.noteEmission(i, 5, 2_981L + i); // 首次
            crowded.noteEmission(i, 5, 3_000L + i); // 末次（跨度 20 tick）
            crowded.retire(i, 48);
        }
        // 观察点取 3_020：还在衰减期内（最迟 3_020 + 48 才散完），5_000 起什么都没有了
        int tailUncapped = crowded.uncappedSum(3_020L);
        // <b>写数字，不复述式子</b>（轮 22 §4.3）：原先左半边是 `ceiling * TAIL_BUDGET_PERCENT / 100`，
        // 与被测类 `TailLedger.bookNow` 里同一个式子、同一个常量——把 25 改成 90 断言照样全绿，
        // 而 check 名"exactly 25%"当场变谎。500 是这个策略的数字，另加一条"装饰不得过半"的策略上界。
        int tailBooked = crowded.bookNow(3_020L, ceiling);
        boolean capped = tailBooked == 500
                && com.klze.colossus.env.TailLedger.TAIL_BUDGET_PERCENT <= 25
                && tailUncapped > tailBooked; // 右半边把"真实残影"与"账面占用"两个量分开钉（这条本来有效）
        // 逐出策略的判据必须能把<b>三种实现</b>分开（轮 22 §4.1，也是本仓一直缺一次成功变异的那条）：
        // "逐 end 最大者" / "逐最新插入者" / "逐插入序最老者"。旧夹具里 80 号<b>同时</b>是
        // "end 最大"和"最新插入"，所以改成"新来的直接不入账"也全绿。
        // 新夹具：先填满 64 条（end 202..265），再塞一条<b>最新但 end 最小</b>的——
        // 逐 end 最大 ⇒ 掉 64 号、留 999 与 1 号；逐最新 ⇒ 掉 999；逐最老 ⇒ 掉 1 号。
        var evict = new com.klze.colossus.env.TailLedger();
        for (int i = 1; i <= com.klze.colossus.env.TailLedger.MAX_ENTRIES; i++) {
            evict.noteEmission(i, 5, 200L);
            evict.noteEmission(i, 5, 200L + i);
            evict.retire(i, 48);
        }
        evict.noteEmission(999L, 5, 205L);
        boolean evictedYoungest = evict.retire(999L, 48);
        boolean dropsSlowest = evictedYoungest
                && evict.size() == com.klze.colossus.env.TailLedger.MAX_ENTRIES
                && evict.contains(999L) && evict.contains(1L) && !evict.contains(64L);
        // 轮 23 P2-4（轮 22 P2-4 的另一半）：本地钟落后时，尾段<b>长度</b>不能塌成 1 tick。
        // 旧写法拿服务端时基的 start 去减客户端时基的 lastEmit，落后 L tick 就交 rate×1，
        // 而真实场上站着 rate×min(寿命, 已发射 tick 数)——低报最多 life 倍，正是全局闸唯一在防的方向。
        // 现在窗口两端都是账本自己的客户端读数（[890, 901) ⇒ 21 tick 里已发射的 11 个），
        // 落后量在减法里根本不出现。判据取 55 这个具体数：旧实现这里只会交 5。
        var lagged = new com.klze.colossus.env.TailLedger();
        for (long t = 890L; t <= 900L; t++) lagged.noteEmission(3L, 5, t); // 发射 11 个 tick
        boolean lagBooked = lagged.retire(3L, 48);
        check("the tail's length comes from the ledger's own emission facts, not from subtracting two clocks",
                lagBooked && lagged.size() == 1 && lagged.bookNow(901L, ceiling) == 5 * 11);
        // 轮 21 P2-3：额度被抢光的那几 tick 里真撒过的粒子不许掉账——峰值存在账本里，
        // 而尾段窗口收窄到最后一次真发射 + 1（不是名义 end）
        var starved = new com.klze.colossus.env.TailLedger();
        for (long t = 100L; t <= 110L; t++) starved.noteEmission(9L, 5, t); // 发射到 110 止，之后额度被抢光
        starved.retire(9L, 48);                   // 名义到期在 200：窗口只认发射事实，不认它
        boolean honestWindow = starved.bookNow(140L, ceiling) == 5 * 11 // 100..110 这 11 tick 的份
                && starved.bookNow(160L, ceiling) == 0; // 111 + 48 = 159 之后颗粒不留
        check("the tail ledger is idempotent per outline, and a view that never emitted leaves no ghost entry",
                idempotent);
        // 退休只"生成"尾段、不"撤销别人的账"（审查轮 22 P1-1 的真实内核）。第二次 retire 同一个键
        // 必须交出 false——发射事实已被第一次消费掉；"又活了"的撤销只由 cancel 在重新入库那一点做。
        var twice = new com.klze.colossus.env.TailLedger();
        twice.noteEmission(5L, 4, 120L);
        boolean firstRetire = twice.retire(5L, 48);
        boolean secondRetire = twice.retire(5L, 48);
        check("retire books a tail exactly once per outline and says so (generation, not cancellation)",
                firstRetire && !secondRetire && twice.size() == 1);
        // 发射事实表溢出时，逐出必须挑<b>停发最久</b>的那条：插入序最老的完全可能仍在长预警里画，
        // 挤掉它就是"退休后一帧都没记账"＝低报（危险方向）。判据两头都要响：新那条留住账，
        // 被丢那条<b>本来就归零</b>（丢了不撒谎，这条才是这个逐出策略成立的理由）。
        var overflow = new com.klze.colossus.env.TailLedger();
        // 夹具刻意让<b>插入序最老的那条反而是最新发射的</b>（轮 22 §4.2 的同类教训：上一版 128 条
        // 全用同一个 tick，于是"FIFO"与"按 lastEmit 逐出"给出同一个牺牲者，断言测不到策略本身）。
        for (int i = 0; i < com.klze.colossus.env.TailLedger.MAX_EMISSIONS; i++) {
            overflow.noteEmission(i, 5, 5_000L - i);            // key 0 最新（5_000），key 127 最老（4_873）
        }
        overflow.noteEmission(999L, 5, 9_000L);                  // 挤出一条
        boolean freshKept = overflow.retire(999L, 48);
        boolean newestSurvived = overflow.retire(0L, 48);                    // 插入最老但最新发射 ⇒ 该留
        boolean oldestEvicted = !overflow.retire(127L, 48);                   // 停发最久 ⇒ 该丢
        check("emission overflow evicts the longest-silent outline, not the oldest-inserted one",
                freshKept && newestSurvived && oldestEvicted
                        // 被丢那条<b>本来就归零</b>——这才是"丢它不撒谎"的依据，必须一起钉住
                        && com.klze.colossus.env.TelegraphBudget.tailAlive(5, 48, 4_870L, 4_873L, 9_000L) == 0);
        // 本地钟还没走到发射起点（换维度/重生：新 ClientLevel 从 0 起算，登记时刻来自服务端）
        // 时按<b>一生峰值</b>记，而不是交 0——与本文件"宁压不假"同向；但真散干净的那条必须还是 0。
        check("a tail seen before its own start books its lifetime peak, not zero (bias toward over-booking)",
                com.klze.colossus.env.TelegraphBudget.tailAlive(5, 48, 1_000L, 1_210L, 900L) == 5 * 48
                        && com.klze.colossus.env.TelegraphBudget.tailAlive(5, 48, 1_000L, 1_010L, 900L) == 5 * 10
                        && com.klze.colossus.env.TelegraphBudget.tailAlive(5, 48, 1_000L, 1_010L, 2_000L) == 0);
        check("tail residue is booked to exactly 25% of the ceiling while the physical residue is larger", capped);
        check("tail eviction drops the slowest-decaying entry and keeps the nearly-faded one", dropsSlowest);
        check("the tail window follows the last real emission tick, not the nominal expiry", honestWindow);
        // 轮 22 P2-7：客户端"这一 tick 到底撒不撒"的三道门原先只是三个 early-return 的<b>相对位置</b>，
        // 而发射事实记不记完全取决于那个位置——四条门不执行 client/*，所以挪错顺序可以全绿。
        // 判据搬进 TelegraphBudget 之后，这里第一次真的能把它跑红（缺门方向＝低报＝危险方向）。
        // 轮 23 P3-1：门必须<b>交出要撒的那个对象</b>，而不是交出布尔再让调用方自己去读率。
        // 上一版那条 gateTable 逐字就是 !a&&!b&&!c 的真值表——本仓定义的废断言：把 noteEmission
        // 挪到那道 return 之前、或整条删掉那个 if，150 条照样全绿。现在钉的是<b>有内容的返回值</b>：
        // 放行时原样交回预算（率/槽位/成本三个数都还在），任一条件成立时交 NONE（率恒 0）。
        var budgeted = com.klze.colossus.env.TelegraphBudget.plan(2 * Math.PI * 6.0,
                com.klze.colossus.env.TelegraphBudget.DUST_LIVE_TICKS, 0L, 1210L,
                com.klze.colossus.env.TelegraphBudget.MAX_LIVE_GLOBAL);
        boolean gateReturnsPayload =
                com.klze.colossus.env.TelegraphBudget.emissionOrZero(false, budgeted, false) == budgeted
                        && budgeted.ratePerTick() > 0 // 前置：预算本身非空，否则"交回同一个对象"是空话
                && com.klze.colossus.env.TelegraphBudget.emissionOrZero(true, budgeted, false).ratePerTick() == 0
                && com.klze.colossus.env.TelegraphBudget.emissionOrZero(false, budgeted, true).ratePerTick() == 0
                && com.klze.colossus.env.TelegraphBudget.emissionOrZero(false,
                        com.klze.colossus.env.TelegraphBudget.Outline.NONE, false).ratePerTick() == 0
                && com.klze.colossus.env.TelegraphBudget.emissionOrZero(true, budgeted, true).empty();
        // 落后判据本身：阈值是"一整个发射窗口（span）"，边界必须双侧钉住——早一 tick 画、晚一 tick 不画，
        // 否则"落后多少才不画"又会变成只有注释知道的口径（轮 16 P3-7 当初改措辞就是为了这个）。
        boolean skewEdge = !com.klze.colossus.env.TelegraphBudget.clockTooFarBehind(1_000L, 1_011L, 989L)
                && com.klze.colossus.env.TelegraphBudget.clockTooFarBehind(1_000L, 1_011L, 988L)
                // 反向（本地钟<b>超前</b>）永远不该被这道门挡：这道闸只管"落后"，
                // 挡超前的话就把正常补包也压掉了，而超前那一侧由到期清扫自然收敛
                && !com.klze.colossus.env.TelegraphBudget.clockTooFarBehind(1_000L, 1_011L, 5_000L);
        check("the emission gate hands back the payload to book (not a bare boolean the caller re-reads)",
                gateReturnsPayload && skewEdge);
        check("outline density is a per-tick-independent band (small ring 8, huge 96, NaN 8)",
                com.klze.colossus.env.TelegraphBudget.ringSlotCount(0.5) == 8
                        && com.klze.colossus.env.TelegraphBudget.ringSlotCount(2 * Math.PI * 256) == 96
                        && com.klze.colossus.env.TelegraphBudget.ringSlotCount(Double.NaN) == 8);
        // 错样式名不许落在贵的那一档（轮 17 P2-3 的初衷；轮 18 P3-2 要求它可断言）
        check("only the literal SPARK_VISUAL pays the END_ROD lifetime; unknown/blank/cased-wrong all cost dust",
                com.klze.colossus.env.TelegraphBudget.SPARK_VISUAL.equals("spark")
                        && com.klze.colossus.env.TelegraphBudget.particleLifeTicks("spark")
                        == com.klze.colossus.env.TelegraphBudget.SPARK_LIVE_TICKS
                        && com.klze.colossus.env.TelegraphBudget.particleLifeTicks("Spark")
                                == com.klze.colossus.env.TelegraphBudget.DUST_LIVE_TICKS
                        && com.klze.colossus.env.TelegraphBudget.particleLifeTicks("dust!")
                                == com.klze.colossus.env.TelegraphBudget.DUST_LIVE_TICKS
                        && com.klze.colossus.env.TelegraphBudget.particleLifeTicks(null)
                                == com.klze.colossus.env.TelegraphBudget.DUST_LIVE_TICKS);
        // 轮 19 P3-4 的那条"两个裸 long 不进钳 ⇒ (int)(end-start) 截成负数"不再需要一个判据：
        // 预算签名改掉之后<b>没有任何地方把 span 转成 int</b>（progress 走 double 除法、
        // 到期比较走 long 减法），危险面是"少了一次转换"消除的，不是靠一个上限数字掩盖的。

        // 偏移判据按**范数**而不是分量（轮 18 P3-1：逐分量会让 (2048,2048) 两条都"合法"、
        // 实际偏移 2896.3，比上限宽 √2 倍，而规格与回执文案写的都是"≤ 2048"）
        var bothEdges = com.klze.colossus.env.TelegraphZone.offsetNotice(2048.0, 2048.0);
        var scaled = com.klze.colossus.env.TelegraphZone.clampOffsets(4096.0, 100.0);
        check("offset cap is a norm (hypot), not a per-component cap, and clamping keeps the direction",
                bothEdges.isPresent()
                        && com.klze.colossus.env.TelegraphZone.offsetNotice(2048.0, 0.0).isEmpty()
                        && Math.hypot(scaled.forward(), scaled.side())
                                <= com.klze.colossus.env.TelegraphZone.MAX_AHEAD_OFFSET + 1e-9
                        && Math.abs(scaled.side() / scaled.forward() - 100.0 / 4096.0) < 1e-9);
        // 一次性回执本身要有判据（轮 18 P2-3：删掉那两个 ifPresent 今天全绿）
        com.klze.colossus.env.OncePerKey.reset();
        boolean latchHolds = com.klze.colossus.env.OncePerKey.firstTime("k1")
                && !com.klze.colossus.env.OncePerKey.firstTime("k1")
                && com.klze.colossus.env.OncePerKey.firstTime("k2");
        for (int i = 0; i < 500; i++) com.klze.colossus.env.OncePerKey.firstTime("flood" + i);
        check("the notice latch fires once per key and stays bounded under a flood of distinct keys",
                latchHolds && com.klze.colossus.env.OncePerKey.size() <= 64);
        // 真 LRU 而不是插入序（轮 19 P3-3）：反复命中的热键被灌入新键冲刷后<b>不该</b>再响一次。
        // 上一版用默认 LinkedHashMap（插入序）时这条会红——热键的位置从不更新，必然被滚出窗口。
        com.klze.colossus.env.OncePerKey.reset();
        com.klze.colossus.env.OncePerKey.firstTime("hot");
        for (int i = 0; i < 63; i++) com.klze.colossus.env.OncePerKey.firstTime("fill" + i);
        for (int round = 0; round < 3; round++) {
            com.klze.colossus.env.OncePerKey.firstTime("hot"); // 命中即刷新访问序
            for (int i = 0; i < 40; i++) com.klze.colossus.env.OncePerKey.firstTime("flood" + round + "_" + i);
        }
        check("a repeatedly-hit notice key survives the flood window instead of being evicted by insertion order",
                !com.klze.colossus.env.OncePerKey.firstTime("hot"));
        com.klze.colossus.env.OncePerKey.reset();
        // view tag 缺件必须整条丢，而不是读成"世界原点一个 1 格圈"（轮 18 P3-3：
        // CompoundTag 的 getDouble/getString 对缺失返回 0/""、不抛）
        var partial = new net.minecraft.nbt.CompoundTag();
        partial.putInt("id", 3);
        partial.putLong("start", 100L);
        partial.putLong("end", 140L);
        partial.putDouble("rXZ", 4.0); // 只有半径，其余缺
        // 闸门不许比读侧更严（轮 19 P3-4）：CompoundTag 的三个 getter 走 mask=99，
        // /data modify 给的 IntTag 与 putFloat 的 FloatTag 本来都读得出来；用精确类型会把它们判成缺件。
        var floatTyped = new net.minecraft.nbt.CompoundTag();
        floatTyped.putFloat("cx", 1f); floatTyped.putFloat("cy", 2f); floatTyped.putFloat("cz", 3f);
        floatTyped.putFloat("rXZ", 4f); floatTyped.putFloat("rY", 1f);
        floatTyped.putInt("warn", 20); floatTyped.putInt("color", 0);
        floatTyped.putString("visual", "dust");
        var intTypedView = new net.minecraft.nbt.CompoundTag();
        intTypedView.put("zone", floatTyped.copy());
        intTypedView.put("burst", new net.minecraft.nbt.CompoundTag()); // 空 burst 也要齐件：三个键都给
        net.minecraft.nbt.CompoundTag burstKeys = new net.minecraft.nbt.CompoundTag();
        burstKeys.putFloat("damage", 3f); burstKeys.putFloat("knockback", 0f); burstKeys.putInt("freeze", 0);
        intTypedView.put("burst", burstKeys);
        check("a numeric tag stored as Float/Int still counts as complete (the gate must not be stricter than the getters)",
                com.klze.colossus.env.TelegraphZone.hasRequiredKeys(floatTyped)
                        && com.klze.colossus.env.ZoneWork.settleRejectReason(intTypedView) == null);
        check("a view tag missing geometry keys is rejected outright instead of reading as a 1-block ring at origin",
                !com.klze.colossus.env.TelegraphZone.hasRequiredKeys(partial)
                        && com.klze.colossus.env.TelegraphZone.hasRequiredKeys(
                                new com.klze.colossus.env.TelegraphZone(1, 2, 3, 4, 5, 6, 7, "ring").toTag()));
        // 要紧的那一半：**结算**走的是 ZoneWork 的载荷，不是投影。上一批我只补了投影侧，
        // 于是"看不见的圈照样落伤"这个洞在原处还开着（轮 18 P3-3）。判据抽成纯函数后才能在这里跑红。
        var okPayload = com.klze.colossus.env.ZoneWork.encode(
                new com.klze.colossus.env.TelegraphZone(1, 2, 3, 4, 1, 20, 0, "dust"),
                new com.klze.colossus.env.ZoneBurst(3.0f, 0.0f, 0));
        var noZone = new net.minecraft.nbt.CompoundTag();
        noZone.put("burst", new com.klze.colossus.env.ZoneBurst(3.0f, 0.0f, 0).toTag());
        var noBurst = new net.minecraft.nbt.CompoundTag();
        noBurst.put("zone", okPayload.getCompound("zone").copy());
        var halfZone = new net.minecraft.nbt.CompoundTag();
        var stripped = okPayload.getCompound("zone").copy();
        stripped.remove("cz"); // 截断的存档最常见形态：少一个键，读回来是 0 而不是异常
        halfZone.put("zone", stripped);
        halfZone.put("burst", okPayload.getCompound("burst").copy());
        check("settlement refuses a payload whose zone tag is incomplete (and accepts a full one)",
                com.klze.colossus.env.ZoneWork.settleRejectReason(okPayload) == null
                        && "no zone payload".equals(com.klze.colossus.env.ZoneWork.settleRejectReason(noZone))
                        && "no burst payload".equals(com.klze.colossus.env.ZoneWork.settleRejectReason(noBurst))
                        && "incomplete zone tag".equals(
                                com.klze.colossus.env.ZoneWork.settleRejectReason(halfZone)));

        var burst = new com.klze.colossus.env.ZoneBurst(6.0f, 0.5f, 40)
                .merge(new com.klze.colossus.env.ZoneBurst(0.0f, 0.0f, 0));
        var bb = com.klze.colossus.env.ZoneBurst.fromTag(burst.toTag());
        check("burst tag round-trips damage/knockback/freeze",
                Math.abs(bb.damage() - 6.0f) < 1e-6 && Math.abs(bb.knockback() - 0.5f) < 1e-6
                        && bb.freezeTicks() == 40);
        check("merge takes the per-field max (damage+freeze compose, nothing cancels)",
                new com.klze.colossus.env.ZoneBurst(3.0f, 0.0f, 0)
                        .merge(new com.klze.colossus.env.ZoneBurst(0.0f, 0.9f, 20)).damage() == 3.0f
                        && new com.klze.colossus.env.ZoneBurst(3.0f, 0.0f, 0)
                        .merge(new com.klze.colossus.env.ZoneBurst(0.0f, 0.9f, 20)).freezeTicks() == 20);

        // 这条只证 DFU 的缺键回落（getCompound 返回新空标签、不抛）；
        // ZoneWork.execute 的两道弃单门（未知种类 / 残缺载荷）由 GameTest deferred-work 覆盖
        var broken = new net.minecraft.nbt.CompoundTag();
        broken.put("zone", zone.toTag());
        check("missing burst tag reads as an empty burst (no throw)",
                com.klze.colossus.env.ZoneBurst.fromTag(broken.getCompound("burst")).empty());
    }

    private static void testDirtyMeter() {
        com.klze.colossus.bar.DirtyMeter m = new com.klze.colossus.bar.DirtyMeter();
        int sends = 0;
        for (int i = 0; i < 600; i++) {
            if (m.changedAndRemember(0.5f)) sends++;
        }
        check("steady value costs 0 packets over 600 ticks", sends == 1); // 首帧必发一次，之后静默
        check("real change still sends", m.changedAndRemember(0.25f));
        check("NaN never sends and does not poison the memo", !m.changedAndRemember(Float.NaN));
        check("NaN does not swallow the next legitimate change", m.changedAndRemember(0.9f));

        m.invalidate();
        check("invalidate forces exactly one snapshot (join / become-visible)",
                m.changedAndRemember(0.9f) && !m.changedAndRemember(0.9f));

        // 第二十四批（ColossusBossEvent 补包路径的修法）：旧代码是 invalidate() 之后立刻读
        // lastSent() 当包体——那是"从未发过"的哨兵 NaN，而客户端 ShieldBars.set 把非有限值
        // 视作"这条 bar 没有盾"直接删项 ⇒ 注释承诺的"晚入场也拿到当前真值"实际拿到空值。
        m.invalidate();
        check("right after invalidate the memo IS the unset sentinel (so it must never be the packet payload)",
                Float.isNaN(m.lastSent()));
        m.remember(0.75f);
        check("remember() books the value actually pushed ⇒ the same value next tick costs 0 packets",
                !m.changedAndRemember(0.75f));
        m.remember(Float.NaN);
        check("remember() refuses non-finite values instead of muting the memo",
                !m.changedAndRemember(0.75f) && m.changedAndRemember(0.4f));
    }

    private static void testFrameRepeatingFiresEveryPeriod() {
        List<Integer> hit = new ArrayList<>();
        List<Integer> single = new ArrayList<>();
        FrameRunner<Object> fr = FrameRunner.builder()
                .repeating(4, 10, 3, (c, t) -> hit.add(t))
                .between(4, 10, (c, t) -> single.add(t))  // 同窗口的普通帧：应只响一次
                .build();
        for (int t = 1; t <= 14; t++) fr.advance(null, t);
        check("repeating frame fires at from, from+period, ... within window",
                hit.equals(List.of(4, 7, 10)));
        check("repeating coexists with one-shot frame in the same window",
                single.equals(List.of(4)));
    }

    /** 持续帧的 lag 语义：跳过的节拍不补触发（与窗口帧同一doctrine——宁可漏也不在错拍打伤害）。 */
    private static void testFrameRepeatingSkipsBeatsWithoutCompensation() {
        List<Integer> hit = new ArrayList<>();
        FrameRunner<Object> fr = FrameRunner.builder()
                .repeating(4, 12, 3, (c, t) -> hit.add(t))   // 节拍=4,7,10
                .build();
        fr.advance(null, 5);   // 迟到入场：4 那拍已过
        fr.advance(null, 9);   // 非节拍
        fr.advance(null, 10);  // 节拍
        fr.advance(null, 13);  // 出窗
        check("late entry drops the missed beat, later beats still fire",
                hit.equals(List.of(10)));

        boolean threw = false;
        try {
            FrameRunner.builder().repeating(2, 6, 0, (c, t) -> { });
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("repeating frame rejects period < 1 at registration", threw);
    }

    private StateSelfTest() {}
}
