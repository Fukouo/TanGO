# TanGO 当前头像素材

使用内置 image_gen 生成，非CLI/API Key方式。当前团子头像经过参考风格重绘及轻量优化，保留原有身份与表情。

- `TanGO-dango-flat-v2.png`：当前实际使用的透明头像原图。
- `TanGO-icon-preview.png`：当前圆角图标预览。
- `TanGO-icon-round-preview.png`：当前圆形图标预览。
- Android图标资源位于 `app/src/main/res/`，由 `scripts/prepare-icon.cjs` 机械缩放导出。

按用户要求，旧头像和旧界面预览已清理。以下提示词保留为当前素材的生成来源记录，其中所说的首轮图片与参考图片不再存放于项目中。

## 首轮提示词

Edit the character in image 2 into the drawing style of image 1. Image 1 is a STYLE REFERENCE ONLY, image 2 is the IDENTITY TO REDRAW. Deliver a single square Android avatar foreground, genuinely transparent background, no lettering. Match image 1's extremely simplified, chunky hand-drawn chibi sticker look: thick near-black ink contours with slight natural asymmetry, broad flat pastel color regions, enormous simple oval eyes with a single white oval highlight, tiny playful mouth, oversized round head, very few inner hair lines, only one hard-edged shadow color per material. Keep the original TanGO girl's cream/pale pink hair, two ROUND DANGO HAIR BUNS, muted green bows, olive eyes, green-and-cream outfit, and one three-ball pink/white/green dango skewer. Make her expression a little cheeky and relaxed rather than an exaggerated sparkling smile. Simplify the outfit to two or three large shapes and a plain collar; remove flowers, gold hardware, tassels, ruffles, fancy ornaments, strands and micro-details. Head and shoulders, head about 75 percent of visible silhouette, skewer and one very simple mitten-like hand near bottom. A few big bangs, confident heavy black outlines throughout. Absolutely no gradients, no glossy or translucent hair, no shiny bead rendering, no complex iris, no airbrush blush, no soft lighting, no painterly rendering, no 3D. The finished result should look like a casual clean digital doodle/sticker by a human fan artist, not a highly polished anime illustration. Do not copy the blue-haired character, ducks, frog, cat, hat, caption or any other extra objects from image 1. Center the entire silhouette on a square canvas with a little transparent padding for icon export. No frame, no logo, no text, no watermark.

## 轻量优化提示词

Image 1 is the accepted avatar to refine. Image 2 is a style reference for FLAT COLOR only. Make a very restrained cleanup of IMAGE 1: preserve the exact character identity, cream-pink hair, two large round hair buns and green bows, tilted head, chunky black silhouette, olive oval eyes with single white highlights, the same little smiling mouth, cheek dots, simple green-and-cream collar, the pink/white/green dango skewer, overall pose and composition. Do not redesign the face or add any objects. The only improvements: replace ALL soft gradients and airbrushed lighting in hair, skin, eyes, bows and dumplings with solid uniform flat colors; at most one extra flat hard-edged shadow tone per material, like image 2. Simplify a few redundant inner hair strokes so the bangs read as 4 or 5 large clear chunks at small icon size. Keep the thick hand-drawn slightly uneven black outline; not a geometric corporate logo. Soft cream hair and skin must have absolutely uniform base fill, no glow, no translucent sheen, no gloss, no scattered shine marks, no blush haze, no rendered bead lighting. Keep both eyes and the entire face layout essentially unchanged. Original charming doodle/sticker illustration. True transparent background around the entire existing silhouette. Square canvas, preserve all existing subject parts and leave a little transparent margin; no text, no watermark, no border, no extra character.

提示词是生成要求，不代表每个像素都严格满足；最终以实际图片为准。
