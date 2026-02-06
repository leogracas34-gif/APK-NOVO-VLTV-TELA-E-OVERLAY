package com.vltv.play

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface TmdbApi {

    @GET("search/movie")
    fun searchMovie(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = TmdbConfig.LANGUAGE,
        @Query("page") page: Int = 1
    ): Call<TmdbSearchResponse>

    companion object {
        private val retrofit: Retrofit by lazy {
            Retrofit.Builder()
                .baseUrl(TmdbConfig.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        val service: TmdbApi by lazy {
            retrofit.create(TmdbApi::class.java)
        }
    }
}
