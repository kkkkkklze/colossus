# Colossus（巨像引擎）— Forge 1.20.1 BOSS 战框架

> 目标：把"写一个 BOSS"从 15 个文件的样板活，压成 **1 个实体类 + 1 份招式表 + 1 个渲染器**。
> 底座来自 `1.20.1-forge-neoforge-mod-backup-20260912.zip` 的 Forge MDK 模板（备份未动）。

## 状态

- **v0.1.0 骨架**：状态机内核、招式表（builder + 触发帧）、阶段闸门、死亡延迟结算、
  参战名单、人数缩放、血条旁路协议、召唤物、击杀板、示范 Boss。
- 设计书：`docs/DESIGN.md`；研究输入：`../Mod源码研究汇总/分析报告/_分析报告/深挖__BOSS引擎调研__六样本共性模式.md`
- 已知缺口（v0.2）：datapack JSON 招式、战利品缓冲入箱、护盾/多资源条、竞技场会话、
  整栈状态 NBT 持久化、自绘血条样式、GeckoLib 适配。

## 五分钟上手（下游作者）

```java
public class MyBoss extends ColossusBossEntity {
    public ResourceLocation getBossId() { return new ResourceLocation("mymod", "my_boss"); }

    protected void registerMoves(MoveSetBuilder m) {
        m.move("smash").duration(36).cooldown(70).range(7.5f)
         .at(24, MoveTriggers.arcHit(6.5f, 100, 8f, 0.6f))   // 判定帧
         .at(24, MoveTriggers.event("mymod:smash_ring"));     // 客户端演出帧
    }
}
```

其余全部由基类装配：血条/参战 credit/阶段/缩放/死亡结算/同步。完整参考实现见
`com.klze.colossus.testboss.ExampleColossus`（活文档）。

## 构建与验证

```bash
gradlew build             # 编译 + 打包
gradlew colossusSelfTest  # 状态机/帧表内核确定性自检（27 断言，纯逻辑）
gradlew gameTestAudit     # GameTest 声明数下限门（floor=2，堵零测试假绿）
gradlew runGameTestServer # 真服务端 GameTest（击杀路径全链回归；免 EULA）
```

- 网络：仓库列表已按国内镜像优先排布（BMCLAPI/阿里云等），Forge 47.4.23 依赖复用本机 gradle 缓存。
- 本工程路径含中文：跑 JVM 测试一律走 `colossusSelfTest`（JavaExec），勿用标准 test 任务。
- **`run/gameteststructures/colossus_yard.snbt` 是手写测试模板，建库后必须提交**（CI 要用，
  `.gitignore` 的 run/ 规则需要为它开例外——姊妹工程同款约定）。

## 工程身份

| 项 | 值 |
|---|---|
| mod id | `colossus` |
| 包 | `com.klze.colossus` |
| Loader | Forge 1.20.1（47.4.23，ModDevGradle legacyforge） |
| 许可 | 暂定 All Rights Reserved（框架定位建议 MIT/LGPL，待定夺） |
