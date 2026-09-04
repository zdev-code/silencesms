package org.smssecure.smssecure.ui.importexport;

public final class ImportExportUiState {
  public enum Operation { IMPORT_PLAINTEXT, IMPORT_ENCRYPTED, EXPORT }

  private final boolean running;
  private final int titleResource;
  private final int messageResource;
  private final Operation completedOperation;
  private final Integer result;

  ImportExportUiState(boolean running, int titleResource, int messageResource,
                      Operation completedOperation, Integer result) {
    this.running = running;
    this.titleResource = titleResource;
    this.messageResource = messageResource;
    this.completedOperation = completedOperation;
    this.result = result;
  }

  public boolean isRunning() { return running; }
  public int getTitleResource() { return titleResource; }
  public int getMessageResource() { return messageResource; }
  public Operation getCompletedOperation() { return completedOperation; }
  public Integer getResult() { return result; }
}