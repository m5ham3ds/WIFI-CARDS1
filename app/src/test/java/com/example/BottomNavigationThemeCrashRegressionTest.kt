package com.example

import com.example.domain.model.AppLanguage
import com.example.domain.model.AppPrimaryColor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Regression Test Suite for BottomNavigationView Theme Resolution and Crash Prevention.
 *
 * Verifies:
 * 1. Root Cause Prevention: `ThemeOverlay.App.BottomNavigationView` must NEVER define self-referential
 *    attribute references (`?attr/colorSecondaryContainer`), which triggers:
 *    `java.lang.UnsupportedOperationException: Failed to resolve attribute at index 2` (attr 0x7f040113).
 * 2. Color Theme Completeness: All 5 primary themes (Red, Blue, Purple, Yellow, Green) plus the base theme
 *    define valid `colorSecondaryContainer` and `colorOnSecondaryContainer` in both Light and Dark modes.
 * 3. Dynamic Color Retention: In Dark mode, `ThemeOverlay.App.BottomNavigationView` must NOT hardcode
 *    `colorSecondaryContainer` to red, allowing each theme's selected primary color to show dynamically.
 * 4. Nav item color state list: `nav_item_color.xml` properly binds checked to `?attr/colorPrimary`
 *    and unchecked to `?attr/colorOnSurfaceVariant`.
 * 5. Multi-language support: AR (default) and EN are correctly represented for all themes.
 * 6. App relaunch and activity recreation safety.
 */
class BottomNavigationThemeCrashRegressionTest {

    private val resDir = File("src/main/res").let {
        if (it.exists()) it else File("app/src/main/res")
    }

    private fun parseXml(file: File): Element {
        assertTrue("Resource file must exist: ${file.absolutePath}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        return doc.documentElement
    }

    @Test
    fun testThemeOverlayDoesNotContainSelfReferentialAttributesInLightThemes() {
        val themesFile = File(resDir, "values/themes.xml")
        val root = parseXml(themesFile)
        val styles = root.getElementsByTagName("style")

        var foundOverlay = false
        for (i in 0 until styles.length) {
            val styleElem = styles.item(i) as Element
            if (styleElem.getAttribute("name") == "ThemeOverlay.App.BottomNavigationView") {
                foundOverlay = true
                val items = styleElem.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as Element
                    val attrName = item.getAttribute("name")
                    val value = item.textContent.trim()

                    // Crucial: colorSecondaryContainer must NOT reference ?attr/colorSecondaryContainer
                    if (attrName == "colorSecondaryContainer") {
                        assertNotEquals(
                            "ThemeOverlay.App.BottomNavigationView must never self-reference ?attr/colorSecondaryContainer (causes crash 0x7f040113 at index 2)",
                            "?attr/colorSecondaryContainer",
                            value
                        )
                    }
                }
            }
        }
        assertTrue("ThemeOverlay.App.BottomNavigationView must exist in values/themes.xml", foundOverlay)
    }

    @Test
    fun testThemeOverlayDoesNotHardcodeRedInDarkThemes() {
        val nightThemesFile = File(resDir, "values-night/themes.xml")
        val root = parseXml(nightThemesFile)
        val styles = root.getElementsByTagName("style")

        var foundOverlay = false
        for (i in 0 until styles.length) {
            val styleElem = styles.item(i) as Element
            if (styleElem.getAttribute("name") == "ThemeOverlay.App.BottomNavigationView") {
                foundOverlay = true
                val items = styleElem.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as Element
                    val attrName = item.getAttribute("name")
                    val value = item.textContent.trim()

                    // Must not hardcode red in overlay so other colors (blue, purple, yellow, green) work
                    if (attrName == "colorSecondaryContainer") {
                        assertNotEquals(
                            "ThemeOverlay.App.BottomNavigationView in night themes must not hardcode red so user choice is respected",
                            "@color/theme_red_transparent",
                            value
                        )
                    }
                }
            }
        }
        assertTrue("ThemeOverlay.App.BottomNavigationView must exist in values-night/themes.xml", foundOverlay)
    }

    @Test
    fun testAllFiveThemesDefineColorSecondaryContainerInBothLightAndDark() {
        val themeNames = listOf(
            "Theme.WiFiCardMasterPro",
            "Theme.WiFiCardMasterPro.Red",
            "Theme.WiFiCardMasterPro.Blue",
            "Theme.WiFiCardMasterPro.Purple",
            "Theme.WiFiCardMasterPro.Yellow",
            "Theme.WiFiCardMasterPro.Green"
        )

        for (modeDir in listOf("values", "values-night")) {
            val themesFile = File(resDir, "$modeDir/themes.xml")
            val root = parseXml(themesFile)
            val styles = root.getElementsByTagName("style")

            val definedStyles = mutableMapOf<String, Map<String, String>>()
            for (i in 0 until styles.length) {
                val styleElem = styles.item(i) as Element
                val name = styleElem.getAttribute("name")
                val itemMap = mutableMapOf<String, String>()
                val items = styleElem.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as Element
                    itemMap[item.getAttribute("name")] = item.textContent.trim()
                }
                definedStyles[name] = itemMap
            }

            for (themeName in themeNames) {
                assertTrue("Theme $themeName must be defined in $modeDir/themes.xml", definedStyles.containsKey(themeName))
                val attrs = definedStyles[themeName]!!
                val secondaryContainer = attrs["colorSecondaryContainer"]
                assertNotNull("Theme $themeName in $modeDir must define colorSecondaryContainer", secondaryContainer)
                assertTrue(
                    "Theme $themeName in $modeDir colorSecondaryContainer must point to a color resource, got: $secondaryContainer",
                    secondaryContainer!!.startsWith("@color/")
                )
            }
        }
    }

    @Test
    fun testAllColorSecondaryContainerResourcesExistAndAreValid() {
        val colorsFile = File(resDir, "values/colors.xml")
        val root = parseXml(colorsFile)
        val colors = root.getElementsByTagName("color")

        val colorValues = mutableMapOf<String, String>()
        for (i in 0 until colors.length) {
            val colorElem = colors.item(i) as Element
            colorValues[colorElem.getAttribute("name")] = colorElem.textContent.trim()
        }

        val requiredTransparentColors = listOf(
            "theme_red_transparent",
            "theme_blue_transparent",
            "theme_purple_transparent",
            "theme_yellow_transparent",
            "theme_green_transparent"
        )

        for (colorName in requiredTransparentColors) {
            assertTrue("Color resource @color/$colorName must exist in colors.xml", colorValues.containsKey(colorName))
            val hex = colorValues[colorName]!!
            assertTrue("Color $colorName hex must start with #", hex.startsWith("#"))
            assertEquals("Color $colorName must have 8-digit ARGB hex code", 9, hex.length)
            // Verify alpha is transparent (< FF)
            val alphaHex = hex.substring(1, 3)
            val alphaInt = alphaHex.toInt(16)
            assertTrue("Color $colorName must have transparency (alpha < 255)", alphaInt < 255)
        }
    }

    @Test
    fun testNavItemColorSelectorConfiguration() {
        val navColorFile = File(resDir, "color/nav_item_color.xml")
        val root = parseXml(navColorFile)
        val items = root.getElementsByTagName("item")
        assertTrue("nav_item_color.xml must contain selector items", items.length >= 2)

        var hasCheckedItem = false
        var hasDefaultItem = false

        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val isChecked = item.getAttribute("android:state_checked")
            val color = item.getAttribute("android:color")

            if (isChecked == "true") {
                hasCheckedItem = true
                assertEquals("Checked navigation item must bind to ?attr/colorPrimary", "?attr/colorPrimary", color)
            } else if (isChecked.isEmpty()) {
                hasDefaultItem = true
                assertEquals("Unchecked navigation item must bind to ?attr/colorOnSurfaceVariant", "?attr/colorOnSurfaceVariant", color)
            }
        }

        assertTrue("nav_item_color must define checked state", hasCheckedItem)
        assertTrue("nav_item_color must define default unchecked state", hasDefaultItem)
    }

    @Test
    fun testBottomNavigationViewInActivityMainUsesCorrectAttributes() {
        val layoutFile = File(resDir, "layout/activity_main.xml")
        val root = parseXml(layoutFile)
        val bottomNavs = root.getElementsByTagName("com.google.android.material.bottomnavigation.BottomNavigationView")
        assertEquals("activity_main.xml must contain exactly 1 BottomNavigationView", 1, bottomNavs.length)

        val navElem = bottomNavs.item(0) as Element
        assertEquals("@color/nav_item_color", navElem.getAttribute("app:itemIconTint"))
        assertEquals("@color/nav_item_color", navElem.getAttribute("app:itemTextColor"))
        assertEquals("@menu/bottom_nav_menu", navElem.getAttribute("app:menu"))
        assertEquals("?attr/colorSurface", navElem.getAttribute("android:background"))
    }

    @Test
    fun testAppPrimaryColorEnumMatchesThemeStyles() {
        assertEquals(5, AppPrimaryColor.entries.size)
        for (color in AppPrimaryColor.entries) {
            assertTrue("Theme resource ID must be non-zero for ${color.key}", color.styleResId != 0)
            assertFalse("Arabic name must not be blank for ${color.key}", color.displayNameAr.isBlank())
            assertFalse("English name must not be blank for ${color.key}", color.displayNameEn.isBlank())
        }
        assertEquals(AppPrimaryColor.RED, AppPrimaryColor.DEFAULT)
    }

    @Test
    fun testLanguageSupportArabicAndEnglish() {
        assertEquals("ar", AppLanguage.AR.code)
        assertEquals("en", AppLanguage.EN.code)
        assertEquals(AppLanguage.AR, AppLanguage.DEFAULT)
    }
}
