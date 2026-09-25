# Colossus — Forge 1.20.1 BOSS 战框架设计书 v0.1

> 目标：把"写一个 BOSS"从 15 个文件的样板活，压成 **1 个实体类 + 1 份招式表 + 1 个渲染器**。
> 研究输入：`Mod源码研究汇总/分析报告/_分析报告/深挖__BOSS引擎调研__六样本共性模式.md`
> （样本：首领崛起/unusualend、Cataclysm、Twilight Forest、Eternal Starlight、Iron's Spells、DE/FDLib/Confluence/Alex's Caves。）

## 0. 设计立场（工业框架视角）

1. **单一基类 + 组合式内核**。首领崛起踩过的坑：两版基类复制粘贴（`AbstractBossEntity`/`AbstractStateBossEntity` 466 行重复）。框架只留 `ColossusBossEntity`，能力全部是组合件（状态栈、招式表、参战表、缩放策略、血条），基类只做装配。
2. **状态名是协议，数组索引不是**。Cataclysm 的 Lionfish `indexOf(animations)` 把数组顺序固化进网络协议，增删动画即错位。帧序统一用 `ResourceLocation` 字符串名。
3. **前摇/判定/后摇是数据的推论，不是手写魔数**。所有样本的 `animationTick == 24` 魔法数不可维护。招式声明 `(动画时长, 触发帧表, 总时长)`，前摇=首触发帧前、后摇=总时长尾部，由框架计算。
4. **战斗状态零自定义包**：全部走 `SynchedEntityData`；包只负责旁路信息（血条样式、音乐、客户端事件）。这是 Cataclysm/ACBossEvent 验证过的最干净协议。
5. **动画后端可插拔**：核心不 import GeckoLib。v0.1 只提供"同步帧名 + tick"的抽象口（`getActiveAttack()` 客户端可读），GeckoLib 3 / vanilla AnimationState 各自适配。
6. **逃生口优先于完备性**：状态栈、触发器、缩放策略、选招谓词全部是接口/函数式，Boss 作者可整体替换任何一层。
7. **Cataclysm 许可为 CC BY-NC-ND**——只取思想，不搬代码；本框架原创实现。

## 1. 分层架构

```
com.klze.colossus
├── api/          —— 面向 Boss 作者的稳定门面（实体基类+builder+注册）
│   ├── ColossusBossEntity     抽象基类（生命周期装配）
│   ├── Colossus               主类 + 注册入口
│   └── ColossusRegistries     BossDefinition / 血条样式注册表
├── state/        —— 通用分层状态机（与 MC 解耦，可单测）
│   ├── State / ActiveState / StateController（栈式：push/replace/active）
│   └── ColossusStateGoal      桥进 vanilla Goal 体系（BR 的 StateGoal 模式）
├── move/         —— 招式建模
│   ├── MoveDef                id/时长/冷却/阶段门/距离门/权重函数/触发帧表
│   ├── MoveTrigger            tick→语义帧（damage/sound/event/custom）
│   ├── MoveSet + MoveSelector 过滤(阶段/冷却/距离) → 加权随机
│   └── HitboxUtil             扇形/圆形 AOE 服务端结算（atan2 vs yBodyRot）
├── phase/        —— 阶段推进
│   └── PhaseGate              一次性血量闸门（hpGate 模式），触发 PhaseChangeState
├── fight/        —— 战斗上下文
│   ├── EngagementTracker      参战名单（hurt 收集+剔除），共享击杀 credit
│   └── ScalingStrategy        人数缩放（sqrt 曲线，百分比回填，策略可换）
├── bar/          —— 血条协议
│   └── ColossusBossEvent      ServerBossEvent + renderType 旁路（ACBossEvent 模式）
├── progress/     —— 进度与持久化
│   ├── BossKillBoard          SavedData 全局击杀板（defeated/kills）
│   └── BossChallenges         玩家 PersistentData 挑战次数（未来 loot condition 挂点）
├── summon/       —— 召唤管线
│   └── ColossusSummonItem     召唤物基类（gate 谓词+同类查重+落点）
├── network/      —— Forge SimpleChannel：BarStyleS2C / BossEventS2C / MusicS2C
├── client/       —— 音乐单例播放器、渲染钩子
└── testboss/     —— 示范 Boss（2 阶段 4 招），即活文档
```

## 2. 核心契约

### 2.1 状态机（借鉴首领崛起 `state/` 包语义，原创实现）
```java
interface State<E> {
    default void onStart(E e) {}
    Result onTick(E e, ActiveState<E> self);   // CONTINUE | END
    default void onEnd(E e) {}
    default boolean isInterruptable(E e) { return true; }
}
class StateController<E> {   // 栈；栈顶为 active
    push(state) / replaceActive(state) / endAll() / active() / isIdle()
}
```
- `ActiveState` 自带 tick 计数；**转场窗口用负 timer 表达**（BR 语义：tick<0 为动画过渡，不跑触发帧）。
- `ColossusStateGoal.canUse = !controller.isIdle()`，锁 MOVE+LOOK——vanilla AI 只在"空闲"时接管，战斗中一切由状态说了算。

### 2.2 招式表（builder 声明式；JSON 化为 v0.2 目标，接口已按 Codec 友好设计）
```java
protected void registerMoves(MoveSetBuilder m) {
    m.move("colossus:example_smash")
     .duration(40).cooldown(80)
     .phase(0, 2)                                  // 阶段门 [min,max)
     .range(6.0f)                                  // 目标距离门（≤ 才可选）
     .weight(ctx -> ctx.distSq() < 25 ? 3 : 1)     // 上下文权重
     .anim("attack_smash")                         // 客户端动画名（字符串协议）
     .at(10, MoveTriggers.sound("entity.generic.explode"))
     .at(24, MoveTriggers.arcHit(6.5f, 90, 7.0f, 0.4f))   // 判定帧：扇形 AOE
     .at(24, MoveTriggers.event("smash_ring"));            // 客户端特效事件
}
```
调度：`IdleState.onTick` → 有目标 → `MoveSelector.pick(ctx)`（过滤阶段/冷却/距离 → 加权随机）→ `AttackState`。AttackState 每 tick 比对 `self.tick()` 触发帧表，**服务端单帧结算**，结束回 idle。冷却表挂在实体（`Map<String,Integer>`），随阶段可由 `MoveSelector` 修饰。

### 2.3 生命周期（基类固化）
- **接敌**：`startSeenByPlayer` → bar 可见；`EngagementTracker` hurt 收集，10 分钟 TTL 剔除死亡/超距。
- **阶段**：`checkPhaseGates()` 在 aiStep 头部跑一次性阈值（0.66/0.33 默认表可覆写）→ push `PhaseChangeState`（期间 `hurt` 返回 false 全免伤，动画帧点真正 `setPhase(n)`，同时 `resetAttacks()` 清冷却——Ignis 语义）。
- **死亡**：`hurt` 检测 hp≤0 → 钉住血量进入 `DeathState`（免伤、清 bar、停 AI），动画时长到 → `resolveDeath()`：killBoard 记录 → 全体参战者补 `PLAYER_KILLED_ENTITY` 触发 → 挑战次数 +1 → 掉落（v0.1 即时 vanilla 掉落；战利品缓冲入箱 = v0.2，TF IBossLootBuffer 方案已设计好）。
- **缩放**：`finalizeSpawn` + 每 10t 复查附近存活玩家数，`ScalingStrategy` 默认 `1+(sqrt(n)-1)*0.5`，用 `addTransientModifier` + 血量百分比回填。

### 2.4 同步契约（entityData，全 int/bool/string）
`PHASE / ATTACK_INDEX(-1=idle) / ATTACK_TICK / ACTIVATED / DEATH_TICK`；客户端渲染器读 `ATTACK_INDEX → MoveDef.anim 名 + ATTACK_TICK`，动画后端自决映射。血条样式走 `BarStyleS2C{barUUID, renderType}`。

## 3. v0.1 范围裁定

**做**：状态机+Goal 桥、招式表+触发帧+扇形/圆形判定、阶段闸门+转场免伤、死亡延迟结算、参战追踪+共享 credit、人数缩放、血条旁路协议、音乐开关、KillBoard+挑战计数、召唤物基类、示范 Boss（含最简模型渲染器与生成蛋）、完整中文 javadoc。
**不做（已知缺口，见 §5）**：datapack JSON 招式、loot condition 注册、护盾/多资源条、竞技场会话（锁区/传送/团灭弹出）、多实体共 bar、GeckoLib 适配、状态栈 NBT 持久化（v0.1 读档回 idle）、首杀全服播报。

## 4. 验证策略

无头优先（用户无法操作客户端）：
1. `gradlew build` 绿——状态机/selector/相位/缩放曲线写 **JUnit-free 纯逻辑自检**（`state`/`move` 包不 import net.minecraft，可被 `dev.klze.colossus.test` 的 main 方法 runner 直接跑）。
2. GameTest（`colossus:boss_smoke`）：生成示范 Boss → 断言 bar 装配/状态机切换/判定命中玩家假实体。
3. 数据断言：KillBoard NBT、ATTACK_INDEX 同步值从磁盘/世界回读。

## 5. 偏差与遗留记录（交付时同步更新）
- 许可证暂留 All Rights Reserved——框架定位是"方便别人写 Boss"，建议改 MIT/LGPL 才成生态底座，待用户拍板。
- 示范 Boss 的模型是占位方块，仅证明管线通。
- v0.1 读档后攻击中断回 idle（BR 的整栈 NBT 留到 v0.2）。

## 6. v0.2 扩展设计（第二轮调研输入，2026-09-23）

> 研究依据：`Mod源码研究汇总/分析报告/_分析报告/深挖__BOSS引擎调研v2__动画分体与环境交互.md`
> （动画系统三路盘点 / 分体 Boss 四建法 / 环境交互碎片拼装）。

### 6.1 动画适配层（`api.anim`）——v0.1 契约被反向验证后的精确升级
> **进度（2026-09-23）**：✅ 已完成 `between(a,b)` 窗口帧（`state/FrameRunner` 纯逻辑+15 断言自检）、
> `scheduleWork` 延迟队列、TelegraphZone 数据形态（`env/` + `ZoneSyncS2C` + 客户端轮廓粒子）。
> ⬜ 未做：AnchorSampler/TableSampler、GL3 反编译复核、`bindBarOwner`。
- 名字协议、双端各自计 tick、flag 驱动 Layer 均被独立样本确认（Alex's Caves Forsaken 为 1.20.1 原生实证）。
- **升级 A：触发帧窗口化**。`MoveBuilder.between(a, b, trigger)`——Forsaken 的 `tick∈[15,18]` 窗口判定优于单帧等值，抗双端漂移；保留 `at(t)` 糖。
- **升级 B：`AnchorSampler` 接口**，两个后端：`TableSampler`（Forsaken 式分段解析轨迹表，零依赖兜底，可进自检）与 `BoneSampler`（空壳，等 GL3 适配/未来版本）。
- **拒绝**：keyframe event→逻辑（GL3 事件仅客户端触发，跨端语义不闭合）、动画图/IK/root-motion（DBE 2 万行级，不跟进）。
- ⚠ 落地 GL3 适配前须反编译实际 GL3 jar 复核其同步形态（库内无一手证据）。
  **✅ 已复核并反转（v4 取证）**：1.20.1 没有 GL3——适配目标是 **GeckoLib 4（≥4.4.x）**。GL4 事实：`triggerAnim(controller, 动画名)` 字符串包 fire-and-forget、`SerializableDataTicket` 是唯一 S→C 动画决策通道、**无服务端动画时钟、无 C→S 回报**。推论：①"服务端权威帧表 + GL 只当显示层"是唯一可行架构（已从偏好升级为事实）；②GL4 适配器职责收窄为 onStart→triggerAnim + 旗标 ticket；③AnchorSampler 骨骼坐标只走 (a) 离线烘焙 `.animation` JSON 关键帧进帧表，拒绝 (b) C→S 回传。

### 6.2 分体系统（`entity.part`）
> 进度（2026-09-24）：✅ 全层落地（真服务端 GameTest 3/3）——`ColossusBossPart`（v5 取证规矩：零包/零入档/零入 level、失活=尺寸0、hurt 单向转发、倍率部件侧乘一次）、`PartStates` 位图编解码、`RigPose/PartRig` 状态表；parent 侧 `hurtPart/partGate/registerPart/positionParts` + `applyBodyHit` 抽取共用；**部件位图已挂接**（`DATA_PART_BITS` entityData long + NBT 自管持久化 `colossus_part_bits` + `onSyncedDataUpdated→onPartFlagsChanged` 双端回放 + 读档对账）；**弱点机制定案**——v5"射线打不到部件"开放题按 Kraken 先例分流：玩家可瞄准部件=真实体 squad（随 ArenaSession 批），PartEntity 岗位走**护壳路径**：`incomingDamageScale` 减伤 + `partBreakThreshold` 分流击破（DAMAGED→清 ACTIVE→置 DEAD + `onProtectionBroken` 钩子），ExampleColossus 核心（30% 承伤/150 阈值）由回归桩 `coreProtectionAbsorbsThenBreaks` 钉住。取证全文：`深挖__BOSS引擎调研v5__PartEntity取证与竞技场碎片.md`。⬜ 剩余：squad 成员实体化、碎裂视觉钩子。**GameTest 隔离教训**：`run/data/` 跨运行留存 SavedData 污染绝对值断言（killCount 2≠1 事件）——一律断增量。

- **铁律**：部件不拥有网络身份——部件状态位图+少量标量挂 **parent 的 entityData**，客户端 `onSyncedDataUpdated` 回放到 parts；位置由已同步的 parent pos/rot+状态**双侧同算**（Cataclysm 的位置包是死代码、TF 发包是 Fabric-only——零包是正解）。
- `ColossusBossPart<P> extends PartEntity<P>`：失活=尺寸 0（非删除）；`hurt → parent.hurtPart(this, src, amount)`；`id = parentId*100 + index` 伪造。
- `partGate(part, src, amount)`：闸门链照 Hydra 序（自伤过滤→部件姿态门→距离门→相位门→倍率）。
- `PartRig`：Hydra 四张 `Map<State,Float>` 泛化（状态表 + `clampedLerp(prev,cur,t)` + 限速转向 + Naga 式链脊椎 anchor）。
- `BossSquad`（真实体成员）：血条 `bindBarOwner(FloatSupplier)` 单一所有权（修 Kraken 的"父类每 tick 硬 setProgress"脆弱点）；分母=**定义总数**（重生中计 0 防回弹）；重生排期用**绝对 gameTime** SortedSet 进 NBT；成员 hurt 必须转发 `leader.engagement` 与单次伤害上限，否则漏 credit。
- 尸体碎块：纯客户端实体，不进 squad/bar。

### 6.3 环境层（`env`），按性价比排序
1. **TelegraphZone**（两形态）：数据形态（BR IceSpike：区域由 delay 标量推导，零包，到期 AABB 一次性结算）与实体形态（CAT LightningArea：可扩散、周期结算）。触发时刻由 `MoveDef` 帧表声明——BR 依赖的 GeckoLib 关键帧指令在我们的状态机里有现成等价物。客户端契约：`ZoneSync{x,y,z,sx,sz,rot,color,ticks}` + `RenderLevelStageEvent` 画贴地 quad。
2. **ArenaBlockAccess**：`clearBox(sweep, filter)`（NagaSmash 形）+ `applyPattern(offsetTable, facing, state)`（Yeti BREAK_1..4 形）+ mobGriefing/方块 tag 豁免门控。
3. **结构保护 + POI 解锁**：`StructureDestructionEvents` 近乎可照搬；"已击败"用 POI 查询而非读结构 NBT（成本最低）；`getAllStructuresAt` 结果按 chunkKey 缓存。
4. **ArenaSession 最小闭环**：closeOffExit 封路（InfernalDragon 形）+ 团灭弹出 + **加载闸门**（arena 未加载则相位不推进——Kraken 教训）。
5. （v0.3）方块缓存换块（KrakenShipCache）、天气=相位开关（setWeatherParameters 幂等刷）。
> 进度（2026-09-24 第七批）：✅ **ArenaSession 最小闭环**（`env/ArenaSession`：封路快照 save/load 走 NbtUtils、ACTIVATED 上升沿 begin、越界纠偏=宽限 200t+冷却提醒 teleportTo（框架自拟值——**原文这里写"Obelisk 制"，经 v7 全库复核不成立：库内没有越界拉回的一手样本**，不用 setCanceled 冻 tick）、团灭 fail=解封+回血+清目标、victory 挂 resolveDeath、状态随 Boss NBT 持久化）+ **squad 最小核**（`entity.squad`：`ColossusSquadMember` 契约/`SquadManager` 补员+血条均值（分母=定义总数+本体，Kraken 案）+`RespawnSchedule` 绝对 gameTime 排期（30/30 自检验过 NBT 往返——两列 ListTag 在 1.20.1 类型滤镜下不可靠，已改单 CompoundTag 映射）；`barProgress()` 有 squad 时自动改道）。边界（当时的）：会话 bounds 暂为 Boss 周围 64/32/64 固定域——**第八批已改：锚开战点 home 且半轴进 Spec**（跟着 Boss 走等于没有纠偏）。成员死亡→`onMemberDefeated` 的接线由成员实体侧调（接口给了）。GameTest 3/3 无回归。

## 7. 第三轮技术挖掘吸收（2026-09-23，v0.2 排期修订）

> 输入：`深挖__BOSS引擎调研v3__全库通用技术清单.md`（不限 Mod 类型全库扫描，七方向全有实证）。

**已裁决的落点与批次**：
- ✅ 第五批已完成（2026-09-23 构建绿）：掉落管线 `loot/LootDelivery`（DROP_NOW/INTO_CHEST）——死亡演出开场 roll 入 27 格 NBT 缓冲（TF 时序）、remove 前放箱、放不下去落地兜底（修掉 TF 的两个静默丢失洞）、KillBoard first 驱动全服首杀播报；ExampleColossus 切 INTO_CHEST + 战利品表 json。INTO_BAG/loot condition → v0.3。依据：`深挖__BOSS引擎调研v4__掉落管线与GeckoLib版本反转.md`。
- ✅ 动画适配层定案（v4 报告 §一）：适配目标 **GL4（非 GL3，1.20.1 无 GL3）**；帧表服务端权威为唯一可行架构；AnchorSampler 走离线烘焙 `.animation`。
- ✅ 第三批补（2026-09-23，21/21 自检）：`anim/ColossusAnimBackend` SPI（服务端触发面：onAttackStart/onPhaseChangeStart/onDeathStart，NOOP 默认，后端异常只吞日志）+ `anim/TableSampler` 解析轨迹表（Forsaken getHandPos 泛化，局部系可无头单测，右向=forward×up 已验）+ `bindBarOwner` → `barProgress()` 单一所有权钩子。三个状态类 onStart 已挂后端回调。GL4 适配器的两个挂点就位：AttackState.onStart→triggerAnim、`SerializableDataTicket` 旗标（下游用）。
- ✅ 第二批已完成（2026-09-23 构建绿，15/15 自检）：`env/ArenaBlockAccess`（clearBox/breakAhead/applyPattern + mobGriefing + 硬度带 [0,50) + `colossus:unbreakable` 方块 tag 豁免）+ `MoveTriggers.breakAhead` + ExampleColossus.smash 挂碎裂带。
- ⬜ 第三批：`HitSolver` 扫掠判定（TACZ 配方：expandTowards+inflate(1)+逐实体 clip+按距离排序），arcHit/circleHit 升级为"帧内扫掠段"，终结高速挥击漏帧（1a）。 ✅（2026-09-23：HitSolver + MoveTriggers.sweepHit，ExampleColossus.icering 挂演示帧）
- ⬜ 第三批：演出 cue 注册表（ES VfxType 形：MapCodec+StreamCodec 双编码、一个 `PlayCuePacket` 承载全部效果），`sendBossVisualEvent(String)` 保留为字符串糖；首个内置 cue=世界空间震屏（ES ScreenShake 形）。 ✅（2026-09-23：fx/CueType+注册表、CueS2C 单包、ScreenShakeCue（世界坐标衰减+三轴错相）+ViewportEvent 相机钩子，smash/meteor 挂演示）
- ⬜ 第四批：`between()` 窗口加 ContactKey 去重身份（DBE 形——为持续区/多段伤害预备）+ `MoveDef.postAttackInvuln`（ES/Apotheosis 的 i-frame 钩子）。 ✅（2026-09-23：`fight/ContactBook`（纯逻辑，beginAttack 清集）+ `MoveTriggers.once/arcHitContacted` 双粒度 + `.postInvuln(n)` 招式字段，smash 双帧共享键演示，18/18 自检）
- ⬜ 随 GL3 适配落地：`SyncedAnimClock` 漂移纠偏（DBE 常数定版：4ms 死区/80ms 硬快照/0.12s 平滑收敛、永不倒退）。注：ATTACK_TICK 走 entityData 每 tick 同步，框架主路径无漂移——该模块只服务动画后端。
- ⬜ 测试升级：`BossTestScenario` 声明式招式回归（SFM 形）+ sable 式空白 level；`testboss/` 学 Moonlight 变 example 源集。
- **拒绝/降级**：hitstop 顿帧进服务端（保持 DBE 的客户端降速语义，v0.3 再议）；引 Rhino/表达式引擎（微型四则+函数表够用）；timeline 剪辑器（编排长在状态机上）。

> 进度（2026-09-24 第八批·v7 取证回灌）：✅ **竞技场会话与 squad 身份账加固**——
> ①`EngagementTracker` 拆两口径（`participants`=只含活人做结算，`trackedIncludingFallen/hasTracked`=含阵亡未除名做失败判定），
> 并消灭 `ArenaSession.tick` 的**恒假团灭分支**（旧写法遍历已滤死者的快照再问 `isAlive()`）；
> ②判定界从"跟 Boss 走的 bbox"改为**锚开战点 home**，半轴进 `Spec`（`boundRadiusXZ/Y`，默认 64/32）；
> ③纠偏传送改用 `ServerPlayer:1199` 七参版（挂 `POST_TELEPORT` ticket；`:1191` 三参版只发 ROTATION 相对包，
> 拉进未加载区块＝扔虚空），落点经 `safeLanding`（home 上方扫 `-2..3` 找 `isFaceSturdy` 面 + 按序号绕圈散开）；
> ④`SquadManager` 身份账从 AABB 扫场改为 **`key→UUID` 表入 NBT**（Kraken `ownedTentacles` 形，`CompoundTag.putUUID`）
> \+ `LOST_GRACE=200t` 宽限（**未加载≠死亡**，否则成员走散就被补成同 key 第二个）+ 显式 `adopt(key,uuid)`；
> ⑤`ArenaSession.sealedPositions()` 供诊断/测试；`ExampleColossus` 挂上 `arenaSpec()` 当活文档；
> ⑥新增 GameTest `arenaSealsOnActivateAndRestoresOnKill`（封路→快照→击杀→还原全链），
> 并因 `BossKillBoard` 是 level 级 `SavedData` 的**跨测试串扰**（兄弟测试也结算击杀，增量断言被打成 +2），
> 改为每个会结算击杀的测试独占 `@GameTest(batch=...)`（`GameTestBatchRunner` 逐批串行）。
> 取证纠偏：v5 的"纠偏制取材 ObeliskDepths"**作废**（库内无越界拉回一手样本，grace/warn 数值是框架自拟），
> BR 龙洞封路**没有恢复代码**（可抄的记账形是同项目 `KrakenShipStructure.captureRegion`）——详见研究库 v7 报告。
> 验证：build 绿 + 自检 30/30 + `gameTestAudit` 4 条（floor 3）+ `runGameTestServer` **All 4 required tests passed**。
> 边界与欠账：squad 账本的运行期覆盖要等示范成员实体（第九批）；封路位被玩家/爆炸破坏时的对账（BR 在 `ExplosionEvent.Detonate` 清账）未做；
> `homeBindRadius` 仍是挂名参数（Boss 归位约束未接）；跨维 arena / 副本实例状态机推 v0.3。

> 进度（2026-09-24 第九批·squad 成员基类与示范成员）：✅ **分体系统从"隐形锚"补上"可打的身体"**——
> v5/v7 的裁决（1.20.1 玩家射线打不到 PartEntity ⇒ 可瞄准部位必须是真实体）第一次有了可抄的实现形：
> `entity.squad.ColossusSquadMemberEntity`（抽象基类）钉死四件事——
> ①**UUID 懒解析**（`setSquadLeader` 只存 id，`baseTick → resolveLeader` 带 Kraken 快路径三条件；客户端永不解析；leader 未加载时成员挂空不自杀）；
> ②**锚点单一来源**（局部位移只写在 `MemberDef.localOffset()`，补员时 `setAnchorFromDefinition` 注入，
> 实体侧 `anchorOverride()` 默认 null ⇒ 不再出现"定义一个数、实体一个数"两处打架；`customServerAiStep` 每 tick 钉位，
> **注意 1.20.1 的 `Mob.serverAiStep()` 是 final（`Mob:742`）**，挂点只能是 `customServerAiStep`）；
> ③**受击单点转发**（`hurt` 里 `incomingDamageScale()` 只乘一次 → `damageForwardRatio()` 按比例同步削本体 →
> 打成员也进队长参战账 `forwardContribution`）；④**位移钉死 1.20.1 形**（1.20.1 **没有 `NoMoveControl`**，那是 1.20.2+ 类，
> v7 样本出自 NeoForge 1.21.1 ⇒ 这里用"构造期换掉 `moveControl` 且 `tick()` 空转 + `registerGoals()` 留空 +
> `isPushable()=false` + `setNoGravity(true)`"达到同一效果）。
> 队长倒下时 `resolveDeath → squad().notifyLeaderDeath()` 广播收摊，`leaderDown` 门同时关掉重生排期
> （否则"Boss 死了、触手还在原地复活打人"）。示范成员 `ExampleSentry`（浮游炮，key/锚点都不自写）
> 完整走通注册面：EntityType + `createMobAttributes` + 模型层 + `MobRenderer`。
> 新增 GameTest `squadLedgerSpawnOnceAndCleanupWithLeader`：判别式设计取"成员凭空消失"而不是"把成员挪远"
> （锚点跟随的成员下一 tick 就被拉回肩上，物理挪动测不出任何东西）——
> **消失后 20t 场上必须仍是 0 具**（旧扫场形此刻已刷出第二具）+ **240t 后必须补回 1 具**（宽限期没有出口＝成员永远失踪），
> 两条一夹就把 `LOST_GRACE` 的上下界都钉住了（等价于变异检验，且无需临时改产品码）。
> 验证：build 绿 + 自检 30/30 + `gameTestAudit` **5** 条（floor 3）+ `runGameTestServer` **All 5 required tests passed :)**。
> 边界与欠账：`squad` 成员尚未参与 `HitSolver`/招式的"打哪个部位"解算（弱点几何仍是开放题）；
> `ExampleSentry` 不攻击（纯靶子），仆从 AI 走原版 goal 由下游自行接；成员客户端可视化只有占位模型。

> 进度（2026-09-24 第十批·持续帧=hold 语义）：✅ **危险区逐轮复判从"v0.3 承诺"变成帧表原语**——
> `FrameRunner.Frame` 加 `period`（`Frame.repeating(from,to,period,action)`，`advance` 用
> `(tick-from)%period==0` 判定），`MoveSetBuilder.MoveBuilder.repeating(from,to,period,trigger)` 登记，
> `MoveTriggers.telegraphVisual(zoneFn)` 只画区不结算（防止与 `telegraph` 的"warnTicks 后爆发"叠成两遍伤害）。
> **没有新增触发器子类**：`circleHit` 复触发＝持续掉血，`arcHitContacted` 复触发＝区域内每人整招至多一次
> ——新维度做成帧参数，不做新类（用户纪律）。示范招 `flamewall`（画 68t 火圈 + 每 10t 复判）。
> 自检补 4 条（节拍序列、同窗口一次性帧不受影响、**迟到入场只丢拍不补拍**、period<1 登记期抛）＝**34/34**。
> 顺手抓到一个自己埋的雷：`MoveDef.newRunner()` 原先用 `between(f.from,f.to,f.action)` 重建帧，
> 加了 period 之后这条路径会**静默把持续帧降回一次性帧**——改为 `Builder.add(frame)` 原样搬帧。
> 验证：build 绿 + 34/34 + `gameTestAudit` 5 条 + `runGameTestServer` **All 5 required tests passed :)**。
> 边界：`repeating` 帧尚未进 datapack 词汇表（v8 报告的 JSON 形态里已预留 `"repeating":[from,to,period]` 位）；
> 火墙的持续伤害仍走原版无敌帧节流，没有独立的"区域 tick 记账"（做 hold 的 DoT 分层时再议）。

> 进度（2026-09-24 审查轮 5 收口）：✅ 第八/九/十批的回归专查回来，**三条 P1 全部为真**（锚点注入不入 NBT⇒读档后成员全贴队长原点、`participants()` 是全服名单⇒跨维绑架、宽限期销账不销人⇒同 key 两具+孤儿漏收摊），另修 P2 团灭判据挂易失名单（`everEngaged` 入 NBT）与一条**恒真断言**。逐条处置与"设计如此不修"的清单见 `docs/代码审查-v0.2.md` 末节。纪律沉淀两条：**新维度只存一份事实源**（锚点在定义表里，实体现查，不留副本）；**判"没人了"之前先问名单是不是易失的**（跨维/退服/重载都会让当下快照为空）。验证：build + 34/34 + audit 5 + GameTest **All 5 passed**。

> 进度（2026-09-24 第十一批·附加资源条 + 动画契约收口）：✅ 两件事都来自取证回灌。
> ①**出招序号** `DATA_ATTACK_SEQ`（GL4 一手源码点名的事故：`ATTACK_INDEX` 在"连放同一招"时不变，
> 客户端 predicate 不会重启动画）——OrdertoCook 的 `ACTION_STATE` 与 dumbcat 的 `HURT_SEQ` 都是递增序号，
> 框架照此加 `attackSequence()`，并把"服务端永不反向查询动画播完没"写进 `ColossusAnimBackend` 契约
> （GL4 的 15 个 packet 全 S→C、无 finished 事件）。顺带开 `forceMove(moveId)`（仅空闲可推、走同一条
> AttackState 管线），否则"同招二连"这类只有真机才暴露的问题根本写不出回归桩——桩 `repeatedCastBumpsSequenceNotIdentity`。
> ②**附加资源条（护盾）**：权威值放 `DATA_SHIELD`（§0.4 零包法则），血条那条只是表现层镜像
> （`bar/DirtyMeter` 纯件钉三条不变量：同值 0 包 / NaN 不发也不污染 memo / `invalidate()` 是唯一补账口）；
> `ColossusBossEvent` 在 `addPlayer` 与 `setVisible(true)` 翻转处补发快照、`removePlayer` 显式发 0 清镜像、
> 包**编码一次多播 N 次**（DE 反面）；吸收顺序=护壳分流之后再进盾，破盾发 `colossus:shield_break`，
> 脱战按 `shieldRegenDelay/PerTick` 回充；**参战记账判据从 `hit` 改成 `willLand`**——否则"被盾整个吃掉的一刀"不进账。
> 客户端 `CustomizeGuiOverlayEvent.BossEventProgress`（1.20.1 实测有 `GuiGraphics` + 裸 float partialTick +
> `setIncrement`）只接管我们有盾的 bar，自绘两条后用 `setIncrement(+6)` 让位，不必像 DE 那样整条取消再自己排 Y。
> 桩先红后绿抓到一个**真产品 bug**：回充门写作 `gameTime - Long.MIN_VALUE < delay`，减法溢出成负数 ⇒
> 护盾永远回不起来（哨兵值改成 `-1` + 显式判）。
> 验证：build 绿 + 自检 **39/39**（DirtyMeter 5 条）+ `gameTestAudit` **7** 条 + `runGameTestServer` **All 7 required tests passed :)**。
> 边界：护盾条的**视觉**未在真客户端复验（headless 只能验服务端数值与包不变量）；GL4 依赖本身仍未引（optional 不可行——
> 实体 `implements GeoEntity` 是字节码级引用，取证已判死），等用户拍板；`BossBarStyles` 自绘贴图仍未接（现在填的是固定色 rect）。
> 归因纠偏：《可抄总表》第 58 条把 `ShieldHudElement` 挂在 twilight 名下（实为 DE）、第 60 条的 `StreamCodec` 形态
> 在 1.20.1 **不存在**（1.20.2+ 才有）——详见研究库 v9 报告 §四。

> 进度（2026-09-24 第十二批·GeckoLib4 可选 addon）：✅ **动画这层按"框架零依赖 + addon 单独出 jar"落地**（用户拍板）。
> 裁决依据是 v9 取证判死的那条：实体 `implements GeoEntity` 属字节码级引用，GL 缺失即 `NoClassDefFoundError` ⇒
> 核心里做不出"可选后端"，只能把 GL 全关进 `addons/colossus-gecko`。构建形＝同工程内**额外 source set**
> （`gecko`）+ `geckoCompileOnly geckolib-forge-1.20.1:4.8.3` + 独立 `jarColossusGecko` 任务，
> 整块在 `if (project.hasProperty('gecko'))` 里 ⇒ **默认构建完全不碰 GL**（连下载都不发生），addon 另有自己的 `mods.toml`
> （依赖 colossus + geckolib mandatory）。适配基类 `GeoColossusEntity` 三件必覆写 + 两条控制器
> （main/movement），触发档取"读同步实体数据"族：判据用 `attackSequence()`（序号变化即 `forceAnimationReset()` 再起播）、
> `RawAnimation` 按名缓存、**明写不许用 `hasAnimationFinished()` 反推判定**。示范 Boss `ExampleGeoColossus` 证明"下游只 extends"成立。
> 已验：`-Pgecko compileGeckoJava` 与 `jarColossusGecko` 通过（产出 `colossus-gecko-0.1.0.jar`）、
> 核心 jar GL 引用数 **0**（`unzip -l colossus-0.1.0.jar | grep -ci geckolib` → 0）、主门不受影响（build + 39/39 + audit 7 + GameTest 7/7）。
> **未验（说白）**：运行期表现——本仓库没有 geo/animation/texture 资产，且本机验证口径是 headless（客户端不可操作）；
> MDG legacy 下 GL 自带的 mclib 能否被正确 remap 也未实测。这两条要等能起真客户端的那一轮，或改用 playtest-bridge 通道。

> 进度（2026-09-24 第十三批·全局进度同步）：✅ **客户端持权威镜像**（总表第 60 条改道后的 1.20.1 形）。
> 取证的硬约束先落地：**1.20.1 没有 StreamCodec/ByteBufCodecs/CustomPacketPayload**（sources jar 四关键词 0 命中，
> 那是 1.20.2+），Confluence 的"Codec/StreamCodec 成对 + 包体即状态"不可平移；`SavedData` 也没有变更钩子
> （全文只有 dirty 布尔，`DimensionDataStorage` 无通知）⇒ 只能在改写点显式推。
> 实现分三块：**①纯件 `ProgressLedger`**（`encode/decode` 一包 `CompoundTag`，与 `SavedData#save` 共用形状；
> 解码端硬上限 `MAX_ENTRIES=4096`——**截断+告警而不是抛**，抛在 packet handler 里等于炸连接；
> 畸形条目跳过不整包作废；`shouldSend` 只认代际号）；**②`BossKillBoard` 加 `revision`**（recordKill 时 ++，且入 SavedData，
> 让重启后第一 tick 少发一个全表包）+ `snapshot()`；**③驱动 `ProgressSync`**（20t 对账 + `PlayerLoggedInEvent` 补包
> + `ServerStoppingEvent` 重置基线，对应取证三判据"同值不发 / join 必补 / 换世界不拿旧表"）。
> 客户端 `ClientProgress` 只读镜像（O(1) 查询给 loot 条件/HUD 用），代际号倒退的迟到包直接丢，`LoggingOut` 清空。
> 桩：自检 +4（**warn 日志实证了上限与跳过两条路径真的被走到**，不是只断结果）＝43/43；
> GameTest +1 `progressSnapshotMatchesLiveBoard`（真 SavedData 上断代际号 +1、快照与权威表逐项相等、脏检查两侧都对）＝**8 条**。
> **未验（说白）**：包的实际投递——headless GameTest 里没有真玩家，`ProgressSync` 的广播与 `ClientProgress.apply`
> 这条链只能靠纯件与数据侧证，交付/镜像真值要等能连客户端的那轮（或 mock 玩家进 `getPlayerList()` 的通道打通）。
> 本批还暴露一次自伤：脚本按 index 拼接把 `shield-meter` 桩整段吃掉了，**`gameTestAudit` 从 8 掉到 7 才暴露**——
> 已按原文补回。教训：改测试文件别用 index 切片，改完必须复数一遍声明数。

> 进度（2026-09-24 第十四批·datapack JSON 招式表）：✅ **v0.3 头号欠账提前落地**，按 v8 报告的七步路线走完。
> 三条取证裁决都真的起了作用：**①行为留在 Java、数据只给词汇表**（`"type"` 字符串查注册表分派，
> 无脚本/反射/表达式——全部样本一致，v3 §3c 判过"别引 Rhino"）；**②telegraph 那个技术阻塞点靠封闭 kind 表解开**
> （JSON 写不出 `Function<Boss,TelegraphZone>`，于是 `zone.kind=circle_ahead` + `effect.kind=damage|freeze`（数组按 `and()` 组合），
> 由解码器负责把词汇表重建回函数）；**③"一文件多记录、记录自带 key"**（1.20.1 重复 id 抛 `IllegalStateException` 会中止整次扫描）。
> 落地形：`move.data.MoveCodec`（trigger/zone/effect/requires/weight/frames 六套词汇表 + 字段级错误）、
> `move.MoveDataRegistry`（**表/实例分离**：只存纯数据 + 代际号，实体按号自建实例；正在播的招继续用旧 `MoveDef`，
> 下一招才取新表——DBE `GraphRuntimeReloader` 的等价简化）、`move.ColossusMoveSetLoader`
> （`SimpleJsonResourceReloadListener("colossus/moves")` + 加载事务四步：全程写副本 / 逐条 try-catch 收回执 /
> 整批 `publish` 原子换表 / **把跳过谁都打进日志**）、`MoveDef.of(...)` 数据侧工厂（**只多一个入口，不多一套模型**，
> JSON 招与 Java 招产出同一个不可变 `MoveDef`，选招/帧执行/血条解析全共用）。
> 寻址只留一条规则：`data/<任意包>/colossus/moves/<bossNs>/<bossPath>.json` → Boss `<bossNs>:<bossPath>`
> （刻意不用"包命名空间当 Boss 命名空间"——整合包作者改的恰恰是别人的 Boss）；合并语义=**datapack 同名覆盖 Java 并打日志**。
> 顺带删掉一条多余路径：原先"ServerStarting 清表 + publish 换表"两件事做同一份职责，
> 先后顺序一旦反了就是"表被清空"——`publish` 本身是整表替换，`clear()` 随之删除。
> 桩：自检 +7（解码结构、at/between/repeating 三种帧都活、weight 相加、四类坏数据各带字段名被拒）＝**50/50**；
> GameTest +1 `datapackMoveLoadsMergesAndHits`（真数据包 → listener → 回执有货 → 与 Java 招合并且互不挤掉 →
> JSON 招的判定帧真的打伤牛）＝**9 条**，日志实证 `files=1 records=1 applied=1 skipped=0`。
> 本批红过三次、每次都指向真问题：`weight.base` 把整行对象当成员值取（`requireFloat(row)`）、
> 非法 id 走 `new ResourceLocation(...)` 抛 `IllegalArgumentException` 冒充"解析炸了"（改 `tryParse`）、
> 以及上一批那次 index 拼接误删桩——**测试确实在咬**。
> 边界（如实）：JSON 侧暂不支持 `cue`（其载荷是类型化泛型，先给已有 8 个 trigger 配 codec 已完成）、
> 不支持 `not_recent`/连招链（语料无先例，要的是 MoveSet 侧新运行时状态，留 v0.3）、
> `requires` 无嵌套布尔（只有单键与取合取）、无跨包覆写声明式优先级。

> 进度（2026-09-24 审查轮 6 + 第十五批·协议面去索引）：✅ 轮 6 把轮 4 我记的"索引只作显示、双端同源安全"这条偏差**判死**：
> datapack 表只在服务端 reload（客户端 `MoveDataRegistry` 为空或残留上一世界），索引反解轻则无动画、重则错动画。
> 修法是把协议面换成字符串并**删掉索引**：`DATA_ATTACK_ID`(同步 id) + `DATA_ATTACK_DURATION`(同步时长)，
> `attackAnimId/attackAnimName/attackDuration/attackProgress` 全部客户端可算；`currentAttack()` 改服务端权威字段
> （顺带修掉出招中途 /reload 读错 `postAttackInvuln` 的 P3-3）；`MoveSet.indexOf()` 零调用者即删。
> 同批修 P2-1/P2-2/P2-3/P2-4/P3-1/P3-2/P3-4/P3-5（判据统一到 `FrameRunner.windowError`、广播计数当哨兵、
> 帧数/深度封顶、快照来源身份、id 用 tryParse、上限断言取等号）。
> 验证：build + **57/57** + audit **10** + GameTest **All 10 passed**。
> 未验：多人客户端下的动画表现（本机起不了真客户端），只能靠"客户端不再需要表"这个结构性结论兜住。

> 进度（2026-09-24 第十六批·延迟工作可持久化）：✅ `workQueue` 从"存 Runnable + 按 tickCount 计数"改成
> **`DeferredWork(绝对 gameTime, 种类 id, CompoundTag)`**——旧形态既写不进 NBT（闭包），
> 又会在重载后把"还剩几 tick"当成"从 0 起第几 tick"（telegraph 要么凭空消失要么立刻结算）。
> 配套把 telegraph 的结算数据化成 `env/ZoneBurst(damage,knockback,freezeTicks)` + `env/ZoneWork`
> （KIND + encode/execute，区域存**出招时解算好的世界坐标**，不随 Boss 位移重算）；
> JSON 侧的 `effect.kind` 词汇表**同一个形状**（单一事实源），DSL 的 `MoveTriggers.telegraph` 第二参改收 `ZoneBurst`。
> 扩展点 `ColossusBossEntity.registerDeferredWork(kind, handler)`：处理器只准吃 NBT，
> 查不到的种类 warn + 丢弃（mod 更新后不炸存档）。Runnable 版 API **直接删除**，不留第二条路。
> 桩：自检 +4（zone/burst 过 NBT 不变样、merge 取每字段上限、缺 burst 段不抛）＝**61/61**；
> GameTest +1 `deferredWorkSurvivesSaveAndStillSettles`（saveWithoutId → 另一实例 load → 队列还在 → 到点真的打伤圈内牛）＝**11 条**。
> **本批抓出的两个真 bug 都是我自己上一批埋的**：护盾与延迟队列的落盘语句被嵌进
> `if (!partDamage.isEmpty())` 里——**没有护壳分流账的 Boss 永远不持久化这两样**（护盾那条连轮 6 审查都没看见，
> 因为示范 Boss 恰好总有分流账）。移出后 `colossus_works` 空列表也写，读侧才能区分"没待办"与"旧版本没存过"。
> 另记一次工具性自伤：用 python `s.replace(切片, 新块)` 时切片取成空串，`replace('', x)` 会把新块插进**每个字符之间**，
> `MoveCodec.java` 一度变成 47 万行——已按该文件最后一次提交恢复（无未提交工作丢失），坏文件留在 `/tmp/MoveCodec.corrupted.bak`。
> 规矩：**改文件别用 index 切片 + 全局 replace，用精确 Edit**。
> 边界：telegraph 的**客户端轮廓**不持久化（重载后圈子的伤害照落、轮廓消失），要一起恢复得让 ZoneSync 也进 NBT，记 v0.3。
