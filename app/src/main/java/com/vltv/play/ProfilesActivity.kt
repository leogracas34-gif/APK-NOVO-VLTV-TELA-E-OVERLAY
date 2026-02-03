package com.vltv.play

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProfilesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        val rvProfiles = findViewById<RecyclerView>(R.id.rvProfiles)
        rvProfiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Lista de perfis (No futuro, virá do Firebase)
        val listaPerfis = listOf("Pai", "Mãe", "Kids", "Convidado")
        rvProfiles.adapter = ProfileAdapter(listaPerfis)
    }

    inner class ProfileAdapter(private val perfis: List<String>) : RecyclerView.Adapter<ProfileAdapter.VH>() {
        
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val imgAvatar: ImageView = v.findViewById(R.id.imgAvatar)
            val tvName: TextView = v.findViewById(R.id.tvProfileName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tvName.text = perfis[position]
            
            // Efeito de Foco Premium
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.2f).scaleY(1.2f).setDuration(200).start()
                    holder.tvName.setTextColor(android.graphics.Color.WHITE)
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    holder.tvName.setTextColor(android.graphics.Color.parseColor("#888888"))
                }
            }

            holder.itemView.setOnClickListener {
                // Salva qual perfil entrou e vai para a Home
                val intent = Intent(this@ProfilesActivity, HomeActivity::class.java)
                intent.putExtra("PROFILE_NAME", perfis[position])
                startActivity(intent)
                finish()
            }
        }

        override fun getItemCount() = perfis.size
    }
}
