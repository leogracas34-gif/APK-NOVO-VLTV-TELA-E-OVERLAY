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
import androidx.recyclerview.widget.LinearLayoutManager // ✅ ALTERADO PARA LISTA
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val database by lazy { AppDatabase.getDatabase(this) }

    private var cachedCategories: List<LiveCategory>? = null
    private val channelsCache = mutableMapOf<String, List<LiveStream>>() 

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv_painel)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

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

        setupRecyclerFocus()
        initExoPlayer()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50) 
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER 
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // ✅ MUDANÇA: AGORA É UMA LISTA VERTICAL (NÃO MAIS GRID)
        rvChannels.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvChannels.isFocusable = true
        rvChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvChannels.setHasFixedSize(true)
        rvChannels.setItemViewCacheSize(100) 

        rvCategories.requestFocus()

        carregarCategorias()
    }

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

    private fun playInMiniPlayer(canal: LiveStream) {
        val streamUrl = "$serverUrl/live/$username/$password/${canal.id}.ts"
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        
        // Verifica se é o mesmo item para não recarregar à toa
        val currentItem = player?.currentMediaItem
        if (currentItem?.localConfiguration?.uri.toString() != streamUrl) {
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.playWhenReady = true
        }
        
        val idString = canal.id.toString()
        carregarEpgNoPainel(idString)
    }

    private fun carregarEpgNoPainel(streamId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localEpg = database.streamDao().getEpgByChannel(streamId)
            withContext(Dispatchers.Main) {
                if (localEpg != null) {
                    tvEpgAtualPainel.text = "AGORA: ${localEpg.title}"
                    tvEpgProximo1.text = "DESCRIÇÃO: ${localEpg.description ?: "Carregando detalhes..."}"
                }
                buscarEpgApiESalvar(streamId)
            }
        }
    }

    private fun buscarEpgApiESalvar(streamId: String) {
        XtreamApi.service.getShortEpg(
            user = username, 
            pass = password, 
            streamId = streamId, 
            limit = 4
        ).enqueue(object : Callback<EpgWrapper> {
            override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                if (response.isSuccessful && response.body()?.epg_listings != null) {
                    val listings = response.body()!!.epg_listings!!
                    if (listings.isNotEmpty()) {
                        val title = decodeBase64(listings[0].title)
                        tvEpgAtualPainel.text = "AGORA: $title"
                        
                        lifecycleScope.launch(Dispatchers.IO) {
                            val entity = EpgEntity(
                                stream_id = streamId,
                                title = title,
                                start = listings[0].start ?: "",
                                stop = listings[0].stop ?: "",
                                description = decodeBase64(listings[0].description)
                            )
                            database.streamDao().insertEpg(listOf(entity))
                        }
                    }
                }
            }
            override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {}
        })
    }

    // ✅ LÓGICA DE EXPANSÃO DINÂMICA (ZERO DELAY + FOCO PRESERVADO)
    private fun toggleFullScreen() {
        val containerLateral = findViewById<View>(R.id.layoutInfoEpgLateral)
        val scrollEpg = findViewById<View>(R.id.layoutInfoEpgScroll)
        val colunaDoMeio = rvChannels.parent as View // Pega o layout que segura a lista e o titulo

        isFullScreen = !isFullScreen

        if (isFullScreen) {
            // Modo Tela Cheia: Esconde listas, expande player
            rvCategories.visibility = View.GONE
            colunaDoMeio.visibility = View.GONE
            scrollEpg.visibility = View.GONE

            // Expande container lateral para ocupar tudo
            val paramsSide = containerLateral.layoutParams
            paramsSide.width = ViewGroup.LayoutParams.MATCH_PARENT
            containerLateral.layoutParams = paramsSide

            // Expande container do player para ocupar tudo
            val paramsPlayer = containerMiniPlayer.layoutParams
            paramsPlayer.height = ViewGroup.LayoutParams.MATCH_PARENT
            containerMiniPlayer.layoutParams = paramsPlayer

            miniPlayerView.useController = true
            miniPlayerView.requestFocus() // Passa foco para o player
        } else {
            // Modo Mini: Restaura listas e tamanhos
            rvCategories.visibility = View.VISIBLE
            colunaDoMeio.visibility = View.VISIBLE
            scrollEpg.visibility = View.VISIBLE

            // Restaura largura lateral (380dp)
            val paramsSide = containerLateral.layoutParams
            paramsSide.width = dpToPx(380)
            containerLateral.layoutParams = paramsSide

            // Restaura altura do player (220dp)
            val paramsPlayer = containerMiniPlayer.layoutParams
            paramsPlayer.height = dpToPx(220)
            containerMiniPlayer.layoutParams = paramsPlayer

            miniPlayerView.useController = false
            rvChannels.requestFocus() // ✅ O FOCO VOLTA PARA A LISTA (ONDE ESTAVA ANTES)
        }
    }

    // Função auxiliar para converter DP em Pixels
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun decodeBase64(text: String?): String {
        return try {
            if (text.isNullOrEmpty()) "" else String(
                Base64.decode(text, Base64.DEFAULT),
                Charset.forName("UTF-8") 
            )
        } catch (e: Exception) {
            text ?: ""
        }
    }

    private fun preLoadChannelLogos(canais: List<LiveStream>) {
        CoroutineScope(Dispatchers.IO).launch {
            val limit = if (canais.size > 40) 40 else canais.size
            for (i in 0 until limit) {
                val url = canais[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@LiveTvActivity)
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .priority(Priority.LOW)
                            .preload()
                    }
                }
            }
        }
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rvCategories.smoothScrollToPosition(0)
            }
        }
        
        rvChannels.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rvChannels.smoothScrollToPosition(0)
            }
        }
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
            override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) {
                progressBar.visibility = View.GONE
            }
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
        channelsCache[catIdStr]?.let { 
            aplicarCanais(categoria, it)
            preLoadChannelLogos(it)
            return 
        }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getLiveStreams(
            user = username, 
            pass = password, 
            categoryId = catIdStr
        ).enqueue(object : Callback<List<LiveStream>> {
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
            override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {
                progressBar.visibility = View.GONE
            }
        })
    }

    private fun aplicarCanais(categoria: LiveCategory, canais: List<LiveStream>) {
        tvCategoryTitle.text = categoria.name
        channelAdapter = ChannelAdapter(canais, username, password) { canal ->
            val streamUrl = "$serverUrl/live/$username/$password/${canal.id}.ts"
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val playerPref = prefs.getString("player_type", "Interno")
            
            if (playerPref == "Interno") {
                // ✅ VERIFICAÇÃO DE CLIQUE DUPLO PARA EXPANDIR
                if (isFullScreen) {
                    // Já está em tela cheia, não faz nada ou recarrega
                } else {
                    // Se já estiver tocando esse canal, expande. Se não, toca.
                    val currentUri = player?.currentMediaItem?.localConfiguration?.uri.toString()
                    if (currentUri == streamUrl) {
                        toggleFullScreen()
                    } else {
                        playInMiniPlayer(canal)
                    }
                }
            } else {
                abrirPlayerExterno(streamUrl, playerPref ?: "VLC")
            }
        }
        rvChannels.adapter = channelAdapter
    }

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
                if (hasFocus) { onClick(item) }
            }
            holder.itemView.setOnClickListener {
                notifyItemChanged(selectedPos); selectedPos = holder.adapterPosition
                notifyItemChanged(selectedPos); onClick(item)
            }
        }
        private fun atualizarEstiloCategoria(holder: VH, isSelected: Boolean, hasFocus: Boolean) {
            if (hasFocus) {
                holder.tvName.setTextColor(Color.YELLOW)
                holder.itemView.setBackgroundResource(R.drawable.bg_focus_neon)
                holder.itemView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
            } else {
                holder.itemView.setBackgroundResource(0)
                holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
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
                    // Chama a função de clique (que lida com o play na mini tela)
                    onClick(item)
                } else {
                    holder.tvName.setTextColor(Color.WHITE); holder.tvName.textSize = 16f
                    view.setBackgroundResource(0); view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }
            holder.itemView.setOnClickListener { 
                // Clique manual (touch ou OK do controle)
                onClick(item) 
            }
        }
        private fun carregarEpg(holder: VH, canal: LiveStream) {
            epgCacheMap[canal.id]?.let { mostrarEpg(holder, it); return }
            
            // ✅ CORREÇÃO: Argumentos Nomeados
            val idParaApi = canal.id.toString()
            XtreamApi.service.getShortEpg(
                user = user, 
                pass = pass, 
                streamId = idParaApi, 
                limit = 2
            ).enqueue(object : Callback<EpgWrapper> {
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFullScreen) { toggleFullScreen(); return true }
            finish(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
