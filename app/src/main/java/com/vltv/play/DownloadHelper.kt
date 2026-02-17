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
                
                // ✅ Ajuste no nome do arquivo para evitar erro de "Arquivo não encontrado"
                val sufixoEp = if (nomeEpisodio != null) "_${nomeEpisodio.replace(" ", "_")}" else ""
                val nomeArquivo = "${tipo}_${streamId}${sufixoEp}_${nomeSeguro}$EXTENSAO_SEGURA"

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(if (nomeEpisodio != null) "$nomePrincipal - $nomeEpisodio" else nomePrincipal)
                    // ✅ VISIBILITY_VISIBLE: Aparece progresso e some ao terminar (Não fica preso)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setVisibleInDownloadsUi(false)
                    .setAllowedOverMetered(true)
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

                // Chamada do monitoramento imediato
                iniciarMonitoramento(context)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar download: ${e.message}")
            }
        }
    }

    private fun iniciarMonitoramento(context: Context) {
        // Se já estiver rodando, não criamos outro, mas garantimos que ele continue ativo
        if (progressJob?.isActive == true) return 

        progressJob = CoroutineScope(Dispatchers.IO).launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val db = AppDatabase.getDatabase(context).streamDao()
            
            // Mantemos o loop enquanto houver algo com status BAIXANDO no seu Banco de Dados
            var continuarMonitorando = true

            while (continuarMonitorando) {
                val cursor = dm.query(DownloadManager.Query())
                var aindaTemDownloadRodandoNoAndroid = false

                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        if (idIndex != -1 && statusIndex != -1) {
                            val id = cursor.getLong(idIndex)
                            val status = cursor.getInt(statusIndex)
                            
                            val baixado = cursor.getLong(downloadedIndex)
                            val total = cursor.getLong(totalIndex)
                            val progresso = if (total > 0) ((baixado * 100) / total).toInt() else 0

                            // ✅ Atualização Assertiva baseada no status do DownloadManager
                            when (status) {
                                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                                    db.updateDownloadProgress(id, STATE_BAIXANDO, progresso)
                                    aindaTemDownloadRodandoNoAndroid = true
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    db.updateDownloadProgress(id, STATE_BAIXADO, 100)
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    db.updateDownloadProgress(id, STATE_ERRO, 0)
                                }
                            }
                        }
                    } while (cursor.moveToNext())
                    cursor.close()
                }

                // ✅ Verificação Assertiva: Conferimos no SEU banco de dados se ainda existem itens "BAIXANDO"
                // Isso evita que o Job morra se o Android demorar a iniciar o processo
                val ativosNoDb = db.getDownloadsByStatus(STATE_BAIXANDO)
                if (ativosNoDb.isEmpty() && !aindaTemDownloadRodandoNoAndroid) {
                    continuarMonitorando = false
                }
                
                delay(1000) // Reduzido para 1s para ser mais "instantâneo" na UI
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
                    // Ao terminar um, tentamos rodar o monitoramento para garantir que outros na fila sigam atualizando
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
