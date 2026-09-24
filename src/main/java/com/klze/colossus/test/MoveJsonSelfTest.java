package com.klze.colossus.test;

import com.google.gson.JsonObject;
import com.klze.colossus.move.MoveDef;
import com.klze.colossus.move.data.MoveCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * datapack 招式表解码的纯逻辑桩（第十四批 + 轮 6 加固）。
 *
 * <p>独立成类而不是塞进 {@link StateSelfTest}：这些用例要写大量 JSON 文本，混在一起时
 * Java 字面量里得叠三层引号转义——首版就是这么坏的（转义一错，编译期只看到"未结束的字符串文字"）。
 * 这里统一<b>用单引号写 JSON、进解码器前换成双引号</b>，源码里一眼读得出对象形状。
 *
 * <p>覆盖目标是"<b>坏数据必须带字段名被拒</b>"：loader 的回执全靠这一条；拒得不明，
 * 内容作者看到的就只有"这招没生效"。准入谓词（{@code available()} 会读 {@code ctx.phase()} → 要活 Boss）
 * 不在这里测，留给 GameTest。
 */
final class MoveJsonSelfTest {

    private static final ResourceLocation NS = new ResourceLocation("colossus", "example");

    private MoveJsonSelfTest() {}

    static void run(BiConsumer<String, Boolean> check) {
        // ---------- 正常路径 ----------
        MoveDef def = decodeOk("q", "{ 'id':'q', 'duration':40, 'cooldown':90, 'post_invuln':6, 'range':7.5,"
                + " 'frames':[{'at':2,'trigger':{'type':'colossus:event','id':'e'}}] }");
        check.accept("json move keeps id/duration/cooldown/range",
                def.id().equals(new ResourceLocation("colossus", "q"))
                        && def.duration() == 40 && def.cooldownTicks() == 90
                        && def.postAttackInvuln() == 6 && def.range() == 7.5f);

        MoveDef framed = decodeOk("f", "{ 'id':'f', 'duration':40, 'frames':["
                + "{'at':4,'trigger':{'type':'colossus:event','id':'e'}},"
                + "{'between':[20,24],'trigger':{'type':'colossus:circle_hit','radius':6.0,'damage':5.0}},"
                + "{'repeating':[24,36,6],'trigger':{'type':'colossus:circle_hit','radius':6.0,'damage':1.0}}]}");
        check.accept("at/between/repeating all survive decoding",
                framed.frames().size() == 3
                        && framed.frames().get(0).from() == 4 && framed.frames().get(0).to() == 4
                        && !framed.frames().get(0).repeating()
                        && framed.frames().get(1).from() == 20 && framed.frames().get(1).to() == 24
                        && !framed.frames().get(1).repeating()
                        && framed.frames().get(2).repeating() && framed.frames().get(2).period() == 6
                        && framed.frames().get(2).to() == 36);

        MoveDef weighty = decodeOk("w", "{ 'id':'w', 'duration':40,"
                + " 'weight':[{'kind':'base','base':3},{'kind':'distance_band','min':0.0,'max':6.0,'add':10}],"
                + " 'frames':[{'at':2,'trigger':{'type':'colossus:event','id':'e'}}]}");
        check.accept("json weight entries sum additively",
                weighty.weight(new com.klze.colossus.move.AttackContext(null, null, 25.0)) == 13
                        && weighty.weight(new com.klze.colossus.move.AttackContext(null, null, 100.0)) == 3);

        MoveDef composed = decodeOk("c", "{ 'id':'c', 'duration':30, 'frames':[{'at':6,'trigger':"
                + "{'type':'colossus:telegraph','zone':{'kind':'circle_ahead','radius':4.0,'forward':3.0,'warn':10},"
                + "'effect':[{'kind':'damage','damage':2.0},{'kind':'freeze','ticks':20}]}}]}");
        check.accept("telegraph zone with an effect array decodes", composed.frames().size() == 1);

        // ---------- 坏数据：每条都要带字段名 ----------
        check.accept("bad duration is rejected with its field name",
                rejects("{ 'id':'b', 'duration':0, 'frames':[] }", "duration"));
        check.accept("unknown trigger type is rejected and names what exists",
                rejects("{ 'id':'b', 'duration':10, 'frames':[{'at':2,'trigger':{'type':'colossus:nope'}}] }",
                        "unknown trigger"));
        check.accept("frame later than duration is rejected at decode time",
                rejects("{ 'id':'b', 'duration':10, 'frames':[{'at':50,'trigger':{'type':'colossus:event','id':'x'}}] }",
                        "exceeds duration"));
        check.accept("between with from<1 is rejected (same rule as the Java DSL)",
                rejects("{ 'id':'b', 'duration':10, 'frames':[{'between':[0,5],"
                        + "'trigger':{'type':'colossus:event','id':'x'}}] }", "frames start at 1"));
        check.accept("inverted between window is rejected",
                rejects("{ 'id':'b', 'duration':10, 'frames':[{'between':[9,2],"
                        + "'trigger':{'type':'colossus:event','id':'x'}}] }", "max < min"));
        // 招式 key 由 loader 传入（JSON 里的 id 字段只是它的镜像），所以坏 key 要当参数给
        check.accept("illegal move id characters give a field-level error, not a raw exception",
                rejectsAs("Bad ID", "{ 'duration':10, 'frames':[{'at':2,"
                        + "'trigger':{'type':'colossus:event','id':'x'}}] }", "不是合法的招式 id"));
        check.accept("missing required trigger field is rejected with the field name",
                rejects("{ 'id':'b', 'duration':10, 'frames':[{'at':2,"
                        + "'trigger':{'type':'colossus:circle_hit','damage':5.0}}] }", "radius"));
        check.accept("unknown zone kind is rejected by name",
                rejects("{ 'id':'b', 'duration':10, 'frames':[{'at':2,'trigger':{'type':'colossus:telegraph',"
                        + "'zone':{'kind':'triangle'},'effect':{'kind':'damage','damage':1.0}}}] }",
                        "unknown zone kind"));
        check.accept("per-move frame count is capped", rejects(framesOf(70), "frames"));
        check.accept("nested once beyond depth cap is rejected", rejects(nestedOnce(12), "嵌套"));
    }

    // ==================== 小工具 ====================

    /** 单引号写 JSON，进解码器前统一换双引号（双引号在这里只以字面量出现一次）。 */
    private static JsonObject obj(String squotedJson) {
        char dq = (char) 34;
        char sq = (char) 39;
        return com.google.gson.JsonParser.parseString(squotedJson.replace(sq, dq)).getAsJsonObject();
    }

    private static MoveDef decodeOk(String key, String squotedJson) {
        try {
            return MoveCodec.decodeMove(key, NS, obj(squotedJson));
        } catch (Exception e) {
            throw new IllegalStateException("这条应当能解码：" + e.getMessage(), e);
        }
    }

    private static boolean rejects(String squotedJson, String needleInMessage) {
        return rejectsAs("probe", squotedJson, needleInMessage);
    }

    private static boolean rejectsAs(String key, String squotedJson, String needleInMessage) {
        try {
            MoveCodec.decodeMove(key, NS, obj(squotedJson));
            return false;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            return msg.contains(needleInMessage.toLowerCase(Locale.ROOT));
        }
    }

    private static String framesOf(int n) {
        StringBuilder sb = new StringBuilder("{ 'id':'b', 'duration':9999, 'frames':[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append("{'at':").append(i).append(",'trigger':{'type':'colossus:event','id':'e'}}");
        }
        return sb.append("]}").toString();
    }

    /** 套 n 层 once（轮 6 P3-1 深度门的判据：SOE 不是 RuntimeException，loader 抓不住）。 */
    private static String nestedOnce(int depth) {
        StringBuilder sb = new StringBuilder("{ 'id':'b', 'duration':10, 'frames':[{'at':2,'trigger':");
        for (int i = 0; i < depth; i++) {
            sb.append("{'type':'colossus:once','tag':'t").append(i).append("','then':");
        }
        sb.append("{'type':'colossus:event','id':'leaf'}");
        for (int i = 0; i <= depth; i++) sb.append('}'); // depth 层 once + 那个 frames 元素本身
        return sb.append("]}").toString();
    }
}
