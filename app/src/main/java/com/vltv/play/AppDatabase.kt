package com.vltv.play.data

import androidx.room.*
import android.content.Context

// --- ENTITIES (Tabelas do Banco) ---

@Entity(tableName = "live_streams")
data class LiveStreamEntity(
    @PrimaryKey val stream_id: Int,
    val name: String,
    val stream_icon: String?,
    val epg_channel_id: String?,
    val category_id: String
)

// ✅ Index para carregar Home instantânea
@Entity(tableName = "vod_streams", indices = [Index(value = ["added"])])
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

// ✅ Index para carregar Home instantânea
@Entity(tableName = "series_streams", indices = [Index(value = ["last_modified"])])
data class SeriesEntity(
    @PrimaryKey val series_id: Int,
    val name: String,
    val cover: String?,
    val rating: String?,
    val category_id: String,
    val last_modified: Long,
    val logo_url: String? = null
)

@Entity(tableName = "watch_history", primaryKeys = ["stream_id", "profile_name"])
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

@Entity(tableName = "epg_cache")
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val stream_id: String,
    val title: String?,
    val start: String?,
    val stop: String?,
    val description: String?
)

// ==========================================
// 🚀 NOVA TABELA: DOWNLOADS SEGUROS
// ==========================================
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val android_download_id: Long, // ID do sistema para cancelar/monitorar
    val stream_id: Int,
    val name: String, // "Vingadores" ou "Breaking Bad"
    val episode_name: String?, // "T01E05 - O Começo" (Null se for filme)
    val image_url: String?,
    val file_path: String, // Caminho secreto (/Android/data/...)
    val type: String, // "movie" ou "series"
    val status: String, // "DOWNLOADING", "COMPLETED", "FAILED"
    val progress: Int = 0,
    val total_size: String = "0MB"
)

// --- DAO (Comandos de Acesso aos Dados) ---

@Dao
interface StreamDao {
    
    // --- BUSCAS OTIMIZADAS HOME ---
    @Query("SELECT * FROM vod_streams ORDER BY added DESC LIMIT :limit")
    suspend fun getRecentVods(limit: Int): List<VodEntity>

    @Query("SELECT * FROM series_streams ORDER BY last_modified DESC LIMIT :limit")
    suspend fun getRecentSeries(limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM live_streams WHERE name LIKE '%' || :query || '%'")
    suspend fun searchLive(query: String): List<LiveStreamEntity>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%'")
    suspend fun searchVod(query: String): List<VodEntity>

    // --- INSERTS PADRÃO ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveStreams(streams: List<LiveStreamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodStreams(streams: List<VodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesStreams(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM live_streams")
    suspend fun clearLive()

    // --- LOGOS ---
    @Query("UPDATE series_streams SET logo_url = :logoUrl WHERE series_id = :id")
    suspend fun updateSeriesLogo(id: Int, logoUrl: String)

    @Query("UPDATE vod_streams SET logo_url = :logoUrl WHERE stream_id = :id")
    suspend fun updateVodLogo(id: Int, logoUrl: String)

    // --- HISTÓRICO ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWatchHistory(history: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history WHERE profile_name = :profile ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getWatchHistory(profile: String, limit: Int = 20): List<WatchHistoryEntity>

    // ==========================================
    // 🚀 COMANDOS DE DOWNLOAD
    // ==========================================
    
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
}

// --- DATABASE ---

// ✅ Versão 4 para incluir a tabela de Downloads
@Database(entities = [LiveStreamEntity::class, VodEntity::class, SeriesEntity::class, CategoryEntity::class, EpgEntity::class, WatchHistoryEntity::class, DownloadEntity::class], version = 4, exportSchema = false)
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
                .fallbackToDestructiveMigration() // Recria o banco com a nova tabela
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
