# CardVault 卡包

一个**纯本地**的安卓银行卡管理应用（Kotlin + Jetpack Compose + Material 3）。

## 功能

- **录入/编辑**：持卡人姓名、卡号、有效期（MM/YY）、CVV（可选）、发卡行、备注；支持前台 NFC 读卡预填卡号与有效期
- **自动识别**
  - 卡组织：银联 / Visa / Mastercard / Amex / JCB / Discover / Diners（本地 IIN 前缀判断，银联 62 优先）
  - 发卡行：内置国内 17 家常见银行卡 BIN 表（工行、农行、中行、建行、交行、招行、邮储等），最长前缀优先匹配
  - 识别不到可手动选择卡组织、手动填写发卡行
  - 可选**在线 BIN 查询**（binlist.net，仅上传卡号前 8 位，支持走代理）
- **卡片墙**：钱包式堆叠列表，上下滑动浏览；长按拖拽调整顺序；点击卡片弹出（缩放+淡入动画），再点卡面**翻转**看卡背 CVV
- **精美卡面**：19 种渐变预设（含银行主题色与通用配色），带**流光**扫过效果，每张卡可单独选择样式
- **点击复制**：详情页每个字段点一下即复制，剪贴板标记为敏感内容并可定时自动清空
- **模糊搜索**：按持卡人 / 银行 / 备注 / 卡组织 / 卡号（含末四位）搜索，支持子序列模糊匹配
- **到期管理**：30 天内到期的卡自动归入「即将到期」（带数量角标），已过期单独分类，卡面显示角标；可开启本地到期通知
- **加密备份**：导出/导入加密备份文件，使用独立备份密码保护；导入时按卡号合并，同卡号更新
- **安全**
  - 应用锁默认关闭，可在设置中开启：6 位 PIN（PBKDF2 十二万次迭代加盐存储，连错 5 次锁定 30 秒）
  - 生物识别（指纹/面容）需先开启应用锁，且通过一次生物验证后才启用
  - 卡片详情默认打码，点右上角眼睛临时显示（复制不受影响，始终复制真实值）
  - 数据库使用 **SQLCipher（AES-256）** 加密，口令随机生成并由 **Android Keystore** 硬件密钥包裹
  - 备份文件使用 PBKDF2 派生密钥并通过 AES-GCM 加密
  - 退后台超时自动锁定（可配置：立即 ~ 15 分钟）
  - 防截屏（FLAG_SECURE）、列表卡号打码、禁用系统备份
- **设置**：代理（`socks5://127.0.0.1:10808` 或 `http://host:port`，含连通性测试）、生物识别开关、自动锁定时长、剪贴板清除时长、打码开关、防截屏开关、到期通知、加密备份、检查更新、主题（跟随系统/浅色/深色）、修改 PIN

## 隐私说明

- 卡片数据只写入本机加密数据库，应用无任何统计/上报。
- 联网行为只发生在用户主动触发的 BIN 查询、代理连通测试和版本检查；BIN 查询只发送卡号前 8 位。
- 检查更新会访问 GitHub 官方 release，并通过系统默认浏览器打开 APK 下载链接。
- SOCKS 代理使用 `createUnresolved`，DNS 解析也走代理，避免泄露。
- NFC 读卡只在应用前台启用，用于读取卡号与有效期；不会读取 PIN 或 CVV。

## 构建

### 本地构建

本仓库使用 JDK 17、Gradle 8.7、Android Gradle Plugin 和 Android SDK 34。已安装本地命令行工具链时，可以运行项目根目录的 `build.bat`：

```bat
build.bat          :: 构建 debug APK -> app\build\outputs\apk\debug\app-debug.apk
build.bat install  :: 构建并 adb 安装到已连接的手机（需开启 USB 调试）
build.bat release  :: 构建 release APK（需要配置 release 签名环境变量）
```

最低支持 Android 8.0（API 26），目标 API 34。

也可以用 Android Studio 打开本目录（可选，非必需）。

### 标签发布

推送语义化版本标签会触发 GitHub Actions 自动构建安装包，并把 APK 上传到对应的 GitHub Release：

```powershell
git tag v1.1.0
git push origin v1.1.0
```

当前发布工作流构建的是签名 release APK，并按 ABI 输出多个安装包：

- `CardVault_<version>_arm64-v8a.apk`
- `CardVault_<version>_armeabi-v7a.apk`
- `CardVault_<version>_x86.apk`
- `CardVault_<version>_x86_64.apk`
- `CardVault_<version>_universal.apk`
- `SHA256SUMS.txt`

首次发布前需要在 GitHub 仓库的 `Settings` -> `Secrets and variables` -> `Actions` 中配置：

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | release keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 key alias |
| `ANDROID_KEY_PASSWORD` | 签名 key 密码 |

项目根目录提供了 `generate-release-keystore.ps1` 用于生成 release keystore 和 Base64 文本。推荐直接让脚本生成随机强密码和本地 `.env`：

```powershell
$ErrorActionPreference = 'Stop'
.\generate-release-keystore.ps1 -InitEnv
```

也可以手动复制模板并填写本地 `.env`：

```powershell
$ErrorActionPreference = 'Stop'
Copy-Item -LiteralPath .env.example -Destination .env
```

然后编辑 `.env`：

```text
ANDROID_KEYSTORE_PASSWORD=替换成强密码
ANDROID_KEY_PASSWORD=替换成同一个强密码
ANDROID_KEY_ALIAS=cardvault
ANDROID_DISTINGUISHED_NAME=CN=CardVault,O=CardVault,C=CN
```

手动填写 `.env` 后再执行：

```powershell
$ErrorActionPreference = 'Stop'
.\generate-release-keystore.ps1
```

脚本会生成：

- `release-signing/cardvault-release.p12`
- `release-signing/cardvault-release.base64.txt`

`.env` 和 `release-signing/` 已加入 `.gitignore`。不要把 `.env`、keystore、密码或 Base64 文件提交到仓库。正式发布过后不要重新生成 keystore，否则后续版本会被 Android 视为另一个应用签名。

## 主要目录

```
app/src/main/java/com/cardvault/app/
├── data/        Room 实体/DAO/仓库、DataStore 设置
├── domain/      卡组织识别、银行 BIN 表、校验/格式化、卡面样式预设
├── network/     BIN 在线查询 + 代理解析（OkHttp）
├── nfc/         前台 NFC/EMV 读卡预填
├── notifications/ WorkManager 到期提醒
├── security/    Keystore 包裹的数据库密钥、PIN 管理、锁控制、剪贴板助手
└── ui/          lock（解锁/设置 PIN）、home（卡片墙/详情/卡面）、edit、settings
```

## 已知取舍与可扩展点

- BIN 表是常见前缀子集，识别不到时可手动填写；要扩充直接改 `domain/BankDirectory.kt`。
- binlist.net 免费接口有频率限制（约 5 次/小时），国内直连可能不通——这正是代理设置的用途。
- 搜索未做拼音首字母匹配（如 `gs` → 工商），可引入 pinyin 库扩展 `CardValidation.fuzzyMatch`。
- 部分银行卡不会通过 NFC 暴露实体卡号，或只返回受限记录；自动录入失败时会保留手动录入流程。
