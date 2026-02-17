package com.vltv.play

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.ArrayList

// ✅ IMPORTS NECESSÁRIOS
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.WatchHistoryEntity

// ✅ FIREBASE
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loading: View
    private lateinit var tvChannelName: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnAspect: ImageButton
    private lateinit var topBar: View

    private lateinit var nextEpisodeContainer: View
    private lateinit var tvNextEpisodeTitle: TextView
    private lateinit var btnPlayNextEpisode: Button

    private var player: ExoPlayer? = null

    private var streamId = 0
    private var streamExtension = "ts"
    private var streamType = "live"
    private var nextStreamId: Int = 0
    private var nextChannelName: String? = null
    private var startPositionMs: Long = 0L
    
    // ✅ VARIÁVEL PARA O PERFIL ATUAL
    private var currentProfile: String = "Padrao"

    private var offlineUri: String? = null

    // MOCHILA DE EPISÓDIOS
    private var episodeList = ArrayList<Int>()

    // Lista de Backup
    private val serverBackupList = listOf(
        "http://tvblack.shop",
        "http://firewallnaousardns.xyz:80",
        "http://fibercdn.sbs"
    )

    // Lista Ativa
    private val activeServerList = mutableListOf<String>()

    private var serverIndex = 0
    private val extensoesTentativa = mutableListOf<String>()
    private var extIndex = 0

    private val USER_AGENT = "IPTVSmartersPro"

    // ✅ INSTÂNCIA DO BANCO DE DADOS ROOM
    private val database by lazy { AppDatabase.getDatabase(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val nextChecker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (streamType == "series" && nextStreamId != 0) {
                val dur = p.duration
                val pos = p.currentPosition
                if (dur > 0) {
                    val progress = pos.toFloat() / dur.toFloat()
                    if (progress >= 0.98f) {
                        val remaining = dur - pos
                        val seconds = (remaining / 1000L).toInt()
                        tvNextEpisodeTitle.text = "Próximo episódio em ${seconds}s"
                        
                        if (nextEpisodeContainer.visibility != View.VISIBLE) {
                            nextEpisodeContainer.visibility = View.VISIBLE
                            btnPlayNextEpisode.requestFocus()
                        }
                        
                        if (remaining <= 1000L) {
                            nextEpisodeContainer.visibility = View.GONE
                        }
                    } else {
                        nextEpisodeContainer.visibility = View.GONE
                    }
                }
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        btnAspect = findViewById(R.id.btnAspect)
        topBar = findViewById(R.id.topBar)

        nextEpisodeContainer = findViewById(R.id.nextEpisodeContainer)
        tvNextEpisodeTitle = findViewById(R.id.tvNextEpisodeTitle)
        btnPlayNextEpisode = findViewById(R.id.btnPlayNextEpisode)

        btnPlayNextEpisode.isFocusable = true
        btnPlayNextEpisode.isFocusableInTouchMode = true
        
        // ✅ ALTERAÇÃO: Removido o efeito de escala (Zoom) para manter o formato original
        btnPlayNextEpisode.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.setBackgroundResource(R.drawable.bg_focus_neon)
                btnPlayNextEpisode.setTextColor(Color.WHITE)
            } else {
                view.setBackgroundResource(0)
                btnPlayNextEpisode.setTextColor(Color.WHITE)
            }
        }

        streamId = intent.getIntExtra("stream_id", 0)
        streamExtension = intent.getStringExtra("stream_ext") ?: "ts"
        streamType = intent.getStringExtra("stream_type") ?: "live"
        startPositionMs = intent.getLongExtra("start_position_ms", 0L)
        nextStreamId = intent.getIntExtra("next_stream_id", 0)
        nextChannelName = intent.getStringExtra("next_channel_name")

        currentProfile = intent.getStringExtra("PROFILE_NAME") ?: "Padrao"

        val listaExtra = intent.getIntegerArrayListExtra("episode_list")
        if (listaExtra != null) {
            episodeList = listaExtra
        }

        calcularProximoEpisodioAutomaticamente()

        offlineUri = intent.getStringExtra("offline_uri")

        val channelName = intent.getStringExtra("channel_name") ?: ""
        tvChannelName.text = if (channelName.isNotBlank()) channelName else "Canal"

        tvNowPlaying.text = if (streamType == "live") "Carregando programação..." else ""

        btnAspect.setOnClickListener {
            val current = playerView.resizeMode
            val next = when (current) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                    Toast.makeText(this, "Modo: Preencher", Toast.LENGTH_SHORT).show()
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                    Toast.makeText(this, "Modo: Zoom", Toast.LENGTH_SHORT).show()
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                else -> {
                    Toast.makeText(this, "Modo: Ajustar", Toast.LENGTH_SHORT).show()
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
            playerView.resizeMode = next
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
            }
        )

        btnPlayNextEpisode.setOnClickListener {
            if (nextStreamId != 0) {
                abrirProximoEpisodio()
            } else {
                Toast.makeText(this, "Sem próximo episódio", Toast.LENGTH_SHORT).show()
            }
        }

        if (streamType == "movie") {
            extensoesTentativa.add(streamExtension)
            extensoesTentativa.add("mp4")
            extensoesTentativa.add("mkv")
        } else {
            extensoesTentativa.add("m3u8")
            extensoesTentativa.add("ts")
            extensoesTentativa.add("")
        }

        setupServerList()
        iniciarPlayer()

        if (streamType == "live" && streamId != 0) {
            carregarEpg()
        }

        if (streamType == "series" && nextStreamId != 0) {
            handler.post(nextChecker)
        }
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.useController = false
            topBar.visibility = View.GONE
            loading.visibility = View.GONE
            nextEpisodeContainer.visibility = View.GONE
        } else {
            playerView.useController = true
            topBar.visibility = if (playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
        }
    }

    private fun setupServerList() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedDns = prefs.getString("dns", "") ?: ""
        
        activeServerList.clear()
        
        if (savedDns.isNotEmpty()) {
            var cleanDns = savedDns
            if (cleanDns.endsWith("/")) cleanDns = cleanDns.dropLast(1)
            activeServerList.add(cleanDns)
        }
        
        for (server in serverBackupList) {
            var cleanServer = server
            if (cleanServer.endsWith("/")) cleanServer = cleanServer.dropLast(1)
            if (cleanServer != savedDns && !savedDns.contains(cleanServer)) {
                activeServerList.add(cleanServer)
            }
        }
    }

    private fun calcularProximoEpisodioAutomaticamente() {
        if (nextStreamId != 0) return 

        if (episodeList.isNotEmpty() && streamType == "series") {
            val indexAtual = episodeList.indexOf(streamId)
            if (indexAtual != -1 && indexAtual < episodeList.size - 1) {
                nextStreamId = episodeList[indexAtual + 1]
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
            
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    @OptIn(UnstableApi::class)
    private fun iniciarPlayer() {
        if (streamType == "vod_offline" || !offlineUri.isNullOrBlank()) {
            var uriStr = offlineUri
            if (uriStr.isNullOrBlank()) {
                Toast.makeText(this, "Arquivo offline não encontrado.", Toast.LENGTH_LONG).show()
                loading.visibility = View.GONE
                return
            }
            
            if (!uriStr!!.startsWith("content://") && !uriStr!!.startsWith("file://") && !uriStr!!.startsWith("http")) {
                uriStr = "file://$uriStr"
            }

            player?.release()
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
                
            player = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, true)
                .build()
                
            playerView.player = player
            
            try {
                val mediaItem = MediaItem.fromUri(Uri.parse(uriStr))
                player?.setMediaItem(mediaItem)
                player?.prepare()
                player?.playWhenReady = true
                
                player?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> loading.visibility = View.GONE
                            Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@PlayerActivity, "Erro ao reproduzir arquivo: ${error.message}", Toast.LENGTH_LONG).show()
                        Log.e("PLAYER_OFFLINE", "Erro: ${error.message}")
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Erro crítico ao carregar arquivo.", Toast.LENGTH_SHORT).show()
            }
            return 
        }

        if (activeServerList.isEmpty()) {
            Toast.makeText(this, "Erro: Sem servidor.", Toast.LENGTH_LONG).show()
            loading.visibility = View.GONE
            return
        }

        if (extIndex >= extensoesTentativa.size) {
            serverIndex++
            extIndex = 0
            if (serverIndex >= activeServerList.size) {
                serverIndex = 0 
                Toast.makeText(this, "Reconectando...", Toast.LENGTH_SHORT).show()
            }
        }

        val currentServer = activeServerList[serverIndex]
        val currentExt = extensoesTentativa[extIndex]

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        val url = montarUrlStream(
            server = currentServer,
            streamType = streamType,
            user = user,
            pass = pass,
            id = streamId,
            ext = currentExt
        )

        player?.release()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val isLive = streamType == "live"
        val minBufferMs = 2000
        val maxBufferMs = if (isLive) 5000 else 15000
        val playBufferMs = 1000
        val playRebufferMs = 2000

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                playBufferMs,
                playRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()

        playerView.player = player

        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            player?.setMediaItem(mediaItem)
            player?.prepare()

            if (startPositionMs > 0L && (streamType == "movie" || streamType == "series")) {
                player?.seekTo(startPositionMs)
            }

            player?.playWhenReady = true
        } catch (e: Exception) {
            tentarProximo()
            return
        }

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> loading.visibility = View.GONE
                    Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                    Player.STATE_ENDED -> {
                        if (streamType == "movie") {
                            clearMovieResume(streamId)
                        } else if (streamType == "series") {
                            clearSeriesResume(streamId)
                            if (nextStreamId != 0) abrirProximoEpisodio()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                loading.visibility = View.VISIBLE
                handler.postDelayed({ tentarProximo() }, 1000L)
            }
        })
    }

    private fun tentarProximo() {
        extIndex++
        iniciarPlayer()
    }

    private fun abrirProximoEpisodio() {
        if (nextStreamId == 0) return

        player?.stop()
        player?.release()
        player = null

        var novoTitulo = nextChannelName
        val tituloAtual = tvChannelName.text.toString()

        if (novoTitulo == null || novoTitulo.equals("Próximo Episódio", ignoreCase = true) || novoTitulo == tituloAtual) {
            val regex = Regex("(?i)(E|Episódio|Episodio|Episode)\\s*0*(\\d+)")
            val match = regex.find(tituloAtual)
            
            if (match != null) {
                try {
                    val textoCompletoEncontrado = match.groupValues[0] 
                    val prefixo = match.groupValues[1] 
                    val numeroStr = match.groupValues[2] 
                    val numeroAtual = numeroStr.toInt()
                    val novoNumero = numeroAtual + 1
                    val novoNumeroStr = if (numeroStr.length > 1 && novoNumero < 10) 
                        "0$novoNumero" else novoNumero.toString()
                    novoTitulo = tituloAtual.replace(textoCompletoEncontrado, "$prefixo$novoNumeroStr")
                } catch (e: Exception) {
                    novoTitulo = tituloAtual 
                }
            } else {
                novoTitulo = tituloAtual 
            }
        }

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", nextStreamId)
        intent.putExtra("stream_ext", "mp4")
        intent.putExtra("stream_type", "series")
        intent.putExtra("channel_name", novoTitulo)
        intent.putExtra("PROFILE_NAME", currentProfile)
        
        if (episodeList.isNotEmpty()) {
            intent.putIntegerArrayListExtra("episode_list", episodeList)
        }
        startActivity(intent)
        finish()
    }

    private fun getMovieKey(id: Int) = "${currentProfile}_movie_resume_$id"

    private fun saveMovieResume(id: Int, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val percent = positionMs.toDouble() / durationMs.toDouble()
        if (positionMs < 30_000L || percent > 0.95) {
            clearMovieResume(id)
            return
        }
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("${getMovieKey(id)}_pos", positionMs)
            .putLong("${getMovieKey(id)}_dur", durationMs)
            .apply()
            
        salvarNoFirebase(id, positionMs, durationMs)
        salvarNoHistoricoLocal(id.toString())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.streamDao().saveWatchHistory(WatchHistoryEntity(
                    stream_id = id,
                    profile_name = currentProfile,
                    name = tvChannelName.text.toString(),
                    icon = intent.getStringExtra("icon") ?: "",
                    last_position = positionMs,
                    duration = durationMs,
                    is_series = false,
                    timestamp = System.currentTimeMillis()
                ))
            } catch (e: Exception) { }
        }
    }

    private fun clearMovieResume(id: Int) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${getMovieKey(id)}_pos")
            .remove("${getMovieKey(id)}_dur")
            .apply()
    }

    private fun getSeriesKey(episodeStreamId: Int) = "${currentProfile}_series_resume_$episodeStreamId"

    private fun saveSeriesResume(id: Int, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val percent = positionMs.toDouble() / durationMs.toDouble()
        if (positionMs < 30_000L || percent > 0.95) {
            clearSeriesResume(id)
            return
        }
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("${getSeriesKey(id)}_pos", positionMs)
            .putLong("${getSeriesKey(id)}_dur", durationMs)
            .apply()

        salvarNoFirebase(id, positionMs, durationMs)
        salvarNoHistoricoLocal(id.toString())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.streamDao().saveWatchHistory(WatchHistoryEntity(
                    stream_id = id,
                    profile_name = currentProfile,
                    name = tvChannelName.text.toString(),
                    icon = intent.getStringExtra("icon") ?: "",
                    last_position = positionMs,
                    duration = durationMs,
                    is_series = true,
                    timestamp = System.currentTimeMillis()
                ))
            } catch (e: Exception) { }
        }
    }

    private fun clearSeriesResume(id: Int) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${getSeriesKey(id)}_pos")
            .remove("${getSeriesKey(id)}_dur")
            .apply()
    }

    private fun salvarNoHistoricoLocal(id: String) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val keyIds = "${currentProfile}_local_history_ids"
        val ids = prefs.getStringSet(keyIds, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        ids.add(id)
        prefs.edit().apply {
            putStringSet(keyIds, ids)
            putString("${currentProfile}_history_name_$id", tvChannelName.text.toString())
            putString("${currentProfile}_history_icon_$id", intent.getStringExtra("icon") ?: "")
            apply()
        }
    }

    private fun salvarNoFirebase(id: Int, positionMs: Long, durationMs: Long) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val userEmail = prefs.getString("username", "") ?: ""
        if (userEmail.isBlank()) return

        val db = FirebaseFirestore.getInstance()
        val data = hashMapOf(
            "id" to id.toString(),
            "name" to tvChannelName.text.toString(),
            "streamIcon" to (intent.getStringExtra("icon") ?: ""),
            "positionMs" to positionMs,
            "durationMs" to durationMs,
            "timestamp" to com.google.firebase.Timestamp.now()
        )

        db.collection("users")
            .document(userEmail)
            .collection("profiles")
            .document(currentProfile)
            .collection("history")
            .document(id.toString())
            .set(data, SetOptions.merge())
            .addOnFailureListener { e -> Log.e("FIREBASE_PLAYER", "Erro: ${e.message}") }
    }

    private fun decodeBase64(text: String?): String {
        return try {
            if (text.isNullOrEmpty()) "" else String(
                Base64.decode(text, Base64.DEFAULT),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            text ?: ""
        }
    }

    private fun carregarEpg() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (user.isBlank() || pass.isBlank()) {
            tvNowPlaying.text = "Sem informação de programação"
            return
        }

        XtreamApi.service.getShortEpg(
            user = user,
            pass = pass,
            streamId = streamId.toString(),
            limit = 2
        ).enqueue(object : Callback<EpgWrapper> {
            override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                if (!response.isSuccessful || response.body()?.epg_listings.isNullOrEmpty()) {
                    tvNowPlaying.text = "Sem informação de programação"
                    return
                }
                val epg = response.body()!!.epg_listings!!.firstOrNull() ?: return
                val titulo = decodeBase64(epg.title)
                val inicio = epg.start ?: ""
                val fim = epg.stop ?: epg.end.orEmpty()
                tvNowPlaying.text = if (inicio.isNotBlank()) "$titulo ($inicio - $fim)" else titulo
            }
            override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {
                tvNowPlaying.text = "Falha ao carregar programação"
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val p = player ?: return super.dispatchKeyEvent(event)

        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (nextEpisodeContainer.visibility == View.VISIBLE) {
                        btnPlayNextEpisode.performClick()
                        return true
                    }
                    
                    if (playerView.isControllerFullyVisible) {
                        if (p.isPlaying) p.pause() else p.play()
                    } else {
                        playerView.showController()
                    }
                    return true 
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (nextEpisodeContainer.visibility == View.VISIBLE) {
                        btnPlayNextEpisode.requestFocus()
                        return true
                    }
                    if (!playerView.isControllerFullyVisible) {
                        playerView.showController()
                    }
                    val seekBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    seekBar?.requestFocus()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (streamType != "live") {
                        p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration))
                        playerView.showController()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (streamType != "live") {
                        p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                        playerView.showController()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
            val p = player ?: return
            if (streamType == "movie") {
                saveMovieResume(streamId, p.currentPosition, p.duration)
            } else if (streamType == "series") {
                saveSeriesResume(streamId, p.currentPosition, p.duration)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(nextChecker)
        val p = player
        if (p != null) {
            if (streamType == "movie") {
                saveMovieResume(streamId, p.currentPosition, p.duration)
            } else if (streamType == "series") {
                saveSeriesResume(streamId, p.currentPosition, p.duration)
            }
        }
        player?.release()
        player = null
    }

    private fun montarUrlStream(server: String, streamType: String, user: String, pass: String, id: Int, ext: String): String {
        val base = if (server.endsWith("/")) server.dropLast(1) else server
        return if (ext.isBlank()) "$base/$streamType/$user/$pass/$id" else "$base/$streamType/$user/$pass/$id.$ext"
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
            player?.stop()
            player?.release()
            player = null
        }
        if (isFinishing) {
            player?.release()
            player = null
        }
    }

    override fun onBackPressed() {
        player?.stop()
        player?.release()
        player = null
        super.onBackPressed()
        finish()
    }
}
