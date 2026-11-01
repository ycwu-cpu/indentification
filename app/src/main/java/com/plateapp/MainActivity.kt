package com.plateapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val exec = Executors.newSingleThreadExecutor()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camOut: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                if (params == null) return false
                return try {
                    val intent = params.createIntent()
                    @Suppress("DEPRECATION")
                    camOut = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
                    fileCb = callback
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: Exception) { fileCb = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "LPRBridge")
        web.loadUrl("file:///android_asset/app.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCb; fileCb = null
            var uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            if (uris == null && resultCode == RESULT_OK) {
                val out = camOut
                if (out != null) uris = arrayOf(out)
            }
            cb?.onReceiveValue(uris)
        }
    }

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun recognize(b64: String) {
            exec.execute {
                val result = runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("decode failed")
                    LprEngine.ensureInit(applicationContext)
                    LprEngine.recognize(bmp)
                }.getOrNull()
                runOnUiThread {
                    val js = if (result != null)
                        "window.__onLPRResult && window.__onLPRResult('${result.first}', ${result.second})"
                    else
                        "window.__onLPRResult && window.__onLPRResult('', 0)"
                    web.evaluateJavascript(js, null)
                }
            }
        }
    }

    companion object { private const val REQ_FILE = 1001 }
}

/** 用反射呼叫 HyperLPR3 SDK，避免套件名版本差異導致編譯失敗 */
object LprEngine {
    private val HYPER_CLS = listOf(
        "com.hyperai.hyperlpr3.HyperLPR3",
        "com.hyperai.lpr3.HyperLPR3",
        "com.hyperlpr3.HyperLPR3"
    )
    private var initialized = false
    private var hyperCls: Class<*>? = null
    private var instance: Any? = null
    private var initError: String? = null

    @Synchronized
    fun ensureInit(ctx: Context) {
        if (initialized || initError != null) return
        try {
            hyperCls = HYPER_CLS.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }
                ?: throw ClassNotFoundException("HyperLPR3 class not found")
            val cls = hyperCls!!
            // 參數物件
            val paramCls = runCatching { Class.forName(cls.name.replace("HyperLPR3", "HyperLPRParameter")) }.getOrNull()
            val param = paramCls?.getDeclaredConstructor()?.newInstance()
            param?.let { p ->
                for (m in p.javaClass.methods) {
                    when (m.name) {
                        "setDetLevel" -> m.invoke(p, 1)   // HIGH
                        "setMaxNum" -> m.invoke(p, 1)
                        "setRecConfidenceThreshold" -> m.invoke(p, 0.75f)
                    }
                }
            }
            instance = cls.getMethod("getInstance").invoke(null)
            val initMethod = cls.methods.first { m -> m.name == "init" && m.parameterTypes.size == 2 }
            initMethod.invoke(instance, ctx, param)
            initialized = true
        } catch (e: Exception) {
            initError = e.message ?: e.javaClass.simpleName
        }
    }

    fun recognize(bmp: Bitmap): Pair<String, Float>? {
        if (!initialized) return null
        val cls = hyperCls ?: return null
        val rot = runCatching { cls.getField("CAMERA_ROTATION_0").getInt(null) }.getOrDefault(0)
        val stream = runCatching { cls.getField("STREAM_BGRA").getInt(null) }.getOrDefault(0)
        val m = cls.methods.firstOrNull {
            it.name == "plateRecognition" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Bitmap::class.java
        } ?: return null
        val plates = m.invoke(instance, bmp, rot, stream) as? Array<*> ?: return null
        for (p in plates) {
            if (p == null) continue
            val code = p.javaClass.getMethod("getCode").invoke(p) as? String ?: continue
            if (code.isBlank()) continue
            val conf = runCatching {
                val cm = p.javaClass.methods.firstOrNull { it.name in listOf("getConfidence", "getConf") }
                (cm?.invoke(p) as? Number)?.toFloat()
            }.getOrNull() ?: 0f
            return code.uppercase() to conf
        }
        return null
    }
}
