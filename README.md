# 游戏安卓 App：五子棋 + 象棋 + 三国身份局

这个仓库里有三个原生安卓 App，都是用一个全屏 WebView 加载游戏页面，不需要联网也能玩。

| App | 模块目录 | 下载 |
| --- | --- | --- |
| 五子棋 | `app/` | [gomoku.apk](https://github.com/mronexie-cloud/GitHub/releases/latest/download/gomoku.apk) |
| 象棋 | `xiangqi/` | [xiangqi.apk](https://github.com/mronexie-cloud/GitHub/releases/latest/download/xiangqi.apk) |
| 三国身份局 | `sanguo/` | [sanguo.apk](https://github.com/mronexie-cloud/GitHub/releases/latest/download/sanguo.apk) |

**苹果手机版：** 用 Safari 打开 https://mronexie-cloud.github.io/GitHub/ ，选游戏后通过"分享 → 添加到主屏幕"安装。苹果版文件在 `ios/` 目录，和安卓版互不影响。

每次推送到 main 分支，GitHub Actions 都会自动编译这三个 APK，并更新上面的下载链接。

象棋 App 支持完整走法规则、人机对战（电脑会往后计算 4 步）和双人对战，被将军时手机会震动，同样会自动保存棋局。

三国身份局是单机五人局（你 + 4 名电脑），开局可以挑选身份和武将，出牌时有特效和牌名语音。牌名语音由 `tts/` 目录里的工作流用中文神经网络语音合成，已经嵌在游戏页面里。

五子棋 App 相比网页版多了几项功能：

- 桌面图标，竖屏全屏运行
- 落子时轻微震动，分出胜负时长震一下
- 自动保存棋局，退出后再打开会接着上次的局面
- 支持人机和双人两种模式，可以悔棋

## 打包成 APK

有两种方法，任选一种。

### 方法一：用 GitHub 自动打包（不用装任何软件）

1. 注册或登录 GitHub，新建一个仓库（Public 或 Private 都可以）。
2. 解压本压缩包，把里面的**全部文件**上传到仓库。
   - 一定要包含 `.github` 文件夹。它是隐藏文件夹，电脑上可能看不到，需要先打开"显示隐藏文件"。
3. 上传完成后，进入仓库的 **Actions** 页面，会看到 "Build APK" 自动开始运行，大约需要 3～5 分钟。
4. 运行完成后点进去，在页面底部的 **Artifacts** 区域下载 `gomoku-apk`。
5. 解压得到 `app-debug.apk`，发到手机上安装。
   - 安装时如果提示"未知来源"，在设置里允许安装即可。

### 方法二：用 Android Studio

1. 安装 [Android Studio](https://developer.android.com/studio)。
2. 选择 **Open**，打开这个文件夹，等待 Gradle 同步完成（第一次会下载依赖）。
3. 在菜单中选择 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
4. 生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 修改游戏

五子棋本体是 `app/src/main/assets/index.html`，象棋是 `xiangqi/src/main/assets/index.html`。改完重新打包就能生效。

- 应用名称：`app/src/main/res/values/strings.xml`
- 应用图标：`app/src/main/res/mipmap-*/ic_launcher.png`
- 包名：`app/build.gradle.kts` 里的 `applicationId`，当前是 `com.zlc.gomoku`

## 说明

- 生成的是 debug 版 APK，自己安装、分享给朋友都没问题。
- 如果要上架应用商店，需要另外生成签名的 release 版本。
