# 推送规范（PUSH RULES）

本仓库的推送策略由本文件约束，所有改动提交前请对照检查。

## 一、根目录文档推送规则

**根目录的 `.md` / `.txt` 文件中，只有 `制作目录.md` 推送到 GitHub，其余一律保留在本地、不得推送。**

| 文件 | 是否推送 | 说明 |
|---|---|---|
| `制作目录.md` | ✔ 推送 | 对外项目说明/制作目录 |
| `导引目录.md` | ✘ 不推送 | 本地工作笔记 |
| `更新日志.md` | ✘ 不推送 | 本地工作笔记 |
| `魂技生成引擎.txt` | ✘ 不推送 | 本地草稿/规则文档 |
| `新开发文档.txt` | ✘ 不推送 | 本地草稿/规则文档 |

`.gitignore` 已通过精确路径规则（`/导引目录.md` 等）禁止误推送；若新增本地文档，请在 `.gitignore` 同步登记并在本表追加。

## 二、提交范围红黑榜

**✅ 应提交**

- `src/` 下所有源码、资源、配置
- 根目录 `build.gradle` / `settings.gradle` / `gradle.properties` / `check-ap.gradle`
- 根目录 `.gitignore`
- `gradle/`、`gradlew`、`gradlew.bat`（构建脚本）
- `制作目录.md`
- 本推送规范文档 `PUSH_RULES.md`

**🚫 严禁提交**

- `bin/`：Gradle 构建产物（已 `git rm --cached` 之前的追踪，按当前 `.gitignore` 规则可重新纳入追踪；本规范生效期间请勿 `git add bin/`）
- `.eclipse/`、`.idea/`、`.vscode/`：IDE 配置
- `.codebuddy/`、`generated-images/`：本地缓存
- `run/`：Minecraft 运行目录
- `*.log`、`tmp_ap_check.txt`：临时日志
- 根目录其他本地文档（详见第一节）
- 任何含真实 `API Key` / `AccessKey Secret` / `Bukkit` 配置的凭据文件

## 三、推送前自检

每次 `git push` 前必须执行：

```bash
git status --short
git diff --cached --name-only
```

确认上述红榜条目均不在暂存区；如出现，按 `git reset HEAD <file>` 撤回。

## 四、变更本规范

修改本规范或 `.gitignore` 时，请在 commit message 中明确标注 `PUSH_RULES:` 前缀，便于审计追溯。