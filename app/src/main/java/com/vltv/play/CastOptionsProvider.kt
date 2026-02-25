package com.vltv.play

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.CastMediaControlIntent

/**
 * Esta classe fornece as configurações necessárias para o Google Cast funcionar.
 * Sem ela registrada no Manifest, o botão de Cast não consegue localizar dispositivos na rede.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        // Usamos o Receiver ID padrão (DEFAULT_MEDIA_RECEIVER) que aceita quase todos os formatos de vídeo
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
