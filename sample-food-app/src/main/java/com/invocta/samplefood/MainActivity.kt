package com.invocta.samplefood

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var cartView: TextView

    private fun refreshCart() {
        val preferences = getSharedPreferences("food", MODE_PRIVATE)
        cartView.text = "\nCart: " + JSONObject(preferences.getString("cart", "{}")).toString()
    }

    override fun onResume() {
        super.onResume()
        if (::cartView.isInitialized) refreshCart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("food", MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 32)
        }
        val title = TextView(this).apply { text = "Sample Food"; textSize = 28f }
        val menu = TextView(this).apply {
            text = "\nMenu\nVeg Burger · ₹149\nPaneer Wrap · ₹189\nLemonade · ₹79\n"
            textSize = 18f
        }
        val consent = Switch(this).apply {
            text = "Allow Invocta to search the menu and edit this app's cart"
            isChecked = preferences.getBoolean("allow_invocta", false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("allow_invocta", enabled).apply()
            }
        }
        cartView = TextView(this).apply { textSize = 18f }
        refreshCart()
        container.addView(title)
        container.addView(menu)
        container.addView(consent)
        container.addView(cartView)
        container.setOnClickListener { refreshCart() }
        setContentView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
