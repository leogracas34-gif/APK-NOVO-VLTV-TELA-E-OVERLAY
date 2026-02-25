package com.vltv.play

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.ProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    // ✅ VARIÁVEIS PARA OS PERFIS NO TOPO
    private lateinit var rvProfiles: RecyclerView
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentProfileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // ✅ RECUPERA O PERFIL ATUAL PARA DESTACAR NO TOPO
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfileName = prefs.getString("last_profile_name", "Padrao")

        // -------- VIEWS ORIGINAIS --------
        val switchParental: Switch = findViewById(R.id.switchParental)
        val etPin: EditText = findViewById(R.id.etPin)
        val btnSavePin: Button = findViewById(R.id.btnSavePin)

        // se existirem no layout premium:
        val tvVersion: TextView? = findViewById(R.id.tvVersion)
        val cardClearCache: LinearLayout? = findViewById(R.id.cardClearCache)
        val cardAbout: LinearLayout? = findViewById(R.id.cardAbout)
        val cardLogout: LinearLayout? = findViewById(R.id.cardLogout)

        // ✅ NOVA VIEW: RECYCLERVIEW DE PERFIS (No topo do seu layout)
        rvProfiles = findViewById(R.id.rvProfilesSettings)
        setupProfilesHeader()

        // -------- VERSÃO DO APP (FIXA) --------
        tvVersion?.text = "Versão 1.0.0"

        // -------- CONTROLE PARENTAL (CÓDIGO ANTIGO) --------
        // carregar estado atual
        switchParental.isChecked = ParentalControlManager.isEnabled(this)
        etPin.setText(ParentalControlManager.getPin(this))

        switchParental.setOnCheckedChangeListener { _, isChecked ->
            ParentalControlManager.setEnabled(this, isChecked)
        }

        btnSavePin.setOnClickListener {
            val pin = etPin.text.toString().trim()
            if (pin.length != 4) {
                Toast.makeText(this, "PIN precisa ter 4 dígitos", Toast.LENGTH_SHORT).show()
            } else {
                ParentalControlManager.setPin(this, pin)
                Toast.makeText(this, "PIN salvo", Toast.LENGTH_SHORT).show()
            }
        }

        // -------- LIMPAR CACHE (SE O CARD EXISTIR NO LAYOUT) --------
        cardClearCache?.setOnClickListener {
            Thread {
                Glide.get(this).clearDiskCache()
            }.start()
            Glide.get(this).clearMemory()
            Toast.makeText(this, "Cache limpo", Toast.LENGTH_SHORT).show()
        }

        // -------- SOBRE O APLICATIVO (OPCIONAL) --------
        cardAbout?.setOnClickListener {
            val msg = "VLTV PLAY\nVersão 1.0.0\nDesenvolvido por VLTV."
            AlertDialog.Builder(this)
                .setTitle("Sobre o aplicativo")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }

        // -------- SAIR DA CONTA (OPCIONAL) --------
        cardLogout?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sair")
                .setMessage("Deseja realmente sair e desconectar?")
                .setPositiveButton("Sim") { _, _ ->
                    val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()

                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    // ✅ LÓGICA PARA CARREGAR OS PERFIS IGUAL À REFERÊNCIA
    private fun setupProfilesHeader() {
        rvProfiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        lifecycleScope.launch(Dispatchers.IO) {
            // ✅ ATUALIZADO: Usando streamDao() conforme seu arquivo AppDatabase.kt
            val profiles = database.streamDao().getAllProfiles()
            withContext(Dispatchers.Main) {
                rvProfiles.adapter = SettingsProfileAdapter(profiles) { selected ->
                    trocarPerfilRapido(selected)
                }
            }
        }
    }

    // ✅ LÓGICA DE TROCA RÁPIDA (SALVA NA CADERNETA E REINICIA)
    private fun trocarPerfilRapido(profile: ProfileEntity) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_profile_name", profile.name)
            putString("last_profile_icon", profile.imageUrl)
            apply()
        }
        
        Toast.makeText(this, "Perfil alterado para: ${profile.name}", Toast.LENGTH_SHORT).show()
        
        // Reinicia a Home para aplicar a nova foto/nome no rodapé
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // ✅ ADAPTER INTERNO PARA OS CÍRCULOS DE PERFIL
    inner class SettingsProfileAdapter(
        private val list: List<ProfileEntity>,
        private val onClick: (ProfileEntity) -> Unit
    ) : RecyclerView.Adapter<SettingsProfileAdapter.ProfileVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile_settings, parent, false)
            return ProfileVH(view)
        }

        override fun onBindViewHolder(holder: ProfileVH, position: Int) {
            val profile = list[position]
            holder.tvName.text = profile.name
            
            // ✅ PROTEÇÃO ANTI-BRANCO: Se falhar ou expirar, carrega ic_profile_default
            Glide.with(this@SettingsActivity)
                .load(profile.imageUrl)
                .placeholder(R.drawable.ic_profile_disney) 
                .error(R.drawable.ic_profile_disney)
                .circleCrop()
                .into(holder.imgProfile)

            // ✅ ATUALIZADO: Destaque com borda usando o XML que criamos
            if (profile.name == currentProfileName) {
                holder.itemView.alpha = 1.0f
                holder.imgProfile.setBackgroundResource(R.drawable.bg_profile_border)
            } else {
                holder.itemView.alpha = 0.6f
                holder.imgProfile.background = null
            }

            holder.itemView.setOnClickListener { onClick(profile) }
        }

        override fun getItemCount() = list.size

        inner class ProfileVH(v: View) : RecyclerView.ViewHolder(v) {
            val imgProfile: ImageView = v.findViewById(R.id.imgProfileItem)
            val tvName: TextView = v.findViewById(R.id.tvProfileNameItem)
        }
    }
}
