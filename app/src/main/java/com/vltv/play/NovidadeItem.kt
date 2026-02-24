package com.vltv.play

/**
 * Modelo de dados para os itens da tela de Novidades.
 * Centraliza as informações vindas do TMDb e a identificação para o servidor local.
 */
data class NovidadeItem(
    val id: Int,                    // ID original do TMDb
    val titulo: String,             // Nome do Filme ou Série
    val sinopse: String,            // Descrição/Overview
    val imagemFundoUrl: String,     // URL do Backdrop (Fundo)
    val tagline: String,            // Texto de apoio (ex: Data de estreia ou "Top 10")
    val isSerie: Boolean = false,   // Define se busca no banco de séries ou filmes
    val isEmBreve: Boolean = false, // Define se oculta os botões de ação
    val isTop10: Boolean = false,   // Identifica se pertence à aba Top 10
    val posicaoTop10: Int = 0       // Posição no ranking (1 a 10)
)
