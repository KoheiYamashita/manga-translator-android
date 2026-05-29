# Third-party notices

This project bundles the following third-party OCR model assets.

## PaddleOCR English recognition model

- Bundled file: `assets/models/ocr/en_PP-OCRv5_rec_mobile_infer.onnx`
- Upstream model: PaddlePaddle `en_PP-OCRv5_mobile_rec`
- Source/model card: https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec
- Original inference archive used for conversion: https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/en_PP-OCRv5_mobile_rec_infer.tar
- License: Apache License 2.0
- Notes: The bundled ONNX file was converted from the upstream Paddle inference model and includes the recognition character dictionary in ONNX metadata.

## PaddleOCR multilingual text-line detection model

- Bundled file: `assets/models/detection/Multilingual_PP-OCRv3_det_infer.onnx`
- Upstream project/model: PaddleOCR multilingual PP-OCRv3 text detection model
- Bundled ONNX source: https://github.com/Kazuhito00/PaddleOCRv3-ONNX-Sample/blob/main/ppocr_onnx/model/det_model/Multilingual_PP-OCRv3_det_infer.onnx
- Original project: https://github.com/PaddlePaddle/PaddleOCR
- License: Apache License 2.0

The Apache License 2.0 text is included at `licenses/APACHE-2.0.txt`.
