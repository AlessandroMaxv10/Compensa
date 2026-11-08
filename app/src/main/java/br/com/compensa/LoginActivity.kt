package br.com.compensa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.com.compensa.Ui.button
import br.com.compensa.Ui.card
import br.com.compensa.Ui.column
import br.com.compensa.Ui.dp
import br.com.compensa.Ui.field
import br.com.compensa.Ui.gone
import br.com.compensa.Ui.header
import br.com.compensa.Ui.passwordField
import br.com.compensa.Ui.text

/**
 * Tela de entrada com dois modos:
 *  - Entrar: usuário + senha.
 *  - Primeiro acesso: nome + CPF (liberados pelo administrador) → cria usuário e senha.
 */
class LoginActivity : Activity() {

    private lateinit var server: EditText
    private lateinit var msg: TextView
    private lateinit var loginCard: LinearLayout
    private lateinit var firstCard: LinearLayout
    private lateinit var forgotCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(this)
        if (session.valid) {
            openMain()
            return
        }

        val root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        header(root, "Compensa", "Sua corrida compensa? A resposta na hora.")

        val sc = card(root)
        server = field(sc, "Endereço do servidor", session.server.ifEmpty { Config.DEFAULT_SERVER }, numeric = false)
        server.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        // ---------- Entrar ----------
        loginCard = card(root, "Entrar")
        val user = field(loginCard, "Usuário", session.lastUser, numeric = false)
        val pass = passwordField(loginCard, "Senha", "")
        lateinit var bLogin: Button
        bLogin = button(loginCard, "Entrar") {
            val srv = checkServer() ?: return@button
            if (user.text.isBlank() || pass.text.isEmpty()) { msg.text = "Preencha usuário e senha"; return@button }
            session.rememberUser(user.text.toString().trim())
            bLogin.isEnabled = false
            msg.text = "Entrando…"
            Api.login(this, srv, user.text.toString().trim(), pass.text.toString()) { err ->
                bLogin.isEnabled = true
                if (err == null) openMain() else msg.text = "❌ $err"
            }
        }
        button(loginCard, "É meu primeiro acesso", primary = false) { showFirst(true) }
        button(loginCard, "Esqueci minha senha", primary = false) { showForgot() }

        // ---------- Primeiro acesso ----------
        firstCard = card(root, "Primeiro acesso")
        firstCard.addView(text("Digite o nome completo e o CPF que o administrador cadastrou. Depois crie seu usuário e sua senha.", 14f, color = Ui.MUTED))
        val name = field(firstCard, "Nome completo", "", numeric = false)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val cpf = field(firstCard, "CPF (só números)", "")
        cpf.inputType = InputType.TYPE_CLASS_NUMBER
        firstCard.addView(text("Agora crie seu acesso:", 15f, true))
        val newUser = field(firstCard, "Crie um usuário (sem espaço, ex: joao.silva)", "", numeric = false)
        val newPass = passwordField(firstCard, "Crie uma senha (mínimo 6, não use o CPF)", "")
        val newPass2 = passwordField(firstCard, "Repita a senha", "")
        lateinit var bFirst: Button
        bFirst = button(firstCard, "Criar meu acesso") {
            val srv = checkServer() ?: return@button
            val cpfDigits = cpf.text.toString().filter { it.isDigit() }
            when {
                name.text.trim().split(" ").filter { it.isNotBlank() }.size < 2 -> { msg.text = "Coloque nome e sobrenome"; return@button }
                cpfDigits.length != 11 -> { msg.text = "O CPF tem 11 números"; return@button }
                !newUser.text.toString().trim().matches(Regex("[a-zA-Z0-9._@-]{3,40}")) -> { msg.text = "Usuário: de 3 a 40 letras ou números, sem espaço"; return@button }
                newPass.text.length < 6 -> { msg.text = "A senha precisa ter pelo menos 6 caracteres"; return@button }
                newPass.text.toString() != newPass2.text.toString() -> { msg.text = "As senhas não são iguais"; return@button }
            }
            bFirst.isEnabled = false
            msg.text = "Criando seu acesso…"
            Api.firstAccess(
                this, srv, name.text.toString().trim(), cpfDigits,
                newUser.text.toString().trim(), newPass.text.toString(),
            ) { err ->
                bFirst.isEnabled = true
                if (err == null) openMain() else msg.text = "❌ $err"
            }
        }
        button(firstCard, "Já tenho usuário e senha", primary = false) { showFirst(false) }

        forgotCard = card(root, "Esqueci minha senha")
        forgotCard.addView(text("Digite seu nome e CPF. Enviaremos um pedido de troca; depois fale com o suporte para concluir.", 14f, color = Ui.MUTED))
        val fName = field(forgotCard, "Nome completo", "", numeric = false)
        fName.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val fCpf = field(forgotCard, "CPF (só números)", "")
        fCpf.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        lateinit var bForgot: android.widget.Button
        bForgot = button(forgotCard, "Pedir troca de senha") {
            val srv = checkServer() ?: return@button
            val cpfDigits = fCpf.text.toString().filter { it.isDigit() }
            if (cpfDigits.length != 11) { msg.text = "O CPF tem 11 números"; return@button }
            bForgot.isEnabled = false
            msg.text = "Enviando…"
            Api.forgot(this, srv, fName.text.toString().trim(), cpfDigits) { err ->
                bForgot.isEnabled = true
                if (err == null) {
                    showForgot(false)
                    msg.text = "✅ Pedido enviado! Agora fale com o suporte no WhatsApp para concluir a troca."
                } else msg.text = "❌ $err"
            }
        }
        button(forgotCard, "Falar com o suporte (WhatsApp)", primary = false) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.link("Olá! Esqueci minha senha do Compensa e preciso de ajuda.")))) }
            catch (e: Exception) { msg.text = "Instale o WhatsApp: " + Support.DISPLAY }
        }
        button(forgotCard, "Voltar", primary = false) { showForgot(false) }

        msg = text("", 15f, true, Ui.DARK)
        root.addView(msg)

        val sup = card(root, "Precisa de ajuda?")
        sup.addView(text("Trocou de celular, esqueceu a senha ou quer usar em dois aparelhos seus? Fale com o suporte no WhatsApp " + Support.DISPLAY + ".", 14f, color = Ui.MUTED))
        button(sup, "Falar com o suporte (WhatsApp)", primary = false) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.link("Olá! Preciso de suporte no app Compensa."))))
            } catch (e: Exception) {
                msg.text = "Instale o WhatsApp para falar com o suporte: " + Support.DISPLAY
            }
        }
        showFirst(false)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(root)
        })
    }

    private fun showFirst(first: Boolean) {
        loginCard.gone(first)
        firstCard.gone(!first)
        forgotCard.gone(true)
        if (::msg.isInitialized) msg.text = ""
    }

    private fun showForgot(show: Boolean = true) {
        forgotCard.gone(!show)
        loginCard.gone(show)
        firstCard.gone(true)
        if (::msg.isInitialized) msg.text = ""
    }

    private fun checkServer(): String? {
        val srv = server.text.toString().trim()
        if (!srv.startsWith("http://") && !srv.startsWith("https://")) {
            msg.text = "O endereço do servidor deve começar com https://"
            return null
        }
        return srv
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
