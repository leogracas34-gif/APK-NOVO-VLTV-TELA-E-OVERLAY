package com.vltv.play

/**
 * Modelo de dados para os itens da tela de Novidades.
 * Sincronizado com StreamDao: usa stream_id para Filmes e series_id para Séries.
 */
data class NovidadeItem(
    val idTMDB: Int,                // ID vindo da API externa
    val stream_id: Int = 0,         // ID do seu banco para Filmes (VodEntity)
    val series_id: Int = 0,         // ID do seu banco para Séries (SeriesEntity)
    val titulo: String,
    val sinopse: String,
    val imagemFundoUrl: String,     // Backdrop para Bombando/Top10, Poster para Em Breve
    val tagline: String,            // Data de estreia ou posição no Top 10
    val isSerie: Boolean = false,
    val isEmBreve: Boolean = false,
    val isTop10: Boolean = false,
    val posicaoTop10: Int = 0
)
