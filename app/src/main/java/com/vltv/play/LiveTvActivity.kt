package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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

// --- ADICIONADO PARA SUPORTE AOS 6 DNS E GSON ---
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject

class LiveTvActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCategoryTitle: TextView

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

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progressBar = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        // Configuração de Foco
        setupRecyclerFocus()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50) // ✅ AUMENTO DE CACHE PARA NAVEGAÇÃO RÁPIDA
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER // ✅ REMOVE TREMEDIRA NO FINAL

        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // Mantendo o GridLayoutManager (4 colunas)
        rvChannels.layoutManager = GridLayoutManager(this, 4)
        rvChannels.isFocusable = true
        rvChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvChannels.setHasFixedSize(true)
        rvChannels.setItemViewCacheSize(100) // ✅ CACHE EXTRA PARA EVITAR TRAVAMENTO NOS CANAIS

        rvCategories.requestFocus()

        carregarCategorias()
    }

    // ✅ FUNÇÃO DE VELOCIDADE ADICIONADA
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
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(
                    call: Call<ResponseBody>,
                    response: Response<ResponseBody>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        try {
                            val rawJson = response.body()!!.string()
                            val listaProcessada = mutableListOf<LiveCategory>()
                            val gson = Gson()

                            if (rawJson.trim().startsWith("[")) {
                                val listType = object : TypeToken<List<LiveCategory>>() {}.type
                                val list: List<LiveCategory> = gson.fromJson(rawJson, listType)
                                listaProcessada.addAll(list)
                            } else if (rawJson.trim().startsWith("{")) {
                                val jsonObject = JSONObject(rawJson)
                                val keys = jsonObject.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    val catJson = jsonObject.getJSONObject(key).toString()
                                    val category: LiveCategory = gson.fromJson(catJson, LiveCategory::class.java)
                                    listaProcessada.add(category)
                                }
                            }

                            var categorias: List<LiveCategory> = listaProcessada

                            cachedCategories = categorias

                            if (ParentalControlManager.isEnabled(this@LiveTvActivity)) {
                                categorias = categorias.filterNot { cat ->
                                    isAdultName(cat.name)
                                }.toMutableList() // ✅ CORREÇÃO: Converte para lista mutável para evitar erro de tipo
                            }

                            aplicarCategorias(categorias)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this@LiveTvActivity, "Erro no formato dos dados", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(
                            this@LiveTvActivity,
                            "Erro ao carregar categorias",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
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

        // Correção aqui: Converter ID para String para usar no Cache corretamente
        val catIdStr = categoria.id.toString()

        channelsCache[catIdStr]?.let { canaisCacheadas ->
            aplicarCanais(categoria, canaisCacheadas)
            preLoadChannelLogos(canaisCacheadas) // ✅ CHAMADA ADICIONADA NO CACHE
            return
        }

        progressBar.visibility = View.VISIBLE

        // Correção aqui: Passar categoryId como String
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
                        preLoadChannelLogos(canais) // ✅ CHAMADA ADICIONADA NA API
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
            val intent = Intent(this@LiveTvActivity, PlayerActivity::class.java)
            intent.putExtra("stream_id", canal.id)
            intent.putExtra("stream_ext", "ts")
            intent.putExtra("stream_type", "live")
            intent.putExtra("channel_name", canal.name)
            startActivity(intent)
        }
        rvChannels.adapter = channelAdapter
    }

    // --------------------
    // ADAPTER DAS CATEGORIAS (COM FOCO NEON)
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

            // Logica de cor original preservada, mas integrada ao FocusListener
            atualizarEstiloCategoria(holder, position == selectedPos, false)

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                atualizarEstiloCategoria(holder, selectedPos == position, hasFocus)
            }

            holder.itemView.setOnClickListener {
                notifyItemChanged(selectedPos)
                selectedPos = holder.adapterPosition
                notifyItemChanged(selectedPos)
                onClick(item)
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

    // --------------------
    // ADAPTER DOS CANAIS (COM FOCO NEON + ZOOM 1.15f)
    // --------------------
    inner class ChannelAdapter(
        private val list: List<LiveStream>,
        private val username: String,
        private val password: String,
        private val onClick: (LiveStream) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private val epgCache = mutableMapOf<Int, List<EpgResponseItem>>()

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
                .priority(Priority.HIGH)
                .placeholder(R.drawable.bg_logo_placeholder)
                .error(R.drawable.bg_logo_placeholder)
                .centerCrop()
                .into(holder.imgLogo)

            carregarEpg(holder, item)

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            // ✅ APLICAÇÃO DO FOCO NEON + ZOOM 1.15f + TEXTO MAIOR
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    holder.tvName.setTextColor(Color.YELLOW)
                    holder.tvName.textSize = 20f // ✅ AUMENTO DO NOME DO CANAL NA TV
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()
                    view.elevation = 20f
                } else {
                    holder.tvName.setTextColor(Color.WHITE)
                    holder.tvName.textSize = 16f // ✅ VOLTA AO TAMANHO PADRÃO
                    view.setBackgroundResource(0)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    view.elevation = 4f
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
