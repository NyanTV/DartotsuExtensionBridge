package com.aayush262.dartotsu_extension_bridge

import android.content.Context
import android.util.Log
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudStreamBridge(private val context: Context) : MethodChannel.MethodCallHandler {

    private val TAG = "CloudStreamBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = CloudStreamExtensionManager(context)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initialize" -> {
                result.success(null)
            }

            "getRegisteredProviders" -> {
                try {
                    result.success(manager.getRegisteredProviders())
                } catch (e: Throwable) {
                    result.error("CS_ERROR", e.message, null)
                }
            }

            "loadPlugin" -> {
                val path = call.argument<String>("path")
                if (path.isNullOrBlank()) {
                    result.error("INVALID_ARG", "path is required", null)
                    return
                }
                scope.launch {
                    try {
                        val ok = manager.loadPlugin(path)
                        withContext(Dispatchers.Main) {
                            if (ok) result.success(true)
                            else result.error("LOAD_FAILED", "Failed to load plugin: $path", null)
                        }
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            result.error("CS_ERROR", e.message, Log.getStackTraceString(e))
                        }
                    }
                }
            }

            "deletePlugin" -> {
                val internalName = call.argument<String>("internalName")
                if (internalName.isNullOrBlank()) {
                    result.error("INVALID_ARG", "internalName is required", null)
                    return
                }
                val ok = manager.unloadPlugin(internalName)
                result.success(ok)
            }

            "search" -> {
                val apiName = call.argument<String>("apiName") ?: ""
                val query = call.argument<String>("query") ?: ""
                val page = call.argument<Int>("page") ?: 1
                val parameters = call.argument<Map<String, Any?>>("parameters")
                scope.launch {
                    try {
                        val res = manager.search(apiName, query, page, parameters)
                        withContext(Dispatchers.Main) { result.success(res) }
                    } catch (e: Throwable) {
                        sendError(result, "search", e)
                    }
                }
            }

            "getDetail" -> {
                val apiName = call.argument<String>("apiName") ?: ""
                val url = call.argument<String>("url") ?: ""
                val parameters = call.argument<Map<String, Any?>>("parameters")
                scope.launch {
                    try {
                        val res = manager.getDetail(apiName, url, parameters)
                        withContext(Dispatchers.Main) { result.success(res) }
                    } catch (e: Throwable) {
                        sendError(result, "getDetail", e)
                    }
                }
            }

            "getVideoList" -> {
                val apiName = call.argument<String>("apiName") ?: ""
                val url = call.argument<String>("url") ?: ""
                val parameters = call.argument<Map<String, Any?>>("parameters")
                scope.launch {
                    try {
                        val res = manager.getVideoList(apiName, url, parameters)
                        withContext(Dispatchers.Main) { result.success(res) }
                    } catch (e: Throwable) {
                        sendError(result, "getVideoList", e)
                    }
                }
            }

            "getExtensionSettings" -> {
                val apiName = call.argument<String>("pluginName") ?: ""
                try {
                    result.success(manager.getExtensionSettings(apiName))
                } catch (e: Throwable) {
                    result.error("CS_ERROR", e.message, null)
                }
            }

            "setExtensionSettings" -> {
                val apiName = call.argument<String>("pluginName") ?: ""
                val key = call.argument<String>("key") ?: ""
                val value = call.argument<Any>("value")
                try {
                    manager.setExtensionSettings(apiName, key, value)
                    result.success(null)
                } catch (e: Throwable) {
                    result.error("CS_ERROR", e.message, null)
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun sendError(result: MethodChannel.Result, method: String, e: Throwable) {
        val real = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        Log.e(TAG, "Error in $method: ${real.message}", e)
        scope.launch(Dispatchers.Main) {
            result.error("CS_ERROR", real.message, Log.getStackTraceString(e))
        }
    }

    fun destroy() {
        scope.cancel()
    }

    inner class VideoStreamHandler : EventChannel.StreamHandler {
        private var streamJob: Job? = null

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            val args = arguments as? Map<*, *> ?: run {
                events?.endOfStream()
                return
            }
            val apiName = args["apiName"] as? String ?: run {
                events?.error("INVALID_ARG", "apiName is required", null)
                events?.endOfStream()
                return
            }
            val url = args["url"] as? String ?: run {
                events?.error("INVALID_ARG", "url is required", null)
                events?.endOfStream()
                return
            }
            val parameters = (args["parameters"] as? Map<*, *>)
                ?.mapKeys { it.key.toString() }

            streamJob?.cancel()
            streamJob = scope.launch {
                try {
                    manager.getVideoListStream(apiName, url, { video ->
                        if (isActive) {
                            scope.launch(Dispatchers.Main) {
                                events?.success(video)
                            }
                        }
                    }, parameters)

                    delay(500)
                    withContext(Dispatchers.Main) { events?.endOfStream() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "VideoStream error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        events?.error("CS_ERROR", e.message, Log.getStackTraceString(e))
                        events?.endOfStream()
                    }
                }
            }
        }

        override fun onCancel(arguments: Any?) {
            streamJob?.cancel()
            streamJob = null
        }
    }
}