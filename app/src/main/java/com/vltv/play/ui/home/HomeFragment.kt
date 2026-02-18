package com.vltv.play.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.vltv.play.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    // O Binding ajuda o app a carregar os componentes do XML de forma instantânea
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Aqui ele infla o desenho Premium que criamos (layout-port/fragment_home.xml)
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Por enquanto, vamos apenas deixar a tela carregar.
        // É aqui que, no futuro, colocaremos a lógica do Banner Infinito 
        // e o carregamento das listas de filmes do seu servidor.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
