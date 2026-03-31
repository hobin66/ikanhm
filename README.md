# 漫小肆韩漫 (Ikanhm) - Mihon/Tachiyomi 插件源

这是一个基于 [Keiyoushi Extensions Source](https://github.com/keiyoushi/extensions-source) 模板简化的单源编译项目。它内置了 GitHub Actions，无需任何本地编译环境（例如不需要配置 Java 和 Android Studio），就可以全自动地为您云端编译并发布一个您可以直接添加到 Mihon/Tachiyomi 中的自定义插件源！

## 🚀 如何使用（添加到您的阅读器）

此仓库一旦经过 GitHub Actions 自动编译（当您 push 到 `main` 之后，后台会自动运行名叫 *Build and Publish Plugin Repo* 的任务），就会自动生成并推送一个带有最新插件地址的 `repo` 分支。

要在您的 Mihon 中添加此漫画源：
1. 打开您的 Mihon 软件
2. 进入 **更多 (More)** -> **设置 (Settings)** -> **浏览 (Browse)** -> **插件仓库 (Extension repos)**
3. 点击右下角的 **添加 (+)**，并**复制粘贴以下地址**：

```text
https://raw.githubusercontent.com/hobin66/ikanhm/repo/index.min.json
```

4. 添加成功后，回到 **浏览 (Browse)** -> **插件 (Extensions)** 界面，刷新一下，您就能在列表中看到并安装 `漫小肆韩漫 (Ikanhm)` 插件了！

> **注意：** 只有当您在 GitHub 的 Actions 页面看到绿色的编译成功标志（✅）后，以上的链接才会正式生效并包含最新编译好的插件。

---

## 🛠️ 关于代码修改
如果您想要对代码进行修改，或者网站域名/结构发生了变化：
- 插件的解析逻辑代码位于 `src/zh/ikanhm/src/eu/kanade/tachiyomi/extension/zh/ikanhm/Ikanhm.kt`
- 如果您修改了上述文件并提交至仓库（Push 任何改动至 `main` 分支），GitHub Actions 会在 2~3 分钟内帮您自动打包新版本，您的手机端也会紧接着收到插件**更新通知**。
