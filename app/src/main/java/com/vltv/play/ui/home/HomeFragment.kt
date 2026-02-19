package com.vltv.play.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.vltv.play.*
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
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
        
        currentProfile = activity?.intent?.getStringExtra("PROFILE_NAME") ?: "Padrao"

        setupSingleBanner()
        setupClicks()
        carregarDadosLocaisImediato()
        sincronizarConteudoSilenciosamente()
    }

    private fun setupSingleBanner() {
        bannerAdapter = BannerAdapter(emptyList())
        binding.bannerViewPager.adapter = bannerAdapter
        binding.bannerViewPager.isUserInputEnabled = false
    }

    private fun carregarDadosLocaisImediato() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMovies = database.streamDao().getRecentVods(20)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), it.name, it.stream_icon ?: "") }

                val localSeries = database.streamDao().getRecentSeries(20)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), it.name, it.cover ?: "") }

                withContext(Dispatchers.Main) {
                    if (movieItems.isNotEmpty()) {
                        binding.rvRecentlyAdded.setHasFixedSize(true)
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

    private fun carregarContinuarAssistindoLocal() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                val vodItems = mutableListOf<VodItem>()
                val seriesMap = mutableMapOf<String, Boolean>()

                for (item in historyList) {
                    vodItems.add(VodItem(item.stream_id.toString(), item.name, item.icon ?: ""))
                    seriesMap[item.stream_id.toString()] = item.is_series
                }

                withContext(Dispatchers.Main) {
                    if (vodItems.isNotEmpty()) {
                        binding.tvContinueWatching.visibility = View.VISIBLE
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems) { selected ->
                            val isSer = seriesMap[selected.id] ?: false
                            val intent = if (isSer) Intent(requireContext(), SeriesDetailsActivity::class.java).apply { putExtra("series_id", selected.id.toIntOrNull() ?: 0) }
                            else Intent(requireContext(), DetailsActivity::class.java).apply { putExtra("stream_id", selected.id.toIntOrNull() ?: 0) }
                            intent.putExtra("name", selected.name); intent.putExtra("icon", selected.streamIcon); intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    } else {
                        binding.tvContinueWatching.visibility = View.GONE
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

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            delay(4000)
            try {
                val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                val vodResponse = URL(vodUrl).readText()
                val vodArray = org.json.JSONArray(vodResponse)
                val vodBatch = mutableListOf<VodEntity>()
                val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")

                for (i in 0 until vodArray.length()) {
                    val obj = vodArray.getJSONObject(i)
                    val nome = obj.optString("name")
                    if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                        vodBatch.add(VodEntity(
                            stream_id = obj.optInt("stream_id"),
                            name = nome,
                            title = obj.optString("name"),
                            stream_icon = obj.optString("stream_icon"),
                            container_extension = obj.optString("container_extension"),
                            category_id = obj.optString("category_id"),
                            added = obj.optLong("added")
                        ))
                    }
                    if (vodBatch.size >= 100) {
                        database.streamDao().insertVodStreams(vodBatch)
                        vodBatch.clear()
                    }
                }
                if (vodBatch.isNotEmpty()) database.streamDao().insertVodStreams(vodBatch)

                withContext(Dispatchers.Main) { carregarDadosLocaisImediato() }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun limparNomeParaTMDB(nome: String): String {
        return nome.replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT-BR|PTBR|WEB-DL|BLURAY|MKV|MP4|AVI|REPACK|H264|H265|HEVC|WEB|S\\d+E\\d+|SEASON|TEMPORADA)\\b"), "")
                   .replace(Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}|\\(.*\\d{4}.*\\)"), "")
                   .replace(Regex("\\s+"), " ")
                   .trim()
                   .take(50)
    }

    private fun buscarImagemBackgroundTMDB(nome: String, isSeries: Boolean, fallback: String, internalId: Int, targetImg: ImageView, targetLogo: ImageView, targetTitle: TextView) {
        val tipo = if (isSeries) "tv" else "movie"
        val nomeLimpo = limparNomeParaTMDB(nome)
        val query = URLEncoder.encode(nomeLimpo, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/$tipo?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&region=BR"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = URL(url).readText()
                val results = JSONObject(response).getJSONArray("results")
                if (results.length() > 0) {
                    val backdropPath = results.getJSONObject(0).optString("backdrop_path")
                    withContext(Dispatchers.Main) {
                        if (backdropPath.isNotEmpty() && backdropPath != "null") {
                            Glide.with(requireContext())
                                .load("https://image.tmdb.org/t/p/original$backdropPath")
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .into(targetImg)
                        } else {
                            Glide.with(requireContext()).load(fallback).into(targetImg)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Glide.with(requireContext()).load(fallback).into(targetImg) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class BannerAdapter(private var items: List<Any>) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {
        fun updateList(newItems: List<Any>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            return BannerViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false))
        }
        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) { if (items.isNotEmpty()) holder.bind(items[0]) }
        override fun getItemCount(): Int = items.size
        inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgBanner: ImageView = itemView.findViewById(R.id.imgBanner)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvBannerTitle)
            private val imgLogo: ImageView = itemView.findViewById(R.id.imgBannerLogo)
            private val btnPlay: View = itemView.findViewById(R.id.btnBannerPlay)

            fun bind(item: Any) {
                var title = ""; var icon = ""; var id = 0; var isSeries = false
                if (item is VodEntity) { title = item.name; icon = item.stream_icon ?: ""; id = item.stream_id; isSeries = false }
                else if (item is SeriesEntity) { title = item.name; icon = item.cover ?: ""; id = item.series_id; isSeries = true }
                
                tvTitle.text = title
                buscarImagemBackgroundTMDB(title, isSeries, icon, id, imgBanner, imgLogo, tvTitle)
                
                btnPlay.setOnClickListener {
                    val intent = if (isSeries) Intent(requireContext(), SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) }
                    else Intent(requireContext(), DetailsActivity::class.java).apply { putExtra("stream_id", id) }
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    startActivity(intent)
                }
            }
        }
    }
}
