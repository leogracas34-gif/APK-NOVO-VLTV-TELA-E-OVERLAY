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
import java.net.HttpURLConnection
import java.net.URL

object DownloadHelper {

    private const val TAG = "DownloadHelper"
    private const val PREFS_NAME = "vltv_prefs"
    private const val KEY_DM_ID_PREFIX = "dm_id_"
    private const val KEY_DL_STATE_PREFIX = "dl_state_"
    private const val KEY_DL_PROGRESS_PREFIX = "dl_progress_"

    const val STATE_BAIXAR = "BAIXAR"
    const val STATE_BAIXANDO = "BAIXANDO"
    const val STATE_BAIXADO = "BAIXADO"

    fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String,
        logicalId: String,
        type: String
    ) {
        Log.d(TAG, "Iniciando download: $url | ID: $logicalId")
        
        try {
            // ✅ TESTAR URL ANTES
            if (!testarUrl(url, context)) {
                Toast.makeText(context, "❌ URL inválida: $url", Toast.LENGTH_LONG).show()
                Log.e(TAG, "URL inválida: $url")
                return
            }

            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription("Baixando $type")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setVisibleInDownloadsUi(false)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_MOVIES,
                    fileName
                )

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            
            if (downloadId == -1L) {
                Toast.makeText(context, "❌ Erro ao iniciar download", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "DownloadManager.enqueue() retornou -1")
                return
            }

            Log.d(TAG, "Download ID: $downloadId para logicalId: $logicalId")
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_DM_ID_PREFIX + logicalId, downloadId)
                .putString(KEY_DL_STATE_PREFIX + logicalId, STATE_BAIXANDO)
                .putInt(KEY_DL_PROGRESS_PREFIX + logicalId, 0)
                .apply()

            Toast.makeText(context, "✅ Download iniciado: $fileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "ERRO no enqueueDownload: ${e.message}", e)
            Toast.makeText(context, "❌ Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ✅ TESTAR URL antes de baixar
    private fun testarUrl(urlString: String, context: Context): Boolean {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            
            val code = conn.responseCode
            Log.d(TAG, "Teste URL $urlString → HTTP $code")
            conn.disconnect()
            
            code in 200..299  // OK
        } catch (e: Exception) {
            Log.e(TAG, "Erro testando URL $urlString: ${e.message}")
            false
        }
    }

    fun getDownloadState(context: Context, logicalId: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DL_STATE_PREFIX + logicalId, STATE_BAIXAR) ?: STATE_BAIXAR
    }

    fun getDownloadProgress(context: Context, logicalId: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DL_PROGRESS_PREFIX + logicalId, 0)
    }

    fun setDownloadState(context: Context, logicalId: String, state: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DL_STATE_PREFIX + logicalId, state)
            .apply()
    }

    fun updateDownloadProgress(context: Context, logicalId: String, progress: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_DL_PROGRESS_PREFIX + logicalId, progress)
            .apply()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id == -1L || context == null) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val logicalId = prefs.all.keys
                .firstOrNull { key ->
                    key.startsWith(KEY_DM_ID_PREFIX) && prefs.getLong(key, -1L) == id
                }
                ?.removePrefix(KEY_DM_ID_PREFIX) ?: return

            Log.d(TAG, "Receiver: download $id → logicalId $logicalId")

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            
            dm.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    Log.d(TAG, "Status download $id: $status")
                    
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            setDownloadState(context, logicalId, STATE_BAIXADO)
                            updateDownloadProgress(context, logicalId, 100)
                            Log.d(TAG, "✅ Download concluído: $logicalId")
                        }
                        DownloadManager.STATUS_FAILED -> {
                            setDownloadState(context, logicalId, STATE_BAIXAR)
                            updateDownloadProgress(context, logicalId, 0)
                            Log.e(TAG, "❌ Download falhou: $logicalId")
                        }
                    }
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        try {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            Log.d(TAG, "Receiver registrado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro registrando receiver: ${e.message}")
        }
    }
}
