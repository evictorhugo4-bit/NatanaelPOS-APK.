package com.natanaelpos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Puente JavaScript -> Android para guardar archivos (PDF, CSV, respaldos) en la carpeta Descargas.
 * La página lo usa así:  AndroidBridge.saveBase64File(nombre, mime, datoEnBase64)
 * Solo acepta llamadas si la página actual es la de Natanael POS (isTrusted).
 */
class AndroidBridge(
    private val activity: Activity,
    private val isTrusted: () -> Boolean
) {
    @JavascriptInterface
    fun saveBase64File(name: String, mime: String, base64: String) {
        if (!isTrusted()) {
            toast("Operación no permitida desde esta página")
            return
        }
        // Android 10 (API 29) o superior. Se usan constantes de texto en vez de MediaStore.Downloads
        // para que compile con cualquier compileSdk y no dispare el lint "NewApi".
        if (Build.VERSION.SDK_INT < 29) {
            toast("Guardar archivos requiere Android 10 o superior")
            return
        }
        try {
            val safeName = name.replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "archivo" }
            val bytes = Base64.decode(base64, Base64.DEFAULT)

            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put("relative_path", Environment.DIRECTORY_DOWNLOADS)
            val collection = Uri.parse("content://media/external/downloads")
            val uri = activity.contentResolver.insert(collection, values)
            if (uri == null) {
                toast("No se pudo crear el archivo en Descargas")
                return
            }
            activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

            activity.runOnUiThread {
                Toast.makeText(activity, "Guardado en Descargas: $safeName", Toast.LENGTH_LONG).show()
                try {
                    val view = Intent(Intent.ACTION_VIEW)
                    view.setDataAndType(uri, mime)
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(view)
                } catch (e: Exception) {
                    // no hay app para abrir este tipo de archivo: queda guardado igual
                }
            }
        } catch (e: Exception) {
            toast("No se pudo guardar: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
    }
}

class MainActivity : Activity() {

    private val siteUrl = "https://evictorhugo4-bit.github.io/Natanael-POS/"
    private val virtualDomain = "appassets.androidplatform.net"
    private val mediaPermissionCode = 100

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File
    private lateinit var assetLoader: WebViewAssetLoader

    private val mainHandler = Handler(Looper.getMainLooper())

    // URL de la página que está cargada (se actualiza desde el WebViewClient) para validar el puente
    @Volatile private var currentUrl: String = ""

    private fun isTrustedPage(): Boolean {
        val url = currentUrl
        return url.startsWith(siteUrl) || url.startsWith("https://$virtualDomain/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cacheDir = File(filesDir, "cache").apply { mkdirs() }
        cacheFile = File(cacheDir, "index_cache.html")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            text = "Cargando Natanael POS..."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(40, 200, 40, 40)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
        }

        // Puente para guardar archivos en Descargas (la página lo llama como window.AndroidBridge)
        webView.addJavascriptInterface(AndroidBridge(this) { isTrustedPage() }, "AndroidBridge")

        root.addView(statusText)
        root.addView(webView)
        setContentView(root)

        requestMediaPermissionsIfNeeded()

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(virtualDomain)
            .addPathHandler("/cache/", WebViewAssetLoader.InternalStoragePathHandler(this, cacheDir))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: ""
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: ""
                showWebView()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    loadOfflineOrError()
                }
            }
        }

        // Le da permiso a la PÁGINA WEB para usar cámara y/o micrófono cuando el
        // JavaScript los pide (ej. escáner de códigos, notas de voz, etc.)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }
        }

        loadContent()
    }

    private fun requestMediaPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), mediaPermissionCode)
        }
    }

    private fun loadContent() {
        showLoading()
        Thread {
            val online = isReachable(siteUrl)
            mainHandler.post {
                if (online) {
                    val freshUrl = siteUrl + (if (siteUrl.contains("?")) "&" else "?") + "v=" + System.currentTimeMillis()
                    webView.loadUrl(freshUrl)
                    refreshCacheInBackground()
                } else {
                    loadOfflineOrError()
                }
            }
        }.start()
    }

    private fun isReachable(urlStr: String): Boolean {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.connect()
            val ok = conn.responseCode in 200..399
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshCacheInBackground() {
        Thread {
            try {
                val conn = URL(siteUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                // si falla el guardado de caché, no rompemos nada
            }
        }.start()
    }

    private fun loadOfflineOrError() {
        if (cacheFile.exists()) {
            mainHandler.post {
                statusText.text = "SIN CONEXIÓN — mostrando última versión guardada"
                webView.loadUrl("https://$virtualDomain/cache/index_cache.html")
            }
        } else {
            mainHandler.post {
                statusText.text = "No se pudo conectar a Natanael POS y todavía no hay\n" +
                        "ninguna copia guardada. Conectate a internet y volvé a abrir la app."
                webView.visibility = View.GONE
            }
        }
    }

    private fun showLoading() {
        statusText.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    private fun showWebView() {
        statusText.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }
}
