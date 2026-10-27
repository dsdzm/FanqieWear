package com.fanqie.wear.login

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机登录器（手表端）。
 *
 * 手表在 WiFi 上开一个极简 HTTP 服务，手机扫码打开页面，把番茄的 Cookie 粘进来点「发送」，
 * 手表收到就保存。全程只在局域网内，不出公网。
 *
 * 为什么不让手机网页自动抓 Cookie：番茄的 sessionid 是 HttpOnly，JS 读不到，
 * 所以这里提供粘贴入口；再往前一步是手机装伴侣 App 用 WebView 的 CookieManager（后续版本）。
 */
class PhoneLoginServer(private val context: Context) {

    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile private var onCookie: ((String) -> Unit)? = null
    @Volatile private var onLog: ((String) -> Unit)? = null

    val port = 8765

    fun start(onCookie: (String) -> Unit, onLog: (String) -> Unit = {}): Boolean {
        stop()
        this.onCookie = onCookie
        this.onLog = onLog
        return runCatching {
            acquireWifiLock()
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            server = s
            running.set(true)
            worker = Thread({
                while (running.get()) {
                    val client = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { handle(client) }.start()
                }
            }, "phone-login").apply { isDaemon = true; start() }
            onLog?.invoke("服务已启动：$address")
            true
        }.getOrElse {
            Log.e("PhoneLogin", "启动失败", it)
            onLog?.invoke("启动失败：${it.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        runCatching { worker?.interrupt() }
        worker = null
    }

    /** 手表在局域网里的地址，供二维码使用 */
    val address: String
        get() = "http://${localIp() ?: "127.0.0.1"}:$port"

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun acquireWifiLock() {
        runCatching {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fanqie:phone-login").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    // ---------------- HTTP ----------------

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            when {
                method == "GET" && (path == "/" || path.startsWith("/index")) -> {
                    respond(socket, "text/html; charset=utf-8", PAGE)
                }
                method == "POST" && path.startsWith("/save") -> {
                    val body = readBody(reader, contentLength)
                    val cookie = URLDecoder.decode(body.substringAfter("cookie=", ""), "UTF-8").trim()
                    if (cookie.isBlank()) {
                        respond(socket, "text/plain; charset=utf-8", "没收到内容")
                    } else {
                        onCookie?.invoke(cookie)
                        respond(socket, "text/html; charset=utf-8", DONE_PAGE)
                        onLog?.invoke("已收到 Cookie（${cookie.length} 字符）")
                    }
                }
                method == "GET" && path.startsWith("/ping") -> respond(socket, "text/plain", "ok")
                else -> respond(socket, "text/plain", "404")
            }
        }.onFailure { Log.w("PhoneLogin", "请求处理失败", it) }
        runCatching { socket.close() }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun respond(socket: Socket, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out: OutputStream = socket.getOutputStream()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val PAGE = """
<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>书阁 · 传 Cookie 给手表</title>
<style>
 body{font:16px/1.6 -apple-system,system-ui,sans-serif;margin:0;padding:18px;background:#12121a;color:#e8e8ee}
 h1{font-size:19px;margin:0 0 6px} .muted{color:#9a9aa8;font-size:13px}
 textarea{width:100%;height:150px;margin-top:12px;padding:10px;border-radius:10px;border:1px solid #333;
   background:#1b1b25;color:#e8e8ee;font:13px/1.5 ui-monospace,monospace;box-sizing:border-box}
 button{width:100%;margin-top:12px;padding:13px;border:0;border-radius:10px;background:#d4623f;color:#fff;
   font-size:16px;font-weight:600}
 ol{padding-left:20px;font-size:13px;color:#b8b8c4} code{background:#22222e;padding:1px 5px;border-radius:4px}
</style></head><body>
<h1>把番茄 Cookie 传到手表</h1>
<div class="muted">同一 WiFi 下才会成功，数据不出局域网。</div>
<ol>
 <li>手机浏览器打开 <code>fanqienovel.com</code> 并登录</li>
 <li>复制整条 Cookie（HttpOnly 的 sessionid 要用 F12 → 网络 → 请求头里拿）</li>
 <li>粘贴到下面，点发送</li>
</ol>
<textarea id="c" placeholder="sessionid=xxxxx; sid_guard=xxxxx; ..."></textarea>
<button onclick="send()">发送到手表</button>
<div id="s" class="muted" style="margin-top:10px"></div>
<script>
function send(){
  const v=document.getElementById('c').value.trim();
  if(!v){document.getElementById('s').textContent='先粘贴 Cookie';return}
  document.getElementById('s').textContent='发送中…';
  fetch('/save',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:'cookie='+encodeURIComponent(v)})
   .then(r=>r.text()).then(()=>{document.getElementById('s').textContent='已发送，回手表看看是否登录成功'})
   .catch(()=>{document.getElementById('s').textContent='发送失败，检查是否同一 WiFi'});
}
</script></body></html>
        """.trimIndent()

        private val DONE_PAGE = """
<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>已发送</title>
<style>body{font:16px/1.7 system-ui;background:#12121a;color:#e8e8ee;padding:40px 20px;text-align:center}
 .ok{font-size:44px}</style></head><body>
<div class="ok">✓</div><h2>已发送到手表</h2>
<p class="muted">回到手表看「番茄账号」，状态变成「已保存」即可。</p>
</body></html>
        """.trimIndent()
    }
}
