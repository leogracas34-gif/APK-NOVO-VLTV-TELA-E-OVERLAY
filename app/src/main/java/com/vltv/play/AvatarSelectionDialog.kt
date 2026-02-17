package com.vltv.play.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.recyclerview.widget.GridLayoutManager
import com.vltv.play.data.TmdbClient
import com.vltv.play.databinding.DialogAvatarSelectionBinding
import kotlinx.coroutines.*

class AvatarSelectionDialog(
    context: Context,
    private val apiKey: String, // Passaremos a chave do seu TmdbConfig aqui
    private val onAvatarSelected: (String) -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogAvatarSelectionBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogAvatarSelectionBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        setupRecyclerView()
        loadAvatars()
    }

    private fun setupRecyclerView() {
        binding.rvAvatars.layoutManager = GridLayoutManager(context, 3)
    }

    private fun loadAvatars() {
        scope.launch {
            try {
                // Aqui fazemos a busca por coleções de heróis para pegar os personagens
                // Exemplo: ID 86311 é a coleção dos Vingadores
                val response = withContext(Dispatchers.IO) {
                    TmdbClient.api.getPopularPeople(apiKey) // Por enquanto usamos o popular, mas vou te ensinar a trocar pelo ID do herói
                }
                
                // Lógica de exibição no Adapter (que criaremos a seguir)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
