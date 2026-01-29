package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.google.android.material.bottomsheet.BottomSheetDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.ArrayList
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Importante: Certifique-se de que CastAdapter e CastMember estão no projeto
import com.vltv.play.CastAdapter
import com.vltv.play.CastMember

// ✅ IMPORTAÇÕES ADICIONADAS PARA A LOGO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesDetailsActivity : AppCompatActivity() {

    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var seriesIcon: String? = null
    private var seriesRating: String = "0.0"

    // Views
    private lateinit var imgPoster: ImageView
    private lateinit var imgBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var imgTitleLogo: ImageView // ✅ ADICIONADO
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView // Título "Elenco"
    private lateinit var recyclerCast: RecyclerView // Lista de bolinhas (Novo)
    private lateinit var tvPlot: TextView
    private lateinit var btnSeasonSelector: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var btnFavoriteSeries: ImageButton

    private lateinit var btnPlaySeries: Button
    private lateinit var btnDownloadEpisodeArea: LinearLayout
    private lateinit var imgDownloadEpisodeState: ImageView
    private lateinit var tvDownloadEpisodeState: TextView

    private lateinit var btnDownloadSeason: Button
    private lateinit var btnResume: Button // Botão Continuar

    private var episodesBySeason: Map<String, List<EpisodeStream>> = emptyMap()
    private var sortedSeasons: List<String> = emptyList()
    private var currentSeason: String = ""
    private var currentEpisode: EpisodeStream? = null

    private enum class DownloadState { BAIXAR, BAIXANDO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_details)

        // ✅ ADICIONE O CÓDIGO EXATAMENTE AQUI:
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        // ------------------------------------------

        seriesId = intent.getIntExtra("series_id", 0)
        seriesName = intent.getStringExtra("name") ?: ""
        seriesIcon = intent.getStringExtra("icon")
        seriesRating = intent.getStringExtra("rating") ?: "0.0"

        inicializarViews() 

        if (isTelevisionDevice()) {
            btnDownloadEpisodeArea.visibility = View.GONE
            btnDownloadSeason.visibility = View.GONE
        }

        tvTitle.text = seriesName
        tvRating.text = "Nota: $seriesRating"
        tvGenre.text = "Gênero: Buscando..."
        tvCast.text = "Elenco:" 
        tvPlot.text = "Carregando sinopse..."

        btnSeasonSelector.setBackgroundColor(Color.parseColor("#333333"))

        Glide.with(this)
            .load(seriesIcon)
            .placeholder(R.mipmap.ic_launcher)
            .centerCrop()
            .into(imgPoster)

        rvEpisodes.isFocusable = true
        rvEpisodes.isFocusableInTouchMode = true
        rvEpisodes.setHasFixedSize(true)
        rvEpisodes.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        
        rvEpisodes.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val holder = rvEpisodes.findContainingViewHolder(view) as? EpisodeAdapter.VH
                holder?.let {
                    val position = holder.adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        currentEpisode = (rvEpisodes.adapter as? EpisodeAdapter)?.list?.getOrNull(position)
                        restaurarEstadoDownload()
                    }
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        val isFavInicial = getFavSeries(this).contains(seriesId)
        atualizarIconeFavoritoSerie(isFavInicial)

        btnFavoriteSeries.setOnClickListener {
            val favs = getFavSeries(this)
            if (favs.contains(seriesId)) {
                favs.remove(seriesId)
            } else {
                favs.add(seriesId)
            }
            saveFavSeries(this, favs)
            atualizarIconeFavoritoSerie(favs.contains(seriesId))
        }

        btnSeasonSelector.setOnClickListener { mostrarSeletorDeTemporada() }

        btnPlaySeries.setOnClickListener {
            val ep = currentEpisode
            if (ep == null) {
                Toast.makeText(this, "Selecione um episódio.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            abrirPlayer(ep, false)
        }
        
        btnResume.setOnClickListener {
             val ep = currentEpisode ?: return@setOnClickListener
             abrirPlayer(ep, true)
        }

        restaurarEstadoDownload()
        setupDownloadButtons() 

        // ✅ MEMÓRIA DE LOGOS
        tentarCarregarLogoCache()

        carregarSeriesInfo()
        sincronizarDadosTMDB()
    }
    
    private fun inicializarViews() {
        imgPoster = findViewById(R.id.imgPoster)
        imgBackground = try { findViewById(R.id.imgBackground) } catch (e: Exception) { imgPoster }
        tvTitle = findViewById(R.id.tvTitle)
        tvTitle.visibility = View.INVISIBLE 

        imgTitleLogo = findViewById(R.id.imgTitleLogo) 
        tvRating = findViewById(R.id.tvRating)
        tvGenre = findViewById(R.id.tvGenre)
        tvPlot = findViewById(R.id.tvPlot)
        
        tvCast = findViewById(R.id.tvCast)
        recyclerCast = findViewById(R.id.recyclerCast)
        recyclerCast.visibility = View.GONE // ✅ MUDANÇA: OCULTA O RECYCLERVIEW DE FOTOS
        
        btnSeasonSelector = findViewById(R.id.btnSeasonSelector)
        rvEpisodes = findViewById(R.id.recyclerEpisodes) 
        
        btnPlaySeries = findViewById(R.id.btnPlay)
        btnFavoriteSeries = findViewById(R.id.btnFavorite)
        btnResume = findViewById(R.id.btnResume) 
        
        btnDownloadEpisodeArea = findViewById(R.id.btnDownloadArea)
        imgDownloadEpisodeState = findViewById(R.id.imgDownloadState)
        tvDownloadEpisodeState = findViewById(R.id.tvDownloadState)
        btnDownloadSeason = findViewById(R.id.btnDownloadSeason)
    }

    private fun setupDownloadButtons() {
        btnDownloadEpisodeArea.setOnClickListener {
            val ep = currentEpisode ?: return@setOnClickListener
            when (downloadState) {
                DownloadState.BAIXAR -> {
                    val eid = ep.id.toIntOrNull() ?: 0
                    if (eid == 0) return@setOnClickListener
                    val url = montarUrlEpisodio(ep)
                    val safeTitle = seriesName.replace("[^a-zA-Z0-9 _.-]".toRegex(), "_").ifBlank { "serie" }
                    val fileName = "${safeTitle}_T${currentSeason}E${ep.episode_num}_${eid}.mp4"
                    DownloadHelper.enqueueDownload(this, url, fileName, logicalId = "series_$eid", type = "series")
                    Toast.makeText(this, "Baixando...", Toast.LENGTH_SHORT).show()
                    setDownloadState(DownloadState.BAIXANDO, ep)
                }
                DownloadState.BAIXANDO -> startActivity(Intent(this, DownloadsActivity::class.java))
                DownloadState.BAIXADO -> startActivity(Intent(this, DownloadsActivity::class.java))
            }
        }

        btnDownloadSeason.setOnClickListener {
            if (currentSeason.isBlank()) return@setOnClickListener
            val lista = episodesBySeason[currentSeason] ?: emptyList()
            if (lista.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Baixar temporada")
                .setMessage("Baixar todos os ${lista.size} episódios?")
                .setPositiveButton("Sim") { _, _ -> baixarTemporadaAtual(lista) }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    private fun sincronizarDadosTMDB() {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        var cleanName = seriesName
        cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
        val lixo = listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUBLADO", "DUB", "|", "-", "_", ".")
        lixo.forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")

        val encodedName = try { URLEncoder.encode(cleanName, "UTF-8") } catch(e:Exception) { cleanName }
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encodedName&language=pt-BR"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val jsonObject = JSONObject(body)
                        val results = jsonObject.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val show = results.getJSONObject(0)
                            val tmdbId = show.getInt("id")
                            buscarLogoSerieTraduzida(tmdbId, apiKey)
                            buscarDetalhesTMDB(tmdbId, apiKey)
                            runOnUiThread {
                                val sinopse = show.optString("overview")
                                tvPlot.text = if (sinopse.isNotEmpty()) sinopse else "Sinopse indisponível."
                                val vote = show.optDouble("vote_average", 0.0)
                                if (vote > 0) tvRating.text = "Nota: ${String.format("%.1f", vote)}"
                                val backdropPath = show.optString("backdrop_path")
                                if (backdropPath.isNotEmpty() && imgBackground != imgPoster) {
                                    Glide.with(this@SeriesDetailsActivity)
                                        .load("https://image.tmdb.org/t/p/w1280$backdropPath")
                                        .centerCrop().into(imgBackground)
                                }
                                Glide.with(this@SeriesDetailsActivity).load(seriesIcon).placeholder(R.mipmap.ic_launcher).centerCrop().into(imgPoster)
                            }
                        } else {
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                    }
                }
            }
        })
    }

    private fun buscarLogoSerieTraduzida(id: Int, key: String) {
        val imagesUrl = "https://api.themoviedb.org/3/tv/$id/images?api_key=$key&include_image_language=pt,en,null"
        client.newCall(Request.Builder().url(imagesUrl).build()).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val obj = JSONObject(body)
                        val logos = obj.optJSONArray("logos")
                        if (logos != null && logos.length() > 0) {
                            var logoPath: String? = null
                            for (i in 0 until logos.length()) {
                                val logo = logos.getJSONObject(i)
                                if (logo.optString("iso_639_1") == "pt") {
                                    logoPath = logo.getString("file_path")
                                    break
                                }
                            }
                            if (logoPath == null) logoPath = logos.getJSONObject(0).getString("file_path")
                            val finalUrl = "https://image.tmdb.org/t/p/w500$logoPath"
                            getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
                                .edit().putString("series_logo_$seriesId", finalUrl).apply()
                            runOnUiThread {
                                tvTitle.visibility = View.GONE
                                imgTitleLogo.visibility = View.VISIBLE
                                Glide.with(this@SeriesDetailsActivity).load(finalUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
                            }
                        } else {
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
                    }
                }
            }
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = seriesName }
            }
        })
    }

    private fun buscarDetalhesTMDB(id: Int, key: String) {
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$key&append_to_response=credits&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string() ?: return
                try {
                    val d = JSONObject(body)
                    val gs = d.optJSONArray("genres")
                    val genresList = mutableListOf<String>()
                    if (gs != null) for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))

                    val credits = d.optJSONObject("credits")
                    val castArray = credits?.optJSONArray("cast")
                    val castNames = mutableListOf<String>()
                    if (castArray != null) {
                        val limit = if (castArray.length() > 10) 10 else castArray.length()
                        for (i in 0 until limit) {
                            castNames.add(castArray.getJSONObject(i).getString("name"))
                        }
                    }
                    runOnUiThread {
                        tvGenre.text = "Gênero: ${if (genresList.isEmpty()) "Variados" else genresList.joinToString(", ")}"
                        tvCast.text = "Elenco: ${castNames.joinToString(", ")}" // ✅ MUDANÇA: ELENCO PARA TEXTO
                    }
                } catch(e: Exception) { }
            }
        })
    }

    private fun tentarCarregarLogoCache() {
        val prefs = getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
        val cachedUrl = prefs.getString("series_logo_$seriesId", null)
        if (cachedUrl != null) {
            tvTitle.visibility = View.GONE
            imgTitleLogo.visibility = View.VISIBLE
            Glide.with(this).load(cachedUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
        }
    }

    override fun onResume() {
        super.onResume()
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        restaurarEstadoDownload()
    }
    
    private fun isTelevisionDevice() = packageManager.hasSystemFeature("android.software.leanback") || packageManager.hasSystemFeature("android.hardware.type.television")

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fav_series", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun saveFavSeries(context: Context, ids: Set<Int>) {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("fav_series", ids.map { it.toString() }.toSet()).apply()
    }

    private fun atualizarIconeFavoritoSerie(isFav: Boolean) {
        if (isFav) {
            btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_on)
            btnFavoriteSeries.setColorFilter(Color.parseColor("#FFD700"))
        } else {
            btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_off)
            btnFavoriteSeries.clearColorFilter()
        }
    }

    private fun carregarSeriesInfo() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        XtreamApi.service.getSeriesInfoV2(username, password, seriesId = seriesId)
            .enqueue(object : Callback<SeriesInfoResponse> {
                override fun onResponse(call: Call<SeriesInfoResponse>, response: Response<SeriesInfoResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        episodesBySeason = body.episodes ?: emptyMap()
                        sortedSeasons = episodesBySeason.keys.sortedBy { it.toIntOrNull() ?: 0 }
                        if (sortedSeasons.isNotEmpty()) mudarTemporada(sortedSeasons.first())
                        else btnSeasonSelector.text = "Indisponível"
                    }
                }
                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    Toast.makeText(this@SeriesDetailsActivity, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun mostrarSeletorDeTemporada() {
        if (sortedSeasons.isEmpty()) return
        val dialog = BottomSheetDialog(this, R.style.DialogTemporadaTransparente)
        val root = LinearLayout(this)
        root.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(0, 30, 0, 30)
        root.setBackgroundColor(Color.TRANSPARENT)

        val rvSeasons = RecyclerView(this)
        val rvParams = LinearLayout.LayoutParams(250.toPx(), 400.toPx()) 
        rvSeasons.layoutParams = rvParams
        rvSeasons.layoutManager = LinearLayoutManager(this)

        rvSeasons.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context)
                tv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tv.setPadding(20, 35, 20, 35)
                tv.gravity = Gravity.CENTER
                tv.textSize = 22f
                tv.setTextColor(Color.WHITE)
                tv.isFocusable = true
                tv.isClickable = true
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val season = sortedSeasons[position]
                val tv = holder.itemView as TextView
                tv.text = "Temporada $season"
                tv.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundColor(Color.parseColor("#FFD700"))
                        (v as TextView).setTextColor(Color.BLACK)
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    } else {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        (v as TextView).setTextColor(Color.WHITE)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }
                tv.setOnClickListener { mudarTemporada(season); dialog.dismiss() }
            }
            override fun getItemCount() = sortedSeasons.size
        }

        val btnClose = TextView(this)
        btnClose.layoutParams = LinearLayout.LayoutParams(250.toPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
        btnClose.text = "✕ FECHAR"
        btnClose.gravity = Gravity.CENTER
        btnClose.setTextColor(Color.WHITE)
        btnClose.setPadding(0, 25, 0, 25)
        btnClose.isFocusable = true
        btnClose.isClickable = true
        btnClose.setOnClickListener { dialog.dismiss() }

        root.addView(rvSeasons)
        root.addView(btnClose)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun mudarTemporada(seasonKey: String) {
        currentSeason = seasonKey
        btnSeasonSelector.text = "Temporada $seasonKey ▼"
        val lista = episodesBySeason[seasonKey] ?: emptyList()
        if (lista.isNotEmpty()) {
            currentEpisode = lista.first()
            restaurarEstadoDownload()
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val streamId = currentEpisode?.id?.toIntOrNull() ?: 0
            val pos = prefs.getLong("series_resume_${streamId}_pos", 0L)
            btnResume.visibility = if (pos > 10000) View.VISIBLE else View.GONE
        }
        rvEpisodes.adapter = EpisodeAdapter(lista) { ep, _ ->
            currentEpisode = ep
            restaurarEstadoDownload()
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val streamId = ep.id.toIntOrNull() ?: 0
            val pos = prefs.getLong("series_resume_${streamId}_pos", 0L)
            btnResume.visibility = if (pos > 10000) View.VISIBLE else View.GONE
            abrirPlayer(ep, true)
        }
    }

    private fun abrirPlayer(ep: EpisodeStream, usarResume: Boolean) {
        val streamId = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"

        // ✅ LÓGICA DE MOCHILA MANTIDA
        val lista = episodesBySeason[currentSeason] ?: emptyList()
        val posInList = lista.indexOfFirst { it.id == ep.id }
        val nextEp = if (posInList + 1 < lista.size) lista[posInList + 1] else null
        
        val mochilaIds = ArrayList<Int>()
        for (item in lista) {
            val idInt = item.id.toIntOrNull() ?: 0
            if (idInt != 0) mochilaIds.add(idInt)
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val pos = prefs.getLong("series_resume_${streamId}_pos", 0L)
        val dur = prefs.getLong("series_resume_${streamId}_dur", 0L)
        val existe = usarResume && pos > 30000L && pos < (dur * 0.95).toLong()

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId)
        intent.putExtra("stream_ext", ext)
        intent.putExtra("stream_type", "series")
        intent.putExtra("channel_name", "T${currentSeason}E${ep.episode_num} - $seriesName")
        
        if (mochilaIds.isNotEmpty()) {
            intent.putIntegerArrayListExtra("episode_list", mochilaIds) // ✅ MOCHILA
        }

        if (existe) intent.putExtra("start_position_ms", pos) // ✅ CONTINUAR
        
        if (nextEp != null) {
            intent.putExtra("next_stream_id", nextEp.id.toIntOrNull() ?: 0) // ✅ PRÓXIMO
            intent.putExtra("next_channel_name", "T${currentSeason}E${nextEp.episode_num} - $seriesName")
        }
        
        startActivity(intent)
    }

    private fun montarUrlEpisodio(ep: EpisodeStream): String {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        val server = "http://tvblack.shop"
        val eid = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"
        return "$server/get.php?username=$user&password=$pass&type=series&output=$ext&id=$eid"
    }

    private fun baixarTemporadaAtual(lista: List<EpisodeStream>) {
        for (ep in lista) {
            val eid = ep.id.toIntOrNull() ?: continue
            DownloadHelper.enqueueDownload(this, montarUrlEpisodio(ep), "${seriesName}_T${currentSeason}E${ep.episode_num}.mp4", logicalId = "series_$eid", type = "series")
        }
        Toast.makeText(this, "Baixando episódios...", Toast.LENGTH_LONG).show()
    }

    private fun getProgressText(): String {
        val ep = currentEpisode ?: return "Baixar"
        val eid = ep.id.toIntOrNull() ?: 0
        val progress = DownloadHelper.getDownloadProgress(this, "series_$eid")
        return when (downloadState) {
            DownloadState.BAIXANDO -> "Baixando ${progress}%"
            DownloadState.BAIXADO -> "Baixado"
            else -> "Baixar"
        }
    }

    private fun setDownloadState(state: DownloadState, ep: EpisodeStream?) {
        downloadState = state
        val eid = ep?.id?.toIntOrNull() ?: 0
        if (eid != 0) getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().putString("series_download_state_$eid", state.name).apply()
        when (state) {
            DownloadState.BAIXAR -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_arrow); tvDownloadEpisodeState.text = "Baixar" }
            DownloadState.BAIXANDO -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_loading); tvDownloadEpisodeState.text = getProgressText() }
            DownloadState.BAIXADO -> { imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_done); tvDownloadEpisodeState.text = "Baixado" }
        }
    }

    private fun restaurarEstadoDownload() {
        val ep = currentEpisode ?: return
        val eid = ep.id.toIntOrNull() ?: 0
        val saved = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).getString("series_download_state_$eid", DownloadState.BAIXAR.name)
        setDownloadState(DownloadState.valueOf(saved!!), ep)
    }

        class EpisodeAdapter(val list: List<EpisodeStream>, private val onClick: (EpisodeStream, Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvEpisodeTitle)
            val imgThumb: ImageView = v.findViewById(R.id.imgEpisodeThumb) // Removida a interrogação para garantir o ID
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))
        
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            holder.tvTitle.text = "E${ep.episode_num.toString().padStart(2, '0')} - ${ep.title}"
            
            // ✅ RESTAURAÇÃO DA CAPA (O que tinha sumido):
            // O link da imagem vem do campo 'movie_image' dentro de 'info'
            val capaUrl = ep.info?.movie_image ?: ""
            
            Glide.with(holder.itemView.context)
                .load(capaUrl)
                .placeholder(android.R.color.darker_gray) // Cinza apenas enquanto baixa
                .error(android.R.color.black)             // Preto se o link quebrar
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.imgThumb)

            // ✅ CONFIGURAÇÃO COMPLETA DE CONTROLE REMOTO + CELULAR (UM CLIQUE)
            holder.itemView.setOnClickListener { onClick(ep, position) }
            
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                // Cor do título muda no foco (Padrão das suas outras telas)
                holder.tvTitle.setTextColor(if (hasFocus) Color.YELLOW else Color.WHITE)
                
                // Efeito visual de foco para TV (Aumenta o card)
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
                    view.elevation = 10f
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    view.elevation = 4f
                }
            }
        }
        
        override fun getItemCount() = list.size
    }
}
