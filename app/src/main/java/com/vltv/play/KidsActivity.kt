package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// --- IMPORTAÇÕES PARA SUPORTE AOS 6 DNS ---
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import com.google.android.material.bottomnavigation.BottomNavigationView

class KidsActivity : AppCompatActivity() {
    private lateinit var rvHubChannels: RecyclerView
    private lateinit var rvRecentKids: RecyclerView
    private lateinit var rvMoviesKids: RecyclerView
    private lateinit var rvSeriesKids: RecyclerView
    private lateinit var tvTitleRecent: TextView
    private lateinit var etSearchKids: EditText
    private lateinit var prefs: SharedPreferences
    private var user = ""
    private var pass = ""

    private val termosProibidos = listOf(
        "adulto", "xxx", "sexo", "sexy", "porn", "18+", "erótico", "violência", 
        "007", "terror", "horror", "assassinato", "guerra", "pânico", "morte"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- ALTERAÇÃO: GARANTE QUE OS BOTÕES DO APARELHO FIQUEM FIXOS ---
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false 
        // Removidas as linhas que ocultavam as systemBars (navigationBars)

        setContentView(R.layout.activity_kids)

        prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        user = prefs.getString("username", "") ?: ""
        pass = prefs.getString("password", "") ?: ""

        tvTitleRecent = findViewById(R.id.tvTitleRecent)
        rvHubChannels = findViewById(R.id.rvHubChannels)
        rvRecentKids = findViewById(R.id.rvRecentKids)
        rvMoviesKids = findViewById(R.id.rvMoviesKids)
        rvSeriesKids = findViewById(R.id.rvSeriesKids)
        etSearchKids = findViewById(R.id.etSearchKids)

        findViewById<TextView>(R.id.btnBackKids).let {
            configurarFoco(it)
            it.setOnClickListener { finish() }
        }

        configurarFoco(etSearchKids)
        etSearchKids.isFocusableInTouchMode = true 
        etSearchKids.setOnClickListener {
            etSearchKids.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearchKids, InputMethodManager.SHOW_FORCED)
        }

        etSearchKids.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val query = v.text.toString().trim()
                val contemProibido = termosProibidos.any { query.contains(it, ignoreCase = true) }
                
                if (query.isNotEmpty() && !contemProibido) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    etSearchKids.clearFocus()
                    val intent = Intent(this, SearchActivity::class.java).apply {
                        putExtra("query", query)
                        putExtra("search_text", query)
                        putExtra("initial_query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else if (contemProibido) {
                    Toast.makeText(this, "Busca bloqueada na Área Kids 🛡️", Toast.LENGTH_LONG).show()
                    etSearchKids.setText("")
                }
                true
            } else false
        }

        setupLayouts()
        setupHubChannels()
        carregarConteudoKids()
        setupBottomNavigation() // Chamada para configurar o rodapé
    }

    // --- NOVA FUNÇÃO: CONFIGURAÇÃO DO RODAPÉ ---
    private fun setupBottomNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        nav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    finish()
                    true
                }
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    startActivity(intent)
                    false 
                }
                R.id.nav_novidades -> {
                    val intent = Intent(this, NovidadesActivity::class.java)
                    startActivity(intent)
                    false
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                    false
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Mantendo os botões fixos também no onResume
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        etSearchKids.setText("")
        etSearchKids.clearFocus()
        atualizarRecentesVisual()
    }

    private fun configurarFoco(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                v.setBackgroundResource(R.drawable.bg_selector_kids)
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                v.background = null
            }
        }
    }

    private fun setupLayouts() {
        rvHubChannels.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvRecentKids.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvMoviesKids.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvSeriesKids.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
    }

    private fun setupHubChannels() {
        val nomesDesejados = listOf("Cartoon Network", "Discovery Kids", "Gloob", "Cartoonito", "Nickelodeon")
        XtreamApi.service.getLiveStreams(user, pass, categoryId = "0").enqueue(object : Callback<List<LiveStream>> {
            override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                if (response.isSuccessful && response.body() != null) {
                    val todosCanais = response.body()!!
                    val listaHub = mutableListOf<LiveStream>()
                    nomesDesejados.forEach { nomeBusca ->
                        todosCanais.firstOrNull { it.name.contains(nomeBusca, ignoreCase = true) }?.let {
                            listaHub.add(it)
                        }
                    }
                    rvHubChannels.adapter = HubAdapter(listaHub) { canal ->
                        val intent = Intent(this@KidsActivity, PlayerActivity::class.java).apply {
                            putExtra("stream_id", canal.id)
                            putExtra("name", canal.name)
                            putExtra("title", canal.name)
                            putExtra("type", "live")
                            putExtra("epg_channel_id", canal.epg_channel_id)
                        }
                        startActivity(intent)
                    }
                }
            }
            override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {}
        })
    }

    private fun carregarConteudoKids() {
        XtreamApi.service.getVodCategories(user, pass).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val rawJson = response.body()!!.string()
                        val listaCategorias = mutableListOf<LiveCategory>()
                        val gson = Gson()

                        if (rawJson.trim().startsWith("[")) {
                            val listType = object : TypeToken<List<LiveCategory>>() {}.type
                            listaCategorias.addAll(gson.fromJson(rawJson, listType))
                        } else if (rawJson.trim().startsWith("{")) {
                            val jsonObject = JSONObject(rawJson)
                            jsonObject.keys().forEach { key ->
                                val cat = gson.fromJson(jsonObject.getJSONObject(key).toString(), LiveCategory::class.java)
                                listaCategorias.add(cat)
                            }
                        }

                        val kidsCats = listaCategorias.filter {
                            val n = it.name.lowercase()
                            n.contains("kids") || n.contains("infantil") || n.contains("desenho") || n.contains("disney")
                        }

                        kidsCats.forEach { cat ->
                            XtreamApi.service.getVodStreams(user, pass, categoryId = cat.id).enqueue(object : Callback<List<VodStream>> {
                                override fun onResponse(call: Call<List<VodStream>>, res: Response<List<VodStream>>) {
                                    if (res.isSuccessful && res.body() != null) {
                                        val adapterExistente = rvMoviesKids.adapter as? KidsVodAdapter
                                        if (adapterExistente != null) {
                                            val listaAtual = adapterExistente.list.toMutableList()
                                            listaAtual.addAll(res.body()!!)
                                            rvMoviesKids.adapter = KidsVodAdapter(listaAtual.distinctBy { it.id }) { filme ->
                                                salvarNosRecentes(filme.id.toString(), "movie")
                                                abrirDetalhesFilme(filme)
                                            }
                                        } else {
                                            rvMoviesKids.adapter = KidsVodAdapter(res.body()!!) { filme ->
                                                salvarNosRecentes(filme.id.toString(), "movie")
                                                abrirDetalhesFilme(filme)
                                            }
                                        }
                                    }
                                }
                                override fun onFailure(call: Call<List<VodStream>>, t: Throwable) {}
                            })
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
        })

        XtreamApi.service.getSeriesCategories(user, pass).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val rawJson = response.body()!!.string()
                        val listaCategorias = mutableListOf<LiveCategory>()
                        val gson = Gson()

                        if (rawJson.trim().startsWith("[")) {
                            val listType = object : TypeToken<List<LiveCategory>>() {}.type
                            listaCategorias.addAll(gson.fromJson(rawJson, listType))
                        } else if (rawJson.trim().startsWith("{")) {
                            val jsonObject = JSONObject(rawJson)
                            jsonObject.keys().forEach { key ->
                                val cat = gson.fromJson(jsonObject.getJSONObject(key).toString(), LiveCategory::class.java)
                                listaCategorias.add(cat)
                            }
                        }

                        val kidsSeriesCats = listaCategorias.filter {
                            val n = it.name.lowercase()
                            n.contains("kids") || n.contains("infantil") || n.contains("desenho") || n.contains("disney")
                        }

                        kidsSeriesCats.forEach { cat ->
                            XtreamApi.service.getSeries(user, pass, categoryId = cat.id).enqueue(object : Callback<List<SeriesStream>> {
                                override fun onResponse(call: Call<List<SeriesStream>>, res: Response<List<SeriesStream>>) {
                                    if (res.isSuccessful && res.body() != null) {
                                        val adapterExistente = rvSeriesKids.adapter as? KidsSeriesAdapter
                                        if (adapterExistente != null) {
                                            val listaAtual = adapterExistente.list.toMutableList()
                                            listaAtual.addAll(res.body()!!)
                                            rvSeriesKids.adapter = KidsSeriesAdapter(listaAtual.distinctBy { it.id }) { serie ->
                                                salvarNosRecentes(serie.id.toString(), "series")
                                                abrirDetalhesSerie(serie)
                                            }
                                        } else {
                                            rvSeriesKids.adapter = KidsSeriesAdapter(res.body()!!) { serie ->
                                                salvarNosRecentes(serie.id.toString(), "series")
                                                abrirDetalhesSerie(serie)
                                            }
                                        }
                                    }
                                }
                                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {}
                            })
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
        })
    }

    private fun salvarNosRecentes(id: String, tipo: String) {
        val key = if (tipo == "movie") "kids_recent_vod" else "kids_recent_series"
        val atuais = prefs.getStringSet(key, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        atuais.remove(id)
        atuais.add(id)
        prefs.edit().putStringSet(key, atuais.take(10).toSet()).apply()
    }

    private fun atualizarRecentesVisual() {
        val recentVodIds = prefs.getStringSet("kids_recent_vod", emptySet()) ?: emptySet()
        val recentSeriesIds = prefs.getStringSet("kids_recent_series", emptySet()) ?: emptySet()
        if (recentVodIds.isNotEmpty() || recentSeriesIds.isNotEmpty()) {
            val listaRecentesUnificada = mutableListOf<KidsRecentItem>()
            if (recentVodIds.isNotEmpty()) {
                XtreamApi.service.getAllVodStreams(user, pass).enqueue(object : Callback<List<VodStream>> {
                    override fun onResponse(call: Call<List<VodStream>>, response: Response<List<VodStream>>) {
                        if (response.isSuccessful && response.body() != null) {
                            response.body()!!.filter { recentVodIds.contains(it.id.toString()) }.forEach {
                                listaRecentesUnificada.add(KidsRecentItem(it.id.toString(), it.name ?: "Filme", it.icon ?: "", "movie", it, null))
                            }
                            exibirRecentes(listaRecentesUnificada)
                        }
                    }
                    override fun onFailure(call: Call<List<VodStream>>, t: Throwable) {}
                })
            }
            if (recentSeriesIds.isNotEmpty()) {
                XtreamApi.service.getAllSeries(user, pass).enqueue(object : Callback<List<SeriesStream>> {
                    override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                        if (response.isSuccessful && response.body() != null) {
                            response.body()!!.filter { recentSeriesIds.contains(it.id.toString()) }.forEach {
                                listaRecentesUnificada.add(KidsRecentItem(it.id.toString(), it.name ?: "Série", it.icon ?: "", "series", null, it))
                            }
                            exibirRecentes(listaRecentesUnificada)
                        }
                    }
                    override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {}
                })
            }
        }
    }

    private fun exibirRecentes(itens: List<KidsRecentItem>) {
        val listaFinal = itens.distinctBy { it.id }.reversed()
        if (listaFinal.isNotEmpty()) {
            tvTitleRecent.visibility = View.VISIBLE
            rvRecentKids.visibility = View.VISIBLE
            rvRecentKids.adapter = KidsUnifiedAdapter(listaFinal) { item ->
                if (item.tipo == "movie" && item.filmeObj != null) abrirDetalhesFilme(item.filmeObj)
                else if (item.tipo == "series" && item.serieObj != null) abrirDetalhesSerie(item.serieObj)
            }
        }
    }

    private fun abrirDetalhesFilme(filme: VodStream) {
        val intent = Intent(this, DetailsActivity::class.java).apply {
            putExtra("stream_id", filme.id)
            putExtra("stream_ext", filme.extension ?: "mp4")
            putExtra("name", filme.name)
            putExtra("icon", filme.icon)
            putExtra("rating", filme.rating ?: "0.0")
        }
        startActivity(intent)
    }

    private fun abrirDetalhesSerie(serie: SeriesStream) {
        val intent = Intent(this, SeriesDetailsActivity::class.java).apply {
            putExtra("series_id", serie.id)
            putExtra("name", serie.name)
            putExtra("icon", serie.icon)
        }
        startActivity(intent)
    }

    data class KidsRecentItem(val id: String, val nome: String, val capa: String, val tipo: String, val filmeObj: VodStream?, val serieObj: SeriesStream?)

    inner class HubAdapter(val list: List<LiveStream>, val onClick: (LiveStream) -> Unit) : RecyclerView.Adapter<HubAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgLogoHub)
            val txt: TextView = v.findViewById(R.id.tvNameHub)
            val container: LinearLayout = v.findViewById(R.id.containerHub)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hub_kids, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val nomeUpper = item.name.uppercase()
            holder.txt.text = nomeUpper

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .fitCenter() 
                .into(holder.img)

            val corFundo = when {
                nomeUpper.contains("CARTOON") -> "#000000"
                nomeUpper.contains("DISCOVERY") -> "#00AEEF"
                nomeUpper.contains("NICK") -> "#FF6600"
                nomeUpper.contains("GLOOB") -> "#E30613"
                nomeUpper.contains("DISNEY") -> "#FF007F"
                else -> "#4A148C"
            }
            holder.container.setBackgroundColor(Color.parseColor(corFundo))

            configurarFoco(holder.itemView)
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size
    }

    inner class KidsUnifiedAdapter(val list: List<KidsRecentItem>, val onClick: (KidsRecentItem) -> Unit) : RecyclerView.Adapter<KidsUnifiedAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgPoster)
            val txt: TextView = v.findViewById(R.id.tvName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txt.text = item.nome
            Glide.with(holder.itemView.context).load(item.capa).diskCacheStrategy(DiskCacheStrategy.ALL).override(200, 300).centerCrop().into(holder.img)
            configurarFoco(holder.itemView)
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size
    }

    inner class KidsVodAdapter(val list: List<VodStream>, val onClick: (VodStream) -> Unit) : RecyclerView.Adapter<KidsVodAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgPoster)
            val txt: TextView = v.findViewById(R.id.tvName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txt.text = item.name
            Glide.with(holder.itemView.context).load(item.icon).diskCacheStrategy(DiskCacheStrategy.ALL).override(200, 300).centerCrop().into(holder.img)
            configurarFoco(holder.itemView)
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size
    }

    inner class KidsSeriesAdapter(val list: List<SeriesStream>, val onClick: (SeriesStream) -> Unit) : RecyclerView.Adapter<KidsSeriesAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgPoster)
            val txt: TextView = v.findViewById(R.id.tvName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txt.text = item.name
            Glide.with(holder.itemView.context).load(item.icon).diskCacheStrategy(DiskCacheStrategy.ALL).override(200, 300).centerCrop().into(holder.img)
            configurarFoco(holder.itemView)
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size
    }
}
