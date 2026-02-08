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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object DownloadHelper {

    private const val TAG = "DownloadHelper"
    
    // ✅ SEGURANÇA: Pasta e Extensão ocultas
    private const val PASTA_OCULTA = "vltv_secure_storage"
    private const val EXTENSAO_SEGURA = ".vltv"

    // Estados do Download (Compatível com sua lógica visual)
    const val STATE_BAIXAR = "BAIXAR"
    const val STATE_BAIXANDO = "BAIXANDO"
    const val STATE_BAIXADO = "BAIXADO"
    const val STATE_ERRO = "ERRO"

    // ✅ NOVA FUNÇÃO: Aceita dados de Filme e Série para o Banco
    fun iniciarDownload(
        context: Context,
        url: String,
        streamId: Int,
        nomePrincipal: String, // Nome do Filme ou da Série
        nomeEpisodio: String?, // Ex: "T01E01" (Null se for filme)
        imagemUrl: String?,
        isSeries: Boolean
    ) {
        // Rodamos em Background para o teste de URL não travar o app
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Iniciando processo: $nomePrincipal | ID: $streamId")

            // 1. Testa a URL antes (Sua lógica, agora segura em background)
            if (!testarUrl(url)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ Link indisponível/offline", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            try {
                // 2. Cria nome de arquivo seguro e único
                val tipo = if (isSeries) "series" else "movie"
                val nomeArquivoFisico = "${tipo}_${streamId}_${System.currentTimeMillis()}$EXTENSAO_SEGURA"

                val uri = Uri.parse(url)
                val request = DownloadManager.Request(uri)
                    .setTitle(if (isSeries) "$nomePrincipal - $nomeEpisodio" else nomePrincipal)
                    .setDescription("Baixando conteúdo seguro...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE) // Mostra progresso
                    .setVisibleInDownloadsUi(false) // Esconde do app "Downloads" do Android
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                
                // 🔒 SALVA NA PASTA OCULTA DO APP (Ninguém vê na galeria)
                request.setDestinationInExternalFilesDir(context, PASTA_OCULTA, nomeArquivoFisico)

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = dm.enqueue(request)

                if (downloadId == -1L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "❌ Erro ao iniciar gerenciador", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 3. Salva no BANCO DE DADOS (Substituindo o SharedPreferences)
                val caminhoCompleto = File(context.getExternalFilesDir(PASTA_OCULTA), nomeArquivoFisico).absolutePath
                
                val novoDownload = DownloadEntity(
                    android_download_id = downloadId,
                    stream_id = streamId,
                    name = nomePrincipal,
                    episode_name = nomeEpisodio,
                    image_url = imagemUrl,
                    file_path = caminhoCompleto,
                    type = tipo,
                    status = STATE_BAIXANDO
                )

                AppDatabase.getDatabase(context).streamDao().insertDownload(novoDownload)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "✅ Download iniciado: $nomePrincipal", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "ERRO CRÍTICO: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao baixar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ✅ Teste de URL simplificado e seguro
    private fun testarUrl(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "URL Test: $code")
            code in 200..399 // Aceita redirecionamentos (3xx) também
        } catch (e: Exception) {
            Log.e(TAG, "Falha no teste de URL: ${e.message}")
            false
        }
    }

    // --- RECEIVER (Atualiza o Banco quando termina) ---

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id == -1L || context == null) return

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context).streamDao()
                
                // Verifica o status real no sistema
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                
                var statusFinal = STATE_ERRO
                var progress = 0

                try {
                    dm.query(query).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusColumn >= 0) {
                                val status = cursor.getInt(statusColumn)
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    statusFinal = STATE_BAIXADO
                                    progress = 100
                                    Log.d(TAG, "Download CONCLUÍDO ID: $id")
                                } else if (status == DownloadManager.STATUS_FAILED) {
                                    statusFinal = STATE_ERRO
                                    Log.e(TAG, "Download FALHOU ID: $id")
                                }
                            }
                        }
                    }
                    
                    // Atualiza a tabela do banco
                    db.updateDownloadProgress(id, statusFinal, progress)
                    
                    // Se falhou, podemos deletar o registro ou marcar como erro
                    if (statusFinal == STATE_ERRO) {
                        // db.deleteDownloadByAndroidId(id) // Opcional: remover se falhar
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        try {
            // Compatível com Android 12+ (Flag Exported/Not Exported)
            ContextCompat.registerReceiver(
                context, 
                receiver, 
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "Receiver registrado com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro registrando receiver: ${e.message}")
        }
    }
    
    fun unregisterReceiver(context: Context) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }
}
