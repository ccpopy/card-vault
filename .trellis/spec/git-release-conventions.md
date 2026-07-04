# Git 与 Release 规范

## 提交信息

本仓库使用 Conventional Commit 风格，主题行必须使用中文说明：

```text
<type>(<scope>): <中文动宾短语>
```

允许的常用 `type`：

- `feat`：新增用户可见能力。
- `fix`：修复缺陷、安全问题或可靠性问题。
- `chore`：发布、配置、依赖、脚手架等维护事项。
- `ci`：GitHub Actions 或发布流水线。
- `docs`：文档与仓库规范。
- `test`：测试补充或测试基础设施。
- `refactor`：不改变行为的结构调整。
- `perf`：性能优化。

常用 `scope`：

- `app`：跨模块应用能力或多个业务域一起改动。
- `update`：检查更新、下载更新包、安装流程。
- `ui`：Compose 界面、交互、动画、视觉。
- `security`：应用锁、PIN、生物识别、剪贴板、Keystore。
- `data`：Room、备份、导入导出、数据迁移。
- `nfc`：NFC 读卡与 EMV 解析。
- `release`：版本号、发布说明、签名安装包。

示例：

```text
feat(update): 匹配设备架构下载更新包
fix(app): 修复安全与更新可靠性问题
chore(release): 准备 1.1.5 发布
ci(release): 校验发布标签与版本号一致
docs(trellis): 添加提交与发布规范
```

避免使用泛化英文提交信息，例如 `Fix security and update reliability issues`。

## 发布说明

每个版本如果需要正式发布，必须新增：

```text
.github/release-notes/vX.Y.Z.md
```

发布说明沿用当前 GitHub Release 格式，按需使用以下分组：

```markdown
## ✨ 新增

- ...

## 🐛 修复

- ...

## 🔧 优化

- ...

## 📦 安装包

- CardVault_X.Y.Z_arm64-v8a.apk
- CardVault_X.Y.Z_armeabi-v7a.apk
- CardVault_X.Y.Z_universal.apk
- CardVault_X.Y.Z_x86.apk
- CardVault_X.Y.Z_x86_64.apk
```

版本发布提交使用：

```text
chore(release): 准备 X.Y.Z 发布
```

发布 tag 必须与 `app/build.gradle.kts` 的 `versionName` 一致。
