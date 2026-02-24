package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.ProfileEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.databinding.ActivityProfileSelectionBinding
import com.vltv.play.databinding.ItemProfileCircleBinding
import com.vltv.play.ui.AvatarSelectionDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.net.URL

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectionBinding
    private var isEditMode = false
    private lateinit var adapter: ProfileAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val listaPerfis = mutableListOf<ProfileEntity>()
    
    private var isCreating = false
    private val mutex = Mutex()

    private val tmdbApiKey = "9b73f5dd15b8165b1b57419be2f29128" 
    
    private val defaultAvatarUrl1 = "https://image.tmdb.org/t/p/original/ywe9S1cOyIhR5yWzK7511NuQ2YX.jpg"
    private val defaultAvatarUrl2 = "https://image.tmdb.org/t/p/original/4fLZUr1e65hKPPVw0R3PmKFKxj1.jpg"
    private val defaultAvatarUrl3 = "https://image.tmdb.org/t/p/original/53iAkBnBhqJh2ZmhCug4lSCSUq9.jpg"
    private val defaultAvatarUrl4 = "https://image.tmdb.org/t/p/original/8I37NtDffNV7AZlDa7uDvvqhovU.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        verificarPerfilSalvo()
        setupRecyclerView()
        loadProfilesFromDb()

        // ✅ LÓGICA IDÊNTICA AO LOGIN ACTIVITY
        sincronizarConteudoBackgroundSilencioso()

        binding.tvEditProfiles.setOnClickListener {
            isEditMode = !isEditMode
            binding.tvEditProfiles.text = if (isEditMode) "CONCLUÍDO" else "EDITAR PERFIS"
            adapter.notifyDataSetChanged()
        }

        binding.layoutAddProfile.setOnClickListener {
            addNewProfile()
        }
    }

    // ✅ FUNÇÃO REESCRITA COM A LÓGICA DO SEU ARQUIVO DE LOGIN
    private fun sincronizarConteudoBackgroundSilencioso() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", null)
        val user = prefs.getString("username", null)
        val pass = prefs.getString("password", null)

        if (dns.isNullOrEmpty() || user.isNullOrEmpty() || pass.isNullOrEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // FILMES (Igual ao LoginActivity)
                try {
                    val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
                    val response = URL(vodUrl).readText()
                    val jsonArray = JSONArray(response)
                    val batch = mutableListOf<VodEntity>()
                    val limit = minOf(60, jsonArray.length())
                    
                    for (i in 0 until limit) {
                        val obj = jsonArray.getJSONObject(i)
                        batch.add(VodEntity(
                            stream_id = obj.optInt("stream_id"),
                            name = obj.optString("name"),
                            title = obj.optString("name"),
                            stream_icon = obj.optString("stream_icon"),
                            container_extension = obj.optString("container_extension"),
                            rating = obj.optString("rating"),
                            category_id = obj.optString("category_id"),
                            added = obj.optLong("added")
                        ))
                    }
                    if (batch.isNotEmpty()) db.streamDao().insertVodStreams(batch)
                } catch (e: Exception) { e.printStackTrace() }

                // SÉRIES (Igual ao LoginActivity)
                try {
                    val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
                    val response = URL(seriesUrl).readText()
                    val jsonArray = JSONArray(response)
                    val batch = mutableListOf<SeriesEntity>()
                    val limit = minOf(60, jsonArray.length())
                    
                    for (i in 0 until limit) {
                        val obj = jsonArray.getJSONObject(i)
                        batch.add(SeriesEntity(
                            series_id = obj.optInt("series_id"),
                            name = obj.optString("name"),
                            cover = obj.optString("cover"),
                            rating = obj.optString("rating"),
                            category_id = obj.optString("category_id"),
                            last_modified = obj.optLong("last_modified")
                        ))
                    }
                    if (batch.isNotEmpty()) db.streamDao().insertSeriesStreams(batch)
                } catch (e: Exception) { e.printStackTrace() }

            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun verificarPerfilSalvo() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val salvoNome = prefs.getString("last_profile_name", null)
        val salvoIcon = prefs.getString("last_profile_icon", null)
        val forcarSelecao = intent.getBooleanExtra("FORCE_SELECTION", false)
        
        if (!forcarSelecao && salvoNome != null) {
            val intent = Intent(this, HomeActivity::class.java)
            intent.putExtra("PROFILE_NAME", salvoNome)
            intent.putExtra("PROFILE_ICON", salvoIcon)
            startActivity(intent)
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = ProfileAdapter(listaPerfis)
        binding.rvProfiles.layoutManager = GridLayoutManager(this, 2)
        binding.rvProfiles.adapter = adapter
    }

    private fun loadProfilesFromDb() {
        if (isCreating) return
        lifecycleScope.launch {
            mutex.withLock {
                val perfis = withContext(Dispatchers.IO) { db.streamDao().getAllProfiles() }
                if (perfis.isEmpty()) {
                    createDefaultProfiles()
                } else {
                    listaPerfis.clear()
                    listaPerfis.addAll(perfis)
                    withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
                }
            }
        }
    }

    private suspend fun createDefaultProfiles() {
        isCreating = true
        val padrao = listOf(
            ProfileEntity(name = "Meu Perfil 1", imageUrl = defaultAvatarUrl1),
            ProfileEntity(name = "Meu Perfil 2", imageUrl = defaultAvatarUrl2),
            ProfileEntity(name = "Meu Perfil 3", imageUrl = defaultAvatarUrl3),
            ProfileEntity(name = "Meu Perfil 4", imageUrl = defaultAvatarUrl4)
        )
        withContext(Dispatchers.IO) {
            val checagem = db.streamDao().getAllProfiles()
            if (checagem.isEmpty()) {
                padrao.forEach { db.streamDao().insertProfile(it) }
            }
        }
        val perfisCriados = withContext(Dispatchers.IO) { db.streamDao().getAllProfiles() }
        withContext(Dispatchers.Main) {
            listaPerfis.clear()
            listaPerfis.addAll(perfisCriados)
            adapter.notifyDataSetChanged()
            isCreating = false
        }
    }

    private fun addNewProfile() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Novo Perfil")
            .setView(input)
            .setPositiveButton("Adicionar") { _, _ ->
                val nome = input.text.toString()
                if (nome.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.streamDao().insertProfile(ProfileEntity(name = nome, imageUrl = defaultAvatarUrl1))
                        withContext(Dispatchers.Main) { loadProfilesFromDb() }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateProfileInDb(perfil: ProfileEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.streamDao().updateProfile(perfil)
            withContext(Dispatchers.Main) { loadProfilesFromDb() }
        }
    }

    inner class ProfileAdapter(private val perfis: List<ProfileEntity>) :
        RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

        inner class ProfileViewHolder(val itemBinding: ItemProfileCircleBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val itemBinding = ItemProfileCircleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ProfileViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            val perfil = perfis[position]
            holder.itemBinding.tvProfileName.text = perfil.name
            Glide.with(this@ProfilesActivity)
                .load(perfil.imageUrl ?: R.drawable.ic_profile_placeholder)
                .circleCrop()
                .into(holder.itemBinding.ivProfileAvatar)

            holder.itemBinding.root.setOnClickListener {
                if (isEditMode) {
                    val intent = Intent(this@ProfilesActivity, EditProfileActivity::class.java)
                    intent.putExtra("PROFILE_ID", perfil.id)
                    startActivity(intent)
                } else {
                    val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("last_profile_name", perfil.name)
                        putString("last_profile_icon", perfil.imageUrl)
                        apply()
                    }
                    val intent = Intent(this@ProfilesActivity, HomeActivity::class.java)
                    intent.putExtra("PROFILE_NAME", perfil.name)
                    intent.putExtra("PROFILE_ICON", perfil.imageUrl)
                    startActivity(intent)
                    finish()
                }
            }
        }
        override fun getItemCount(): Int = perfis.size
    }

    override fun onResume() {
        super.onResume()
        loadProfilesFromDb()
    }
}
