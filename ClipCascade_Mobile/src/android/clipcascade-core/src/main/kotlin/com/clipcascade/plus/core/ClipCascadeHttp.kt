package com.clipcascade.plus.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI

enum class ClipType(val wireName: String) {
    TEXT("text"), IMAGE("image"), FILES("files");

    companion object {
        /** The server defaults a missing type to text. */
        fun fromWire(name: String?): ClipType? =
            if (name == null) TEXT else entries.firstOrNull { it.wireName == name }
    }
}

enum class ServerMode { P2S, P2P }

data class ServerInfo(val mode: ServerMode, val maxSizeBytes: Long?)

/** Everything that stops a connection attempt. The kind decides how SyncService retries. */
sealed class ConnectFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Retry soon, with backoff. */
    class Network(message: String, cause: Throwable? = null) : ConnectFailure(message, cause)

    /** The session is gone. Log in again straight away. */
    class SessionExpired : ConnectFailure("Session expired")

    /** Retry rarely, because repeated failures trigger the server's login lockout. */
    class BadCredentials : ConnectFailure("Incorrect username or password")

    /** The server or a proxy answered with something unexpected. */
    class Server(message: String) : ConnectFailure(message)

    /** This server runs in a mode the native client doesn't handle. */
    class UnsupportedMode(val mode: ServerMode) : ConnectFailure("Server is in $mode mode")
}

/** Cookies are handled by hand, so a session can be persisted and the client can see each redirect. */
class Session(val cookies: Map<String, String>) {
    val cookieHeader: String get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    val isUsable: Boolean get() = cookies.containsKey("JSESSIONID")
}

/** The HTTP half of the protocol. Calls block, so they belong on a background thread. */
class ClipCascadeHttp(baseUrl: String, okHttp: OkHttpClient) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    // Redirects are part of the protocol: the server answers a dead session with a 302 to /login,
    // never a 401 or 403, and following it would turn that into a 200 HTML page.
    private val http = okHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun login(username: String, loginHash: String): Session {
        val cookies = LinkedHashMap<String, String>()
        val page = execute(get("/login"), cookies) { response ->
            if (!response.isSuccessful) throw ConnectFailure.Server("Login page returned ${response.code}")
            response.body?.string().orEmpty()
        }
        val csrf = parseCsrf(page) ?: throw ConnectFailure.Server("No CSRF token on the login page")
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", loginHash)
            .add("_csrf", csrf)
            .build()
        val request = Request.Builder().url(baseUrl + "/login").post(form).withCookies(cookies).build()
        execute(request, cookies) { response ->
            when {
                response.isRedirect && !isLoginRedirect(response) -> Unit
                response.isRedirect -> throw ConnectFailure.BadCredentials()
                response.body?.string().orEmpty().contains("bad credentials", ignoreCase = true) ->
                    throw ConnectFailure.BadCredentials()
                response.code == 403 ->
                    throw ConnectFailure.Server("Login rejected (403). Check cookies and proxy HTTPS settings")
                else -> Unit
            }
        }
        val session = Session(cookies)
        if (!isValid(session)) throw ConnectFailure.Server("Login could not be verified")
        return session
    }

    fun isValid(session: Session): Boolean {
        if (!session.isUsable) return false
        return execute(get("/validate-session", session), null) { response ->
            when {
                response.isRedirect -> false
                response.code == 401 || response.code == 403 -> false
                response.isSuccessful -> response.body?.string()?.trim() == "OK"
                else -> throw ConnectFailure.Network("validate-session returned ${response.code}")
            }
        }
    }

    fun serverInfo(session: Session): ServerInfo {
        val mode = when (getJson("/server-mode", session, "mode").uppercase()) {
            "P2P" -> ServerMode.P2P
            else -> ServerMode.P2S
        }
        // Only meaningful in P2S mode. P2P servers don't relay clips.
        val maxSize = if (mode == ServerMode.P2S) {
            execute(get("/max-size", session), null) { response ->
                if (response.isRedirect) throw ConnectFailure.SessionExpired()
                if (!response.isSuccessful) return@execute null
                runCatching {
                    Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("maxsize").jsonPrimitive.long
                }.getOrNull()
            }
        } else null
        return ServerInfo(mode, maxSize)
    }

    private fun getJson(path: String, session: Session, field: String): String =
        execute(get(path, session), null) { response ->
            if (response.isRedirect) throw ConnectFailure.SessionExpired()
            if (!response.isSuccessful) throw ConnectFailure.Server("$path returned ${response.code}")
            try {
                Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue(field).jsonPrimitive.content
            } catch (e: Exception) {
                // For example a proxy's HTML page. The old client looped forever on this.
                throw ConnectFailure.Server("$path returned an unexpected response")
            }
        }

    private fun get(path: String, session: Session? = null): Request =
        Request.Builder().url(baseUrl + path).apply { session?.let { header("Cookie", it.cookieHeader) } }.build()

    private fun Request.Builder.withCookies(cookies: Map<String, String>): Request.Builder = apply {
        if (cookies.isNotEmpty()) header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    private inline fun <T> execute(request: Request, jar: MutableMap<String, String>?, handle: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ConnectFailure.Network(e.message ?: e.javaClass.simpleName, e)
        }
        return response.use {
            jar?.let { cookies ->
                it.headers("Set-Cookie").forEach { header ->
                    val pair = header.substringBefore(';')
                    val eq = pair.indexOf('=')
                    if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
            }
            try {
                handle(it)
            } catch (e: ConnectFailure) {
                throw e
            } catch (e: IOException) {
                throw ConnectFailure.Network(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    companion object {
        private val INPUT_TAG = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val NAME_CSRF = Regex("""\bname\s*=\s*["']_csrf["']""", RegexOption.IGNORE_CASE)
        private val VALUE = Regex("""\bvalue\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        internal fun parseCsrf(html: String): String? = INPUT_TAG.findAll(html)
            .firstOrNull { NAME_CSRF.containsMatchIn(it.value) }
            ?.let { VALUE.find(it.value)?.groupValues?.get(1) }

        internal fun isLoginRedirect(response: Response): Boolean {
            val location = response.header("Location") ?: return false
            val path = runCatching { URI(location).path }.getOrNull() ?: location.substringBefore('?')
            return path.trimEnd('/').endsWith("/login")
        }
    }
}
