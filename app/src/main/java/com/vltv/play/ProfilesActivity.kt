package com.vltv.play

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vltv.play.databinding.ActivityProfilesBinding
import com.vltv.play.databinding.ItemProfileBinding

// Classe de dados para organizar os perfis
data class UserProfile(val name: String, val color: Int)

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvProfiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Lista de perfis com cores Premium
        val listaPerfis = listOf(
            UserProfile("Pai", Color.parseColor("#E50914")),   // Vermelho Netflix
            UserProfile("Mãe", Color.parseColor("#2BADEE")),   // Azul
            UserProfile("Kids", Color.parseColor("#FFC107")),  // Amarelo
            UserProfile("Convidado", Color.parseColor("#4CAF50")) // Verde
        )
        
        binding.rvProfiles.adapter = ProfileAdapter(listaPerfis)
    }

    inner class ProfileAdapter(private val perfis: List<UserProfile>) : RecyclerView.Adapter<ProfileAdapter.VH>() {
        
        inner class VH(val itemBinding: ItemProfileBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ib = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(ib)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val perfil = perfis[position]
            val b = holder.itemBinding

            b.tvProfileName.text = perfil.name
            
            // Aplica a cor no avatar (se não tiver imagem, preenche com a cor)
            b.imgAvatar.setBackgroundColor(perfil.color)
            b.imgAvatar.setImageResource(android.R.color.transparent) 

            // Efeito de Foco Premium com Borda Neon
            b.root.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()
                    b.tvProfileName.setTextColor(Color.WHITE)
                    // Mostra a borda neon (View que criaremos no XML)
                    b.borderFocus.visibility = View.VISIBLE
                    b.borderFocus.background?.setTint(perfil.color)
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    b.tvProfileName.setTextColor(Color.parseColor("#888888"))
                    b.borderFocus.visibility = View.INVISIBLE
                }
            }

            b.root.setOnClickListener {
                val intent = Intent(this@ProfilesActivity, HomeActivity::class.java)
                intent.putExtra("PROFILE_NAME", perfil.name)
                intent.putExtra("PROFILE_COLOR", perfil.color)
                startActivity(intent)
                finish()
            }
        }

        override fun getItemCount() = perfis.size
    }
}
