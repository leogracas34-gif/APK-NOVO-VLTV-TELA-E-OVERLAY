package com.vltv.play.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.vltv.play.*
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.databinding.FragmentHomeBinding
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    private var currentProfile: String = "Padrao"
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    
    private var listaCompletaParaSorteio: List<Any> = emptyList()
    private lateinit var bannerAdapter: BannerAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Recupera o perfil vindo da Activity
        currentProfile = activity?.intent?.getStringExtra("PROFILE_NAME") ?: "Padrao"

        setupSingleBanner()
        setupClicks()
        carregarDadosLocaisImediato()
        sincronizarConteudoSilenciosamente()
    }

    private fun setupSingleBanner() {
        bannerAdapter = BannerAdapter(emptyList())
        binding.bannerViewPager?.adapter = bannerAdapter
        binding.bannerViewPager?.isUserInputEnabled = false
    }

    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMovies = database.streamDao().getRecentVods(20)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }

                withContext(Dispatchers.Main) {
                    if (movieItems.isNotEmpty()) {
                        binding.rvRecentlyAdded.setHasFixedSize(true)
                        binding.rvRecentlyAdded.setItemViewCacheSize(20)
                        binding.rvRecentlyAdded.adapter = HomeRowAdapter(movieItems) { selectedItem ->
                            val intent = Intent(requireContext(), DetailsActivity::class.java)
                            intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", false)
                            startActivity(intent)
                        }
                    }
                    if (seriesItems.isNotEmpty()) {
                        binding.rvRecentSeries.setHasFixedSize(true)
                        binding.rvRecentSeries.setItemViewCacheSize(20)
                        binding.rvRecentSeries.adapter = HomeRowAdapter(seriesItems) { selectedItem ->
                            val intent = Intent(requireContext(), SeriesDetailsActivity::class.java)
                            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", true)
                            startActivity(intent)
                        }
                    }
                    listaCompletaParaSorteio = (localMovies + localSeries)
                    sortearBannerUnico()
                    carregarContinuarAssistindoLocal()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun sortearBannerUnico() {
        if (listaCompletaParaSorteio.isNotEmpty()) {
            val itemSorteado = listaCompletaParaSorteio.random()
            bannerAdapter.updateList(listOf(itemSorteado))
        }
    }

    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                val vodItems = mutableListOf<VodItem>()
                val seriesMap = mutableMapOf<String, Boolean>()
                val seriesJaAdicionadas = mutableSetOf<String>()

                for (item in historyList) {
                    var finalId = item.stream_id.toString()
                    var finalName = item.name
                    var finalIcon = item.icon ?: ""
                    val isSeries = item.is_series

                    if (isSeries) {
                        var cleanName = item.name.replace(Regex("(?i)^(S\\d+E\\d+|T\\d+E\\d+|\\d+x\\d+|E\\d+)\\s*(-|:)?\\s*"), "")
                        if (cleanName.contains(":")) cleanName = cleanName.substringBefore(":")
                        cleanName = cleanName.replace(Regex("(?i)\\s+(S\\d+|T\\d+|E\\d+|Ep\\d+|Temporada|Season|Episode|Capitulo|\\d+x\\d+).*"), "").trim()

                        val cursor = database.openHelper.writableDatabase.query(
                            "SELECT series_id, name, cover FROM series_streams WHERE name LIKE ? LIMIT 1", 
                            arrayOf("%$cleanName%")
                        )
                        if (cursor.moveToFirst()) {
                            val realSeriesId = cursor.getInt(0).toString()
                            if (seriesJaAdicionadas.contains(realSeriesId)) { cursor.close(); continue }
                            finalId = realSeriesId
                            finalName = cursor.getString(1)
                            finalIcon = cursor.getString(2)
                            seriesJaAdicionadas.add(realSeriesId)
                        }
                        cursor.close()
                    }
                    vodItems.add(VodItem(finalId, finalName, finalIcon))
                    seriesMap[finalId] = isSeries
                }

                withContext(Dispatchers.Main) {
                    val tvTitle = binding.root.findViewById<TextView>(R.id.tvContinueWatching)
                    if (vodItems.isNotEmpty()) {
                        tvTitle?.visibility = View.VISIBLE
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems) { selected ->
                            val isSer = seriesMap[selected.id] ?: false
                            val intent = if (isSer) Intent(requireContext(), SeriesDetailsActivity::class.java).apply { putExtra("series_id", selected.id.toIntOrNull() ?: 0) }
                            else Intent(requireContext(), DetailsActivity::class.java).apply { putExtra("stream_id", selected.id.toIntOrNull() ?: 0) }
                            intent.putExtra("name", selected.name); intent.putExtra("icon", selected.streamIcon); intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    } else {
                        tvTitle?.visibility = View.GONE
                        binding.rvContinueWatching.visibility = View.GONE
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun sincronizarConteudoSilenciosamente() {
        val prefs = requireContext().getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", "") ?: ""; val user = prefs.getString("username", "") ?: ""; val pass = prefs.getString("password", "") ?: ""
        if (dns.isEmpty() || user.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            delay(4000)
            try {
                // Lógica de VOD, Séries e Live idêntica à sua original...
                // (Omitido aqui por brevidade, mas você deve manter o bloco completo que estava na Activity)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupClicks() {
        binding.cardLiveTv.setOnClickListener {
            startActivity(Intent(requireContext(), LiveTvActivity::class.java).apply { putExtra("SHOW_PREVIEW", true); putExtra("PROFILE_NAME", currentProfile) })
        }
        binding.cardMovies.setOnClickListener {
            startActivity(Intent(requireContext(), VodActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", currentProfile) })
        }
        binding.cardSeries.setOnClickListener {
            startActivity(Intent(requireContext(), SeriesActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", currentProfile) })
        }
        binding.cardKids.setOnClickListener {
            startActivity(Intent(requireContext(), KidsActivity::class.java).apply { putExtra("SHOW_PREVIEW", false); putExtra("PROFILE_NAME", "Kids") })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Inclua aqui as funções auxiliares: limparNomeParaTMDB, buscarImagemBackgroundTMDB, buscarLogoOverlayHome e a Inner Class BannerAdapter
    // Todas exatamente como estavam na sua HomeActivity.kt
    
    private fun limparNomeParaTMDB(nome: String): String {
        return nome.replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT-BR|PTBR|WEB-DL|BLURAY|MKV|MP4|AVI|REPACK|H264|H265|HEVC|WEB|S\\d+E\\d+|SEASON|TEMPORADA)\\b"), "")
                   .replace(Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}|\\(.*\\d{4}.*\\)"), "")
                   .replace(Regex("\\s+"), " ")
                   .trim()
                   .take(50)
    }

    inner class BannerAdapter(private var items: List<Any>) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {
        fun updateList(newItems: List<Any>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false)
            return BannerViewHolder(view)
        }
        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) { if (items.isNotEmpty()) holder.bind(items[0]) }
        override fun getItemCount(): Int = items.size
        inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgBanner: ImageView = itemView.findViewById(R.id.imgBanner)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvBannerTitle)
            private val imgLogo: ImageView = itemView.findViewById(R.id.imgBannerLogo)
            private val btnPlay: View = itemView.findViewById(R.id.btnBannerPlay)

            fun bind(item: Any) {
                // Lógica de Bind idêntica à sua original...
            }
        }
    }
}
