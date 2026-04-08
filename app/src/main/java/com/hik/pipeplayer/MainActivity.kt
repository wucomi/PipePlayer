package com.hik.pipeplayer

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hik.pipeplayer.cache.CacheCallback
import com.hik.pipeplayer.cache.StreamLoader
import com.hik.pipeplayer.databinding.ActivityMainBinding
import com.hik.pipeplayer.download.HttpDownloader
import com.hik.pipeplayer.error.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), CacheCallback {

    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var streamingLoader: StreamLoader? = null

    private var isPlaying = false
    private var isBuffering = false
    private var currentVideoUrl: String? = null
    private var pendingSeekPosition: Int = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private var startLoadStreamTime: Long = 0
    private var startSeekTime: Long = 0
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isPlaying) {
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTextureView()
        setupControls()
    }

    private fun setupTextureView() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                initMediaPlayer()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseMediaPlayer()
                surface?.release()
                surface = null
                return true
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            surface?.let { setSurface(it) }

            setOnPreparedListener {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "就绪"

                // 设置视频比例
                val videoWidth = it.videoWidth
                val videoHeight = it.videoHeight
                if (videoWidth > 0 && videoHeight > 0) {
                    binding.textureView.setVideoSize(videoWidth, videoHeight)
                }

                start()
                this@MainActivity.isPlaying = true
                binding.btnPause.isEnabled = true
                updatePlayPauseButton()
                startProgressUpdate()
            }

            setOnCompletionListener {
                this@MainActivity.isPlaying = false
                updatePlayPauseButton()
                binding.tvStatus.text = "播放完成"
            }

            setOnErrorListener { _, what, _ ->
                binding.tvStatus.text = "播放错误: $what"
                binding.progressBar.visibility = View.GONE
                true
            }
        }
    }
    class ReuseStreamDataSource(private val url: String) : MediaDataSource() {
        private var connection: HttpURLConnection? = null
        private var inputStream: InputStream? = null
        private var currentPosition: Long = -1
        private var contentLength: Long = -1

        override fun getSize(): Long {
            if (contentLength == -1L) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                contentLength = conn.contentLengthLong
                conn.disconnect()
            }
            return contentLength
        }

        override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
            println("position = [${position}], buffer = [${buffer?.size}], offset = [${offset}], size = [${size}]==============")
            if (buffer == null || size <= 0) return 0

            // 位置不匹配，重建连接
            if (position != currentPosition || inputStream == null) {
                reconnect(position)
            }

            return try {
                val read = inputStream!!.read(buffer, offset, size)
                if (read > 0) {
                    currentPosition += read
                }
                read
            } catch (_: IOException) {
                // 连接断开，重试一次
                reconnect(position)
                inputStream!!.read(buffer, offset, size).also {
                    if (it > 0) currentPosition += it
                }
            }
        }

        private fun reconnect(position: Long) {
            // 关闭旧连接
            inputStream?.close()
            connection?.disconnect()

            // 建立新连接，Range 定位到指定位置
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=$position-")
            conn.setRequestProperty("Connection", "keep-alive")

            connection = conn
            inputStream = conn.inputStream
            currentPosition = position
        }

        override fun close() {
            inputStream?.close()
            connection?.disconnect()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("SetTextI18n")
    private fun setupControls() {
//        thread {
//
//
//            // 使用
//            val extractor = MediaExtractor()
//            extractor.setDataSource(ReuseStreamDataSource("https://v5-se-jltc-default.365yg.com/ccc6cba0794110b0b79d8adaeb28de38/69d678b9/video/tos/cn/tos-cn-v-0015c002/o8uUEELEGKi40CpgAIewAWgPfLqUETBBMe87Sp/?a=0&br=59958&bt=59958&btag=80000e00030000&cd=0%7C0%7C0%7C0&ch=0&cquery=106H&cr=0&cv=1&dr=0&dy_q=1775659095&dy_va_biz_cert=&er=6&ft=k7Fz7VVywIiRZm8Zmo~pK7pswApemQf_vrKlISd2do0g3cI&l=202604082238156C52C96BF375B0343371&lr=unwatermarked&mime_type=video_mp4&net=5&qs=13&rc=MzNvbHk5cjR0OTMzNGkzM0BpMzNvbHk5cjR0OTMzNGkzM0Byc2ReMmRjYmhhLS1kLS9zYSNyc2ReMmRjYmhhLS1kLS9zcw%3D%3D"))
////            val serverSocket = ServerSocket(5555)
////            val accept = serverSocket.accept()
////            println(accept.getInputStream().readBytes().toHexString()+"==================")
//
//        thread {
//            while (true) {
//                println("===${extractor.cachedDuration}=====${extractor.getTrackFormat(0)}=====${extractor.sampleTime}")
//                Thread.sleep(500)
//            }
//        }
//        }

        progressHandler.postDelayed({
//            val extractor = MediaExtractor()
//            extractor.setDataSource("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/10/80/36603168010/36603168010-1-192.mp4?e=ig8euxZM2rNcNbRVhwdVhwdlhWdVhwdVhoNvNC8BqJIzNbfq9rVEuxTEnE8L5F6VnEsSTx0vkX8fqJeYTj_lta53NCM=&uipk=5&platform=html5&trid=4cd1c74cae3c43708e339da05cfee26O&deadline=1775497658&mid=0&gen=playurlv3&og=hw&oi=1385955528&nbs=1&os=estghw&upsig=f2d795dab22316a2fc420dd19c1d5c3d&uparams=e,uipk,platform,trid,deadline,mid,gen,og,oi,nbs,os&bvc=vod&nettype=1&bw=524051&agrr=0&buvid=&build=7330300&dl=0&f=O_0_0&orderid=0,3")
//            lifecycleScope.launch {
//                while (true) {
//                    println("===${extractor.cachedDuration}=====${extractor.getTrackFormat(0)}=====${extractor.sampleTime}")
//                    delay(200)
//                }
//            }
//            progressHandler.postDelayed({
//                // 必须先获取并选中轨道！
//                val trackCount = extractor.trackCount
//                Log.e("XXX", "Tracks: $trackCount")
//
//                if (trackCount == 0) {
//                    Log.e("XXX", "No tracks found!")
//                    return@postDelayed
//                }
//                // 选中第一个视频轨道
//                for (i in 0 until trackCount) {
//                    val format = extractor.getTrackFormat(i)
//                    val mime = format.getString(MediaFormat.KEY_MIME)
//                    Log.e("XXX", "Track $i: $mime")
//
//                    if (mime?.startsWith("video/") == true) {
//                        extractor.selectTrack(i)  // ← 关键！必须选中轨道
//                        Log.e("XXX", "Selected track $i")
//                        break
//                    }
//                }
//
//                extractor.seekTo(200000000, SEEK_TO_PREVIOUS_SYNC)
////                val buffer = ByteBuffer.allocate(1024 * 1024)
////                extractor.readSampleData(buffer, 0) // ← 第一次！触发网络请求该位置数据
//                println("===============1================")
//progressHandler.postDelayed({
//    println("===================2============")
//
//    extractor.seekTo(400000000, SEEK_TO_PREVIOUS_SYNC)
//},2000)
//            },200)
        },5000)

        binding.btnPlay.setOnClickListener {
            val url = binding.etVideoUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startPlayback(url)
            }
        }

        binding.btnPause.setOnClickListener {
            if (isPlaying) pausePlayback()
            else resumePlayback()
        }

        binding.btnStop.setOnClickListener {
            stopPlayback()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = mediaPlayer?.duration ?: 0
                updateTimeText(progress.toLong(), duration.toLong())

                if (!fromUser) {
                    streamingLoader?.seekTo(progress.toLong())
                } else {
                    startSeekTime = System.nanoTime()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                pausePlayback()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val position = sb!!.progress
                pendingSeekPosition = position
                // 拖动结束时同步进度并触发缓存检查
                streamingLoader?.seekTo(position.toLong())
            }
        })

        binding.etVideoUrl.setText("http://192.168.124.21:55554/b0faed6acb03efb0b61a2aa2d82d1b64.mp4")
    }

    private fun startPlayback(url: String) {
        startLoadStreamTime = System.nanoTime()

        currentVideoUrl = url

        streamingLoader?.release()
        val cacheDir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        streamingLoader = StreamLoader(cacheDir, url).apply {
            callback = this@MainActivity
            setDownloader(HttpDownloader())
            setSegmentTime(5)
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "准备中..."

        streamingLoader?.start()
    }

    @SuppressLint("SetTextI18n")
    private fun playVideo(path: String) {
        Log.i(TAG, "首屏时间: ${(System.nanoTime() - startLoadStreamTime) / 1000f / 1000 / 1000}")
        try {
            mediaPlayer?.apply {
                // 确保在调用 reset 前处于可重置状态
                if (isPlaying) {
                    stop()
                }
                reset()
                setDataSource(path)
                prepareAsync()
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "播放失败: ${e.message}"
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun pausePlayback() {
        if (isBuffering) {
            binding.tvStatus.text = "缓冲中..."
            binding.progressBar.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = "已暂停"
        }
        if (!isPlaying) return
        mediaPlayer?.pause()
        isPlaying = false
        stopProgressUpdate()
        updatePlayPauseButton()
    }

    private fun resumePlayback() {
        if (isBuffering) {
            binding.tvStatus.text = "缓冲中..."
            return
        }
        binding.tvStatus.text = "播放中"
        if (pendingSeekPosition != 0) {
            mediaPlayer?.seekTo(pendingSeekPosition)
            pendingSeekPosition = 0
        }
        if (isPlaying) return
        binding.progressBar.visibility = View.GONE
        mediaPlayer?.start()
        isPlaying = true
        lifecycleScope.launch {
            delay(500)
            startProgressUpdate()
        }
        updatePlayPauseButton()
    }

    @SuppressLint("SetTextI18n")
    private fun stopPlayback() {
        stopProgressUpdate()
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
        }
        isPlaying = false
        isBuffering = false
        binding.btnPause.isEnabled = false
        updatePlayPauseButton()
        binding.seekBar.progress = 0
        binding.tvProgress.text = "00:00 / 00:00"
        binding.tvStatus.text = "已停止"
    }

    private fun updatePlayPauseButton() {
        binding.btnPause.text = if (isPlaying) "暂停" else "继续"
    }

    @SuppressLint("SetTextI18n")
    private fun updateTimeText(position: Long, duration: Long) {
        binding.tvProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun updateProgress() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return

        val position = player.currentPosition
        val duration = player.duration
        if (duration > 0) {
            binding.seekBar.max = duration
            binding.seekBar.progress = position
        }
    }

    private fun startProgressUpdate() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdate() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    @SuppressLint("DefaultLocale")
    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    // ========== CacheCallback ==========

    override fun onReady(filePath: String) {
        binding.progressBar.visibility = View.GONE
        playVideo(filePath)
    }

    override fun onBufferingLack() {
        isBuffering = true
        pausePlayback()
    }

    override fun onBufferingReady() {
        isBuffering = false
        resumePlayback()
        if (startSeekTime != 0L) {
            Log.i(TAG, "seek恢复时间: ${(System.nanoTime() - startSeekTime) / 1000f / 1000 / 1000}s")
            startSeekTime = 0L
        }
    }

    override fun onComplete(filePath: String) {
        binding.tvStatus.text = "缓存完成"
    }

    @SuppressLint("SetTextI18n")
    override fun onError(errorCode: Int) {
        binding.tvStatus.text = "缓存错误($errorCode): ${ErrorCode.getDescription(errorCode)}"
        binding.progressBar.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        resumePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
        streamingLoader?.release()
    }

    private fun releaseMediaPlayer() {
        stopProgressUpdate()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        isBuffering = false
    }

    companion object {
        const val TAG = "MainActivity"
    }
}