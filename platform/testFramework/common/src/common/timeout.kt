// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework.common

import com.intellij.diagnostic.dumpCoroutines
import com.intellij.util.io.blockingDispatcher
import kotlinx.coroutines.*
import org.jetbrains.annotations.TestOnly
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@TestOnly
val DEFAULT_TEST_TIMEOUT: Duration = 10.seconds

@TestOnly
fun timeoutRunBlocking(timeout: Duration = DEFAULT_TEST_TIMEOUT, action: suspend CoroutineScope.() -> Unit) {
  var error: Throwable? = null
  @Suppress("RAW_RUN_BLOCKING")
  runBlocking {
    val job = async(block = action)
    @OptIn(DelicateCoroutinesApi::class)
    withContext(blockingDispatcher) {
      try {
        withTimeout(timeout) {
          job.await()
        }
      }
      catch (e: TimeoutCancellationException) {
        println(dumpCoroutines())
        job.cancel(e)
        error = e
      }
    }
  }
  error?.let { throw AssertionError(it) }
}
