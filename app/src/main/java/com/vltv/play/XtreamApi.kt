package com.vltv.play

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ---------------------
// Modelos de Dados (CORRIGIDOS PARA COMPATIBILIDADE)
// ---------------------

data class XtreamLoginResponse(val user_info: UserInfo?, val server_info: ServerInfo?)
data class UserInfo(val username: String?, val status: String?, val exp_date: String?)
data class ServerInfo(val url: String?, val port: String?, val server_protocol: String?)

data class LiveCategory(val category_id: String, val category_name: String)

data class LiveStream(
    val stream_id: Int, 
    val name: String, 
    val stream_icon: String?, 
    val epg_channel_id: String?
) {
    // Mantém compatibilidade com quem pede .id ou .icon
    val id: Int get() = stream_id
    val icon: String? get() = stream_icon
}

data class VodStream(
    val stream_id: Int,
    val name: String,
    val title: String?,
    val stream_icon: String?,
    val container_extension: String?,
    val rating: String?
) {
    val id: Int get() = stream_id
    val icon: String? get() = stream_icon
    val extension: String? get() = container_extension
}

data class SeriesStream(
    val series_id: Int,
    val name: String,
    val cover: String?,
    val rating: String?
) {
    val id: Int get() = series_id
    val icon: String? get() = cover
}

// ... (Restante dos modelos SeriesInfoResponse, VodInfoResponse, EpgWrapper permanecem iguais)

// ---------------------
// Interface Retrofit (NOMES RESTAURADOS PARA AS ATIVIDADES ACHAREM)
// ---------------------

interface XtreamService {
    @GET("player_api.php")
    fun login(@Query("username") user: String, @Query("password") pass: String): Call<XtreamLoginResponse>

    @GET("player_api.php")
    fun getLiveCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_categories"): Call<List<LiveCategory>>

    @GET("player_api.php")
    fun getLiveStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_streams", @Query("category_id") categoryId: String): Call<List<LiveStream>>

    @GET("player_api.php")
    fun getVodCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_categories"): Call<List<LiveCategory>>

    // ✅ Restaurado nome original que a KidsActivity e VodActivity procuram
    @GET("player_api.php")
    fun getVodStreams(
        @Query("username") user: String, 
        @Query("password") pass: String, 
        @Query("action") action: String = "get_vod_streams", 
        @Query("category_id") categoryId: String = "0"
    ): Call<List<VodStream>>

    @GET("player_api.php")
    fun getAllVodStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_streams"): Call<List<VodStream>>

    @GET("player_api.php")
    fun getSeriesCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series_categories"): Call<List<LiveCategory>>

    // ✅ Restaurado nome original que a KidsActivity e SeriesActivity procuram
    @GET("player_api.php")
    fun getSeries(
        @Query("username") user: String, 
        @Query("password") pass: String, 
        @Query("action") action: String = "get_series", 
        @Query("category_id") categoryId: String = "0"
    ): Call<List<SeriesStream>>

    @GET("player_api.php")
    fun getAllSeries(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series"): Call<List<SeriesStream>>

    // ... (Manter getVodInfo, getSeriesInfoV2, getShortEpg iguais ao original)
}

object XtreamApi {
    private var retrofit: Retrofit? = null
    private var baseUrl: String = "http://tvblack.shop/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun setBaseUrl(newUrl: String) {
        baseUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        retrofit = null
    }

    val service: XtreamService get() {
        if (retrofit == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(XtreamService::class.java)
    }
}
