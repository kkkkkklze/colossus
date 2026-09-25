package com.klze.colossus.network;

import com.klze.colossus.Colossus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 框架网络层（Forge 1.20.1 SimpleChannel，可选通道）。
 *
 * <p>铁律（六样本一致验证）：战斗状态本身零自定义包——全部走 SynchedEntityData；
 * 这里只有六条旁路：血条样式、护盾镜像、客户端演出事件、音乐开关、通用 cue、全局进度快照
 * （第二十四批删掉了第七条 {@code ZoneSync}：危险区轮廓改住 {@code DATA_TELEGRAPHS}，
 *  由 vanilla 给新追踪者自动补包，见 {@code ColossusBossEntity#showTelegraph}）。
 */
public final class ColossusPackets {

    /**
     * 协议号（第二十四批 1→2）：消息表整条删除会让 Forge 的 discriminator <b>下标</b>整体前移，
     * 老客户端把后来的包按旧表解码＝静默错位。版本号不匹配会在握手期就明确断开
     * （{@code acceptMissingOr} 仍放行"根本没装 mod"的对端，原版服照进），
     * 比在局中解出一坨垃圾好。
     */
    private static final String PROTOCOL = "2";
    /**
     * 可选通道（47.4.23 sources 实测）：用官方助手 {@code acceptMissingOr}——
     * 对端缺通道（ABSENT.version()）或连原版服（ACCEPTVANILLA）都放行，
     * 装了本 mod 的客户端不再被无 mod 的服务器踢掉（引擎底座不许卡进服）。
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(Colossus.res("main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
            .simpleChannel();

    private ColossusPackets() {}

    private static int index = 0;
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return; // 幂等（审查 P2#20：index 静态累加，二跑会重注册同批消息）
        initialized = true;
        CHANNEL.registerMessage(index++, BarStyleS2C.class,
                BarStyleS2C::encode, BarStyleS2C::decode, BarStyleS2C::handle);
        CHANNEL.registerMessage(index++, BossVisualEventS2C.class,
                BossVisualEventS2C::encode, BossVisualEventS2C::decode, BossVisualEventS2C::handle);
        CHANNEL.registerMessage(index++, MusicToggleS2C.class,
                MusicToggleS2C::encode, MusicToggleS2C::decode, MusicToggleS2C::handle);
        CHANNEL.registerMessage(index++, CueS2C.class,
                CueS2C::encode, CueS2C::decode, CueS2C::handle);
        CHANNEL.registerMessage(index++, BarShieldS2C.class,
                BarShieldS2C::encode, BarShieldS2C::decode, BarShieldS2C::handle);
        CHANNEL.registerMessage(index++, ProgressSnapshotS2C.class,
                ProgressSnapshotS2C::encode, ProgressSnapshotS2C::decode, ProgressSnapshotS2C::handle);
    }

    // ---------------- 附加资源条（护盾）旁路 ----------------

    /**
     * 护盾进度包。原版 {@code ClientboundBossEventPacket.Operation} 是 package-private
     * （{@code ClientboundBossEventPacket:162}）且 OperationType 硬编 6 个常量，
     * <b>没法扩</b>——所以"血条旁路另开一条通道"是唯一正解（DE 同款裁决）。
     */
    public record BarShieldS2C(UUID barId, float value) {
        static void encode(BarShieldS2C msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.barId);
            buf.writeFloat(msg.value);
        }
        static BarShieldS2C decode(FriendlyByteBuf buf) {
            return new BarShieldS2C(buf.readUUID(), buf.readFloat());
        }
        static void handle(BarShieldS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    com.klze.colossus.client.ShieldBars.set(msg.barId(), msg.value()));
            ctx.get().setPacketHandled(true);
        }
    }

    public static BarShieldS2C barShield(UUID barId, float value) {
        return new BarShieldS2C(barId, value);
    }

    // ---------------- 血条样式旁路 ----------------

    public record BarStyleS2C(UUID barId, int renderType) {
        static void encode(BarStyleS2C msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.barId);
            buf.writeVarInt(msg.renderType);
        }
        static BarStyleS2C decode(FriendlyByteBuf buf) {
            return new BarStyleS2C(buf.readUUID(), buf.readVarInt());
        }
        static void handle(BarStyleS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.klze.colossus.client.BossBarStyles.apply(msg.barId(), msg.renderType()));
            ctx.get().setPacketHandled(true);
        }
    }

    public static BarStyleS2C barStyle(UUID barId, int renderType) {
        return new BarStyleS2C(barId, renderType);
    }

    // ---------------- 类型化客户端演出事件 ----------------

    public record BossVisualEventS2C(int entityId, String eventId) {
        static void encode(BossVisualEventS2C msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeUtf(msg.eventId);
        }
        static BossVisualEventS2C decode(FriendlyByteBuf buf) {
            return new BossVisualEventS2C(buf.readVarInt(), buf.readUtf());
        }
        static void handle(BossVisualEventS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) return;
                Entity e = mc.level.getEntity(msg.entityId());
                if (e instanceof com.klze.colossus.entity.ColossusBossEntity boss) {
                    boss.onBossVisualEvent(msg.eventId());
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static BossVisualEventS2C visualEvent(int entityId, String eventId) {
        return new BossVisualEventS2C(entityId, eventId);
    }

    // ---------------- 音乐开关（服务端幂等布尔 + 客户端单例播放器） ----------------

    public record MusicToggleS2C(String musicId, boolean on) {
        static void encode(MusicToggleS2C msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.musicId);
            buf.writeBoolean(msg.on);
        }
        static MusicToggleS2C decode(FriendlyByteBuf buf) {
            return new MusicToggleS2C(buf.readUtf(), buf.readBoolean());
        }
        static void handle(MusicToggleS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.klze.colossus.client.BossMusicClient.apply(msg.musicId(), msg.on()));
            ctx.get().setPacketHandled(true);
        }
    }

    public static MusicToggleS2C musicToggle(String musicId, boolean on) {
        return new MusicToggleS2C(musicId, on);
    }

    // ---------------- 通用演出 cue（单包承载所有效果类型，ES VfxPacket 形） ----------------

    public record CueS2C(com.klze.colossus.fx.CueType.Type<Object> type,
                         net.minecraft.world.phys.Vec3 pos, Object data) {
        static void encode(CueS2C m, FriendlyByteBuf buf) {
            buf.writeResourceLocation(m.type.id());
            buf.writeDouble(m.pos.x); buf.writeDouble(m.pos.y); buf.writeDouble(m.pos.z);
            m.type.encode(m.data, buf);
        }

        @SuppressWarnings("unchecked")
        static CueS2C decode(FriendlyByteBuf buf) {
            ResourceLocation id = buf.readResourceLocation();
            net.minecraft.world.phys.Vec3 pos =
                    new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            com.klze.colossus.fx.CueType.Type<?> type = com.klze.colossus.fx.CueType.get(id);
            if (type == null) return new CueS2C(null, pos, null); // 客户端缺类型：静默丢
            return new CueS2C((com.klze.colossus.fx.CueType.Type<Object>) type, pos, type.decode(buf));
        }

        @SuppressWarnings("unchecked")
        static void handle(CueS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                if (msg.type() == null) return;
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) return;
                ((com.klze.colossus.fx.CueType.Player<Object>) msg.type().player())
                        .play(mc.level, msg.pos(), msg.data());
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** 向追踪 {@code source} 的所有客户端广播一个带参 cue。 */
    @SuppressWarnings("unchecked")
    public static <D> void sendCue(Entity source, com.klze.colossus.fx.CueType.Type<D> type,
                                   net.minecraft.world.phys.Vec3 pos, D data) {
        sendToTrackers(new CueS2C((com.klze.colossus.fx.CueType.Type<Object>) type, pos, data), source);
    }

    // ---------------- 发送助手 ----------------

    public static void sendToPlayer(Object msg, ServerPlayer player) {
        CHANNEL.sendTo(msg, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    /** 发给所有正在追踪该实体的玩家（演出事件专用）。1.20.1 形态：send(consumer, msg)。 */
    public static <M> void sendToTrackers(M msg, Entity tracked) {
        CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> tracked), msg);
    }

    public static void sendToPlayers(java.util.Collection<ServerPlayer> players, Object msg) {
        for (ServerPlayer p : players) sendToPlayer(msg, p);
    }

    // ---------------- 全局进度快照（第十三批） ----------------

    /**
     * 一包 {@link CompoundTag} 的进度快照——1.20.1 没有 StreamCodec（v9 取证实测 0 命中），
     * 所以"包体即状态"用 NBT 实现：服务端 {@code BossKillBoard#snapshot()} 产出什么，这里就发什么，
     * 两侧共用同一份 tag 形状，不留第二套序列化。
     */
    public record ProgressSnapshotS2C(CompoundTag data) {
        static void encode(ProgressSnapshotS2C msg, FriendlyByteBuf buf) {
            buf.writeNbt(msg.data);
        }
        static ProgressSnapshotS2C decode(FriendlyByteBuf buf) {
            net.minecraft.nbt.Tag t = buf.readNbt();
            return new ProgressSnapshotS2C(t instanceof CompoundTag c ? c : new CompoundTag());
        }
        static void handle(ProgressSnapshotS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.klze.colossus.progress.ClientProgress
                    .apply(com.klze.colossus.progress.ProgressLedger.decode(msg.data())));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 广播全量快照给所有在线玩家（代际号变化时才调，见 ProgressSync 的脏检查）。 */
    public static void broadcastProgress(CompoundTag snapshot) {
        CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(),
                new ProgressSnapshotS2C(snapshot));
    }

    /** 进视角补包：新玩家一进来就要拿到当前真值，不能等下一次变化。 */
    public static void sendProgressTo(CompoundTag snapshot, ServerPlayer player) {
        sendToPlayer(new ProgressSnapshotS2C(snapshot), player);
    }
}
