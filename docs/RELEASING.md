# 使用 Android Studio 签名并发布 Spendy

适用当前项目：包名 `com.github.caiheyu.spendy`，最低 Android 8.0（API 26）。当前版本为 `0.1.0`、`versionCode = 1`。本指南不会自动创建签名密钥、提交代码或发布 GitHub Release。

## 1. 确定版本并保存源代码

在 `app/build.gradle.kts` 的 `defaultConfig` 中设置版本：

```kotlin
versionCode = 1
versionName = "0.1.0"
```

首版可以保留当前值。后续每次发布都增加 `versionCode`，例如下一版设为 `2` 和 `"0.1.1"`。内置更新以 `versionCode` 判断新版本，标签文字不会替代它。修改后点击 Android Studio 的 **Sync Now**。

在 Android Studio 的 **Commit** 窗口检查并提交此次发布的全部源代码，然后 **Git → Push** 推送至 `origin/master`。创建版本标签时选择包含本次发布完整代码的提交。确保暂存与未暂存的最终修改均纳入提交；不要加入密码、密钥、用户备份或本地配置。

提交源代码与上传可安装 APK 是两个独立步骤：代码进入仓库，安装包作为 Release 附件上传。现有 GitHub Actions 工作流只测试并构建 debug APK，不会自动签名或发布正式版。

## 2. 第一次创建并保管签名密钥

1. 在 Android Studio 选择 **Build → Generate Signed App Bundle / APK…**。
2. 选择 **APK**，点击 **Next**。GitHub 分发使用可直接安装的 APK；AAB 主要供 Google Play 分发。
3. 模块选择 `app`。第一次点击 **Create new…**：
   - **Key store path**：放在仓库以外，例如 `C:\AndroidKeys\Spendy\spendy-release.jks`；先创建父目录。
   - **Password / Confirm**：设置并妥善保存 keystore 密码。
   - **Alias**：例如 `spendy`。
   - **Key password**：设置并保存密钥密码；可与 keystore 密码一致。
   - **Validity (years)**：至少 25 年，例如 30 年。
   - **Certificate**：填写你自己的证书身份信息；常规自签名应用发布不需要购买证书。
4. 将 `.jks`、别名及密码分别妥善备份。以后所有更新都使用同一密钥，遗失后不能覆盖更新现有安装。不要把私钥或密码上传 GitHub，也不要通过聊天发送。

项目 `.gitignore` 已忽略 `.jks`、`.keystore` 和签名属性文件，但仍建议把私钥保存在仓库以外。

## 3. 生成正式签名 APK

1. 回到签名向导，选择刚创建的 keystore、别名并输入密码。
2. 下一页选择 **release**。如显示签名方案选项，保留兼容当前最低 API 26 的默认设置；不要为了发布关闭签名。
3. 选择输出目录，例如 `C:\workspace\Spendy\app\release`，点击 **Create / Finish**（不同 Android Studio 版本按钮文字可能不同）。
4. 构建完成后点击通知中的 **Locate** 找到实际生成的签名 APK，通常文件名为 `app-release.apk`。后续脚本以此文件为输入，不使用 debug 包或 unsigned 包。

若设备此前通过 Android Studio Run 安装过 debug 版，新正式签名与 debug 签名不同，不能直接覆盖。先在应用中导出完整 `.spendy` 加密备份并保存密码，再卸载 debug 版、安装正式版、恢复备份。卸载会清除应用本地账目；系统云备份已关闭。以后同一正式签名、同一包名且更高版本的 APK 可用于覆盖升级。

建议先在备用设备或模拟器检查正式包能否安装启动、保存账目及备份恢复，再正式上传。

## 4. 生成 GitHub 附件与更新清单

Spendy 内置更新固定读取 GitHub 最新正式 Release 的 `update.json` 和 `spendy.apk`。两个文件名须保持一致；只上传 APK 可以手动下载安装，但不能完成内置更新检查。

在 Android Studio 的 PowerShell Terminal 中，进入项目根目录后运行（将 `-ApkPath` 改成 Locate 找到的实际签名 APK 路径）：

```powershell
cd C:\workspace\Spendy
.\scripts\prepare-release.ps1 `
  -ApkPath 'C:\workspace\Spendy\app\release\app-release.apk' `
  -ReleaseNotes '首个公开版本：本地记账、账户管理、收支统计、CSV 与加密备份。'
```

脚本默认使用本机 SDK 的 `C:\develop\android\Sdk\build-tools\36.0.0`。换电脑时可通过 `-BuildToolsDirectory` 指定包含 `aapt.exe` 和 `apksigner.bat` 的目录。需要 Java 可从终端访问。

如果 Windows PowerShell 提示“此系统上禁止运行脚本”，用单独进程执行，不修改全局执行策略：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-release.ps1 `
  -ApkPath 'C:\workspace\Spendy\app\release\app-release.apk' `
  -ReleaseNotes 'Spendy 0.1.0'
```

脚本从 APK 本身读取版本与最低 SDK，验证 APK 签名、包名、单证书要求及文件大小，拒绝 debuggable 包和标准 Android debug 证书，再生成两个附件：

```text
C:\workspace\Spendy\app\build\outputs\github-release\spendy.apk
C:\workspace\Spendy\app\build\outputs\github-release\update.json
```

`update.json` 包含实际 APK 的 SHA-256、签名证书 SHA-256、版本及 UTC 生成时间；不含私钥或密码。脚本不进行签名，也不会提交或上传任何文件。运行前先完成签名构建；每次重新构建 APK 后重新生成清单，两个附件须匹配。文件位于被 Git 忽略的构建输出目录，不需要提交到代码仓库。

## 5. 在 GitHub 发布

1. 打开仓库 <https://github.com/caiheyu/Spendy>，进入 **Releases → Draft a new release / Create a new release**。
2. 选择或新建标签，例如首版 `v0.1.0`；**Target** 选择已经推送完整开发代码的 `master` 最新提交。发布标题可填 `Spendy v0.1.0`。
3. 填写更新说明，将上一步生成的 **`spendy.apk` 和 `update.json` 一起拖入附件区域**，等待两者上传完成。
4. 首个正式发布不要选择 **pre-release**；若显示 **Set as the latest release**，选中它。确认仓库公开，以便无 GitHub 凭据的应用访问更新附件。
5. 点击 **Publish release**。GitHub 自动附加的源码 zip / tar.gz 不是可安装应用，用户应下载附件中的 `spendy.apk`。
6. 从已发布页面重新下载 APK，确认可安装，并确认以下两个地址都能访问：
   - <https://github.com/caiheyu/Spendy/releases/latest/download/spendy.apk>
   - <https://github.com/caiheyu/Spendy/releases/latest/download/update.json>

首次在浏览器或文件管理器安装 APK 时，Android 可能要求为该来源开启“允许安装未知应用”。这与应用自身更新时为 Spendy 授予的安装来源权限分别管理。

同版本安装后，“检查更新”不会提示有新版本；只有后续正式 Release 的 `versionCode` 高于已安装版本且设备兼容时才提示。测试覆盖升级与应用内更新请使用同一正式密钥签名的旧版和新版，并在升级后确认账目仍在。

## 后续发布顺序

增加版本 → 测试 → 提交并推送代码 → 用原 keystore 生成 release APK → 运行清单脚本 → 新建对应标签的正式 Release → 上传两个附件 → 验证安装和更新。

## 官方参考

- [Android：为应用签名](https://developer.android.com/studio/publish/app-signing)
- [Android：apksigner](https://developer.android.com/tools/apksigner)
- [GitHub：管理仓库中的发布](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
