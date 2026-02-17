package com.vltv.play

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import kotlinx.coroutines.*
import java.io.File

object DownloadHelper {

    private const val TAG = "DownloadHelper"
    private const val PASTA_OCULTA = "vltv_secure_storage"
    private const val EXTENSAO_SEGURA = ".vltv"

    const val STATE_BAIXAR = "BAIXAR"
    const val STATE_BAIXANDO = "BAIXANDO"
    const val STATE_BAIXADO = "BAIXADO"
    const val STATE_ERRO = "ERRO"

    private var progressJob: Job? = null

    fun iniciarDownload(
        context: Context,
        url: String,
        streamId: Int,
        nomePrincipal: String,
        nomeEpisodio: String? = null,
        imagemUrl: String? = null,
        isSeries: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Prepara o nome e caminho (Garantindo compatibilidade com Séries)
                val nomeSeguro = nomePrincipal.replace(Regex("[^a-zA-Z0-9\\.\\-]"), "_")
                val tipo = if (isSeries) "series" else "movie"
                
                // ✅ NOME ÚNICO: Adicionado System.currentTimeMillis() para evitar conflito de arquivo se baixar rápido
                val sufixoEp = if (nomeEpisodio != null) "_${nomeEpisodio.replace(" ", "_")}" else ""
                val timestamp = System.currentTimeMillis()
                val nomeArquivo = "${tipo}_${streamId}${sufixoEp}_${timestamp}$EXTENSAO_SEGURA"

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(if (nomeEpisodio != null) "$nomePrincipal - $nomeEpisodio" else nomePrincipal)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setVisibleInDownloadsUi(false)
                    .setAllowedOverMetered(true)
                    // ✅ IMPORTANTE: Deixa o Android gerenciar a criação do arquivo de forma isolada
                    .setDestinationInExternalFilesDir(context, PASTA_OCULTA, nomeArquivo)

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = dm.enqueue(request)

                val file = File(context.getExternalFilesDir(PASTA_OCULTA), nomeArquivo)

                val entity = DownloadEntity(
                    android_download_id = downloadId,
                    stream_id = streamId,
                    name = nomePrincipal,
                    episode_name = nomeEpisodio,
                    image_url = imagemUrl,
                    file_path = file.absolutePath,
                    type = tipo,
                    status = STATE_BAIXANDO,
                    progress = 0,
                    total_size = "Carregando..."
                )
                AppDatabase.getDatabase(context).streamDao().insertDownload(entity)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download iniciado...", Toast.LENGTH_SHORT).show()
                }

                iniciarMonitoramento(context)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar download: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao preparar download.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun iniciarMonitoramento(context: Context) {
        if (progressJob?.isActive == true) return 

        progressJob = CoroutineScope(Dispatchers.IO).launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val db = AppDatabase.getDatabase(context).streamDao()
            
            var continuarMonitorando = true

            while (continuarMonitorando) {
                // ✅ CONSULTA DINÂMICA: Filtramos apenas pelos IDs que estão no seu banco como BAIXANDO
                val listaNoDb = db.getDownloadsByStatus(STATE_BAIXANDO)
                
                if (listaNoDb.isEmpty()) {
                    continuarMonitorando = false
                    break
                }

                var encontrouAtivoNoAndroid = false

                listaNoDb.forEach { entity ->
                    val query = DownloadManager.Query().setFilterById(entity.android_download_id)
                    val cursor = dm.query(query)

                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        if (statusIndex != -1) {
                            val status = cursor.getInt(statusIndex)
                            val baixado = cursor.getLong(downloadedIndex)
                            val total = cursor.getLong(totalIndex)
                            val progresso = if (total > 0) ((baixado * 100) / total).toInt() else 0

                            when (status) {
                                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                                    db.updateDownloadProgress(entity.android_download_id, STATE_BAIXANDO, progresso)
                                    encontrouAtivoNoAndroid = true
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    db.updateDownloadProgress(entity.android_download_id, STATE_BAIXADO, 100)
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    db.updateDownloadProgress(entity.android_download_id, STATE_ERRO, 0)
                                }
                            }
                        }
                        cursor.close()
                    }
                }

                if (!encontrouAtivoNoAndroid) {
                    // Pequena pausa extra antes de encerrar para dar tempo de novos downloads entrarem
                    delay(2000)
                    val checagemFinal = db.getDownloadsByStatus(STATE_BAIXANDO)
                    if (checagemFinal.isEmpty()) continuarMonitorando = false
                }
                
                delay(1200) 
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (context != null && id != -1L) {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(context).streamDao()
                    db.updateDownloadProgress(id, STATE_BAIXADO, 100)
                    withContext(Dispatchers.Main) {
                        iniciarMonitoramento(context)
                    }
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {}
    }
    
    fun unregisterReceiver(context: Context) {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
    }
}
