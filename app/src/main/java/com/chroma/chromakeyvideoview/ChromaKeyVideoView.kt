/*
 * Copyright 2019 Kal Herath
 *
 * Copyright 2017 Pavel Semak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.chroma.chromakeyvideoview

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.View

import java.io.FileDescriptor
import java.io.IOException
import java.util.*
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class ChromaKeyVideoView(context: Context, attrs: AttributeSet) : GLTextureView(context, attrs) {
    private var videoAspectRatio = DEFAULT_ASPECT_RATIO
    private var videoScale = 1f
    private var centerX = 0.5f
        private set(layoutParameter) {
            var formattedValue = abs(layoutParameter)
            if (formattedValue > 1.0f) {
                formattedValue = 1.0f
            }
            field = formattedValue
        }
    private var centerY = 0.5f
        private set(layoutParameter) {
            var formattedValue = abs(layoutParameter)
            if (formattedValue > 1.0f) {
                formattedValue = 1.0f
            }
            formattedValue = 1.0f - formattedValue
            field = formattedValue
        }

    private var renderer: ChromaKeyRenderer? = null
    var mediaPlayer: MediaPlayer? = null
        private set

    private var onVideoStartedListener: OnVideoStartedListener? = null
    private var onVideoEndedListener: OnVideoEndedListener? = null

    private var isSurfaceCreated: Boolean = false
    private var isDataSourceSet: Boolean = false

    private var fadeOutLead = 0
    private var fadeOutStartTime = 0
    private var fadeOutStartTimer : PausableTimer? = null
    private var isLooping = false

    private var state = PlayerState.NOT_PREPARED

    val isPlaying: Boolean
        get() = state == PlayerState.STARTED

    val isPaused: Boolean
        get() = state == PlayerState.PAUSED

    val isStopped: Boolean
        get() = state == PlayerState.STOPPED

    val isReleased: Boolean
        get() = state == PlayerState.RELEASE

    val currentPosition: Int
        get() = mediaPlayer!!.currentPosition

    init {
        if (!isInEditMode) {
            init(attrs)
        }
    }

    private fun init(attrs: AttributeSet) {
        setEGLContextClientVersion(GL_CONTEXT_VERSION)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        initMediaPlayer()
        renderer = ChromaKeyRenderer()
        obtainRendererOptions(attrs)
        this.addOnSurfacePrepareListener()
        setRenderer(renderer)
        bringToFront()
        preserveEGLContextOnPause = true
        isOpaque = false
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer()
        setScreenOnWhilePlaying(true)
        setLooping(isLooping)

        mediaPlayer!!.setOnCompletionListener {
            state = PlayerState.PAUSED
            onVideoEndedListener?.onVideoEnded()
        }
    }

    private fun obtainRendererOptions(attrs: AttributeSet?) {
        if (attrs != null) {
            val arr = context.obtainStyledAttributes(attrs, R.styleable.ChromaKeyVideoView)
            val silhouetteMode = arr.getBoolean(R.styleable.ChromaKeyVideoView_silhouetteMode, ChromaKeyRenderer.DEFAULT_SILHOUETTE_MODE)
            isLooping = arr.getBoolean(R.styleable.ChromaKeyVideoView_looping, false)
            setLooping(isLooping)
            renderer?.silhouetteMode = silhouetteMode
            renderer?.setBackgroundColor(arr.getColor(R.styleable.ChromaKeyVideoView_backgroundColor, ChromaKeyRenderer.DEFAULT_BACKGROUND_COLOR))
            renderer?.setSilhouetteColor(arr.getColor(R.styleable.ChromaKeyVideoView_silhouetteColor, ChromaKeyRenderer.DEFAULT_SILHOUETTE_COLOR))
            renderer?.tolerance = arr.getFloat(R.styleable.ChromaKeyVideoView_tolerance, ChromaKeyRenderer.DEFAULT_TOLERANCE)
            renderer?.fadeInDelay = arr.getInteger(R.styleable.ChromaKeyVideoView_fadeInDelay, ChromaKeyRenderer.DEFAULT_FADE_IN_DELAY)
            renderer?.fadeInDuration = arr.getInteger(R.styleable.ChromaKeyVideoView_fadeInDuration, ChromaKeyRenderer.DEFAULT_FADE_IN_DURATION)
            renderer?.fadeOutLead = arr.getInteger(R.styleable.ChromaKeyVideoView_fadeOutLead, ChromaKeyRenderer.DEFAULT_FADE_OUT_LEAD)
            renderer?.fadeOutDuration = arr.getInteger(R.styleable.ChromaKeyVideoView_fadeOutDuration, ChromaKeyRenderer.DEFAULT_FADE_OUT_DURATION)

            videoScale = arr.getFloat(R.styleable.ChromaKeyVideoView_videoScale, DEFAULT_VIDEO_SCALE)
            centerX = arr.getFloat(R.styleable.ChromaKeyVideoView_centerVideoX, DEFAULT_CENTER_VIDEO_X)
            centerY = arr.getFloat(R.styleable.ChromaKeyVideoView_centerVideoY, DEFAULT_CENTER_VIDEO_Y)
            arr.recycle()
        }
    }

    private fun addOnSurfacePrepareListener() {
        renderer?.let {
            it.onSurfacePrepareListener = object : ChromaKeyRenderer.OnSurfacePrepareListener {
                override fun surfacePrepared(surface: Surface) {
                    isSurfaceCreated = true
                    mediaPlayer?.setSurface(surface)
                    surface.release()
                    if (isDataSourceSet) {
                        prepareAndStartMediaPlayer()
                    }
                }
            }
        }
    }

    private fun prepareAndStartMediaPlayer() {
        prepareAsync(MediaPlayer.OnPreparedListener {
            start()
        })
    }

    private fun calculateVideoAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            videoAspectRatio = videoWidth.toFloat() / videoHeight
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val containerHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        val videoPlayerHeight = (containerHeight * videoScale).toInt()
        val containerWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val videoPlayerWidth = (videoPlayerHeight * videoAspectRatio).toInt()
        translationX = (containerWidth - videoPlayerWidth) * centerX
        translationY = (containerHeight - videoPlayerHeight) * centerY

        super.onMeasure(View.MeasureSpec.makeMeasureSpec(videoPlayerWidth, widthMode),
                View.MeasureSpec.makeMeasureSpec(videoPlayerHeight, heightMode))
    }

    private fun onDataSourceSet(retriever: MediaMetadataRetriever) {
        val sourceVideoWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        val sourceVideoHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        calculateVideoAspectRatio(sourceVideoWidth, sourceVideoHeight)
        fadeOutStartTime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toInt() - fadeOutLead
        isDataSourceSet = true
        if (isSurfaceCreated) {
            prepareAndStartMediaPlayer()
        }
    }

    fun setVideoFromAssets(assetsFileName: String) {
        reset()
        try {
            val assetFileDescriptor = context.assets.openFd(assetsFileName)
            mediaPlayer?.setDataSource(assetFileDescriptor.fileDescriptor, assetFileDescriptor.startOffset, assetFileDescriptor.length)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(assetFileDescriptor.fileDescriptor, assetFileDescriptor.startOffset, assetFileDescriptor.length)
            onDataSourceSet(retriever)
        } catch (e: IOException) {
            Log.e(LOG_TAG, e.message, e)
        }
    }

    fun setVideoByUrl(url: String) {
        reset()
        try {
            mediaPlayer?.setDataSource(url)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(url, HashMap())
            onDataSourceSet(retriever)
        } catch (e: IOException) {
            Log.e(LOG_TAG, e.message, e)
        }

    }

    fun setVideoFromFile(fileDescriptor: FileDescriptor) {
        reset()
        try {
            mediaPlayer?.setDataSource(fileDescriptor)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(fileDescriptor)
            onDataSourceSet(retriever)
        } catch (e: IOException) {
            Log.e(LOG_TAG, e.message, e)
        }
    }

    fun setVideoFromFile(fileDescriptor: FileDescriptor, startOffset: Int, endOffset: Int) {
        reset()
        try {
            mediaPlayer?.setDataSource(fileDescriptor, startOffset.toLong(), endOffset.toLong())
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(fileDescriptor, startOffset.toLong(), endOffset.toLong())
            onDataSourceSet(retriever)
        } catch (e: IOException) {
            Log.e(LOG_TAG, e.message, e)
        }
    }

    @TargetApi(23)
    fun setVideoFromMediaDataSource(mediaDataSource: MediaDataSource) {
        reset()
        mediaPlayer?.setDataSource(mediaDataSource)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(mediaDataSource)
        onDataSourceSet(retriever)
    }

    fun setVideoFromUri(context: Context, uri: Uri) {
        reset()
        try {
            mediaPlayer?.setDataSource(context, uri)

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            onDataSourceSet(retriever)
        } catch (e: IOException) {
            Log.e(LOG_TAG, e.message, e)
        }
    }

    override fun onPause() {
        super.onPause()
        pause()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    private fun prepareAsync(onPreparedListener: MediaPlayer.OnPreparedListener) {
        if (state == PlayerState.NOT_PREPARED || state == PlayerState.STOPPED) {
            mediaPlayer?.setOnPreparedListener { mp ->
                state = PlayerState.PREPARED
                onPreparedListener.onPrepared(mp)
            }
            mediaPlayer?.prepareAsync()
        }
    }

    private fun initFadeOutStartTimer(currentPosition : Long = 0) {
        fadeOutStartTimer?.cancel()
        fadeOutStartTimer = PausableTimer()
        fadeOutStartTimer?.schedule(object : TimerTask() {
            override fun run() {
                renderer?.startFadeOut()
            }
        }, fadeOutStartTime.toLong() - currentPosition)
    }

    fun start() {
        if (mediaPlayer != null) {
            when (state) {
                PlayerState.PREPARED -> {
                    mediaPlayer!!.start()
                    state = PlayerState.STARTED
                    onVideoStartedListener?.onVideoStarted()
                    renderer!!.startFadeIn()
                    initFadeOutStartTimer()
                }
                PlayerState.PAUSED -> {
                    mediaPlayer!!.start()
                    state = PlayerState.STARTED
                    fadeOutStartTimer?.resume()
                }
                PlayerState.STOPPED -> prepareAsync(MediaPlayer.OnPreparedListener {
                    mediaPlayer!!.start()
                    state = PlayerState.STARTED
                    onVideoStartedListener?.onVideoStarted()
                    renderer!!.startFadeIn()
                    initFadeOutStartTimer()
                })
                else -> {}
            }
        }
    }

    fun pause() {
        if (state == PlayerState.STARTED) {
            mediaPlayer?.pause()
            state = PlayerState.PAUSED
            fadeOutStartTimer?.pause()
        }
    }

    fun stop() {
        if (state == PlayerState.STARTED || state == PlayerState.PAUSED) {
            mediaPlayer?.stop()
            state = PlayerState.STOPPED
        }
    }

    fun reset() {
        if (state == PlayerState.STARTED || state == PlayerState.PAUSED ||
                        state == PlayerState.STOPPED) {
            mediaPlayer?.reset()
            state = PlayerState.NOT_PREPARED
        }
    }

    fun release() {
        mediaPlayer?.release()
        state = PlayerState.RELEASE
    }

    fun seekTo(msec: Int) {
        mediaPlayer?.seekTo(msec)
        initFadeOutStartTimer(msec.toLong())
    }

    fun setLooping(looping: Boolean) {
        mediaPlayer?.isLooping = looping
    }

    fun setScreenOnWhilePlaying(screenOn: Boolean) {
        mediaPlayer?.setScreenOnWhilePlaying(screenOn)
    }

    fun setOnErrorListener(onErrorListener: MediaPlayer.OnErrorListener) {
        mediaPlayer?.setOnErrorListener(onErrorListener)
    }

    fun setOnVideoStartedListener(onVideoStartedListener: OnVideoStartedListener) {
        this.onVideoStartedListener = onVideoStartedListener
    }

    fun setOnVideoEndedListener(onVideoEndedListener: OnVideoEndedListener) {
        this.onVideoEndedListener = onVideoEndedListener
    }

    fun setOnSeekCompleteListener(onSeekCompleteListener: MediaPlayer.OnSeekCompleteListener) {
        mediaPlayer?.setOnSeekCompleteListener(onSeekCompleteListener)
    }

    interface OnVideoStartedListener {
        fun onVideoStarted()
    }

    interface OnVideoEndedListener {
        fun onVideoEnded()
    }

    private enum class PlayerState {
        NOT_PREPARED, PREPARED, STARTED, PAUSED, STOPPED, RELEASE
    }

    companion object {
        private const val GL_CONTEXT_VERSION = 2
        private const val DEFAULT_ASPECT_RATIO = 16f / 9f
        private const val DEFAULT_VIDEO_SCALE = 1.0f
        private const val DEFAULT_CENTER_VIDEO_X = 0.5f
        private const val DEFAULT_CENTER_VIDEO_Y = 0.5f
        private const val LOG_TAG = "ChromaKeyVideoView"
    }
}