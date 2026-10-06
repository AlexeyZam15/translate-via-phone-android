// OcrBlockStabilizer.kt (полный файл)
package com.example.screentranslator

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Стабилизация блоков между кадрами.
 *
 * ИЗМЕНЕНО:
 *   - BBOX_SETTLE_FRAMES: 3 → 1.
 *   - POS_CHANGE_THRESHOLD: 20 → 10.
 *   - SIZE_CHANGE_THRESHOLD: 25 → 15.
 *   - STABLE_TEXT_UPDATE_THRESHOLD: 2 → 1.
 *   - Двухфазная логика bbox сохранена.
 *   - Убран detectMergeSplit.
 */
object OcrBlockStabilizer {

    private const val TAG = "OcrBlockStabilizer"

    private const val STABLE_IOU_THRESHOLD = 0.3
    private const val CENTER_MATCH_DIST = 60.0
    private const val CONTAINMENT_MARGIN = 0.3
    private const val STABLE_MISS_LIMIT = 15L

    private const val STABLE_TEXT_UPDATE_THRESHOLD = 1

    private const val EVICTION_IOU = 0.3
    private const val REPLACE_IOU = 0.5

    private const val CONTAINMENT_MARGIN_PX = 20

    private const val POS_CHANGE_THRESHOLD = 10
    private const val SIZE_CHANGE_THRESHOLD = 15
    private const val BIG_SIZE_CHANGE_THRESHOLD = 120

    private const val BBOX_SETTLE_FRAMES = 1

    data class StableBlock(
        var text: String,
        var x: Int,
        var y: Int,
        var w: Int,
        var h: Int,
        var lastSeenFrame: Long,
        var pendingText: String? = null,
        var pendingCount: Int = 0,
        var bboxSettled: Boolean = true,
        var bboxSettleCounter: Int = 0
    )

    fun stabilizeBlocks(
        blocks: List<OcrBlock>,
        stableBlocks: MutableList<StableBlock>,
        frameCounter: Long,
        sceneChanged: Boolean
    ): List<OcrBlock> {
        if (blocks.isEmpty()) {
            pruneStableBlocks(stableBlocks, frameCounter)
            return emptyList()
        }

        val used = BooleanArray(blocks.size) { false }
        val result = ArrayList<OcrBlock>(blocks.size)
        var matchedStableCount = 0
        var newBlocksCount = 0

        for (sb in stableBlocks) {
            var bestIdx = -1
            var bestScore = 0.0
            for (i in blocks.indices) {
                if (used[i]) continue
                val nb = blocks[i]
                val r = nb.boundingBox
                if (r.width() <= 0 || r.height() <= 0) continue

                val iou = computeIoU(
                    sb.x, sb.y, sb.w, sb.h,
                    r.left, r.top, r.width(), r.height()
                )
                val dist = centerDistance(
                    sb.x, sb.y, sb.w, sb.h,
                    r.left, r.top, r.width(), r.height()
                )

                val matchByIou = iou >= STABLE_IOU_THRESHOLD
                val matchByCenter = dist < CENTER_MATCH_DIST
                val matchByContain =
                    isContained(
                        sb.x, sb.y, sb.w, sb.h,
                        r.left, r.top, r.width(), r.height(),
                        CONTAINMENT_MARGIN
                    ) ||
                            isContained(
                                r.left, r.top, r.width(), r.height(),
                                sb.x, sb.y, sb.w, sb.h,
                                CONTAINMENT_MARGIN
                            )

                val geometryMatch = matchByIou || matchByCenter || matchByContain
                if (!geometryMatch) continue

                val candidateText = OcrTextNormalizer.normalizeText(nb.text)
                val textSimilar = OcrTextNormalizer.textsAreSimilar(sb.text, candidateText)

                var score = iou
                if (matchByCenter) {
                    score += (1.0 - dist / CENTER_MATCH_DIST) * 0.5
                }
                if (matchByContain) {
                    score += 0.2
                }
                if (textSimilar) {
                    score += 0.3
                }
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }

            if (bestIdx < 0) continue

            used[bestIdx] = true
            matchedStableCount++
            val nb = blocks[bestIdx]
            val newText = OcrTextNormalizer.normalizeText(nb.text)
            if (newText.isEmpty()) {
                sb.lastSeenFrame = frameCounter
                continue
            }

            sb.lastSeenFrame = frameCounter

            // ДВУХФАЗНАЯ ЛОГИКА ОБНОВЛЕНИЯ BBOX
            if (newText == sb.text) {
                sb.pendingText = null
                sb.pendingCount = 0

                if (sb.bboxSettled) {
                    smoothBlockGeometry(
                        sb,
                        nb.boundingBox.left,
                        nb.boundingBox.top,
                        nb.boundingBox.width(),
                        nb.boundingBox.height()
                    )
                } else {
                    sb.bboxSettleCounter++
                    if (sb.bboxSettleCounter >= BBOX_SETTLE_FRAMES) {
                        sb.bboxSettled = true
                        smoothBlockGeometry(
                            sb,
                            nb.boundingBox.left,
                            nb.boundingBox.top,
                            nb.boundingBox.width(),
                            nb.boundingBox.height()
                        )
                    }
                }
            } else if (newText == sb.pendingText) {
                sb.pendingCount++
                if (sb.pendingCount >= STABLE_TEXT_UPDATE_THRESHOLD) {
                    sb.text = newText
                    sb.pendingText = null
                    sb.pendingCount = 0
                    sb.bboxSettled = false
                    sb.bboxSettleCounter = 0
                }
            } else {
                sb.pendingText = newText
                sb.pendingCount = 1
            }

            result.add(
                OcrBlock(
                    text = newText,
                    boundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h),
                    confidence = nb.confidence,
                    initialBoundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h),
                    elements = nb.elements
                )
            )
        }

        if (sceneChanged && matchedStableCount == 0 && stableBlocks.isNotEmpty()) {
            Log.d(TAG, "scene changed: no stable matched, clear stableBlocks")
            DebugTimingLogger.logEvent("android.pc_ocr_scene_changed",
                mapOf("new_blocks" to blocks.size))
            stableBlocks.clear()
        }

        for (i in blocks.indices) {
            if (used[i]) continue
            val nb = blocks[i]
            val r = nb.boundingBox
            if (r.width() <= 0 || r.height() <= 0) continue

            val newText = OcrTextNormalizer.normalizeText(nb.text)
            if (newText.isEmpty()) continue

            var isDuplicate = false
            val containsToRemove = ArrayList<StableBlock>()

            for (sb in stableBlocks) {
                if (sb.w <= 0 || sb.h <= 0) continue

                if (isBBoxContained(
                        r.left, r.top, r.width(), r.height(),
                        sb.x, sb.y, sb.w, sb.h,
                        CONTAINMENT_MARGIN_PX
                    )
                ) {
                    sb.lastSeenFrame = frameCounter
                    smoothBlockGeometry(
                        sb,
                        r.left, r.top, r.width(), r.height()
                    )

                    isDuplicate = true
                    DebugTimingLogger.logEvent("android.pc_ocr_skip_contained",
                        mapOf(
                            "new" to newText.take(60),
                            "existing" to sb.text.take(60),
                            "direction" to "new_in_stable",
                            "refreshed" to true
                        ))
                    break
                }

                if (isBBoxContained(
                        sb.x, sb.y, sb.w, sb.h,
                        r.left, r.top, r.width(), r.height(),
                        CONTAINMENT_MARGIN_PX
                    )
                ) {
                    containsToRemove.add(sb)
                }
            }

            if (isDuplicate) continue

            var overlapsResult = false
            for (existing in result) {
                val er = existing.boundingBox
                val iou = computeIoU(
                    r.left, r.top, r.width(), r.height(),
                    er.left, er.top, er.width(), er.height()
                )
                if (iou >= EVICTION_IOU) {
                    overlapsResult = true
                    DebugTimingLogger.logEvent("android.pc_ocr_skip_overlap",
                        mapOf(
                            "new" to newText.take(60),
                            "existing" to existing.text.take(60),
                            "iou" to String.format("%.2f", iou),
                            "check" to "result"
                        ))
                    break
                }
            }
            if (overlapsResult) continue

            var overlapsStable = false
            for (sb in stableBlocks) {
                if (containsToRemove.contains(sb)) continue
                val iou = computeIoU(
                    r.left, r.top, r.width(), r.height(),
                    sb.x, sb.y, sb.w, sb.h
                )
                if (iou >= REPLACE_IOU) {
                    overlapsStable = true
                    DebugTimingLogger.logEvent("android.pc_ocr_skip_overlap",
                        mapOf(
                            "new" to newText.take(60),
                            "existing" to sb.text.take(60),
                            "iou" to String.format("%.2f", iou),
                            "check" to "stable"
                        ))
                    break
                }
            }
            if (overlapsStable) continue

            val staleToRemove = ArrayList<StableBlock>()
            for (sb in stableBlocks) {
                if (sb.lastSeenFrame >= frameCounter) continue
                val iou = computeIoU(
                    r.left, r.top, r.width(), r.height(),
                    sb.x, sb.y, sb.w, sb.h
                )
                if (iou >= REPLACE_IOU) {
                    staleToRemove.add(sb)
                }
            }

            val allToRemove = HashSet<StableBlock>()
            allToRemove.addAll(containsToRemove)
            allToRemove.addAll(staleToRemove)

            for (sb in allToRemove) {
                stableBlocks.remove(sb)
                DebugTimingLogger.logEvent("android.pc_ocr_replace_stale",
                    mapOf(
                        "stale" to sb.text.take(60),
                        "new" to newText.take(60)
                    ))
            }

            val sb = StableBlock(
                text = newText,
                x = r.left,
                y = r.top,
                w = r.width(),
                h = r.height(),
                lastSeenFrame = frameCounter,
                pendingText = null,
                pendingCount = 0,
                bboxSettled = true,
                bboxSettleCounter = 0
            )
            stableBlocks.add(sb)
            newBlocksCount++

            result.add(
                OcrBlock(
                    text = newText,
                    boundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h),
                    confidence = nb.confidence,
                    initialBoundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h),
                    elements = nb.elements
                )
            )
        }

        if (newBlocksCount > 0) {
            DebugTimingLogger.logEvent("android.pc_ocr_new_blocks",
                mapOf("count" to newBlocksCount, "total" to result.size))
        }

        pruneStableBlocks(stableBlocks, frameCounter)

        result.sortWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

        return result
    }

    private fun smoothBlockGeometry(
        sb: StableBlock,
        newX: Int, newY: Int, newW: Int, newH: Int
    ) {
        val dx = newX - sb.x
        val dy = newY - sb.y
        val dw = newW - sb.w
        val dh = newH - sb.h

        if (abs(dx) >= POS_CHANGE_THRESHOLD) {
            sb.x = sb.x + dx / 2
        }
        if (abs(dy) >= POS_CHANGE_THRESHOLD) {
            sb.y = sb.y + dy / 2
        }

        if (abs(dw) >= BIG_SIZE_CHANGE_THRESHOLD) {
            sb.w = newW
        } else if (abs(dw) >= SIZE_CHANGE_THRESHOLD) {
            sb.w = sb.w + dw / 2
        }

        if (abs(dh) >= BIG_SIZE_CHANGE_THRESHOLD) {
            sb.h = newH
        } else if (abs(dh) >= SIZE_CHANGE_THRESHOLD) {
            sb.h = sb.h + dh / 2
        }
    }

    fun pruneStableBlocks(stableBlocks: MutableList<StableBlock>, frameCounter: Long) {
        val threshold = frameCounter - STABLE_MISS_LIMIT
        val it = stableBlocks.iterator()
        while (it.hasNext()) {
            val sb = it.next()
            if (sb.lastSeenFrame < threshold) {
                it.remove()
            }
        }
    }

    private fun isBBoxContained(
        smallL: Int, smallT: Int, smallW: Int, smallH: Int,
        bigL: Int, bigT: Int, bigW: Int, bigH: Int,
        marginPx: Int
    ): Boolean {
        if (smallW <= 0 || smallH <= 0 || bigW <= 0 || bigH <= 0) return false
        val smallR = smallL + smallW
        val smallB = smallT + smallH
        val bigR = bigL + bigW
        val bigB = bigT + bigH
        return smallL >= bigL - marginPx &&
                smallT >= bigT - marginPx &&
                smallR <= bigR + marginPx &&
                smallB <= bigB + marginPx
    }

    private fun computeIoU(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Double {
        val ax2 = ax + aw
        val ay2 = ay + ah
        val bx2 = bx + bw
        val by2 = by + bh

        val ix1 = maxOf(ax, bx)
        val iy1 = maxOf(ay, by)
        val ix2 = minOf(ax2, bx2)
        val iy2 = minOf(ay2, by2)

        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0 || ih <= 0) return 0.0

        val inter = iw.toDouble() * ih.toDouble()
        val areaA = aw.toDouble() * ah.toDouble()
        val areaB = bw.toDouble() * bh.toDouble()
        val union = areaA + areaB - inter
        if (union <= 0) return 0.0
        return inter / union
    }

    private fun centerDistance(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Double {
        val acx = ax + aw / 2.0
        val acy = ay + ah / 2.0
        val bcx = bx + bw / 2.0
        val bcy = by + bh / 2.0
        return hypot(acx - bcx, acy - bcy)
    }

    private fun isContained(
        smallX: Int, smallY: Int, smallW: Int, smallH: Int,
        bigX: Int, bigY: Int, bigW: Int, bigH: Int,
        margin: Double
    ): Boolean {
        if (smallW <= 0 || smallH <= 0 || bigW <= 0 || bigH <= 0) return false

        val marginX = (bigW * margin).toInt()
        val marginY = (bigH * margin).toInt()

        val bigLeft = bigX - marginX
        val bigTop = bigY - marginY
        val bigRight = bigX + bigW + marginX
        val bigBottom = bigY + bigH + marginY

        val smallLeft = smallX
        val smallTop = smallY
        val smallRight = smallX + smallW
        val smallBottom = smallY + smallH

        return smallLeft >= bigLeft &&
                smallTop >= bigTop &&
                smallRight <= bigRight &&
                smallBottom <= bigBottom
    }
}