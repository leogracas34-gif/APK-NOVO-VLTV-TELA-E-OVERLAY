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
    private lateinit var tabTodoMundo: TextView // Nome corrigido conforme combinado
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
        tabTodoMundo = findViewById(R.id.tabBombando) // Mantive o ID original do XML para não dar erro
        tabTopSeries = findViewById(R.id.tabTopSeries)
        tabTopFilmes = findViewById(R.id.tabTopFilmes)
        recyclerNovidades = findViewById(R.id.recyclerNovidades)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        
        // Texto visual da aba corrigido
        tabTodoMundo.text = "Todo mundo está assistindo"
    }

    private fun configurarRodape() {
        bottomNavigation.selectedItemId = R.id.nav_novidades
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
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
        tabTodoMundo.setOnClickListener {
            ativarAba(tabTodoMundo)
            adapter.atualizarLista(listaTodoMundo)
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
        Toast.makeText(this, "Sincronizando com o servidor...", Toast.LENGTH_SHORT).show()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dataHoje = sdf.format(Date()) 
        
        // 1. Aba Em Breve (Não sincroniza, apenas mostra estreias)
        val urlEmBreve = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&region=BR&with_release_type=2|3&primary_release_date.gte=$dataHoje&sort_by=primary_release_date.asc"
        buscarDadosNaApi(urlEmBreve, listaEmBreve, isTop10 = false, tagFixa = "Estreia em Breve", isEmBreve = true, isSerie = false) {
            runOnUiThread { adapter.atualizarLista(listaEmBreve) }
        }

        val dataRecente = "2024-01-01" 
        
        // 2. Todo mundo está assistindo (Sincronizado)
        val urlTodoMundo = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&primary_release_date.gte=$dataRecente&sort_by=popularity.desc"
        buscarDadosNaApi(urlTodoMundo, listaTodoMundo, isTop10 = false, tagFixa = "Bombando no Mundo", isEmBreve = false, isSerie = false) {}

        // 3. Top 10 Séries (Sincronizado)
        val urlTopSeries = "https://api.themoviedb.org/3/discover/tv?api_key=$apiKey&language=pt-BR&first_air_date.gte=$dataRecente&sort_by=popularity.desc"
        buscarDadosNaApi(urlTopSeries, listaTopSeries, isTop10 = true, tagFixa = "Top 10 Séries", isEmBreve = false, isSerie = true) {}

        // 4. Top 10 Filmes (Sincronizado)
        val urlTopFilmes = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&primary_release_date.gte=$dataRecente&sort_by=popularity.desc"
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
            override fun onFailure(call: Call, e: IOException) { }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val jsonObject = JSONObject(body)
                    val results = jsonObject.optJSONArray("results") ?: return
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        val tempLista = mutableListOf<NovidadeItem>()
                        
                        for (i in 0 until results.length()) {
                            if (tempLista.size >= (if (isTop10) 10 else 20)) break

                            val itemJson = results.getJSONObject(i)
                            val titulo = itemJson.optString("title", itemJson.optString("name", "Sem Título"))
                            
                            // LÓGICA DE SINCRONIZAÇÃO: Se não for "Em Breve", verifica se existe no servidor
                            var existeNoServidor = true
                            if (!isEmBreve) {
                                existeNoServidor = if (isSerie) {
                                    database.streamDao().getRecentSeries(2000).any { it.name.contains(titulo, true) }
                                } else {
                                    database.streamDao().searchVod(titulo).isNotEmpty()
                                }
                            }

                            if (existeNoServidor) {
                                val id = itemJson.optInt("id")
                                val sinopse = itemJson.optString("overview", "Descrição indisponível.")
                                val backdropPath = itemJson.optString("backdrop_path", "")
                                if (backdropPath.isEmpty()) continue
                                
                                val imagemUrl = "https://image.tmdb.org/t/p/w780$backdropPath"
                                var dataEstreia = tagFixa
                                val releaseDate = itemJson.optString("release_date", itemJson.optString("first_air_date", ""))
                                
                                if (releaseDate.isNotEmpty() && isEmBreve) {
                                    dataEstreia = formatarData(releaseDate)
                                }

                                tempLista.add(
                                    NovidadeItem(
                                        id = id, titulo = titulo, sinopse = sinopse, 
                                        imagemFundoUrl = imagemUrl, tagline = dataEstreia, 
                                        isTop10 = isTop10, posicaoTop10 = i + 1, 
                                        isEmBreve = isEmBreve, isSerie = isSerie
                                    )
                                )
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            listaDestino.clear()
                            listaDestino.addAll(tempLista)
                            onSucesso()
                        }
                    }
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
