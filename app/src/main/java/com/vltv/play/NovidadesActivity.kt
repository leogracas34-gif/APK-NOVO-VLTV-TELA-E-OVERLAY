package com.vltv.play

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovidadesActivity : AppCompatActivity() {

    private lateinit var tabEmBreve: TextView
    private lateinit var tabBombando: TextView
    private lateinit var tabTopSeries: TextView
    private lateinit var tabTopFilmes: TextView
    private lateinit var recyclerNovidades: RecyclerView
    private lateinit var bottomNavigation: BottomNavigationView

    private lateinit var adapter: NovidadesAdapter

    private var listaEmBreve = mutableListOf<NovidadeItem>()
    private var listaBombando = mutableListOf<NovidadeItem>()
    private var listaTopSeries = mutableListOf<NovidadeItem>()
    private var listaTopFilmes = mutableListOf<NovidadeItem>()

    private val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
    private val client = OkHttpClient()
    private var currentProfile: String = "Padrao"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novidades)

        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        inicializarViews()
        configurarRecyclerView()
        configurarCliquesDasAbas()
        configurarRodape()

        carregarTodasAsListasTMDb()
    }

    private fun inicializarViews() {
        tabEmBreve = findViewById(R.id.tabEmBreve)
        tabBombando = findViewById(R.id.tabBombando)
        tabTopSeries = findViewById(R.id.tabTopSeries)
        tabTopFilmes = findViewById(R.id.tabTopFilmes)
        recyclerNovidades = findViewById(R.id.recyclerNovidades)
        bottomNavigation = findViewById(R.id.bottomNavigation)
    }

    private fun configurarRodape() {
        bottomNavigation.selectedItemId = R.id.nav_novidades
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    finish() 
                    true
                }
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_novidades -> true
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun configurarRecyclerView() {
        adapter = NovidadesAdapter(emptyList(), currentProfile)
        recyclerNovidades.layoutManager = LinearLayoutManager(this)
        recyclerNovidades.adapter = adapter
    }

    private fun configurarCliquesDasAbas() {
        tabEmBreve.setOnClickListener {
            ativarAba(tabEmBreve)
            adapter.atualizarLista(listaEmBreve)
            recyclerNovidades.scrollToPosition(0)
        }
        tabBombando.setOnClickListener {
            ativarAba(tabBombando)
            adapter.atualizarLista(listaBombando)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTopSeries.setOnClickListener {
            ativarAba(tabTopSeries)
            adapter.atualizarLista(listaTopSeries)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTopFilmes.setOnClickListener {
            ativarAba(tabTopFilmes)
            adapter.atualizarLista(listaTopFilmes)
            recyclerNovidades.scrollToPosition(0)
        }
    }

    private fun ativarAba(abaAtiva: TextView) {
        val todasAsAbas = listOf(tabEmBreve, tabBombando, tabTopSeries, tabTopFilmes)
        for (aba in todasAsAbas) {
            if (aba == abaAtiva) {
                aba.setBackgroundResource(R.drawable.bg_aba_selecionada)
                aba.setTextColor(Color.BLACK)
            } else {
                aba.setBackgroundResource(R.drawable.bg_aba_inativa)
                aba.setTextColor(Color.WHITE)
            }
        }
    }

    private fun carregarTodasAsListasTMDb() {
        Toast.makeText(this, "Atualizando lançamentos...", Toast.LENGTH_SHORT).show()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dataHoje = sdf.format(Date()) 
        
        val urlEmBreve = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&region=BR&with_release_type=2|3&primary_release_date.gte=$dataHoje&sort_by=primary_release_date.asc"
        buscarDadosNaApi(urlEmBreve, listaEmBreve, isTop10 = false, tagFixa = "Estreia em Breve", isEmBreve = true) {
            runOnUiThread { adapter.atualizarLista(listaEmBreve) }
        }

        val dataRecente = "2024-01-01" 
        
        val urlBombando = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&primary_release_date.gte=$dataRecente&sort_by=popularity.desc"
        buscarDadosNaApi(urlBombando, listaBombando, isTop10 = false, tagFixa = "Em Alta no Mundo", isEmBreve = false) {}

        val urlTopSeries = "https://api.themoviedb.org/3/discover/tv?api_key=$apiKey&language=pt-BR&first_air_date.gte=$dataRecente&sort_by=popularity.desc"
        buscarDadosNaApi(urlTopSeries, listaTopSeries, isTop10 = true, tagFixa = "Top 10 Séries", isEmBreve = false) {}

        val urlTopFilmes = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&primary_release_date.gte=$dataRecente&sort_by=popularity.desc"
        buscarDadosNaApi(urlTopFilmes, listaTopFilmes, isTop10 = true, tagFixa = "Top 10 Filmes", isEmBreve = false) {}
    }

    private fun buscarDadosNaApi(
        url: String, 
        listaDestino: MutableList<NovidadeItem>, 
        isTop10: Boolean, 
        tagFixa: String, 
        isEmBreve: Boolean,
        onSucesso: () -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val jsonObject = JSONObject(body)
                    val results = jsonObject.optJSONArray("results") ?: return
                    
                    val limite = if (isTop10) 10 else Math.min(results.length(), 20)
                    for (i in 0 until limite) {
                        val itemJson = results.getJSONObject(i)
                        val id = itemJson.optInt("id")
                        val titulo = itemJson.optString("title", itemJson.optString("name", "Sem Título"))
                        val sinopse = itemJson.optString("overview", "Descrição indisponível.")
                        val backdropPath = itemJson.optString("backdrop_path", "")
                        
                        if (backdropPath.isEmpty()) continue
                        
                        val imagemUrl = "https://image.tmdb.org/t/p/w780$backdropPath"
                        
                        var dataEstreia = tagFixa
                        val releaseDate = itemJson.optString("release_date", itemJson.optString("first_air_date", ""))
                        if (releaseDate.isNotEmpty() && isEmBreve) {
                            dataEstreia = formatarData(releaseDate)
                        }

                        // LÓGICA PARA BUSCAR O TRAILER DO YOUTUBE
                        var youtubeKey: String? = null
                        val tipoMedia = if (url.contains("/tv")) "tv" else "movie"
                        val videoUrl = "https://api.themoviedb.org/3/$tipoMedia/$id/videos?api_key=$apiKey&language=pt-BR"
                        
                        try {
                            val videoReq = Request.Builder().url(videoUrl).build()
                            val videoRes = client.newCall(videoReq).execute() // Pedido síncrono em background
                            val videoBody = videoRes.body?.string()
                            if (videoBody != null) {
                                val videoJson = JSONObject(videoBody)
                                val vResults = videoJson.optJSONArray("results")
                                if (vResults != null && vResults.length() > 0) {
                                    for (j in 0 until vResults.length()) {
                                        val v = vResults.getJSONObject(j)
                                        if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                                            youtubeKey = v.optString("key")
                                            break
                                        }
                                    }
                                    if (youtubeKey == null) youtubeKey = vResults.getJSONObject(0).optString("key")
                                }
                            }
                        } catch (e: Exception) { }

                        // Plano B: Se não encontrar em PT-BR, procura o trailer original (Inglês)
                        if (youtubeKey == null) {
                            try {
                                val videoUrlEn = "https://api.themoviedb.org/3/$tipoMedia/$id/videos?api_key=$apiKey"
                                val videoReqEn = Request.Builder().url(videoUrlEn).build()
                                val videoResEn = client.newCall(videoReqEn).execute()
                                val videoBodyEn = videoResEn.body?.string()
                                if (videoBodyEn != null) {
                                    val videoJsonEn = JSONObject(videoBodyEn)
                                    val vResultsEn = videoJsonEn.optJSONArray("results")
                                    if (vResultsEn != null && vResultsEn.length() > 0) {
                                        youtubeKey = vResultsEn.getJSONObject(0).optString("key")
                                    }
                                }
                            } catch (e: Exception) { }
                        }

                        listaDestino.add(
                            NovidadeItem(
                                id = id, titulo = titulo, sinopse = sinopse, 
                                imagemFundoUrl = imagemUrl, tagline = dataEstreia, 
                                isTop10 = isTop10, posicaoTop10 = i + 1, isEmBreve = isEmBreve,
                                trailerUrl = youtubeKey // A chave do YouTube é enviada para o Adapter aqui!
                            )
                        )
                    }
                    onSucesso()
                } catch (e: Exception) { }
            }
        })
    }

    private fun formatarData(dataIngles: String): String {
        return try {
            val formatoEntrada = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val formatoSaida = SimpleDateFormat("'Estreia dia' dd 'de' MMM", Locale("pt", "BR"))
            val data = formatoEntrada.parse(dataIngles)
            if (data != null) formatoSaida.format(data) else "Estreia em breve"
        } catch (e: Exception) {
            "Estreia em breve"
        }
    }
}
