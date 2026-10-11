package com.invocta.samplefood

import com.invocta.pluginsdk.InvoctaPluginService
import com.invocta.pluginsdk.PluginAction
import org.json.JSONArray
import org.json.JSONObject

class FoodPluginService : InvoctaPluginService() {
    private val store by lazy { FoodStore(this) }
    private val preferences by lazy { getSharedPreferences("food", MODE_PRIVATE) }

    override fun isCallerAllowed(uid: Int): Boolean =
        preferences.getBoolean("allow_invocta", false) &&
            packageManager.getPackagesForUid(uid)?.contains("com.invocta") == true

    override fun actions() = listOf(
        PluginAction("list_menu", "List all Sample Food menu items with prices and descriptions.",
            JSONObject("""{"type":"object","properties":{}}""")),
        PluginAction("search_menu", "Find Sample Food menu items by name or description.",
            JSONObject("""{"type":"object","properties":{"query":{"type":"string","description":"Food or drink to find"}},"required":["query"]}""")),
        PluginAction("get_item_details", "Get the price and description of a Sample Food item.",
            JSONObject("""{"type":"object","properties":{"item_id":{"type":"string","enum":["burger","wrap","lemonade"]}},"required":["item_id"]}""")),
        PluginAction("add_to_cart", "Add a menu item to the local cart. This does not place an order.",
            JSONObject("""{"type":"object","properties":{"item_id":{"type":"string","enum":["burger","wrap","lemonade"]},"quantity":{"type":"integer","minimum":1,"maximum":10}},"required":["item_id","quantity"]}""")),
        PluginAction("set_cart_item_quantity", "Set an item's cart quantity; use zero to remove it. No order is placed.",
            JSONObject("""{"type":"object","properties":{"item_id":{"type":"string","enum":["burger","wrap","lemonade"]},"quantity":{"type":"integer","minimum":0,"maximum":10}},"required":["item_id","quantity"]}""")),
        PluginAction("view_cart", "Show Sample Food cart items and total.",
            JSONObject("""{"type":"object","properties":{}}""")),
        PluginAction("clear_cart", "Remove all items from the local Sample Food cart.",
            JSONObject("""{"type":"object","properties":{}}"""))
    )

    override fun executeAction(id: String, input: JSONObject): JSONObject = when (id) {
        "list_menu" -> JSONObject().put("items", JSONArray().also { items ->
            FoodStore.menu.forEach { items.put(it.toJson()) }
        })
        "search_menu" -> {
            val query = input.getString("query").trim()
            require(query.isNotEmpty() && query.length <= 80) { "Query must be 1–80 characters" }
            JSONObject().put("items", JSONArray().also { items ->
                FoodStore.menu.filter {
                    it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
                }.forEach { items.put(it.toJson()) }
            })
        }
        "get_item_details" -> FoodStore.menu.firstOrNull { it.id == input.getString("item_id") }
            ?.toJson() ?: error("Unknown menu item")
        "add_to_cart" -> {
            val itemId = input.getString("item_id")
            JSONObject().put("item_id", itemId).put("quantity_in_cart", store.add(itemId, input.getInt("quantity")))
                .put("message", "Added to cart. No order was placed.")
        }
        "set_cart_item_quantity" -> {
            val itemId = input.getString("item_id")
            JSONObject().put("item_id", itemId)
                .put("quantity_in_cart", store.setQuantity(itemId, input.getInt("quantity")))
                .put("message", "Cart updated. No order was placed.")
        }
        "view_cart" -> store.cartResult()
        "clear_cart" -> {
            store.clear()
            store.cartResult().put("message", "Cart cleared. No order was placed.")
        }
        else -> error("Unknown action")
    }
}
