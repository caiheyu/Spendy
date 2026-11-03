# Spendy

Spendy 是一款本地优先的 Android 记账应用，使用 Kotlin、Jetpack Compose 与 Room。账目留在设备上；网络仅用于用户可见的 GitHub Releases 更新检查。

## 功能

- 记录收入、支出、账户转账、余额调整，以及部分或多次退款和报销回收。
- 管理多个账户、带生效日期的期初余额、余额对账、分类、子分类和标签。
- 统一管理分类、标签和账户的图标库，可设置图标的可见范围、替换图案并同步更新所有引用；默认显示 12 个常用图标，支持名称搜索、来源及可见范围筛选；支持导入图片、移除图标，并随完整备份迁移。
- 按时间、类型、账户、分类、标签与金额搜索；批量修改和短时撤销。
- 查看周、月、年和自定义区间的收支趋势、分类与账户排行、星期分布及日历。
- 导入导出标准 CSV；生成加密完整备份、预览恢复并撤回最近一次恢复。
- 设置深浅主题、主题色和账户金额隐私遮罩。

## 构建

需要 Android SDK Platform 36.1、Build Tools 36.0.0 和 JDK 21。可以直接用 Android Studio 打开项目。

```powershell
./gradlew.bat :app:assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Windows 工作区的 PATH 有一项包含多余双引号，会影响 Gradle 子 JVM；使用项目脚本进行完整验证：

```powershell
./scripts/verify.ps1
./scripts/verify.ps1 -Device
```

第一条脚本会运行单元测试、Android lint 和 APK 构建；`-Device` 会再运行已连接模拟器或设备上的数据库与 Compose 测试。GitHub Actions 在 Linux 上执行同一组单元测试、lint 和构建，并保留 7 天 APK 工件。

## 数据与安全

应用没有账号、后端、广告、分析或使用统计。金额以整数分保存。CSV 为明文。`.spendy` 完整备份使用 PBKDF2-HMAC-SHA256 和 AES-256-GCM；遗忘密码后无法恢复备份。Android 系统云备份和设备转移备份均已关闭。

Android 当前支持自身 schema v5 与 Android 偏好设置结构，不会迁移网页原型的浏览器存储或设置。CSV 仅导入普通收支；换机时请使用完整备份。独立 XLSX 导出、自动报告、单日统计和单标签历史趋势不属于当前实现范围。

应用检查固定地址 `https://github.com/caiheyu/Spendy/releases/latest/download/update.json`。发布更新需使用签名 APK、递增的 `versionCode`、匹配 APK 与证书 SHA-256，并上传 `spendy.apk` 和 `update.json`。仓库不含发布密钥。

Android Studio 签名、密钥备份、附件生成和 GitHub 发布步骤见 [`docs/RELEASING.md`](docs/RELEASING.md)。签名后可运行 `scripts/prepare-release.ps1` 自动准备 APK 与更新清单。

## 代码结构

`domain/` 包含整数金额规则、校验、统计投影和日期分桶；`data/` 包含 Room、DataStore、CSV、加密备份和更新校验；`ui/` 包含 Compose 页面和 ViewModel。Room schema 导出在 `app/schemas/`。

产品设计与网页原型位于原始开发环境的同级目录 `C:\workspace\example`，构建本应用不依赖该目录。签名与发布流程见 [`docs/RELEASING.md`](docs/RELEASING.md)。
