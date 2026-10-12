// OcrBlockStabilizer.kt (полный файл)
package com.example.screentranslator

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Стабилизация блоков между кадрами.
 *
 * ИЗМЕНЕНО (устранение "старые блоки залипают на экране"):
 *
 *   1) STABLE_MISS_LIMIT уменьшен с 15 до 5 кадров (~0.8 сек при FPS 6).
 *      Раньше блок жил 2.5 сек после исчезновения — этого хватало,
 *      чтобы при смене меню старые пункты ("Запуск", "Запустить главу")
 *      оставались в stableBlocks и продолжали отправляться на ПК.
 *
 *   2) При containment-матче (новый bbox внутри старого или наоборот)
 *      lastSeenFrame БОЛЬШЕ НЕ ПРОДЛЕВАЕТСЯ безусловно. Продление
 *      происходит только если текст совпадает или похож. Если в той же
 *      геометрической зоне появился ДРУГОЙ текст — это новый UI-элемент,
 *      а не тот же самый. Старый блок должен быть удалён через
 *      pruneStableBlocks, а новый — добавлен как отдельный.
 *
 *      Раньше containment-ветка безусловно делала:
 *          sb.lastSeenFrame = frameCounter
 *      и старый текст ("Start Chapter") оставался в stableBlocks
 *      даже когда на экране был уже "Select a file to continue.".
 *
 *   3) Добавлено диагностическое событие android.stabilizer_containment_mismatch
 *      для отладки случаев, когда новый текст в старой зоне не совпал.
 *
 *   Сохранено из предыдущих версий:
 *     - При срабатывании pendingCount >= STABLE_TEXT_UPDATE_THRESHOLD
 *       сразу обновляется bbox из nb.boundingBox.
 *     - В result.add(...) для сматченного stable-блока используется
 *       sb.text вместо newText.
 *     - STABLE_TEXT_UPDATE_THRESHOLD: 3.
 *     - POS_CHANGE_THRESHOLD: 25, SIZE_CHANGE_THRESHOLD: 30.
 *     - BBOX_SETTLE_FRAMES: 3, GEO_UPDATE_THRESHOLD: 3, GEO_DEAD_ZONE: 8.
 */
object OcrBlockStabilizer {

    private const val TAG = "OcrBlockStabilizer"

    private const val STABLE_IOU_THRESHOLD = 0.3
    private const val CENTER_MATCH_DIST = 60.0
    private const val CONTAINMENT_MARGIN = 0.3
    private const val STABLE_MISS_LIMIT = 5L

    private const val STABLE_TEXT_UPDATE_THRESHOLD = 3

    private const val EVICTION_IOU = 0.3
    private const val REPLACE_IOU = 0.5
    private const val CONTAINMENT_MARGIN_PX = 20

    private const val POS_CHANGE_THRESHOLD = 25
    private const val SIZE_CHANGE_THRESHOLD = 30
    private const val BIG_SIZE_CHANGE_THRESHOLD = 120

    private const val BBOX_SETTLE_FRAMES = 3
    private const val GEO_UPDATE_THRESHOLD = 3
    private const val GEO_DEAD_ZONE = 8

    private const val STABILIZER_VERSION = 6

    @Volatile private var versionLogged = false

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
        var bboxSettleCounter: Int = 0,
        var pendingDx: Int = 0,
        var pendingDy: Int = 0,
        var pendingDw: Int = 0,
        var pendingDh: Int = 0,
        var pendingGeoCount: Int = 0,
        var pendingGeoSignDx: Int = 0,
        var pendingGeoSignDy: Int = 0,
        var pendingGeoSignDw: Int = 0,
        var pendingGeoSignDh: Int = 0
    )

    fun stabilizeBlocks(
        blocks: List<OcrBlock>,
        stableBlocks: MutableList<StableBlock>,
        frameCounter: Long,
        sceneChanged: Boolean
    ): List<OcrBlock> {
        if (!versionLogged) {
            versionLogged = true
            DebugTimingLogger.logEvent("android.stabilizer_version",
                mapOf(
                    "v" to STABILIZER_VERSION,
                    "text_update_threshold" to STABLE_TEXT_UPDATE_THRESHOLD,
                    "pos_threshold" to POS_CHANGE_THRESHOLD,
                    "size_threshold" to SIZE_CHANGE_THRESHOLD,
                    "miss_limit" to STABLE_MISS_LIMIT,
                    "fix" to "containment_no_prolong_on_text_mismatch"
                ))
        }

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

            // Логика обновления текста.
            if (newText == sb.text) {
                sb.pendingText = null
                sb.pendingCount = 0

                if (sb.bboxSettled) {
                    accumulateGeometry(sb, nb.boundingBox)
                } else {
                    sb.bboxSettleCounter++
                    if (sb.bboxSettleCounter >= BBOX_SETTLE_FRAMES) {
                        sb.bboxSettled = true
                        resetGeoAccumulator(sb)
                        accumulateGeometry(sb, nb.boundingBox)
                    }
                }
            } else if (OcrTextNormalizer.textsAreSimilar(sb.text, newText)) {
                // OCR-шум — не заменяем.
                DebugTimingLogger.logEvent("android.stabilizer_noise_skipped",
                    mapOf(
                        "old" to sb.text.take(60),
                        "new" to newText.take(60)
                    ))

                sb.pendingText = null
                sb.pendingCount = 0

                if (sb.bboxSettled) {
                    accumulateGeometry(sb, nb.boundingBox)
                } else {
                    sb.bboxSettleCounter++
                    if (sb.bboxSettleCounter >= BBOX_SETTLE_FRAMES) {
                        sb.bboxSettled = true
                        resetGeoAccumulator(sb)
                        accumulateGeometry(sb, nb.boundingBox)
                    }
                }
            } else if (newText == sb.pendingText) {
                sb.pendingCount++
                if (sb.pendingCount >= STABLE_TEXT_UPDATE_THRESHOLD) {
                    // При замене текста сразу обновляем bbox
                    // из нового блока.
                    val oldText = sb.text
                    val oldBbox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h)
                    val newBbox = nb.boundingBox

                    val bboxChanged = (
                            oldBbox.left != newBbox.left ||
                                    oldBbox.top != newBbox.top ||
                                    oldBbox.width() != newBbox.width() ||
                                    oldBbox.height() != newBbox.height()
                            )

                    sb.text = newText

                    if (bboxChanged) {
                        sb.x = newBbox.left
                        sb.y = newBbox.top
                        sb.w = newBbox.width()
                        sb.h = newBbox.height()

                        DebugTimingLogger.logEvent("android.stabilizer_bbox_replaced",
                            mapOf(
                                "text" to newText.take(60),
                                "old_x" to oldBbox.left,
                                "old_y" to oldBbox.top,
                                "old_w" to oldBbox.width(),
                                "old_h" to oldBbox.height(),
                                "new_x" to newBbox.left,
                                "new_y" to newBbox.top,
                                "new_w" to newBbox.width(),
                                "new_h" to newBbox.height()
                            ))
                    }

                    DebugTimingLogger.logEvent("android.stabilizer_text_replaced",
                        mapOf(
                            "old" to oldText.take(60),
                            "new" to newText.take(60),
                            "pending_count" to sb.pendingCount,
                            "bbox_changed" to bboxChanged
                        ))

                    sb.pendingText = null
                    sb.pendingCount = 0
                    sb.bboxSettled = false
                    sb.bboxSettleCounter = 0
                    resetGeoAccumulator(sb)
                }
            } else {
                sb.pendingText = newText
                sb.pendingCount = 1
            }

            result.add(
                OcrBlock(
                    text = sb.text,
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

                // Новый блок внутри старого.
                if (isBBoxContained(
                        r.left, r.top, r.width(), r.height(),
                        sb.x, sb.y, sb.w, sb.h,
                        CONTAINMENT_MARGIN_PX
                    )
                ) {
                    val textSame = (newText == sb.text)
                    val textSimilar = OcrTextNormalizer.textsAreSimilar(sb.text, newText)

                    // ИЗМЕНЕНО: продлеваем lastSeenFrame ТОЛЬКО если
                    // текст совпадает или похож. Если текст другой —
                    // это новый UI-элемент в той же зоне, старый блок
                    // не должен считаться "увиденным".
                    if (textSame || textSimilar) {
                        sb.lastSeenFrame = frameCounter

                        if (textSame) {
                            accumulateGeometry(sb, r)
                        } else {
                            accumulateGeometry(sb, r)
                        }

                        isDuplicate = true
                        DebugTimingLogger.logEvent("android.pc_ocr_skip_contained",
                            mapOf(
                                "new" to newText.take(60),
                                "existing" to sb.text.take(60),
                                "direction" to "new_in_stable",
                                "refreshed" to true
                            ))
                    } else {
                        // Текст в той же зоне РАЗНЫЙ — не продлеваем.
                        // Логируем для диагностики. Старый блок
                        // удалится через pruneStableBlocks, если
                        // больше не появится.
                        DebugTimingLogger.logEvent(
                            "android.stabilizer_containment_mismatch",
                            mapOf(
                                "new" to newText.take(60),
                                "existing" to sb.text.take(60),
                                "direction" to "new_in_stable",
                                "action" to "no_prolong"
                            ))
                    }
                    break
                }

                // Старый блок внутри нового.
                if (isBBoxContained(
                        sb.x, sb.y, sb.w, sb.h,
                        r.left, r.top, r.width(), r.height(),
                        CONTAINMENT_MARGIN_PX
                    )
                ) {
                    val textSame = (newText == sb.text)
                    val textSimilar = OcrTextNormalizer.textsAreSimilar(sb.text, newText)

                    // ИЗМЕНЕНО: аналогично — продлеваем только при
                    // совпадении или похожести текста.
                    if (textSame || textSimilar) {
                        sb.lastSeenFrame = frameCounter
                        containsToRemove.add(sb)
                    } else {
                        DebugTimingLogger.logEvent(
                            "android.stabilizer_containment_mismatch",
                            mapOf(
                                "new" to newText.take(60),
                                "existing" to sb.text.take(60),
                                "direction" to "stable_in_new",
                                "action" to "no_prolong"
                            ))
                    }
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

    private fun resetGeoAccumulator(sb: StableBlock) {
        sb.pendingDx = 0
        sb.pendingDy = 0
        sb.pendingDw = 0
        sb.pendingDh = 0
        sb.pendingGeoCount = 0
        sb.pendingGeoSignDx = 0
        sb.pendingGeoSignDy = 0
        sb.pendingGeoSignDw = 0
        sb.pendingGeoSignDh = 0
    }

    private fun accumulateGeometry(sb: StableBlock, newBox: Rect) {
        val dx = newBox.left - sb.x
        val dy = newBox.top - sb.y
        val dw = newBox.width() - sb.w
        val dh = newBox.height() - sb.h

        val dxSign = when {
            abs(dx) < GEO_DEAD_ZONE -> 0
            dx > 0 -> 1
            else -> -1
        }
        val dySign = when {
            abs(dy) < GEO_DEAD_ZONE -> 0
            dy > 0 -> 1
            else -> -1
        }
        val dwSign = when {
            abs(dw) < GEO_DEAD_ZONE -> 0
            dw > 0 -> 1
            else -> -1
        }
        val dhSign = when {
            abs(dh) < GEO_DEAD_ZONE -> 0
            dh > 0 -> 1
            else -> -1
        }

        if (dxSign == 0 && dySign == 0 && dwSign == 0 && dhSign == 0) {
            resetGeoAccumulator(sb)
            return
        }

        val sameSigns = (dxSign == 0 || dxSign == sb.pendingGeoSignDx) &&
                (dySign == 0 || dySign == sb.pendingGeoSignDy) &&
                (dwSign == 0 || dwSign == sb.pendingGeoSignDw) &&
                (dhSign == 0 || dhSign == sb.pendingGeoSignDh)

        if (!sameSigns) {
            sb.pendingGeoSignDx = dxSign
            sb.pendingGeoSignDy = dySign
            sb.pendingGeoSignDw = dwSign
            sb.pendingGeoSignDh = dhSign
            sb.pendingGeoCount = 1
            sb.pendingDx = dx
            sb.pendingDy = dy
            sb.pendingDw = dw
            sb.pendingDh = dh
            return
        }

        sb.pendingGeoSignDx = dxSign
        sb.pendingGeoSignDy = dySign
        sb.pendingGeoSignDw = dwSign
        sb.pendingGeoSignDh = dhSign
        sb.pendingGeoCount++
        sb.pendingDx = dx
        sb.pendingDy = dy
        sb.pendingDw = dw
        sb.pendingDh = dh

        if (sb.pendingGeoCount >= GEO_UPDATE_THRESHOLD) {
            applyAccumulatedGeometry(sb)
            resetGeoAccumulator(sb)
        }
    }

    private fun applyAccumulatedGeometry(sb: StableBlock) {
        val dx = sb.pendingDx
        val dy = sb.pendingDy
        val dw = sb.pendingDw
        val dh = sb.pendingDh

        if (abs(dx) >= POS_CHANGE_THRESHOLD) {
            sb.x += dx / 2
        }
        if (abs(dy) >= POS_CHANGE_THRESHOLD) {
            sb.y += dy / 2
        }

        if (abs(dw) >= BIG_SIZE_CHANGE_THRESHOLD) {
            sb.w = sb.w + dw
        } else if (abs(dw) >= SIZE_CHANGE_THRESHOLD) {
            sb.w += dw / 2
        }

        if (abs(dh) >= BIG_SIZE_CHANGE_THRESHOLD) {
            sb.h = sb.h + dh
        } else if (abs(dh) >= SIZE_CHANGE_THRESHOLD) {
            sb.h += dh / 2
        }
    }

    private fun smoothBlockGeometryImmediate(sb: StableBlock, newBox: Rect) {
        smoothBlockGeometry(
            sb,
            newBox.left,
            newBox.top,
            newBox.width(),
            newBox.height()
        )
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