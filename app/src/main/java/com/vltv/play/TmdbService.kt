package com.vltv.play.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// Modelo para receber a resposta do TMDB
data class TmdbResponse(val results: List<TmdbPerson>)
data class TmdbPerson(val title: String?, val poster_path: String?) {
    val name: String get() = title ?: ""
    val profile_path: String? get() = poster_path
}

interface TmdbApi {
    // Agora o termo 'query' é livre para enviarmos qualquer personagem
    @GET("search/movie")
    suspend fun getPopularPeople(
        @Query("api_key") apiKey: String,
        @Query("query") query: String, 
        @Query("language") language: String = "pt-BR",
        @Query("page") page: Int = 1
    ): TmdbResponse
}

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w185" // Mantido w185 para nitidez e velocidade

    // Ajustado OkHttpClient para ser mais robusto e evitar falhas de conexão
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // Aumenta tempo de conexão
        .readTimeout(30, TimeUnit.SECONDS)    // Aumenta tempo de leitura
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)       // Tenta reconectar automaticamente se falhar
        .build()

    val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    fun getFullImageUrl(path: String?): String? {
        return if (path != null) "$IMAGE_BASE_URL$path" else null
    }
}
