package com.wcx.mywords

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * 安卓外壳：内嵌与网页版完全同源的 index.html + data.js（assets/web），
 * 发音优先使用 APK 内置的 6090 条真人美音（assets/audio/us），
 * 找不到才退回在线有道，最后用系统 TTS。全部离线可用。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var player: MediaPlayer? = null

    /** 小写词 -> 实际音频文件名（去扩展名），兼容 37 个首字母大写的文件 */
    private val audioMap = ConcurrentHashMap<String, String>(8192)

    /** 单线程串行后台池：音频索引、例句缓存、语音包下载共用，替代裸 Thread */
    private val bg = Executors.newSingleThreadExecutor()

    /** 音频焦点（API 26+）：播放时独占焦点，避免与后台音乐 App 混音 */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }
    private var focusReq: AudioFocusRequest? = null

    /** 旧版进度迁移脚本，只在首次启动执行一次 */
    private var migrateScript: String? = null

    /** 网页 <input type=file> 的回调（选壁纸） */
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 音频索引移到后台线程：6090 个文件名的主线程扫描会拖慢冷启动
        bg.execute { indexAudio() }

        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 页面自己处理深色主题，禁止 WebView 强制反色
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
        }
        web.setBackgroundColor(BG_LIGHT)
        web.addJavascriptInterface(Bridge(), "AndroidAudio")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 首次启动：把旧版（≤v2.9 Compose 版）的学习进度搬进网页存储，再重载一次生效
                migrateScript?.let { js ->
                    migrateScript = null
                    web.evaluateJavascript(js) { web.reload() }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // 渲染进程崩了不要让整个 App 退出，重启一次界面
                runCatching {
                    startActivity(Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
                return true
            }
        }
        // 相册选壁纸：网页里的 <input type="file"> 需要原生接管，否则点击无反应
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    startActivityForResult(Intent.createChooser(intent, "选择壁纸"), REQ_PICK_IMAGE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        migrateScript = buildMigrationScript()
        web.loadUrl("file:///android_asset/web/index.html")
        applySystemBars(false)
    }

    /**
     * 把 v2.9 及更早（Compose 版）保存在 SharedPreferences 里的进度转成网页版的 srs/hist。
     * 旧格式：{"v":2,"cards":{word:{st,sp,s,d,due,lr,r,l}},"hist":{"2026-10-5":37}}
     * 新格式：srs{word:{iv,e,due,r,wCnt,star,g}}、hist{"Mon Nov 09 2026":37}
     * 任何异常一律放弃迁移，绝不影响启动。
     */
    private fun buildMigrationScript(): String? = runCatching {
        val prefs = getSharedPreferences("progress", MODE_PRIVATE)
        val text = prefs.getString("data", null) ?: return null
        val root = JSONObject(text)
        val srs = JSONObject()
        val cards = if (root.optInt("v", 1) == 2) root.optJSONObject("cards")
        else {
            // v1: {"word":[iv, ease, due, reps, grade]}
            val legacy = JSONObject()
            root.keys().forEach { k ->
                root.optJSONArray(k)?.let { a ->
                    legacy.put(k, JSONObject()
                        .put("s", maxOf(a.optInt(0), 1)).put("d", a.optDouble(1, 2.5))
                        .put("due", a.optLong(2)).put("r", a.optInt(3)).put("l", 0))
                }
            }
            legacy
        } ?: return null
        if (cards.length() == 0) return null

        cards.keys().forEach { w ->
            val c = cards.optJSONObject(w) ?: return@forEach
            val s = c.optDouble("s", 0.0)
            val d = c.optDouble("d", 5.0)
            if (c.optInt("r", 0) <= 0 && s <= 0.0) return@forEach
            // FSRS 稳定性 -> 网页版的间隔天数
            val iv = s.roundToInt().coerceIn(1, 180)
            // 难度 1~10 -> 熟练度系数 3.0~1.3
            val e = (3.0 - (d.coerceIn(1.0, 10.0) - 1.0) / 9.0 * 1.7).coerceIn(1.3, 3.0)
            val lapses = c.optInt("l", 0)
            srs.put(w, JSONObject()
                .put("iv", iv)
                .put("e", Math.round(e * 100) / 100.0)
                .put("ease", Math.round(e * 100) / 100.0)
                .put("due", c.optLong("due", System.currentTimeMillis()))
                .put("r", c.optInt("r", 1))
                .put("wCnt", lapses)
                .put("star", false)
                .put("g", if (lapses > 0) 0 else 2))
        }
        if (srs.length() == 0) return null

        val hist = JSONObject()
        val oldHist = root.optJSONObject("hist")
        val fmt = java.text.SimpleDateFormat("EEE MMM dd yyyy", Locale.US)
        oldHist?.keys()?.forEach { k ->
            val n = oldHist.optLong(k, 0)
            if (n <= 0) return@forEach
            // 2026-10-5 -> Mon Oct 05 2026
            val p = k.split("-")
            if (p.size != 3) return@forEach
            runCatching {
                val cal = java.util.Calendar.getInstance()
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                hist.put(fmt.format(cal.time), n)
            }
        }

        """
(function(){
  try{
    if(localStorage.getItem('srs')) return;
    localStorage.setItem('srs', JSON.stringify(${srs}));
    localStorage.setItem('hist', JSON.stringify(${hist}));
  }catch(e){}
})();
"""
    }.getOrNull()


    /** 与生成端一致：小写，非字母数字连续串压成单个下划线 */
    private fun audioKey(word: String): String =
        word.trim().lowercase(Locale.US)
            .replace(RE_NON_ALNUM, "_")
            .trim('_')

    /** 建立词 -> 音频文件索引（一次性约几十毫秒） */
    private fun indexAudio() {
        runCatching {
            assets.list("audio/us")?.forEach { fn ->
                val base = fn.substringBeforeLast('.')
                audioMap[audioKey(base)] = base
            }
        }
    }

    /** 词 -> 实际音频文件名（去扩展名），找不到返回 null */
    private fun resolveAsset(word: String): String? {
        val key = audioKey(word)
        return if (key.isEmpty()) null else audioMap[key]
    }

    // ==================== 例句离线音频 ====================

    private fun sentDir(): File = File(filesDir, "sent_audio").apply { if (!exists()) mkdirs() }

    /** 与生成端 tts_gen.py 的 canon() 完全一致，保证 md5 键一致 */
    private fun canonText(t: String): String {
        var x = t.trim()
        x = x.replace(RE_SENT_EDGE, "")
        x = x.replace(RE_SENT_WS, " ")
        x = x.replace(RE_SENT_MULTI, " ").trim()
        return x
    }

    private fun sentKey(text: String): String {
        val d = MessageDigest.getInstance("MD5").digest(canonText(text).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun sentFile(text: String): File = File(sentDir(), sentKey(text) + ".mp3")

    /** 复用同一个 MediaPlayer（reset 后重载），避免频繁切词时反复创建/销毁 */
    private fun ensurePlayer(): MediaPlayer {
        val p = player ?: MediaPlayer().also { mp ->
            // 复用实例：播放完/出错不销毁，只释放音频焦点，下次 reset 继续用
            mp.setOnCompletionListener { abandonAudioFocus() }
            mp.setOnErrorListener { _, _, _ -> abandonAudioFocus(); true }
            player = mp
        }
        return p
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focusReq = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusReq?.let {
            runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        }
    }

    private fun playLocalFile(f: File) {
        try {
            val p = ensurePlayer()
            p.reset()
            p.setDataSource(f.absolutePath)
            p.prepare()
            if (!requestAudioFocus()) return
            p.start()
        } catch (e: Exception) {
            releasePlayer()
        }
    }

    private fun baiduTtsUrl(text: String, spd: Int): String =
        "https://fanyi.baidu.com/gettts?lan=en&text=" +
            java.net.URLEncoder.encode(text, "UTF-8") + "&spd=$spd&source=web&per=0"

    /** 后台把一句的发音下载到本地缓存（下次离线可播） */
    private fun cacheSentenceAsync(text: String) {
        bg.execute {
            var tmp: File? = null
            var conn: HttpURLConnection? = null
            try {
                val f = sentFile(text)
                if (f.exists() && f.length() > 3500) return@execute
                // 唯一临时名，避免连续点击同一句时双线程写同一 .tmp
                tmp = File(sentDir(), f.name + "." + System.nanoTime() + ".tmp")
                conn = URL(baiduTtsUrl(canonText(text), 4)).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                conn.inputStream.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out) } }
                if (tmp.length() > 3500) {
                    if (f.exists()) f.delete()
                    tmp.renameTo(f); tmp = null
                    if (packCountN.get() >= 0) packCountN.incrementAndGet()   // 未初始化(-1)时不覆写，等首次磁盘扫描
                } else tmp.delete()
            } catch (e: Exception) {
                runCatching { tmp?.delete() }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    // ---------- 语音包下载状态 ----------
    @Volatile private var packState = "idle"   // idle / downloading / done / error
    @Volatile private var packDone = 0L
    @Volatile private var packTotal = -1L
    @Volatile private var packUnzip = 0
    @Volatile private var packError = ""

    /** -1 = 尚未从磁盘初始化；AtomicInteger 保证并发缓存句子时计数不丢 */
    private val packCountN = AtomicInteger(-1)

    /** 下载进行中标志：CAS 抢占，快速连点不会起第二个下载任务 */
    private val packDownloading = AtomicBoolean(false)

    /** packCount() 磁盘重扫的互斥锁 */
    private val countLock = Any()

    private fun packCount(): Int {
        val cached = packCountN.get()
        if (cached >= 0) return cached
        synchronized(countLock) {
            val again = packCountN.get()
            if (again >= 0) return again
            val n = sentDir().listFiles()?.count { it.name.endsWith(".mp3") } ?: 0
            packCountN.set(n)
            return n
        }
    }

    private fun setPackState(v: String) {
        packState = v
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            runCatching { web.evaluateJavascript("document.dispatchEvent(new CustomEvent('packstate'))", null) }
        }
    }

    private fun downloadPackAsync(url: String) {
        // 先 CAS 抢占再置状态：旧写法「先查后置」有 TOCTOU，连点两下会起两个线程写坏同一个 zip
        if (!packDownloading.compareAndSet(false, true)) return
        setPackState("downloading")   // 起任务前同步置位，前端立即有反馈
        packDone = 0; packTotal = -1; packUnzip = 0; packError = ""
        bg.execute {
            var conn: HttpURLConnection? = null
            try {
                val tmpZip = File(cacheDir, "sent_pack.zip")
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = conn.responseCode
                if (code !in 200..299) {
                    packError = "服务器返回 $code，请稍后重试"
                    setPackState("error")
                    return@execute
                }
                packTotal = conn.contentLengthLong
                conn.inputStream.use { inp ->
                    FileOutputStream(tmpZip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); packDone += n
                        }
                    }
                }
                ZipInputStream(tmpZip.inputStream().buffered()).use { zis ->
                    val dir = sentDir()
                    while (true) {
                        val e: ZipEntry = zis.nextEntry ?: break
                        if (!e.isDirectory && e.name.endsWith(".mp3")) {
                            val outF = File(dir, File(e.name).name)
                            FileOutputStream(outF).use { zis.copyTo(it) }
                            packUnzip++
                        }
                        zis.closeEntry()
                    }
                }
                tmpZip.delete()
                // 计数以磁盘为准：置 -1 触发重扫，避免覆盖掉包外单独缓存的句子数
                packCountN.set(-1)
                packCount()
                packError = if (packUnzip > 0) "" else "包内无音频文件"
                setPackState(if (packUnzip > 0) "done" else "error")
            } catch (e: Exception) {
                runCatching { File(cacheDir, "sent_pack.zip").delete() }
                packError = e.message ?: "下载失败"
                setPackState("error")
            } finally {
                runCatching { conn?.disconnect() }
                packDownloading.set(false)
            }
        }
    }

    /** 必须在主线程调用：MediaPlayer 内部要建 Handler，需要 Looper */
    private fun startAsset(actual: String) {
        try {
            // use{}：setDataSource/prepare 抛异常时也会关闭 AssetFileDescriptor，不再泄漏
            assets.openFd("audio/us/$actual.mp3").use { afd ->
                val p = ensurePlayer()
                p.reset()
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                p.prepare()
                if (!requestAudioFocus()) return
                p.start()
            }
        } catch (e: Exception) {
            releasePlayer()
        }
    }

    /** 状态栏 / 导航栏跟随网页的明暗主题 */
    private fun applySystemBars(dark: Boolean) {
        val bar = if (dark) BG_DARK else BG_LIGHT
        window.statusBarColor = bar
        window.navigationBarColor = bar
        web.setBackgroundColor(bar)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            @Suppress("DEPRECATION")
            flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    inner class Bridge {
        /**
         * 注意：JavascriptInterface 的调用线程不是主线程，MediaPlayer 必须回主线程创建；
         * 这里只做「文件存在性」判断后异步播放，同步返回 true 让 JS 不再走在线兜底。
         */
        @JavascriptInterface
        fun play(word: String): Boolean {
            // 长文本（例句）不走 MediaPlayer：既没有对应音频，也能避开解码器异常
            if (word.length > 48) return false
            val actual = resolveAsset(word) ?: return false
            runOnUiThread { runCatching { startAsset(actual) } }
            return true
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread { releasePlayer() }
        }

        @JavascriptInterface
        fun setDark(dark: Boolean) {
            runOnUiThread { applySystemBars(dark) }
        }

        /** 例句本地发音：有缓存返回 true 并直接播放 */
        @JavascriptInterface
        fun sentencePlay(text: String): Boolean {
            val f = sentFile(text)
            if (!f.exists() || f.length() < 3500) return false
            runOnUiThread { runCatching { playLocalFile(f) } }
            return true
        }

        /** 在线播放后顺手缓存，下次离线可用 */
        @JavascriptInterface
        fun cacheSentence(text: String) {
            cacheSentenceAsync(text)
        }

        /** 已离线缓存的例句数量 */
        @JavascriptInterface
        fun packCount(): Int = this@MainActivity.packCount()

        /** 一键下载整包例句发音（zip） */
        @JavascriptInterface
        fun downloadPack(url: String) {
            downloadPackAsync(url)
        }

        /** 下载状态，供 JS 轮询：JSON（JSONObject 保证特殊字符安全转义） */
        @JavascriptInterface
        fun packStatus(): String {
            val percent = if (packTotal > 0) (packDone * 100 / packTotal).toInt() else -1
            return org.json.JSONObject()
                .put("state", packState)
                .put("percent", percent)
                .put("unzip", packUnzip)
                .put("count", this@MainActivity.packCount())
                .put("error", packError)
                .toString()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK_IMAGE) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            runCatching { fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null) }
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        // 返回逻辑收敛到前端统一的 window.handleAndroidBack()，页面名不再硬编码在 Kotlin 里
        web.evaluateJavascript(
            "(function(){try{return (typeof window.handleAndroidBack==='function')?String(window.handleAndroidBack()):'exit';}catch(e){return 'exit';}})()"
        ) { result ->
            if (result == null || !result.contains("nav")) finish()
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { if (player?.isPlaying == true) player?.pause() }
        abandonAudioFocus()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        bg.shutdownNow()   // 排队任务不再执行；正在跑的任务随网络超时自然收尾
        releasePlayer()
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val REQ_PICK_IMAGE = 1001

        val BG_LIGHT: Int = Color.rgb(0xEB, 0xF0, 0xFA)
        val BG_DARK: Int = Color.rgb(0x07, 0x0A, 0x12)

        // 正则只编译一次：此前 audioKey 每次播放重编译、canonText 每次缓存重编译
        val RE_NON_ALNUM = Regex("[^a-z0-9]+")
        val RE_SENT_EDGE = Regex("^[.\\u2026]+|[.\\u2026]+\u0024")
        val RE_SENT_WS = Regex("[\\u00a0\\u2000-\\u200b\\u3000]")
        val RE_SENT_MULTI = Regex("\\s{2,}")
    }
}
