# Manga Translator 📖

[中文](./README.md) | English

An Android manga translation app with local speech bubble detection and OCR, combined with an OpenAI-compatible API for translation. Translated text is rendered back onto the original image as draggable text bubbles. It also supports screen translation with a floating overlay, so you can recognize and translate manga text directly from any app or from the home screen.

Tutorial: [Simplified Chinese Tutorial](./Tutorial/简中教程.md)

| Original | Translated |
|------|----------|
| ![Original](./Tutorial/FirePunch.webp) | ![Translated](./Tutorial/translated.webp) |

## Key Features ✨
- Japanese to Chinese, English to Chinese
- Screen translation: supports floating-window translation to recognize and translate manga text from any screen
- Manga library management: create folders, import images in batch, import manga folders, and support CBZ, ZIP, and PDF import/export
- Translation pipeline: speech bubble detection + OCR + LLM translation, with both standard mode and full-text fast translation
- Reading experience: translation overlay, draggable translated bubbles, and automatic reading progress saving
- Glossary and cache: maintain `glossary.json` per folder and automatically accumulate consistent name translations
- Updates and logs: check for updates on launch, foreground service during translation, and in-app log viewing
- Multi-provider load balancing: configure multiple translation providers and balance requests automatically by weight

## Supported Translation Languages 🌐
- Source languages: Japanese, English
- Target language: Chinese
  - Simplified Chinese
  - Traditional Chinese
- The translation language for each current folder can also be set independently in the library as:
  - Japanese -> Chinese
  - English -> Chinese
  - Korean -> Chinese
- When the app UI is switched to Traditional Chinese, it will prioritize Traditional Chinese prompts and output Traditional Chinese translation results by default

## Quick Start 🚀
1. Create a folder in the manga library and import images
2. Make sure image filenames match the reading order, such as `1.jpg`, `2.jpg`
3. Fill in the API URL, API key, and model name in Settings (OpenAI-compatible)
4. Return to the library, choose a folder, and tap "Translate Folder"
5. After translation finishes, tap "Start Reading" and drag bubble positions on the reader page as needed

*For full-text fast translation, it is recommended to upload and translate in batches for large folders, or increase the API timeout in Settings.*

## FAQ ❓
- Translation fails or returns empty results: make sure the API URL ends with `/v1`, the model name matches the provider, and the network is reachable
- Translation order is incorrect: rename images first so they match the reading order
- How do I get an AI API: please search for a suitable provider based on your needs

## Community
Join the QQ group for questions and discussion: `1080302768`

## Star History
** If you like this project, please consider giving it a star **
[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator&type=date&legend=top-left)

## Data and File Layout 🗂️
- Manga library storage: `/Android/data/<package>/files/manga_library/`
- Each image generates a same-name `*.json` translation result, and OCR cache is stored as `*.ocr.json`
- Glossary: each folder maintains its own `glossary.json`
- Reading progress, full-text fast translation switches, and related settings are stored in SharedPreferences

## Build from Source 🧩

### Requirements
- JDK 17.0.17+
- Kotlin 2.0.0+
- Gradle 8.11.1+
- Android SDK: platform 35, build-tools 35.0.0

### Build Commands
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### Models and Assets
Place the following model files into the corresponding subdirectories under `assets/`:
- `models/detection/yolov8m_seg-speech-bubble.onnx` (speech bubble detection)
- `models/ocr/manga_ocr/encoder_model.onnx`, `models/ocr/manga_ocr/decoder_model.onnx` (Japanese OCR: MangaOcr, switchable in Settings)
- `models/ocr/manga_ocr/generation_config.json`, `models/ocr/manga_ocr/preprocessor_config.json`, `models/ocr/manga_ocr/tokenizer.json`, `models/ocr/manga_ocr/special_tokens_map.json`
- `models/ocr/manga_ocr_mobile/encoder.tflite`, `models/ocr/manga_ocr_mobile/decoder.tflite` and tokenizer/config files (currently the default Japanese OCR: MangaOcr Mobile)
- `models/ocr/en_PP-OCRv5_rec_mobile_infer.onnx` (English OCR)
- `models/ocr/korean_PP-OCRv3_rec_infer.onnx` (Korean OCR)
- `models/text_detection/ysgyolo_1.2_OS1.0.onnx` (supplementary text detection + text masking)
- `models/detection/Multilingual_PP-OCRv3_det_infer.onnx` (English line detection)

Model download links:
- Speech bubble detection model: https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m
- OCR model: https://huggingface.co/l0wgear/manga-ocr-2025-onnx
- Korean OCR model: https://huggingface.co/breezedeus/cnocr-ppocr-korean_PP-OCRv3

Prompts, fonts, and OCR configuration files are located in subdirectories under `assets/`, and their names must stay consistent with the code.

### Release Version Sync
Update all of the following files at the same time:
- `app/src/main/java/com/manga/translate/VersionInfo.kt`
- `app/build.gradle.kts`
- `update.json`

## Acknowledgements 🙏

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) - OCR model support
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) - MangaOCR model support
- [bluolightning/manga-ocr-mobile](https://huggingface.co/bluolightning/manga-ocr-mobile) - MangaOCR-mobile model support
- Support from all users
