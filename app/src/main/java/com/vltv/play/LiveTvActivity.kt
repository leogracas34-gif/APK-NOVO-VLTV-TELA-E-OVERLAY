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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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

    // Novas Views para o Preview (Conforme o novo Layout)
    private lateinit var pvPreview: PlayerView
    private lateinit var tvPreviewName: TextView
    private lateinit var tvPreviewEpg: TextView
    private var miniPlayer: ExoPlayer? = null

    private var username = ""
    private var password = ""

    // Mantendo a estrutura original do seu cache
    private var cachedCategories: List<LiveCategory>? = null
    
    // IMPORTANTE: Alterado para String para evitar o erro de tipos no Cache
    private val channelsCache = mutableMapOf<String, List<LiveStream>>() 

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv)

        // Esconder barras iniciais
        hideSystemUI()

        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        // Inicializar as Views do Preview que adicionamos no XML
        pvPreview = findViewById(R.id.pvPreview)
        tvPreviewName = findViewById(R.id.tvPreviewName)
        tvPreviewEpg = findViewById(R.id.tvPreviewEpg)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        // Configuração de Foco
        setupRecyclerFocus()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)

        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // Trocado para LinearLayoutManager para modo Lista Vertical
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.isFocusable = true
        rvChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvChannels.setHasFixedSize(true)

        rvCategories.requestFocus()

        carregarCategorias()
    }

    // Função para forçar a barra de bateria e relógio a sumirem
    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    // Garante que as barras sumam sempre que a tela for focada
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
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

    // Lógica para carregar o Preview no quadro da direita com TRAVA para Filmes/Séries
    private fun carregarPreview(canal: LiveStream) {
        val categoriaAtual = tvCategoryTitle.text.toString().lowercase()

        // TRAVA: Se for filme ou série, para o player e esconde a tela de vídeo
        if (categoriaAtual.contains("filme") || categoriaAtual.contains("serie") || categoriaAtual.contains("série")) {
            miniPlayer?.stop()
            miniPlayer?.release()
            miniPlayer = null
            pvPreview.visibility = View.GONE
            tvPreviewName.text = canal.name
            return
        }

        // Se for TV ao Vivo, mostra a tela e inicia o player
        pvPreview.visibility = View.VISIBLE
        miniPlayer?.stop()
        miniPlayer?.release()
        
        tvPreviewName.text = canal.name
        
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "http://tvblack.shop") ?: "http://tvblack.shop"
        val cleanDns = if (dns.endsWith("/")) dns.dropLast(1) else dns
        
        val url = "$cleanDns/live/$username/$password/${canal.id}.ts"

        miniPlayer = ExoPlayer.Builder(this).build()
        pvPreview.player = miniPlayer
        pvPreview.useController = false // Impede o player de chamar as barras do sistema

        val mediaItem = MediaItem.fromUri(url)
        miniPlayer?.setMediaItem(mediaItem)
        miniPlayer?.prepare()
        miniPlayer?.playWhenReady = true
    }

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
        cachedCategories?.let { categorias ->
            aplicarCategorias(categorias)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getLiveCategories(username, password)
            .enqueue(object : Callback<List<LiveCategory>> {
                override fun onResponse(
                    call: Call<List<LiveCategory>>,
                    response: Response<List<LiveCategory>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var categorias = response.body()!!

                        cachedCategories = categorias

                        if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                            categorias = categorias.filterNot { cat ->
                                isAdultName(cat.name)
                            }
                        }

                        aplicarCategorias(categorias)
                    } else {
                        Toast.makeText(
                            this@LiveTvActivity,
                            "Erro ao carregar categorias",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<List<LiveCategory>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@LiveTvActivity,
                        "Falha de conexão",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun aplicarCategorias(categorias: List<LiveCategory>) {
        if (categorias.isEmpty()) {
            Toast.makeText(
                this@LiveTvActivity,
                "Nenhuma categoria disponível.",
                Toast.LENGTH_SHORT
            ).show()
            rvCategories.adapter = CategoryAdapter(emptyList()) {}
            rvChannels.adapter = ChannelAdapter(emptyList(), username, password) {}
            return
        }

        categoryAdapter = CategoryAdapter(categorias) { categoria ->
            carregarCanais(categoria)
        }
        rvCategories.adapter = categoryAdapter

        carregarCanais(categorias[0])
    }

    private fun carregarCanais(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        miniPlayer?.stop() // Para o vídeo ao trocar de categoria

        val catIdStr = categoria.id.toString()

        channelsCache[catIdStr]?.let { canaisCacheados ->
            aplicarCanais(categoria, canaisCacheados)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getLiveStreams(username, password, categoryId = catIdStr)
            .enqueue(object : Callback<List<LiveStream>> {
                override fun onResponse(
                    call: Call<List<LiveStream>>,
                    response: Response<List<LiveStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        var canais = response.body()!!

                        channelsCache[catIdStr] = canais

                        if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                            canais = canais.filterNot { canal ->
                                isAdultName(canal.name)
                            }
                        }

                        aplicarCanais(categoria, canais)
                    } else {
                        Toast.makeText(
                            this@LiveTvActivity,
                            "Erro ao carregar canais",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@LiveTvActivity,
                        "Falha de conexão",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun aplicarCanais(categoria: LiveCategory, canais: List<LiveStream>) {
        tvCategoryTitle.text = categoria.name

        channelAdapter = ChannelAdapter(canais, username, password) { canal ->
            // Para o mini player antes de ir para a tela cheia
            miniPlayer?.stop()
            miniPlayer?.release()
            miniPlayer = null
            
            val intent = Intent(this@LiveTvActivity, PlayerActivity::class.java)
            intent.putExtra("stream_id", canal.id)
            intent.putExtra("stream_ext", "ts")
            intent.putExtra("stream_type", "live")
            intent.putExtra("channel_name", canal.name)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
        rvChannels.adapter = channelAdapter
    }

    // --------------------
    // ADAPTER DAS CATEGORIAS
    // --------------------
    inner class CategoryAdapter(
        private val list: List<LiveCategory>,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {

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
                holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.red_primary))
                holder.tvName.setBackgroundColor(0xFF252525.toInt())
            } else {
                holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.gray_text))
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

    // --------------------
    // ADAPTER DOS CANAIS + PREVIEW DINÂMICO
    // --------------------
    inner class ChannelAdapter(
        private val list: List<LiveStream>,
        private val username: String,
        private val password: String,
        private val onClick: (LiveStream) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private val epgCache = mutableMapOf<Int, List<EpgResponseItem>>()
        private val handler = Handler(Looper.getMainLooper())
        private var pendingRunnable: Runnable? = null

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvNow: TextView = v.findViewById(R.id.tvNow)
            val tvNext: TextView = v.findViewById(R.id.tvNext)
            val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]

            holder.tvName.text = item.name

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(160, 160)
                .priority(Priority.HIGH)
                .thumbnail(0.1f)
                .placeholder(R.drawable.bg_logo_placeholder)
                .error(R.drawable.bg_logo_placeholder)
                .centerInside()
                .into(holder.imgLogo)

            carregarEpg(holder, item)

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.itemView.alpha = if (hasFocus) 1.0f else 0.8f
                
                if (hasFocus) {
                    // Atualiza texto do EPG no Preview
                    tvPreviewEpg.text = holder.tvNow.text
                    
                    // Delay para carregar o vídeo (Evita travar ao navegar rápido)
                    pendingRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable { carregarPreview(item) }
                    pendingRunnable = r
                    handler.postDelayed(r, 800)
                }
            }

            holder.itemView.setOnClickListener { 
                pendingRunnable?.let { handler.removeCallbacks(it) }
                onClick(item) 
            }
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

        private fun carregarEpg(holder: VH, canal: LiveStream) {
            epgCache[canal.id]?.let { epg ->
                mostrarEpg(holder, epg)
                return
            }

            val epgId = canal.id.toString()

            XtreamApi.service.getShortEpg(
                user = username,
                pass = password,
                streamId = epgId,
                limit = 2
            ).enqueue(object : Callback<EpgWrapper> {
                override fun onResponse(
                    call: Call<EpgWrapper>,
                    response: Response<EpgWrapper>
                ) {
                    if (response.isSuccessful && response.body()?.epg_listings != null) {
                        val epg = response.body()!!.epg_listings!!
                        epgCache[canal.id] = epg
                        mostrarEpg(holder, epg)
                    } else {
                        holder.tvNow.text = "Programação não disponível"
                        holder.tvNext.text = ""
                    }
                }

                override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {
                    holder.tvNow.text = "Programação não disponível"
                    holder.tvNext.text = ""
                }
            })
        }

        private fun mostrarEpg(holder: VH, epg: List<EpgResponseItem>) {
            if (epg.isNotEmpty()) {
                val agora = epg[0]
                holder.tvNow.text = decodeBase64(agora.title)

                if (epg.size > 1) {
                    val proximo = epg[1]
                    holder.tvNext.text = decodeBase64(proximo.title)
                } else {
                    holder.tvNext.text = ""
                }
            } else {
                holder.tvNow.text = "Programação não disponível"
                holder.tvNext.text = ""
            }
        }

        override fun getItemCount() = list.size
    }

    override fun onStop() {
        super.onStop()
        miniPlayer?.stop()
        miniPlayer?.release()
        miniPlayer = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
