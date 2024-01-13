package io.github.takusan23.androidmediaprojectionopenglrecorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.contentValuesOf
import io.github.takusan23.androidmediaprojectionopenglrecorder.opengl.InputSurface
import io.github.takusan23.androidmediaprojectionopenglrecorder.opengl.TextureRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class OpenglMediaRecorder(private val context: Context) {

    // OpenGL はスレッドでコンテキストを識別するので、OpenGL 関連はこの openGlRelatedDispatcher から呼び出す
    private val openGlRelatedScope = CoroutineScope(Dispatchers.Default + Job())
    private val openGlRelatedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var inputOpenGlSurface: InputSurface? = null

    val openGlTextureSurface: Surface?
        get() = inputOpenGlSurface?.drawSurface

    /** OpenGL と MediaRecorder の初期化を行う */
    suspend fun prepareRecorder(
        videoWidth: Int,
        videoHeight: Int,
        videoBitrate: Int = 6_000_000,
        audioBitrate: Int = 192_000
    ) {
        // 意味がないような launch だが、
        // OpenGL は makeCurrent したスレッドでしか swapBuffers できないので、必要
        openGlRelatedScope.launch(openGlRelatedDispatcher) {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
                // 呼び出し順があるので注意
                // TODO 音声
                // setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                // setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // setAudioChannels(2)
                setVideoEncodingBitRate(videoBitrate)
                setVideoFrameRate(60)
                // 解像度、縦動画の場合は、代わりに回転情報を付与する（縦横の解像度はそのまま）
                setVideoSize(videoWidth, videoHeight)
                // setAudioEncodingBitRate(audioBitrate)
                // setAudioSamplingRate(44_100)
                // 保存先
                // 動画フォルダーに入れる
                recordingFile = context.getExternalFilesDir(null)?.resolve("MediaProjectionOpenGlRecorder_${System.currentTimeMillis()}.mp4")
                setOutputFile(recordingFile!!.path)
                prepare()
            }

            // エンコーダー での入力前に OpenGL を経由する
            inputOpenGlSurface = InputSurface(mediaRecorder?.surface!!, TextureRenderer())
            inputOpenGlSurface?.makeCurrent()
            inputOpenGlSurface?.createRender(videoWidth, videoHeight)
        }.join()
    }

    /**
     * 録画を開始して、OpenGL の描画を開始する。
     * キャンセルされるまで一時停止します（キャンセルされるまで呼び出し元に戻りません）
     */
    suspend fun startRecordAndOpenGlLoop() {
        openGlRelatedScope.launch(openGlRelatedDispatcher) {
            mediaRecorder?.start()
            while (isActive) {
                try {
                    // 映像フレームが来ていれば OpenGL のテクスチャを更新
                    val isNewFrameAvailable = inputOpenGlSurface?.awaitIsNewFrameAvailable()
                    // 描画する
                    if (isNewFrameAvailable == true) {
                        inputOpenGlSurface?.makeCurrent()
                        inputOpenGlSurface?.updateTexImage()
                        inputOpenGlSurface?.drawImage()
                        inputOpenGlSurface?.swapBuffers()
                    }
                } catch (e: Exception) {
                    e.printStackTrace(System.out)
                }
            }
        }.join()
    }

    /** 終了時。動画ファイルが端末の動画フォルダに保存されます。 */
    suspend fun stopRecordAndSaveVideoFolder() {
        // 録画停止
        mediaRecorder?.stop()
        mediaRecorder?.release()
        openGlRelatedScope.cancel()

        // 端末の動画フォルダに移動
        withContext(Dispatchers.IO) {
            recordingFile?.also { recordingFile ->
                val contentValues = contentValuesOf(
                    MediaStore.MediaColumns.DISPLAY_NAME to recordingFile.name,
                    MediaStore.MediaColumns.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/Camera2ApiVideoSample",
                    MediaStore.MediaColumns.MIME_TYPE to "video/mp4"
                )
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)!!
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    recordingFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                recordingFile.delete()
            }
        }
    }

}