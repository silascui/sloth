# 发布到 Maven Central

本工程使用 Central Portal，命名空间为 `io.github.silascui`，源码仓库为 https://github.com/silascui/sloth。

## 坐标和产物

- 核心：`io.github.silascui:sloth:1.0.0`
- UI 接入层：`io.github.silascui:sloth-ui:1.0.0`，自动依赖同版本核心。

Maven Central 已公开提供上述两个模块的 `1.0.0`。已发布版本不可覆盖；本仓库后续的包名迁移等改动需使用新版本号发布，本地重新生成的 `1.0.0` 分发包不会更新 Central 上的现有版本。

版本统一在根目录 `gradle.properties` 的 `VERSION_NAME` 中维护；修改版本后 README 和接入文档中的示例也应同步。两个库均发布 Release AAR、sources JAR、包含现有 Markdown 接入及架构说明的 javadoc JAR、POM 和 Gradle 模块元数据。文档包使用现有维护文档，不是自动生成的 Kotlin API 参考。

POM 依据仓库根目录 LICENSE 声明 Apache-2.0；NOTICE.md 仍有上游来源和对外分发授权说明，正式发布前应确认这些声明与实际授权一致。

## 本地验证，不上传

使用 JDK 21、Android SDK 35：

```sh
./gradlew :player:testDebugUnitTest :player-ui:testDebugUnitTest \
  :player:publishReleasePublicationToBundleRepository \
  :player-ui:publishReleasePublicationToBundleRepository
python3 -B tools/package_framework.py
```

无需发布凭据或签名密钥。Maven 文件位于 `player/build/bundle-repository/io/github/silascui/`，分发 ZIP 位于 `dist/`。源码分发包不包含 `docs/verification/*.log` 历史验收日志；原始日志保留在 Git 仓库中供追溯。

## 准备凭据和签名

1. 在 [Central Portal](https://central.sonatype.com/) 的账号页面生成 User Token，保存生成的 username 和 password。这里使用发布 Token，不是 GitHub 密码。
2. 使用 GPG 创建签名密钥，将公钥上传至 Central 支持的公共密钥服务器。详见 [Sonatype GPG 指南](https://central.sonatype.org/publish/requirements/gpg/)。
3. 导出 ASCII 格式私钥到仓库之外的本机安全目录，供 Secrets 使用。导出的完整内容包含 BEGIN/END 行；不要把私钥或口令放入 Git。

例如，`KEY_FINGERPRINT` 替换为实际完整指纹：

```sh
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys KEY_FINGERPRINT
# 将路径替换为仓库之外的私有目录；此文件是私钥。
gpg --armor --export-secret-keys KEY_FINGERPRINT > /path/outside/repository/signing-private.asc
```

## GitHub Actions 上传

在 [仓库 Actions Secrets](https://github.com/silascui/sloth/settings/secrets/actions) 添加：

| Secret | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central User Token 生成的 username |
| `MAVEN_CENTRAL_PASSWORD` | Central User Token 生成的 password |
| `SIGNING_IN_MEMORY_KEY` | 完整 ASCII 格式 GPG 私钥，保留换行 |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | GPG 私钥口令；无口令时可留空 |

Token 只填写标签内的值。例如 Portal 显示 `<username>abc123</username>` 和 `<password>xyz789</password>` 时，两个 Secret 分别填写 `abc123` 和 `xyz789`，不要包含 XML 标签、引号或额外空格。用户名和密码必须来自同一次生成的 User Token。

将配置推送到默认分支后，在 Actions 选择 **Upload to Maven Central**，点击 **Run workflow**。工作流会验证测试和本地分发，再上传两个已签名模块。上传完成后进入 [Central Deployments](https://central.sonatype.com/publishing/deployments)，检查验证结果，点击 **Publish**。工作流上传成功不代表版本已经公开可下载。

两个模块在同一次 Gradle 调用中上传，避免 UI 模块单独上线而找不到核心。正式版本发布后不可覆盖；下一次发布需递增 `VERSION_NAME`。本工作流仅支持手动启动，不会在普通 push 时自动发布。

## 上传后的验证与接入

1. 在 Central Deployments 检查本次所有部署记录。状态为 `VALIDATED` 时点击 **Publish**，等待变为 `PUBLISHED`；校验失败则查看 Validation Results。
2. 在另一个 Android 项目中按[接入文档](integration.md#从-maven-central-接入)配置 `mavenCentral()` 和 `1.0.0` 依赖，不添加本地分发仓库或源码替换，以验证远程依赖可解析。
3. 同步 Gradle、编译并运行播放页面，确认核心和 UI 模块正常接入。

状态说明与手动发布流程见 [Central Portal 官方指南](https://central.sonatype.org/publish/publish-portal-guide/)。

## 常见上传错误

`Upload failed: {"error":{"message":"Invalid token"}}` 表示 Central 拒绝了上传凭据。检查两个 Token Secret 是否误带 XML 标签、是否来自同一次生成，以及 Token 是否仍有效；必要时重新生成 User Token，同时更新 `MAVEN_CENTRAL_USERNAME` 和 `MAVEN_CENTRAL_PASSWORD` 后重新运行工作流。

同一日志中的 `Failed to stop service 'maven-central-build-service'` 可能是收尾上传时报出的外层异常，应继续查看其后的具体错误。Node.js 或 Gradle 弃用警告本身不能说明上传失败原因。

## 本机上传

也可以在安全的本机终端设置以下环境变量，值与上面的 Secrets 一致：

- `ORG_GRADLE_PROJECT_mavenCentralUsername`
- `ORG_GRADLE_PROJECT_mavenCentralPassword`
- `ORG_GRADLE_PROJECT_signingInMemoryKey`
- `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`

然后执行：

```sh
./gradlew --no-daemon -PcentralPublishing=true \
  :player:publishToMavenCentral :player-ui:publishToMavenCentral
```

随后仍需在 Central Portal 检查并点击 Publish。`centralPublishing=true` 会同时开启 Central 上传任务和强制签名；日常构建与本地打包不需要这个参数。

配置依据：[Vanniktech 发布指南](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)。插件锁定为与当前 Gradle 8.14.3、AGP 8.7.3 和 Kotlin 2.1.0 兼容的 0.35.0；升级插件前应检查最低工具链要求。
