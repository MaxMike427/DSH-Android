package com.dsh.android.pet

import android.content.Context
import org.json.JSONObject

/**
 * Talks to the running harness's pet API on the user's behalf.
 *
 * The plugin exposes its own HTTP surface under `/dsh-pet-7340` (loopback only,
 * no auth on top of the harness token the page already carries). The floating
 * window renders the pet through the harness page itself, so animations and
 * click reactions already match the in-app pet exactly; this client is what lets
 * the host offer the actions the page's own menu cannot reliably serve from a
 * second WebView instance — play an animation, say a line, put it to sleep.
 *
 * Requests run on a background dispatcher and results are delivered on the main
 * thread, because every caller is UI.
 */
class PetApi(private val context: Context) {

    /** One menu action: a label and what it does. */
    data class Action(val label: String, val run: (PetApi) -> Unit)

    /** Base URL of the harness, e.g. `http://127.0.0.1:3080`. */
    var baseUrl: String = ""

    /** The pet's id in the profile; `main` is the default single pet. */
    var petId: String = "main"

    /**
     * Play a named animation.
     *
     * `surface` must be given: the plugin refuses an action that does not say
     * which surface it targets. `web` is the surface this overlay renders (the
     * page), which is what the plugin checks against.
     */
    fun play(animName: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        post("/dsh-pet-7340/anim?pet=$petId&surface=web", JSONObject().put("name", animName), onResult)
    }

    /**
     * Show a line in the speech bubble above the pet.
     *
     * `broadcast` is the right call here rather than `chat`: it only displays
     * text, while `chat` asks the configured model for a reply and fails outright
     * until an API key is set.
     */
    fun say(text: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        post(
            "/dsh-pet-7340/broadcast?pet=$petId&surface=web",
            JSONObject().put("text", text).put("duration", 6),
            onResult,
        )
    }

    fun sleep(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        post("/dsh-pet-7340/sleep?pet=$petId&surface=web", JSONObject(), onResult)
    }

    fun wake(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        post("/dsh-pet-7340/wake?pet=$petId&surface=web", JSONObject(), onResult)
    }

    private fun post(path: String, body: JSONObject, onResult: (Boolean, String) -> Unit) {
        if (baseUrl.isBlank()) {
            onResult(false, "runtime not ready")
            return
        }
        Thread {
            val result = runCatching {
                val url = java.net.URL(baseUrl.trimEnd('/') + path)
                val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 4000
                    readTimeout = 8000
                    setRequestProperty("content-type", "application/json")
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                status to text
            }.getOrElse { error(it.message ?: "request failed") }

            // {"ok":true} is the success shape; the plugin reports failures with
            // a 200 and ok:false, so the body decides.
            val ok = result.first in 200..299 && !result.second.contains("\"ok\":false")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(ok, result.second.ifBlank { "HTTP ${result.first}" })
            }
        }.apply { isDaemon = true }.start()
    }

    private fun error(message: String): Pair<Int, String> = 0 to message

    companion object {
        /** The actions offered by the floating window's own menu. */
        fun menuActions(): List<List<Action>> = listOf(
            listOf(
                Action("打招呼") { api -> api.say("主人，我在这儿呢～") },
                Action("碎碎念") { api -> api.say("主人在忙什么呢？") },
            ),
            listOf(
                Action("走一走") { api -> api.play("螃蟹走路") },
                Action("原地转圈") { api -> api.play("小幅度原地360度旋转展示") },
                Action("吃点什么") { api -> api.play("吃白饭") },
            ),
            listOf(
                Action("睡觉") { api -> api.sleep() },
                Action("叫醒") { api -> api.wake() },
            ),
        )
    }
}