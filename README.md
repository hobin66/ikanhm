# ikanhm

漫小肆（ikanhm）图源插件，仅包含这一个图源。

## 源地址
`https://raw.githubusercontent.com/hobin66/ikanhm/repo/index.min.json`

## 支持的图源
- [漫小肆](https://www.ikanhm.top)

## 目录结构
- `src/zh/ikanhm/`：漫小肆图源实现（`Ikanhm.kt`）及模块配置
- `core/`：图源公用的基础工具（URL 配置等）
- `buildSrc/`、`gradle/`：构建脚本与依赖版本配置

## 构建
本地调试（使用自动生成的 debug 签名）：

```bash
./gradlew :src:zh:ikanhm:assembleDebug
```

正式发布由 GitHub Actions 工作流 `Build and Publish Plugin Repo` 执行 `assembleRelease` 并发布到 `repo` 分支，需要在仓库中配置以下 secrets：

- `KEY_STORE_FILE`：签名密钥库（base64）
- `KEY_STORE_PASSWORD`
- `ALIAS`
- `KEY_PASSWORD`
