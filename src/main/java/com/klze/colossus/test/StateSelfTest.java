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
        testContactBook();
        testTableSamplerInterpolation();
        testPartRig();
        testPartStatesBits();
        testRespawnSchedule();
        testDirtyMeter();
        testProgressLedger();
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

    /** 持续帧（第十批）：窗口内每 period 复触发，出窗即止。 */
    /** 护盾脏检查（第十一批）：钉住"同值不发包 / NaN 不污染 / 补账口子只有一处"三条不变量。 */
    /** 全局进度线上形态（第十三批）：编解码对称 + 硬上限 + 畸形跳过 + 代际号脏检查。 */
    private static void testProgressLedger() {
        java.util.Map<String, Integer> kills = new java.util.LinkedHashMap<>();
        kills.put("colossus:a", 3);
        kills.put("colossus:b", 1);
        java.util.Set<String> defeated = new java.util.LinkedHashSet<>(java.util.List.of("colossus:a"));
        var tag = com.klze.colossus.progress.ProgressLedger.encode(42L, kills, defeated);
        var back = com.klze.colossus.progress.ProgressLedger.decode(tag);
        check("progress snapshot round-trips revision and counts",
                back.revision() == 42L && back.killCount("colossus:a") == 3
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
                com.klze.colossus.progress.ProgressLedger.encode(7L, flood, java.util.Set.of()));
        check("decode-side cap holds against an oversized snapshot",
                capped.kills().size() <= com.klze.colossus.progress.ProgressLedger.MAX_ENTRIES
                        && capped.kills().size() > 0);

        check("dirty check: same revision sends nothing, changed sends once",
                !com.klze.colossus.progress.ProgressLedger.shouldSend(9L, 9L)
                        && com.klze.colossus.progress.ProgressLedger.shouldSend(-1L, 9L));
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
