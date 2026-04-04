package com.hik.pipeplayer.analyzer

import android.util.Log
import com.hik.pipeplayer.error.Mp4ParseException
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.ChunkOffsetBox
import org.mp4parser.boxes.iso14496.part12.HandlerBox
import org.mp4parser.boxes.iso14496.part12.MediaBox
import org.mp4parser.boxes.iso14496.part12.MediaHeaderBox
import org.mp4parser.boxes.iso14496.part12.MediaInformationBox
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.SampleToChunkBox
import org.mp4parser.boxes.iso14496.part12.TimeToSampleBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object Mp4Analyzer {
    const val TAG = "Mp4Analyzer"
    fun analyze(file: File): Mp4StructureInfo {
        val currentTime = System.nanoTime()
        RandomAccessFile(file, "r").use { raf ->
            var ftypSize = 0L
            var moovOffset = 0L
            var moovSize = 0L
            var mdatOffset = 0L
            var mdatSize = 0L

            var offset = 0L
            val fileSize = raf.length()

            while (offset < fileSize) {
                raf.seek(offset)
                val boxSize = raf.readInt().toLong()
                val boxType = ByteArray(4)
                raf.readFully(boxType)
                val type = String(boxType)

                when (type) {
                    "ftyp" -> ftypSize = boxSize
                    "moov" -> {
                        moovOffset = offset
                        moovSize = boxSize
                    }

                    "mdat" -> {
                        mdatOffset = offset
                        mdatSize = boxSize
                    }
                }

                offset += boxSize
            }

            Log.d(TAG, "analyze time: ${(System.nanoTime() - currentTime) / 1000 / 1000}")
            return Mp4StructureInfo(
                ftypSize = ftypSize,
                moovOffset = moovOffset,
                moovSize = moovSize,
                mdatOffset = mdatOffset,
                mdatSize = mdatSize
            )
        }
    }

    @Throws
    fun getSegmentRanges(file: File, intervalMs: Long): List<SegmentRange> {
        val currentTime = System.nanoTime()
        IsoFile(file).use { isoFile ->
            val movieBox = isoFile.getBoxes(MovieBox::class.java).firstOrNull()
            if (movieBox == null) {
                Log.e(TAG, "No moov box found")
                throw Mp4ParseException()
            }

            val track = movieBox.getBoxes(TrackBox::class.java)
                .firstOrNull { trackBox ->
                    val handler = trackBox.getBoxes(MediaBox::class.java)
                        .firstOrNull()
                        ?.getBoxes(HandlerBox::class.java)
                        ?.firstOrNull()
                    handler?.handlerType == "vide"
                }
            if (track == null) {
                Log.e(TAG, "No video track found")
                throw Mp4ParseException()
            }

            val mediaBox = track.getBoxes(MediaBox::class.java).firstOrNull()
            val mediaInfo = mediaBox?.getBoxes(MediaInformationBox::class.java)?.firstOrNull()
            val sampleTable = mediaInfo?.getBoxes(SampleTableBox::class.java)?.firstOrNull()
            if (sampleTable == null) {
                Log.e(TAG, "No sample table found")
                throw Mp4ParseException()
            }

            val mediaHeader = mediaBox.getBoxes(MediaHeaderBox::class.java)?.firstOrNull()
            val timescale = mediaHeader?.timescale ?: 1000 // 时间单位，1s=多少时间单位
            val duration = mediaHeader?.duration ?: 0
            val durationMs = (duration * 1000) / timescale

            val sttsBox = sampleTable.getBoxes(TimeToSampleBox::class.java).firstOrNull()
            val stscBox = sampleTable.getBoxes(SampleToChunkBox::class.java).firstOrNull()
            val stcoBox = sampleTable.getBoxes(ChunkOffsetBox::class.java).firstOrNull()

            val sttsEntries = sttsBox?.entries ?: emptyList()
            val stscEntries = stscBox?.entries ?: emptyList()
            val chunkOffsets = stcoBox?.chunkOffsets ?: LongArray(0)

            val ranges = mutableListOf<SegmentRange>()
            for (i in 1 until durationMs / intervalMs) {
                val targetEndTime = i * intervalMs
                // 1. 时间 -> 目标采样序号
                var targetSample = 0L
                var timeMs = 0L
                for (entry in sttsEntries) {
                    val entryTimeMS = (entry.delta * 1000f / timescale).roundToInt()
                    val entryTotalTimeMs = entry.count * entryTimeMS
                    val remainingTimeMs = targetEndTime - timeMs
                    if (remainingTimeMs > entryTotalTimeMs) {
                        targetSample += entry.count
                        timeMs += entryTotalTimeMs
                    } else {
                        val count = (remainingTimeMs.toFloat() / entryTimeMS + 0.5).toLong()
                        targetSample += count
                        break
                    }
                }

                // 2. 目标采样序号 -> chunk -> endOffset/endSample
                var sampleCount = 0L
                var endChunk = 0L
                var endSample = 0L
                var endOffset = 0L

                for (i in stscEntries.indices) {
                    val entry = stscEntries[i]
                    // 获取下一个起始块的索引，最后一个是通过块的个数+1计算
                    val nextFirstChunk = if (i + 1 < stscEntries.size) {
                        stscEntries[i + 1].firstChunk
                    } else chunkOffsets.size + 1L
                    val chunkCount = nextFirstChunk - entry.firstChunk
                    val samplesInEntry = chunkCount * entry.samplesPerChunk

                    if (sampleCount + samplesInEntry > targetSample) {
                        // targetSample 在这个 entry 里
                        val remainingSamples = targetSample - sampleCount
                        val chunkCount =
                            (remainingSamples.toFloat() / entry.samplesPerChunk + 0.5).toLong()
                        endChunk = entry.firstChunk + chunkCount
                        endSample = sampleCount + chunkCount * entry.samplesPerChunk

                        endOffset = if (endChunk < chunkOffsets.size - 1) {
                            chunkOffsets[(endChunk + 1).toInt()] - 1
                        } else {
                            file.length()
                        }
                        break
                    }
                    sampleCount += samplesInEntry
                }

                // 3. endSample -> endTimeMs
                var endTimeMs = 0L
                var sampleTotal = 0L
                for (entry in sttsEntries) {
                    val entryTimeMS = (entry.delta * 1000f / timescale).roundToInt()
                    val entryTotalTimeMs = entry.count * entryTimeMS
                    val remainingSamples = endSample - sampleTotal
                    if (remainingSamples > entry.count) {
                        sampleTotal += entry.count
                        endTimeMs += entryTotalTimeMs
                    } else {
                        endTimeMs += remainingSamples * entryTimeMS
                        break
                    }
                }

                var startOffset = 0L
                var startTimeMs = 0L
                ranges.lastOrNull()?.let {
                    startOffset = it.endOffset + 1
                    startTimeMs = it.endTimeMs + 1
                }
                ranges.add(SegmentRange(startTimeMs, endTimeMs, startOffset, endOffset))
            }

            // 添加最后一个分片
            var startOffset = 0L
            var startTimeMs = 0L
            ranges.lastOrNull()?.let {
                startOffset = it.endOffset + 1
                startTimeMs = it.endTimeMs + 1
            }
            ranges.add(SegmentRange(startTimeMs, durationMs, startOffset, file.length()))
            Log.d(TAG, "getSegmentRanges time: ${(System.nanoTime() - currentTime) / 1000 / 1000}")
            return ranges
        }
    }
}
