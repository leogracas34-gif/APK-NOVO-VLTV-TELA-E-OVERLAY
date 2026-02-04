package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.nio.charset.Charset

// --- AS ÚNICAS IMPORTAÇÕES ADICIONADAS ---
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ✅ IMPORTAÇÕES PARA MEDIA3, DATABASE E PLAYER EXTERNO
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView 
import androidx.lifecycle.lifecycleScope
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.EpgEntity
import com.vltv.play.data.LiveStreamEntity

class LiveTvActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCategoryTitle: TextView

    // ✅ NOVOS ELEMENTOS DO PAINEL DIREITO
    private lateinit var miniPlayerView: PlayerView
    private lateinit var containerMiniPlayer: FrameLayout
    private lateinit var tvEpgAtualPainel: TextView
    private lateinit var tvEpgProximo1: TextView
    private lateinit var tvEpgProximo2: TextView
    private lateinit var tvEpgProximo3: TextView
    
    private var player: ExoPlayer? = null
    private var isFullScreen = false
    private var serverUrl = ""

    private var username = ""
    private var password = ""
    
    // ✅ DATABASE
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Mantendo a estrutura original do seu cache
    private var cachedCategories: List<LiveCategory>? = null
    
    // IMPORTANTE: Alterado para String para evitar o erro de tipos no Cache
    private val channelsCache = mutableMapOf<String, List<LiveStream>>() 

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ✅ APONTA PARA O NOVO XML DE 3 COLUNAS
        setContentView(R.layout.activity_live_tv_painel)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        // ✅ INICIALIZAÇÃO DOS ELEMENTOS DO PAINEL LATERAL
        miniPlayerView = findViewById(R.id.miniPlayerView)
        containerMiniPlayer = findViewById(R.id.containerMiniPlayer)
        tvEpgAtualPainel = findViewById(R.id.tvEpgAtualPainel)
        tvEpgProximo1 = findViewById(R.id.tvEpgProximo1)
        tvEpgProximo2 = findViewById(R.id.tvEpgProximo2)
        tvEpgProximo3 = findViewById(R.id.tvEpgProximo3)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""
        serverUrl = prefs.getString("dns", "") ?: ""

        // Configuração de Foco
        setupRecyclerFocus()
        
        // ✅ INICIALIZAÇÃO DO PLAYER
        initExoPlayer()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50) 
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER 

        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // ✅ AJUSTADO PARA 2 COLUNAS PARA CABER O PAINEL DIREITO
        rvChannels.layoutManager = GridLayoutManager(this, 2)
        rvChannels.isFocusable = true
        rvChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvChannels.setHasFixedSize(true)
        rvChannels.setItemViewCacheSize(100) 

        rvCategories.requestFocus()

        carregarCategorias()
    }

    // ✅ INICIALIZAÇÃO DO EXOPLAYER (MEDIA3)
    private fun initExoPlayer() {
        player = ExoPlayer.Builder(this).build()
        miniPlayerView.player = player
        miniPlayerView.useController = false
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) progressBar.visibility = View.VISIBLE
                else progressBar.visibility = View.GONE
            }
        })
    }

    // ✅ LÓGICA DE CARREGAMENTO DO MINI PLAYER (CHAMA O EPG DO DATABASE)
    private fun playInMiniPlayer(canal: LiveStream) {
        val streamUrl = "$serverUrl/live/$username/$password/${canal.id}.ts"
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
        
        // Busca EPG no Database para ser instantâneo
        carregarEpgNoPainel(canal.id.toString())
    }

    // ✅ LÓGICA DE EPG VIA DATABASE (CARREGAMENTO RÁPIDO)
    private fun carregarEpgNoPainel(streamId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val epgLocal = database.streamDao().getEpgByChannel(streamId)
            withContext(Dispatchers.Main) {
                if (epgLocal != null) {
                    tvEpgAtualPainel.text = "AGORA: ${epgLocal.title}"
                    tvEpgProximo1.text = "DESCRIÇÃO: ${epgLocal.description ?: "Sem detalhes."}"
                } else {
                    // Se não tem no banco, busca na API e salva
                    buscarEpgApiESalvar(streamId)
                }
            }
        }
    }

    private fun buscarEpgApiESalvar(streamId: String) {
        XtreamApi.service.getShortEpg(username, password, streamId, 4).enqueue(object : Callback<EpgWrapper> {
            override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                if (response.isSuccessful && response.body()?.epg_listings != null) {
                    val listings = response.body()!!.epg_listings!!
                    if (listings.isNotEmpty()) {
                        tvEpgAtualPainel.text = "AGORA: ${decodeBase64(listings[0].title)}"
                        // Salva no Database para a próxima vez
                        lifecycleScope.launch(Dispatchers.IO) {
                            val entity = EpgEntity(stream_id = streamId, title = decodeBase64(listings[0].title), description = "Programação atualizada")
                            database.streamDao().insertEpg(listOf(entity))
                        }
                    }
                }
            }
            override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {}
        })
    }

    // ✅ LÓGICA DE PLAYER EXTERNO (VLC / MX PLAYER)
    private fun abrirPlayerExterno(url: String, playerName: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(url), "video/*")
        if (playerName == "VLC") intent.setPackage("org.videolan.vlc")
        if (playerName == "MX") intent.setPackage("com.mxtech.videoplayer.ad")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "$playerName não instalado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFullScreen() {
        if (!isFullScreen) {
            isFullScreen = true
            miniPlayerView.useController = true
            containerMiniPlayer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            rvCategories.visibility = View.GONE
            rvChannels.visibility = View.GONE
            findViewById<View>(R.id.layoutInfoEpgLateral)?.visibility = View.GONE
        } else {
            isFullScreen = false
            miniPlayerView.useController = false
            val intent = Intent(this, LiveTvActivity::class.java)
            finish()
            startActivity(intent)
        }
    }

    private fun decodeBase64(text: String?): String {
        return try {
            if (text.isNullOrEmpty()) "" else String(Base64.decode(text, Base64.DEFAULT), Charset.forName("UTF-8"))
        } catch (e: Exception) { text ?: "" }
    }

    private fun preLoadChannelLogos(canais: List<LiveStream>) {
        CoroutineScope(Dispatchers.IO).launch {
            val limit = if (canais.size > 40) 40 else canais.size
            for (i in 0 until limit) {
                val url = canais[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@LiveTvActivity).load(url).diskCacheStrategy(DiskCacheStrategy.ALL).priority(Priority.LOW).preload()
                    }
                }
            }
        }
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rvCategories.smoothScrollToPosition(0) }
        rvChannels.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rvChannels.smoothScrollToPosition(0) }
    }

    private fun isAdultName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("+18") || n.contains("adult") || n.contains("xxx") || n.contains("hot") || n.contains("sexo")
    }

    private fun carregarCategorias() {
        cachedCategories?.let { aplicarCategorias(it); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getLiveCategories(username, password).enqueue(object : Callback<List<LiveCategory>> {
            override fun onResponse(call: Call<List<LiveCategory>>, response: Response<List<LiveCategory>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    var categorias = response.body()!!
                    cachedCategories = categorias
                    if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                        categorias = categorias.filterNot { isAdultName(it.name) }
                    }
                    aplicarCategorias(categorias)
                }
            }
            override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) { progressBar.visibility = View.GONE }
        })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        if (categorias.isEmpty()) return
        categoryAdapter = CategoryAdapter(categorias) { categoria -> carregarCanais(categoria) }
        rvCategories.adapter = categoryAdapter
        carregarCanais(categorias[0])
    }

    private fun carregarCanais(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        val catIdStr = categoria.id.toString()
        channelsCache[catIdStr]?.let { aplicarCanais(categoria, it); preLoadChannelLogos(it); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getLiveStreams(username, password, categoryId = catIdStr).enqueue(object : Callback<List<LiveStream>> {
            override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    var canais = response.body()!!
                    channelsCache[catIdStr] = canais
                    if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                        canais = canais.filterNot { isAdultName(it.name) }
                    }
                    aplicarCanais(categoria, canais)
                    preLoadChannelLogos(canais)
                }
            }
            override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) { progressBar.visibility = View.GONE }
        })
    }

    private fun aplicarCanais(categoria: LiveCategory, canais: List<LiveStream>) {
        tvCategoryTitle.text = categoria.name
        channelAdapter = ChannelAdapter(canais, username, password) { canal ->
            val streamUrl = "$serverUrl/live/$username/$password/${canal.id}.ts"
            
            // VERIFICA SE USA PLAYER EXTERNO NAS CONFIGS
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val playerPref = prefs.getString("player_type", "Interno")
            
            if (playerPref == "Interno") {
                playInMiniPlayer(canal)
                toggleFullScreen()
            } else {
                abrirPlayerExterno(streamUrl, playerPref!!)
            }
        }
        rvChannels.adapter = channelAdapter
    }

    inner class CategoryAdapter(private val list: List<LiveCategory>, private val onClick: (LiveCategory) -> Unit) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        private var selectedPos = 0
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tvName: TextView = v.findViewById(R.id.tvName) }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            atualizarEstiloCategoria(holder, position == selectedPos, false)
            holder.itemView.isFocusable = true
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                atualizarEstiloCategoria(holder, selectedPos == position, hasFocus)
                if (hasFocus) onClick(item)
            }
            holder.itemView.setOnClickListener {
                notifyItemChanged(selectedPos); selectedPos = holder.adapterPosition
                notifyItemChanged(selectedPos); onClick(item)
            }
        }
        private fun atualizarEstiloCategoria(holder: VH, isSelected: Boolean, hasFocus: Boolean) {
            if (hasFocus) {
                holder.tvName.setTextColor(Color.YELLOW); holder.itemView.setBackgroundResource(R.drawable.bg_focus_neon)
                holder.itemView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
            } else {
                holder.itemView.setBackgroundResource(0); holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                if (isSelected) {
                    holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.red_primary))
                    holder.tvName.setBackgroundColor(0xFF252525.toInt())
                } else {
                    holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.gray_text))
                    holder.tvName.setBackgroundColor(0x00000000)
                }
            }
        }
        override fun getItemCount() = list.size
    }

    inner class ChannelAdapter(private val list: List<LiveStream>, private val user: String, private val pass: String, private val onClick: (LiveStream) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private val epgCacheMap = mutableMapOf<Int, List<EpgResponseItem>>()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName); val tvNow: TextView = v.findViewById(R.id.tvNow)
            val tvNext: TextView = v.findViewById(R.id.tvNext); val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            Glide.with(holder.itemView.context).load(item.icon).diskCacheStrategy(DiskCacheStrategy.ALL).priority(Priority.HIGH).placeholder(R.drawable.bg_logo_placeholder).into(holder.imgLogo)
            carregarEpg(holder, item)
            holder.itemView.isFocusable = true
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    holder.tvName.setTextColor(Color.YELLOW); holder.tvName.textSize = 20f 
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()
                    playInMiniPlayer(item)
                } else {
                    holder.tvName.setTextColor(Color.WHITE); holder.tvName.textSize = 16f
                    view.setBackgroundResource(0); view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }
        private fun carregarEpg(holder: VH, canal: LiveStream) {
            epgCacheMap[canal.id]?.let { mostrarEpg(holder, it); return }
            XtreamApi.service.getShortEpg(user, pass, canal.id.toString(), 2).enqueue(object : Callback<EpgWrapper> {
                override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                    if (response.isSuccessful && response.body()?.epg_listings != null) {
                        val epg = response.body()!!.epg_listings!!
                        epgCacheMap[canal.id] = epg
                        mostrarEpg(holder, epg)
                    }
                }
                override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {}
            })
        }
        private fun mostrarEpg(holder: VH, epg: List<EpgResponseItem>) {
            if (epg.isNotEmpty()) {
                holder.tvNow.text = decodeBase64(epg[0].title)
                if (epg.size > 1) holder.tvNext.text = decodeBase64(epg[1].title)
            }
        }
        override fun getItemCount() = list.size
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { if (isFullScreen) { toggleFullScreen(); return true }; finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { super.onDestroy(); player?.release() }
}
