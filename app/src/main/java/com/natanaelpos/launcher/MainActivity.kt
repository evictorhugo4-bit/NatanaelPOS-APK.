package com.natanaelpos.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val siteUrl = "https://evictorhugo4-bit.github.io/Natanael-POS/"
    private val virtualDomain = "appassets.androidplatform.net"

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File
    private lateinit var assetLoader: WebViewAssetLoader

    private val mainHandler = Handler(Looper.getMainLooper())

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
        }

        root.addView(statusText)
        root.addView(webView)
        setContentView(root)

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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
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

        loadContent()
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
