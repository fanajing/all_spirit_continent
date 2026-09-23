<div align="center">

<!-- =========================================================
     Hero Banner (建议未来替换为 .github/assets/banner.png 实际图)
     ========================================================= -->

# 🌌 All Spirit Continent

### *斗罗大陆 · 魂环魂技深度玩法重构*

<br>

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.29-orange.svg)](https://projects.neoforged.net/neoforged/neoforge)
[![Java](https://img.shields.io/badge/Java-21-red.svg)](https://adoptium.net/)
[![Status](https://img.shields.io/badge/Status-V6.2-blue.svg)](#-版本路线)
[![Platform](https://img.shields.io/badge/Platform-Server%20%2B%20Client-purple.svg)](#-安装)

<br>

> **在 Minecraft 沙盒引擎上，重现「猎杀魂兽 → 吸收魂环 → AI 推演魂技 → 九环成神」的核心养成闭环。**
>
> *每一次猎杀都是独一无二的能力抉择，每一次吸收都是与世界的契约。*

<br>

[**✨ 功能特性**](#-功能特性) · [**🎮 核心玩法**](#-核心玩法) · [**🛠 技术架构**](#-技术架构) · [**📦 安装**](#-安装) · [**⚙️ 配置**](#-配置) · [**🤝 贡献**](#-贡献) · [**📄 协议**](#-协议)

</div>

---

## 📜 项目简介

**All Spirit Continent** 是一个由 [NeoForge](https://projects.neoforged.net/neoforged/neoforge) 驱动的 Minecraft 深度玩法拓展模组。

它并非简单的"加武器 / 加怪物"内容包，而是尝试在 Minecraft 的沙盒引擎上，**重构"魂师修炼"这一长线性养成体验**——通过一整套围绕"**魂环 × 魂技 × 签名机制**"的设计语言，让玩家与世界、与怪物、与 AI 的每一次交互都充满**策略性与不可逆性**。

从 V5.0 基线到 V6.2 当前版本，作者独立完成了从机制设计、规则引擎、网络同步到云端 AI 推演的全部工作，所有规则均由代码定义——**代码即规则权威**。

> **🎨 IP 声明**
>
> 本模组为斗罗大陆 IP 的**粉丝致敬**作品，所有角色、机制设定均为基于原作世界观的二次创作。模组内不包含任何原作剧情、人物名称或美术资源；与原作方无任何官方关联。如有侵权疑虑请联系作者处理。

---

## ✨ 功能特性

### 🌀 完整的九环养成闭环

| 环位 | 年限 | 原语数 | 类别多样性 | 核心定位 |
|:---:|:---:|:---:|:---:|---|
| 1 | 十年 | 1 | 1 | 简单控制入门 |
| 2 | 百年 | 1 | 1 | 强攻单体 |
| 3 | 百年 | 2 | 1 | 控攻混合 |
| 4 | 千年 | 2 | **2** | 联携雏形 |
| 5 | 千年 | 3 | 2 | 三段连携 |
| 6 | 万年 | 3 | 2 | 防御介入 |
| 8 | 万年 | 4 | 3 | 复合控制 |
| 9 | 十万年 | **5** | 3 | **武魂真身**（禁召唤实体） |

**伤害缩放**：十年 ×1 → 百万年 ×32，每升一档伤害翻倍，保证"万年魂技"的存在感。

### 🧠 云端 AI 推演（独家能力）

- 🔗 **DeepSeek / OpenAI 兼容** chat/completions 接口，OpenAI JSON 模式强制结构化输出
- 🎲 **三池抽取 + 自检 + 评分闭环**：`品鉴池 / 臻品池 / 凡品池 / 待确认池`（PENDING 不参与抽取）
- ⚡ **熔断器 + 双轨 Key 降级**：作者 API 失败自动降级玩家私人 Key；连续 N 次失败自动熔断
- 🚨 **异常检测四红线**：违规关键词 / 数值溢出 / 秒杀刷怪 / 9 环禁召唤——命中即自动加入本地黑名单

### 🎯 47 个原语的精细化战斗编排

七大系别，每个原语声明精确的参数白名单与 `target` 规则：

| 系别 | 原语数 | 代表 |
|:---|:---:|:---|
| 控制系 | 9 | BIND / SILENCE / ROOT / STUN / WEAKEN / CONFUSE / DISARM |
| 强攻系 | 7 | BURST / AOE_BURST / EXECUTE / BACKSTAB / COMBO / CHARGE |
| 敏攻系 | 6 | DASH / PHANTOM / EVADE / ACCELERATE |
| 辅助系 | 8 | BUFF_STATS / HOT / REVIVE / SHIELD_TRANSFER / CLEANSE / CD_REDUCE |
| 防御系 | 5 | TAUNT / REFLECT / BARRIER / IRON_BODY / HARDEN |
| 生活系 | 4 | CROP_GROW / FOOD_BLESS / HARVEST / BONEMEAL |
| 通用系 | 11 | SUMMON_ENTITY / PROJECTILE / EXPLOSION / FIRE / POTION ... |

**EXECUTE 双语义**：`value` 同时承担斩杀阈值（`×2` 血量）与伤害基准（斩杀 `×4` / 普通 `×1`）。

**签名机制 A~O 共 15 种**：连续释放 / 范围放大 / 强化效果 / 延时爆破 / 持续时间 / 引导施法 / 锁定目标 / 多重目标 / 瞬发 / 蓄力最大 / 反弹 / 护盾 / 隐身 / 附加治疗 / 削弱——同武魂不同环不得重复。

### 🌐 数据驱动 + 云端协同

```
┌────────────────────────────────────────────────────────────┐
│  OSS 三个 JSON 文件                                         │
│  ├─ public_skills.json   全量魂技库（读-改-写回）           │
│  ├─ blacklist.json       组合键黑名单                       │
│  └─ version.json         版本号（增量同步触发器）           │
└────────────────────────────────────────────────────────────┘
```

- ✅ 启动读离线快照、定时增量拉取、本地改动 10 秒合并写回
- ✅ `version` 递增触发全量重拉；服务端可关闭上传（`enableCloudUpload=false`）
- ✅ PENDING 池隔离；写回前自动剔除黑名单条目
- ✅ **mod 数据驱动**：服务端启动扫描 `data/<modid>/skills/*.json`，按本机已安装 mod 过滤——新增魂技无需重新打包

### 🎬 沉浸式呈现

- 🎞 **完整吸收动画**：升空 → 飞向玩家头顶 → 武魂显形 → 盘旋等待 → 落位加环 → 弹出绑定窗口
- 📊 **魂技感应窗口**：原语摘要、冷却、来源评分、AI/上传者水印
- 🔄 **「自行推演」按钮**：玩家不满意当前魂技可强制 AI 重生成（每环位一次，不入云端）
- 🚫 **ESC 防护**：未选择关闭窗口自动回滚吸收——避免误锁导致魂环浪费

---

## 🎮 核心玩法

### 🔁 主循环

```
  击杀原版/模组生物
       │
       ▼
  魂环实体掉落（年限由 Soul Beast 年限公式推算）
       │
       ▼
  右击魂环 ── 节点等级（10/20/.../90）瓶颈判定 ──▶ 通过
       │                                            │
       │                                            ▼
       │                              播放「吸收魂环动画」
       │                              （升空 → 落位 → 武魂显形）
       │                                            │
       │                                            ▼
       │                              落位瞬间 ──▶ 真正加环（封顶解除）
       │                                            │
       │                                            ▼
       │                              三池抽取 ── 命中 ──▶ 推送魂技绑定窗口
       │                                            │
       │                                            └─ 全空 ──▶ 异步 AI 推演
       │                                                          │
       │                                                          ▼
       │                                            客户端弹出「魂技感应」窗口
       │                                            ┌──────────────────┐
       │                                            │ [吸 收]   ──▶ 绑定 │
       │                                            │ [拒 绝]   ──▶ 回滚 │
       │                                            │ [自行推演] ──▶ 重生 │
       │                                            └──────────────────┘
       ▼
   魂环融入玩家 ──▶ 玩家按环位绑定魂技 ──▶ 评分 / 升池 / 写回云端
```

### 🎲 魂技评分与升池机制

| 评分 | 含义 | 统计归属 |
|:---:|:---|:---|
| ★1-2 | 差评 | 计入 `reject_votes` |
| ★3 | 中性 | — |
| ★4-5 | 好评 | 计入 `rating_sum / rating_count` |

**升池规则**：

- 🌟 **臻品池** `ZHEN_PIN`：均分 ≥ 4.0 **且** 评价数 ≥ 3，抽取权重 ×3
- 😐 **品鉴池** `PIN_JIAN`：默认池，权重 ×1（上传后 7 天观察期内强制留此池）
- 😶 **凡品池** `FAN_PIN`：均分 < 2.5，权重 ×0.3
- 🚫 **待确认池** `PENDING`：差评 ≥ 5 且差评率 > 60%，**不参与抽取**

> 玩家评分有 24h 防刷窗口。

### 🎮 玩家命令速查

```
/douluo help                    # 查看所有命令
/douluo status                  # 当前魂环状态
/douluo skill <环位>            # 查看环位魂技详情
/douluo rate <环位> <1-5>       # 给当前环位魂技评分（24h 一次）
/douluo config                  # 查看客户端/服务端配置
```

完整命令列表通过 `/douluo help` 获取。

---

## 🛠 技术架构

### 📦 模块结构

```
src/main/java/org/fanajing/all_spirit_continent/
├── All_spirit_continent.java        # 模组主类 + 事件订阅总线
├── cloud/                            # 云端同步层（OSS + AI + 黑名单）
│   ├── ApiClient.java               # DeepSeek/OpenAI 兼容 chat/completions 客户端
│   ├── CloudSyncService.java        # OSS 读-改-写回协调器
│   ├── SkillBlacklist.java          # 本地黑名单持久化
│   ├── OssClient.java               # 阿里云 OSS 直连 HTTP 客户端
│   └── Credentials.java             # 混淆内置凭据（ObfuscatedSecrets）
├── config/                          # ModConfig / CloudConfig
├── data/                            # 玩家数据持久化
├── entity/                          # SoulRingEntity（魂环实体）
├── init/                            # ModItems / ModBlocks / ModAttachments
├── item/                            # 昊天锤 / 水晶球 / 调试棒
├── mixin/                           # Mixin 注入（相机 / 接口）
├── network/                         # 网络包（8 个 CustomPacketPayload）
├── skill/                           # 魂技核心
│   ├── SkillData.java               # 数据模型（execution / passive / mechanism）
│   ├── SkillExecutor.java           # 执行引擎
│   ├── SkillPrimitive.java          # 47 原语枚举 + Param 声明
│   ├── RatingStats.java             # 评分模型 + 池判定
│   ├── engine/                      # 校验 / 规则 / 修复 / 异常检测
│   │   ├── RingPositionRules.java   # 9 环位硬约束表
│   │   ├── SignatureMechanism.java  # A~O 签名机制
│   │   ├── SignaturePool.java       # 玩家签名池（唯一性）
│   │   ├── SkillValidator.java      # 综合校验器
│   │   ├── SkillAnomalyDetector.java# 四红线异常检测
│   │   ├── LocalRepair.java         # 本地修复器
│   │   ├── FallbackSkillBuilder.java# 兜底构建器
│   │   ├── ModResourceScanner.java  # mod 数据驱动扫描
│   │   ├── ModDataSkillLoader.java  # mod 数据 JSON 加载
│   │   ├── PassiveBalanceValidator.java
│   │   └── ResourceResolver.java
│   └── profile/                     # 玩家战斗指纹（云同步）
└── util/                            # 工具层
```

### 🔌 关键依赖

| 依赖 | 版本 | 用途 |
|:---|:---:|:---|
| NeoForge | 21.1.29 | Minecraft 1.21.1 现代模组加载器 |
| Java | 21 | 利用最新虚拟线程与模式匹配 |
| Gson | 2.10+ | 云端 JSON 序列化 |
| SLF4J | latest | 日志门面 |
| Aliyun OSS | — | 直连 HTTP（无 SDK 依赖，纯 `java.net.http.HttpClient`） |

### 🛡 安全设计

- 🔐 **凭据隔离**：OSS / AI Key 优先从环境变量读取（`ASC_OSS_ACCESS_KEY_ID`、`ASC_AI_AUTHOR_API_KEY`），其次配置文件，**绝不**写入仓库
- 🛡 **本地黑名单**：恶意条目命中异常检测后**永远**进入本地黑名单，即使云端删除也会拒收
- 🚨 **熔断器**：AI 接口连续失败自动熔断，避免接口滥用与玩家阻塞
- 📵 **24h 防刷**：玩家评分 24h 窗口期，防止恶意升降池

---

## 📦 安装

### 🎮 客户端玩家

1. 📥 下载最新 release 的 `all_spirit_continent-X.Y.Z.jar`
2. 🔧 安装 [NeoForge 21.1.29+](https://projects.neoforged.net/neoforged/neoforge) for Minecraft 1.21.1
4. 📂 把 jar 放进 `.minecraft/mods/` 目录
5. 🚀 启动游戏，进入世界后 `/douluo help` 查看命令

### 🖥 服务端

1. 📥 同上安装 NeoForge + 模组 jar
2. ⚙️ 首次启动会在 `config/all_spirit_continent-cloud.toml` 生成云端配置
3. 🔐（可选）通过环境变量注入凭据：

   ```bash
   # Linux / macOS
   export ASC_OSS_ACCESS_KEY_ID=...
   export ASC_OSS_ACCESS_KEY_SECRET=...
   export ASC_AI_AUTHOR_API_KEY=...

   # Windows PowerShell
   $env:ASC_OSS_ACCESS_KEY_ID="..."
   $env:ASC_OSS_ACCESS_KEY_SECRET="..."
   $env:ASC_AI_AUTHOR_API_KEY="..."
   ```

4. 🔄 重启服务端，观察日志中 `[ASC-CloudSync]` 是否拉取成功

---

## ⚙️ 配置

服务端配置文件：`config/all_spirit_continent-cloud.toml`

| 配置项 | 默认值 | 说明 |
|:---|:---|:---|
| `oss.endpoint` | `https://douluofan.oss-cn-beijing.aliyuncs.com` | OSS Bucket 域名 |
| `oss.bucket` | `douluofan` | OSS Bucket 名 |
| `oss.accessKeyId` | （留空） | 写回用；留空 = 仅读模式 |
| `oss.accessKeySecret` | （留空） | 同上 |
| `ai.authorUrl` | `https://api.deepseek.com/chat/completions` | AI 端点（OpenAI 兼容） |
| `ai.authorApiKey` | （留空） | 作者 Key；留空降级玩家 Key |
| `ai.model` | `deepseek-chat` | AI 模型 |
| `ai.timeoutSeconds` | `10` | AI 超时（最低 10s 硬下限） |
| `ai.circuitBreakerFailures` | `3` | 熔断阈值（连续失败 N 次） |
| `ai.circuitBreakerOpenSeconds` | `60` | 熔断持续时长 |
| `sync.intervalSeconds` | `300` | 拉取间隔（最低 30s） |
| `defaultWuhun` | `昊天锤` | 玩家默认武魂（云端组合键用） |

> **⚠️ 隐私提示**：配置文件中**切勿填写真实凭据**，优先使用环境变量。`.gitignore` 已忽略 `config/*.json` 等本地配置。

---

## 📸 演示截图

> _演示截图将在后续 release 中随二进制一同发布。_
>
> 建议位置：`.github/assets/screenshots/`

| 魂环感应窗口 | 魂环吸收动画 |
|:---:|:---:|
| _（待补图）_ | _（待补图）_ |
| **三池抽取结果** | **ESC 防护提示** |
| _（待补图）_ | _（待补图）_ |

---

## 🤝 贡献

欢迎 Issue / PR。提交前请阅读 [`PUSH_RULES.md`](./PUSH_RULES.md)。

### 💻 开发环境

| 工具 | 推荐 |
|:---|:---|
| JDK | Eclipse Adoptium 21.0.11+ |
| Gradle | 8.x（项目自带 wrapper） |
| IDE | IntelliJ IDEA / Eclipse / VS Code 均可 |

### 📋 提交流程

1. 🍴 Fork → 新建特性分支
2. ✅ `./gradlew compileJava` + `./gradlew runClient` 验证
3. 📤 PR 标题以 `[V6.x]` 开头，描述动机与变更
4. 🏷 提交信息：`PUSH_RULES:` 前缀表示规则文件改动
5. 📐 遵循 [PUSH_RULES.md](./PUSH_RULES.md) 红榜，不提交 `bin/`、本地文档、IDE 配置、凭据

### 🐛 Bug 报告

请使用 [GitHub Issues](../../issues) 并提供：

- Minecraft 与 NeoForge 版本
- 模组版本（`mod_version`）
- 相关日志（**请脱敏任何 API Key / 玩家 ID**）
- 复现步骤

---

## 🗺 版本路线

| 版本 | 状态 | 重点 |
|:---|:---:|:---|
| V5.0 | ✅ 已发布 | 基础魂环机制（baseline） |
| V6.0 | ✅ 已发布 | 云端 AI 推演 + OSS 同步 |
| V6.1 | ✅ 已发布 | 完整战斗引擎 + 9 环签名机制 |
| **V6.2** | 🚀 **当前** | 落位吸收 + 防御性 ESC 防护 + 数据驱动 mod 技能 |
| V6.3 | 🔮 规划中 | 多武魂支持 / 跨维度流 |

---

## 📄 协议

本项目基于 **MIT License** 开源发布 — 详见 [`LICENSE`](./LICENSE) 文件。

```
MIT License

Copyright (c) 2025 fanajing

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND...
```

> ⚠️ **再次提示**：本模组为斗罗大陆 IP 粉丝致敬作品，所有角色与剧情均为基于原作的二次创作，与原作方无官方合作关系。

---

## 💖 致谢

| 项目 | 贡献 |
|:---|:---|
| 🐉 斗罗大陆（唐家三少） | 世界观与机制灵感来源 |
| 🛠 NeoForge 团队 | 现代模组加载器 |
| 🤖 DeepSeek | 提供 AI 推演能力 |
| ☁️ 阿里云 OSS | 提供云端存储 |
| 🎮 所有测试玩家与提交 PR 的贡献者 | 让这个模组变得更好 |

---

<div align="center">

### ⭐ 如果觉得有帮助，欢迎 Star 支持开发！

<br>

**Made with ❤️ by [fanajing](https://github.com/fanajing)**

<sub>MIT License · Copyright © 2025 fanajing · All Spirit Continent is an unofficial fan-made mod, not affiliated with the original IP holders.</sub>

<br>

[⬆ 返回顶部](#-all-spirit-continent)

</div>