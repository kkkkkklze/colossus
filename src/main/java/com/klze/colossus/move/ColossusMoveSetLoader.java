package com.klze.colossus.move;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.colossus.Colossus;
import com.klze.colossus.move.data.MoveCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * datapack 招式表加载器：{@code data/<ns>/colossus/moves/<boss 路径>.json}。
 *
 * <p>文件形态定死为<b>"一个文件一张表，记录自带 key"</b>（Tetra {@code DataManager.java:173} 的
 * {@code actions} 目录同形）：1.20.1 的 {@code SimpleJsonResourceReloadListener}
 * 在同 id 重复时抛 {@code IllegalStateException("Duplicate data file ignored with ID")}
 * 并中止整次扫描（{@code :44} 实测），所以绝不做"一条招式一个文件"。
 *
 * <p>寻址只有一条规则：<b>文件在 {@code colossus/moves/} 下的路径就是 Boss 种类 id</b>——
 * {@code data/<任意包>/colossus/moves/<bossNs>/<bossPath>.json} → Boss {@code <bossNs>:<bossPath>}。
 * 刻意<b>不</b>用"包命名空间当 Boss 命名空间"：那样别的包就只能改自己命名空间下的 Boss，
 * 而整合包作者改的恰恰是别人的 Boss。同 Boss 被多个包各写一份时后写者覆盖并打日志（不静默）。
 *
 * <p>加载事务（ViScriptRecipe 的三件套，去 Mixin 化）：
 * ①全程只写<b>副本</b> {@code next}，不碰线上表；
 * ②每条记录独立 try/catch，失败只丢这一条并把字段级错误收进回执；
 * ③整批完成后经 {@link MoveDataRegistry#publish} 一次性原子换表 + 代际号 +1；
 * ④回执必须<b>把跳过谁都打出来</b>——静默跳过会被内容作者误认为已生效。
 */
public final class ColossusMoveSetLoader extends SimpleJsonResourceReloadListener {

    public static final String FOLDER = "colossus/moves";

    /** 单文件最多多少条记录（防一个 10 万记录的"好事"文件把主线程钉住）。 */
    private static final int MAX_RECORDS_PER_FILE = 512;

    public ColossusMoveSetLoader() {
        super(new Gson(), FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, java.util.LinkedHashMap<ResourceLocation, MoveDef>> next = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int records = 0;
        int applied = 0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation file = entry.getKey();
            if (!(entry.getValue() instanceof JsonObject root)) {
                errors.add(file + " 顶层不是对象");
                continue;
            }
            String rel = file.getPath();               // "<bossNs>/<bossPath>"
            int slash = rel.lastIndexOf('/');
            if (slash <= 0 || slash == rel.length() - 1) {
                errors.add(file + " 命名不符：colossus/moves/<boss 命名空间>/<boss path>.json");
                continue;
            }
            ResourceLocation bossId = ResourceLocation.tryParse(
                    rel.substring(0, slash) + ":" + rel.substring(slash + 1));
            if (bossId == null) {
                errors.add(file + " 的路径不构成合法 Boss id：" + rel);
                continue;
            }
            if (!(root.get("moves") instanceof JsonArray moves)) {
                errors.add(file + " 缺 moves 数组");
                continue;
            }
            if (moves.size() > MAX_RECORDS_PER_FILE) {
                errors.add(file + " 记录数 " + moves.size() + " 超单文件上限 " + MAX_RECORDS_PER_FILE + "，整文件跳过");
                continue;
            }
            for (JsonElement row : moves) {
                records++;
                if (!(row instanceof JsonObject mo)) {
                    errors.add(file + " 有一条记录不是对象");
                    continue;
                }
                // 判 isJsonPrimitive 再取值：getAsString() 在对象/数组上抛 UnsupportedOperationException，
                // 而它在记录级 try 之前抛＝整次 reload 被打掉（轮6 P2-1，作者写 "id": {} 就能触发）
                String key = mo.has("id") && mo.get("id").isJsonPrimitive()
                        ? mo.get("id").getAsString() : null;
                if (key == null || key.isEmpty()) {
                    errors.add(file + " 有记录缺 id");
                    continue;
                }
                try {
                    MoveDef def = MoveCodec.decodeMove(key, bossId, mo);
                    MoveDef prior = next.computeIfAbsent(bossId, k -> new java.util.LinkedHashMap<>())
                            .put(def.id(), def);
                    if (prior != null) {
                        errors.add(file + " / " + key + " 与更早的包重复定义同一招，后者生效（前一份被覆盖）");
                    }
                    applied++;
                } catch (MoveCodec.MoveDataException e) {
                    errors.add(file + " / " + key + " -> " + e.getMessage());
                } catch (RuntimeException e) {
                    errors.add(file + " / " + key + " -> 解析炸了：" + e);
                }
            }
        }

        Map<ResourceLocation, List<MoveDef>> publishable = new LinkedHashMap<>();
        next.forEach((bossId, byId) -> publishable.put(bossId, new ArrayList<>(byId.values())));
        MoveDataRegistry.publish(publishable,
                new MoveDataRegistry.Report(files.size(), records, applied, errors));
        for (String err : errors) {
            Colossus.LOGGER.error("colossus/moves: skipped a move record — {}", err);
        }
        Colossus.LOGGER.info("colossus/moves: files={} records={} applied={} skipped={}",
                files.size(), records, applied, records - applied);
    }

    /** 挂进原版数据包重载（服务端）。 */
    @Mod.EventBusSubscriber(modid = Colossus.MODID)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onAddReloadListeners(AddReloadListenerEvent event) {
            event.addListener(new ColossusMoveSetLoader());
        }
    }
}
