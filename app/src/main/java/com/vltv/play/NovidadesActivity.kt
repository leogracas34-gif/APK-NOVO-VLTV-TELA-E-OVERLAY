package com.vltv.play

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class NovidadesActivity : AppCompatActivity() {

    // Views das Abas
    private lateinit var tabEmBreve: TextView
    private lateinit var tabBombando: TextView
    private lateinit var tabTopSeries: TextView
    private lateinit var tabTopFilmes: TextView
    private lateinit var recyclerNovidades: RecyclerView

    // O nosso Adapter
    private lateinit var adapter: NovidadesAdapter

    // Listas para guardar os dados de cada aba
    private var listaEmBreve = mutableListOf<NovidadeItem>()
    private var listaBombando = mutableListOf<NovidadeItem>()
    private var listaTopSeries = mutableListOf<NovidadeItem>()
    private var listaTopFilmes = mutableListOf<NovidadeItem>()

    // Chave da API e Cliente de Internet
    private val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
    private val client = OkHttpClient()
    
    // Perfil atual (para manter o padrão do seu app)
    private var currentProfile: String = "Padrao"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novidades)

        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        inicializarViews()
        configurarRecyclerView()
        configurarCliquesDasAbas()

        // Assim que a tela abre, já busca tudo da internet
        carregarTodasAsListasTMDb()
    }

    private fun inicializarViews() {
        tabEmBreve = findViewById(R.id.tabEmBreve)
        tabBombando = findViewById(R.id.tabBombando)
        tabTopSeries = findViewById(R.id.tabTopSeries)
        tabTopFilmes = findViewById(R.id.tabTopFilmes)
        recyclerNovidades = findViewById(R.id.recyclerNovidades)
    }

    private fun configurarRecyclerView() {
        // Inicia o adapter vazio
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

    // Função que pinta a aba clicada de Branco e as outras de Cinza
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

    // ==========================================
    // BUSCAS NA API DO TMDB
    // ==========================================

    private fun carregarTodasAsListasTMDb() {
        Toast.makeText(this, "Carregando novidades...", Toast.LENGTH_SHORT).show()

        // 1. Em Breve (Filmes que vão lançar no Brasil)
        val urlEmBreve = "https://api.themoviedb.org/3/movie/upcoming?api_key=$apiKey&language=pt-BR&region=BR"
        buscarDadosNaApi(urlEmBreve, listaEmBreve, isTop10 = false, tagFixa = "Estreia em Breve") {
            // Quando terminar de baixar o "Em Breve", já mostra na tela (pois é a aba principal)
            runOnUiThread { adapter.atualizarLista(listaEmBreve) }
        }

        // 2. Bombando (Trending Mundial da Semana)
        val urlBombando = "https://api.themoviedb.org/3/trending/all/week?api_key=$apiKey&language=pt-BR"
        buscarDadosNaApi(urlBombando, listaBombando, isTop10 = false, tagFixa = "Em Alta no Mundo") {}

        // 3. Top 10 Séries (As séries mais populares do momento)
        val urlTopSeries = "https://api.themoviedb.org/3/tv/popular?api_key=$apiKey&language=pt-BR"
        buscarDadosNaApi(urlTopSeries, listaTopSeries, isTop10 = true, tagFixa = "Top 10 Séries") {}

        // 4. Top 10 Filmes (Os filmes mais populares do momento)
        val urlTopFilmes = "https://api.themoviedb.org/3/movie/popular?api_key=$apiKey&language=pt-BR"
        buscarDadosNaApi(urlTopFilmes, listaTopFilmes, isTop10 = true, tagFixa = "Top 10 Filmes") {}
    }

    // Função genérica que faz a requisição e transforma o JSON nos nossos "NovidadeItem"
    private fun buscarDadosNaApi(
        url: String, 
        listaDestino: MutableList<NovidadeItem>, 
        isTop10: Boolean, 
        tagFixa: String, 
        onSucesso: () -> Unit
    ) {
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("VLTV_NOVIDADES", "Erro ao buscar $url", e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (body != null) {
                    try {
                        val jsonObject = JSONObject(body)
                        val results = jsonObject.optJSONArray("results")
                        
                        if (results != null) {
                            // Se for Top 10, pegamos só os 10 primeiros. Se não, pegamos até 20.
                            val limite = if (isTop10) 10 else Math.min(results.length(), 20)
                            
                            for (i in 0 until limite) {
                                val itemJson = results.getJSONObject(i)
                                
                                val id = itemJson.optInt("id")
                                // TMDB usa "title" para filmes e "name" para séries
                                val titulo = itemJson.optString("title", itemJson.optString("name", "Sem Título"))
                                val sinopse = itemJson.optString("overview", "Descrição indisponível no momento.")
                                val backdropPath = itemJson.optString("backdrop_path", "")
                                val imagemUrl = "https://image.tmdb.org/t/p/w780$backdropPath"
                                
                                // Tentativa de pegar a data exata (para "Em Breve")
                                var dataEstreia = tagFixa
                                val releaseDate = itemJson.optString("release_date", itemJson.optString("first_air_date", ""))
                                if (releaseDate.isNotEmpty() && !isTop10 && tagFixa == "Estreia em Breve") {
                                    dataEstreia = formatarData(releaseDate)
                                }

                                val novidade = NovidadeItem(
                                    id = id,
                                    titulo = titulo,
                                    sinopse = sinopse,
                                    imagemFundoUrl = imagemUrl,
                                    tagline = dataEstreia,
                                    isTop10 = isTop10,
                                    posicaoTop10 = i + 1,
                                    trailerUrl = null // Por enquanto null, o botão mostrará um Toast.
                                )
                                listaDestino.add(novidade)
                            }
                            // Executa a ação de sucesso (geralmente atualizar a tela principal)
                            onSucesso()
                        }
                    } catch (e: Exception) {
                        Log.e("VLTV_NOVIDADES", "Erro ao ler JSON", e)
                    }
                }
            }
        })
    }

    // Helper para transformar "2025-11-25" em "Estreia dia 25 de Nov"
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
