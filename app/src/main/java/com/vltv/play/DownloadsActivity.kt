package com.vltv.play

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var llEmptyState: LinearLayout // Alterado para o container do estado vazio
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO IMERSIVO
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        
        setContentView(R.layout.activity_downloads)

        rvDownloads = findViewById(R.id.rvDownloads)
        // Certifique-se de que no seu layout activity_downloads.xml o id seja llEmptyState ou ajuste aqui
        llEmptyState = findViewById(R.id.llEmptyState) 

        rvDownloads.layoutManager = LinearLayoutManager(this)
        
        // Inicializa o Adapter
        adapter = DownloadsAdapter(emptyList(), 
            onClick = { item -> 
                if (item.type == "series" && !item.isEpisodeExtra) {
                    mostrarEpisodiosDaSerie(item.name)
                } else {
                    abrirPlayerOffline(item)
                }
            },
            onLongClick = { item -> confirmarExclusao(item) }
        )
        rvDownloads.adapter = adapter

        observarBancoDeDados()
    }

    private fun observarBancoDeDados() {
        val dao = AppDatabase.getDatabase(this).streamDao()
        
        dao.getAllDownloads().observe(this, Observer { lista ->
            if (lista.isNullOrEmpty()) {
                llEmptyState.visibility = View.VISIBLE
                rvDownloads.visibility = View.GONE
            } else {
                llEmptyState.visibility = View.GONE
                rvDownloads.visibility = View.VISIBLE
                
                // ✅ LÓGICA DE AGRUPAMENTO: Agrupa episódios de série para não ficarem soltos
                val listaAgrupada = lista.groupBy { it.name }.map { entry ->
                    val itensDaSerie = entry.value
                    if (itensDaSerie.size > 1 && itensDaSerie.any { it.type == "series" }) {
                        // Cria um "header" da série
                        val header = itensDaSerie[0].copy()
                        header.isEpisodeExtra = false // Flag para identificar que é o grupo
                        header.totalEpisodesDownloaded = itensDaSerie.size
                        header
                    } else {
                        val item = itensDaSerie[0]
                        item.isEpisodeExtra = true // Item individual (filme ou ep único)
                        item
                    }
                }
                adapter.atualizarLista(listaAgrupada)
            }
        })
    }

    private fun mostrarEpisodiosDaSerie(nomeSerie: String) {
        val dao = AppDatabase.getDatabase(this).streamDao()
        dao.getAllDownloads().observe(this, object : Observer<List<DownloadEntity>> {
            override fun onChanged(lista: List<DownloadEntity>) {
                val episodios = lista.filter { it.name == nomeSerie }
                
                // Aqui você pode abrir um Dialog ou outra Activity para listar os episódios
                // Para manter simples e funcional agora, vamos usar um AlertDialog com a lista
                val nomesEpisodios = episodios.map { it.episode_name ?: "Episódio" }.toTypedArray()
                
                AlertDialog.Builder(this@DownloadsActivity)
                    .setTitle(nomeSerie)
                    .setItems(nomesEpisodios) { _, which ->
                        abrirPlayerOffline(episodios[which])
                    }
                    .show()
                
                dao.getAllDownloads().removeObserver(this)
            }
        })
    }

    private fun abrirPlayerOffline(item: DownloadEntity) {
        val file = File(item.file_path)
        
        if (!file.exists()) {
            Toast.makeText(this, "Arquivo não encontrado! Talvez tenha sido apagado.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            putExtra("stream_type", "vod_offline")
            putExtra("offline_uri", item.file_path)
            putExtra("channel_name", if (item.episode_name != null) "${item.name} - ${item.episode_name}" else item.name)
            putExtra("icon", item.image_url)
            putExtra("PROFILE_NAME", "Padrao")
        }
        startActivity(intent)
    }

    private fun confirmarExclusao(item: DownloadEntity) {
        AlertDialog.Builder(this)
            .setTitle("Excluir Download")
            .setMessage("Deseja apagar '${item.name}' do seu dispositivo?")
            .setPositiveButton("Apagar") { _, _ -> deletarArquivo(item) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deletarArquivo(item: DownloadEntity) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Apaga o arquivo físico
                val file = File(item.file_path)
                if (file.exists()) {
                    file.delete()
                }

                // 2. Apaga do Banco de Dados
                val db = AppDatabase.getDatabase(applicationContext).streamDao()
                db.deleteDownload(item.id)

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Download excluído.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- ADAPTER INTERNO ---
    class DownloadsAdapter(
        private var items: List<DownloadEntity>,
        private val onClick: (DownloadEntity) -> Unit,
        private val onLongClick: (DownloadEntity) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        fun atualizarLista(novaLista: List<DownloadEntity>) {
            items = novaLista
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvDownloadName)
            val tvStatus: TextView = v.findViewById(R.id.tvDownloadPath)
            val imgCapa: ImageView? = v.findViewById(R.id.imgPoster)
            val imgDeviceIcon: ImageView? = v.findViewById(R.id.imgDeviceIcon) // Ícone do telefone no XML
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            
            // Lógica de Nome e Agrupamento
            if (item.type == "series" && !item.isEpisodeExtra) {
                holder.tvName.text = item.name
                holder.tvStatus.text = "${item.totalEpisodesDownloaded} episódios"
            } else {
                holder.tvName.text = if (item.episode_name != null) "${item.name}\n${item.episode_name}" else item.name
            }

            // ✅ ATUALIZAÇÃO: Troca de texto por ícone de dispositivo
            if (item.status == "BAIXANDO" || item.status == "DOWNLOADING") {
                holder.tvStatus.text = "Baixando... ${item.progress}%"
                holder.tvStatus.setTextColor(android.graphics.Color.YELLOW)
                holder.imgDeviceIcon?.visibility = View.GONE
                holder.itemView.isEnabled = false
                holder.itemView.alpha = 0.5f
            } else if (item.status == "BAIXADO" || item.status == "COMPLETED") {
                // Remove o texto "Toque para assistir" e mostra o ícone do celular
                holder.tvStatus.text = if (item.type == "series" && !item.isEpisodeExtra) "${item.totalEpisodesDownloaded} episódios" else "Baixado"
                holder.tvStatus.setTextColor(android.graphics.Color.parseColor("#A6FFFFFF"))
                holder.imgDeviceIcon?.visibility = View.VISIBLE
                holder.itemView.isEnabled = true
                holder.itemView.alpha = 1.0f
            } else {
                holder.tvStatus.text = "Falha no download"
                holder.tvStatus.setTextColor(android.graphics.Color.RED)
                holder.imgDeviceIcon?.visibility = View.GONE
                holder.itemView.isEnabled = true
            }

            holder.imgCapa?.let { img ->
                Glide.with(holder.itemView.context)
                    .load(item.image_url)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(img)
            }

            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { 
                onLongClick(item)
                true
            }
            
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.bg_focus_neon)
                    v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(200).start()
                } else {
                    v.setBackgroundResource(0)
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}

// Nota: Adicione essas variáveis na sua classe DownloadEntity no arquivo DownloadEntity.kt:
// var isEpisodeExtra: Boolean = true
// var totalEpisodesDownloaded: Int = 0
