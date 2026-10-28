package com.anshu.labs.offline

// Labs Offline: phone ke andar AI chalane wala plugin (Google ki LiteRT-LM library).
// Ye file GitHub build me apne aap sahi folder me rakh di jati hai (setup_android.py se). Tumhe kuch karna nahi.

import android.util.Base64
import android.view.WindowManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private class DownloadCancelled : Exception()

@CapacitorPlugin(name = "LabsLlm")
class LabsLlmPlugin : Plugin() {

    private val lock = Any()

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedGpu = false
    @Volatile private var loadedVision = false

    private val downloading = AtomicBoolean(false)
    @Volatile private var dlCancel = false

    private val generating = AtomicBoolean(false)
    @Volatile private var genId = 0L
    @Volatile private var activeConv: Conversation? = null
    @Volatile private var stopFlag = false

    // ---------- files ----------

    private fun modelsDir(): File {
        val d = File(context.filesDir, "models")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun modelFile(): File = File(modelsDir(), "model.litertlm")
    private fun partFile(): File = File(modelsDir(), "model.part")
    private fun nameFile(): File = File(modelsDir(), "model.name")
    private fun mb(bytes: Long): Long = if (bytes > 0) bytes / (1024L * 1024L) else 0L

    private fun readName(): String {
        return try {
            nameFile().readText().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun describe(t: Throwable): String {
        val m = t.message
        val base = if (m.isNullOrBlank()) t.javaClass.simpleName else m
        val c = t.cause?.message
        return if (!c.isNullOrBlank() && c != m) "$base ($c)" else base
    }

    // ---------- status ----------

    @PluginMethod
    fun status(call: PluginCall) {
        val f = modelFile()
        val p = partFile()
        val o = JSObject()
        o.put("hasModel", f.exists())
        o.put("name", if (f.exists()) readName() else "")
        o.put("mb", if (f.exists()) mb(f.length()) else 0L)
        o.put("partMb", if (p.exists()) mb(p.length()) else 0L)
        o.put("loaded", engine != null)
        o.put("gpu", loadedGpu)
        o.put("vision", loadedVision)
        o.put("downloading", downloading.get())
        o.put("freeMb", mb(modelsDir().usableSpace))
        call.resolve(o)
    }

    // ---------- download (resume support) ----------

    private fun emitDl(state: String, doneMb: Long, totalMb: Long, msg: String) {
        val o = JSObject()
        o.put("state", state)
        o.put("doneMb", doneMb)
        o.put("totalMb", totalMb)
        o.put("message", msg)
        notifyListeners("dl", o)
    }

    private fun openConn(urlStr: String, from: Long): HttpURLConnection {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 40000
        c.setRequestProperty("User-Agent", "LabsOffline/1.0")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    @PluginMethod
    fun download(call: PluginCall) {
        val url = call.getString("url") ?: ""
        val label = call.getString("name") ?: "model"
        if (!url.startsWith("https://")) {
            call.reject("Link https:// se shuru hona chahiye")
            return
        }
        if (!downloading.compareAndSet(false, true)) {
            call.reject("Download pehle se chal raha hai")
            return
        }
        dlCancel = false
        // naya link ho to purani adhuri file se jodna galat hoga, isliye shuru se
        if (call.getBoolean("fresh", false) == true) {
            partFile().delete()
        }
        val started = JSObject()
        started.put("started", true)
        call.resolve(started)
        Thread { downloadWorker(url, label) }.start()
    }

    private fun downloadWorker(urlStr: String, label: String) {
        val part = partFile()
        val dest = modelFile()
        try {
            var existing = if (part.exists()) part.length() else 0L
            var conn = openConn(urlStr, existing)
            var code = conn.responseCode
            if (code == 416) {
                // purani adhuri file is link se match nahi karti, shuru se lete hain
                conn.disconnect()
                part.delete()
                existing = 0L
                conn = openConn(urlStr, 0L)
                code = conn.responseCode
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                throw IOException("Server ne HTTP " + code + " diya. Link galat ya band ho sakta hai.")
            }
            val resumed = code == 206 && existing > 0
            if (!resumed) existing = 0L
            val len = conn.contentLengthLong
            val total = if (len > 0) len + existing else -1L
            val need = (if (total > 0) total - existing else 0L) + 150L * 1024L * 1024L
            if (modelsDir().usableSpace < need) {
                conn.disconnect()
                throw IOException("Phone me jagah kam hai. Kam se kam " + mb(need) + " MB khali chahiye.")
            }
            var done = existing
            var lastEmit = 0L
            emitDl("progress", mb(done), mb(total), "")
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (dlCancel) throw DownloadCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n.toLong()
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 400L) {
                            lastEmit = now
                            emitDl("progress", mb(done), mb(total), "")
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && part.length() < total) {
                throw IOException("Download adhura reh gaya. Dobara dabao, wahin se aage badhega.")
            }
            closeEngine()
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("File ka naam badalne me dikkat aayi")
            nameFile().writeText(label)
            emitDl("done", mb(dest.length()), mb(dest.length()), "")
        } catch (e: DownloadCancelled) {
            emitDl("cancelled", mb(part.length()), 0L, "Download roka gaya. Adhura hissa save hai.")
        } catch (t: Throwable) {
            emitDl("error", mb(part.length()), 0L, describe(t))
        } finally {
            downloading.set(false)
        }
    }

    // download ke time screen band na ho (band hone par Android download rok deta hai)
    @PluginMethod
    fun keepAwake(call: PluginCall) {
        val on = call.getBoolean("on", false) == true
        val act = activity
        if (act != null) {
            act.runOnUiThread {
                if (on) {
                    act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        dlCancel = true
        call.resolve()
    }

    // ---------- load / unload ----------

    private fun closeEngine() {
        synchronized(lock) {
            val e = engine
            engine = null
            if (e != null) {
                try {
                    e.close()
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }

    @PluginMethod
    fun load(call: PluginCall) {
        val f = modelFile()
        if (!f.exists()) {
            call.reject("Model file nahi mili. Pehle model download karo.")
            return
        }
        if (downloading.get()) {
            call.reject("Download chal raha hai, thoda ruko.")
            return
        }
        val gpu = call.getBoolean("gpu", false) == true
        val vision = call.getBoolean("vision", false) == true
        Thread {
            try {
                synchronized(lock) {
                    if (engine == null || loadedGpu != gpu || loadedVision != vision) {
                        closeEngine()
                        val backend: Backend = if (gpu) Backend.GPU() else Backend.CPU()
                        // photo samajhne ke liye vision backend (GPU band ho to CPU, jo zyada safe hai)
                        val visionBackend: Backend? = if (vision) (if (gpu) Backend.GPU() else Backend.CPU()) else null
                        val cfg = EngineConfig(
                            modelPath = f.absolutePath,
                            backend = backend,
                            visionBackend = visionBackend,
                            cacheDir = context.cacheDir.path,
                        )
                        val e = Engine(cfg)
                        e.initialize()
                        engine = e
                        loadedGpu = gpu
                        loadedVision = vision
                    }
                }
                val o = JSObject()
                o.put("ok", true)
                call.resolve(o)
            } catch (t: Throwable) {
                call.reject(describe(t))
            }
        }.start()
    }

    @PluginMethod
    fun unload(call: PluginCall) {
        Thread {
            closeEngine()
            call.resolve()
        }.start()
    }

    @PluginMethod
    fun deleteModel(call: PluginCall) {
        if (downloading.get()) {
            call.reject("Download chal raha hai, pehle use roko.")
            return
        }
        Thread {
            closeEngine()
            modelFile().delete()
            partFile().delete()
            nameFile().delete()
            call.resolve()
        }.start()
    }

    // ---------- generate ----------

    private fun emitGen(kind: String, id: String, text: String, msg: String) {
        val o = JSObject()
        o.put("id", id)
        o.put("text", text)
        o.put("message", msg)
        notifyListeners(kind, o)
    }

    private fun closeConvLater(c: Conversation?) {
        if (c == null) return
        Thread {
            try {
                c.close()
            } catch (t: Throwable) {
                // ignore
            }
        }.start()
    }

    // sirf wahi generation flag girati hai jo abhi chal rahi thi (der se aaye callback naye ko na chhedein)
    private fun finishGen(myGen: Long, c: Conversation?) {
        if (genId == myGen && generating.compareAndSet(true, false)) {
            activeConv = null
        }
        closeConvLater(c)
    }

    @PluginMethod
    fun generate(call: PluginCall) {
        val id = call.getString("id") ?: "0"
        val system = call.getString("system") ?: ""
        val arr = call.getArray("turns")
        val eng = engine
        if (eng == null) {
            call.reject("Model load nahi hai")
            return
        }
        if (arr == null || arr.length() == 0) {
            call.reject("Sawal khali hai")
            return
        }
        val roles = ArrayList<String>()
        val texts = ArrayList<String>()
        val images = ArrayList<List<ByteArray>>()
        var anyImage = false
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                roles.add(o.getString("role"))
                texts.add(o.getString("text"))
                val imgs = ArrayList<ByteArray>()
                val ia = o.optJSONArray("images")
                if (ia != null) {
                    for (k in 0 until ia.length()) {
                        val b64 = ia.optString(k, "")
                        if (b64.isNotEmpty()) {
                            imgs.add(Base64.decode(b64, Base64.DEFAULT))
                        }
                    }
                }
                if (imgs.isNotEmpty()) anyImage = true
                images.add(imgs)
            }
        } catch (e: Exception) {
            call.reject("Sawal ya photo padh nahi paaya")
            return
        }
        if (anyImage && !loadedVision) {
            call.reject("Photo samajhne wala option on nahi hai. Model setup me 'Photo samajhna' on karo.")
            return
        }
        if (roles[roles.size - 1] != "user") {
            call.reject("Aakhri message user ka hona chahiye")
            return
        }
        val started = JSObject()
        started.put("started", true)
        call.resolve(started)
        Thread { runGeneration(eng, id, system, roles, texts, images) }.start()
    }

    private fun buildMessage(role: String, text: String, imgs: List<ByteArray>): Message {
        if (imgs.isEmpty()) {
            return if (role == "user") Message.user(text) else Message.model(text)
        }
        val list = ArrayList<Content>()
        for (b in imgs) {
            list.add(Content.ImageBytes(b))
        }
        list.add(Content.Text(text))
        return Message.user(Contents.of(list))
    }

    private fun runGeneration(
        eng: Engine,
        id: String,
        system: String,
        roles: List<String>,
        texts: List<String>,
        images: List<List<ByteArray>>,
    ) {
        // pichla jawab abhi khatam na hua ho (stop ke baad) to thoda ruko
        var waited = 0
        while (generating.get() && waited < 300) {
            Thread.sleep(100L)
            waited++
        }
        if (!generating.compareAndSet(false, true)) {
            emitGen("error", id, "", "Model abhi pichla jawab band kar raha hai. Thodi der baad dobara bhejo.")
            return
        }
        val myGen = System.nanoTime()
        genId = myGen
        stopFlag = false
        var conv: Conversation? = null
        try {
            val initial = ArrayList<Message>()
            for (i in 0 until roles.size - 1) {
                initial.add(buildMessage(roles[i], texts[i], images[i]))
            }
            val config = ConversationConfig(
                systemInstruction = if (system.isBlank()) null else Contents.of(system),
                initialMessages = initial,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
            )
            val c = eng.createConversation(config)
            conv = c
            activeConv = c
            val acc = StringBuilder()
            val finished = AtomicBoolean(false)
            var lastEmit = 0L
            c.sendMessageAsync(
                buildMessage("user", texts[texts.size - 1], images[images.size - 1]),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (stopFlag) return
                        val piece = message.toString()
                        if (piece.isEmpty()) return
                        val cur = acc.toString()
                        if (cur.isNotEmpty() && piece.length > cur.length && piece.startsWith(cur)) {
                            // kuch versions poora text dobara bhejte hain, kuch sirf naya tukda
                            acc.setLength(0)
                            acc.append(piece)
                        } else {
                            acc.append(piece)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 70L) {
                            lastEmit = now
                            emitGen("token", id, acc.toString(), "")
                        }
                    }

                    override fun onDone() {
                        if (finished.compareAndSet(false, true)) {
                            emitGen("done", id, acc.toString(), "")
                            finishGen(myGen, c)
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        if (finished.compareAndSet(false, true)) {
                            if (stopFlag) {
                                emitGen("done", id, acc.toString(), "")
                            } else {
                                emitGen("error", id, acc.toString(), describe(throwable))
                            }
                            finishGen(myGen, c)
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            emitGen("error", id, "", describe(t))
            finishGen(myGen, conv)
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        stopFlag = true
        val c = activeConv
        val g = genId
        if (c != null) {
            try {
                c.cancelProcess()
            } catch (t: Throwable) {
                // ignore
            }
            // agar library callback na bheje to 4 second baad khud chhod dete hain
            Thread {
                try {
                    Thread.sleep(4000L)
                } catch (e: InterruptedException) {
                    // ignore
                }
                if (genId == g && generating.get()) finishGen(g, c)
            }.start()
        }
        call.resolve()
    }
}
