package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.nio.charset.Charset

class LiveTvActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCategoryTitle: TextView

    // Novas Views para o Preview
    private lateinit var pvPreview: PlayerView
    private lateinit var tvPreviewName: TextView
    private lateinit var tvPreviewEpg: TextView
    private lateinit var tvPreviewNext: TextView
    private var miniPlayer: ExoPlayer? = null
    private lateinit var layoutPreviewContainer: LinearLayout

    private var username = ""
    private var password = ""
    private var isFullscreen = false

    private var cachedCategories: List<LiveCategory>? = null
    private val channelsCache = mutableMapOf<String, List<LiveStream>>() 

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv)

        hideSystemUI()

        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        pvPreview = findViewById(R.id.pvPreview)
        tvPreviewName = findViewById(R.id.tvPreviewName)
        tvPreviewEpg = findViewById(R.id.tvPreviewEpg)
        tvPreviewNext = findViewById(R.id.tvPreviewNext)
        layoutPreviewContainer = findViewById(R.id.layoutPreviewContainer)

        // Inicializa o player UMA VEZ SÓ aqui para ser rápido
        inicializarPlayer()

        val showPreview = intent.getBooleanExtra("SHOW_PREVIEW", true)
        if (showPreview) {
            layoutPreviewContainer.visibility = View.VISIBLE
        } else {
            layoutPreviewContainer.visibility = View.GONE
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        setupRecyclerFocus()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.isFocusable = true
        rvChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvChannels.setHasFixedSize(true)

        rvCategories.requestFocus()

        carregarCategorias()
    }

    // Função dedicada para iniciar o player (Evita recriar toda hora)
    private fun inicializarPlayer() {
        if (miniPlayer == null) {
            miniPlayer = ExoPlayer.Builder(this).build()
            pvPreview.player = miniPlayer
            pvPreview.useController = false
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        // Se o player existe, garante que toque. Se não, recria.
        if (miniPlayer == null) inicializarPlayer()
        miniPlayer?.playWhenReady = true
    }

    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) rvCategories.smoothScrollToPosition(0)
        }
        rvChannels.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) rvChannels.smoothScrollToPosition(0)
        }
    }

    // ✅ OTIMIZADO: Agora troca de canal instantaneamente sem travar
    private fun carregarPreview(canal: LiveStream) {
        val categoriaAtual = tvCategoryTitle.text.toString().lowercase()
        
        layoutPreviewContainer.visibility = View.VISIBLE
        pvPreview.visibility = View.VISIBLE
        
        tvPreviewName.text = canal.name
        
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "http://tvblack.shop") ?: "http://tvblack.shop"
        val cleanDns = if (dns.endsWith("/")) dns.dropLast(1) else dns
        val url = "$cleanDns/live/$username/$password/${canal.id}.ts"

        // Garante que o player existe
        if (miniPlayer == null) inicializarPlayer()

        // Garante que a proporção está correta para Mini Tela
        if (!isFullscreen) {
            pvPreview.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        // Define Metadata para o HUD (Nome do canal)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(canal.name).build())
            .build()

        // Troca apenas a mídia, não o player inteiro (MUITO MAIS RÁPIDO)
        miniPlayer?.setMediaItem(mediaItem)
        miniPlayer?.prepare()
        miniPlayer?.playWhenReady = true
    }

    private fun isAdultName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("+18") || n.contains("adult") || n.contains("xxx") || n.contains("hot") || n.contains("sexo")
    }

    private fun carregarCategorias() {
        cachedCategories?.let { categorias -> aplicarCategorias(categorias); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getLiveCategories(username, password)
            .enqueue(object : Callback<List<LiveCategory>> {
                override fun onResponse(call: Call<List<LiveCategory>>, response: Response<List<LiveCategory>>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var categorias = response.body()!!
                        cachedCategories = categorias
                        if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                            categorias = categorias.filterNot { cat -> isAdultName(cat.name) }
                        }
                        aplicarCategorias(categorias)
                    } else {
                        Toast.makeText(this@LiveTvActivity, "Erro ao carregar categorias", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@LiveTvActivity, "Falha de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        if (categorias.isEmpty()) {
            Toast.makeText(this@LiveTvActivity, "Nenhuma categoria disponível.", Toast.LENGTH_SHORT).show()
            rvCategories.adapter = CategoryAdapter(emptyList()) {}
            rvChannels.adapter = ChannelAdapter(emptyList(), username, password) {}
            return
        }
        categoryAdapter = CategoryAdapter(categorias) { categoria -> carregarCanais(categoria) }
        rvCategories.adapter = categoryAdapter
        carregarCanais(categorias[0])
    }

    private fun carregarCanais(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        // miniPlayer?.stop() // REMOVIDO: Não parar o vídeo ao trocar categoria para ser mais fluido
        val catIdStr = categoria.id.toString()
        channelsCache[catIdStr]?.let { canaisCacheadas -> aplicarCanais(categoria, canaisCacheadas); return }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getLiveStreams(username, password, categoryId = catIdStr)
            .enqueue(object : Callback<List<LiveStream>> {
                override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var canais = response.body()!!
                        channelsCache[catIdStr] = canais
                        if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                            canais = canais.filterNot { canal -> isAdultName(canal.name) }
                        }
                        aplicarCanais(categoria, canais)
                    } else {
                        Toast.makeText(this@LiveTvActivity, "Erro ao carregar canais", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) { progressBar.visibility = View.GONE }
            })
    }

    private fun aplicarCanais(categoria: LiveCategory, canais: List<LiveStream>) {
        tvCategoryTitle.text = categoria.name
        channelAdapter = ChannelAdapter(canais, username, password) { canal ->
            toggleFullscreen(true)
        }
        rvChannels.adapter = channelAdapter
        if (canais.isNotEmpty()) {
            rvChannels.post {
                val firstView = rvChannels.layoutManager?.findViewByPosition(0)
                firstView?.requestFocus()
                carregarPreview(canais[0])
            }
        }
    }

    // ✅ OTIMIZADO: Transição suave sem piscar a tela preta
    private fun toggleFullscreen(full: Boolean) {
        isFullscreen = full
        if (full) {
            rvCategories.visibility = View.GONE
            rvChannels.visibility = View.GONE
            tvCategoryTitle.visibility = View.GONE
            tvPreviewName.visibility = View.GONE
            tvPreviewEpg.visibility = View.GONE
            tvPreviewNext.visibility = View.GONE
            
            val params = layoutPreviewContainer.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            layoutPreviewContainer.layoutParams = params
            
            // ZOOM para preencher a tela toda (Remove bordas pretas)
            pvPreview.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            pvPreview.useController = true
            pvPreview.requestFocus()
            hideSystemUI()
        } else {
            rvCategories.visibility = View.VISIBLE
            rvChannels.visibility = View.VISIBLE
            tvCategoryTitle.visibility = View.VISIBLE
            tvPreviewName.visibility = View.VISIBLE
            tvPreviewEpg.visibility = View.VISIBLE
            tvPreviewNext.visibility = View.VISIBLE

            val params = layoutPreviewContainer.layoutParams
            params.width = 0 
            params.height = ViewGroup.LayoutParams.MATCH_PARENT 
            layoutPreviewContainer.layoutParams = params
            
            // FIT para caber na caixinha sem cortar
            pvPreview.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            pvPreview.useController = false
            
            // NÃO resetamos o player aqui para evitar a tela preta de 4s
            // Apenas devolvemos o foco
            rvChannels.requestFocus()
        }
    }

    // ADAPTERS (Mantidos idênticos)
    inner class CategoryAdapter(private val list: List<LiveCategory>, private val onClick: (LiveCategory) -> Unit) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        private var selectedPos = 0
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tvName: TextView = v.findViewById(R.id.tvName) }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            if (selectedPos == position) {
                holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.red_primary))
                holder.tvName.setBackgroundColor(0xFF252525.toInt())
            } else {
                holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.gray_text))
                holder.tvName.setBackgroundColor(0x00000000)
            }
            holder.itemView.isFocusable = true
            holder.itemView.setOnClickListener { notifyItemChanged(selectedPos); selectedPos = holder.adapterPosition; notifyItemChanged(selectedPos); onClick(item) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) { holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.red_primary)); holder.tvName.setBackgroundColor(0xFF252525.toInt()) }
                else if (selectedPos != holder.adapterPosition) { holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.gray_text)); holder.tvName.setBackgroundColor(0x00000000) }
            }
        }
        override fun getItemCount() = list.size
    }

    inner class ChannelAdapter(private val list: List<LiveStream>, private val username: String, private val password: String, private val onClick: (LiveStream) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private val epgCache = mutableMapOf<Int, List<EpgResponseItem>>()
        private val handler = Handler(Looper.getMainLooper())
        private var pendingRunnable: Runnable? = null
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName); val tvNow: TextView = v.findViewById(R.id.tvNow)
            val tvNext: TextView = v.findViewById(R.id.tvNext); val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            Glide.with(holder.itemView.context).load(item.icon).diskCacheStrategy(DiskCacheStrategy.ALL).priority(Priority.HIGH).placeholder(R.drawable.bg_logo_placeholder).into(holder.imgLogo)
            carregarEpg(holder, item)
            holder.itemView.isFocusable = true
            holder.itemView.setOnClickListener { pendingRunnable?.let { handler.removeCallbacks(it) }; onClick(item) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.itemView.alpha = if (hasFocus) 1.0f else 0.8f
                if (hasFocus) {
                    tvPreviewEpg.text = "Agora: ${holder.tvNow.text}"; tvPreviewNext.text = "A seguir: ${holder.tvNext.text}"
                    pendingRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable { carregarPreview(item) }
                    pendingRunnable = r; handler.postDelayed(r, 800)
                }
            }
        }
        private fun decodeBase64(text: String?) = try { if (text.isNullOrEmpty()) "" else String(Base64.decode(text, Base64.DEFAULT), Charset.forName("UTF-8")) } catch (e: Exception) { text ?: "" }
        private fun carregarEpg(holder: VH, canal: LiveStream) {
            epgCache[canal.id]?.let { mostrarEpg(holder, it); return }
            XtreamApi.service.getShortEpg(username, password, canal.id.toString(), 2).enqueue(object : Callback<EpgWrapper> {
                override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                    if (response.isSuccessful && response.body()?.epg_listings != null) {
                        val epg = response.body()!!.epg_listings!!
                        epgCache[canal.id] = epg; mostrarEpg(holder, epg)
                    }
                }
                override fun onFailure(call: Call<EpgWrapper>, t: Throwable) { holder.tvNow.text = "Programação não disponível"; holder.tvNext.text = "" }
            })
        }
        private fun mostrarEpg(holder: VH, epg: List<EpgResponseItem>) {
            if (epg.isNotEmpty()) {
                holder.tvNow.text = decodeBase64(epg[0].title)
                holder.tvNext.text = if (epg.size > 1) decodeBase64(epg[1].title) else ""
            }
        }
        override fun getItemCount() = list.size
    }

    override fun onStop() {
        super.onStop()
        miniPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        miniPlayer?.release()
        miniPlayer = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFullscreen) {
                toggleFullscreen(false)
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
