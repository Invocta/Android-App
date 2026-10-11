package com.invocta.samplefood

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val store by lazy { FoodStore(this) }
    private val preferences by lazy { getSharedPreferences("food", MODE_PRIVATE) }
    private lateinit var cartItems: LinearLayout
    private val cartListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "cart") refreshCart()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(45, 38, 34))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(2).toFloat()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun refreshCart() {
        if (!::cartItems.isInitialized) return
        cartItems.removeAllViews()
        val cart = store.cart()
        val selected = FoodStore.menu.filter { cart.optInt(it.id) > 0 }
        if (selected.isEmpty()) cartItems.addView(label("Your cart is empty", 16f))
        selected.forEach { item ->
            val quantity = cart.optInt(item.id)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label("${item.name}\n₹${item.priceInr * quantity}", 16f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("−") { store.setQuantity(item.id, quantity - 1); refreshCart() }.apply {
                contentDescription = "Remove one ${item.name}"
            })
            row.addView(label("$quantity", 16f, true).apply { setPadding(dp(8), dp(12), dp(8), 0) })
            row.addView(button("+") { store.setQuantity(item.id, quantity + 1); refreshCart() }.apply {
                contentDescription = "Add one ${item.name}"
                isEnabled = quantity < 10
            })
            cartItems.addView(row)
        }
        if (selected.isNotEmpty()) {
            cartItems.addView(label("Total  ₹${store.cartResult().getInt("total_inr")}", 19f, true))
            cartItems.addView(button("Clear cart") { store.clear(); refreshCart() })
        }
    }

    override fun onResume() {
        super.onResume()
        preferences.registerOnSharedPreferenceChangeListener(cartListener)
        refreshCart()
    }

    override fun onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(cartListener)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            setBackgroundColor(Color.rgb(250, 246, 239))
        }
        root.addView(label("Sample Food", 30f, true))
        root.addView(label("Browse the menu and build a local cart", 16f))
        root.addView(label("Menu", 23f, true).apply { setPadding(0, dp(28), 0, dp(12)) })

        FoodStore.menu.forEach { item ->
            val itemCard = card()
            itemCard.addView(ImageView(this).apply {
                setImageResource(item.image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = item.name
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))
            itemCard.addView(label("${item.name}  ·  ₹${item.priceInr}", 20f, true).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            itemCard.addView(label(item.description, 15f))
            itemCard.addView(button("Add to cart") {
                try {
                    store.add(item.id, 1)
                    refreshCart()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(this, "Maximum 10 per item", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(itemCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        }

        root.addView(label("Your cart", 23f, true).apply { setPadding(0, dp(10), 0, dp(12)) })
        cartItems = card()
        root.addView(cartItems)
        root.addView(label("Demo cart only — no checkout or payment", 13f).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        root.addView(Switch(this).apply {
            text = "Allow Invocta to use this menu and edit my cart"
            textSize = 16f
            isChecked = preferences.getBoolean("allow_invocta", false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("allow_invocta", enabled).apply()
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        refreshCart()
    }
}
