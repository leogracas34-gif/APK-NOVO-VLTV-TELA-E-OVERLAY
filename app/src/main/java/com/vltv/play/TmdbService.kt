package com.vltv.play.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Modelo para receber a resposta do TMDB
data class TmdbResponse(val results: List<TmdbPerson>)
data class TmdbPerson(val name: String, val profile_path: String?)

interface TmdbApi {
    @GET("person/popular")
    suspend fun getPopularPeople(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "pt-BR",
        @Query("page") page: Int = 1
    ): TmdbResponse
}

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w200"

    val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    // Função auxiliar para montar o link da foto
    fun getFullImageUrl(path: String?): String? {
        return if (path != null) "$IMAGE_BASE_URL$path" else null
    }
}
