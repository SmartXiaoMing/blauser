# blauser

> 功能说明、设计取舍、调试技巧都在 [README.md](README.md)。
> 这份文件只放「开工前扫一眼」的速查信息，刻意不重复 README 的内容 ——
> 之前两边各写一份，改了一边忘另一边，就出现了指向不存在文件的死链。

## 项目信息

- **名称**：blauser
- **包名 / applicationId**：`com.blauser.browser`
- **目录**：`/Users/mi/work/src/blauser/`
- **目的**：将任意 PC 网页自动缩放适配到手机屏幕，无需修改网站代码

## 技术栈

- Android + Kotlin + WebView + viewBinding
- 注入 viewport JS 完成缩放，固定档位下同步伪装 `window.screen`
- 已注册为系统默认浏览器（Intent Filter http/https）
- compileSdk 34 / targetSdk 34 / minSdk 21
- AGP 8.5.2 + Gradle 8.7（wrapper）+ Kotlin 1.9.24，JDK 17+

## 核心文件

```
app/src/main/java/com/blauser/browser/
├── MainActivity.kt          # 一个实例 = 一个标签页；生命周期、覆盖层、回调分发
├── TabRegistry.kt           # 标签 = task 的枚举 / 切换 / 关闭 + 各标签状态登记
├── WebViewFactory.kt        # 唯一构造 WebView 的地方（WebSettings + 两个 client）
├── PageScaler.kt            # viewport 缩放与 screen 伪装
├── UrlHelper.kt             # 地址栏输入解析（纯函数，有单测）
├── SettingsManager.kt       # 全局设置持久化
├── SiteSettingsManager.kt   # 按域名覆盖全局设置
├── SslExceptionStore.kt     # 已接受的无效证书（域名 + 指纹，可撤销）
├── SettingsDialogs.kt       # 全局设置 / 本站设置两个面板
├── PageActions.kt           # 收藏 / 历史 / 复制 / 分享 / 桌面快捷方式 / 审查元素
├── DownloadHandler.kt       # 下载（走系统 DownloadManager）
├── Incognito.kt             # 无痕标签的多 profile 隔离（Android 9+）
├── FloatingBallView.kt      # 半球悬浮球与下拉菜单（菜单项数据驱动）
├── BookmarkManager.kt       # 收藏夹存储（含旧数据迁移）
├── HistoryManager.kt        # 浏览历史（去重置顶 + 上限截断，有单测）
└── UrlListDialog.kt         # 通用网址列表对话框（收藏夹 / 历史 / 证书例外共用）
```

## 常用命令

```bash
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（R8 + 资源压缩）
./gradlew testDebugUnitTest      # 单元测试
./gradlew lintDebug              # 静态检查（有 error 会中断构建）
```

`local.properties` 需指向本机 SDK，发布签名口令也在这里读：

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
RELEASE_STORE_FILE=../release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

签名项不配也能构建，会自动回落到 debug 签名。

## 调试技巧

WebView 远程调试（debug 包自动开启，release 包关闭）：

```bash
adb forward tcp:9222 localabstract:webview_devtools_remote:$(adb shell pidof com.blauser.browser)
curl -s http://localhost:9222/json    # 列出所有 WebView 页面目标
```

每个标签页是一个独立的 page target，可借此在页面里执行 JS 验证状态是否被保留。
