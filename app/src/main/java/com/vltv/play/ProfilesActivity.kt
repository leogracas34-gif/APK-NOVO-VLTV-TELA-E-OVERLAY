package com.vltv.play

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

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectionBinding
    private var isEditMode = false
    private lateinit var adapter: ProfileAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val listaPerfis = mutableListOf<ProfileEntity>()

    private val tmdbApiKey = "9b73f5dd15b8165b1b57419be2f29128" 
    
    // URL Padrão para os perfis já nascerem com um herói (Ex: Homem-Aranha)
    private val defaultAvatarUrl = "https://image.tmdb.org/t/p/w200/ghvO1pL5u4p0I50L4R6m36L3R5V.jpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun setupRecyclerView() {
        adapter = ProfileAdapter(listaPerfis)
        binding.rvProfiles.layoutManager = GridLayoutManager(this, 2)
        binding.rvProfiles.adapter = adapter
    }

    private fun loadProfilesFromDb() {
        lifecycleScope.launch {
            val perfis = withContext(Dispatchers.IO) { db.streamDao().getAllProfiles() }
            
            // CORREÇÃO: Verificamos se está vazio antes de tentar criar
            if (perfis.isEmpty()) {
                createDefaultProfiles()
            } else {
                listaPerfis.clear()
                listaPerfis.addAll(perfis)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private suspend fun createDefaultProfiles() {
        // CORREÇÃO: Nomes alterados conforme solicitado e apenas 2 itens na lista
        val padrao = listOf(
            ProfileEntity(name = "Meu Perfil 1", imageUrl = defaultAvatarUrl),
            ProfileEntity(name = "Meu Perfil 2", imageUrl = defaultAvatarUrl)
        )
        
        withContext(Dispatchers.IO) {
            // TRAVA DE SEGURANÇA: Checa novamente dentro da Coroutine se o banco está vazio
            // Isso evita que o Android crie 4 perfis se a função for chamada rápido demais
            val checagem = db.streamDao().getAllProfiles()
            if (checagem.isEmpty()) {
                padrao.forEach { db.streamDao().insertProfile(it) }
            }
        }
        
        // CORREÇÃO: Em vez de chamar loadProfilesFromDb() e gerar loop, atualizamos a lista aqui
        val perfisCriados = withContext(Dispatchers.IO) { db.streamDao().getAllProfiles() }
        listaPerfis.clear()
        listaPerfis.addAll(perfisCriados)
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
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
                        // Novo perfil também já nasce com o avatar padrão aqui
                        db.streamDao().insertProfile(ProfileEntity(name = nome, imageUrl = defaultAvatarUrl))
                        withContext(Dispatchers.Main) {
                            loadProfilesFromDb()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- DIÁLOGO DE EDIÇÃO (Ainda mantido para segurança enquanto criamos a nova tela) ---
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

            holder.itemBinding.ivProfileAvatar.setStrokeColorResource(
                if (isEditMode) android.R.color.holo_orange_light else android.R.color.white
            )

            holder.itemBinding.root.setOnClickListener {
                if (isEditMode) {
                    // ✅ ATUALIZADO: Agora abre a nova tela única de edição
                    val intent = Intent(this@ProfilesActivity, EditProfileActivity::class.java)
                    intent.putExtra("PROFILE_ID", perfil.id)
                    startActivity(intent)
                } else {
                    // ✅ AQUI VAI PARA A HOME
                    Toast.makeText(this@ProfilesActivity, "Entrando como: ${perfil.name}", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@ProfilesActivity, HomeActivity::class.java)
                    intent.putExtra("PROFILE_NAME", perfil.name)
                    startActivity(intent)
                    finish()
                }
            }
        }

        override fun getItemCount(): Int = perfis.size
    }

    // Adicionado para atualizar a lista automaticamente ao voltar da tela de edição
    override fun onResume() {
        super.onResume()
        loadProfilesFromDb()
    }
}
