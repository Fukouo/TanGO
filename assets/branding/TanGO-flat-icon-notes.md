# TanGO 图标素材

TanGO 使用团子主题角色作为应用图标。插画通过 AI 辅助生成，采用粗描边与浅色块设计。

| 文件 | 用途 |
| --- | --- |
| `TanGO-dango-flat-v2.png` | 透明背景角色源图 |
| `TanGO-icon-preview.png` | 圆角图标预览 |
| `TanGO-icon-round-preview.png` | 圆形图标预览 |

Android 使用的图标资源位于 `app/src/main/res/`。自适应图标使用独立前景与背景，兼容位图提供五档屏幕密度。

修改源图后，可使用 `scripts/prepare-icon.cjs` 重新导出尺寸与预览。导出需要 Node.js 和 `sharp`，具体步骤见 [开发文档](../../docs/DEVELOPMENT.md#图标资源)。
