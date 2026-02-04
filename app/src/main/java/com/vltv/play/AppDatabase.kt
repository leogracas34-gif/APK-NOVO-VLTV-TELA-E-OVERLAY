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

@Entity(tableName = "vod_streams")
data class VodEntity(
    @PrimaryKey val stream_id: Int,
    val name: String,
    val title: String?,
    val stream_icon: String?,
    val container_extension: String?,
    val rating: String?,
    val category_id: String,
    val added: Long // ✅ CAMPO ADICIONADO PARA CORRIGIR O ERRO
)

@Entity(tableName = "series_streams")
data class SeriesEntity(
    @PrimaryKey val series_id: Int,
    val name: String,
    val cover: String?,
    val rating: String?,
    val category_id: String,
    val last_modified: Long // ✅ CAMPO ADICIONADO PARA CORRIGIR O ERRO
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val category_id: String,
    val category_name: String,
    val type: String // "live", "vod", "series"
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

// --- DAO (Comandos de Acesso aos Dados) ---

@Dao
interface StreamDao {
    
    // Busca VODs recentes (Corrige erro Unresolved reference: getRecentVods)
    @Query("SELECT * FROM vod_streams ORDER BY added DESC LIMIT :limit")
    suspend fun getRecentVods(limit: Int): List<VodEntity>

    // Busca Séries recentes (Corrige erro Unresolved reference: getRecentSeries)
    @Query("SELECT * FROM series_streams ORDER BY last_modified DESC LIMIT :limit")
    suspend fun getRecentSeries(limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM live_streams WHERE name LIKE '%' || :query || '%'")
    suspend fun searchLive(query: String): List<LiveStreamEntity>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%'")
    suspend fun searchVod(query: String): List<VodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveStreams(streams: List<LiveStreamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodStreams(streams: List<VodEntity>)

    // Insere Séries (Corrige erro Unresolved reference: insertSeriesStreams)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesStreams(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    // ✅ NOVOS COMANDOS PARA O EPG INTELIGENTE (ADICIONADOS)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpg(epgList: List<EpgEntity>)

    @Query("SELECT * FROM epg_cache WHERE stream_id = :epgId LIMIT 1")
    suspend fun getEpgByChannel(epgId: String): EpgEntity?

    @Query("DELETE FROM epg_cache")
    suspend fun clearEpg()

    @Query("DELETE FROM live_streams")
    suspend fun clearLive()
}

// --- DATABASE (Configuração Central) ---

@Database(entities = [LiveStreamEntity::class, VodEntity::class, SeriesEntity::class, CategoryEntity::class, EpgEntity::class], version = 1, exportSchema = false)
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
                .fallbackToDestructiveMigration() // ✅ Evita erro se mudar a versão do banco
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
