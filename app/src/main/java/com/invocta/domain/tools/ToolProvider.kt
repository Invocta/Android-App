package com.invocta.domain.tools

import android.content.Context
import com.invocta.domain.tools.impl.*

/**
 * Provides and configures all available tools for the AI agent
 */
object ToolProvider {

    /**
     * Create a ToolRegistry with all available tools initialized
     */
    fun createToolRegistry(context: Context): ToolRegistry {
        return ToolRegistry(context).apply {
            // Device information tools
            register(GetCurrentTimeTool())
            register(BatteryInfoTool(context))

            // Utility tools
            register(CalculatorTool())
            register(FlashlightTool(context))

            // Communication tools
            register(MakeCallTool(context))
            register(SendSmsTool(context))

            // App & System tools
            register(OpenAppTool(context))
            register(DiscoverAppActionsTool(context))
            register(CallAppActionTool(context))
            register(SetAlarmTool(context))
            register(SetTimerTool(context))
            register(WebSearchTool(context))

            // Screen control tools (requires Accessibility Service)
            register(ScreenControlStatusTool(context))
            register(OpenAccessibilitySettingsTool(context))
            register(ReadScreenTool())
            register(ReadScreenVisionTool()) // Vision-based screen reading for web/canvas content
            register(FindOnScreenTool())
            register(ClickElementTool())
            register(ToggleSwitchTool())
            register(SetSeekbarTool())
            register(InputTextTool())
            register(PressKeyTool())
            register(ScrollScreenTool())
            register(SwipeGestureTool())
            register(NavigationTool())
        }
    }

    /**
     * Get tool descriptions for display to user
     */
    fun getToolDescriptions(registry: ToolRegistry): List<Pair<String, String>> {
        return registry.getAllTools().map { tool ->
            tool.name to tool.description
        }
    }
}
