package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

// IMPORTAÇÃO DA DATABASE E ENTIDADES
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.LiveStreamEntity

class SearchActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var etQuery: EditText
    private lateinit var btnDoSearch: ImageButton
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchResultAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    
    // DATABASE INICIALIZADA VIA LAZY
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Variáveis da Busca Otimizada
    private val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + supervisor

    // LISTA MESTRA: Guarda tudo na memória para busca instantânea
    private var catalogoCompleto: List<SearchResultItem> = emptyList()
    private var isCarregandoDados = false
    private var jobBuscaInstantanea: Job? = null
    
    // ✅ NOVA VARIÁVEL: Guarda de onde o usuário veio ("filmes", "series" ou "tudo")
    private var tipoPesquisa: String = "tudo"

    // ✅ ADICIONADO: Função para detectar se é TV ou Celular
    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        // Configuração de Tela Cheia / Barras
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Mantém o comportamento de barras dependendo da tela (opcional, igual VOD)
        if (isTelevision(this)) {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
        
        // ✅ CAPTURA A ETIQUETA ENVIADA PELA TELA ANTERIOR (Padrão é "tudo")
        tipoPesquisa = intent.getStringExtra("tipo_pesquisa") ?: "tudo"

        initViews()
        setupRecyclerView()
        setupSearchLogic()
        
        // Carregamento Híbrido: Primeiro Database, depois API
        carregarDadosIniciais()
    }

    private fun initViews() {
        etQuery = findViewById(R.id.etQuery)
        btnDoSearch = findViewById(R.id.btnDoSearch)
        rvResults = findViewById(R.id.rvResults)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)

        // AJUSTE PARA O TECLADO NÃO COBRIR A TELA
        etQuery.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    private fun setupRecyclerView() {
        adapter = SearchResultAdapter { item ->
            abrirDetalhes(item)
        }

        // ✅ CORREÇÃO APLICADA: 5 colunas se for TV, 3 colunas se for Celular
        val spanCount = if (isTelevision(this)) 5 else 3
        
        rvResults.layoutManager = GridLayoutManager(this, spanCount)
        rvResults.adapter = adapter
        rvResults.isFocusable = true
        rvResults.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun setupSearchLogic() {
        // TextWatcher: Detecta cada letra digitada
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val texto = s.toString().trim()
                
                if (isCarregandoDados) return 

                jobBuscaInstantanea?.cancel()
                jobBuscaInstantanea = launch {
                    delay(100) 
                    filtrarNaMemoria(texto)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnDoSearch.setOnClickListener { 
            filtrarNaMemoria(etQuery.text.toString().trim()) 
        }

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filtrarNaMemoria(etQuery.text.toString().trim())
                true
            } else false
        }
    }

    private fun carregarDadosIniciais() {
        isCarregandoDados = true
        progressBar.visibility = View.VISIBLE
        tvEmpty.text = "Carregando catálogo..."
        tvEmpty.visibility = View.VISIBLE
        etQuery.isEnabled = false 

        val prefs = getSharedPreferences("vltv_prefs", MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        launch {
            try {
                // TENTA CARREGAR DA DATABASE PRIMEIRO
                val resultadosLocal = withContext(Dispatchers.IO) {
                    // ✅ OTIMIZAÇÃO: Só puxa filmes do BD se a etiqueta permitir
                    val filmes = if (tipoPesquisa == "tudo" || tipoPesquisa == "filmes") {
                        database.streamDao().getRecentVods(2000).map {
                            SearchResultItem(it.stream_id, it.name ?: "", "movie", it.rating, it.stream_icon)
                        }
                    } else emptyList()

                    // ✅ OTIMIZAÇÃO: Só puxa séries do BD se a etiqueta permitir
                    val series = if (tipoPesquisa == "tudo" || tipoPesquisa == "series") {
                        database.streamDao().getRecentSeries(2000).map {
                            SearchResultItem(it.series_id, it.name ?: "", "series", it.rating, it.cover)
                        }
                    } else emptyList()
                    
                    filmes + series
                }

                if (resultadosLocal.isNotEmpty()) {
                    catalogoCompleto = resultadosLocal
                    finalizarUI()
                }

                // BUSCA NA API EM SEGUNDO PLANO
                val resultadosAPI = withContext(Dispatchers.IO) {
                    // ✅ OTIMIZAÇÃO: Só chama as rotas pesadas da API se for estritamente necessário
                    val deferredFilmes = if (tipoPesquisa == "tudo" || tipoPesquisa == "filmes") async { buscarFilmes(username, password) } else null
                    val deferredSeries = if (tipoPesquisa == "tudo" || tipoPesquisa == "series") async { buscarSeries(username, password) } else null
                    val deferredCanais = if (tipoPesquisa == "tudo") async { buscarCanais(username, password) } else null

                    val apiFilmes = deferredFilmes?.await() ?: emptyList()
                    val apiSeries = deferredSeries?.await() ?: emptyList()
                    val apiCanais = deferredCanais?.await() ?: emptyList()

                    apiFilmes + apiSeries + apiCanais
                }

                if (resultadosAPI.isNotEmpty()) {
                    catalogoCompleto = resultadosAPI
                    finalizarUI()
                }

            } catch (e: Exception) {
                isCarregandoDados = false
                progressBar.visibility = View.GONE
                tvEmpty.text = "Erro ao carregar dados."
                tvEmpty.visibility = View.VISIBLE
                etQuery.isEnabled = true
            }
        }
    }

    private fun finalizarUI() {
        isCarregandoDados = false
        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        etQuery.isEnabled = true
        etQuery.requestFocus()
        
        val initial = intent.getStringExtra("initial_query")
        if (!initial.isNullOrBlank()) {
            etQuery.setText(initial)
            filtrarNaMemoria(initial)
        } else {
            tvEmpty.text = "Digite para buscar..."
            tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun filtrarNaMemoria(query: String) {
        if (catalogoCompleto.isEmpty() && !isCarregandoDados) return

        if (query.length < 1) {
            adapter.submitList(emptyList())
            tvEmpty.text = "Digite para buscar..."
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val qNorm = query.lowercase().trim()

        val resultadosFiltrados = catalogoCompleto.filter { item ->
            // Checa se o nome bate
            val matchNome = item.title.lowercase().contains(qNorm)
            
            // ✅ Checa se o tipo do item bate com a tela em que o usuário estava
            val matchTipo = when (tipoPesquisa) {
                "filmes" -> item.type == "movie"
                "series" -> item.type == "series"
                else -> true // Se for "tudo", mostra qualquer tipo
            }
            
            matchNome && matchTipo
        }.take(100) 

        adapter.submitList(resultadosFiltrados)
        
        if (resultadosFiltrados.isEmpty()) {
            tvEmpty.text = "Nenhum resultado encontrado."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    // --- FUNÇÕES DE API MANTIDAS SEM ALTERAÇÃO ---
    
    private fun buscarFilmes(u: String, p: String): List<SearchResultItem> {
        return try {
            val response = XtreamApi.service.getAllVodStreams(user = u, pass = p).execute()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.map {
                    SearchResultItem(
                        id = it.id,
                        title = it.name ?: "Sem Título",
                        type = "movie",
                        extraInfo = it.rating,
                        iconUrl = it.icon
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun buscarSeries(u: String, p: String): List<SearchResultItem> {
        return try {
            val response = XtreamApi.service.getAllSeries(user = u, pass = p).execute()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.map {
                    SearchResultItem(
                        id = it.id,
                        title = it.name ?: "Sem Título",
                        type = "series",
                        extraInfo = it.rating,
                        iconUrl = it.icon
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun buscarCanais(u: String, p: String): List<SearchResultItem> {
        return try {
            val response = XtreamApi.service.getLiveStreams(user = u, pass = p, categoryId = "0").execute()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.map {
                    SearchResultItem(
                        id = it.id,
                        title = it.name ?: "Sem Nome",
                        type = "live",
                        extraInfo = null,
                        iconUrl = it.icon 
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun abrirDetalhes(item: SearchResultItem) {
        when (item.type) {
            "movie" -> {
                val i = Intent(this, DetailsActivity::class.java)
                i.putExtra("stream_id", item.id)
                i.putExtra("name", item.title)
                i.putExtra("icon", item.iconUrl ?: "")
                i.putExtra("rating", item.extraInfo ?: "0.0")
                startActivity(i)
            }
            "series" -> {
                val i = Intent(this, SeriesDetailsActivity::class.java)
                i.putExtra("series_id", item.id)
                i.putExtra("name", item.title)
                i.putExtra("icon", item.iconUrl ?: "")
                i.putExtra("rating", item.extraInfo ?: "0.0")
                startActivity(i)
            }
            "live" -> {
                val i = Intent(this, PlayerActivity::class.java)
                i.putExtra("stream_id", item.id)
                i.putExtra("stream_type", "live")
                i.putExtra("channel_name", item.title)
                startActivity(i)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        supervisor.cancel()
    }
}
