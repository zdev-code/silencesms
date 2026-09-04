package org.smssecure.smssecure.data.contact;

public final class ContactEntry {
  private final long id;
  private final int contactType;
  private final String name;
  private final String number;
  private final int numberType;
  private final String label;

  public ContactEntry(long id, int contactType, String name, String number, int numberType, String label) {
    this.id = id;
    this.contactType = contactType;
    this.name = name;
    this.number = number;
    this.numberType = numberType;
    this.label = label;
  }

  public long getId() { return id; }
  public int getContactType() { return contactType; }
  public String getName() { return name; }
  public String getNumber() { return number; }
  public int getNumberType() { return numberType; }
  public String getLabel() { return label; }
}