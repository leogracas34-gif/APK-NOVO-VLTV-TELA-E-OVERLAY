package com.vltv.play // Certifique-se de que este é o pacote correto do seu app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vltv.play.databinding.ActivityProfileSelectionBinding
import com.vltv.play.databinding.ItemProfileCircleBinding

// 1. Classe de dados para o Perfil
data class UserProfile(val id: Int, var name: String, val imageRes: Int)

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializa o ViewBinding
        binding = ActivityProfileSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. Lista com os 4 perfis que você pediu
        val listaPerfis = mutableListOf(
            UserProfile(1, "Perfil 1", R.drawable.ic_profile_placeholder),
            UserProfile(2, "Perfil 2", R.drawable.ic_profile_placeholder),
            UserProfile(3, "Perfil 3", R.drawable.ic_profile_placeholder),
            UserProfile(4, "Perfil 4", R.drawable.ic_profile_placeholder)
        )

        // 3. Configura a Grade de 2 colunas
        binding.rvProfiles.layoutManager = GridLayoutManager(this, 2)
        binding.rvProfiles.adapter = ProfileAdapter(listaPerfis)

        // 4. Botão Editar Perfis (Topo Direito)
        binding.tvEditProfiles.setOnClickListener {
            Toast.makeText(this, "Modo Edição Ativado", Toast.LENGTH_SHORT).show()
            // Aqui futuramente chamaremos a tela de trocar nome/foto
        }

        // 5. Botão Adicionar Perfil (Sinal de +)
        binding.layoutAddProfile.setOnClickListener {
            Toast.makeText(this, "Abrindo tela de novo perfil...", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ADAPTER INTERNO PARA OS CÍRCULOS ---
    inner class ProfileAdapter(private val perfis: List<UserProfile>) : 
        RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

        inner class ProfileViewHolder(val itemBinding: ItemProfileCircleBinding) : 
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemBinding = ItemProfileCircleBinding.inflate(inflater, parent, false)
            return ProfileViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            val perfil = perfis[position]
            
            // Define o nome e a imagem no círculo
            holder.itemBinding.tvProfileName.text = perfil.name
            holder.itemBinding.ivProfileAvatar.setImageResource(perfil.imageRes)

            // Clique no Perfil para entrar
            holder.itemBinding.root.setOnClickListener {
                Toast.makeText(this@ProfilesActivity, "Entrando como: ${perfil.name}", Toast.LENGTH_SHORT).show()
                // Aqui você fará o Intent para a sua HomeActivity
            }
        }

        override fun getItemCount(): Int = perfis.size
    }
}
