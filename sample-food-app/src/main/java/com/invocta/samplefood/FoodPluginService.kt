package com.invocta.samplefood

import com.invocta.pluginsdk.InvoctaPluginService
import com.invocta.pluginsdk.PluginAction
import org.json.JSONArray
import org.json.JSONObject

class FoodPluginService : InvoctaPluginService() {
    private val menu = listOf(
        Triple("burger", "Veg Burger", 149),
        Triple("wrap", "Paneer Wrap", 189),
        Triple("lemonade", "Lemonade", 79)
    )
    private val preferences by lazy { getSharedPreferences("food", MODE_PRIVATE) }

    override fun isCallerAllowed(uid: Int): Boolean =
        preferences.getBoolean("allow_invocta", false) &&
            packageManager.getPackagesForUid(uid)?.contains("com.invocta") == true

    override fun actions() = listOf(
        PluginAction("search_menu", "Find food and drinks on the Sample Food menu.",
            JSONObject("""{"type":"object","properties":{"query":{"type":"string","description":"Food or drink to find"}},"required":["query"]}""")),
        PluginAction("add_to_cart", "Add a menu item to the local cart. This does not place an order.",
            JSONObject("""{"type":"object","properties":{"item_id":{"type":"string","enum":["burger","wrap","lemonade"]},"quantity":{"type":"integer","minimum":1,"maximum":10}},"required":["item_id","quantity"]}""")),
        PluginAction("view_cart", "Show the current Sample Food cart.",
            JSONObject("""{"type":"object","properties":{}}"""))
    )

    override fun executeAction(id: String, input: JSONObject): JSONObject = when (id) {
        "search_menu" -> {
            val query = input.getString("query").trim()
            require(query.isNotEmpty() && query.length <= 80) { "Query must be 1–80 characters" }
            JSONObject().put("items", JSONArray().also { items ->
                menu.filter { it.second.contains(query, ignoreCase = true) }
                    .forEach { (itemId, name, price) ->
                        items.put(JSONObject().put("id", itemId).put("name", name).put("price_inr", price))
                    }
            })
        }
        "add_to_cart" -> {
            val itemId = input.getString("item_id")
            require(menu.any { it.first == itemId }) { "Unknown menu item" }
            val quantity = input.getInt("quantity")
            require(quantity in 1..10) { "Quantity must be 1–10" }
            val cart = JSONObject(preferences.getString("cart", "{}"))
            cart.put(itemId, cart.optInt(itemId) + quantity)
            preferences.edit().putString("cart", cart.toString()).apply()
            JSONObject().put("item_id", itemId).put("quantity_in_cart", cart.getInt(itemId))
                .put("message", "Added to cart. No order was placed.")
        }
        "view_cart" -> JSONObject().put("cart", JSONObject(preferences.getString("cart", "{}")))
        else -> error("Unknown action")
    }
}
