package com.aayush262.dartotsu_extension_bridge

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class CloudStreamPlugin(
    val internalName: String,
    val apiName: String,
    val providerInstance: Any,
    val providerClass: Class<*>
)

class CloudStreamExtensionManager(private val context: Context) {

    private val TAG = "CloudStreamManager"
    private val loadedPlugins = mutableMapOf<String, CloudStreamPlugin>()

    suspend fun loadPlugin(apkPath: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val sourceFile = File(apkPath)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Plugin file does not exist: $apkPath")
                return@withContext false
            }

            val safeDir = File(context.filesDir, "cs_plugins")
            safeDir.mkdirs()

            val safeName = "cs_${sourceFile.nameWithoutExtension}_${sourceFile.lastModified()}.cs3"
            val safeFile = File(safeDir, safeName)

            if (!safeFile.exists()) {
                safeDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("cs_${sourceFile.nameWithoutExtension}_")) {
                        f.delete()
                    }
                }
                sourceFile.copyTo(safeFile, overwrite = true)
                safeFile.setReadOnly()
                Log.i(TAG, "Copied plugin to safe location: ${safeFile.absolutePath}")
            } else {
                Log.i(TAG, "Using cached safe copy: ${safeFile.absolutePath}")
            }

            val optimizedDir = File(
                context.cacheDir,
                "cs_dex_${sourceFile.nameWithoutExtension}_${sourceFile.lastModified()}"
            )
            optimizedDir.mkdirs()

            val loader = DexClassLoader(
                safeFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                context.classLoader
            )

            val pluginClass = findPluginClass(loader, file) ?: run {
                Log.e(TAG, "Could not find any plugin class in: $apkPath")
                return@withContext false
            }

            Log.i(TAG, "Found plugin class: ${pluginClass.name}")

            val instance = try {
                pluginClass.getDeclaredConstructor().newInstance()
            } catch (e: Throwable) {
                Log.e(TAG, "Could not instantiate ${pluginClass.name}: ${e.message}", e)
                return@withContext false
            }

            try {
                pluginClass.getMethod("load", Context::class.java).invoke(instance, context)
            } catch (_: NoSuchMethodException) {
                try {
                    pluginClass.getMethod("load").invoke(instance)
                } catch (_: NoSuchMethodException) {
                    Log.d(TAG, "No load() method found in ${pluginClass.name}")
                }
            }

            val internalName = tryGetName(pluginClass, instance) ?: file.nameWithoutExtension

            loadedPlugins[internalName] = CloudStreamPlugin(
                internalName = internalName,
                apiName = internalName,
                providerInstance = instance,
                providerClass = pluginClass
            )

            Log.i(TAG, "Successfully loaded CloudStream plugin: $internalName")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load plugin $apkPath: ${e.message}", e)
            false
        }
    }

    private fun findPluginClass(loader: DexClassLoader, file: File): Class<*>? {
        val knownNames = listOf(
            "com.lagradost.cloudstream3.plugins.Plugin",
            "Plugin",
            "MainPlugin",
            "CloudStreamPlugin",
        )
        for (name in knownNames) {
            try {
                val cls = loader.loadClass(name)
                Log.d(TAG, "Found class by known name: $name")
                return cls
            } catch (_: ClassNotFoundException) {}
        }

        try {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .also { it.isAccessible = true }
            val pathList = pathListField.get(loader)

            val dexElementsField = pathList.javaClass
                .getDeclaredField("dexElements")
                .also { it.isAccessible = true }
            val dexElements = dexElementsField.get(pathList) as Array<*>

            for (element in dexElements) {
                val dexFileField = element!!.javaClass
                    .getDeclaredField("dexFile")
                    .also { it.isAccessible = true }
                val dexFile = dexFileField.get(element) ?: continue

                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as? java.util.Enumeration<String> ?: continue

                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    try {
                        val cls = loader.loadClass(className)
                        val superNames = generateSequence(cls.superclass) { it.superclass }
                            .map { it.name }
                            .toList()
                        val interfaceNames = cls.interfaces.map { it.name }

                        val isPlugin = superNames.any { it.contains("Plugin", ignoreCase = true) } ||
                            interfaceNames.any { it.contains("Plugin", ignoreCase = true) } ||
                            cls.name.endsWith("Plugin") ||
                            cls.name.endsWith("Provider")

                        if (isPlugin && !cls.isInterface && !cls.isAbstract()) {
                            Log.d(TAG, "Found candidate plugin class via scan: $className")
                            return cls
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Dex scanning failed: ${e.message}", e)
        }

        return null
    }

    private fun Class<*>.isAbstract(): Boolean =
        java.lang.reflect.Modifier.isAbstract(this.modifiers)

    private fun tryGetName(cls: Class<*>, instance: Any): String? {
        try {
            return cls.getMethod("getName").invoke(instance) as? String
        } catch (_: Exception) {}

        try {
            return cls.getField("name").get(instance) as? String
        } catch (_: Exception) {}

        try {
            return cls.getMethod("getApiName").invoke(instance) as? String
        } catch (_: Exception) {}

        return null
    }

    fun unloadPlugin(internalName: String): Boolean {
        val removed = loadedPlugins.remove(internalName)
        return removed != null
    }

    fun getRegisteredProviders(): List<Map<String, Any?>> {
        return loadedPlugins.values.map { plugin ->
            val map = mutableMapOf<String, Any?>()
            map["internalName"] = plugin.internalName
            map["name"] = plugin.apiName

            try {
                val mainPage = plugin.providerClass
                    .getMethod("getMainPage")
                    .invoke(plugin.providerInstance)
                map["mainPage"] = mainPage?.toString()
            } catch (_: Exception) {}

            try {
                val lang = plugin.providerClass
                    .getField("lang")
                    .get(plugin.providerInstance) as? String
                map["language"] = lang
            } catch (_: Exception) {}

            try {
                val iconUrl = plugin.providerClass
                    .getField("iconUrl")
                    .get(plugin.providerInstance) as? String
                map["iconUrl"] = iconUrl
            } catch (_: Exception) {}

            map
        }
    }

    fun getPlugin(apiName: String): CloudStreamPlugin? =
        loadedPlugins[apiName] ?: loadedPlugins.values.firstOrNull {
            it.apiName.equals(apiName, ignoreCase = true)
        }

    suspend fun search(
        apiName: String,
        query: String,
        page: Int,
        parameters: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val plugin = getPlugin(apiName)
            ?: return@withContext mapOf("list" to emptyList<Any>(), "hasNextPage" to false)

        return@withContext try {
            val method = plugin.providerClass.getMethod("search", String::class.java, Int::class.java)
            val result = method.invoke(plugin.providerInstance, query, page)
            parseSearchResult(result)
        } catch (e: Throwable) {
            Log.e(TAG, "search failed for $apiName: ${e.message}", e)
            mapOf("list" to emptyList<Any>(), "hasNextPage" to false)
        }
    }

    suspend fun getDetail(
        apiName: String,
        url: String,
        parameters: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val plugin = getPlugin(apiName)
            ?: return@withContext emptyMap()

        return@withContext try {
            val method = plugin.providerClass.getMethod("load", String::class.java)
            val result = method.invoke(plugin.providerInstance, url)
            parseDetailResult(result)
        } catch (e: Throwable) {
            Log.e(TAG, "getDetail failed for $apiName: ${e.message}", e)
            emptyMap()
        }
    }

    suspend fun getVideoList(
        apiName: String,
        url: String,
        parameters: Map<String, Any?>?
    ): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val plugin = getPlugin(apiName)
            ?: return@withContext emptyList()

        return@withContext try {
            val method = plugin.providerClass.getMethod("loadLinks", String::class.java)
            val result = method.invoke(plugin.providerInstance, url)
            parseVideoListResult(result)
        } catch (e: Throwable) {
            Log.e(TAG, "getVideoList failed for $apiName: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getVideoListStream(
        apiName: String,
        url: String,
        onVideo: (Map<String, Any?>) -> Unit,
        parameters: Map<String, Any?>?
    ) = withContext(Dispatchers.IO) {
        val plugin = getPlugin(apiName)
            ?: return@withContext

        try {
            val method = plugin.providerClass.methods.firstOrNull {
                it.name == "loadLinks" && it.parameterTypes.size >= 2
            }
            if (method != null) {
                val callbackClass = method.parameterTypes.lastOrNull()
                if (callbackClass != null && callbackClass.isInterface) {
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        plugin.providerClass.classLoader,
                        arrayOf(callbackClass)
                    ) { _, m, args ->
                        if (m?.name == "invoke" || m?.name == "onLink") {
                            val videoMap = parseVideoObject(args?.get(0))
                            onVideo(videoMap)
                        }
                        null
                    }
                    method.invoke(plugin.providerInstance, url, proxy)
                    return@withContext
                }
            }

            val videos = getVideoList(apiName, url, parameters)
            videos.forEach { onVideo(it) }
        } catch (e: Throwable) {
            Log.e(TAG, "getVideoListStream failed for $apiName: ${e.message}", e)
        }
    }

    fun getExtensionSettings(apiName: String): List<Map<String, Any?>> {
        val plugin = getPlugin(apiName) ?: return emptyList()
        return try {
            val method = plugin.providerClass.getMethod("getPreferences")
            @Suppress("UNCHECKED_CAST")
            val prefs = method.invoke(plugin.providerInstance) as? List<*> ?: return emptyList()
            prefs.mapNotNull { pref ->
                if (pref == null) return@mapNotNull null
                val prefClass = pref::class.java
                val prefMap = mutableMapOf<String, Any?>()
                try { prefMap["key"] = prefClass.getMethod("getKey").invoke(pref) } catch (_: Exception) {}
                try { prefMap["title"] = prefClass.getMethod("getTitle").invoke(pref) } catch (_: Exception) {}
                try { prefMap["summary"] = prefClass.getMethod("getSummary").invoke(pref) } catch (_: Exception) {}
                try { prefMap["defaultValue"] = prefClass.getMethod("getDefaultValue").invoke(pref) } catch (_: Exception) {}
                prefMap
            }
        } catch (e: Exception) {
            Log.e(TAG, "getExtensionSettings failed for $apiName: ${e.message}")
            emptyList()
        }
    }

    fun setExtensionSettings(apiName: String, key: String, value: Any?) {
        val plugin = getPlugin(apiName) ?: return
        try {
            plugin.providerClass
                .getMethod("setPreference", String::class.java, Any::class.java)
                .invoke(plugin.providerInstance, key, value)
        } catch (e: Exception) {
            Log.e(TAG, "setExtensionSettings failed for $apiName: ${e.message}")
        }
    }

    private fun parseSearchResult(result: Any?): Map<String, Any?> {
        if (result == null) return mapOf("list" to emptyList<Any>(), "hasNextPage" to false)
        return try {
            val cls = result::class.java
            val list = cls.getMethod("getResults").invoke(result)
            val hasNext = try {
                cls.getMethod("getHasNextPage").invoke(result) as? Boolean ?: false
            } catch (_: Exception) { false }

            mapOf(
                "list" to parseMediaList(list),
                "hasNextPage" to hasNext
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseSearchResult error: ${e.message}")
            mapOf("list" to emptyList<Any>(), "hasNextPage" to false)
        }
    }

    private fun parseMediaList(list: Any?): List<Map<String, Any?>> {
        if (list == null) return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            (list as? Iterable<*>)?.mapNotNull { item ->
                item ?: return@mapNotNull null
                val cls = item::class.java
                val map = mutableMapOf<String, Any?>()
                try { map["name"] = cls.getMethod("getName").invoke(item) } catch (_: Exception) {}
                try { map["url"] = cls.getMethod("getUrl").invoke(item) } catch (_: Exception) {}
                try { map["posterUrl"] = cls.getMethod("getPosterUrl").invoke(item) } catch (_: Exception) {}
                try { map["type"] = cls.getMethod("getType").invoke(item)?.toString() } catch (_: Exception) {}
                map
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "parseMediaList error: ${e.message}")
            emptyList()
        }
    }

    private fun parseDetailResult(result: Any?): Map<String, Any?> {
        if (result == null) return emptyMap()
        return try {
            val cls = result::class.java
            val map = mutableMapOf<String, Any?>()
            try { map["name"] = cls.getMethod("getName").invoke(result) } catch (_: Exception) {}
            try { map["url"] = cls.getMethod("getUrl").invoke(result) } catch (_: Exception) {}
            try { map["posterUrl"] = cls.getMethod("getPosterUrl").invoke(result) } catch (_: Exception) {}
            try { map["plot"] = cls.getMethod("getPlot").invoke(result) } catch (_: Exception) {}
            try { map["tags"] = cls.getMethod("getTags").invoke(result) } catch (_: Exception) {}
            try {
                val episodes = cls.getMethod("getEpisodes").invoke(result)
                map["episodes"] = parseEpisodeList(episodes)
            } catch (_: Exception) {}
            map
        } catch (e: Exception) {
            Log.e(TAG, "parseDetailResult error: ${e.message}")
            emptyMap()
        }
    }

    private fun parseEpisodeList(episodes: Any?): List<Map<String, Any?>> {
        if (episodes == null) return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            (episodes as? Iterable<*>)?.mapNotNull { ep ->
                ep ?: return@mapNotNull null
                val cls = ep::class.java
                val map = mutableMapOf<String, Any?>()
                try { map["name"] = cls.getMethod("getName").invoke(ep) } catch (_: Exception) {}
                try { map["url"] = cls.getMethod("getData").invoke(ep) } catch (_: Exception) {}
                try { map["episode"] = cls.getMethod("getEpNum").invoke(ep) } catch (_: Exception) {}
                try { map["season"] = cls.getMethod("getSeason").invoke(ep) } catch (_: Exception) {}
                try { map["posterUrl"] = cls.getMethod("getPosterUrl").invoke(ep) } catch (_: Exception) {}
                map
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "parseEpisodeList error: ${e.message}")
            emptyList()
        }
    }

    private fun parseVideoListResult(result: Any?): List<Map<String, Any?>> {
        if (result == null) return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            (result as? Iterable<*>)?.mapNotNull { parseVideoObject(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "parseVideoListResult error: ${e.message}")
            emptyList()
        }
    }

    private fun parseVideoObject(video: Any?): Map<String, Any?> {
        if (video == null) return emptyMap()
        return try {
            val cls = video::class.java
            val map = mutableMapOf<String, Any?>()
            try { map["url"] = cls.getMethod("getUrl").invoke(video) } catch (_: Exception) {}
            try { map["quality"] = cls.getMethod("getQuality").invoke(video)?.toString() } catch (_: Exception) {}
            try { map["name"] = cls.getMethod("getName").invoke(video) } catch (_: Exception) {}
            try { map["headers"] = cls.getMethod("getHeaders").invoke(video) } catch (_: Exception) {}
            map
        } catch (e: Exception) {
            Log.e(TAG, "parseVideoObject error: ${e.message}")
            emptyMap()
        }
    }
}