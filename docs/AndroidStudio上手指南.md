# Android Studio 上手指南（Wherever Play）

> 面向：项目作者本人。目的：能独立打开工程、跑起来、看懂代码、给我反馈。
> 环境实况采集时间：2026-10-24

---

## 0. 你的环境实况

| 项 | 实际值 |
|---|---|
| Android Studio | **2026.2.1**（build AI-262.9437.185.2621） |
| 安装路径 | `D:\apps\android\android studio\studio64.exe` |
| 内置 JDK（JBR） | 25.0.3 |
| 系统 JDK | 21.0.10 |
| Android SDK | `D:\apps\android\SDK` |
| 已装 SDK Platform | **仅 android-37.0** |
| 已装 build-tools | 36.0.0 |
| Gradle 发行版缓存 | `gradle-9.6.0`（已下载，无需再下） |
| 依赖缓存状况 | 仅基础 androidx；**Compose / Media3 / Room / Coil / Haze 均无缓存** |
| 已连接设备 | 无（`adb devices` 为空） |

结论：**首次 Sync 必须联网**。我会在工程里配阿里云 Maven 镜像，避免连 Google Maven 超时。

---

## 1. 打开工程（关键：选对目录）

1. 启动 Android Studio（开始菜单搜 "Android Studio"，或直接双击
   `D:\apps\android\android studio\bin\studio64.exe`）。
2. 欢迎界面点 **Open**（已开着别的工程就用菜单 `File → Open`）。
3. 在文件选择框里粘贴并进入：`D:\project\android\wherever play`
4. **选中的必须是 `wherever play` 这一层**（这个目录里会有 `settings.gradle.kts`、`gradlew`、
   `app\` 等文件）。点 OK / Open。
5. 如果弹出 "Trust and Open Project?" → 选 **Trust Project**。

### ⚠️ 两个必须避免的操作

| 错误做法 | 后果 |
|---|---|
| 打开 `D:\project\android`（上一级） | Android Studio 会把 `demo` 和 `wherever play` 当成同一工程的子目录，两个 Gradle 工程互相打架，Sync 必失败 |
| 打开 `D:\project\android\wherever play\docs` | 这里只有 md 文档，不是工程，AS 会当成普通文件夹 |

`demo` 目录和 `zh-262.10315.125.jar` 我不会改动，你也不必在 AS 里打开它们。

---

## 2. 首次 Sync 会发生什么

打开后 AS 会自动开始 **Gradle Sync**（底部状态栏显示进度）。

- Gradle 9.6.0 已有缓存 → 跳过下载。
- 依赖（Compose、Media3、Room、Coil 等）需要联网下载，**首次约几百 MB**。
- 国内直连 Google Maven 常超时，所以工程里已配镜像（`settings.gradle.kts`）：

```kotlin
// 阿里云镜像优先，google()/mavenCentral() 兜底
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/public") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```

### 常见报错与处理

| 报错 | 原因 | 处理 |
|---|---|---|
| `Could not resolve com.android.tools.build:gradle` | 网络/镜像问题 | 检查网络；打开 Clash Verge 后 `File → Settings → Build → Gradle` 里勾选使用代理；或再点 `Try Again` |
| `SDK location not found` | 缺 `local.properties` | AS 通常自动生成。若没有，在仓库根目录建 `local.properties`，写一行 `sdk.dir=D\:\\apps\\android\\SDK` |
| `Failed to find target with hash string 'android-37'` | 缺 SDK Platform | `Tools → SDK Manager` → 勾选 Android 37 → Apply（你已有 android-37.0，一般不会遇到） |
| `Unsupported class file major version` / JDK 相关 | Gradle JDK 选错 | `File → Settings → Build, Execution, Deployment → Build Tools → Gradle` → **Gradle JDK** 选 `jbr-25 (JetBrains Runtime)` 或已装的 JDK 21 |
| `Build tools 37.x missing` | AGP 想要更新版 build-tools | 按提示点 `Install missing SDK package(s)` 即可 |

Sync 成功后，右下角不再转圈，左侧出现 **Project 视图**，能看到 `app` 目录。

---

## 3. 把 Demo 跑到设备上

### 方式 A：真机（推荐，必须做）

Demo 的核心是 PDF 渲染与音频同步，**模拟器测不出真实性能**，真机才能验证翻页是否迟滞。

1. 手机开启开发者模式：`设置 → 关于手机` → 连点「版本号」7 次。
2. `设置 → 系统 → 开发者选项` → 打开 **USB 调试**。
3. 数据线连电脑，手机上弹出「允许 USB 调试吗？」→ 勾选「始终允许」→ 确定。
4. AS 顶部工具栏的机型下拉框里应出现你的手机；没有就点它右侧的刷新。
5. 点绿色三角 **▶ Run 'app'**。

### 方式 B：模拟器（只看界面，不测性能）

1. `Tools → Device Manager` → **Create Device**。
2. 选手机型号（如 Pixel 7）→ 系统镜像选 **Android 37**（你已装 `android-37.0`）。
3. 完成后点 ▶ 启动模拟器。
4. 顶部机型下拉选该模拟器 → Run。

### 装好之后

桌面上出现名为 **Wherever Play** 的图标，点击即可运行（不必每次都从 AS 启动）。

---

## 4. 在 Android Studio 里怎么看代码

### 视图切换

左上角 Project 面板顶部有个下拉，默认是 `Android` 视图；改成 **`Project`** 视图能看到真实磁盘结构，和我描述的文件路径一致。

### 关键目录（我写代码后会出现在这些位置）

```
wherever play/
├── settings.gradle.kts          # 仓库源与模块声明
├── build.gradle.kts             # 顶层构建配置
├── gradle/libs.versions.toml    # 依赖版本集中管理（想改版本看这里）
├── local.properties             # SDK 路径（本机专用，不要提交）
├── docs/                        # 需求确认稿与本指南
└── app/
    ├── build.gradle.kts         # 应用模块配置（包名、SDK 版本、依赖）
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/whereverplay/app/
        │   ├── data/            # Room 实体、DAO、Repository
        │   ├── pdf/             # PdfRenderer 封装、页缓存、预渲染
        │   ├── player/          # Media3 播放器封装
        │   ├── ui/              # 各页面（Compose）
        │   └── MainActivity.kt
        └── res/                 # 图标、字符串、主题
```

### 三个能帮你「验收我写的代码」的窗口

| 功能 | 位置 | 用途 |
|---|---|---|
| **Database Inspector** | `View → Tool Windows → App Inspection` → 选 Database Inspector | 运行时实时查看 Room 表：项目表、打点表（能直接看到你打的点对不对） |
| **Device Explorer** | `View → Tool Windows → Device Explorer` | 查看 App 私有目录里的音频/PDF 是否真被复制进去了（验证存储设计） |
| **Logcat** | `View → Tool Windows → Logcat` | 看运行日志。过滤框输入 `package:com.whereverplay.app` 只看本应用；我打的性能日志会带 `WP-` 前缀（如 `WP-PDF`、`WP-SYNC`） |

---

## 5. 怎么给我反馈（最省时间的三种）

1. **出错截图**：AS 右下角报错、或手机上的崩溃弹窗，直接截图发我。
2. **Logcat 文本**：在 Logcat 面板里选中相关行 → 右键 `Copy` 粘贴给我（比截图信息量大）。
3. **行为描述**：例如「第 3 页翻到第 4 页时晚了大概半秒」——带时间感知的描述对调参极有用。

---

## 6. 常用操作速查

| 想做 | 操作 |
|---|---|
| 重新 Sync | 右上角 `Sync Project with Gradle Files`（大象图标） |
| 强制重编译 | `Build → Clean Project`，再 `Build → Rebuild Project` |
| 只装到手机不启动 | `Run → Install 'app'`（或 Gradle 面板 → app → installDebug） |
| 看构建产物 | `app/build/outputs/apk/debug/app-debug.apk` |
| 卸载 App | 手机长按图标卸载，或命令行 `adb uninstall com.whereverplay.app` |
| 清 App 数据（重置到空状态） | 手机 `设置 → 应用 → Wherever Play → 存储 → 清除数据` |

---

## 7. 我能做什么、不会做什么

- **只会写** `D:\project\android\wherever play` 目录内的文件。
- 不会修改 `D:\project\android\demo`、不会动 `zh-262.10315.125.jar`。
- 不会擅自卸载/安装你机器上的软件；如需装 SDK 组件会先告诉你。
- 代码里会留中文注释，关键决策点标注 `// WP:` 前缀，方便你搜索审阅。
