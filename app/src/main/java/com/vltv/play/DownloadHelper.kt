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

    // ✅ Controle do Loop de Progresso
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
                // 1. Prepara o nome e caminho
                val nomeSeguro = nomePrincipal.replace(Regex("[^a-zA-Z0-9\\.\\-]"), "_")
                val tipo = if (isSeries) "series" else "movie"
                val nomeArquivo = "${tipo}_${streamId}_${nomeSeguro}$EXTENSAO_SEGURA"

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(nomePrincipal)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setVisibleInDownloadsUi(false)
                    .setAllowedOverMetered(true)
                    .setDestinationInExternalFilesDir(context, PASTA_OCULTA, nomeArquivo)

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = dm.enqueue(request)

                // 2. Caminho Absoluto do arquivo
                val file = File(context.getExternalFilesDir(PASTA_OCULTA), nomeArquivo)

                // 3. Salva no Banco (Começa com 0%)
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

                // ✅ 4. INICIA O MONITORAMENTO DE PROGRESSO
                iniciarMonitoramento(context)

            } catch (e: Exception) {
                Log.e(TAG, "Erro: ${e.message}")
            }
        }
    }

    // ✅ NOVA FUNÇÃO: Atualiza a % no banco a cada 1 segundo
    private fun iniciarMonitoramento(context: Context) {
        if (progressJob?.isActive == true) return // Já está rodando

        progressJob = CoroutineScope(Dispatchers.IO).launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val db = AppDatabase.getDatabase(context).streamDao()
            var downloadsAtivos = true

            while (downloadsAtivos) {
                val cursor = dm.query(DownloadManager.Query()) // Pega todos
                var encontrouAtivo = false

                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        if (idIndex != -1 && statusIndex != -1) {
                            val id = cursor.getLong(idIndex)
                            val status = cursor.getInt(statusIndex)
                            
                            // Calcula Porcentagem
                            val baixado = cursor.getLong(downloadedIndex)
                            val total = cursor.getLong(totalIndex)
                            val progresso = if (total > 0) ((baixado * 100) / total).toInt() else 0

                            // Atualiza no Banco
                            if (status == DownloadManager.STATUS_RUNNING) {
                                db.updateDownloadProgress(id, STATE_BAIXANDO, progresso)
                                encontrouAtivo = true
                            } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                db.updateDownloadProgress(id, STATE_BAIXADO, 100)
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                db.updateDownloadProgress(id, STATE_ERRO, 0)
                            }
                        }
                    } while (cursor.moveToNext())
                    cursor.close()
                }

                if (!encontrouAtivo) {
                    downloadsAtivos = false // Para o loop se não tiver mais nada baixando
                }
                
                delay(1500) // Espera 1.5s antes de checar de novo
            }
        }
    }

    // Receiver de Segurança (Caso o loop falhe, isso garante o 100%)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (context != null && id != -1L) {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(context).streamDao()
                    db.updateDownloadProgress(id, STATE_BAIXADO, 100)
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
