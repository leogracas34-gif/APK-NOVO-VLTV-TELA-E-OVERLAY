package com.vltv.play.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500" // w500 para melhor qualidade

    // Criamos um cliente de rede separado para não dar conflito com o seu login
    private val okHttpClient = OkHttpClient.Builder().build()

    val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    // Monta o link completo da imagem do herói
    fun getFullImageUrl(path: String?): String? {
        return if (path.isNullOrEmpty()) null else "$IMAGE_BASE_URL$path"
    }
}
