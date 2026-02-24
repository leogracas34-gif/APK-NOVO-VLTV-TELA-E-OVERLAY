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
import com.vltv.play.databinding.ActivityProfileSelectionBinding
import com.vltv.play.databinding.ItemProfileCircleBinding
import com.vltv.play.ui.AvatarSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectionBinding
    private var isEditMode = false
    private lateinit var adapter: ProfileAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val listaPerfis = mutableListOf<ProfileEntity>()
    
    // TRAVA DE SEGURANÇA (SEMÁFORO)
    private var isCreating = false
    private val mutex = Mutex()

    private val tmdbApiKey = "9b73f5dd15b8165b1b57419be2f29128" 
    
    // URLs Padrão para os 4 perfis iniciais
    private val defaultAvatarUrl1 = "https://image.tmdb.org/t/p/original/ywe9S1cOyIhR5yWzK7511NuQ2YX.jpg"
    private val defaultAvatarUrl2 = "https://image.tmdb.org/t/p/original/4fLZUr1e65hKPPVw0R3PmKFKxj1.jpg"
    private val defaultAvatarUrl3 = "https://image.tmdb.org/t/p/original/53iAkBnBhqJh2ZmhCug4lSCSUq9.jpg"
    private val defaultAvatarUrl4 = "https://image.tmdb.org/t/p/original/8I37NtDffNV7AZlDa7uDvvqhovU.jpg"
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ LÓGICA DE AUTO-LOGIN: Verifica se já tem um perfil salvo para entrar direto
        verificarPerfilSalvo()

        setupRecyclerView()
        loadProfilesFromDb()

        binding.tvEditProfiles.setOnClickListener {
            isEditMode = !isEditMode
            binding.tvEditProfiles.text = if (isEditMode) "CONCLUÍDO" else "EDITAR PERFIS"
            adapter.notifyDataSetChanged()
        }

        binding.layoutAddProfile.setOnClickListener {
            addNewProfile()
        }
    }

    // ✅ FUNÇÃO QUE VERIFICA A "CADERNETA" (SHARED PREFERENCES)
    private fun verificarPerfilSalvo() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val salvoNome = prefs.getString("last_profile_name", null)
        val salvoIcon = prefs.getString("last_profile_icon", null)
        
        // Se o usuário não estiver vindo de um "Trocar Perfil" e houver dados salvos, vai direto
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
        // Se já estiver no meio de uma criação, não faz nada para evitar duplicidade
        if (isCreating) return

        lifecycleScope.launch {
            mutex.withLock {
                val perfis = withContext(Dispatchers.IO) { db.streamDao().getAllProfiles() }
                
                if (perfis.isEmpty()) {
                    createDefaultProfiles()
                } else {
                    listaPerfis.clear()
                    listaPerfis.addAll(perfis)
                    withContext(Dispatchers.Main) {
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private suspend fun createDefaultProfiles() {
        // Ativa a trava para evitar que outra chamada entre aqui
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
            // Libera a trava após atualizar a tela
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
                        withContext(Dispatchers.Main) {
                            loadProfilesFromDb()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- DIÁLOGO DE EDIÇÃO ---
    private fun showEditOptions(perfil: ProfileEntity) {
        val options = arrayOf("Editar Nome", "Trocar Avatar (Personagens)", "Excluir Perfil")
        AlertDialog.Builder(this)
            .setTitle("O que deseja fazer?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editProfileName(perfil)
                    1 -> openAvatarSelection(perfil)
                    2 -> deleteProfile(perfil)
                }
            }
            .show()
    }

    private fun editProfileName(perfil: ProfileEntity) {
        val input = EditText(this)
        input.setText(perfil.name)
        AlertDialog.Builder(this)
            .setTitle("Editar Nome")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                perfil.name = input.text.toString()
                updateProfileInDb(perfil)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAvatarSelection(perfil: ProfileEntity) {
        val dialog = AvatarSelectionDialog(this, tmdbApiKey) { imageUrl ->
            perfil.imageUrl = imageUrl
            updateProfileInDb(perfil)
        }
        dialog.show()
    }

    private fun updateProfileInDb(perfil: ProfileEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.streamDao().updateProfile(perfil)
            withContext(Dispatchers.Main) {
                loadProfilesFromDb()
            }
        }
    }

    private fun deleteProfile(perfil: ProfileEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.streamDao().deleteProfile(perfil)
            withContext(Dispatchers.Main) {
                loadProfilesFromDb()
            }
        }
    }

    // --- ADAPTER ---
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
                    // ✅ SALVA NA CADERNETA ANTES DE ENTRAR
                    val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("last_profile_name", perfil.name)
                        putString("last_profile_icon", perfil.imageUrl)
                        apply()
                    }

                    Toast.makeText(this@ProfilesActivity, "Entrando como: ${perfil.name}", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@ProfilesActivity, HomeActivity::class.java)
                    intent.putExtra("PROFILE_NAME", perfil.name)
                    // ✅ ENVIA A FOTO TAMBÉM PARA A HOME NÃO FICAR SEM FOTO
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
