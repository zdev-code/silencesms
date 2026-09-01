package org.smssecure.smssecure;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemBarThemeContractTest {

  private static final Path APP_SOURCE = Paths.get("src", "main");

  @Test
  public void runtimeIconsContrastWithPaintedBarColor() {
    assertThat(BaseActionBarActivity.hasLightBackground(0xffffffff)).isTrue();
    assertThat(BaseActionBarActivity.hasLightBackground(0xff000000)).isFalse();
    assertThat(BaseActionBarActivity.hasLightBackground(0xff1c7ac5)).isFalse();
    assertThat(BaseActionBarActivity.hasLightBackground(0x00000000)).isFalse();
  }

  @Test
  public void normalLightThemesUseDarkIconsForWhiteStartupWindow() throws Exception {
    Document themes = parse("res/values/themes.xml");

    assertThat(itemValue(themes, "Silence.LightTheme", "android:windowLightStatusBar"))
        .isEqualTo("true");
    assertThat(itemValue(themes, "Silence.LightNoActionBar", "android:windowLightStatusBar"))
        .isEqualTo("true");
  }

  @Test
  public void lightWelcomeThemeUsesDarkIconsOverLightStatusBar() throws Exception {
    Document themes = parse("res/values-v23/themes.xml");

    assertThat(itemValue(themes, "Silence.LightWelcomeTheme", "android:windowLightStatusBar"))
        .isEqualTo("true");
  }

  @Test
  public void routingThemesDoNotDrawLightPreviewBeforeDynamicThemeSelection() throws Exception {
    Document themes = parse("res/values/themes.xml");

    assertThat(itemValue(themes, "Silence.RoutingTheme", "android:windowDisablePreview"))
        .isEqualTo("true");
    assertThat(itemValue(themes, "Silence.LightIntroTheme", "android:windowDisablePreview"))
        .isEqualTo("true");
    assertThat(itemValue(themes, "Silence.DarkIntroTheme", "android:windowDisablePreview"))
        .isEqualTo("true");
    assertThat(itemValue(themes, "Silence.RoutingTheme", "android:windowAnimationStyle"))
        .isEqualTo("@null");
    assertThat(itemValue(themes, "Silence.LightIntroTheme", "android:windowAnimationStyle"))
        .isEqualTo("@null");
    assertThat(itemValue(themes, "Silence.DarkIntroTheme", "android:windowAnimationStyle"))
        .isEqualTo("@null");
  }

  @Test
  public void androidTwelveRoutingSplashFollowsSystemModeAndIsIconless() throws Exception {
    Document lightThemes = parse("res/values-v31/themes.xml");
    Document darkThemes = parse("res/values-night-v31/themes.xml");

    assertThat(itemValue(lightThemes, "Silence.RoutingTheme", "android:windowSplashScreenBackground"))
        .isEqualTo("@color/gray5");
    assertThat(itemValue(darkThemes, "Silence.RoutingTheme", "android:windowSplashScreenBackground"))
        .isEqualTo("@color/black");
    assertThat(itemValue(lightThemes, "Silence.RoutingTheme", "android:windowSplashScreenAnimatedIcon"))
        .isEqualTo("@android:color/transparent");
    assertThat(itemValue(darkThemes, "Silence.RoutingTheme", "android:windowSplashScreenAnimatedIcon"))
        .isEqualTo("@android:color/transparent");
    assertThat(itemValue(lightThemes, "Silence.RoutingTheme", "android:windowLightStatusBar"))
        .isEqualTo("true");
    assertThat(itemValue(darkThemes, "Silence.RoutingTheme", "android:windowLightStatusBar"))
        .isEqualTo("false");

    String manifest = new String(Files.readAllBytes(APP_SOURCE.resolve("AndroidManifest.xml")),
                                 StandardCharsets.UTF_8);
    assertThat(manifest).contains("<activity android:name=\".ConversationListActivity\"")
                        .contains("android:theme=\"@style/Silence.RoutingTheme\"");
  }

  @Test
  public void systemThemeIsTheDefaultAppearancePreference() throws Exception {
    Document arrays = parse("res/values/arrays.xml");
    Document preferences = parse("res/xml/preferences_appearance.xml");

    assertThat(stringArrayValues(arrays, "pref_theme_values"))
        .isEqualTo(Arrays.asList("system", "light", "dark"));
    assertThat(preferences.getElementsByTagName("org.smssecure.smssecure.preferences.widgets.SilenceListPreference")
                          .item(0).getAttributes().getNamedItem("android:defaultValue").getNodeValue())
        .isEqualTo("system");
  }

  private static Document parse(String path) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(APP_SOURCE.resolve(path).toFile());
  }

  private static String itemValue(Document document, String styleName, String itemName) {
    NodeList styles = document.getElementsByTagName("style");
    for (int styleIndex = 0; styleIndex < styles.getLength(); styleIndex++) {
      Element style = (Element) styles.item(styleIndex);
      if (!styleName.equals(style.getAttribute("name"))) continue;

      NodeList children = style.getChildNodes();
      for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
        Node child = children.item(childIndex);
        if (child instanceof Element) {
          Element item = (Element) child;
          if ("item".equals(item.getTagName()) && itemName.equals(item.getAttribute("name"))) {
            return item.getTextContent().trim();
          }
        }
      }
    }
    return null;
  }

  private static List<String> stringArrayValues(Document document, String arrayName) {
    NodeList arrays = document.getElementsByTagName("string-array");
    for (int arrayIndex = 0; arrayIndex < arrays.getLength(); arrayIndex++) {
      Element array = (Element) arrays.item(arrayIndex);
      if (!arrayName.equals(array.getAttribute("name"))) continue;

      List<String> values = new ArrayList<>();
      NodeList items = array.getElementsByTagName("item");
      for (int itemIndex = 0; itemIndex < items.getLength(); itemIndex++) {
        values.add(items.item(itemIndex).getTextContent().trim());
      }
      return values;
    }
    return null;
  }
}
