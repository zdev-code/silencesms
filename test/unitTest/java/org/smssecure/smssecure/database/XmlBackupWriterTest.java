package org.smssecure.smssecure.database;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class XmlBackupWriterTest {

  @Test
  public void writesBackupToOutputStream() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    XmlBackup.Writer writer = new XmlBackup.Writer(outputStream, 1);

    writer.writeItem(new XmlBackup.XmlBackupItem(0, null, 1234, 1, null, null, null, 1, 0));
    writer.close();

    String lineSeparator = System.lineSeparator();
    assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>" + lineSeparator +
                 "<!-- File Created By Silence -->" + lineSeparator +
                 "<smses count=\"1\">" + lineSeparator +
                 " <sms protocol=\"0\" address=\"null\" date=\"1234\" type=\"1\" subject=\"null\" " +
                 "body=\"null\" toa=\"null\" sc_toa=\"null\" service_center=\"null\" read=\"1\" " +
                 "status=\"0\" locked=\"0\" />" + lineSeparator +
                 "</smses>",
                 outputStream.toString(StandardCharsets.UTF_8.name()));
  }
}