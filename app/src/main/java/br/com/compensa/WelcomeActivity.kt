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
import br.com.compensa.Ui.text

/**
 * Primeira tela de quem nunca usou: explica o Compensa, deixa pedir acesso
 * (cadastro com nome + CPF) e falar com o suporte no WhatsApp.
 */
class WelcomeActivity : Activity() {

    private lateinit var msg: TextView
    private lateinit var cadastroCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session(this).valid) {
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }

        val root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        header(root, "Compensa", "Sua corrida compensa? A resposta na hora.")

        val sobre = card(root, "O que é o Compensa")
        sobre.addView(text(
            "O Compensa lê a oferta de corrida da Uber e da 99 na sua tela e avisa na hora se a corrida COMPENSA (fica verde) ou NÃO COMPENSA (fica vermelho).",
            15f))
        sobre.addView(text("• Calcula o ganho por km e por hora, já descontando o combustível.", 14f))
        sobre.addView(text("• Tem inteligência artificial que aprende o seu jeito de rodar.", 14f))
        sobre.addView(text("• Avisa sobre estrada de terra, chuva e corrida para fora da cidade.", 14f))
        sobre.addView(text("Funciona só em celular Android.", 13f, color = Ui.MUTED))

        // ---------- pedir acesso ----------
        val acesso = card(root, "Quero usar o Compensa")
        acesso.addView(text("Para usar, você precisa de uma liberação. Faça seu cadastro abaixo e fale com o suporte para liberar seu acesso.", 14f))
        button(acesso, "Fazer meu cadastro") { cadastroCard.gone(false); msg.text = "" }
        button(acesso, "Falar com o suporte (WhatsApp)", primary = false) {
            openWhats("Olá! Quero ter acesso ao app Compensa.")
        }
        button(acesso, "Já tenho acesso — entrar", primary = false) {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // ---------- cadastro ----------
        cadastroCard = card(root, "Cadastro")
        cadastroCard.addView(text("Preencha seus dados. Depois é só falar com o suporte para liberarem seu acesso.", 14f, color = Ui.MUTED))
        val name = field(cadastroCard, "Nome completo", "", numeric = false)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val cpf = field(cadastroCard, "CPF (só números)", "")
        cpf.inputType = InputType.TYPE_CLASS_NUMBER
        lateinit var bEnviar: Button
        bEnviar = button(cadastroCard, "Enviar cadastro") {
            val cpfDigits = cpf.text.toString().filter { it.isDigit() }
            when {
                name.text.trim().split(" ").filter { it.isNotBlank() }.size < 2 -> { msg.text = "Coloque nome e sobrenome"; return@button }
                cpfDigits.length != 11 -> { msg.text = "O CPF tem 11 números"; return@button }
            }
            bEnviar.isEnabled = false
            msg.text = "Enviando…"
            Api.requestAccess(this, Config.DEFAULT_SERVER, name.text.toString().trim(), cpfDigits) { err ->
                bEnviar.isEnabled = true
                if (err == null) {
                    cadastroCard.gone(true)
                    msg.text = "✅ Cadastro enviado! Agora fale com o suporte no WhatsApp para liberarem seu acesso."
                } else {
                    msg.text = "❌ $err"
                }
            }
        }
        button(cadastroCard, "Já enviei — falar com o suporte", primary = false) {
            openWhats("Olá! Fiz meu cadastro no Compensa e quero liberar meu acesso. Meu nome: " + name.text.toString().trim())
        }

        msg = text("", 15f, true, Ui.DARK)
        root.addView(msg)
        cadastroCard.gone(true)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(root)
        })
    }

    private fun openWhats(m: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.link(m))))
        } catch (e: Exception) {
            msg.text = "Instale o WhatsApp para falar com o suporte: " + Support.DISPLAY
        }
    }
}
