package com.vltv.play.data

import androidx.room.*
import android.content.Context

// ==========================================
// 🚀 TABELAS OTIMIZADAS (COM ÍNDICES)
// ==========================================

// Adicionado index em category_id e name para filtros rápidos
@Entity(
    tableName = "live_streams",
    indices = [
        Index(value = ["category_id"]), 
        Index(value = ["name"])
    ]
)
data class LiveStreamEntity(
    @PrimaryKey val stream_id: Int,
    val name: String,
    val stream_icon: String?,
    val epg_channel_id: String?,
    val category_id: String
)

// Index turbo: Busca rápida por Categoria, Nome e Data de Adição
@Entity(
    tableName = "vod_streams", 
    indices = [
        Index(value = ["added"]), 
        Index(value = ["category_id"]), 
        Index(value = ["name"])
    ]
)
data class VodEntity(
    @PrimaryKey val stream_id: Int,
    val name: String,
    val title: String?,
    val stream_icon: String?,
    val container_extension: String?,
    val rating: String?,
    val category_id: String,
    val added: Long,
    val logo_url: String? = null
)

// Index turbo: Busca rápida por Categoria e Modificação
@Entity(
    tableName = "series_streams", 
    indices = [
        Index(value = ["last_modified"]), 
        Index(value = ["category_id"]),
        Index(value = ["name"])
    ]
)
data class SeriesEntity(
    @PrimaryKey val series_id: Int,
    val name: String,
    val cover: String?,
    val rating: String?,
    val category_id: String,
    val last_modified: Long,
    val logo_url: String? = null
)

@Entity(
    tableName = "watch_history", 
    primaryKeys = ["stream_id", "profile_name"],
    indices = [Index(value = ["timestamp"])] // Para ordenar histórico instantaneamente
)
data class WatchHistoryEntity(
    val stream_id: Int,
    val profile_name: String,
    val name: String,
    val icon: String?,
    val last_position: Long,
    val duration: Long,
    val is_series: Boolean,
    val timestamp: Long
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val category_id: String,
    val category_name: String,
    val type: String 
)

@Entity(tableName = "epg_cache", indices = [Index(value = ["stream_id"])])
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val stream_id: String,
    val title: String?,
    val start: String?,
    val stop: String?,
    val description: String?
)

@Entity(tableName = "downloads", indices = [Index(value = ["status"])])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val android_download_id: Long, 
    val stream_id: Int,
    val name: String, 
    val episode_name: String?, 
    val image_url: String?,
    val file_path: String, 
    val type: String, 
    val status: String, 
    val progress: Int = 0,
    val total_size: String = "0MB"
)

// ==========================================
// 🚀 DAO TURBINADO
// ==========================================

@Dao
interface StreamDao {
    
    // --- BUSCAS HOME (ULTRA RÁPIDAS) ---
    // Transaction evita travar a thread principal em leituras grandes
    @Transaction 
    @Query("SELECT * FROM vod_streams ORDER BY added DESC LIMIT :limit")
    suspend fun getRecentVods(limit: Int): List<VodEntity>

    @Query("SELECT COUNT(*) FROM vod_streams")
    suspend fun getVodCount(): Int

    @Transaction
    @Query("SELECT * FROM series_streams ORDER BY last_modified DESC LIMIT :limit")
    suspend fun getRecentSeries(limit: Int): List<SeriesEntity>

    // --- BUSCAS DE PESQUISA (OTIMIZADAS) ---
    // LIKE é pesado, mas com LIMIT ajuda a não explodir a memória
    @Query("SELECT * FROM live_streams WHERE name LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchLive(query: String): List<LiveStreamEntity>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchVod(query: String): List<VodEntity>

    // --- INSERTS EM LOTE (USE @Transaction para ser atômico e rápido) ---
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveStreams(streams: List<LiveStreamEntity>)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodStreams(streams: List<VodEntity>)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesStreams(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM live_streams")
    suspend fun clearLive()

    // --- LOGOS & UPDATES ---
    @Query("UPDATE series_streams SET logo_url = :logoUrl WHERE series_id = :id")
    suspend fun updateSeriesLogo(id: Int, logoUrl: String)

    @Query("UPDATE vod_streams SET logo_url = :logoUrl WHERE stream_id = :id")
    suspend fun updateVodLogo(id: Int, logoUrl: String)

    // --- HISTÓRICO ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWatchHistory(history: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history WHERE profile_name = :profile ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getWatchHistory(profile: String, limit: Int = 20): List<WatchHistoryEntity>

    // --- DOWNLOADS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun getAllDownloads(): androidx.lifecycle.LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE stream_id = :streamId AND type = :type LIMIT 1")
    suspend fun getDownloadByStreamId(streamId: Int, type: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE android_download_id = :downloadId")
    suspend fun updateDownloadProgress(downloadId: Long, status: String, progress: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Int)
    
    @Query("DELETE FROM downloads WHERE android_download_id = :downloadId")
    suspend fun deleteDownloadByAndroidId(downloadId: Long)

    // ✅ FUNÇÃO ADICIONADA PARA CORRIGIR O ERRO DE COMPILAÇÃO E MONITORAR DOWNLOADS ATIVOS
    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getDownloadsByStatus(status: String): List<DownloadEntity>
}

// ==========================================
// 🚀 DATABASE ENGINE
// ==========================================

@Database(
    entities = [
        LiveStreamEntity::class, 
        VodEntity::class, 
        SeriesEntity::class, 
        CategoryEntity::class, 
        EpgEntity::class, 
        WatchHistoryEntity::class, 
        DownloadEntity::class
    ], 
    version = 5, // ⚠️ SUBI A VERSÃO PARA FORÇAR A RECRIACÃO DOS ÍNDICES
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streamDao(): StreamDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vltv_play_db"
                )
                // ⚠️ Destroi e recria o banco se mudar a estrutura (Bom para performance limpa)
                .fallbackToDestructiveMigration() 
                
                // 🚀 O SEGREDO DA VELOCIDADE: Write-Ahead Logging
                // Permite leitura e escrita simultânea (Não trava a tela enquanto salva filmes)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
