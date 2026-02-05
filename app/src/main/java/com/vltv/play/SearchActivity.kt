package com.vltv.play

import android.content.Intent
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

// ✅ IMPORTAÇÃO DA DATABASE PARA BUSCA INSTANTÂNEA
import com.vltv.play.data.AppDatabase

class SearchActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var etQuery: EditText
    private lateinit var btnDoSearch: ImageButton
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchResultAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    
    // ✅ DATABASE INICIALIZADA
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Variáveis da Busca Otimizada
    private val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + supervisor

    // LISTA MESTRA: Guarda tudo na memória para busca instantânea
    private var catalogoCompleto: List<SearchResultItem> = emptyList()
    private var isCarregandoDados = false
    private var jobBuscaInstantanea: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        // Configuração de Tela Cheia / Barras
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        initViews()
        setupRecyclerView()
        setupSearchLogic()
        
        // O PULO DO GATO: Carrega da Database ou API
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

        // 5 Colunas (Grade)
        rvResults.layoutManager = GridLayoutManager(this, 5)
        rvResults.adapter = adapter
        rvResults.isFocusable = true
        rvResults.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun setupSearchLogic() {
        // TextWatcher: Detecta cada letra digitada
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val texto = s.toString().trim()
                
                // Se ainda está baixando os dados, avisa ou espera
                if (isCarregandoDados) return 

                // Cancela busca anterior e inicia nova (Instantânea)
                jobBuscaInstantanea?.cancel()
                jobBuscaInstantanea = launch {
                    delay(100) // Pequeno delay de 0.1s só para não piscar demais
                    filtrarNaMemoria(texto)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Botão de busca (teclado ou icone) força o filtro
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

    // --- LÓGICA DE CARREGAMENTO (HÍBRIDA: DATABASE + API) ---
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
                // ✅ TENTA CARREGAR DA DATABASE PRIMEIRO (INSTANTÂNEO)
                val resultadosLocal = withContext(Dispatchers.IO) {
                    val filmes = database.streamDao().getRecentMovies(2000).map {
                        SearchResultItem(it.stream_id, it.name ?: "", "movie", it.rating, it.container_extension)
                    }
                    val series = database.streamDao().getRecentSeries(2000).map {
                        SearchResultItem(it.series_id, it.name ?: "", "series", it.rating, it.cover)
                    }
                    filmes + series
                }

                if (resultadosLocal.isNotEmpty()) {
                    catalogoCompleto = resultadosLocal
                    finalizarCarregamento()
                }

                // BUSCA NA API EM SEGUNDO PLANO PARA ATUALIZAR CASO ESTEJA VAZIO OU DESATUALIZADO
                val resultadosAPI = withContext(Dispatchers.IO) {
                    val deferredFilmes = async { buscarFilmes(username, password) }
                    val deferredSeries = async { buscarSeries(username, password) }
                    val deferredCanais = async { buscarCanais(username, password) }

                    deferredFilmes.await() + deferredSeries.await() + deferredCanais.await()
                }

                if (resultadosAPI.isNotEmpty()) {
                    catalogoCompleto = resultadosAPI
                    finalizarCarregamento()
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

    private fun finalizarCarregamento() {
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

    // --- LÓGICA DE FILTRO (INSTANTÂNEA NA MEMÓRIA) ---
    private fun filtrarNaMemoria(query: String) {
        if (catalogoCompleto.isEmpty() && !isCarregandoDados) {
            tvEmpty.text = "Nenhum dado carregado."
            tvEmpty.visibility = View.VISIBLE
            return
        }

        if (query.length < 1) {
            adapter.submitList(emptyList())
            tvEmpty.text = "Digite para buscar..."
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val qNorm = query.lowercase().trim()

        val resultadosFiltrados = catalogoCompleto.filter { item ->
            item.title.lowercase().contains(qNorm)
        }.take(100) 

        adapter.submitList(resultadosFiltrados)
        
        if (resultadosFiltrados.isEmpty()) {
            tvEmpty.text = "Nenhum resultado encontrado."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    // --- FUNÇÕES DE API MANTIDAS INTEGRALMENTE ---
    
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

    // --- NAVEGAÇÃO MANTIDA ---
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
