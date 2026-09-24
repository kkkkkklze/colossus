package com.klze.colossus;

import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.progress.BossDefinition;
import com.klze.colossus.summon.ColossusSummonItem;
import com.klze.colossus.testboss.ExampleColossus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 框架自身的注册中心。示范 Boss 在这里挂生成蛋与召唤物——
 * 下游工程照抄本文件形状即可（这是"注册三件套"的活模板）。
 */
@Mod.EventBusSubscriber(modid = Colossus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ColossusRegistries {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Colossus.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Colossus.MODID);

    public static final RegistryObject<EntityType<ExampleColossus>> EXAMPLE_COLOSSUS =
            ENTITY_TYPES.register("example_colossus", () -> EntityType.Builder
                    .<ExampleColossus>of(ExampleColossus::new, MobCategory.MONSTER)
                    .sized(1.4f, 2.8f)
                    .fireImmune()
                    .build("example_colossus"));

    /** 示范 squad 成员：必须正常注册（真实体才能被玩家瞄准——v5/v7 取证结论）。 */
    public static final RegistryObject<EntityType<com.klze.colossus.testboss.ExampleSentry>> EXAMPLE_SENTRY =
            ENTITY_TYPES.register("example_sentry", () -> EntityType.Builder
                    .<com.klze.colossus.testboss.ExampleSentry>of(
                            com.klze.colossus.testboss.ExampleSentry::new, MobCategory.MONSTER)
                    .sized(0.8f, 0.8f)
                    .fireImmune()
                    .build("example_sentry"));

    public static final RegistryObject<Item> EXAMPLE_COLOSSUS_SPAWN_EGG =
            ITEMS.register("example_colossus_spawn_egg",
                    () -> new ForgeSpawnEggItem(EXAMPLE_COLOSSUS, 0x7a4fb0, 0x2a1033, new Item.Properties()));

    /** 召唤物：示范 gate 链——任何条件都能塞进谓词（进度板查询、维度、时间）。 */
    public static final RegistryObject<Item> EXAMPLE_COLOSSUS_SUMMON =
            ITEMS.register("example_colossus_summon", () -> new ColossusSummonItem(
                    EXAMPLE_COLOSSUS,
                    new Item.Properties().stacksTo(1),
                    8.0,
                    player -> true,
                    Colossus.res("example")));

    /** BossDefinition 集中登记（种类 id → 实体/音乐/战利品的连接键）。 */
    @SubscribeEvent
    static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BossDefinition.register(new BossDefinition(
                    Colossus.res("example"), EXAMPLE_COLOSSUS.get(), null, null));
            Colossus.LOGGER.info("Colossus boss engine online; {} boss definition(s) registered",
                    BossDefinition.all().size());
        });
    }

    @SubscribeEvent
    static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(EXAMPLE_COLOSSUS.get(), ExampleColossus.attributes().build());
        event.put(EXAMPLE_SENTRY.get(), com.klze.colossus.testboss.ExampleSentry.attributes().build());
    }

    private ColossusRegistries() {}
}
