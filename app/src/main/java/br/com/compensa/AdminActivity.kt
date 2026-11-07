package br.com.compensa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.com.compensa.Ui.button
import br.com.compensa.Ui.card
import br.com.compensa.Ui.column
import br.com.compensa.Ui.dp
import br.com.compensa.Ui.field
import br.com.compensa.Ui.header
import br.com.compensa.Ui.text
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/** Painel de administrador dentro do app (só para a conta admin). */
class AdminActivity : Activity() {

    private lateinit var statsBox: LinearLayout
    private lateinit var usersBox: LinearLayout
    private lateinit var root: LinearLayout
    private val fmt = SimpleDateFormat("dd/MM/yyyy", BR)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session(this).role != "admin") { finish(); return }

        root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        header(root, "Administração", "Controle de contas e uso")

        statsBox = card(root, "Visão geral")
        statsBox.addView(text("Carregando…", 14f, color = Ui.MUTED))

        val nova = card(root, "Liberar acesso para um motorista")
        nova.addView(text("Cadastre nome + CPF. A pessoa cria o login e a senha no primeiro acesso.", 14f, color = Ui.MUTED))
        val nName = field(nova, "Nome completo", "", numeric = false)
        nName.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val nCpf = field(nova, "CPF (só números)", "")
        nCpf.inputType = InputType.TYPE_CLASS_NUMBER
        val nDays = field(nova, "Dias de acesso (0 = sem limite)", "3")
        button(nova, "Liberar acesso") {
            val cpf = nCpf.text.toString().filter { it.isDigit() }
            if (nName.text.trim().split(" ").filter { it.isNotBlank() }.size < 2) { toast("Coloque nome e sobrenome"); return@button }
            if (cpf.length != 11) { toast("O CPF tem 11 números"); return@button }
            Api.adminCreate(this, nName.text.toString().trim(), cpf) { err ->
                if (err != null) { toast(err); return@adminCreate }
                val dias = nDays.text.toString().toIntOrNull() ?: 0
                // acha o id recém-criado e aplica os dias
                Api.adminUsers(this) { arr, _ ->
                    val id = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                        .firstOrNull { it.optBoolean("pending") && it.optString("cpf").isNotEmpty() && it.optLong("lastLogin", 0) == 0L }
                        ?.optString("id")
                    if (id != null && dias > 0) Api.adminPatch(this, id, JSONObject().put("days", dias)) { }
                    nName.setText(""); nCpf.setText("")
                    toast("Acesso liberado!")
                    reload()
                }
            }
        }

        val tools = card(root, "Ferramentas")
        button(tools, "🧠 Treinar a IA coletiva agora", primary = false) { trainCollective() }
        button(tools, "Atualizar", primary = false) { reload() }
        button(tools, "Abrir painel completo no navegador", primary = false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Session(this).server + "/")))
        }

        usersBox = card(root, "Contas")
        usersBox.addView(text("Carregando…", 14f, color = Ui.MUTED))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(root)
        })
        reload()
    }

    override fun onResume() { super.onResume(); if (::statsBox.isInitialized) reload() }

    private fun trainCollective() {
        // usa o endpoint /api/admin/train via POST autenticado
        Api.adminPost(this, "/api/admin/train") { err ->
            toast(if (err == null) "IA treinada!" else err); reload()
        }
    }

    private fun reload() {
        Api.adminStats(this) { s, err ->
            statsBox.removeAllViews()
            statsBox.addView(text("Visão geral", 17f, true))
            if (s == null) { statsBox.addView(text(err ?: "Erro", 14f, color = Ui.RED_TX)); return@adminStats }
            fun linha(label: String, v: Any) {
                val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
                l.addView(TextView(this).apply {
                    text = "$v"; textSize = 20f; setTextColor(Ui.GREEN); typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                l.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Ui.DARK); gravity = Gravity.CENTER_VERTICAL })
                statsBox.addView(l)
            }
            linha("motoristas cadastrados", s.optInt("drivers"))
            linha("com login criado", s.optInt("withLogin"))
            linha("instalados no celular", s.optInt("installed"))
            linha("usaram nas últimas 24h", s.optInt("using24h"))
            linha("usaram nos últimos 7 dias", s.optInt("using7d"))
            linha("pedindo acesso agora", s.optInt("requests"))
            linha("pediram troca de senha", s.optInt("resetRequests"))
            linha("avaliações recebidas (IA)", s.optInt("samples"))
        }
        Api.adminUsers(this) { arr, err ->
            usersBox.removeAllViews()
            usersBox.addView(text("Contas", 17f, true))
            if (arr == null) { usersBox.addView(text(err ?: "Erro", 14f, color = Ui.RED_TX)); return@adminUsers }
            if (arr.length() == 0) { usersBox.addView(text("Nenhuma conta ainda.", 14f, color = Ui.MUTED)); return@adminUsers }
            // pedidos primeiro
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                .sortedByDescending { (if (it.optBoolean("requested")) 2 else 0) + (if (it.optBoolean("resetRequested")) 1 else 0) }
            for (u in items) addUserRow(u)
        }
    }

    private fun addUserRow(u: JSONObject) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Ui.BG)
            }
        }
        val id = u.optString("id")
        val name = u.optString("name")
        val requested = u.optBoolean("requested")
        val pending = u.optBoolean("pending")
        val active = u.optBoolean("active")
        val vu = u.optLong("validUntil", 0)
        val venc = vu > 0 && vu < System.currentTimeMillis()
        val status = when {
            requested -> "📝 Pediu acesso"
            !active -> "⛔ Bloqueada"
            pending -> "⏳ Aguardando 1º acesso"
            venc -> "⌛ Vencida"
            else -> "✅ Ativa"
        }
        val extra = buildString {
            if (u.optBoolean("resetRequested")) append("  🔑 pediu troca de senha")
        }
        row.addView(text(name, 16f, true))
        val sub = StringBuilder(status + extra)
        if (u.optString("user").isNotEmpty()) sub.append("\nusuário: ").append(u.optString("user"))
        if (u.optString("cpf").isNotEmpty()) sub.append("  •  CPF ").append(u.optString("cpf"))
        sub.append("\nValidade: ").append(if (vu > 0) fmt.format(Date(vu)) else "sem limite")
        sub.append("  •  Aparelhos: ").append(u.optInt("devices")).append("/").append(u.optInt("maxDevices", 1))
        row.addView(text(sub.toString(), 13f, color = Ui.MUTED))

        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        fun chip(label: String, click: () -> Unit) {
            b.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Ui.GREEN) }
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, dp(6), dp(6)) }
            })
        }
        // ações em linhas que quebram
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (requested) chip("✅ Liberar") { patch(id, JSONObject().put("approve", true), "Acesso liberado!") }
        chip(if (active) "Bloquear" else "Liberar") { patch(id, JSONObject().put("active", !active), "Feito") }
        chip("Dias de acesso") { askDays(id, name) }
        chip("+30 dias") { patch(id, JSONObject().put("addDays", 30), "+30 dias") }
        chip(if (u.optInt("maxDevices", 1) == 2) "1 aparelho" else "2 aparelhos") {
            val novo = if (u.optInt("maxDevices", 1) == 2) 1 else 2
            patch(id, JSONObject().put("maxDevices", novo).apply { if (novo == 1) put("unbind", true) }, "Feito")
        }
        if (u.optInt("devices") > 0) chip("Desvincular") { patch(id, JSONObject().put("unbind", true), "Aparelho liberado") }
        if (!pending && u.optString("cpf").isNotEmpty()) chip("Refazer acesso") {
            confirmar("Refazer acesso de $name? Ele cria login e senha de novo com nome + CPF.") {
                patch(id, JSONObject().put("reset", true), "Pronto")
            }
        }
        chip("Excluir") {
            confirmar("Excluir a conta de $name? Não dá para desfazer.") {
                Api.adminDelete(this, id) { err -> toast(err ?: "Excluído"); reload() }
            }
        }
        wrap.addView(b)
        row.addView(wrap)

        usersBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(8), 0, 0) })
    }

    private fun askDays(id: String, name: String) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "ex: 30 (0 = sem limite)" }
        AlertDialog.Builder(this)
            .setTitle("Dias de acesso de $name")
            .setMessage("Quantos dias a partir de hoje?")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val d = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                patch(id, JSONObject().put("days", d), if (d > 0) "Acesso de $d dias" else "Sem limite")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun patch(id: String, body: JSONObject, ok: String) {
        Api.adminPatch(this, id, body) { err -> toast(err ?: ok); reload() }
    }

    private fun confirmar(msg: String, acao: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Sim") { _, _ -> acao() }
            .setNegativeButton("Não", null).show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
