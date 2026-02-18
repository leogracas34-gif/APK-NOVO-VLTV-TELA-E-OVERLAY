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

                // Otimização de Carregamento: Agora usamos async para buscar tudo em paralelo
                withContext(Dispatchers.IO) {
                    val buscas = personagens.map { termo ->
                        async {
                            try {
                                TmdbClient.api.getPopularPeople(apiKey, termo).results
                            } catch (e: Exception) {
                                emptyList<TmdbPerson>()
                            }
                        }
                    }
                    
                    // Aguarda todas as buscas terminarem juntas e junta os resultados
                    buscas.forEach { deferred ->
                        todosOsResultados.addAll(deferred.await())
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
