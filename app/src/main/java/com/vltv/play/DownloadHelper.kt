package com.vltv.play

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object DownloadHelper {

    private const val TAG = "DownloadHelper"
    
    // ✅ Pasta Segura e Extensão Oculta
    private const val PASTA_OCULTA = "vltv_secure_storage"
    private const val EXTENSAO_SEGURA = ".vltv" // Arquivo não aparece na galeria

    // Estados
    const val STATE_BAIXAR = "BAIXAR"
    const val STATE_BAIXANDO = "BAIXANDO"
    const val STATE_BAIXADO = "BAIXADO"
    const val STATE_ERRO = "ERRO"

    /**
     * Inicia o download de forma assíncrona e segura.
     * Compatível com filmes e séries.
     */
    fun iniciarDownload(
        context: Context,
        url: String,
        streamId: Int,
        nomePrincipal: String,
        nomeEpisodio: String? = null, // Pode ser nulo se for filme
        imagemUrl: String? = null,
        isSeries: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Iniciando Download: $nomePrincipal | URL: $url")

                // 1. Sanitização do Nome do Arquivo (Remove caracteres proibidos)
                val nomeSeguro = nomePrincipal.replace(Regex("[^a-zA-Z0-9\\.\\-]"), "_")
                val tipo = if (isSeries) "series" else "movie"
                val nomeArquivoFisico = "${tipo}_${streamId}_${nomeSeguro}$EXTENSAO_SEGURA"

                // 2. Configura a Requisição do Download Manager
                val uri = Uri.parse(url)
                val request = DownloadManager.Request(uri)
                    .setTitle(if (isSeries && nomeEpisodio != null) "$nomePrincipal - $nomeEpisodio" else nomePrincipal)
                    .setDescription("Baixando conteúdo...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setVisibleInDownloadsUi(false) // Esconde do app de Arquivos padrão
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                
                // 3. Define o local de salvamento (Pasta do App - Não requer permissão de armazenamento no Android 10+)
                // O arquivo será salvo em: /Android/data/com.vltv.play/files/vltv_secure_storage/
                request.setDestinationInExternalFilesDir(context, PASTA_OCULTA, nomeArquivoFisico)

                // 4. Enfileira o Download
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = dm.enqueue(request)

                // 5. Salva no Banco de Dados (Room) para persistência
                val diretorio = context.getExternalFilesDir(PASTA_OCULTA)
                val arquivo = File(diretorio, nomeArquivoFisico)
                
                val novoDownload = DownloadEntity(
                    android_download_id = downloadId,
                    stream_id = streamId,
                    name = nomePrincipal,
                    episode_name = nomeEpisodio,
                    image_url = imagemUrl,
                    file_path = arquivo.absolutePath,
                    type = tipo,
                    status = STATE_BAIXANDO, // Começa como baixando
                    progress = 0,
                    total_size = "Calculando..."
                )

                AppDatabase.getDatabase(context).streamDao().insertDownload(novoDownload)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "✅ Download iniciado!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar download: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao baixar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- RECEIVER (Escuta quando o download termina) ---
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id == -1L || context == null) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context).streamDao()
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(id)

                    dm.query(query).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)

                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    db.updateDownloadStatus(id, STATE_BAIXADO)
                                    db.updateDownloadProgress(id, 100)
                                    Log.d(TAG, "Download Concluído ID: $id")
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    db.updateDownloadStatus(id, STATE_ERRO)
                                    Log.e(TAG, "Download Falhou ID: $id")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no Receiver: ${e.message}")
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(
                context, 
                receiver, 
                filter, 
                ContextCompat.RECEIVER_EXPORTED // Necessário para Android 13+
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar receiver: ${e.message}")
        }
    }
    
    fun unregisterReceiver(context: Context) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }
}
