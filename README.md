中文 | [English](./README_EN.md)

# Manga Translator 📖

面向安卓的漫画翻译 App：本地气泡检测与 OCR，结合 OpenAI 兼容接口完成翻译，并在原图上覆盖显示可拖动的翻译气泡。并支持屏幕翻译/悬浮窗翻译，可在任意 App 或桌面上直接识别并翻译当前屏幕中的漫画文本。

使用教程：[简中教程](./Tutorial/简中教程.md)

| 原图 | 翻译结果 |
|------|----------|
| ![原图](./Tutorial/FirePunch.webp) | ![翻译结果](./Tutorial/translated.webp) |


## 主要功能 ✨
- 日译中，英译中
- 屏幕翻译：支持悬浮窗翻译，在任意界面识别并翻译当前屏幕内容
- 漫画库管理：新建文件夹、批量导入图片、漫画文件夹导入，支持CBZ、ZIP、PDF导入导出
- 翻译流程：气泡检测 + OCR + LLM 翻译，支持标准模式与全文速译
- 阅读体验：翻译覆盖层、翻译气泡位置可拖动、阅读进度自动保存
- 译名表与缓存：按文件夹维护 glossary.json，自动累积固定译名
- 更新与日志：启动检查更新，翻译期间前台服务与日志查看
- 多供应商负载：支持配置多个翻译供应商，按权重自动均衡负载

## 支持的翻译语言 🌐
- 源语言：日文、英文
- 目标语言：中文
  - 简体中文
  - 繁体中文
- 当前文件夹的翻译语言可在漫画库中单独设置为：
  - 日文 → 中文
  - 英文 → 中文
  - 韩文 → 中文
- 软件界面切换为繁体中文时，会优先使用繁体提示词，翻译结果也会优先输出繁体中文

## 快速使用 🚀
1. 在漫画库中新建文件夹并导入图片
2. 确保图片文件名顺序与阅读顺序一致（例如 1.jpg, 2.jpg）
3. 在设置页填写 API 地址、API Key、模型名称（OpenAI 兼容）
4. 回到漫画库，选择文件夹并点击“翻译文件夹”
5. 翻译完成后点击“开始阅读”，在阅读页可拖动气泡位置

*全文速译建议：页数较多时分批上传翻译，或在设置中提高 API 超时。*

## 常见问题 ❓
- 翻译失败或结果为空：确认 API 地址以 `/v1` 结尾，模型名与供应商一致，且网络可达
- 翻译顺序错乱：请先对图片按阅读顺序重命名
- 怎么获取AI：具体获取方法可以去搜索一下

## 交流
可以进QQ群提问交流：1080302768

## Star History
** 喜欢的话可以点个Star哦 **
[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator&type=date&legend=top-left)


## 数据与文件说明 🗂️
- 漫画库存储：`/Android/data/<package>/files/manga_library/`
- 每张图片生成同名 `*.json` 翻译结果，OCR 缓存为 `*.ocr.json`
- 译名表：每个文件夹维护 `glossary.json`
- 阅读进度、全文速译开关等存储在 SharedPreferences

## 从源码构建 🧩

### 环境要求
- JDK 17.0.17+
- Kotlin 2.0.0+
- Gradle 8.11.1+
- Android SDK: platform 35, build-tools 35.0.0

### 构建命令
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### 模型与资源
将以下模型文件放入 `assets/` 对应子目录：
- `models/detection/yolov8m_seg-speech-bubble.onnx`（气泡检测）
- `models/ocr/manga_ocr/encoder_model.onnx`、`models/ocr/manga_ocr/decoder_model.onnx`（日文 OCR：MangaOcr，可在设置中切换）
- `models/ocr/manga_ocr/generation_config.json`、`models/ocr/manga_ocr/preprocessor_config.json`、`models/ocr/manga_ocr/tokenizer.json`、`models/ocr/manga_ocr/special_tokens_map.json`
- `models/ocr/manga_ocr_mobile/encoder.tflite`、`models/ocr/manga_ocr_mobile/decoder.tflite` 及 tokenizer/config（当前默认日文 OCR：MangaOcr Mobile）
- `models/ocr/en_PP-OCRv5_rec_mobile_infer.onnx`（英文 OCR）
- `models/ocr/korean_PP-OCRv3_rec_infer.onnx`（韩文 OCR）
- `models/text_detection/ysgyolo_1.2_OS1.0.onnx`（文本补检 + 文字蒙版）
- `models/detection/Multilingual_PP-OCRv3_det_infer.onnx`（英文行检测）

模型下载链接：
- 气泡检测模型：https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m
- OCR 模型：https://huggingface.co/l0wgear/manga-ocr-2025-onnx
- 韩文 OCR 模型：https://huggingface.co/breezedeus/cnocr-ppocr-korean_PP-OCRv3

提示词、字体与 OCR 配置位于 `assets/` 子目录中，名称需与代码保持一致。

### 发布版本号同步
需同时修改：
- `app/src/main/java/com/manga/translate/VersionInfo.kt`
- `app/build.gradle.kts`
- `update.json`

## 🙏 致谢

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) - 提供 OCR 模型支持
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) - MangaOCR 模型支持
- [bluolightning/manga-ocr-mobile](https://huggingface.co/bluolightning/manga-ocr-mobile) - MangaOCR-mobile 模型支持
- 所有用户的支持
