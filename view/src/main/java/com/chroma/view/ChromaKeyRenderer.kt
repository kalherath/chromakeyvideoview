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

package com.chroma.view

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class ChromaKeyRenderer : GLTextureView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    interface OnSurfacePrepareListener {
        fun surfacePrepared(surface: Surface)
    }

    private val triangleVerticesData = floatArrayOf(-1.0f, -1.0f, 0f, 0f, 0f, 1.0f, -1.0f, 0f, 1f, 0f, -1.0f, 1.0f, 0f, 0f, 1f, 1.0f, 1.0f, 0f, 1f, 1f)

    private val triangleVertices: FloatBuffer

    private val vertexShader = "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n"

    private val chromaKeyShader = ("#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTextureCoord;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "varying mediump float text_alpha_out;\n"
            + "uniform float fade;\n"
            + "void main() {\n"
            + "  vec4 color = texture2D(sTexture, vTextureCoord);\n"
            + "  float silhouetteMode = %f;\n"
            + "  float b_red = %f;\n"
            + "  float b_green = %f;\n"
            + "  float b_blue = %f;\n"
            + "  float s_red = %f;\n"
            + "  float s_green = %f;\n"
            + "  float s_blue = %f;\n"
            + "  float tolerance = %f;\n"
            + "  if (abs(color.r - b_red) <= tolerance && abs(color.g - b_green) <= tolerance && abs(color.b - b_blue) <= tolerance) {\n"
            + "      gl_FragColor = vec4(color.r, color.g, color.b, 0.0);\n"
            + "  } else if(silhouetteMode != 0.0) {\n"
            + "      gl_FragColor = vec4(s_red, s_green, s_blue, fade);\n"
            + "  } else {\n"
            + "      gl_FragColor = vec4(color.r, color.g, color.b, fade);\n"
            + "  }\n"
            + "}\n")

    internal var onSurfacePrepareListener: OnSurfacePrepareListener? = null

    private val mVPMatrix = FloatArray(16)
    private val sTMatrix = FloatArray(16)

    private var program: Int = 0
    private var textureID: Int = 0
    private var uMVPMatrixHandle: Int = 0
    private var uSTMatrixHandle: Int = 0
    private var aPositionHandle: Int = 0
    private var aTextureHandle: Int = 0

    private var surface: SurfaceTexture? = null
    private var updateSurface = false

    private var backgroundRedParam = 0.0f
    private var backgroundGreenParam = 0.0f
    private var backgroundBlueParam = 0.0f
    private var silhouetteRedParam = 0.0f
    private var silhouetteGreenParam = 0.0f
    private var silhouetteBlueParam = 0.0f

    private var fade = 1.0f
    private var fadeInTimer : Timer? = null
    private var fadeOutTimer : Timer? = null

    internal var silhouetteMode: Boolean = DEFAULT_SILHOUETTE_MODE
    internal var fadeInDuration = DEFAULT_FADE_IN_DURATION
    internal var fadeInDelay = DEFAULT_FADE_IN_DELAY
    internal var fadeOutDuration = DEFAULT_FADE_OUT_DURATION
    internal var fadeOutLead = DEFAULT_FADE_OUT_LEAD

    internal var tolerance = DEFAULT_TOLERANCE
        internal set(layoutParameter) {
            var formattedValue = abs(layoutParameter)
            if (formattedValue > 1.0f) {
                formattedValue = 1.0f
            }
            formattedValue = 1.0f - formattedValue
            field = formattedValue
        }

    private val fadeAlphaIncrement = 1000f / (fadeInDuration * FADE_FRAME_RATE)

    init {
        triangleVertices = ByteBuffer.allocateDirect(
                triangleVerticesData.size * FLOAT_SIZE_BYTES
        )
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)
        Matrix.setIdentityM(sTMatrix, 0)
    }

    override fun onDrawFrame(glUnused: GL10) {
        synchronized(this) {
            if (updateSurface) {
                surface!!.updateTexImage()
                surface!!.getTransformMatrix(sTMatrix)
                updateSurface = false
            }
        }
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)

        GLES20.glUseProgram(program)
        checkForGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        checkForGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        checkForGlError("glEnableVertexAttribArray aPositionHandle")

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(aTextureHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        checkForGlError("glVertexAttribPointer aTextureHandle")
        GLES20.glEnableVertexAttribArray(aTextureHandle)
        checkForGlError("glEnableVertexAttribArray aTextureHandle")

        Matrix.setIdentityM(mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, sTMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkForGlError("glDrawArrays")

        val alphaLocation = GLES20.glGetUniformLocation(program, "fade")
        GLES20.glUniform1f(alphaLocation, fade)

        GLES20.glFinish()
    }

    override fun onSurfaceDestroyed(gl: GL10) {}

    override fun onSurfaceChanged(glUnused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onSurfaceCreated(glUnused: GL10, config: EGLConfig) {
        program = createProgram(vertexShader, this.getShader())
        if (program == 0) {
            return
        }
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        checkForGlError("glGetAttribLocation aPosition")
        if (aPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        checkForGlError("glGetAttribLocation aTextureCoord")
        if (aTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }

        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        checkForGlError("glGetUniformLocation uMVPMatrix")
        if (uMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }

        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        checkForGlError("glGetUniformLocation uSTMatrix")
        if (uSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }

        prepareSurface()
    }

    private fun prepareSurface() {
        Log.e("RAGTAG", "SURFACEPREPARE")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)

        textureID = textures[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID)
        checkForGlError("glBindTexture textureID")

        GLES20.glTexParameterf(
            GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(
            GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat())

        surface = SurfaceTexture(textureID)
        surface!!.setOnFrameAvailableListener(this)

        val surface = Surface(this.surface)
        onSurfacePrepareListener!!.surfacePrepared(surface)

        synchronized(this) {
            updateSurface = false
        }
    }

    @Synchronized
    override fun onFrameAvailable(surface: SurfaceTexture) {
        updateSurface = true
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(LOG_TAG, "Could not compile shader $shaderType:")
                Log.e(LOG_TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }

        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            checkForGlError("glAttachShader")
            GLES20.glAttachShader(program, pixelShader)
            checkForGlError("glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(LOG_TAG, "Could not link program: ")
                Log.e(LOG_TAG, GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    internal fun setBackgroundColor(color: Int) {
        backgroundRedParam = Color.red(color).toFloat() / COLOR_MAX_VALUE
        backgroundGreenParam = Color.green(color).toFloat() / COLOR_MAX_VALUE
        backgroundBlueParam = Color.blue(color).toFloat() / COLOR_MAX_VALUE
    }

    internal fun setSilhouetteColor(color: Int) {
        silhouetteRedParam = Color.red(color).toFloat() / COLOR_MAX_VALUE
        silhouetteGreenParam = Color.green(color).toFloat() / COLOR_MAX_VALUE
        silhouetteBlueParam = Color.blue(color).toFloat() / COLOR_MAX_VALUE
    }

    internal fun fadeIn() {
        fade = 0.0f
        fadeInTimer?.cancel()
        fadeInTimer = Timer()
        fadeInTimer?.schedule(object : TimerTask() {
            override fun run() {
                fade += fadeAlphaIncrement
                if (fade > 1.0f) {
                    fade = 1.0f
                    fadeInTimer?.cancel()
                    fadeInTimer = null
                }
            }
        }, fadeInDelay.toLong(), (fadeInDuration / FADE_FRAME_RATE).toLong())
    }

    internal fun fadeOut() {
        fadeOutTimer?.cancel()
        fadeOutTimer = Timer()
        fadeOutTimer?.schedule(object : TimerTask() {
            override fun run() {
                fade -= fadeAlphaIncrement
                if (fade < 0.0f){
                    fade = 0.0f
                    fadeOutTimer?.cancel()
                    fadeOutTimer = null
                }
            }
        }, 0, (fadeOutDuration / FADE_FRAME_RATE).toLong())
    }

    private fun getShader(): String {
        return String.format(Locale.ENGLISH, chromaKeyShader,
                if (silhouetteMode) 1.0f else 0.0f, backgroundRedParam, backgroundGreenParam, backgroundBlueParam, silhouetteRedParam, silhouetteGreenParam, silhouetteBlueParam, tolerance)
    }

    private fun checkForGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(LOG_TAG, "$op: glError $error")
            throw RuntimeException("$op: glError $error")
        }
    }

    companion object {
        private const val LOG_TAG = "ChromaKeyRenderer"
        private const  val COLOR_MAX_VALUE = 255
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65

        internal const val DEFAULT_BACKGROUND_COLOR = Color.GREEN
        internal const val DEFAULT_SILHOUETTE_COLOR = Color.BLACK
        internal const val DEFAULT_TOLERANCE = 0.7f
        internal const val DEFAULT_SILHOUETTE_MODE = false
        internal const val DEFAULT_FADE_IN_DELAY = 500
        internal const val DEFAULT_FADE_IN_DURATION = 500
        internal const val DEFAULT_FADE_OUT_LEAD = 500
        internal const val DEFAULT_FADE_OUT_DURATION = 500
        internal const val FADE_FRAME_RATE = 60
    }

}