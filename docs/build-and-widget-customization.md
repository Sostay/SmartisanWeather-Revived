# 构建、签名与桌面小组件定制

本文档说明本 fork 的 GitHub Actions 构建链路、Release 签名注入方式，以及桌面小组件视觉定制的调节入口。README 中的免责声明与原版权说明保持不变。

## 一、Fork 与远端

本仓库是 [Mangi-11/SmartisanWeather-Revived](https://github.com/Mangi-11/SmartisanWeather-Revived) 的 fork，远端约定为：

| 远端 | 地址 | 用途 |
| --- | --- | --- |
| `origin` | `https://github.com/<你的用户名>/SmartisanWeather-Revived.git` | 自己的 fork，日常推送目标 |
| `upstream` | `https://github.com/Mangi-11/SmartisanWeather-Revived.git` | 原仓库，只读，用于同步上游更新 |

首次配置（在 fork 仓库根目录执行）：

```bash
git remote rename origin upstream
git remote add origin https://github.com/<你的用户名>/SmartisanWeather-Revived.git
git push -u origin main
```

同步上游：

```bash
git fetch upstream
git merge upstream/main
```

## 二、GitHub Actions 自动构建

工作流文件为 `.github/workflows/android-release.yml`，触发条件：

- 手动触发（Actions 页面 → Run workflow）
- 推送到 `main` 分支
- 推送 `v*` 标签
- 向 `main` 发起 Pull Request（此时仅构建、缓存只读，不发布 Release）

执行 `testDebugUnitTest lintDebug assembleDebug assembleRelease`，产物：

- `app/build/outputs/apk/**` 下的全部 APK 统一复制到 `dist/`，作为构建产物（artifact）上传，保留 30 天
- 推送 `v*` 标签时额外创建 GitHub Release 并上传 APK

推标签即可发版：

```bash
git tag v0.3.1
git push origin v0.3.1
```

Release 签名在未配置 Secrets 时不会启用，本地构建与 CI 的 Debug/Release 均照常产出，只是 Release APK 为未签名状态。

## 三、Release 签名

签名配置集中在 `app/build.gradle.kts`，读取四个 `RELEASE_*` 属性，任一为空即视为「未配置签名」：

| Gradle 属性 | 环境变量 | GitHub Secret |
| --- | --- | --- |
| `RELEASE_STORE_FILE` | `RELEASE_STORE_FILE` | 无（由工作流写入 `$RUNNER_TEMP/release.jks`） |
| `RELEASE_STORE_PASSWORD` | `RELEASE_STORE_PASSWORD` | `RELEASE_STORE_PASSWORD` |
| `RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` |
| `RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` |
| — | — | `RELEASE_KEYSTORE_BASE64`（keystore 的 base64 编码） |

配置步骤：

1. 生成 keystore（在项目目录之外执行，避免误提交）：

   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
     -validity 10000 -alias release
   ```

2. 编码为 base64：

   ```bash
   # Linux / macOS
   base64 -w 0 release.jks

   # Windows PowerShell
   certutil -encode release.jks release.b64   # 再手动去掉首尾的 ---- 头尾行
   ```

3. 在 fork 仓库 **Settings → Secrets and variables → Actions → New repository secret** 中新建上表中的四个 Secret。

4. 推送任意提交或手动触发工作流，日志中的 “Report signing configuration” 会打印每个 Secret 是 `set` 还是 `missing`；“Verify release signature” 会用 `apksigner` 打印实际证书，未签名时给出警告。

> keystore 与密码不要写进任何提交文件。签名始终是可选能力：Secrets 缺失时 `signingConfig` 为 null，构建照常进行。

## 四、桌面小组件视觉定制

桌面小组件由 Glance 1.1.1 渲染，没有 XML 布局（`res/layout/weather_widget_compact.xml` 仅供系统小组件选择器预览）。改动集中在以下几处。

### 1. 配色：改一个文件即可

`app/src/main/res/values/weather_widget_colors.xml` 集中了全部小组件视觉数值：

| 颜色 | 作用 | 调节建议 |
| --- | --- | --- |
| `weather_widget_artwork_scrim` | 压暗原版天空 NinePatch 的黑色蒙版 | **对比度总开关**。原版 `bg_weather_info_*.9.png` 是很浅的蓝色渐变，而组件文字为纯白；没有这层蒙版时对比度仅约 1.9:1，几乎无法阅读。`#59000000`（约 35%）可让主温度达到 3:1 的大字号标准。想要更接近原版观感就调小（`#33000000`），想要更清晰就调大（`#73000000`） |
| `weather_widget_panel_fill` | 预报条玻璃卡底色 | 保持很浅。底色越亮，浅色天空上的白字越难看清 |
| `weather_widget_panel_stroke` | 预报条描边 | 想让卡片边界更清晰就调大 |
| `weather_widget_action_pressed` | 刷新按钮按下反馈 | 原版默认态全透明，仅按下时显形 |

背景的负 inset（`-8dp`，`res/values/weather_widget_dimens.xml`）用于让原版卡片美术溢出 Android 12+ 的系统小组件圆角外框，属于系统适配，不建议改动。

### 2. 排版与尺寸断点

`app/src/main/kotlin/com/smartisan/weather/appwidget/WeatherWidgetModels.kt` 中的 `WeatherWidgetLayoutSpec.fromSize()` 是唯一的排版入口。原版 RemoteViews 布局对主温度使用 `autoSizeTextType`（32sp–44sp）自适应缩放，Glance 没有 autosize，因此这里用 Kotlin 复现同一契约：

1. 由 `LocalSize` 得到精确的宽高；
2. 减去必须保留的行：外边距、页头 30dp、间距、天气行 18dp，以及当前放得下的可选行（meta 块、AQI 行）；
3. 剩余高度 ÷ 1.2（行高比）得到主温度字号，钳制在 **32–44sp**；
4. 再次用剩余空间决定可显示的附加观测量行数（每行 14dp，最多 3 行）。

断点因此由算式决定而非魔法数字：矮组件自动让出 AQI 行以保住主温度，高组件则逐级放大字号并补出附加行。`WeatherWidgetModelsTest` 覆盖了紧凑、宽屏两种形态在多个尺寸下的取值。

### 3. 信息内容

`WeatherWidgetContentFactory.create()` 负责从 `Weather` 中挑选可展示的字段，遵循两条硬性规则：

- **字段不存在就不显示，绝不补造。** 全球 AccuWeather 城市通常不返回 `feelsLike` / `humidity`，此时这些行直接不出现。
- **编码值必须先映射再显示。** `Observe.wind` 是原版风向编码（0–11），`Observe.speed` 是蒲福风级编码，都不能原样打印；风向走 `ResMappingUtil.getWindDirRedId()`，与主页面 `WeatherForecast.kt` 的处理方式一致。

新增任何 `weather_widget_*` 字符串时，必须同时补齐 `res/values/`、`res/values-zh-rCN/`、`res/values-ja-rJP/` 三个语言目录，否则非中文环境会回落到 key 名称。

### 4. 验证要求

上述改动大量涉及组件尺寸与 Insets，按项目约定必须在模拟器或真机实际验证：至少覆盖 `2×2`、`3×2`、`4×2` 三种布局以及 `4×3` 以上的大尺寸，确认主温度不被裁切、附加行按预期出现或消失、圆角与系统外框不冲突。CI 只能验证编译、单元测试与 Lint，不能替代这一步。
