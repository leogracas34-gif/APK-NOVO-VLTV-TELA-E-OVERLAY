package com.vltv.play

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody // ADICIONADO PARA SUPORTE AOS 6 DNS
import okhttp3.dnsoverhttps.DnsOverHttps
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.InetAddress
import java.util.concurrent.TimeUnit

// ---------------------
// Modelos de Dados (Mapeamento de Compatibilidade - MANTIDOS INTACTOS)
// ---------------------

data class XtreamLoginResponse(val user_info: UserInfo?, val server_info: ServerInfo?)
data class UserInfo(val username: String?, val status: String?, val exp_date: String?)
data class ServerInfo(val url: String?, val port: String?, val server_protocol: String?)

data class LiveCategory(val category_id: String, val category_name: String) {
    val id: String get() = category_id
    val name: String get() = category_name
}

data class LiveStream(
    val stream_id: Int, 
    val name: String, 
    val stream_icon: String?, 
    val epg_channel_id: String?
) {
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

data class EpgWrapper(val epg_listings: List<EpgResponseItem>?)
data class EpgResponseItem(
    val id: String?, 
    val epg_id: String?, 
    val title: String?, 
    val lang: String?, 
    val start: String?, 
    val end: String?, 
    val stop: String?,
    val description: String?, 
    val channel_id: String?, 
    val start_timestamp: String?, 
    val stop_timestamp: String?
)

data class SeriesInfoResponse(val episodes: Map<String, List<EpisodeStream>>?)
data class EpisodeStream(
    val id: String, 
    val title: String, 
    val container_extension: String?, 
    val season: Int, 
    val episode_num: Int, 
    val info: EpisodeInfo?
)
data class EpisodeInfo(val plot: String?, val duration: String?, val movie_image: String?)

data class VodInfoResponse(val info: VodInfoData?)
data class VodInfoData(val plot: String?, val genre: String?, val director: String?, val cast: String?, val releasedate: String?, val rating: String?, val movie_image: String?)

// ---------------------
// Interface Retrofit (ATUALIZADA PARA COMPATIBILIDADE DE CATEGORIAS)
// ---------------------

interface XtreamService {
    @GET("player_api.php")
    fun login(@Query("username") user: String, @Query("password") pass: String): Call<XtreamLoginResponse>

    // ✅ ATUALIZADO: ResponseBody permite ler List ou Object nos 6 DNS
    @GET("player_api.php")
    fun getLiveCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_categories"): Call<ResponseBody>

    @GET("player_api.php")
    fun getLiveStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_live_streams", @Query("category_id") categoryId: String): Call<List<LiveStream>>

    // ✅ ATUALIZADO: ResponseBody permite ler List ou Object nos 6 DNS
    @GET("player_api.php")
    fun getVodCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_categories"): Call<ResponseBody>

    @GET("player_api.php")
    fun getVodStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_streams", @Query("category_id") categoryId: String): Call<List<VodStream>>

    @GET("player_api.php")
    fun getAllVodStreams(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_streams"): Call<List<VodStream>>

    @GET("player_api.php")
    fun getVodInfo(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_vod_info", @Query("vod_id") vodId: Int): Call<VodInfoResponse>

    // ✅ ATUALIZADO: ResponseBody permite ler List ou Object nos 6 DNS
    @GET("player_api.php")
    fun getSeriesCategories(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series_categories"): Call<ResponseBody>

    @GET("player_api.php")
    fun getSeries(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series", @Query("category_id") categoryId: String): Call<List<SeriesStream>>

    @GET("player_api.php")
    fun getAllSeries(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series"): Call<List<SeriesStream>>

    @GET("player_api.php")
    fun getSeriesInfoV2(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_series_info", @Query("series_id") seriesId: Int): Call<SeriesInfoResponse>

    @GET("player_api.php")
    fun getShortEpg(@Query("username") user: String, @Query("password") pass: String, @Query("action") action: String = "get_short_epg", @Query("stream_id") streamId: String, @Query("limit") limit: Int = 2): Call<EpgWrapper>
}

// 🔥 OPÇÃO A: VPN INTERCEPTOR REFORÇADO (ANTI-BLOQUEIO)
class VpnInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        val requestWithHeaders = originalRequest.newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Connection", "keep-alive")
            .build()
            
        return chain.proceed(requestWithHeaders)
    }
}

object XtreamApi {
    private var retrofit: Retrofit? = null
    
    // ✅ SERVIDORES PRINCIPAIS
    private var baseUrl: String = "http://tvblack.shop/"
    private val DNS2 = "http://redeinternadestiny.top/"
    private val DNS3 = "http://fibercdn.sbs/"
    private val DNS4 = "http://blackstartv.shop/"
    private val DNS5 = "http://blackdns.shop/"
    private val DNS6 = "http://blackdeluxe.shop/"

    // 🔥 OPÇÃO B: DNS SEGURO (DNS OVER HTTPS) - GOOGLE E CLOUDFLARE
    private val safeDns: Dns by lazy {
        val bootstrapClient = OkHttpClient.Builder().build()
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
                InetAddress.getByName("1.1.1.1")
            ))
            .build()
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(safeDns) // ✅ ATIVA DNS SEGURO CONTRA BLOQUEIO DE ISP
            .addInterceptor(VpnInterceptor()) // ✅ ATIVA VPN / CABEÇALHOS CAMUFLADOS
            .build()
    }

    fun setBaseUrl(newUrl: String) {
        if (newUrl.isEmpty()) return
        val urlFormatada = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        if (baseUrl != urlFormatada) {
            baseUrl = urlFormatada
            retrofit = null
        }
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
