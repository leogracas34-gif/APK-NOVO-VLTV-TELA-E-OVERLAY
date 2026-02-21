package com.vltv.play

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.ProfileEntity
import com.vltv.play.databinding.ActivityEditProfileBinding
import com.vltv.play.ui.AvatarSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentProfileId: Int = -1
    private var selectedImageUrl: String? = null
    private val tmdbApiKey = "9b73f5dd15b8165b1b57419be2f29128"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Recebe o ID do perfil que veio da tela anterior
        currentProfileId = intent.getIntExtra("PROFILE_ID", -1)

        loadProfileData()

        // Botão Pronto (Guardar)
        binding.btnSaveProfile.setOnClickListener { saveChanges() }

        // Botão Cancelar
        binding.btnCancelEdit.setOnClickListener { finish() }

        // Clicar na Foto ou no Lápis abre o Diálogo do TMDB
        binding.avatarFrame.setOnClickListener { openAvatarPicker() }

        // Botão Eliminar
        binding.btnDeleteProfile.setOnClickListener { confirmDeletion() }
    }

    private fun loadProfileData() {
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                db.streamDao().getAllProfiles().find { it.id == currentProfileId }
            }
            profile?.let {
                binding.etEditName.setText(it.name)
                selectedImageUrl = it.imageUrl
                Glide.with(this@EditProfileActivity)
                    .load(it.imageUrl ?: R.drawable.ic_profile_placeholder)
                    .circleCrop()
                    .into(binding.ivEditAvatar)
            }
        }
    }

    private fun openAvatarPicker() {
        val dialog = AvatarSelectionDialog(this, tmdbApiKey) { imageUrl ->
            selectedImageUrl = imageUrl
            Glide.with(this).load(imageUrl).circleCrop().into(binding.ivEditAvatar)
        }
        dialog.show()
    }

    private fun saveChanges() {
        val newName = binding.etEditName.text.toString()
        if (newName.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val profile = ProfileEntity(id = currentProfileId, name = newName, imageUrl = selectedImageUrl)
            db.streamDao().updateProfile(profile)
            withContext(Dispatchers.Main) {
                finish() // Volta para a ProfilesActivity
            }
        }
    }

    private fun confirmDeletion() {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Perfil?")
            .setMessage("Tem certeza que deseja apagar este perfil?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val profile = ProfileEntity(id = currentProfileId, name = "", imageUrl = null)
                    db.streamDao().deleteProfile(profile)
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
