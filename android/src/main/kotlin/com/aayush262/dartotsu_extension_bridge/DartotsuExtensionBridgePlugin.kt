package com.aayush262.dartotsu_extension_bridge

import android.app.Application
import android.content.Context
import android.util.Log
import com.aayush262.dartotsu_extension_bridge.aniyomi.AniyomiBridge
import com.aayush262.dartotsu_extension_bridge.aniyomi.AniyomiExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory

class DartotsuExtensionBridgePlugin : FlutterPlugin {

    private lateinit var context: Context
    private lateinit var aniyomiChannel: MethodChannel
    private lateinit var cloudStreamChannel: MethodChannel
    private lateinit var videoStreamEventChannel: EventChannel
    private var cloudStreamBridge: CloudStreamBridge? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        Injekt.addSingletonFactory<Application> { context as Application }
        Injekt.addSingletonFactory { NetworkHelper(context) }
        Injekt.addSingletonFactory { NetworkHelper(context).client }
        Injekt.addSingletonFactory { Json { ignoreUnknownKeys = true; explicitNulls = false } }
        Injekt.addSingletonFactory { AniyomiExtensionManager(context) }

        aniyomiChannel = MethodChannel(binding.binaryMessenger, "aniyomiExtensionBridge")
        aniyomiChannel.setMethodCallHandler(AniyomiBridge(context))

        val bridge = CloudStreamBridge(context)
        cloudStreamBridge = bridge

        cloudStreamChannel = MethodChannel(binding.binaryMessenger, "cloudstreamExtensionBridge")
        cloudStreamChannel.setMethodCallHandler(bridge)

        videoStreamEventChannel = EventChannel(
            binding.binaryMessenger,
            "cloudstreamExtensionBridge/videoStream"
        )
        videoStreamEventChannel.setStreamHandler(bridge.VideoStreamHandler())
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        aniyomiChannel.setMethodCallHandler(null)
        cloudStreamChannel.setMethodCallHandler(null)
        videoStreamEventChannel.setStreamHandler(null)
        cloudStreamBridge?.destroy()
        cloudStreamBridge = null
    }
}