# 接入与业务适配

列表与短视频推荐使用可选 `player-ui` 模块，接入步骤见[简化接入说明](ui-integration.md)。本页保留核心 API 和业务适配说明。

## 从 Maven Central 接入

在宿主 `settings.gradle.kts` 中配置仓库（已有则无需重复添加）：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

在应用模块的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation("io.github.silascui:sloth-ui:1.0.0") // 自动引入同版本核心
    // 只需核心 API 时改用：
    // implementation("io.github.silascui:sloth:1.0.0")
}
```

同步 Gradle 后即可使用下文的核心 API，列表和短视频使用[简化接入说明](ui-integration.md)。公开依赖无需 Central Token 或 GPG 密钥；这些凭据仅用于维护者发布。版本须在 Central Portal 达到 `PUBLISHED` 状态，Actions 上传成功后仍需手动确认发布，详见[发布指南](publishing.md)。

## 使用本地分发产物

将`dist/sloth-1.0.0-maven.zip`解压到宿主的`vendor/sloth/`，在宿主settings的依赖仓库中声明本地maven目录与公共Android仓库：

```kotlin
repositories {
    maven { url = uri(rootDir.resolve("vendor/sloth/maven")) }
    google()
    mavenCentral()
}
```

宿主应用依赖：

```kotlin
implementation("io.github.silascui:sloth-ui:1.0.0") // 包含核心；只需核心时改用 sloth
```

先按 README 构建本地分发包。本地仓库与 Central 使用相同坐标；生成本地包不会执行远程发布。直接Media3依赖以Gradle模块元数据锁定；单AAR方式需宿主显式对齐版本与依赖。

源码接入时，保留本工程自己的版本目录，通过组合构建避免宿主libs别名冲突：

```kotlin
includeBuild("vendor/sloth") {
    dependencySubstitution {
        substitute(module("io.github.silascui:sloth")).using(project(":player"))
        substitute(module("io.github.silascui:sloth-ui")).using(project(":player-ui"))
    }
}
```

## 数据、权限和统计

宿主将自身网络响应转换为`VideoMedia`；逻辑ID用于会话恢复，URL版本参与sourceKey，签名URL更换不会误用旧source。媒体描述要求有效ID与URL；不可播放内容由`playable=false`阻止进入播放和预加载，未获得URL的业务卡片可以留在宿主UI，暂不注册播放项。

授权对接使用`PlayerConfiguration.playbackPolicy`：返回true表示允许；等待弹窗时返回false。宿主完成授权后先更新自己的授权状态，再在主线程调用`onAllowed`，且建议先检查`isCurrent()`。SDK还会核对请求版本、页面可见性与当前宿主，防止旧授权回调启动别的内容。重试保留原来手动播放意图，关闭自动播放时仍能执行已授权的手动请求。

`autoplayAllowed`决定自动播放及预加载是否可执行。需要用户同意后允许流量自动播放的应用，应让该函数读取更新后的同意状态；不要只更新弹窗状态而一直返回false。登录、订阅、流量设置、弹窗、提示文案和埋点协议都属于宿主，不在SDK内存储或更改。

上游请求头、令牌刷新、网络栈、重试传输策略通过`upstream(Context):DataSource.Factory`提供。SDK沿用原缓存路径进行读取，不输出URL和头部；不要把密钥写入配置样例、日志或本地源码包。

原埋点适配为`PlaybackAnalytics`：每次正式prepare创建一个实例，`onAttached`收到实际状态、playWhenReady及选中耗时，`onDetached`归还统计会话；可提供一个稳定的Media3 AnalyticsListener。实例身份在attach/detach间保持一致。不要把备用引擎的READY或静音预渲染计作正式播放。

其他视频SDK或旧浮窗可通过`beforeAcquire`结束资源。全局音频在开始前调用`interruptForAudio()`；希望可听视频开始时结束旧音频，可以在`PlaybackEvents.onAudiblePlaybackStarted()`适配应用协调器。默认不访问任何业务全局单例。

## 页面和UI

- Holder只注册/注销`FeedRenderTarget`并加载封面，不维护播放器集合。
- 页面可见时`setVisible(true)`，不可见时false，销毁时`close()`。View重建后先submit数据、restoreState，再开始可见播放。
- 生命周期与真实可见性都要接入；位于导航栈后方但仍STARTED的Fragment不能仅依靠Activity onStart判断可见。
- 页面进入详情可以创建SingleVideoController，Runtime自动仲裁旧页面归属，不能各自创建运行时或缓存。
- 自定义UI实现FeedRenderTarget；只有渲染层接收Player代理，业务操作通过PlaybackHandle。默认UI仅提供封面、点击暂停/继续和延迟加载指示，点赞、评论、字幕、全屏、付费和手势交互由宿主组合。
- 浮窗调用transferToFloating需要宿主已成功创建窗口；权限和WindowManager归宿主。浮窗持有自己的新句柄，旧列表句柄失效。

Host回调应短小且不抛异常；SDK不通过吞掉异常掩盖业务适配错误。全局配置不得捕获短生命周期Activity/View。退出应用级播放器能力前关闭所有Controller与浮窗句柄，再shutdown；不要把shutdown作为普通列表滚动操作。

未声明支持多播放器内核、后台音频Service、系统MediaSession/Cast、安全DRM输出、下载管理器或跨平台播放器。本工程只承诺已有Media3路径的封装边界；新增这些能力需要独立设计与验证。
