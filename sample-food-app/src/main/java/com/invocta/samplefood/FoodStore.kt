package com.invocta.samplefood

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FoodItem(
    val id: String,
    val name: String,
    val priceInr: Int,
    val description: String,
    val category: String,
    val image: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("price_inr", priceInr)
        .put("description", description).put("category", category)
}

class FoodStore(context: Context) {
    companion object {
        val menu = listOf(
            FoodItem("burger", "Veg Burger", 149, "Vegetable patty, fresh greens and a soft bun", "Food", R.drawable.food_burger),
            FoodItem("wrap", "Paneer Wrap", 189, "Paneer and vegetables wrapped in a soft roti", "Food", R.drawable.food_wrap),
            FoodItem("lemonade", "Mint Lemonade", 79, "Chilled lemonade with fresh mint", "Drinks", R.drawable.food_lemonade)
        )
    }

    private val preferences = context.getSharedPreferences("food", Context.MODE_PRIVATE)

    fun cart(): JSONObject = JSONObject(preferences.getString("cart", "{}") ?: "{}")

    fun setQuantity(itemId: String, quantity: Int): Int {
        require(menu.any { it.id == itemId }) { "Unknown menu item" }
        require(quantity in 0..10) { "Quantity must be 0–10" }
        val cart = cart()
        if (quantity == 0) cart.remove(itemId) else cart.put(itemId, quantity)
        preferences.edit().putString("cart", cart.toString()).apply()
        return quantity
    }

    fun add(itemId: String, quantity: Int): Int {
        require(quantity in 1..10) { "Quantity must be 1–10" }
        return setQuantity(itemId, cart().optInt(itemId) + quantity)
    }

    fun clear() = preferences.edit().remove("cart").apply()

    fun cartResult(): JSONObject {
        val cart = cart()
        val items = JSONArray()
        var total = 0
        menu.forEach { item ->
            val quantity = cart.optInt(item.id)
            if (quantity > 0) {
                val subtotal = item.priceInr * quantity
                total += subtotal
                items.put(item.toJson().put("quantity", quantity).put("subtotal_inr", subtotal))
            }
        }
        return JSONObject().put("cart", cart).put("items", items).put("total_inr", total)
    }
}
