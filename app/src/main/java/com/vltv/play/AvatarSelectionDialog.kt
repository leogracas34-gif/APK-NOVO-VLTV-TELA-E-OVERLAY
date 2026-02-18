package com.vltv.play.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.recyclerview.widget.GridLayoutManager
import com.vltv.play.data.TmdbClient
import com.vltv.play.data.TmdbPerson
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

        // Configuração do botão cancelar que já estava no seu XML
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun setupRecyclerView() {
        binding.rvAvatars.layoutManager = GridLayoutManager(context, 3)
    }

    private fun loadAvatars() {
        scope.launch {
            try {
                // Lista de termos para buscar os personagens que você pediu
                val personagens = listOf(
                    "Avengers", "Spider-Man", "Iron Man", "Hulk", "Thor", 
                    "Mickey Mouse", "Avatar Way of Water", "Batman", 
                    "Superman", "Wonder Woman", "Scarlet Witch", "X-Men"
                )

                val todosOsResultados = mutableListOf<TmdbPerson>()

                // Fazemos a busca para cada termo e juntamos tudo
                withContext(Dispatchers.IO) {
                    personagens.forEach { termo ->
                        try {
                            // Aqui corrigimos o erro: agora passamos a 'apiKey' e o 'termo' (query)
                            val response = TmdbClient.api.getPopularPeople(apiKey, termo)
                            todosOsResultados.addAll(response.results)
                        } catch (e: Exception) {
                            e.printStackTrace() 
                        }
                    }
                }
                
                // Lógica de exibição no Adapter integrada ao seu código
                // Usamos as fotos filtradas para não repetir muito
                val listaFinal = todosOsResultados.distinctBy { it.profile_path }.filter { it.profile_path != null }

                val adapter = AvatarAdapter(listaFinal) { imageUrl ->
                    onAvatarSelected(imageUrl) // Envia a imagem escolhida de volta
                    dismiss() // Fecha o diálogo após a escolha
                }
                binding.rvAvatars.adapter = adapter
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        scope.cancel() // Limpa as coroutines quando o dialog fecha para não dar erro de memória
    }
}
