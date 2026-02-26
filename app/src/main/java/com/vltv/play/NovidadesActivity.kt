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
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovidadesActivity : AppCompatActivity() {

    private lateinit var tabEmBreve: TextView
    private lateinit var tabTodoMundo: TextView
    private lateinit var tabTopSeries: TextView
    private lateinit var tabTopFilmes: TextView
    private lateinit var recyclerNovidades: RecyclerView
    private lateinit var bottomNavigation: BottomNavigationView

    private lateinit var adapter: NovidadesAdapter

    private var listaEmBreve = mutableListOf<NovidadeItem>()
    private var listaTodoMundo = mutableListOf<NovidadeItem>()
    private var listaTopSeries = mutableListOf<NovidadeItem>()
    private var listaTopFilmes = mutableListOf<NovidadeItem>()

    private val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
    private val client = OkHttpClient()
    private var currentProfile: String = "Padrao"
    private val database by lazy { AppDatabase.getDatabase(this) }

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
        tabTodoMundo = findViewById(R.id.tabBombando)
        tabTopSeries = findViewById(R.id.tabTopSeries)
        tabTopFilmes = findViewById(R.id.tabTopFilmes)
        recyclerNovidades = findViewById(R.id.recyclerNovidades)
        bottomNavigation = findViewById(R.id.bottomNavigation)
    }

    private fun configurarRodape() {
        bottomNavigation.selectedItemId = R.id.nav_novidades
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) })
                    finish()
                    true
                }
                R.id.nav_novidades -> true
                R.id.nav_profile -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) })
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
        tabEmBreve.setOnClickListener { ativarAba(tabEmBreve); adapter.atualizarLista(listaEmBreve); recyclerNovidades.scrollToPosition(0) }
        tabTodoMundo.setOnClickListener { ativarAba(tabTodoMundo); adapter.atualizarLista(listaTodoMundo); recyclerNovidades.scrollToPosition(0) }
        tabTopSeries.setOnClickListener { ativarAba(tabTopSeries); adapter.atualizarLista(listaTopSeries); recyclerNovidades.scrollToPosition(0) }
        tabTopFilmes.setOnClickListener { ativarAba(tabTopFilmes); adapter.atualizarLista(listaTopFilmes); recyclerNovidades.scrollToPosition(0) }
    }

    private fun ativarAba(abaAtiva: TextView) {
        val todasAsAbas = listOf(tabEmBreve, tabTodoMundo, tabTopSeries, tabTopFilmes)
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
        val dataHoje = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) 
        
        // 1. Em Breve
        val urlEmBreve = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&region=BR&with_release_type=2|3&primary_release_date.gte=$dataHoje&sort_by=primary_release_date.asc"
        buscarDadosNaApi(urlEmBreve, listaEmBreve, isTop10 = false, tagFixa = "Estreia em Breve", isEmBreve = true, isSerie = false) {
            runOnUiThread { adapter.atualizarLista(listaEmBreve) }
        }

        // 2. Todo Mundo Assistindo (Ajustado para Trending para não repetir o Top 10)
        val urlTodoMundo = "https://api.themoviedb.org/3/trending/movie/week?api_key=$apiKey&language=pt-BR"
        buscarDadosNaApi(urlTodoMundo, listaTodoMundo, isTop10 = false, tagFixa = "Bombando no Mundo", isEmBreve = false, isSerie = false) {}

        // 3. Top 10 Séries
        val urlTopSeries = "https://api.themoviedb.org/3/tv/popular?api_key=$apiKey&language=pt-BR&page=1"
        buscarDadosNaApi(urlTopSeries, listaTopSeries, isTop10 = true, tagFixa = "Top 10 Séries", isEmBreve = false, isSerie = true) {}

        // 4. Top 10 Filmes
        val urlTopFilmes = "https://api.themoviedb.org/3/movie/popular?api_key=$apiKey&language=pt-BR&page=1"
        buscarDadosNaApi(urlTopFilmes, listaTopFilmes, isTop10 = true, tagFixa = "Top 10 Filmes", isEmBreve = false, isSerie = false) {}
    }

    private fun buscarDadosNaApi(
        url: String, 
        listaDestino: MutableList<NovidadeItem>, 
        isTop10: Boolean, 
        tagFixa: String, 
        isEmBreve: Boolean,
        isSerie: Boolean,
        onSucesso: () -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val results = JSONObject(body).optJSONArray("results") ?: return
                    CoroutineScope(Dispatchers.IO).launch {
                        val tempLista = mutableListOf<NovidadeItem>()
                        var posicaoCount = 1
                        
                        // Busca o catálogo completo uma vez por aba para comparar
                        val catalogoSeries = if (isSerie && !isEmBreve) database.streamDao().getAllSeries() else emptyList()
                        val catalogoVods = if (!isSerie && !isEmBreve) database.streamDao().getAllVods() else emptyList()

                        for (i in 0 until results.length()) {
                            if (tempLista.size >= (if (isTop10) 10 else 20)) break

                            val itemJson = results.getJSONObject(i)
                            val tituloOrig = itemJson.optString("title", itemJson.optString("name", "Sem Título"))
                            
                            var idServidorValido = 0
                            
                            if (!isEmBreve) {
                                val nomeParaBusca = tituloOrig.lowercase().trim()
                                
                                if (isSerie) {
                                    val serieLocal = catalogoSeries.find { it.name.lowercase().contains(nomeParaBusca) }
                                    if (serieLocal != null) idServidorValido = serieLocal.series_id
                                } else {
                                    val filmeLocal = catalogoVods.find { it.name.lowercase().contains(nomeParaBusca) }
                                    if (filmeLocal != null) idServidorValido = filmeLocal.stream_id
                                }
                                
                                if (idServidorValido == 0) continue
                            }

                            val pathImagem = if (isEmBreve) itemJson.optString("poster_path", "") 
                                             else itemJson.optString("backdrop_path", "")
                            
                            if (pathImagem.isEmpty()) continue
                                
                            val releaseDate = itemJson.optString("release_date", itemJson.optString("first_air_date", ""))
                            val tagFinal = if (isEmBreve && releaseDate.isNotEmpty()) formatarData(releaseDate) else tagFixa

                            tempLista.add(NovidadeItem(
                                idTMDB = itemJson.optInt("id"),
                                stream_id = if (!isSerie) idServidorValido else 0,
                                series_id = if (isSerie) idServidorValido else 0,
                                titulo = tituloOrig, 
                                sinopse = itemJson.optString("overview", "Descrição indisponível."), 
                                imagemFundoUrl = "https://image.tmdb.org/t/p/w780$pathImagem", 
                                tagline = tagFinal, 
                                isTop10 = isTop10, 
                                posicaoTop10 = posicaoCount++, 
                                isEmBreve = isEmBreve, 
                                isSerie = isSerie
                            ))
                        }
                        withContext(Dispatchers.Main) {
                            listaDestino.clear()
                            listaDestino.addAll(tempLista)
                            onSucesso()
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun formatarData(dataIngles: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dataIngles)
            if (date != null) SimpleDateFormat("'Estreia dia' dd 'de' MMM", Locale("pt", "BR")).format(date) else "Estreia em breve"
        } catch (e: Exception) { "Estreia em breve" }
    }
}
