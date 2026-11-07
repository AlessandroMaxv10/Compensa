package br.com.compensa

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Sessão do usuário logado (guardada no celular). */
class Session(ctx: Context) {
    private val sp = ctx.getSharedPreferences("compensa_sessao", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()
    val token: String? get() = sp.getString("token", null)
    val name: String get() = sp.getString("name", "") ?: ""
    val role: String get() = sp.getString("role", "driver") ?: "driver"
    val lastUser: String get() = sp.getString("user", "") ?: ""
    private val tokenExp: Long get() = sp.getLong("tokenExp", 0)
    val validUntil: Long get() = sp.getLong("validUntil", 0) // 0 = sem validade

    /** Funciona offline: vale enquanto o token (30 dias) e a assinatura não vencerem. */
    val valid: Boolean
        get() {
            val now = System.currentTimeMillis()
            return token != null && tokenExp > now && (validUntil == 0L || validUntil > now)
        }

    fun save(user: String, j: JSONObject) {
        sp.edit()
            .putString("user", user)
            .putString("token", j.getString("token"))
            .putLong("tokenExp", j.optLong("tokenExp", 0))
            .putString("name", j.optString("name"))
            .putString("role", j.optString("role", "driver"))
            .putLong("validUntil", if (j.isNull("validUntil")) 0 else j.optLong("validUntil", 0))
            .apply()
    }

    fun updateValidity(validUntil: Long, active: Boolean) {
        if (!active) logout() else sp.edit().putLong("validUntil", validUntil).apply()
    }

    /** Identificador deste aparelho (criado uma vez e guardado). Serve para travar 1 conta = 1 celular. */
    val deviceId: String
        get() {
            var id = sp.getString("device", null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                sp.edit().putString("device", id).apply()
            }
            return id
        }

    fun logout() {
        sp.edit().remove("token").remove("tokenExp").apply()
    }
}

/** Comunicação com o servidor do Compensa. */
object Api {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class ApiError(val code: Int, msg: String) : Exception(msg)

    private fun call(server: String, method: String, path: String, token: String?, body: JSONObject?): JSONObject {
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.requestMethod = method
        c.setRequestProperty("Content-Type", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) throw ApiError(code, j.optString("error", "Erro $code no servidor"))
            return j
        } finally {
            c.disconnect()
        }
    }

    fun login(ctx: Context, server: String, user: String, pass: String, cb: (String?) -> Unit) {
        val s = Session(ctx)
        s.server = server
        val srv = s.server
        exec.execute {
            val err: String? = try {
                val j = call(srv, "POST", "/api/login", null,
                    JSONObject().put("user", user).put("pass", pass).put("device", s.deviceId))
                s.save(user, j)
                null
            } catch (e: ApiError) {
                e.message
            } catch (e: Exception) {
                "Não consegui falar com o servidor. Confira o endereço e a internet."
            }
            main.post { cb(err) }
            if (err == null) sync(ctx)
        }
    }

    /** Auto-cadastro: a pessoa pede acesso com nome + CPF (fica aguardando o admin liberar). */
    fun requestAccess(ctx: Context, server: String, name: String, cpf: String, cb: (String?) -> Unit) {
        postSimple(ctx, server, "/api/request-access", JSONObject().put("name", name).put("cpf", cpf), cb)
    }

    /** Esqueci a senha: pede a troca com nome + CPF. */
    fun forgot(ctx: Context, server: String, name: String, cpf: String, cb: (String?) -> Unit) {
        postSimple(ctx, server, "/api/forgot", JSONObject().put("name", name).put("cpf", cpf), cb)
    }

    private fun postSimple(ctx: Context, server: String, path: String, body: JSONObject, cb: (String?) -> Unit) {
        val s = Session(ctx)
        s.server = server
        val srv = s.server
        exec.execute {
            val err: String? = try {
                call(srv, "POST", path, null, body); null
            } catch (e: ApiError) {
                e.message
            } catch (e: Exception) {
                "Não consegui falar com o servidor. Confira a internet."
            }
            main.post { cb(err) }
        }
    }

    /** Primeiro acesso: nome + CPF liberados pelo administrador; a pessoa cria usuário e senha. */
    fun firstAccess(ctx: Context, server: String, name: String, cpf: String, user: String, pass: String, cb: (String?) -> Unit) {
        val s = Session(ctx)
        s.server = server
        val srv = s.server
        exec.execute {
            val err: String? = try {
                val body = JSONObject().put("name", name).put("cpf", cpf).put("user", user).put("pass", pass).put("device", s.deviceId)
                val j = call(srv, "POST", "/api/first-access", null, body)
                s.save(user, j)
                null
            } catch (e: ApiError) {
                e.message
            } catch (e: Exception) {
                "Não consegui falar com o servidor. Confira o endereço e a internet."
            }
            main.post { cb(err) }
            if (err == null) sync(ctx)
        }
    }

    /** Confere a conta, envia avaliações pendentes e baixa a IA coletiva. */
    fun sync(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        exec.execute {
            val s = Session(app)
            val t = s.token
            if (t != null && s.server.isNotEmpty()) {
                try {
                    val me = call(s.server, "GET", "/api/me", t, null)
                    s.updateValidity(if (me.isNull("validUntil")) 0 else me.optLong("validUntil"), me.optBoolean("active", true))
                    flushQueue(app, s.server, t)
                    val model = call(s.server, "GET", "/api/model", t, null)
                    val wa = model.optJSONArray("w")
                    if (wa != null && wa.length() == Brain.N) {
                        val gw = DoubleArray(Brain.N) { wa.getDouble(it) }
                        main.post { Brain(app).setGlobal(gw) }
                    }
                } catch (e: ApiError) {
                    if (e.code == 401 || e.code == 403) s.logout()
                } catch (_: Exception) {
                    // sem internet: tenta de novo depois
                }
            }
            if (done != null) main.post { done() }
        }
    }

    // ---- administração (só para conta admin) ----
    fun adminStats(ctx: Context, cb: (JSONObject?, String?) -> Unit) = authedGet(ctx, "/api/admin/stats", cb)
    fun adminUsers(ctx: Context, cb: (org.json.JSONArray?, String?) -> Unit) {
        val s = Session(ctx); val t = s.token
        if (t == null) { main.post { cb(null, "Faça login") }; return }
        exec.execute {
            try {
                val c = open(s.server, "GET", "/api/admin/users", t, null)
                val txt = readResp(c)
                val arr = org.json.JSONArray(txt)
                main.post { cb(arr, null) }
            } catch (e: ApiError) { main.post { cb(null, e.message) } }
            catch (e: Exception) { main.post { cb(null, "Sem conexão com o servidor") } }
        }
    }
    fun adminPatch(ctx: Context, id: String, body: JSONObject, cb: (String?) -> Unit) =
        authedSend(ctx, "PATCH", "/api/admin/users/$id", body, cb)
    fun adminCreate(ctx: Context, name: String, cpf: String, cb: (String?) -> Unit) =
        authedSend(ctx, "POST", "/api/admin/users", JSONObject().put("name", name).put("cpf", cpf), cb)
    fun adminDelete(ctx: Context, id: String, cb: (String?) -> Unit) =
        authedSend(ctx, "DELETE", "/api/admin/users/$id", null, cb)
    fun adminPost(ctx: Context, path: String, cb: (String?) -> Unit) =
        authedSend(ctx, "POST", path, JSONObject(), cb)

    private fun authedGet(ctx: Context, path: String, cb: (JSONObject?, String?) -> Unit) {
        val s = Session(ctx); val t = s.token
        if (t == null) { main.post { cb(null, "Faça login") }; return }
        exec.execute {
            try {
                val j = call(s.server, "GET", path, t, null)
                main.post { cb(j, null) }
            } catch (e: ApiError) { main.post { cb(null, e.message) } }
            catch (e: Exception) { main.post { cb(null, "Sem conexão com o servidor") } }
        }
    }
    private fun authedSend(ctx: Context, method: String, path: String, body: JSONObject?, cb: (String?) -> Unit) {
        val s = Session(ctx); val t = s.token
        if (t == null) { main.post { cb("Faça login") }; return }
        exec.execute {
            val err = try { call(s.server, method, path, t, body); null }
            catch (e: ApiError) { e.message }
            catch (e: Exception) { "Sem conexão com o servidor" }
            main.post { cb(err) }
        }
    }
    private fun open(server: String, method: String, path: String, token: String?, body: JSONObject?): HttpURLConnection {
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 10000; c.requestMethod = method
        c.setRequestProperty("Content-Type", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        return c
    }
    private fun readResp(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: "[]"
        c.disconnect()
        if (code !in 200..299) throw ApiError(code, "Erro $code")
        return txt
    }

    // ---- fila de avaliações (👍/👎) para a IA coletiva ----
    private fun q(ctx: Context) = ctx.getSharedPreferences("compensa_fila", Context.MODE_PRIVATE)

    /** Avisa o servidor que o motorista está ativo agora (bolinha verde no painel). */
    fun ping(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute {
            val s = Session(app)
            val t = s.token ?: return@execute
            try { call(s.server, "POST", "/api/ping", t, JSONObject()) } catch (e: Exception) { }
        }
    }

    fun queueSample(ctx: Context, f: DoubleArray, good: Boolean) {
        synchronized(this) {
            val arr = try { JSONArray(q(ctx).getString("q", "[]")) } catch (e: Exception) { JSONArray() }
            arr.put(JSONObject().put("f", JSONArray(f.toList())).put("y", if (good) 1 else 0))
            q(ctx).edit().putString("q", arr.toString()).apply()
        }
        sync(ctx)
    }

    private fun flushQueue(ctx: Context, server: String, token: String) {
        val arr = synchronized(this) {
            try { JSONArray(q(ctx).getString("q", "[]")) } catch (e: Exception) { JSONArray() }
        }
        if (arr.length() == 0) return
        call(server, "POST", "/api/samples", token, JSONObject().put("samples", arr))
        synchronized(this) {
            val now = try { JSONArray(q(ctx).getString("q", "[]")) } catch (e: Exception) { JSONArray() }
            val rest = JSONArray()
            for (i in arr.length() until now.length()) rest.put(now.get(i))
            q(ctx).edit().putString("q", rest.toString()).apply()
        }
    }
}
