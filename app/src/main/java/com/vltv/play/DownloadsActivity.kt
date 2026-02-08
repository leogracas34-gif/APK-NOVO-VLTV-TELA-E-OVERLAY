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
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO IMERSIVO
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        
        setContentView(R.layout.activity_downloads)

        rvDownloads = findViewById(R.id.rvDownloads)
        tvEmpty = findViewById(R.id.tvEmptyDownloads)

        rvDownloads.layoutManager = LinearLayoutManager(this)
        
        // Inicializa o Adapter vazio
        adapter = DownloadsAdapter(emptyList(), 
            onClick = { download -> abrirPlayerOffline(download) },
            onLongClick = { download -> confirmarExclusao(download) }
        )
        rvDownloads.adapter = adapter

        observarBancoDeDados()
    }

    private fun observarBancoDeDados() {
        // Observa a tabela 'downloads' em tempo real
        AppDatabase.getDatabase(this).streamDao().getAllDownloads().observe(this, Observer { lista ->
            if (lista.isNullOrEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvDownloads.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvDownloads.visibility = View.VISIBLE
                adapter.atualizarLista(lista)
            }
        })
    }

    private fun abrirPlayerOffline(item: DownloadEntity) {
        val file = File(item.file_path)
        
        if (!file.exists()) {
            Toast.makeText(this, "Arquivo não encontrado! Talvez tenha sido apagado.", Toast.LENGTH_LONG).show()
            return
        }

        // Prepara o Intent para o PlayerActivity
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            putExtra("stream_type", "offline") // Novo tipo para o Player saber que é local
            putExtra("video_url", item.file_path) // Caminho absoluto do arquivo
            putExtra("channel_name", if (item.episode_name != null) "${item.name} - ${item.episode_name}" else item.name)
            putExtra("icon", item.image_url)
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
            val tvStatus: TextView = v.findViewById(R.id.tvDownloadPath) // Usando o ID existente para status
            val imgCapa: ImageView? = v.findViewById(R.id.imgPoster) // Tenta achar imagem se tiver no layout
            val progressBar: ProgressBar? = v.findViewById(R.id.progressBarDownload) // Se tiver barra de progresso
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            
            // Define o título (Se for série, mostra o episódio também)
            if (item.episode_name != null) {
                holder.tvName.text = "${item.name}\n${item.episode_name}"
            } else {
                holder.tvName.text = item.name
            }

            // Status e Progresso
            if (item.status == "BAIXANDO" || item.status == "DOWNLOADING") {
                holder.tvStatus.text = "Baixando... ${item.progress}%"
                holder.tvStatus.setTextColor(android.graphics.Color.YELLOW)
            } else if (item.status == "BAIXADO" || item.status == "COMPLETED") {
                holder.tvStatus.text = "Completo • Toque para assistir"
                holder.tvStatus.setTextColor(android.graphics.Color.GREEN)
            } else {
                holder.tvStatus.text = "Falha no download"
                holder.tvStatus.setTextColor(android.graphics.Color.RED)
            }

            // Tenta carregar a capa se o ImageView existir no layout 'item_download'
            // Se você não tiver ImageView no layout, ele vai ignorar sem dar erro (null safety)
            holder.imgCapa?.let { img ->
                Glide.with(holder.itemView.context)
                    .load(item.image_url)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(img)
            }

            // Clique Simples
            holder.itemView.setOnClickListener { onClick(item) }
            
            // Clique Longo (Deletar)
            holder.itemView.setOnLongClickListener { 
                onLongClick(item)
                true
            }
            
            // Efeito de Foco (Para TV Box)
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.bg_focus_neon) // Seu background de foco
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                } else {
                    v.setBackgroundResource(0)
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
