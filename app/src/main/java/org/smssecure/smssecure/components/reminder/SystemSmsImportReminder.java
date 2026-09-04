package org.smssecure.smssecure.components.reminder;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.View.OnClickListener;

import org.smssecure.smssecure.AuthenticationActivity;
import org.smssecure.smssecure.ConversationListActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.service.ApplicationMigrationService;
import org.smssecure.smssecure.domain.security.UnlockSession;

public class SystemSmsImportReminder extends Reminder {

  public SystemSmsImportReminder(final Context context) {
    super(context.getString(R.string.reminder_header_sms_import_title),
          context.getString(R.string.reminder_header_sms_import_text),
          context.getString(R.string.reminder_header_sms_import_button));

    final OnClickListener okListener = new OnClickListener() {
      @Override
      public void onClick(View v) {
        UnlockSession unlockSession = UnlockSession.capture();
        context.startService(ApplicationMigrationService.createMigrationIntent(context, unlockSession));

        Intent nextIntent = new Intent(context, ConversationListActivity.class);
        Intent activityIntent = AuthenticationActivity.createDatabaseMigrationIntent(
          context, nextIntent);
        context.startActivity(activityIntent);
      }
    };
    final OnClickListener cancelListener = new OnClickListener() {
      @Override
      public void onClick(View v) {
        ApplicationMigrationService.setDatabaseImported(context);
      }
    };
    setOkListener(okListener);
    setDismissListener(cancelListener);
  }

  public static boolean isEligible(Context context) {
    return !ApplicationMigrationService.isDatabaseImported(context);
  }
}
