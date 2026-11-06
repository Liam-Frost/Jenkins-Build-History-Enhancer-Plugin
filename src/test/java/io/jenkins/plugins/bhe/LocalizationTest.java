package io.jenkins.plugins.bhe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.localizer.LocaleProvider;

/**
 * The plugin speaks English and Simplified Chinese.
 *
 * <p>Any Chinese locale (zh, zh_CN, zh_TW, zh_HK, ...) gets Simplified Chinese; every other locale
 * gets English. That follows from naming the Chinese bundles {@code _zh} rather than {@code _zh_CN}:
 * Stapler and the localizer both strip a locale from the right ({@code zh_TW} → {@code zh} → base)
 * and do not consult the server's default locale once they are given one.
 *
 * <p>Which locale they are given is decided before the plugin runs. A request without
 * Accept-Language gets the server's locale from Jetty, as for the rest of Jenkins; that is
 * deliberate and not tested here. The test harness cannot show it either, since it renders every
 * page in English whatever the request asks for.
 */
class LocalizationTest {

    private static final String PKG = "io/jenkins/plugins/bhe/";

    /** Every bundle pair the plugin ships: the English base and its Chinese translation. */
    private static final String[] BUNDLES = {
        PKG + "Messages",
        PKG + "HistorySidebarWidget/index",
        PKG + "BuildHistoryEnhancerConfiguration/config",
        PKG + "ReuseParametersEntryDecorator/entry",
    };

    @Test
    void chineseBundlesHaveExactlyTheEnglishKeys() throws IOException {
        for (String bundle : BUNDLES) {
            Set<String> english = keys(load(bundle + ".properties"));
            Set<String> chinese = keys(load(bundle + "_zh.properties"));
            assertEquals(english, chinese, bundle + ": English and Chinese keys differ");
        }
    }

    @Test
    void translationsKeepTheirPlaceholders() throws IOException {
        Pattern placeholder = Pattern.compile("\\{\\d+}");
        for (String bundle : BUNDLES) {
            Properties english = load(bundle + ".properties");
            Properties chinese = load(bundle + "_zh.properties");
            for (String key : english.stringPropertyNames()) {
                assertEquals(
                        found(placeholder, english.getProperty(key)),
                        found(placeholder, chinese.getProperty(key)),
                        bundle + "." + key + ": placeholders differ between languages");
            }
        }
    }

    @Test
    void chineseBundlesAreActuallyTranslated() throws IOException {
        for (String bundle : BUNDLES) {
            Properties chinese = load(bundle + "_zh.properties");
            for (String key : chinese.stringPropertyNames()) {
                String value = chinese.getProperty(key);
                assertFalse(value.isBlank(), bundle + "." + key + " is empty");
                assertTrue(hasHan(value), bundle + "." + key + " is not translated: " + value);
            }
        }
    }

    @Test
    void noRegionSpecificChineseBundles() {
        // A _zh_CN bundle would hide _zh for mainland users and leave zh_TW users on English.
        for (String bundle : BUNDLES) {
            for (String region : new String[] {"_zh_CN", "_zh_TW", "_zh_HK", "_zh_SG"}) {
                String path = bundle + region + ".properties";
                assertEquals(null, LocalizationTest.class.getClassLoader().getResource(path), path + " exists");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "zh-CN", "zh-TW", "zh-HK", "zh-SG", "zh-Hans-CN", "zh-Hant-TW"})
    void everyChineseLocaleGetsSimplifiedChinese(String tag) {
        assertEquals("定时触发", messageIn(Locale.forLanguageTag(tag)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "en-US", "en-GB", "ja-JP", "ko-KR", "de-DE", "fr-FR", "ru-RU", "es"})
    void everyOtherLocaleGetsEnglish(String tag) {
        assertEquals("Started by timer", messageIn(Locale.forLanguageTag(tag)));
    }

    @Test
    void baseBundleIsEnglish() {
        assertEquals("Started by timer", messageIn(Locale.ROOT));
    }

    /**
     * The Jelly views (sidebar controls, settings page) are resolved by Stapler, not by the
     * localizer. This uses Stapler's own bundle class, so it checks the same lookup a page render
     * does once the request's locale is known.
     */
    @ParameterizedTest
    @ValueSource(strings = {"zh", "zh-CN", "zh-TW", "zh-HK", "zh-Hant-TW"})
    void sidebarAndSettingsUseChineseForEveryChineseLocale(String tag) {
        Locale locale = Locale.forLanguageTag(tag);
        assertEquals("仅我的构建", sidebar().getFormatString(locale, "onlyMine"));
        assertEquals("增强构建历史侧边栏", settings().getFormatString(locale, "enabled.title"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "en-US", "ja-JP", "ko-KR", "de-DE", "fr-FR"})
    void sidebarAndSettingsUseEnglishForEveryOtherLocale(String tag) {
        Locale locale = Locale.forLanguageTag(tag);
        assertEquals("Only my builds", sidebar().getFormatString(locale, "onlyMine"));
        assertEquals("Enhance build history sidebar", settings().getFormatString(locale, "enabled.title"));
    }

    @Test
    void sidebarBaseBundleIsEnglish() {
        assertEquals("Only my builds", sidebar().getFormatString(Locale.ROOT, "onlyMine"));
    }

    private static org.kohsuke.stapler.jelly.ResourceBundle sidebar() {
        return bundle("HistorySidebarWidget/index");
    }

    private static org.kohsuke.stapler.jelly.ResourceBundle settings() {
        return bundle("BuildHistoryEnhancerConfiguration/config");
    }

    /** Stapler resolves a view's bundle from the view's own URL, without the extension. */
    private static org.kohsuke.stapler.jelly.ResourceBundle bundle(String view) {
        java.net.URL jelly = LocalizationTest.class.getClassLoader().getResource(PKG + view + ".jelly");
        assertTrue(jelly != null, "missing view " + view);
        String base = jelly.toExternalForm();
        return new org.kohsuke.stapler.jelly.ResourceBundle(base.substring(0, base.length() - ".jelly".length()));
    }

    @Test
    void serverDefaultLocaleDoesNotLeakIn() {
        // A Chinese server must still answer an English-speaking browser in English.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            assertEquals("Started by timer", messageIn(Locale.US));
        } finally {
            Locale.setDefault(saved);
        }
    }

    private static String messageIn(Locale locale) {
        LocaleProvider saved = LocaleProvider.getProvider();
        try {
            LocaleProvider.setProvider(new LocaleProvider() {
                @Override
                public Locale get() {
                    return locale;
                }
            });
            return Messages.initiator_timer();
        } finally {
            LocaleProvider.setProvider(saved);
        }
    }

    private static Properties load(String path) throws IOException {
        InputStream in = LocalizationTest.class.getClassLoader().getResourceAsStream(path);
        assertTrue(in != null, "missing bundle " + path);
        // Jenkins reads plugin .properties files as UTF-8.
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        }
    }

    private static Set<String> keys(Properties properties) {
        return new TreeSet<>(properties.stringPropertyNames());
    }

    private static Set<String> found(Pattern pattern, String text) {
        Set<String> result = new TreeSet<>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static boolean hasHan(String text) {
        return text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }
}
