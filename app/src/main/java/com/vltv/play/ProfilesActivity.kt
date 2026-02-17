package com.vltv.play

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vltv.play.databinding.ActivityProfileSelectionBinding
import com.vltv.play.databinding.ItemProfileCircleBinding

data class UserProfile(val id: Int, var name: String, val imageRes: Int)

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectionBinding
    private var isEditMode = false // Controla se estamos editando ou entrando
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val listaPerfis = mutableListOf(
            UserProfile(1, "Perfil 1", R.drawable.ic_profile_placeholder),
            UserProfile(2, "Perfil 2", R.drawable.ic_profile_placeholder),
            UserProfile(3, "Perfil 3", R.drawable.ic_profile_placeholder),
            UserProfile(4, "Perfil 4", R.drawable.ic_profile_placeholder)
        )

        adapter = ProfileAdapter(listaPerfis)
        binding.rvProfiles.layoutManager = GridLayoutManager(this, 2)
        binding.rvProfiles.adapter = adapter

        // Lógica do botão EDITAR PERFIS
        binding.tvEditProfiles.setOnClickListener {
            isEditMode = !isEditMode // Inverte o estado
            binding.tvEditProfiles.text = if (isEditMode) "CONCLUÍDO" else "EDITAR PERFIS"
            adapter.notifyDataSetChanged() // Avisa a lista para mudar o comportamento
        }

        binding.layoutAddProfile.setOnClickListener {
            Toast.makeText(this, "Adicionar novo perfil", Toast.LENGTH_SHORT).show()
        }
    }

    // Função para mostrar o diálogo de edição de nome
    private fun showEditDialog(perfil: UserProfile, position: Int) {
        val input = EditText(this)
        input.setText(perfil.name)

        AlertDialog.Builder(this)
            .setTitle("Editar Nome")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val novoNome = input.text.toString()
                if (novoNome.isNotEmpty()) {
                    perfil.name = novoNome
                    adapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class ProfileAdapter(private val perfis: List<UserProfile>) : 
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
            holder.itemBinding.ivProfileAvatar.setImageResource(perfil.imageRes)

            // Se estiver no modo edição, mudamos a cor da borda ou mostramos um aviso visual
            if (isEditMode) {
                holder.itemBinding.ivProfileAvatar.setStrokeColorResource(android.R.color.holo_blue_light)
            } else {
                holder.itemBinding.ivProfileAvatar.setStrokeColorResource(android.R.color.white)
            }

            holder.itemBinding.root.setOnClickListener {
                if (isEditMode) {
                    showEditDialog(perfil, position)
                } else {
                    Toast.makeText(this@ProfilesActivity, "Entrando como: ${perfil.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun getItemCount(): Int = perfis.size
    }
}
