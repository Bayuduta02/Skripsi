package com.example.skripsi

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class HomeActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Inisialisasi ViewPager2
        viewPager = findViewById(R.id.viewPager)

        // Daftar gambar yang akan ditampilkan
        val imageList = listOf(
            R.drawable.gambar1, // Ganti dengan resource gambar Anda
            R.drawable.gambar2,
            R.drawable.gambar3,
            R.drawable.gambar4,
        )

        // Set adapter untuk ViewPager2
        val adapter = ImagePagerAdapter(imageList)
        viewPager.adapter = adapter

        // Buat Handler dan Runnable untuk slide otomatis
        handler = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                val currentItem = viewPager.currentItem
                val nextItem = if (currentItem == imageList.size - 1) 0 else currentItem + 1
                viewPager.setCurrentItem(nextItem, true)
                handler.postDelayed(this, 3000) // Slide setiap 3 detik
            }
        }

        // Mulai slide otomatis
        handler.postDelayed(runnable, 3000)

        val Panduan = findViewById<View>(R.id.panduan)
        Panduan.setOnClickListener {
            val intent = Intent(this, PanduanHomeActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Hentikan Handler saat Activity dihancurkan
        handler.removeCallbacks(runnable)
    }
}