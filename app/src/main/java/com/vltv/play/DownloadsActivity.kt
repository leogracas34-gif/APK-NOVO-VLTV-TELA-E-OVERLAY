package com.vltv.play

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        rvDownloads = findViewById(R.id.rvDownloads)
        tvEmpty = findViewById(R.id.tvEmptyDownloads)

        rvDownloads.layoutManager = LinearLayoutManager(this)

        carregarArquivos()
    }

    private fun carregarArquivos() {
        val dir: File? = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val files = dir?.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvDownloads.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvDownloads.visibility = View.VISIBLE
            rvDownloads.adapter = DownloadedFileAdapter(files) { file ->
                abrirArquivoNoPlayer(file)
            }
        }
    }

    private fun abrirArquivoNoPlayer(file: File) {
        val uri = Uri.fromFile(file)

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_type", "vod_offline")
            putExtra("offline_uri", uri.toString())
            putExtra("channel_name", file.name)
        }
        startActivity(intent)
    }

    class DownloadedFileAdapter(
        private val files: List<File>,
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<DownloadedFileAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvDownloadName)
            val tvPath: TextView = v.findViewById(R.id.tvDownloadPath)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = files[position]
            holder.tvName.text = file.name
            holder.tvPath.text = "Disponível offline"
            holder.itemView.setOnClickListener { onClick(file) }
        }

        override fun getItemCount(): Int = files.size
    }
}
