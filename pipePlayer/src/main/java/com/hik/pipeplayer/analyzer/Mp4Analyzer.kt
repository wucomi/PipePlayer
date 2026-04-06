package com.hik.pipeplayer.analyzer

import android.util.Log
import com.hik.pipeplayer.error.Mp4ParseException
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.HandlerBox
import org.mp4parser.boxes.iso14496.part12.MediaBox
import org.mp4parser.boxes.iso14496.part12.MediaHeaderBox
import org.mp4parser.boxes.iso14496.part12.MediaInformationBox
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min
import kotlin.math.roundToLong

class Mp4Analyzer(private val file: File) {
    companion object {
        const val TAG = "Mp4Analyzer"
    }

    fun analyzeStructureInfo(): Mp4StructureInfo {
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
                var boxSize = raf.readInt().toUInt().toLong()
                val boxType = ByteArray(4)
                raf.readFully(boxType)
                val type = String(boxType)

                // 处理 extended size (size == 1)
                if (boxSize == 1L) {
                    boxSize = raf.readLong()
                }
                // 处理 size == 0 (box 延伸到文件末尾)
                else if (boxSize == 0L) {
                    boxSize = fileSize - offset
                }

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

    /**
     * 按时间间隔获取MP4文件的片段范围
     */
    @Throws
    fun getSegmentRanges(intervalMs: Long, totalSize: Long): Mp4Segment {
        val currentTime = System.nanoTime()

        // 使用IsoFile解析MP4文件
        IsoFile(file).use { isoFile ->
            // 1. 获取moov box（存储视频元数据）
            val movieBox = isoFile.getBoxes(MovieBox::class.java).firstOrNull()
            if (movieBox == null) {
                Log.e(TAG, "No moov box found")
                throw Mp4ParseException()
            }

            // 2. 找到视频轨道
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

            // 3. 获取媒体信息和样本表
            val mediaBox = track.getBoxes(MediaBox::class.java).firstOrNull()
            val mediaInfo = mediaBox?.getBoxes(MediaInformationBox::class.java)?.firstOrNull()
            val sampleTable = mediaInfo?.getBoxes(SampleTableBox::class.java)?.firstOrNull()
            if (sampleTable == null) {
                Log.e(TAG, "No sample table found")
                throw Mp4ParseException()
            }

            // 4. 获取时间尺度和总时长
            val mediaHeader = mediaBox.getBoxes(MediaHeaderBox::class.java)?.firstOrNull()
            val timescale = 1000.0 / (mediaHeader?.timescale ?: 1000) // 时间单位，1000ms=多少时间单位
            val duration = mediaHeader?.duration ?: 0 // 总时长（时间单位）

            // 5. 获取关键映射表
            // stts: 时间到采样的映射数据
            val sttsEntries = sampleTable.timeToSampleBox?.entries ?: emptyList()
            // stsc: 采样到chunk的映射数据
            val stscEntries = sampleTable.sampleToChunkBox?.entries ?: emptyList()
            // stco: chunk到文件偏移量的映射数据
            val chunkOffsets = sampleTable.chunkOffsetBox?.chunkOffsets ?: LongArray(0)
            // 关键帧索引
            val syncSampleNumber = sampleTable.syncSampleBox?.sampleNumber ?: LongArray(0)

            // 预处理 sttsEntries（开始时间、开始索引、采样数量、每个采样的时间）
            val sttsInfos = mutableListOf<SttsEntryInfo>()
            var firstTime = 0L
            var firstSample = 0L
            for (entry in sttsEntries) {
                sttsInfos.add(
                    SttsEntryInfo(
                        firstTime,
                        firstSample,
                        entry.count,
                        entry.delta
                    )
                )
                firstTime += entry.count * entry.delta
                firstSample += entry.count
            }

            // 预处理 stscEntries （开始样本号、开始chunk索引、chunk数量、每个chunk的采样数）
            val stscInfos = mutableListOf<StscEntryInfo>()
            var firstSample1 = 0L
            for (i in stscEntries.indices) {
                val entry = stscEntries[i]
                val nextFirstChunk = if (i + 1 < stscEntries.size) {
                    stscEntries[i + 1].firstChunk
                } else {
                    chunkOffsets.size + 1L
                }
                val chunkCount = nextFirstChunk - entry.firstChunk
                stscInfos.add(
                    StscEntryInfo(
                        firstSample1,
                        entry.firstChunk,
                        chunkCount,
                        entry.samplesPerChunk,
                    )
                )
                firstSample1 += chunkCount * entry.samplesPerChunk
            }

            // 6. 计算片段范围
            val ranges = mutableListOf<SegmentRange>()
            val interval = (intervalMs / timescale).roundToLong()
            for (i in 1..(duration + interval - 1) / interval) {
                // 目标结束时间（毫秒）
                val targetEndTime = min(i * interval, duration)

                // 时间 -> 目标结束采样序号
                val sttsEntryInfo = sttsInfos.first {
                    it.firstTime + it.delta * it.count >= targetEndTime
                }
                val targetEndSample =
                    sttsEntryInfo.firstSample + (targetEndTime - sttsEntryInfo.firstTime) / sttsEntryInfo.delta

                // 目标结束采样序号 -> endChunk
                val stscEntryInfo = stscInfos.first {
                    it.firstSample + it.chunkCount * it.samplesPerChunk >= targetEndSample
                }
                val remainingSample = targetEndSample - stscEntryInfo.firstSample
                val samplesPerChunk = stscEntryInfo.samplesPerChunk
                val endChunk = stscEntryInfo.firstChunk + remainingSample / samplesPerChunk

                // endSample -> endOffset
                val endOffset = chunkOffsets[min(endChunk.toInt() - 1, chunkOffsets.size - 1)]

                // chunk -> endSample
                val endSample = targetEndSample - remainingSample % samplesPerChunk

                // endSample -> endTime
                val sttsEntryInfo1 = sttsInfos.first { it.firstSample + it.count >= endSample }
                val endTime =
                    sttsEntryInfo1.firstTime + (endSample - sttsEntryInfo1.firstSample) * sttsEntryInfo1.delta

                //  计算片段起始位置
                var startOffset = 0L // 起始偏移量
                var startTimeMs = 0L // 起始时间
                var firstSample = 0L // 首帧索引
                ranges.lastOrNull()?.let {
                    startOffset = it.endOffset + 1
                    startTimeMs = it.endTimeMs + 1
                    firstSample = it.endSample + 1
                }

                // 添加分段
                ranges.add(
                    SegmentRange(
                        startTimeMs,
                        ((if (targetEndTime == duration) duration else endTime) * timescale).toLong(),
                        startOffset,
                        if (targetEndTime == duration) totalSize else endOffset,
                        firstSample,
                        endSample,
                    )
                )
            }

            Log.d(TAG, "getSegmentRanges time: ${(System.nanoTime() - currentTime) / 1000 / 1000}")
            return Mp4Segment((duration * timescale).toLong(), ranges, syncSampleNumber.toList())
        }
    }
}
