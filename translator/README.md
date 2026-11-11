# 全屏翻译

安卓屏幕实时翻译：悬浮球点一下 → 截屏 → 识别文字 → 翻译 → 译文盖在原文位置。

下载：[translator.apk](https://github.com/mronexie-cloud/GitHub/releases/download/translator/translator.apk)（64 位手机，安卓 8.0 及以上）

## 来源与许可

本目录基于开源项目 **屏译 / Screen Translator**
（https://github.com/ciddwd/overlay-translator ，提交 `64a7aa2`，版本 0.4.8，
Copyright 2026 ciddwd）修改，原项目采用 Apache License 2.0。
原始 `LICENSE` 和 `NOTICE` 原样保留在本目录。

相对原项目的改动：

- 应用名改为「全屏翻译」，包名改为 `com.zlc.translator`（可与原版屏译同时安装）
- 关闭了指向原项目的应用内更新检查，发布页改为本仓库
- 去掉原项目的 README、截图和 CI 配置，改由本仓库的
  `.github/workflows/translator.yml` 编译发布
- 端侧大模型依赖的 llama.cpp 以子模块形式挂在 `third_party/llama.cpp`

## 编译

这是独立的 Gradle 工程，和仓库里其他 App 分开编译：

```
git submodule update --init --recursive
cd translator && ./gradlew assembleRelease
```
