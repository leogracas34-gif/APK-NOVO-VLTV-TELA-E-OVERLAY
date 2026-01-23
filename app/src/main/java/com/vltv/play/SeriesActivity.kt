package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.request.target.Target
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SeriesActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvSeries: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView

    private var username = ""
    private var password = ""

    // Cache em memória
    private var cachedCategories: List<LiveCategory>? = null
    private val seriesCache = mutableMapOf<String, List<SeriesStream>>() // key = categoryId
    private var favSeriesCache: List<SeriesStream>? = null

    private var categoryAdapter: SeriesCategoryAdapter? = null
    private var seriesAdapter: SeriesAdapter? = null

    // ✅ FUNÇÃO PARA DETECTAR SE É TV BOX OU CELULAR
    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvSeries = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvSeries.layoutManager = GridLayoutManager(this, 5)
        rvSeries.isFocusable = true
        rvSeries.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvSeries.setHasFixedSize(true)

        carregarCategorias()
    }

    // -------- helper p/ detectar adulto --------
    private fun isAdultName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("+18") ||
                n.contains("adult") ||
                n.contains("xxx") ||
                n.contains("hot") ||
                n.contains("sexo")
    }

    private fun carregarCategorias() {
        cachedCategories?.let { categoriasCacheadas ->
            aplicarCategorias(categoriasCacheadas)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getSeriesCategories(username, password)
            .enqueue(object : Callback<List<LiveCategory>> {
                override fun onResponse(
                    call: Call<List<LiveCategory>>,
                    response: Response<List<LiveCategory>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val originais = response.body()!!

                        var categorias = mutableListOf<LiveCategory>()
                        categorias.add(
                            LiveCategory(
                                category_id = "FAV_SERIES",
                                category_name = "FAVORITOS"
                            )
                        )
                        categorias.addAll(originais)

                        cachedCategories = categorias

                        if (ParentalControlManager.isEnabled(this@SeriesActivity)) {
                            categorias = categorias.filterNot { cat ->
                                isAdultName(cat.name)
                            }.toMutableList()
                        }

                        aplicarCategorias(categorias)
                    } else {
                        Toast.makeText(this@SeriesActivity, "Erro ao carregar categorias", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@SeriesActivity, "Falha de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        if (categorias.isEmpty()) {
            Toast.makeText(this, "Nenhuma categoria disponível.", Toast.LENGTH_SHORT).show()
            rvCategories.adapter = SeriesCategoryAdapter(emptyList()) {}
            rvSeries.adapter = SeriesAdapter(emptyList()) {}
            return
        }

        categoryAdapter = SeriesCategoryAdapter(categorias) { categoria ->
            if (categoria.id == "FAV_SERIES") {
                carregarSeriesFavoritas()
            } else {
                carregarSeries(categoria)
            }
        }
        rvCategories.adapter = categoryAdapter

        val primeiraCategoriaNormal = categorias.firstOrNull { it.id != "FAV_SERIES" }
        if (primeiraCategoriaNormal != null) {
            carregarSeries(primeiraCategoriaNormal)
        } else {
            tvCategoryTitle.text = "FAVORITOS"
            carregarSeriesFavoritas()
        }
    }

    private fun carregarSeries(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name

        seriesCache[categoria.id]?.let { seriesCacheadas ->
            aplicarSeries(seriesCacheadas)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getSeries(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(
                    call: Call<List<SeriesStream>>,
                    response: Response<List<SeriesStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var series = response.body()!!

                        seriesCache[categoria.id] = series

                        if (ParentalControlManager.isEnabled(this@SeriesActivity)) {
                            series = series.filterNot { s ->
                                isAdultName(s.name)
                            }
                        }

                        aplicarSeries(series)
                    }
                }

                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun carregarSeriesFavoritas() {
        tvCategoryTitle.text = "FAVORITOS"

        favSeriesCache?.let { cacheadas ->
            aplicarSeries(cacheadas)
            return
        }

        progressBar.visibility = View.VISIBLE

        val favIds = getFavSeries(this)
        if (favIds.isEmpty()) {
            progressBar.visibility = View.GONE
            rvSeries.adapter = SeriesAdapter(emptyList()) {}
            Toast.makeText(this, "Nenhuma série favorita.", Toast.LENGTH_SHORT).show()
            return
        }

        XtreamApi.service.getSeries(username, password, categoryId = "0")
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(
                    call: Call<List<SeriesStream>>,
                    response: Response<List<SeriesStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var todas = response.body()!!
                        todas = todas.filter { favIds.contains(it.id) }

                        if (ParentalControlManager.isEnabled(this@SeriesActivity)) {
                            todas = todas.filterNot { s ->
                                isAdultName(s.name)
                            }
                        }

                        favSeriesCache = todas
                        aplicarSeries(todas)
                    }
                }

                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun aplicarSeries(series: List<SeriesStream>) {
        seriesAdapter = SeriesAdapter(series) { serie ->
            abrirDetalhesSerie(serie)
        }
        rvSeries.adapter = seriesAdapter
    }

    private fun abrirDetalhesSerie(serie: SeriesStream) {
        val intent = Intent(this@SeriesActivity, SeriesDetailsActivity::class.java)
        intent.putExtra("series_id", serie.id)
        intent.putExtra("name", serie.name)
        intent.putExtra("icon", serie.icon)
        intent.putExtra("rating", serie.rating ?: "0.0")
        startActivity(intent)
    }

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fav_series", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    // ================= ADAPTERS =================

    inner class SeriesCategoryAdapter(
        private val list: List<LiveCategory>,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.VH>() {

        private var selectedPos = 0

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            if (selectedPos == position) {
                holder.tvName.setTextColor(
                    holder.itemView.context.getColor(R.color.red_primary)
                )
                holder.tvName.setBackgroundColor(0xFF252525.toInt())
            } else {
                holder.tvName.setTextColor(
                    holder.itemView.context.getColor(R.color.gray_text)
                )
                holder.tvName.setBackgroundColor(0x00000000)
            }

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.red_primary))
                    holder.tvName.setBackgroundColor(0xFF252525.toInt())
                } else {
                    if (selectedPos != holder.adapterPosition) {
                        holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.gray_text))
                        holder.tvName.setBackgroundColor(0x00000000)
                    }
                }
            }

            holder.itemView.setOnClickListener {
                notifyItemChanged(selectedPos)
                selectedPos = holder.adapterPosition
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    inner class SeriesAdapter(
        private val list: List<SeriesStream>,
        private val onClick: (SeriesStream) -> Unit
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vod, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            // ✅ LÓGICA DE CAPAS INTELIGENTES (CELULAR VS TV BOX)
            val context = holder.itemView.context
            if (isTelevision(context)) {
                // Modo TV Box: Otimizado para economizar RAM
                Glide.with(context)
                    .load(item.icon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(200, 300) 
                    .thumbnail(0.1f) 
                    .priority(Priority.HIGH)
                    .placeholder(R.drawable.bg_logo_placeholder)
                    .error(R.drawable.bg_logo_placeholder)
                    .centerCrop()
                    .into(holder.imgPoster)
            } else {
                // Modo Celular: Alta qualidade direta
                Glide.with(context)
                    .load(item.icon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(Target.SIZE_ORIGINAL) 
                    .priority(Priority.IMMEDIATE)
                    .placeholder(R.drawable.bg_logo_placeholder)
                    .error(R.drawable.bg_logo_placeholder)
                    .centerCrop()
                    .into(holder.imgPoster)
            }

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    holder.tvName.setTextColor(0xFF00C6FF.toInt()) 
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    holder.tvName.setTextColor(0xFFFFFFFF.toInt())
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }
}
