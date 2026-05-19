package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class BubbleDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int,
    val maskContour: FloatArray? = null
)

class BubbleDetector(
    private val context: Context,
    private val modelAssetName: String = "models/detection/yolov8m_seg-speech-bubble.onnx",
    private val threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val env = OnnxRuntimeSupport.environment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputShape: LongArray
    private val inputType: OnnxJavaType
    private val hasSegOutput: Boolean

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        val tensorInfo = input.value.info as TensorInfo
        inputShape = tensorInfo.shape
        inputType = tensorInfo.type
        hasSegOutput = session.outputInfo.size >= 2
    }

    fun detect(bitmap: android.graphics.Bitmap): List<BubbleDetection> {
        // Use fixed dim when positive; treat 0 or -1 (dynamic ONNX dim) as 640.
        val inputHeight = inputShape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: 640
        val inputWidth = inputShape.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: 640
        val pre = preprocess(bitmap, inputWidth, inputHeight)

        val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        val tensor = if (inputType == OnnxJavaType.FLOAT16) {
            createFloat16Tensor(pre.inputBuffer, shape)
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(pre.inputBuffer), shape)
        }
        tensor.use {
            val outputs = session.run(mapOf(inputName to tensor))
            outputs.use {
                val output0 = outputs[0]
                val output0Shape = (output0.info as TensorInfo).shape

                var protoData: FloatArray? = null
                var protoH = 160
                var protoW = 160
                if (hasSegOutput) {
                    val output1 = outputs[1]
                    val output1Shape = (output1.info as TensorInfo).shape
                    protoH = output1Shape.getOrNull(2)?.toInt() ?: 160
                    protoW = output1Shape.getOrNull(3)?.toInt() ?: 160
                    protoData = parseProtos(getTensorValue(output1 as OnnxTensor), output1Shape)
                }

                val rawDetections = parseDetections(getTensorValue(output0 as OnnxTensor), output0Shape)
                val confThreshold = settingsStore.loadBubbleConfThresholdPercent() / 100f
                if (settingsStore.loadModelIoLogging()) {
                    val maxConf = rawDetections.maxOfOrNull { it.confidence } ?: 0f
                    val aboveThreshold = rawDetections.count { it.confidence >= confThreshold }
                    AppLogger.log(
                        "BubbleDetector",
                        "Raw detections: ${rawDetections.size}, above ${confThreshold}: $aboveThreshold, max conf: ${"%.3f".format(maxConf)}"
                    )
                }
                val filtered = filterByNms(
                    rawDetections, confThreshold, 0.5f,
                    pre, bitmap.width, bitmap.height
                )
                val result = filtered.map { raw ->
                    val rect = raw.toRect(pre, bitmap.width, bitmap.height)
                    val contour = if (protoData != null && raw.maskCoeffs.isNotEmpty()) {
                        computeMaskContour(raw, protoData, protoH, protoW, pre, bitmap.width, bitmap.height)
                    } else null
                    BubbleDetection(rect, raw.confidence, raw.classId, contour)
                }
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "BubbleDetector",
                        "Input ${bitmap.width}x${bitmap.height}, output ${result.size} boxes: ${describeDetections(result)}"
                    )
                }
                return result
            }
        }
    }

    private fun preprocess(bitmap: Bitmap, inputWidth: Int, inputHeight: Int): PreprocessResult {
        val letterboxed = OnnxImagePreprocessor.letterbox(bitmap, inputWidth, inputHeight)
        val inputBuffer = OnnxImagePreprocessor.bitmapToRgbChwFloat(letterboxed.bitmap)
        letterboxed.bitmap.recycle()

        return PreprocessResult(
            inputBuffer = inputBuffer,
            gain = letterboxed.gain,
            padX = letterboxed.padX,
            padY = letterboxed.padY,
            inputWidth = letterboxed.inputWidth,
            inputHeight = letterboxed.inputHeight
        )
    }

    private fun parseProtos(raw: Any, shape: LongArray): FloatArray? {
        if (shape.size != 4) return null
        val nm = shape[1].toInt()
        val ph = shape[2].toInt()
        val pw = shape[3].toInt()
        val batch = raw as? Array<*> ?: return null
        val first = batch.firstOrNull() as? Array<*> ?: return null
        val result = FloatArray(nm * ph * pw)
        for (k in 0 until min(first.size, nm)) {
            val rows = first[k] as? Array<*> ?: return null
            for (y in 0 until min(rows.size, ph)) {
                val row = rows[y] as? FloatArray ?: return null
                row.copyInto(result, k * ph * pw + y * pw, 0, min(row.size, pw))
            }
        }
        return result
    }

    private fun parseDetections(raw: Any, shape: LongArray): List<RawDetection> {
        if (shape.size != 3) return emptyList()
        val n = max(shape[1], shape[2]).toInt()
        val c = min(shape[1], shape[2]).toInt()
        if (c < 5) return emptyList()

        // c >= 37 means seg model: 4 bbox + numClasses + 32 mask coefficients
        val hasMaskCoeffs = c >= 37
        val nmask = if (hasMaskCoeffs) 32 else 0
        val numClasses = c - 4 - nmask

        val batch = raw as? Array<*> ?: return emptyList()
        val first = batch.firstOrNull() as? Array<*> ?: return emptyList()
        val data = first.mapNotNull { it as? FloatArray }
        if (data.size != first.size) return emptyList()

        val results = ArrayList<RawDetection>(n)
        if (shape[1] <= shape[2]) {
            // channels-first layout: data[channel][anchor]
            if (data.any { it.size < n }) return emptyList()
            if (!hasMaskCoeffs && c == 6) {
                // Legacy detection format: cx, cy, w, h, conf, classId_int
                for (i in 0 until n) {
                    results.add(
                        RawDetection(
                            data[0][i], data[1][i], data[2][i], data[3][i],
                            data[4][i], data[5][i].toInt()
                        )
                    )
                }
            } else {
                for (i in 0 until n) {
                    val (classId, conf) = if (numClasses == 1) {
                        Pair(0, data[4][i])
                    } else {
                        bestClass(data, i, 4, 4 + numClasses)
                    }
                    val coeffs = if (hasMaskCoeffs) {
                        FloatArray(nmask) { k -> data[4 + numClasses + k][i] }
                    } else FloatArray(0)
                    results.add(RawDetection(data[0][i], data[1][i], data[2][i], data[3][i], conf, classId, coeffs))
                }
            }
        } else {
            // anchors-first layout: data[anchor][channel]
            if (data.size < n) return emptyList()
            for (i in 0 until n) {
                val row = data[i]
                if (row.size < 5) continue
                if (!hasMaskCoeffs && row.size == 6) {
                    // Legacy detection format
                    results.add(RawDetection(row[0], row[1], row[2], row[3], row[4], row[5].toInt()))
                } else {
                    val (classId, conf) = if (numClasses == 1) {
                        Pair(0, row[4])
                    } else {
                        bestClassRow(row, 4, 4 + numClasses)
                    }
                    val coeffs = if (hasMaskCoeffs && row.size >= 4 + numClasses + nmask) {
                        FloatArray(nmask) { k -> row[4 + numClasses + k] }
                    } else FloatArray(0)
                    results.add(RawDetection(row[0], row[1], row[2], row[3], conf, classId, coeffs))
                }
            }
        }
        return results
    }

    private fun bestClass(data: List<FloatArray>, index: Int, from: Int, until: Int): Pair<Int, Float> {
        var best = 0f
        var bestId = 0
        for (i in from until until) {
            val v = data[i][index]
            if (v > best) {
                best = v
                bestId = i - from
            }
        }
        return Pair(bestId, best)
    }

    private fun bestClassRow(row: FloatArray, from: Int, until: Int): Pair<Int, Float> {
        var best = 0f
        var bestId = 0
        for (i in from until min(until, row.size)) {
            val v = row[i]
            if (v > best) {
                best = v
                bestId = i - from
            }
        }
        return Pair(bestId, best)
    }

    private fun filterByNms(
        detections: List<RawDetection>,
        confThreshold: Float,
        iouThreshold: Float,
        pre: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): List<RawDetection> {
        val filtered = detections.filter { it.confidence >= confThreshold }
            .sortedByDescending { it.confidence }
        val selected = ArrayList<RawDetection>()
        val taken = BooleanArray(filtered.size)

        for (i in filtered.indices) {
            if (taken[i]) continue
            val det = filtered[i]
            val rect = det.toRect(pre, originalWidth, originalHeight)
            selected.add(det)
            for (j in i + 1 until filtered.size) {
                if (taken[j]) continue
                val other = filtered[j]
                val iou = iou(rect, other.toRect(pre, originalWidth, originalHeight))
                if (iou > iouThreshold) taken[j] = true
            }
        }
        return selected
    }

    private fun computeMaskContour(
        det: RawDetection,
        protos: FloatArray,
        protoH: Int,
        protoW: Int,
        pre: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): FloatArray? {
        val nm = det.maskCoeffs.size
        val totalProto = protoH * protoW

        // Compute raw mask at proto resolution: sigmoid(maskCoeffs @ protos[nm, protoH*protoW])
        val rawMask = FloatArray(totalProto)
        for (i in 0 until totalProto) {
            var sum = 0f
            for (k in 0 until nm) {
                sum += det.maskCoeffs[k] * protos[k * totalProto + i]
            }
            rawMask[i] = sigmoid(sum)
        }

        // Map detection bbox to proto coordinate space
        val normalized = det.w <= 1.5f && det.h <= 1.5f && det.cx <= 1.5f && det.cy <= 1.5f
        val xc = if (normalized) det.cx * pre.inputWidth else det.cx
        val yc = if (normalized) det.cy * pre.inputHeight else det.cy
        val bw = if (normalized) det.w * pre.inputWidth else det.w
        val bh = if (normalized) det.h * pre.inputHeight else det.h

        val x1p = ((xc - bw / 2f) / pre.inputWidth * protoW).toInt().coerceIn(0, protoW)
        val y1p = ((yc - bh / 2f) / pre.inputHeight * protoH).toInt().coerceIn(0, protoH)
        val x2p = ((xc + bw / 2f) / pre.inputWidth * protoW).toInt().coerceIn(0, protoW)
        val y2p = ((yc + bh / 2f) / pre.inputHeight * protoH).toInt().coerceIn(0, protoH)

        if (x2p <= x1p || y2p <= y1p) return null

        return extractContourPolygon(rawMask, protoW, protoH, x1p, y1p, x2p, y2p, pre, originalWidth, originalHeight)
    }

    // Extracts a closed polygon by scanning left/right edges of the mask region.
    // Returns a flat FloatArray [x0,y0,x1,y1,...] with coordinates normalized to [0,1]
    // relative to the original image (proto_px / protoW == fraction of image width).
    private fun extractContourPolygon(
        mask: FloatArray,
        maskW: Int,
        maskH: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        pre: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): FloatArray? {
        val numSamples = (y2 - y1).coerceIn(2, 40)
        val leftEdge = ArrayList<Float>(numSamples * 2)
        val rightEdge = ArrayList<Float>(numSamples * 2)

        for (s in 0 until numSamples) {
            val y = y1 + (y2 - y1) * s / numSamples
            val rowOff = y.coerceIn(0, maskH - 1) * maskW
            var leftX = -1
            var rightX = -1
            for (x in x1 until x2) {
                if (mask[rowOff + x] >= 0.5f) {
                    if (leftX < 0) leftX = x
                    rightX = x
                }
            }
            if (leftX >= 0) {
                val leftPoint = mapMaskPointToOriginal(leftX.toFloat(), y.toFloat(), maskW, maskH, pre, originalWidth, originalHeight)
                val rightPoint = mapMaskPointToOriginal((rightX + 1).toFloat(), y.toFloat(), maskW, maskH, pre, originalWidth, originalHeight)
                leftEdge.add(leftPoint.first)
                leftEdge.add(leftPoint.second)
                rightEdge.add(rightPoint.first)
                rightEdge.add(rightPoint.second)
            }
        }

        if (leftEdge.isEmpty()) return null

        // Polygon: left edge top→bottom, then right edge bottom→top (closed shape)
        val polygon = FloatArray(leftEdge.size + rightEdge.size)
        leftEdge.toFloatArray().copyInto(polygon, 0)
        var outIdx = leftEdge.size
        for (i in rightEdge.size - 2 downTo 0 step 2) {
            polygon[outIdx] = rightEdge[i]
            polygon[outIdx + 1] = rightEdge[i + 1]
            outIdx += 2
        }
        return polygon
    }

    private fun mapMaskPointToOriginal(
        x: Float,
        y: Float,
        maskW: Int,
        maskH: Int,
        pre: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): Pair<Float, Float> {
        val inputX = x / maskW * pre.inputWidth
        val inputY = y / maskH * pre.inputHeight
        val originalX = ((inputX - pre.padX) / pre.gain).coerceIn(0f, max(0f, originalWidth - 1f))
        val originalY = ((inputY - pre.padY) / pre.gain).coerceIn(0f, max(0f, originalHeight - 1f))
        val normalizedX = if (originalWidth > 0) originalX / originalWidth else 0f
        val normalizedY = if (originalHeight > 0) originalY / originalHeight else 0f
        return normalizedX to normalizedY
    }

    // Creates a float16 tensor from a float32 array. ORT float16 tensors require a direct
    // ByteBuffer with 2 bytes per element encoding the IEEE 754 half-precision bit pattern.
    private fun createFloat16Tensor(data: FloatArray, shape: LongArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
        val sb = buf.asShortBuffer()
        for (v in data) sb.put(floatToFloat16(v))
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
    }

    // Converts a float32 value to its float16 bit pattern stored as a Short.
    // Image pixel values are in [0,1] so no NaN/Inf handling needed.
    private fun floatToFloat16(v: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(v)
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 112   // rebias: 127 → 15, so subtract 127-15=112
        val mantissa = bits and 0x7FFFFF
        return when {
            exp >= 31 -> (sign or 0x7C00).toShort()  // overflow → infinity
            exp <= 0 -> sign.toShort()                 // underflow → zero
            else -> (sign or (exp shl 10) or (mantissa ushr 13)).toShort()
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    // Returns the tensor data as a nested FloatArray structure, handling FLOAT16 output tensors
    // that ORT Java does not support via getValue().
    private fun getTensorValue(tensor: OnnxTensor): Any {
        val typeInfo = tensor.info as TensorInfo
        if (typeInfo.type != OnnxJavaType.FLOAT16) return tensor.value
        val shape = typeInfo.shape
        val total = shape.fold(1L) { acc, v -> acc * v }.toInt()
        val sb = tensor.byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val floatData = FloatArray(total) { float16ToFloat32(sb.get()) }
        return reshapeToNestedArray(floatData, shape, 0)
    }

    // Recursively reshapes a flat FloatArray into a nested Array<...FloatArray> structure
    // matching the given ONNX tensor shape, so existing parse functions work unchanged.
    private fun reshapeToNestedArray(data: FloatArray, shape: LongArray, dim: Int): Any {
        val size = shape[dim].toInt()
        if (dim == shape.size - 1) return data.copyOf(size)
        val subSize = shape.drop(dim + 1).fold(1L) { acc, v -> acc * v }.toInt()
        return Array<Any>(size) { i ->
            val start = i * subSize
            reshapeToNestedArray(data.copyOfRange(start, minOf(start + subSize, data.size)), shape, dim + 1)
        }
    }

    // Converts an IEEE 754 float16 bit pattern (stored as Short) to Float.
    private fun float16ToFloat32(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h and 0x7C00) ushr 10
        val mantissa = h and 0x03FF
        return when {
            exp == 0x1F -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mantissa shl 13))
            exp == 0 -> java.lang.Float.intBitsToFloat(sign or (mantissa shl 13))
            else -> java.lang.Float.intBitsToFloat(sign or ((exp + 112) shl 23) or (mantissa shl 13))
        }
    }

    private fun createSession(): OrtSession {
        return OnnxRuntimeSupport.getOrCreateSession(
            cacheDir = context.cacheDir,
            assetProvider = context.assets::open,
            assetName = modelAssetName,
            threadProfile = threadProfile
        )
    }

    private fun describeDetections(detections: List<BubbleDetection>, limit: Int = 3): String {
        if (detections.isEmpty()) return "[]"
        val preview = detections.take(limit).joinToString(prefix = "[", postfix = "]") { det ->
            val r = det.rect
            val maskInfo = if (det.maskContour != null) ",mask=${det.maskContour.size / 2}pts" else ""
            "(${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()},c=${"%.2f".format(det.confidence)}$maskInfo)"
        }
        return if (detections.size > limit) "$preview..." else preview
    }
}

private data class RawDetection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val confidence: Float,
    val classId: Int,
    val maskCoeffs: FloatArray = FloatArray(0)
) {
    fun toRect(
        pre: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): RectF {
        val normalized = w <= 1.5f && h <= 1.5f && cx <= 1.5f && cy <= 1.5f
        val xCenter = if (normalized) cx * pre.inputWidth else cx
        val yCenter = if (normalized) cy * pre.inputHeight else cy
        val width = if (normalized) w * pre.inputWidth else w
        val height = if (normalized) h * pre.inputHeight else h
        val left = ((xCenter - width / 2f) - pre.padX) / pre.gain
        val top = ((yCenter - height / 2f) - pre.padY) / pre.gain
        val right = ((xCenter + width / 2f) - pre.padX) / pre.gain
        val bottom = ((yCenter + height / 2f) - pre.padY) / pre.gain
        val maxX = max(0f, originalWidth - 1f)
        val maxY = max(0f, originalHeight - 1f)
        return RectF(
            left.coerceIn(0f, maxX),
            top.coerceIn(0f, maxY),
            right.coerceIn(0f, maxX),
            bottom.coerceIn(0f, maxY)
        )
    }
}

private data class PreprocessResult(
    val inputBuffer: FloatArray,
    val gain: Float,
    val padX: Float,
    val padY: Float,
    val inputWidth: Int,
    val inputHeight: Int
)
