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

class SearchActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var etQuery: EditText
    private lateinit var btnDoSearch: ImageButton
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchResultAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    
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
        
        // O PULO DO GATO: Baixa tudo agora para não travar depois
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

    // --- LÓGICA DE CARREGAMENTO (FAZ O DOWNLOAD UMA VEZ SÓ) ---
    private fun carregarDadosIniciais() {
        isCarregandoDados = true
        progressBar.visibility = View.VISIBLE
        tvEmpty.text = "Carregando catálogo completo..."
        tvEmpty.visibility = View.VISIBLE
        etQuery.isEnabled = false // Trava a busca enquanto carrega

        val prefs = getSharedPreferences("vltv_prefs", MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        launch {
            try {
                // Baixa Filmes, Séries e Canais AO MESMO TEMPO (Async)
                val resultados = withContext(Dispatchers.IO) {
                    val deferredFilmes = async { buscarFilmes(username, password) }
                    val deferredSeries = async { buscarSeries(username, password) }
                    val deferredCanais = async { buscarCanais(username, password) }

                    // Junta tudo numa lista só
                    val lista1 = deferredFilmes.await()
                    val lista2 = deferredSeries.await()
                    val lista3 = deferredCanais.await()
                    
                    lista1 + lista2 + lista3
                }

                catalogoCompleto = resultados
                isCarregandoDados = false
                
                progressBar.visibility = View.GONE
                tvEmpty.visibility = View.GONE
                etQuery.isEnabled = true
                etQuery.requestFocus()
                
                // Se veio algum texto da tela anterior, já filtra agora
                val initial = intent.getStringExtra("initial_query")
                if (!initial.isNullOrBlank()) {
                    etQuery.setText(initial)
                    filtrarNaMemoria(initial)
                } else {
                    // Se não tem busca inicial, mostra sugestão ou nada
                    tvEmpty.text = "Digite para buscar..."
                    tvEmpty.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                isCarregandoDados = false
                progressBar.visibility = View.GONE
                tvEmpty.text = "Erro ao carregar dados. Tente novamente."
                tvEmpty.visibility = View.VISIBLE
                etQuery.isEnabled = true
            }
        }
    }

    // --- LÓGICA DE FILTRO (INSTANTÂNEA NA MEMÓRIA) ---
    private fun filtrarNaMemoria(query: String) {
        if (catalogoCompleto.isEmpty()) return

        // AJUSTE PARA BUSCA IMEDIATA COM 1 LETRA
        if (query.length < 1) {
            adapter.submitList(emptyList())
            tvEmpty.text = "Digite para buscar..."
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val qNorm = query.lowercase().trim()

        // Filtragem Super Rápida na CPU
        val resultadosFiltrados = catalogoCompleto.filter { item ->
            // Verifica se o título contém o texto digitado (ignora maiúsculas/minúsculas)
            item.title.lowercase().contains(qNorm)
        } // Limite de 100 resultados para não travar a lista visual se a busca for genérica "a"
        .take(100) 

        adapter.submitList(resultadosFiltrados)
        
        if (resultadosFiltrados.isEmpty()) {
            tvEmpty.text = "Nenhum resultado encontrado."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    // --- FUNÇÕES DE API (BAIXAM TUDO SEM FILTRO) ---
    
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

    // --- NAVEGAÇÃO ---
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
