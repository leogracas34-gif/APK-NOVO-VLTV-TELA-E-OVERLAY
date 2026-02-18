package com.vltv.play.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Modelo para receber a resposta do TMDB
// Alterado para suportar posters de filmes (personagens)
data class TmdbResponse(val results: List<TmdbPerson>)
data class TmdbPerson(val title: String?, val poster_path: String?) {
    // Mantemos o nome original da variável no Adapter para não quebrar seu código
    val name: String get() = title ?: ""
    val profile_path: String? get() = poster_path
}

interface TmdbApi {
    // Alterado para buscar filmes/personagens em vez de atores
    @GET("search/movie")
    suspend fun getPopularPeople(
        @Query("api_key") apiKey: String,
        @Query("query") query: String = "Avengers", // Termo de busca
        @Query("language") language: String = "pt-BR",
        @Query("page") page: Int = 1
    ): TmdbResponse
}

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w200"

    // Adicionado OkHttpClient isolado para evitar conflitos com o login original
    private val okHttpClient = OkHttpClient.Builder().build()

    val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // Vinculando o cliente isolado
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    // Função auxiliar para montar o link da foto
    fun getFullImageUrl(path: String?): String? {
        return if (path != null) "$IMAGE_BASE_URL$path" else null
    }
}
