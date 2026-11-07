package ua.tvlight.browser

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebSettings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.app.DownloadManager
import android.os.Environment
import android.net.Uri
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.GridLayout
import android.content.res.Configuration
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var address: EditText
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: LinearLayout
    private lateinit var cursorOverlay: View
    private lateinit var page: FrameLayout
    private lateinit var homePanel: LinearLayout
    @Volatile private var currentSiteHost: String? = null
    private var cursorMode = false
    private var cursorX = 300f
    private var cursorY = 300f

    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "adsrvr.org", "criteo.com", "scorecardresearch.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com",
        "beton.ua", "beton.com.ua", "exoclick.com", "exosrv.com", "trafficjunky.net",
        "propellerads.com", "onclickads.net", "adsterra.com", "hilltopads.net",
        "bidgear.com", "mgid.com", "revcontent.com", "popcash.net",
        "juicyads.com", "clickadu.com", "ad-maven.com", "adcash.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adBlockEngine = AdBlockEngine(applicationContext)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.rgb(8, 22, 45))
        }
        fun navButton(label: String, action: () -> Unit) {
            bar.addView(styledButton(label, Color.rgb(15, 44, 79), Color.rgb(39, 111, 169), action),
                LinearLayout.LayoutParams(dp(44), dp(43)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        navButton("⌂") { showHome() }
        navButton("‹") { if (web.canGoBack()) web.goBack() else showHome() }
        navButton("›") { if (web.canGoForward()) web.goForward() }
        navButton("↻") { web.reload() }
        address = EditText(this).apply {
            setSingleLine(true)
            hint = "Пошук або адреса сайту"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(142, 175, 208))
            setPadding(dp(13), 0, dp(8), 0)
            background = rounded(Color.rgb(14, 38, 70), Color.rgb(34, 114, 181))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> navigate(text.toString()); true }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        navButton("➜") { navigate(address.text.toString()) }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(5))
            setBackgroundColor(Color.rgb(7, 19, 39))
        }
        fun tool(label: String, action: () -> Unit) {
            tools.addView(styledButton(label, Color.rgb(12, 36, 69), Color.rgb(31, 75, 122), action),
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        tool("★") { bookmark() }
        tool("Закладки") { showBookmarks() }
        tool("Історія") { showHistory() }
        tool("⚙") { showMenu() }
        tool("Курсор") {
            cursorMode = !cursorMode
            cursorOverlay.invalidate()
            web.requestFocus()
            Toast.makeText(this, if (cursorMode) "Курсор: стрілки — рух, OK — натискання" else "Звичайне керування", Toast.LENGTH_LONG).show()
        }
        root.addView(bar)
        root.addView(tools)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                currentSiteHost = Uri.parse(url ?: "").host?.lowercase(Locale.ROOT)
                if (isAdBlockActive()) view?.postDelayed({ if (isAdBlockActive()) applyCosmeticFilters() }, 400)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url ?: "")
                if (isAdBlockActive()) applyCosmeticFilters()
                if (!url.isNullOrBlank() && url.startsWith("https://")) addHistory(url)
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (isAdBlockActive() && adBlockEngine.shouldBlock(
                        request, currentSiteHost, prefs.getBoolean("adblock_strong", false))) {
                    return adBlockEngine.emptyResponse()
                }
                return null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullscreen != null) return
                fullscreen = view
                fullscreenCallback = callback
                root.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                addContentView(view, ViewGroup.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() { closeFullscreen() }
        }
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mime)
                request.addRequestHeader("User-Agent", userAgent ?: "")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, disposition, mime))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Завантаження розпочато", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Не вдалося завантажити файл", Toast.LENGTH_SHORT).show()
            }
        }
        page = FrameLayout(this)
        page.addView(web, FrameLayout.LayoutParams(-1, -1))
        cursorOverlay = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                strokeWidth = 3f * resources.displayMetrics.density
                style = Paint.Style.STROKE
            }
            override fun onDraw(canvas: Canvas) {
                if (!cursorMode) return
                val x = cursorX.coerceIn(0f, width.toFloat())
                val y = cursorY.coerceIn(0f, height.toFloat())
                canvas.drawCircle(x, y, 12f * resources.displayMetrics.density, paint)
                canvas.drawLine(x - 18f, y, x + 18f, y, paint)
                canvas.drawLine(x, y - 18f, x, y + 18f, paint)
            }
        }
        page.addView(cursorOverlay, FrameLayout.LayoutParams(-1, -1))
        cursorOverlay.isClickable = false
        cursorOverlay.isFocusable = false
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(5, 14, 31))
        }
        root.addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))
        web.requestFocus()
        setContentView(root)
        showHome()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styledButton(label: String, fill: Int, border: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = rounded(fill, border)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                alpha = if (focused) 1f else 0.94f
                scaleX = if (focused) 1.035f else 1f
                scaleY = if (focused) 1.035f else 1f
                elevation = if (focused) dp(8).toFloat() else dp(2).toFloat()
            }
        }

    private fun showHome() {
        root.getChildAt(0).visibility = View.GONE
        root.getChildAt(1).visibility = View.GONE
        page.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        homePanel.removeAllViews()
        homePanel.setPadding(dp(16), dp(8), dp(16), dp(12))
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            resources.configuration.smallestScreenWidthDp >= 600
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        homePanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "◈  TV LIGHT BROWSER"
            textSize = if (wide) 24f else 19f
            setTextColor(Color.rgb(236, 247, 255))
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(styledButton("⚙", Color.rgb(17, 46, 83), Color.rgb(46, 127, 191)) { showMenu() },
            LinearLayout.LayoutParams(dp(46), dp(42)))
        content.addView(header)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Головна", "Веб", "Закладки", "Історія").forEachIndexed { index, name ->
            val action: () -> Unit = when (index) {
                0 -> { { showHome() } }
                1 -> { { navigate(prefs.getString("home", "https://www.google.com") ?: "https://www.google.com") } }
                2 -> { { showBookmarks() } }
                else -> { { showHistory() } }
            }
            tabs.addView(styledButton(name, if (index == 0) Color.rgb(15, 79, 132) else Color.rgb(9, 29, 56),
                if (index == 0) Color.rgb(0, 185, 255) else Color.rgb(24, 66, 111), action),
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        content.addView(tabs)
        val search = EditText(this).apply {
            hint = "⌕  Пошук або введіть адресу сайту..."
            setSingleLine(true)
            textSize = if (wide) 17f else 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(146, 181, 218))
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(Color.rgb(14, 41, 76), Color.rgb(30, 177, 250))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> navigate(text.toString()); true }
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(dp(3), dp(15), dp(3), dp(9))
        })
        fun heading(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = if (wide) 19f else 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(229, 244, 255))
                setPadding(dp(5), dp(12), 0, dp(10))
            })
        }
        heading("Швидкий доступ")
        val tiles = listOf(
            Triple("▣\\nФільми", Color.rgb(78, 28, 137), { showEntries("bookmarks", "Сайти для фільмів") }),
            Triple("▤\\nСеріали", Color.rgb(8, 77, 142), { showEntries("bookmarks", "Сайти для серіалів") }),
            Triple("▣\\nТБ онлайн", Color.rgb(6, 100, 79), { showEntries("bookmarks", "ТБ-закладки") }),
            Triple("⇩\\nЗавантаження", Color.rgb(130, 88, 15), { openDownloads() }),
            Triple("⚙\\nНалаштування", Color.rgb(26, 65, 125), { showMenu() })
        )
        val columns = if (wide) 5 else 2
        val grid = GridLayout(this).apply {
            columnCount = columns
            useDefaultMargins = false
        }
        val width = resources.displayMetrics.widthPixels - dp(44)
        val tileWidth = (width / columns) - dp(7)
        tiles.forEach { (label, fill, action) ->
            grid.addView(styledButton(label.replace("\\n", "\n"), fill, Color.rgb(50, 166, 242), action).apply {
                textSize = if (wide) 17f else 16f
                elevation = dp(3).toFloat()
            }, GridLayout.LayoutParams().apply {
                width = tileWidth
                height = if (wide) dp(118) else dp(112)
                setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
        content.addView(grid)
        heading("★  Швидкі закладки")
        val shortcuts = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        entries("bookmarks").take(12).forEach { url ->
            val host = Uri.parse(url).host?.removePrefix("www.") ?: url
            row.addView(styledButton(host, Color.rgb(17, 43, 78), Color.rgb(40, 100, 155)) { navigate(url) },
                LinearLayout.LayoutParams(dp(160), dp(49)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        row.addView(styledButton("+ Додати", Color.rgb(18, 52, 91), Color.rgb(35, 155, 231)) { promptBookmark() },
            LinearLayout.LayoutParams(dp(145), dp(49)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        shortcuts.addView(row)
        content.addView(shortcuts)
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 16f * resources.displayMetrics.density
            setStroke((2 * resources.displayMetrics.density).toInt(), stroke)
        }

    private fun promptBookmark() {
        val input = EditText(this).apply { hint = "https://example.com" }
        AlertDialog.Builder(this).setTitle("Додати закладку")
            .setView(input)
            .setPositiveButton("Додати") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    val url = if (value.startsWith("https://")) value else "https://$value"
                    val items = entries("bookmarks")
                    if (!items.contains(url)) items.add(0, url)
                    saveEntries("bookmarks", items)
                    showHome()
                }
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun openDownloads() {
        try {
            startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "Менеджер завантажень недоступний", Toast.LENGTH_SHORT).show()
        }
    }

    private fun entries(key: String): MutableList<String> =
        (prefs.getString(key, "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()

    private fun saveEntries(key: String, values: List<String>) {
        prefs.edit().putString(key, values.take(100).joinToString("\n")).apply()
    }

    private fun addHistory(url: String) {
        val items = entries("history")
        items.remove(url)
        items.add(0, url)
        saveEntries("history", items)
    }

    private fun bookmark() {
        val url = web.url ?: return
        val items = entries("bookmarks")
        if (!items.contains(url)) items.add(0, url)
        saveEntries("bookmarks", items)
        Toast.makeText(this, "Додано в закладки", Toast.LENGTH_SHORT).show()
    }

    private fun showEntries(key: String, title: String) {
        val items = entries(key)
        if (items.isEmpty()) {
            Toast.makeText(this, "Список порожній", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> navigate(items[which]) }
            .setNegativeButton("Закрити", null).show()
    }

    private fun showBookmarks() = showEntries("bookmarks", "Закладки")
    private fun showHistory() = showEntries("history", "Історія")

    private fun showMenu() {
        val options = arrayOf(if (prefs.getBoolean("adblock", true)) "AdBlock: увімкнено (натисни, щоб вимкнути)" else "AdBlock: вимкнено (натисни, щоб увімкнути)", "Рівень блокування: " + if (prefs.getBoolean("adblock_strong", false)) "посилений" else "стандартний", "Винятки AdBlock для сайтів", "Зробити стартовою", "Очистити кеш", "Очистити історію", "Очистити cookies", "Очистити закладки")
        AlertDialog.Builder(this).setTitle("Налаштування")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        val next = !prefs.getBoolean("adblock", true)
                        prefs.edit().putBoolean("adblock", next).apply()
                        Toast.makeText(this, if (next) "AdBlock увімкнено" else "AdBlock вимкнено", Toast.LENGTH_SHORT).show()
                        web.reload()
                    }
                    1 -> {
                        val strong = !prefs.getBoolean("adblock_strong", false)
                        prefs.edit().putBoolean("adblock_strong", strong).apply()
                        Toast.makeText(this, if (strong) "Посилене блокування" else "Стандартне блокування", Toast.LENGTH_SHORT).show()
                        web.reload()
                    }
                    2 -> toggleSiteException()
                    3 -> prefs.edit().putString("home", web.url ?: "https://www.google.com").apply()
                    4 -> web.clearCache(true)
                    5 -> saveEntries("history", emptyList())
                    6 -> CookieManager.getInstance().removeAllCookies(null)
                    7 -> saveEntries("bookmarks", emptyList())
                }
            }.setNegativeButton("Закрити", null).show()
    }

    private fun isAdBlockActive(): Boolean {
        if (!prefs.getBoolean("adblock", true)) return false
        val host = currentSiteHost ?: return true
        return host !in entries("adblock_exceptions")
    }

    private fun toggleSiteException() {
        val host = Uri.parse(web.url ?: "").host?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "Спочатку відкрийте сайт", Toast.LENGTH_SHORT).show()
            return
        }
        val exceptions = entries("adblock_exceptions")
        val added = if (exceptions.contains(host)) { exceptions.remove(host); false }
                    else { exceptions.add(host); true }
        saveEntries("adblock_exceptions", exceptions)
        Toast.makeText(this, if (added) "AdBlock вимкнено для $host" else "AdBlock увімкнено для $host", Toast.LENGTH_LONG).show()
        web.reload()
    }

    private fun applyCosmeticFilters() {
        val selectors = mutableListOf(
            "iframe[src*='doubleclick']", "iframe[src*='adservice']",
            "[id^='google_ads']", ".adsbygoogle", "[data-ad-slot]",
            "a[href*='beton.ua'] img", "a[href*='beton.com.ua'] img"
        )
        if (prefs.getBoolean("adblock_strong", false)) {
            selectors.addAll(listOf(
                "[class*='advertisement']", "[id*='advertisement']",
                "[class*='ad-banner']", "[class*='ad_banner']",
                "[class*='banner-ad']", "[class*='banner_ad']",
                "[class*='popup-ad']", "[id*='popup-ad']",
                "iframe[src*='ads']", "a[href*='beton.ua']",
                "a[href*='beton.com.ua']"
            ))
        }
        val css = selectors.joinToString(",") + "{display:none!important;visibility:hidden!important;max-height:0!important;}"
        val script = "(function(){try{var id='tvlight-adblock-style';var el=document.getElementById(id);if(!el){el=document.createElement('style');el.id=id;(document.head||document.documentElement).appendChild(el);}el.textContent=" +
            org.json.JSONObject.quote(css) + ";}catch(e){}})();"
        web.evaluateJavascript(script, null)
    }

    private fun navigate(value: String) {
        val input = value.trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("https://", true) -> input
            input.startsWith("http://", true) -> "https://" + input.substring(7)
            input.contains(" ") || !input.contains(".") ->
                "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8")
            else -> "https://$input"
        }
        homePanel.visibility = View.GONE
        root.getChildAt(0).visibility = View.VISIBLE
        root.getChildAt(1).visibility = View.VISIBLE
        page.visibility = View.VISIBLE
        web.loadUrl(url)
    }

    private fun closeFullscreen() {
        val view = fullscreen ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreen = null
        root.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (cursorMode && fullscreen == null && event.action == KeyEvent.ACTION_DOWN) {
            val step = 28f * resources.displayMetrics.density
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
                KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
                KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
                KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    val x = cursorX.coerceIn(0f, web.width.toFloat())
                    val y = cursorY.coerceIn(0f, web.height.toFloat())
                    web.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                    web.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
                    return true
                }
                else -> return super.dispatchKeyEvent(event)
            }
            cursorX = cursorX.coerceIn(0f, web.width.toFloat())
            cursorY = cursorY.coerceIn(0f, web.height.toFloat())
            cursorOverlay.invalidate()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            fullscreen != null -> closeFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        closeFullscreen()
        web.stopLoading()
        web.webChromeClient = null
        web.webViewClient = WebViewClient()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
