/*
 * Copyright 2019 Hippo Seven
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

package com.hippo.quickjs.android.test

import android.content.Context
import com.getkeepsafe.relinker.ReLinker
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.lingala.zip4j.core.ZipFile
import java.io.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class Tester(
  private val context: Context
) {

  private val printer = MessageHolder()

  private val assetsNameFile = File(context.filesDir, "testassets.name")
  private val assetsDir = File(context.filesDir, "testassets")
  private val tempFile = File(context.cacheDir, "testassets.zip")

  @Volatile
  private var testNumber = 0
  private val failedTests = ConcurrentLinkedQueue<String>()

  fun registerMessageQueuePrinter(messageQueuePrinter: MessageQueuePrinter) {
    printer.registerMessageQueuePrinter(messageQueuePrinter)
  }

  private fun ensureAssetFiles() {
    val exceptAssetsName = try {
      assetsNameFile.readText()
    } catch (e: IOException) {
      null
    }

    var actualAssetsName: String? = null
    for (asset in context.assets.list("") ?: emptyArray()) {
      if (asset.startsWith("testassets-") && asset.endsWith(".crc32")) {
        actualAssetsName = asset
      }
    }
    if (actualAssetsName == null) {
      error("Can't find test assets")
    }

    if (exceptAssetsName != actualAssetsName) {
      printer.print("Need exact assets")
      printer.print("except = $exceptAssetsName")
      printer.print("actual = $actualAssetsName")

      assetsDir.deleteRecursively()
      if (!assetsDir.mkdirs()) {
        error("Can't create test assets dir")
      }

      context.assets.open("testassets.zip").use { `in` ->
        tempFile.outputStream().use { out ->
          `in`.copyTo(out)
        }
      }

      val zipFile = ZipFile(tempFile)
      zipFile.extractAll(assetsDir.path)

      assetsNameFile.writeText(actualAssetsName)

      printer.print("All test assets are copied")
    } else {
      printer.print("All test assets are UP-TO-DATE")
    }
  }

  private fun ensureExecutable() {
    ReLinker.loadLibrary(context, "patch_test")
    ReLinker.loadLibrary(context, "qjs")
    ReLinker.loadLibrary(context, "qjsbn")
  }

  private fun runTest(name: String, executable: String, parameter: String) {
    printer.print("********************************")
    printer.print("** ${++testNumber}. $name")
    printer.print("********************************")

    val code = run(executable, parameter)

    if (code == 0) {
      printer.print("PASSED")
    } else {
      printer.print("FAILED")
      failedTests.add(name)
    }
  }

  private fun runTest(executable: String, parameter: String) {
    val name = "$executable $parameter"
    runTest(name, executable, parameter)
  }

  private fun testPatch() {
    runTest("patch_test", "")
  }

  private fun test() {
    runTest("qjs", "tests/test_closure.js")
    runTest("qjs", "tests/test_op.js")
    runTest("qjs", "tests/test_builtin.js")
    runTest("qjs", "tests/test_loop.js")
    // tmpfile returns null
    runTest("qjs", "-m tests/test_std.js")
    runTest("qjsbn", "tests/test_closure.js")
    runTest("qjsbn", "tests/test_op.js")
    runTest("qjsbn", "tests/test_builtin.js")
    runTest("qjsbn", "tests/test_loop.js")
    // tmpfile returns null
    runTest("qjsbn", "-m tests/test_std.js")
    // Failed. Might be related to libbf
    runTest("qjsbn", "--qjscalc tests/test_bignum.js")
  }

  private fun stats() {
    runTest("qjs", "-qd")
  }

  private fun microbench() {
    runTest("qjs", "tests/microbench.js")
  }

  private fun runTest262() {
    runTest("run-test262", "-m -c test262o.conf")
    runTest("run-test262", "-u -c test262o.conf")
    runTest("run-test262", "-m -c test262.conf")
    runTest("run-test262", "-m -c test262.conf -a")
    runTest("run-test262", "-u -c test262.conf -a")
    runTest("run-test262", "-m -c test262.conf -E -a")
  }

  private fun runTest262bn() {
    runTest("run-test262-bn", "-m -c test262bn.conf")
    runTest("run-test262-bn", "-m -c test262bn.conf -a")
  }

  private fun benchV8() {
    runTest("qjs", "-d tests/bench-v8/combined.js")
  }

  private fun printThrowable(e: Throwable) {
    val baos = ByteArrayOutputStream()
    PrintWriter(baos).apply {
      e.printStackTrace(this)
      flush()
    }
    ByteArrayInputStream(baos.toByteArray()).reader().buffered().forEachLine { printer.print(it) }
  }

  fun start() {
    thread {
      try {
        ensureAssetFiles()
        ensureExecutable()

        testPatch()
        test()
        stats()
        microbench()
        runTest262()
        runTest262bn()
        benchV8()

        printer.print("********************************")
        printer.print("********************************")
        printer.print("********************************")
        printer.print("TEST COMPLETE")
      } catch (e: Throwable) {
        e.printStackTrace()
        printer.print("********************************")
        printer.print("********************************")
        printer.print("********************************")
        printer.print("TEST INTERRUPT")
        printThrowable(e)
        return@thread
      }
    }
  }

  private fun run(executable: String, parameter: String): Int = runBlocking {
    val nativeDir = context.applicationInfo.nativeLibraryDir
    val executableFile = File(nativeDir, "lib$executable.so")
    val command = "${executableFile.path} $parameter"

    val processChannel = Channel<Process>()

    val job1 = (GlobalScope + Dispatchers.IO).launch {
      val process = processChannel.receive()
      process.inputStream.reader().buffered().forEachLine { printer.print(it) }
    }

    val job2 = (GlobalScope + Dispatchers.IO).launch {
      val process = processChannel.receive()
      process.errorStream.reader().buffered().forEachLine { printer.print(it) }
    }

    val process = Runtime.getRuntime().exec(command, null, assetsDir)
    processChannel.send(process)
    processChannel.send(process)
    val code = process.waitFor()

    job1.join()
    job2.join()

    code
  }

  /**
   * MessageList cache messages and dispatch message to the last registered MultiPrinter.
   */
  private class MessageHolder : MessagePrinter {

    private val messages = MessageQueue()

    @Volatile
    private var weakMessageQueuePrinter: WeakReference<MessageQueuePrinter>? = null

    @Synchronized
    fun registerMessageQueuePrinter(messageQueuePrinter: MessageQueuePrinter) {
      messageQueuePrinter.print(messages.copy())
      weakMessageQueuePrinter = WeakReference(messageQueuePrinter)
    }

    @Synchronized
    override fun print(message: String) {
      messages.add(message)
      weakMessageQueuePrinter?.get()?.print(message)
    }
  }
}
