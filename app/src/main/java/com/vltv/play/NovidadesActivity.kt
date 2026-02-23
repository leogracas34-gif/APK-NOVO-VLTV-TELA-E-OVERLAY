package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
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
                    finish() // Volta pra Home
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
        adapter = NovidadesAdapter(emptyList(), this)
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

    // ==========================================
    // LÓGICA DE DATAS E API (ATUALIZADO PARA 2026)
    // ==========================================

    private fun carregarTodasAsListasTMDb() {
        Toast.makeText(this, "Atualizando lançamentos...", Toast.LENGTH_SHORT).show()

        // 1. O SEGREDO DO "EM BREVE": Pegar a data exata de hoje e forçar a ordem crescente!
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dataHoje = sdf.format(Date()) 
        
        // URL força a busca de filmes com data maior ou igual a hoje, em ordem crescente de calendário.
        val urlEmBreve = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=pt-BR&region=BR&with_release_type=2|3&primary_release_date.gte=$dataHoje&sort_by=primary_release_date.asc"
        buscarDadosNaApi(urlEmBreve, listaEmBreve, isTop10 = false, tagFixa = "Estreia em Breve", isEmBreve = true) {
            runOnUiThread { adapter.atualizarLista(listaEmBreve) }
        }

        // 2. Bombando, Top Séries e Top Filmes (Filtro para ignorar filmes muito velhos)
        val dataRecente = "2024-01-01" // Bloqueia coisas mais antigas que 2024
        
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
                        
                        // Pula se não tiver imagem (evita buraco na tela)
                        if (backdropPath.isEmpty()) continue
                        
                        val imagemUrl = "https://image.tmdb.org/t/p/w780$backdropPath"
                        
                        var dataEstreia = tagFixa
                        val releaseDate = itemJson.optString("release_date", itemJson.optString("first_air_date", ""))
                        if (releaseDate.isNotEmpty() && isEmBreve) {
                            dataEstreia = formatarData(releaseDate)
                        }

                        listaDestino.add(
                            NovidadeItem(
                                id = id, titulo = titulo, sinopse = sinopse, 
                                imagemFundoUrl = imagemUrl, tagline = dataEstreia, 
                                isTop10 = isTop10, posicaoTop10 = i + 1, isEmBreve = isEmBreve
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

    // ==========================================
    // CLASSES E ADAPTER
    // ==========================================

    data class NovidadeItem(
        val id: Int,
        val titulo: String,
        val sinopse: String,
        val imagemFundoUrl: String,
        val tagline: String,
        val isTop10: Boolean,
        val posicaoTop10: Int,
        val isEmBreve: Boolean
    )

    inner class NovidadesAdapter(
        private var lista: List<NovidadeItem>,
        private val context: Context
    ) : RecyclerView.Adapter<NovidadesAdapter.VH>() {

        fun atualizarLista(novaLista: List<NovidadeItem>) {
            lista = novaLista
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val imgFundo: ImageView = v.findViewById(R.id.imgFundoNovidade)
            val btnPlayTrailer: ImageView = v.findViewById(R.id.btnPlayTrailer)
            val tvTitulo: TextView = v.findViewById(R.id.tvTituloNovidade)
            val tvTagline: TextView = v.findViewById(R.id.tvTagline)
            val tvSinopse: TextView = v.findViewById(R.id.tvSinopseNovidade)
            
            // Números Sólidos
            val tvNumeroTop10: TextView = v.findViewById(R.id.tvNumeroTop10)
            
            // Botões de Ação
            val containerBotoesAtivos: LinearLayout = v.findViewById(R.id.containerBotoesAtivos)
            val btnAssistir: LinearLayout = v.findViewById(R.id.btnAssistirNovidade)
            val btnMinhaLista: LinearLayout = v.findViewById(R.id.btnMinhaListaNovidade)
            
            // Botão Em Breve
            val containerBotaoAviso: LinearLayout = v.findViewById(R.id.containerBotaoAviso)
            val btnReceberAviso: LinearLayout = v.findViewById(R.id.btnReceberAviso)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = lista[position]

            holder.tvTitulo.text = item.titulo
            holder.tvSinopse.text = item.sinopse
            holder.tvTagline.text = item.tagline

            // Arredondando a capa
            Glide.with(context)
                .load(item.imagemFundoUrl)
                .apply(RequestOptions.bitmapTransform(RoundedCorners(24)))
                .into(holder.imgFundo)

            // Lógica do Número Sólido Top 10
            if (item.isTop10) {
                holder.tvNumeroTop10.visibility = View.VISIBLE
                holder.tvNumeroTop10.text = String.format("%02d", item.posicaoTop10)
            } else {
                holder.tvNumeroTop10.visibility = View.GONE
            }

            // Lógica dos Botões (Troca "Assistir" por "Receber Aviso")
            if (item.isEmBreve) {
                holder.containerBotoesAtivos.visibility = View.GONE
                holder.containerBotaoAviso.visibility = View.VISIBLE
            } else {
                holder.containerBotoesAtivos.visibility = View.VISIBLE
                holder.containerBotaoAviso.visibility = View.GONE
            }

            // Cliques dos botões
            holder.btnReceberAviso.setOnClickListener {
                Toast.makeText(context, "Lembrete criado! Avisaremos quando chegar no servidor.", Toast.LENGTH_LONG).show()
            }

            holder.btnAssistir.setOnClickListener {
                Toast.makeText(context, "Abrindo detalhes...", Toast.LENGTH_SHORT).show()
                // Aqui você pode colocar o Intent para a DetailsActivity depois
            }

            // Clique do botão redondo do Trailer na imagem
            holder.btnPlayTrailer.setOnClickListener {
                Toast.makeText(context, "Buscando trailer de ${item.titulo}...", Toast.LENGTH_SHORT).show()
            }
        }

        override fun getItemCount() = lista.size
    }
}
