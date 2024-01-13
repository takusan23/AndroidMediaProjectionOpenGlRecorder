package io.github.takusan23.androidmediaprojectionopenglrecorder

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenRecorder(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent
) {
    private val mediaProjectionManager by lazy { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    private var recordingJob: Job? = null
    private var openglMediaRecorder: OpenglMediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** 録画開始 */
    suspend fun startRecord() = coroutineScope {
        recordingJob = launch {
            initOpenGlMediaRecorder()
            initMediaProjection()
            try {
                // 録画開始
                openglMediaRecorder?.startRecordAndOpenGlLoop()
            } finally {
                // キャンセル時（録画終了時）
                // リソース開放
                mediaProjection?.stop()
                virtualDisplay?.release()
                withContext(NonCancellable) {
                    openglMediaRecorder?.stopRecordAndSaveVideoFolder()
                }
            }
        }
    }

    private suspend fun initOpenGlMediaRecorder() {
        openglMediaRecorder = OpenglMediaRecorder(context)
        openglMediaRecorder?.prepareRecorder(VIDEO_WIDTH, VIDEO_HEIGHT)
    }

    private suspend fun initMediaProjection() {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        // メインスレッドで呼ぶ
        withContext(Dispatchers.Main) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    super.onCapturedContentResize(width, height)
                    // サイズが変化したら呼び出される
                    // do nothing
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    super.onCapturedContentVisibilityChanged(isVisible)
                    // 録画中の画面の表示・非表示が切り替わったら呼び出される
                    openglMediaRecorder?.isDrawAltImage = !isVisible
                }

                override fun onStop() {
                    super.onStop()
                    // MediaProjection 終了時
                    // do nothing
                }
            }, null)
        }
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "io.github.takusan23.androidmediaprojectionopenglrecorder",
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            context.resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            openglMediaRecorder?.openGlTextureSurface,
            null,
            null
        )
    }

    /** 録画終了 */
    suspend fun stopRecord() {
        // キャンセルする。終了するまで待つ
        recordingJob?.cancelAndJoin()
    }

    companion object {
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
    }

}