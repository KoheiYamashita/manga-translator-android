package com.manga.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max
import kotlin.math.min

internal object ImageProcessingGuards {
    private const val MEMORY_BUDGET_MULTIPLIER = 3L
    private const val MEMORY_WAIT_MS = 32L
    private const val MEMORY_RETRY_COUNT = 3

    private val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val maxMemoryBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)

    val renderConcurrency: Int = computeRenderConcurrency()
    val decodeConcurrency: Int = computeDecodeConcurrency()

    private val renderSemaphore = Semaphore(renderConcurrency)
    private val decodeSemaphore = Semaphore(decodeConcurrency)

    suspend fun <T> withRenderPermit(
        width: Int,
        height: Int,
        tag: String,
        block: suspend () -> T
    ): T = withMemoryBoundPermit(
        semaphore = renderSemaphore,
        width = width,
        height = height,
        tag = tag,
        stage = "render",
        block = block
    )

    suspend fun <T> withDecodePermit(
        width: Int,
        height: Int,
        tag: String,
        block: suspend () -> T
    ): T = withMemoryBoundPermit(
        semaphore = decodeSemaphore,
        width = width,
        height = height,
        tag = tag,
        stage = "decode",
        block = block
    )

    fun hasMemoryBudgetForBitmap(width: Int, height: Int, copies: Int = MEMORY_BUDGET_MULTIPLIER.toInt()): Boolean {
        if (width <= 0 || height <= 0 || copies <= 0) return false
        val need = width.toLong() * height.toLong() * 4L
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return free > need * copies.toLong()
    }

    private suspend fun <T> withMemoryBoundPermit(
        semaphore: Semaphore,
        width: Int,
        height: Int,
        tag: String,
        stage: String,
        block: suspend () -> T
    ): T {
        return semaphore.withPermit {
            awaitMemoryBudget(width, height, tag, stage)
            block()
        }
    }

    private suspend fun awaitMemoryBudget(width: Int, height: Int, tag: String, stage: String) {
        if (width <= 0 || height <= 0) return
        repeat(MEMORY_RETRY_COUNT) { attempt ->
            if (hasMemoryBudgetForBitmap(width, height)) {
                return
            }
            if (attempt == 0) {
                AppLogger.log(
                    tag,
                    "Delay $stage due to memory pressure for ${width}x$height bitmap"
                )
            }
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.yield()
            }
            delay(MEMORY_WAIT_MS)
        }
    }

    private fun computeRenderConcurrency(): Int {
        val byCpu = min(2, max(1, cpuCount / 2))
        val byMemory = when {
            maxMemoryBytes >= 768L * 1024L * 1024L -> 2
            else -> 1
        }
        return min(byCpu, byMemory).coerceAtLeast(1)
    }

    private fun computeDecodeConcurrency(): Int {
        val byCpu = min(2, max(1, cpuCount / 2))
        val byMemory = when {
            maxMemoryBytes >= 1024L * 1024L * 1024L -> 2
            else -> 1
        }
        return min(byCpu, byMemory).coerceAtLeast(1)
    }
}
