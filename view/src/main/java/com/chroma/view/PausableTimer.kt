/*
 * Copyright 2019 Kal Herath
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

import java.util.Timer
import java.util.TimerTask

class PausableTimer {

    private val baseTimer = Timer()
    private lateinit var baseTimerTask : TimerTask
    private var startTimeNanos : Long = 0
    private var remainingTimeMillis : Long = 0

    fun schedule(task : TimerTask, delay : Long) {
        baseTimerTask = task
        remainingTimeMillis = delay
        resume()
    }

    fun pause() {
        remainingTimeMillis -= (System.nanoTime() - startTimeNanos)/1000
        baseTimer.cancel()
    }

    fun resume() {
        startTimeNanos = System.nanoTime()
        baseTimer.schedule(baseTimerTask, remainingTimeMillis)
    }

    fun cancel() {
        baseTimer.cancel()
    }
}