package br.com.compensa

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import br.com.compensa.Ui.button
import br.com.compensa.Ui.card
import br.com.compensa.Ui.column
import br.com.compensa.Ui.dp
import br.com.compensa.Ui.field
import br.com.compensa.Ui.header
import br.com.compensa.Ui.text
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var session: Session
    private lateinit var statusText: TextView
    private lateinit var locText: TextView
    private lateinit var todayText: TextView
    private lateinit var aiText: TextView
    private lateinit var historyText: TextView
    private lateinit var previewText: TextView
    private lateinit var subtitle: TextView
    private val fields = LinkedHashMap<String, EditText>()
    private val switches = LinkedHashMap<String, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        session = Session(this)
        if (!session.valid) { goLogin(); return }

        val root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        subtitle = header(root, "Compensa", "")

        // --- Status e ativação ---
        val st = card(root, "Leitura das corridas")
        statusText = text("", 15f)
        st.addView(statusText)
        button(st, "Ativar leitura da tela (Acessibilidade)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Toque em \"Compensa\" e ative", Toast.LENGTH_LONG).show()
        }
        st.addView(text("No Android 13 ou mais novo, se a opção estiver bloqueada: Configurações › Apps › Compensa › ⋮ › \"Permitir configurações restritas\".", 13f, color = Ui.MUTED))
        button(st, "Testar com corrida de exemplo (Uber)", primary = false) { test(SAMPLE_UBER, "Uber") }
        button(st, "Testar com corrida de exemplo (99)", primary = false) { test(SAMPLE_99, "99") }
        previewText = text("", 15f, true, Color.WHITE).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = android.view.View.GONE }
        st.addView(previewText)

        // --- Resumo do dia ---
        val td = card(root, "Hoje")
        todayText = text("", 15f)
        td.addView(todayText)

        // --- Localização ---
        val loc = card(root, "📍 Localização")
        locText = text("", 15f)
        loc.addView(locText)
        loc.addView(text("O Compensa usa onde você está agora para saber se o destino é fora da sua cidade e contar a volta vazia certinho.", 13f, color = Ui.MUTED))
        button(loc, "Permitir localização") { pedirLocalizacao() }

        // --- Metas e custos ---
        val cfg = card(root, "Suas metas e seu carro")
        fun f(key: String, label: String, def: String, numeric: Boolean = true) {
            fields[key] = field(cfg, label, prefs.raw(key, def), numeric)
        }
        f(Prefs.K_MIN_KM, "Mínimo R\$ por km", "1,80")
        f(Prefs.K_MIN_HORA, "Mínimo R\$ por hora", "35")
        f(Prefs.K_MAX_BUSCA, "Busca máxima até o passageiro (km)", "4")
        f(Prefs.K_PRECO, "Preço do combustível (R\$/litro)", "6,19")
        f(Prefs.K_KML, "Consumo do carro (km por litro)", "13")
        f(Prefs.K_CUSTO_KM, "Outros custos por km (pneu, óleo, manutenção) R\$", "0,20")
        f(Prefs.K_CIDADE, "Sua cidade base", "São Roque", numeric = false)
        f(Prefs.K_BAIRROS, "Bairros/lugares que você evita (separe por vírgula)", "", numeric = false)

        fun sw(key: String, label: String, def: Boolean) {
            val s = Switch(this).apply {
                text = label
                textSize = 15f
                isChecked = prefs.sp.getBoolean(key, def)
                setPadding(0, dp(8), 0, dp(8))
            }
            switches[key] = s
            cfg.addView(s)
        }
        cfg.addView(text("Quais apps o Compensa deve ler:", 15f, true))
        sw(Prefs.K_LER_UBER, "Ler ofertas da Uber", true)
        sw(Prefs.K_LER_99, "Ler ofertas da 99", true)
        sw(Prefs.K_EVITAR_TERRA, "Ficar vermelho se o destino for estrada de terra", true)
        sw(Prefs.K_VOLTA, "Contar a volta vazia em corridas para fora da cidade", true)
        sw(Prefs.K_MAPA, "Usar mapa e clima (terra, chuva, cidade)", true)
        sw(Prefs.K_VOZ, "Falar em voz alta (Compensa / Não compensa)", true)
        button(cfg, "Salvar") { save(); Toast.makeText(this, "Salvo!", Toast.LENGTH_SHORT).show() }

        // --- IA ---
        val ia = card(root, "🧠 Inteligência artificial")
        aiText = text("", 15f)
        ia.addView(aiText)
        button(ia, "Sincronizar IA coletiva agora", primary = false) {
            aiText.text = "Sincronizando…"
            Api.sync(this) { refresh() }
        }
        button(ia, "Zerar minha IA pessoal", primary = false) {
            AlertDialog.Builder(this).setMessage("Apagar tudo que a IA aprendeu com você?")
                .setPositiveButton("Apagar") { _, _ -> Brain(this).reset(); refresh() }
                .setNegativeButton("Cancelar", null).show()
        }

        // --- Etanol x gasolina ---
        val fuel = card(root, "⛽ Etanol ou gasolina?")
        val pg = field(fuel, "Preço gasolina (R\$/L)", prefs.raw(Prefs.K_PRECO, "6,19"))
        val pe = field(fuel, "Preço etanol (R\$/L)", prefs.raw("preco_eta", "4,29"))
        val kg = field(fuel, "Seu consumo com gasolina (km/L)", prefs.raw(Prefs.K_KML, "13"))
        val ke = field(fuel, "Seu consumo com etanol (km/L)", prefs.raw("kml_eta", "9,3"))
        val fuelOut = text("", 15f, true, Ui.GREEN)
        fuel.addView(fuelOut)
        button(fuel, "Calcular") {
            prefs.putString("preco_eta", pe.text.toString())
            prefs.putString("kml_eta", ke.text.toString())
            fuelOut.text = Fuel.compare(d(pg), d(pe), d(kg), d(ke))
        }

        // --- Dicas ---
        val tips = card(root, "💡 Dicas para o motorista")
        Tips.list.forEach { tips.addView(text(it, 14f)) }

        // --- Histórico ---
        val hist = card(root, "Últimas corridas analisadas")
        historyText = text("", 14f)
        hist.addView(historyText)

        // --- Suporte ---
        val sup = card(root, "📲 Suporte")
        sup.addView(text("Precisa de ajuda, trocou de celular ou quer usar em dois aparelhos seus? Fale comigo no WhatsApp " + Support.DISPLAY + ".", 14f))
        button(sup, "Falar com o suporte (WhatsApp)") { openSupport() }

        // --- Conta ---
        val acc = card(root, "Conta")
        acc.addView(text("Logado como " + session.name.ifEmpty { session.lastUser }, 15f))
        if (session.role == "admin") {
            button(acc, "🛠️ Administração (ver usuários e controlar)") {
                startActivity(Intent(this, AdminActivity::class.java))
            }
            button(acc, "Abrir painel completo no navegador", primary = false) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.server + "/")))
            }
        }
        button(acc, "Sair", primary = false) { session.logout(); goLogin() }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        Api.sync(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (!::statusText.isInitialized) return
        if (!session.valid) { goLogin(); return }
        refresh()
    }

    private fun d(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun save() {
        fields.forEach { (k, e) -> prefs.putString(k, e.text.toString()) }
        switches.forEach { (k, s) -> prefs.putBool(k, s.isChecked) }
    }

    private fun refresh() {
        val on = OfferReaderService.instance != null
        statusText.text = if (on) "✅ Leitura ATIVA. Abra a Uber ou a 99 e fique online."
        else "⛔ Leitura DESATIVADA. Toque no botão abaixo e ative o Compensa."
        statusText.setTextColor(if (on) Ui.GREEN else Color.rgb(217, 48, 37))

        val locOn = Locator.hasPermission(this)
        locText.text = if (locOn) "✅ Localização permitida. O cálculo usa onde você está agora."
        else "⛔ Localização desativada. Toque abaixo e permita (de preferência 'o tempo todo')."
        locText.setTextColor(if (locOn) Ui.GREEN else Color.rgb(217, 48, 37))

        val vu = session.validUntil
        subtitle.text = "Olá, " + session.name.ifEmpty { session.lastUser } +
            if (vu > 0) " • conta válida até " + SimpleDateFormat("dd/MM/yyyy", BR).format(Date(vu)) else ""

        val brain = Brain(this)
        val n = brain.samples
        aiText.text = if (brain.active)
            "IA personalizada ATIVA com $n avaliações suas, somada à IA coletiva de todos os motoristas."
        else "A IA já usa o conhecimento coletivo. Faltam " + (Brain.MIN_SAMPLES - n) +
            " avaliações (👍/👎 no balão) para ela ficar com o SEU jeito de rodar."

        // resumo do dia + histórico
        val arr = History.list(this)
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var hoje = 0; var verdes = 0; var somaKm = 0.0; var melhor = 0.0
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("dd/MM HH:mm", BR)
        for (i in arr.length() - 1 downTo 0) {
            val e = arr.getJSONObject(i)
            if (e.optLong("id") >= startOfDay) {
                hoje++
                if (e.optBoolean("good")) verdes++
                somaKm += e.optDouble("perKm", 0.0)
                melhor = maxOf(melhor, e.optDouble("perHour", 0.0))
            }
            if (arr.length() - i <= 15) {
                sb.append(if (e.optBoolean("good")) "🟢 " else "🔴 ")
                    .append(fmt.format(Date(e.optLong("id")))).append(" • ").append(e.optString("app")).append(" • ")
                    .append(money(e.optDouble("fare"))).append(" • ").append(money(e.optDouble("perKm"))).append("/km")
                when {
                    e.has("fb") && e.optBoolean("fb") -> sb.append(" 👍")
                    e.has("fb") -> sb.append(" 👎")
                }
                val dest = e.optString("dest")
                if (dest.isNotEmpty()) sb.append("\n     → ").append(dest.take(45))
                sb.append('\n')
            }
        }
        val horas = Shift.hoursToday(this)
        todayText.text = if (hoje == 0) "Nenhuma oferta analisada hoje ainda."
        else "Ofertas recebidas: $hoje   •   Compensaram: $verdes (" + (verdes * 100 / hoje) + "%)\n" +
            "Média oferecida: " + money(somaKm / hoje) + "/km   •   Melhor: " + money(melhor) + "/h\n" +
            "Tempo de turno: " + num(horas, 1) + " h"
        historyText.text = if (sb.isEmpty()) "Ainda vazio." else sb.toString()
    }

    private fun test(sample: String, app: String) {
        save()
        val offer = OfferParser.parse(sample, app) ?: return
        val svc = OfferReaderService.instance
        if (svc != null) {
            svc.handleOffer(offer)
            Toast.makeText(this, "Veja o balão no topo da tela", Toast.LENGTH_SHORT).show()
        }
        val v = Scorer.evaluate(offer, Prefs(this), Brain(this), Extras(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), false)
        previewText.visibility = android.view.View.VISIBLE
        previewText.setBackgroundColor(if (v.good) OverlayController.GREEN else OverlayController.RED)
        previewText.text = (if (v.good) "✅ COMPENSA" else "⛔ NÃO COMPENSA") + "\n" +
            money(offer.fare) + " • " + money(v.perKm) + "/km • " + money(v.perHour) + "/h\n" +
            "Lucro estimado: " + money(v.profit) +
            (if (v.reasons.isNotEmpty()) "\n" + v.reasons.joinToString("\n") else "") +
            (offer.destination?.let { "\nDestino lido: $it" } ?: "")
        refresh()
    }

    private fun pedirLocalizacao() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && Locator.hasPermission(this) && android.os.Build.VERSION.SDK_INT >= 29) {
            // pede "o tempo todo" para funcionar enquanto dirige (vai abrir as configurações no Android 11+)
            try { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1002) } catch (e: Exception) { }
        }
        refresh()
    }

    private fun openSupport() {
        val quem = session.name.ifEmpty { session.lastUser }
        val msg = "Olá! Sou $quem, uso o app Compensa e preciso de suporte."
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.link(msg))))
        } catch (e: Exception) {
            Toast.makeText(this, "Instale o WhatsApp para falar com o suporte: " + Support.DISPLAY, Toast.LENGTH_LONG).show()
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        const val SAMPLE_UBER = "UberX\nR\$ 18,50\n4,92 ★\n5 min (1,8 km) de distância\nRua Sete de Setembro, Centro\nViagem de 14 min (7,3 km)\nAv. Tiradentes, 450 - Jardim Bela Vista, São Roque\nAceitar"
        const val SAMPLE_99 = "99Pop\nR\$ 9,80\nAté o passageiro\n3,9 km • 9 min\nViagem\n4,2 km • 11 min\nRua das Flores, 120 - Vila Junqueira, São Roque\nAceitar"
    }
}
