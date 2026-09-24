# colossus-gecko — GeckoLib 4 适配层（可选 addon）

## 为什么是另一个 jar
v9 取证（GeckoLib 官方 `1.20.1` 分支源码 + OrdertoCook/dumbcat 两样本）判死了一条路：
**实体 `implements GeoEntity` 是字节码级引用**，GL 缺失即 `NoClassDefFoundError`。
所以"动画后端可选"在核心里做不到，只能把 GL 的引用全部关进这个 addon——
`colossus-0.1.0.jar` 里 GL 引用数为 **0**（构建后可用 `unzip -l ... | grep -ci geckolib` 复验）。

## 怎么构建
addon 默认**不参与构建**，加属性才建：

```bash
./gradlew -Pgecko jarColossusGecko     # → build/libs/colossus-gecko-<ver>.jar
./gradlew -Pgecko geckoProbe           # 只做网络解析探针（拉 geckolib 工件）
./gradlew build colossusSelfTest gameTestAudit runGameTestServer   # 主构建与它无关
```

依赖版本钉在 `gradle.properties` 的 `geckolib_version`（现 4.8.3，MC 1.20.1 / Forge 47.x）。
仓库 `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/`——本机实测可达并解析成功。

## 下游怎么用（三步）
1. `class MyBoss extends GeoColossusEntity`，实现 `idleAnim()/walkAnim()/deathAnim()`（招式动画名走 `MoveDef.animName()`，要改名规则就覆写 `animForMove`）。
2. 注册 `EntityType` + 属性 + 渲染器（照抄本目录的 `ColossusGecko`/`ExampleGeoRenderer`）。
3. 放资产：`data/<addon>/geo/<name>.geo.json`、`animations/<name>.animation.json`、`textures/entity/<name>.png`，
   动画名要与 `MoveDef.animName()` 对得上（GL4 的 `triggerableAnim` 也可以，但本适配层不依赖它）。

## 适配层的设计裁决（都有取证出处）
- **触发档＝读同步实体数据**，不引 GL 自带的 `geckolib:main` 通道（15 个 packet 全 S→C，无 C→S）。
- 判据用 **`attackSequence()`** 而不是招式 index：连放同一招时 index 不变，
  只有序号递增（OrdertoCook 的 `ACTION_STATE`、dumbcat 的 `HURT_SEQ` 同形——"同值不广播"）。
  序号变化时 `forceAnimationReset()` 再起播，否则第二下会续播上一轮剩余帧。
- `RawAnimation` 按名字缓存（GL 文档明示该缓存，predicate 每帧现 new 是无谓 GC）。
- **永不**用 `hasAnimationFinished()` 反向决定判定：GL4 没有服务端动画时钟，也没有"播完"事件，
  帧表时长（`MoveDef.duration()`）才是权威时间线。
- 渲染注册面只有 `EntityRenderersEvent.RegisterRenderers`——1.20.1 **没有** `RegisterModelLayerEvent`
  与 `GeoModelRegistry.registerModel`（那是 GL5 形态）。模型在 renderer 构造里 `new`。
- 必覆写的只有 `registerControllers / getAnimatableInstanceCache / getTick`
  （`registerAnimations`、`isAnimatable` 在 1.20.1 不存在）。`PlayState` 只有 `CONTINUE/STOP`。

## 已验证 / 未验证（别混着说）
- ✅ 已验：addon 对 GL4 4.8.3 的 API 用法**编译通过**（`-Pgecko compileGeckoJava`），
  addon jar 产出且自带 `mods.toml`（依赖 `colossus` + `geckolib` mandatory）；
  核心 jar 零 GL 引用；主构建四道门（build / 39 自检 / audit / GameTest 7 条）不受影响。
- ❌ 未验：**运行期表现**。本仓库没有 geo/animation/texture 资产，且本机验证口径是 headless
  （客户端不可操作），所以"动画真的播起来了/多人下会不会漏重启"这类问题留待有真客户端时验收。
- ⚠ 风险点（第一批实机必查）：ModDevGradle legacy 下 GL4 jar 的 remap（其内部 SRG/official 混排）
  与 `runClient` classpath 是否带得上 GL；核心自身的 `runGameTestServer` 与 addon 无关。
- 备选路线：`GeoReplacedEntity`（实体不实现任何 GL 接口，animatable 只被客户端 renderer 持有）。
  它在 1.20.1 **只有官方 example 一处参照**，且是"一类型一实例"的单例形，
  多头 Boss 要靠 `getManagerForId(entity.getId())` 自己隔离——本 addon 没走这条路。
