package org.smssecure.smssecure;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemBarThemeContractTest {

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

  private static Document parse(String path) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(path));
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
}
